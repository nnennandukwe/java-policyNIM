"""Derive profile expectations from the published generation, topic manifests and engineering fixture."""

import hashlib
import json
import re
import tomllib

from archived_pack import prepare_aggregate, verify_digest

SHIPPED = ("sha256:862df46d965e63c57b010543815cfb4eb470bf60474db8c758f3af358de76159",
           "sha256:f79f74dbf4651ac20c27e0b5316f3028c341fcacc4339a0927dd590281030930")
BUILTIN = ("sha256:1f3e011f97cbb4fd36dcf03f28d4d0e2a3c9968f53d179cb368d6df7c1ee4ecd",
           "sha256:19296547678340e555850c35725797ca5b9d601778e4a5eb125f7e6882dbb9a5")
REVERSED = ("sha256:176aa8c305ea8105dbcd68875670ddad505411a866cfcdd894d4bfe8a5f9bc26",
            "sha256:03d6858f1648737f0b8b04f3d2a28fd8fe2a88672d366c07454dc0faa8a561de")


def compact(value):
    """Serialize public fields in model order for the shared UTF-8 digest."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def digest(data):
    """Return a content digest with the CLI's algorithm prefix."""
    return "sha256:" + hashlib.sha256(data).hexdigest()


def ordered(data, fields):
    """Recover the published field order from an alphabetically keyed archive record."""
    return {key: data[key] for key in fields.split()}


def restore_entry(stored):
    """Restore model serialization order throughout an archived record without changing its values."""
    entry = ordered(stored, "state target_digest reason replacement_id path policy body")
    policy = ordered(entry["policy"], "policy_id title doc_type domain tags grounded_in provenance")
    prov = ordered(policy["provenance"],
                   "content_digest pack_id pack_version requirement_level applicability grounding_kind sources")
    prov["sources"] = [ordered(src, "title url revision sections retrieved_on license attribution change_notice")
                       for src in prov["sources"]]
    policy["provenance"] = prov
    entry["policy"] = policy
    return entry


def shipped_entries(reference, root):
    """Reconstruct and verify all topic entries from their archived metadata and original bytes."""
    aggregate = prepare_aggregate(reference, root)
    archived = {entry["policy"]["policy_id"]: entry for entry in aggregate["policies"]}
    profile = root / "profiles/full-engineering.toml"
    raw = profile.read_bytes()
    verify_digest(raw, "sha256:62823f454afbbf8135e383de1ca89b504b3b74877a2a80dae18a952dd9e239bc", "Shipped profile")
    text = raw.decode("utf-8")
    entries = []
    for pin in tomllib.loads(text)["packs"]:
        directory = profile.parent / pin["path"]
        manifest = tomllib.loads((directory / "pack.toml").read_text("utf-8"))
        pack = []
        for member in manifest["policies"]:
            pid = member["policy_id"]
            entry = restore_entry(archived[pid])
            policy = entry["policy"]
            prov = policy["provenance"]
            prov.update(pack_id=pin["pack_id"], pack_version=pin["version"])
            verify_digest((directory / member["path"]).read_bytes(), prov["content_digest"], member["path"])
            entry["path"] = member["path"]
            entry["target_digest"] = digest(b"\n".join((compact(policy), entry["body"].encode(), entry["path"].encode())))
            verify_digest(compact(entry), pin["policies"][pid], pid)
            pack.append(entry)
        pack.sort(key=lambda entry: entry["policy"]["policy_id"])
        verify_digest(b"\n".join(map(compact, pack)), pin["digest"], pin["pack_id"])
        entries.extend(entry | {"path": pin["pack_id"] + "/" + entry["path"]} for entry in pack)
    golden = json.loads((reference / "evals/engineering_scenarios.json").read_bytes())["full_engineering_profile"]
    if golden != {"profile_id": "policynim.full-engineering", "profile_digest": SHIPPED[0]}:
        raise ValueError("Engineering fixture doesn't match the pinned full-engineering profile.")
    aggregate_entries = [restore_entry(archived[key]) for key in sorted(archived)]
    verify_digest(b"\n".join(map(compact, aggregate_entries)), aggregate["digest"], "Published baseline pack")
    pin = aggregate_entries[0]["policy"]["provenance"]
    baseline_text = (f'schema_version = 1\nprofile_id = "aggregate"\n[[packs]]\npath = "builtin:baseline"\n'
                     f'pack_id = "{pin["pack_id"]}"\nversion = "{pin["pack_version"]}"\n'
                     f'digest = "{aggregate["digest"]}"\n[packs.policies]\n')
    for entry in aggregate_entries:
        pid = entry["policy"]["policy_id"]
        baseline_text += f'"{pid}" = "{digest(compact(entry))}"\n'
        entry["path"] = pin["pack_id"] + "/" + entry["path"]
    return text, entries, (baseline_text, catalog(baseline_text, aggregate_entries))


def catalog(text, entries):
    """Build the expected source view from declared pins and independently verified entries."""
    model = tomllib.loads(text)
    for pin in model["packs"]:
        pin["policies"] = dict(sorted(pin["policies"].items()))
    model = model | {field: model.get(field, []) for field in ("exclusions", "replacements", "conflicts")}
    entries = sorted(entries, key=lambda entry: entry["policy"]["policy_id"])
    revision = digest(compact(model))
    return {"schema_version": 1, "scope": "source", "generation_id": None,
            "digest": digest(b"\n".join([revision.encode(), *map(compact, entries)])),
            "profile_id": model["profile_id"], "profile_digest": revision,
            "pack_pins": [ordered(pin, "path pack_id version digest") for pin in model["packs"]],
            "authored_count": len(entries), "effective_count": sum(entry["state"] == "active" for entry in entries),
            "indexed_count": None, "unlisted_markdown": [], "policies": entries}


def variants(shipped, entries):
    """Vary path spelling, declaration order, TOML key order, comments and physical line endings."""
    builtin = re.sub(r'^path = "\.\./([^"]+)"$', r'path = "builtin:\1"', shipped, flags=re.MULTILINE)
    header, *blocks = builtin.split("[[packs]]")
    reversed_packs = header + "".join("[[packs]]" + block.rstrip() + "\n\n" for block in reversed(blocks))
    reversed_keys = re.sub(r"(\[packs\.policies\])\n((?:[^\[\n][^\n]*\n)+)",
                           lambda m: m[1] + "\n" + "\n".join(reversed(m[2].splitlines())) + "\n", builtin)
    cases = {"relative": (shipped, SHIPPED), "builtin": (builtin, BUILTIN), "reverse-packs": (reversed_packs, REVERSED),
             "reverse-keys": (reversed_keys, BUILTIN), "comment": (builtin + "\n# operator review\n", BUILTIN),
             "crlf": (builtin.replace("\n", "\r\n"), BUILTIN)}
    result = {}
    for name, (text, pins) in cases.items():
        expected = catalog(text, entries)
        if (expected["profile_digest"], expected["digest"]) != pins:
            raise ValueError(f"Archived records and source pins disagree for {name}")
        result[name] = (text, expected)
    return result

"""Calculate profile review tokens and full policy revisions from the authored test inputs."""

import tomllib

from profile_oracle import compact, digest

CATEGORIES = ("added", "changed", "removed", "changed_packs", "invalidated_decisions")


def model(text):
    """Read the authored declarations and supply the profile model's empty decision arrays."""
    profile = tomllib.loads(text)
    return {"schema_version": profile["schema_version"], "profile_id": profile["profile_id"], "packs": profile["packs"],
            **{field: profile.get(field, []) for field in ("exclusions", "replacements", "conflicts")}}


def capture(directory, selector):
    """Calculate standalone revisions for the synthetic packs from their declared Markdown bytes."""
    manifest = tomllib.loads((directory / "pack.toml").read_text("utf-8"))
    pack_id, version = manifest["pack_id"], manifest["version"]
    entries = []
    for member in sorted(manifest["policies"], key=lambda item: item["policy_id"]):
        pid, path = member["policy_id"], member["path"]
        raw = (directory / path).read_bytes()
        body = raw.decode("utf-8").split("---\n", 2)[2]
        source = {"content_digest": digest(raw), "pack_id": pack_id, "pack_version": version,
                  "requirement_level": "unknown", "applicability": [], "grounding_kind": "legacy_unknown", "sources": []}
        policy = {"policy_id": pid, "title": "Reviewed " + pid, "doc_type": "guidance", "domain": "ops",
                  "tags": [], "grounded_in": [], "provenance": source}
        target = digest(b"\n".join((compact(policy), body.encode(), path.encode())))
        entries.append({"state": "active", "target_digest": target, "reason": None, "replacement_id": None,
                        "path": path, "policy": policy, "body": body})
    pin = {"path": selector, "pack_id": pack_id, "version": version,
           "digest": digest(b"\n".join(map(compact, entries))),
           "policies": {entry["policy"]["policy_id"]: digest(compact(entry)) for entry in entries}}
    return pin, [entry | {"path": pack_id + "/" + entry["path"]} for entry in entries]


def report(text, current, entries, **changes):
    """Combine the original text, full current pins and explicit expected changes into the review report."""
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return {"profile_id": current["profile_id"], "review_digest": digest(normalized.encode() + b"\n" + compact(current)),
            **dict.fromkeys(CATEGORIES, []), "current_policies": sorted(entries, key=lambda e: e["policy"]["policy_id"]),
            "validation_error": None, "warnings": [], **changes}


def local_report(path, **changes):
    """Resolve the ordered local fixture packs without importing any product implementation."""
    text = path.read_text("utf-8")
    current = model(text)
    entries = []
    for i, previous in enumerate(current["packs"]):
        selector = previous["path"]
        pin, pack_entries = capture(path.parent / selector, selector)
        current["packs"][i] = pin
        entries.extend(pack_entries)
    return report(text, current, entries, **changes)


def summary(expected):
    """Summarize every current entry using the independently pinned full-member digest."""
    entries = expected["current_policies"]
    return {key: value for key, value in expected.items() if key != "current_policies"} | {
        "memberCount": len(entries), "membersSha256": digest(compact(entries))}

"""Compare explicit source packs through the installed PolicyNIM Python and Java commands."""

import argparse
import hashlib
import json
import os
import select
import shutil
import socket
import sys
import tempfile
from copy import deepcopy
from importlib import metadata
from pathlib import Path

from archived_pack import ARCHIVE_SHA, MANIFEST_SHA, REFERENCE_COMMIT, prepare_aggregate
from source_catalog import CatalogCommand, match, require, sha256, validation

FIXTURE = Path(__file__).resolve().parents[1] / "resources/parity/catalog/explicit-logging.json"
SECRET_MARKER = "SYNTHETIC-PACK-MARKER-673"


def file_digests(root):
    """Record every regular fixture file's relative path and raw revision."""
    return {path.relative_to(root).as_posix(): sha256(path.read_bytes()) for path in root.rglob("*") if path.is_file()}


def check_catalog(cli, expected):
    """Compare the entire list and validation response with the independently specified catalog."""
    before = file_digests(cli.root)
    listed = cli.json("list")
    match(listed, expected, f"{cli.product} list")
    valid = validation(expected) | {"unlisted_markdown": expected["unlisted_markdown"]}
    match(cli.json("validate"), valid, f"{cli.product} validate")
    match(file_digests(cli.root), before, f"{cli.product} input after inspection")
    return {"catalogDigest": listed["digest"], "policies": len(listed["policies"]), "unlisted": listed["unlisted_markdown"]}


def exercise_selection(cli, fixture):
    """Check declared provenance, source identity and operator text while skipping unselected files."""
    cli.root.mkdir()
    manifest = fixture["manifest"].encode("utf-8")
    member = fixture["member"].encode("utf-8")
    expected = fixture["catalog"]
    entry = expected["policies"][0]
    revision = entry["policy"]["provenance"]["content_digest"]
    require(sha256(manifest) == fixture["manifest_sha256"] and sha256(member) == revision, "Fixture bytes changed")
    (cli.root / "pack.toml").write_bytes(manifest)
    (cli.root / "policy.md").write_bytes(member)
    (cli.root / "README.md").write_bytes(b"unselected\xff")
    (cli.root / "TEMPLATE.md").write_bytes(b"template\xff")
    notices = cli.root / "licenses"
    notices.mkdir()
    note = notices / "NOTICE.txt"
    note.write_bytes(b"old notice\xff")
    result = check_catalog(cli, expected)
    match(cli.json("show", "LOG-001"), entry | {"scope": "source", "generation_id": None}, f"{cli.product} show")
    for args, details in (
        (["list"], ["team.logging@1.0.0", "Unlisted Markdown", "policies validate", expected["digest"]]),
        (["validate"], ["Unlisted Markdown", "README.md", expected["digest"]]),
        (["show", "LOG-001"], ["Source: policy.md", revision, "Grounding: adaptation", entry["body"].strip()]),
    ):
        output = cli.run(*args)
        require(output.returncode == 0 and not output.stderr and all(s in output.stdout.decode("utf-8") for s in details),
                f"{cli.product} {args}: missing text details {details!r}")
    note.write_bytes(b"updated notice\xfe")
    match(cli.json("list"), expected, f"{cli.product} changed notice")
    markdown_directory = cli.root / "draft.md"
    markdown_directory.mkdir()
    with_directory = expected | {"unlisted_markdown": ["README.md", "draft.md"]}
    match(cli.json("list"), with_directory, f"{cli.product} unselected directory name")
    match(cli.json("validate")["unlisted_markdown"], with_directory["unlisted_markdown"], "Validation listing")
    markdown_directory.rmdir()

    # non-English test data
    filename = "author\u2028notes.md"
    author_note = cli.root / filename
    author_note.write_bytes(b"Author notes")
    match(cli.json("validate")["unlisted_markdown"], ["README.md", filename], f"{cli.product} exact filenames")
    if cli.product == "java":
        listing = cli.run("validate")
        require(listing.returncode == 0 and not listing.stderr, "Java text validation failed")
        require(b"author\\u2028notes.md" in listing.stdout and filename.encode() not in listing.stdout,
                "Java text validation must keep a filename on one line")
    author_note.unlink()

    selected = cli.root / "policy.md"
    selected.write_bytes(member.replace(b"policy_id:", b"unlisted_field: SYNTHETIC-SOURCE-VALUE\npolicy_id:", 1))
    response = cli.run("list", "--format", "json")
    require(response.returncode == 1 and not response.stderr, f"{cli.product} strict metadata diagnostics")
    require(b"SYNTHETIC-SOURCE-VALUE" not in response.stdout, f"{cli.product} exposed a rejected field value")
    error = json.loads(response.stdout)
    require("unsupported frontmatter keys: unlisted_field" in error["message"], "Missing local source field name")
    match(error, {"status": "error", "scope": "source", "generation_id": None, "message": error["message"]},
          f"{cli.product} unsupported field envelope")
    rule = fixture["runtime_rule"]
    with_rule = member.replace(b"policy_id:", rule["frontmatter"].encode("utf-8") + b"policy_id:", 1)
    require(sha256(with_rule) == rule["source_digest"], "The authored rule fixture changed")
    selected.write_bytes(with_rule)
    rule_catalog = deepcopy(expected)
    rule_entry = rule_catalog["policies"][0]
    rule_entry["policy"]["provenance"]["content_digest"] = rule["source_digest"]
    rule_entry["target_digest"] = rule["target_digest"]
    rule_catalog["digest"] = rule["catalog_digest"]
    match(cli.json("list"), rule_catalog, f"{cli.product} declared rule catalog")
    selected.write_bytes(member)
    match(cli.json("list"), expected, f"{cli.product} corrected metadata")
    return result | {"sourceDigest": revision, "targetDigest": entry["target_digest"],
                     "unlistedDirectory": with_directory["unlisted_markdown"], "unsupportedField": "unlisted_field",
                     "runtimeRuleCatalogDigest": rule["catalog_digest"]}


def check_recovery(cli, fixture):
    """Reject invalid declarations without revealing credentials and repeat the same command after each correction."""
    original = fixture["manifest"]
    manifest = cli.root / "pack.toml"
    cases = (
        ("missingMember", original.replace('"policy.md"', '"missing.md"'), ["policies.0.path", "doesn't exist"]),
        ("duplicateMember", original + '\n[[policies]]\npolicy_id = "LOG-002"\npath = "./policy.md"\n',
         ["policies.1.path", "repeats a member"]),
        ("credential", original.replace("https://example.com", f"https://user:{SECRET_MARKER}@example.com"),
         ["policies.0.sources.0.url"]),
        ("schemaVersion", original.replace("schema_version = 1", "schema_version = 1000000000000000001"),
         ["schema_version"]),
        ("yearZero", original.replace("2026-09-01", "0000-01-01"),
         ["date"] if cli.product == "python" else ["retrieved_on"]),
        ("schemaOverflow", original.replace("schema_version = 1", "schema_version = 9223372036854775808"),
         ["schema_version"] if cli.product == "python" else ["TOML", "line 1, column 18"]),
        ("dateOverflow", original.replace("2026-09-01", "9223372036854775808"),
         ["policies.0.sources.0.retrieved_on"] if cli.product == "python" else ["TOML", "line 15, column 16"]),
    )
    errors = {}
    for name, invalid, fields in cases:
        manifest.write_bytes(invalid.encode("utf-8"))
        response = cli.run("list", "--format", "json")
        require(SECRET_MARKER.encode() not in response.stdout + response.stderr, f"{cli.product} exposed a test credential")
        require(response.returncode == 1 and not response.stderr, f"{cli.product} {name}: unexpected exit or diagnostics")
        error = json.loads(response.stdout)
        message = error.get("message")
        require(isinstance(message, str) and "pack.toml" in message and all(field in message for field in fields),
                f"{cli.product} {name}: unexpected failure {message!r}")
        match(error, {"status": "error", "scope": "source", "generation_id": None, "message": message},
              f"{cli.product} {name} envelope")
        errors[name] = message
        valid = original.replace("2026-09-01", "2024-02-29") if name == "yearZero" else original
        manifest.write_bytes(valid.encode("utf-8"))
        expected = fixture["catalog"]
        if name == "yearZero":
            expected = deepcopy(expected)
            entry = expected["policies"][0]
            entry["policy"]["provenance"]["sources"][0]["retrieved_on"] = "2024-02-29"
            entry["target_digest"] = fixture["leap_date"]["target_digest"]
            expected["digest"] = fixture["leap_date"]["catalog_digest"]
        match(cli.json("list"), expected, f"{cli.product} {name} corrected")
    return errors


def check_member_links(cli, fixture):
    """Verify each in-pack name's identity and retry the same declaration after correcting a broken link."""
    selected = cli.at_root(cli.root.parent / "links" / "pack")
    root = selected.root
    members = root / "members"
    members.mkdir(parents=True)
    (members / "policy.md").write_bytes(fixture["member"].encode("utf-8"))
    manifest = root / "pack.toml"

    def select(member):
        """Name one lexical member in the otherwise unchanged manifest."""
        manifest.write_bytes(fixture["manifest"].replace('"policy.md"', f'"{member}"').encode("utf-8"))

    def check_location(path, unlisted):
        """Compare the complete record against the independently calculated digest for its path."""
        expected = deepcopy(fixture["catalog"])
        identity = fixture["member_locations"][path]
        expected.update(digest=identity["catalog_digest"], unlisted_markdown=unlisted)
        expected["policies"][0].update(path=path, target_digest=identity["target_digest"])
        listed = selected.json("list")
        match(listed, expected, f"{cli.product} member {path}")
        return listed["digest"]

    select("members//./policy.md")
    result = {"normalized": check_location("members/policy.md", [])}
    original = ["members/policy.md"]
    alias = root / "alias.md"
    alias.symlink_to("members/policy.md")
    select("alias.md")
    result["fileAlias"] = check_location("alias.md", original)
    alias.unlink()
    directory = root / "linked"
    directory.symlink_to("members", target_is_directory=True)
    select("linked/policy.md")
    result["directoryAlias"] = check_location("linked/policy.md", original)
    directory.unlink()

    sibling = root.parent / "sibling.md"
    sibling.write_bytes(fixture["member"].encode("utf-8"))
    select("alias.md")
    for name, target, cause in (("outside", sibling, "within the pack"), ("dangling", "missing.md", "doesn't exist")):
        alias.symlink_to(target)
        error = selected.error("list", fragments=["pack.toml", "policies.0.path", cause])
        alias.unlink()
        alias.symlink_to("members/policy.md")
        result[name] = {"message": error["message"], "recoveredDigest": check_location("alias.md", original)}
        alias.unlink()
    return result


def check_hard_links(cli, fixture):
    """Select two hard links whose Markdown paths infer separate policy IDs."""
    selected = cli.at_root(cli.root.parent / "hard" / "policies")
    api = selected.root / "api"
    api.mkdir(parents=True)
    first = api / "a.md"
    first.write_bytes(fixture["body"].encode("utf-8"))
    (api / "b.md").hardlink_to(first)
    manifest = ('schema_version=1\npack_id="team.hard"\nversion="1.0.0"\n'
                '[[policies]]\npolicy_id="API-A"\npath="api/a.md"\n[[policies]]\npolicy_id="API-B"\npath="api/b.md"\n')
    (selected.root / "pack.toml").write_bytes(manifest.encode("utf-8"))
    listed = selected.json("list")
    match(listed["digest"], fixture["catalog_digest"], f"{cli.product} hard-link catalog")
    match([entry["policy"]["policy_id"] for entry in listed["policies"]], ["API-A", "API-B"], "Inferred hard-link IDs")
    for entry in listed["policies"]:
        match(entry["target_digest"], fixture["targets"][entry["path"]], "Hard-link target")
        match(entry["policy"]["provenance"]["content_digest"], fixture["source_digest"], "Shared source bytes")
    return listed["digest"]


def launchers(workspace, java, jar, executable, port):
    """Use private homes, config, working directories and evaluation workspaces for both installed CLIs."""
    for product in ("python", "java"):
        base = workspace / product
        home = base / "home"
        cwd = base / "work"
        root = base / "pack"
        home.mkdir(parents=True)
        cwd.mkdir()
        tmp = home / "tmp"
        tmp.mkdir()
        config = home / "empty.env"
        config.touch()
        env = {"PATH": os.defpath, "LC_ALL": "C.UTF-8", "HOME": str(home), "XDG_CONFIG_HOME": str(home / "config"),
               "XDG_DATA_HOME": str(home / "data"), "POLICYNIM_CONFIG_FILE": str(config), "NO_COLOR": "1",
               "POLICYNIM_EVAL_WORKSPACE_DIR": str(home / "evaluations"), "PYTHONDONTWRITEBYTECODE": "1",
               "POLICYNIM_PROVIDER_NVIDIA_ENABLED": "false", "TMPDIR": str(tmp)}
        if product == "python":
            env["POLICYNIM_CORPUS_DIR"] = str(root)
            program = [executable]
        else:
            env.update(SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(port), POLICYNIM_STORAGE_MODE="jdbc",
                       SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{port}/pack_test?connectTimeout=1")
            program = [java, f"-Duser.home={home}", f"-Djava.io.tmpdir={tmp}", "-jar", str(jar)]
        yield CatalogCommand(product, program, cwd, root, env)


def main():
    """Run installed commands on the same fixtures and report the exact jar and reference artifact digests."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--reference-root", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    executable = shutil.which("policynim", path=str(Path(sys.executable).parent))
    require(sys.version_info[:3] == (3, 12, 10) and metadata.version("policynim") == "0.3.0" and executable and args.java,
            "Use the pinned Python 3.12.10 PolicyNIM 0.3.0 installation and Java 21.")
    jar = args.jar.resolve(strict=True)
    fixture = json.loads(FIXTURE.read_bytes())
    with jar.open("rb") as artifact:
        digest = hashlib.file_digest(artifact, "sha256").hexdigest()
    receipt = {"javaJarSha256": digest, "referenceCommit": REFERENCE_COMMIT,
               "pythonVersion": sys.version.split()[0], "fixtureSha256": sha256(FIXTURE.read_bytes()),
               "aggregateManifestSha256": MANIFEST_SHA, "archiveSha256": ARCHIVE_SHA}
    with tempfile.TemporaryDirectory() as temporary, socket.socket() as occupied:
        occupied.bind(("127.0.0.1", 0))
        occupied.listen()
        workspace = Path(temporary)
        aggregate_root = workspace / "aggregate" / "policies"
        expected = prepare_aggregate(args.reference_root.resolve(strict=True), aggregate_root)
        for cli in launchers(workspace, args.java, jar, executable, occupied.getsockname()[1]):
            receipt[cli.product] = {"fixture": exercise_selection(cli, fixture), "failures": check_recovery(cli, fixture),
                                    "memberLinks": check_member_links(cli, fixture),
                                    "hardLinkCatalogDigest": check_hard_links(cli, fixture["hard_links"]),
                                    "aggregate": check_catalog(cli.at_root(aggregate_root), expected)}
        require(not select.select([occupied], [], [], 0)[0], "Pack inspection opened a database connection.")
    with jar.open("rb") as artifact:
        require(hashlib.file_digest(artifact, "sha256").hexdigest() == digest, "The jar changed during this comparison.")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()

"""Compare source catalog commands from the packaged Java jar and pinned Python installation."""

import argparse
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from importlib import metadata
from pathlib import Path

from legacy_stdio import require

FIXTURE = Path(__file__).resolve().parents[1] / "resources" / "parity" / "catalog" / "plain-source.json"


def sha256(data):
    """Hash the original file bytes using the public content revision format."""
    return "sha256:" + hashlib.sha256(data).hexdigest()


def match(actual, expected, label):
    """Compare complete JSON values, including the types of counts and null fields."""
    require(json.dumps(actual, sort_keys=True) == json.dumps(expected, sort_keys=True),
            f"{label}: expected {expected!r}, received {actual!r}")


@dataclass
class CatalogCommand:
    """Call one installed CLI from its temporary home and unrelated working directory."""

    product: str
    program: list[str]
    cwd: Path
    root: Path
    env: dict[str, str]

    def run(self, *arguments, source=True, separate_root=False):
        """Run one bounded command with the selected root and source flag."""
        args = [*self.program, "policies", *arguments]
        if source:
            args.append("--source")
            if self.product == "java":
                args.extend(["--corpus-root", str(self.root)] if separate_root else [f"--corpus-root={self.root}"])
        return subprocess.run(args, env=self.env, cwd=self.cwd, capture_output=True, timeout=15, check=False)

    def json(self, *arguments, exit_code=0, separate_root=False):
        """Require the expected exit status and one JSON value on stdout."""
        result = self.run(*arguments, "--format", "json", separate_root=separate_root)
        require(result.returncode == exit_code,
                f"{self.product} {arguments}: expected exit {exit_code}, got {result.returncode}; "
                f"stdout={result.stdout[:500]!r} stderr={result.stderr[:500]!r}")
        require(not result.stderr.strip(), f"{self.product} {arguments}: unexpected diagnostics")
        return json.loads(result.stdout)

    def error(self, *arguments, fragments):
        """Check the structured failure and its correction hint without depending on absolute root paths."""
        body = self.json(*arguments, exit_code=1)
        message = body.get("message")
        require(isinstance(message, str) and message.strip() and all(fragment in message for fragment in fragments),
                f"{self.product} {arguments}: unexpected failure message {message!r}")
        match(body, {"status": "error", "scope": "source", "generation_id": None, "message": message},
              f"{self.product} {arguments} failure")
        return body["status"]


def validation(catalog):
    """Project the independently specified catalog into the public validation response."""
    return {"status": "ok", "scope": "source", "generation_id": None, "catalog_digest": catalog["digest"],
            "authored_count": catalog["authored_count"], "effective_count": catalog["effective_count"],
            "indexed_count": None, "unlisted_markdown": []}


def write_fixture(root, files):
    """Materialize the checked-in fixture as its exact UTF-8 bytes, preserving BOM and newlines."""
    for relative, contents in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(contents.encode("utf-8"))


def check_text_and_help(cli, catalog):
    """Check human-readable default output plus the public help for all three commands."""
    rich = catalog["policies"][1]
    for args, fragments in (
        (["list"], [catalog["digest"], "API-TASKS", "OPS-É"]),
        (["validate"], [catalog["digest"], "2"]),
        (["show", "OPS-É"], [rich["path"], rich["policy"]["provenance"]["content_digest"], rich["body"].strip()]),
    ):
        result = cli.run(*args)
        require(result.returncode == 0 and all(value in result.stdout.decode("utf-8") for value in fragments),
                f"{cli.product} {args}: default text output omitted a source detail")
        help_result = cli.run(args[0], "--help", source=False)
        help_text = help_result.stdout.decode("utf-8")
        require(help_result.returncode == 0 and "--source" in help_text and "--format" in help_text,
                f"{cli.product} {args[0]}: help omitted source or format options")
        if cli.product == "java":
            require("--corpus-root" in help_text, f"{args[0]}: help omitted the Java root option")


def check_recovery(cli, fixture):
    """Reject bad files and IDs, restore a healthy catalog, and verify a one-byte source edit."""
    failures = {"unknownId": cli.error("show", "api-tasks", fragments=["api-tasks", "policies list", "--source"])}
    duplicate = cli.root / "duplicate.md"
    duplicate.write_bytes(b"---\npolicy_id: API-TASKS\n---\n# Duplicate\n")
    failures["duplicateId"] = cli.error("validate", fragments=["API-TASKS", "duplicate.md", "api/tasks.md"])
    duplicate.unlink()
    for name, content in (("unreadable.md", b'\xff'), ("incomplete.md", b'---\npolicy_id: SAMPLE\n')):
        path = cli.root / name
        path.write_bytes(content)
        failures[name] = cli.error("list", fragments=[name])
        path.unlink()
    directory = cli.root / "folder.md"
    directory.mkdir()
    failures["folder.md"] = cli.error("validate", fragments=["folder.md"])
    directory.rmdir()
    dangling = cli.root / "dangling.md"
    dangling.symlink_to("missing-target")
    failures["dangling.md"] = cli.error("validate", fragments=["dangling.md"])
    dangling.unlink()
    alias = cli.root / "api" / "alias.md"
    alias.symlink_to("tasks.md")
    linked_dir = cli.root / "linked-api"
    linked_dir.symlink_to("api", target_is_directory=True)
    listed = cli.json("list")
    require([entry["policy"]["policy_id"] for entry in listed["policies"]] == ["API-ALIAS", "API-TASKS", "OPS-É"],
            f"{cli.product}: unexpected membership for a file and directory link")
    alias_entry = listed["policies"][0]
    original = fixture["catalog"]["policies"][0]
    require(alias_entry["path"] == "api/alias.md" and alias_entry["body"] == original["body"]
            and alias_entry["policy"]["provenance"] == original["policy"]["provenance"],
            f"{cli.product}: incorrect source path or revision for the in-root alias")
    linked_dir.unlink()
    alias.unlink()
    match(cli.json("validate"), validation(fixture["catalog"]), f"{cli.product} repaired source")

    tasks = cli.root / "api/tasks.md"
    tasks.write_bytes(b"# Tasks\r\n")
    updated = cli.json("list")
    expected = json.loads(json.dumps(fixture["catalog"]))
    changed = fixture["crlf_task"]
    expected["digest"] = changed["catalog_digest"]
    entry = expected["policies"][0]
    entry["policy"]["provenance"]["content_digest"] = changed["content_digest"]
    entry.update(target_digest=changed["target_digest"], body=changed["body"])
    match(updated, expected, f"{cli.product} CRLF catalog")
    tasks.write_bytes(fixture["files"]["api/tasks.md"].encode("utf-8"))
    restored = cli.json("validate")
    match(restored, validation(fixture["catalog"]), f"{cli.product} restored LF source")
    return {"sourceErrors": failures, "aliasPath": alias_entry["path"],
            "crlfCatalogDigest": updated["digest"], "recoveredDigest": restored["catalog_digest"]}


def exercise(cli, fixture):
    """Compare complete success and error shapes for the same authored files."""
    cli.root.parent.mkdir(parents=True, exist_ok=True)
    root_errors = {"missing": cli.error("validate", fragments=[cli.root.name])}
    cli.root.write_bytes(b"not a directory")
    root_errors["nonDirectory"] = cli.error("validate", fragments=[cli.root.name])
    cli.root.unlink()
    cli.root.mkdir()
    root_errors["empty"] = cli.error("validate", fragments=[])
    write_fixture(cli.root, fixture["files"])
    expected = fixture["catalog"]
    for entry in expected["policies"]:
        require(sha256((cli.root / entry["path"]).read_bytes()) == entry["policy"]["provenance"]["content_digest"],
                f"Fixture bytes differ for {entry['path']}")
    match(cli.json("validate", separate_root=True), validation(expected), f"{cli.product} validate")
    listed = cli.json("list")
    match(listed, expected, f"{cli.product} list")
    for entry in expected["policies"]:
        match(cli.json("show", entry["policy"]["policy_id"]), entry | {"scope": "source", "generation_id": None},
              f"{cli.product} show {entry['path']}")
    check_text_and_help(cli, expected)
    recovery = check_recovery(cli, fixture)
    actual_files = {path.relative_to(cli.root).as_posix(): path.read_bytes() for path in cli.root.rglob("*")
                    if path.is_file()}
    require(actual_files == {path: content.encode("utf-8") for path, content in fixture["files"].items()},
            f"{cli.product}: source files changed during inspection")
    return {"scope": listed["scope"], "catalogDigest": listed["digest"],
            "policyIds": [p["policy"]["policy_id"] for p in listed["policies"]],
            "rootErrors": root_errors, "recovery": recovery}


def main():
    """Identify the tested jar, isolate both installed services, and print a JSON receipt."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    cli = shutil.which("policynim", path=str(Path(sys.executable).parent))
    require(cli and args.java and metadata.version("policynim") == "0.3.0", "Use the pinned Python install and Java 21.")
    jar = args.jar.resolve(strict=True)
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    with jar.open("rb") as artifact:
        digest = hashlib.file_digest(artifact, "sha256").hexdigest()
    result = {"javaJarSha256": digest, "pythonVersion": metadata.version("policynim"),
              "fixtureSha256": sha256(FIXTURE.read_bytes())}
    with tempfile.TemporaryDirectory() as workspace, socket.socket() as occupied:
        occupied.bind(("127.0.0.1", 0))
        occupied.listen()
        for product in ("python", "java"):
            base = Path(workspace) / product
            home = base / "home"
            cwd = base / "work"
            root = base / "custom source" / "policies"
            home.mkdir(parents=True)
            cwd.mkdir()
            config = home / "empty.env"
            config.touch()
            env = {"HOME": str(home), "PATH": os.defpath, "XDG_CONFIG_HOME": str(home / "config"),
                   "XDG_DATA_HOME": str(home / "data"), "POLICYNIM_CONFIG_FILE": str(config),
                   "POLICYNIM_EVAL_WORKSPACE_DIR": str(home / "evaluations"), "NO_COLOR": "1",
                   "PYTHONDONTWRITEBYTECODE": "1"}
            if product == "python":
                env["POLICYNIM_CORPUS_DIR"] = str(root)
                program = [cli]
            else:
                env.update(SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(occupied.getsockname()[1]),
                           POLICYNIM_PROVIDER_NVIDIA_ENABLED="false")
                program = [args.java, f"-Duser.home={home}", "-jar", str(jar)]
            result[product] = exercise(CatalogCommand(product, program, cwd, root, env), fixture)
    with jar.open("rb") as artifact:
        require(hashlib.file_digest(artifact, "sha256").hexdigest() == digest, "The jar changed during the comparison.")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

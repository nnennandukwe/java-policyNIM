"""Compare installed PolicyNIM profile readers against independent source and decision fixtures."""

import argparse
import json
import os
import select
import shutil
import socket
import subprocess
import sys
import tempfile
import tomllib
from dataclasses import replace
from importlib import metadata
from pathlib import Path

from archived_pack import REFERENCE_COMMIT
from bundled_source import jar_hash
from explicit_pack import file_digests, launchers
from profile_oracle import BUILTIN, SHIPPED, catalog, shipped_entries, variants
from source_catalog import CatalogCommand, match, require, validation, write_fixture

FIXTURES = Path(__file__).resolve().parents[1] / "resources/parity/catalog"
PROFILE = "POLICYNIM_PROFILE"
CORPUS = "POLICYNIM_CORPUS_DIR"
LIMITS = "Structure and declared conflicts only; prose compatibility isn't certified."


def invoke(cli, args, *, settings=None, text=False, error=None):
    """Check exit status, both output streams and the absence of filesystem writes for one invocation."""
    settings = settings or {}
    command = list(cli.program)
    if cli.product == "image":
        for key, value in settings.items():
            command[-1:-1] = ["--env", f"{key}={value}"]
    command.extend(args)
    if not text:
        command.extend(["--format", "json"])
    before = file_digests(cli.cwd.parent)
    result = subprocess.run(command, cwd=cli.cwd, env=cli.env | settings, stdin=subprocess.DEVNULL,
                            capture_output=True, timeout=35)
    match(file_digests(cli.cwd.parent), before, f"{cli.product} files after {args}")
    require(result.returncode == (1 if error else 0),
            f"{cli.product} {args} exited {result.returncode}: {result.stdout[:250]!r} {result.stderr[:250]!r}")
    if text and error:
        require(not result.stdout and error.lower() in result.stderr.decode().lower(), f"{args}: {result.stderr[:300]!r}")
        return result.stderr.decode()
    require(not result.stderr, f"{cli.product} {args}: {result.stderr[:300]!r}")
    if text:
        return result.stdout.decode()
    doc = json.loads(result.stdout)
    if error:
        message = doc.get("message", "")
        require(error.lower() in message.lower(), f"{cli.product} {args}: {message}")
        expected = {"status": "error", "message": message}
        if args[0] == "policies":
            expected.update(scope="source", generation_id=None)
        match(doc, expected, f"{cli.product} {args} failure")
    return doc


def validate_profile(expected):
    """Return the independent profile validation response, including its documented limit."""
    return {"status": "ok", "catalog_digest": expected["digest"], "effective_count": expected["effective_count"],
            "limits": LIMITS}


def check_decisions(cli, team, name):
    """Verify every authored decision and both validation contracts through the installed commands."""
    settings = {PROFILE: name}
    match(invoke(cli, ["profile", "preview", name]), team, f"{cli.product} decision preview")
    match(invoke(cli, ["profile", "validate", name]), validate_profile(team), f"{cli.product} profile validation")
    match(invoke(cli, ["policies", "list", "--source"], settings=settings), team, f"{cli.product} source list")
    match(invoke(cli, ["policies", "validate", "--source"], settings=settings), validation(team), "Source validation")
    preview = invoke(cli, ["profile", "preview", name], text=True)
    rows = [f'{e["policy"]["policy_id"]}  {e["state"]}  {e["reason"] or ""}' for e in team["policies"]]
    match(preview.splitlines(), ["Profile team: 2 effective policies", f'Source catalog: {team["digest"]}; indexed generation unknown',
                                 *rows], f"{cli.product} profile text")
    summary = invoke(cli, ["profile", "validate", name], text=True)
    match(summary.splitlines(), [f'Valid profile: 2 effective policies; {team["digest"]}', LIMITS], "Validation text")
    listed = invoke(cli, ["policies", "list", "--source"], settings=settings, text=True)
    for entry in team["policies"]:
        pid = entry["policy"]["policy_id"]
        require(f'{pid}  {entry["state"]}  ops  ' in listed, f"Source list omitted {pid}")
        args = ["policies", "show", pid, "--source"]
        match(invoke(cli, args, settings=settings), entry | {"generation_id": None, "scope": "source"}, f"Show {pid}")
        body = invoke(cli, args, settings=settings, text=True)
        require(f'Source: {entry["path"]}; state: {entry["state"]}' in body and body.endswith(entry["body"] + "\n"),
                f"Show {pid} omitted its original path, state or body")
        decisions = [line for line in body.splitlines() if line.startswith("Decision: ")]
        match(decisions, ["Decision: " + entry["reason"]] if entry["reason"] else [], f"Show {pid} decision")


def check_selection(cli, name, team, forms, mixed, plain_digest):
    """Select each setting independently and keep direct profile commands independent of corpus selection."""
    config = cli.cwd.parent / "home/source.env"
    setting = {"POLICYNIM_CONFIG_FILE": str(config)}
    config.write_text(f"{PROFILE}={name}\n", encoding="utf-8")
    cmd = ["policies", "list", "--source"]
    builtin = forms["builtin"][1]
    match(invoke(cli, cmd, settings=setting), team, "Config-file profile")
    match(invoke(cli, cmd, settings=setting | {PROFILE: "inputs/mixed.toml"}), mixed, "Process profile wins")
    config.write_text(f"{PROFILE}=inputs/mixed.toml\n", encoding="utf-8")
    match(invoke(cli, cmd, settings=setting), mixed, "Mixed local and builtin packs from file")
    config.write_text(f"{PROFILE}=policies/profiles/full-engineering.toml\n", encoding="utf-8")
    match(invoke(cli, ["policies", "validate", "--source"], settings=setting | {PROFILE: "inputs/builtin.toml"}),
          validation(builtin), "Process-selected builtin profile")
    match(invoke(cli, cmd, settings=setting), forms["relative"][1], "Restored file-selected profile")
    invoke(cli, cmd, settings=setting | {CORPUS: "inputs/team"}, error="POLICYNIM_PROFILE")
    invoke(cli, cmd, settings={PROFILE: " "}, error="POLICYNIM_PROFILE")
    invoke(cli, cmd, settings={PROFILE: "missing-profile.toml"}, error="profile")
    config.write_text(f"{CORPUS}=inputs/team\n", encoding="utf-8")
    invoke(cli, cmd, settings=setting | {PROFILE: name}, error="POLICYNIM_CORPUS_DIR")
    match(invoke(cli, cmd, settings=setting | {PROFILE: name, CORPUS: " "}), team, "Cleared corpus")
    for corpus in ("inputs/plain/policies", "inputs/team"):
        values = setting | {CORPUS: corpus, PROFILE: "unused-profile.toml"}
        match(invoke(cli, ["profile", "preview", "inputs/builtin.toml"], settings=values), builtin,
              "Direct preview ignores ambient selectors")
        match(invoke(cli, ["profile", "validate", name], settings=values), validate_profile(team),
              "Direct validation ignores ambient selectors")
        if cli.product == "java":
            config.write_text(f"{PROFILE}=missing-profile.toml\n", encoding="utf-8")
            is_plain = corpus.endswith("/policies")
            flag = ["--corpus-root", corpus] if is_plain else ["--corpus-root=" + corpus]
            invalid = values | {"POLICYNIM_CONFIG_FILE": str(config.parent / "missing.env")}
            result = invoke(cli, cmd + flag, settings=invalid)
            match(result["digest"], plain_digest if is_plain else team["pack_pins"][1]["digest"], "Explicit corpus")
            match([e["policy"]["policy_id"] for e in result["policies"]], ["API-TASKS" if is_plain else "D"], "Selected IDs")
            match([result["profile_id"], result["profile_digest"], result["pack_pins"]], [None, None, []], "Plain corpus")
            invoke(cli, cmd + ["--corpus-root=absent"], settings=values, error="absent")


def check_failures(cli, profile, fixture, matcher):
    """Refuse invalid pins, selectors and conflicting policies, then restore and inspect the same inputs."""
    name = "inputs/team.toml"
    preview = ["profile", "preview", name]
    original = fixture["files"]["team.toml"]
    stale = fixture["catalog"]["policies"][0]["target_digest"]
    cases = (
        (original.replace(stale, "sha256:" + "0" * 64), "Stale target"),
        (original.replace('path = "team"', 'path = "./base"'), "more than once"),
        (original.replace('path = "base"', 'path = "builtin:absent"'), "packs.0.path"),
        (original.replace('path = "base"', 'path = "https://127.0.0.1/absent"'), "packs.0.path"),
        (original + '\n[[conflicts]]\nleft="C"\nright="D"\nreason="Choose one."\n', "Declared conflict"),
    )
    try:
        for content, error in cases:
            profile.write_text(content, encoding="utf-8")
            invoke(cli, preview, error=error)
        invoke(cli, ["profile", "validate", name], text=True, error="Declared conflict")
        invoke(cli, ["policies", "list", "--source"], settings={PROFILE: name}, error="Declared conflict")
    finally:
        profile.write_text(original, encoding="utf-8")
    manifest = profile.parent / "team/pack.toml"
    clean = manifest.read_bytes()
    try:
        manifest.write_bytes(clean + b'\napplicability = ["audit"]\n')
        invoke(cli, preview, error="pin")
    finally:
        manifest.write_bytes(clean)
    match(invoke(cli, preview), fixture["catalog"], "Restored decision catalog")

    rules = "inputs/matchers/rules.toml"
    validate = ["profile", "validate", rules]
    invoke(cli, validate, error="Conflicting runtime effects")
    invoke(cli, ["policies", "list", "--source"], settings={PROFILE: rules}, error="Conflicting runtime effects")
    with (cli.cwd / rules).open("ab") as stream:
        stream.write(matcher["exclusion"].encode())
    match(invoke(cli, validate), {"status": "ok", "catalog_digest": matcher["repaired_catalog_digest"],
                                  "effective_count": 1, "limits": LIMITS}, "Exclude conflicting rule owner")


def exercise(cli, inputs, fixture, matcher, forms, entries):
    """Use a separate cwd, filesystem packs and private configuration for each installed product."""
    write_fixture(cli.cwd / "inputs", fixture["files"])
    write_fixture(cli.cwd / "inputs/matchers", matcher["files"])
    plain = json.loads((FIXTURES / "plain-source.json").read_bytes())
    write_fixture(cli.cwd / "inputs/plain/policies", {"api/tasks.md": plain["files"]["api/tasks.md"]})
    shutil.copytree(inputs, cli.cwd / "policies")
    for name, (text, expected) in forms.items():
        path = "policies/profiles/full-engineering.toml" if name == "relative" else f"inputs/{name}.toml"
        if name != "relative":
            (cli.cwd / path).write_bytes(text.encode())
        match(invoke(cli, ["profile", "preview", path]), expected, f"{cli.product} {name} profile")
    team = fixture["catalog"]
    team_name = "inputs/team.toml"
    check_decisions(cli, team, team_name)
    header, first, *_ = forms["builtin"][0].split("[[packs]]")
    local = fixture["files"]["team.toml"]
    mixed_text = header + "[[packs]]" + first + local[local.index("[[packs]]"):]
    (cli.cwd / "inputs/mixed.toml").write_bytes(mixed_text.encode())
    first_pack = tomllib.loads(mixed_text)["packs"][0]["pack_id"]
    mixed = catalog(mixed_text, [e for e in entries if e["policy"]["provenance"]["pack_id"] == first_pack]
                    + team["policies"])
    match(invoke(cli, ["profile", "preview", "inputs/mixed.toml"]), mixed, "Builtin and local pack")
    check_selection(cli, team_name, team, forms, mixed, plain["single_task_catalog_digest"])
    check_failures(cli, cli.cwd / team_name, fixture, matcher)


def check_image(image, work, fixture, forms):
    """Inspect mounted and builtin inputs under the production nonroot entrypoint on a read-only filesystem."""
    image_id = subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Id}}", image], text=True, timeout=30).strip()
    work.mkdir(parents=True)
    write_fixture(work / "inputs", fixture["files"])
    (work / "builtin.toml").write_bytes(forms["builtin"][0].encode())
    for path in (work, *work.rglob("*")):
        path.chmod(0o755 if path.is_dir() else 0o644)
    command = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
               "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=32m", "--mount", f"type=bind,source={work},target=/inputs,readonly",
               "--workdir", "/inputs", "--env", "POLICYNIM_PROVIDER_NVIDIA_ENABLED=false", image_id]
    cli = CatalogCommand("image", command, work, None, os.environ.copy())
    match(invoke(cli, ["profile", "preview", "builtin.toml"]), forms["builtin"][1], "Image builtin profile")
    match(invoke(cli, ["profile", "validate", "inputs/team.toml"]), validate_profile(fixture["catalog"]), "Image local profile")
    selected = ["policies", "list", "--source"]
    invoke(cli, selected, settings={PROFILE: "missing.toml"}, error="profile")
    match(invoke(cli, selected, settings={PROFILE: "inputs/team.toml"}), fixture["catalog"], "Corrected image selector")
    return image_id


def main():
    """Run the paired installed processes and print the exact source and artifact identities."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--reference-root", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--image", help="Also check the locally built production image")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    reference = args.reference_root.resolve(strict=True)
    dist = metadata.distribution("policynim")
    install = json.loads(dist.read_text("direct_url.json") or "{}")
    require(sys.version_info[:3] == (3, 12, 10) and dist.version == "0.3.0"
            and install.get("url", "").endswith(".whl") and "archive_info" in install, "Run against the pinned wheel.")
    scripts = [dist.locate_file(p) for p in dist.files or () if p.parts[-2:] == ("bin", "policynim")]
    require(args.java and len(scripts) == 1, "Supply Java 21 and the installed wheel console script.")
    fixture = json.loads((FIXTURES / "profile-decisions.json").read_bytes())
    matcher = json.loads((FIXTURES / "profile-runtime-conflict.json").read_bytes())
    revision = jar_hash(jar)
    with tempfile.TemporaryDirectory() as directory, socket.socket() as sentinel:
        workspace = Path(directory)
        inputs = workspace / "reference"
        source, entries, baseline = shipped_entries(reference, inputs)
        forms = variants(source, entries) | {"aggregate": baseline}
        sentinel.bind(("127.0.0.1", 0))
        sentinel.listen(1)
        for cli in launchers(workspace, args.java, jar, str(scripts[0]), sentinel.getsockname()[1]):
            cli.env.pop(CORPUS, None)
            cli.env["POLICYNIM_MCP_TRANSPORT"] = "stdio"
            exercise(replace(cli, root=None), inputs, fixture, matcher, forms, entries)
        require(not select.select([sentinel], [], [], 0)[0], "Profile inspection opened a JDBC connection.")
        receipt = {"pythonReference": REFERENCE_COMMIT, "javaJarSha256": revision, "publishedPolicies": len(entries),
                   "relativeCatalogDigest": SHIPPED[1], "builtinCatalogDigest": BUILTIN[1],
                   "decisionCatalogDigest": fixture["catalog"]["digest"], "repairedMatcherDigest": matcher["repaired_catalog_digest"]}
        if args.image:
            receipt["imageId"] = check_image(args.image, workspace / "image/work", fixture, forms)
    require(jar_hash(jar) == revision, "The jar changed during profile inspection.")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()

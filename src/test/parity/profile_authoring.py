"""Verify profile initialization, cross-reader compatibility and source diffs through installed commands."""

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
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from importlib import metadata
from pathlib import Path

from archived_pack import REFERENCE_COMMIT
from bundled_source import jar_hash
from explicit_pack import file_digests, launchers
from profile_authoring_oracle import CATEGORIES, local_report, model, report, summary
from profile_oracle import catalog, digest, shipped_entries
from read_only_profiles import FIXTURES, invoke, validate_profile
from source_catalog import match, require, write_fixture

FIXTURE_SHA = "sha256:d12884125275d0d240fad39be00117e03fa3cf0a4e44f7421e092fcd6be0efb3"
BUILTIN_FILE = "sha256:5f86a154d2af3b361935f56d11916a9b3751fde288f2ab822f0c27ca738f5483"
BUILTIN_REVIEW = "sha256:03608405e3f4d88f2522d1b2bcea206f9f6cc7ca94f8a4dddd5543080adaf85f"


def initialize(cli, name, options=(), *, text=False, error=None):
    """Verify the writer's output channels and every regular file it created or changed."""
    before = file_digests(cli.cwd.parent)
    args = [*cli.program, "profile", "init", name, *options, *([] if text else ["--format", "json"])]
    result = subprocess.run(args, stdin=subprocess.DEVNULL, capture_output=True, cwd=cli.cwd, env=cli.env, timeout=35)
    require(result.returncode == (1 if error else 0),
            f"{cli.product} init {name}: exit {result.returncode}, {result.stderr[:250]!r}, {result.stdout[:250]!r}")
    path = cli.cwd / name
    expected_files = dict(before)
    if not error:
        expected_files[path.relative_to(cli.cwd.parent).as_posix()] = digest(path.read_bytes())
    match(file_digests(cli.cwd.parent), expected_files, f"{cli.product} init writes")
    if error and text:
        require(not result.stdout and error in result.stderr.decode(), f"{cli.product} init text refusal")
        return None
    require(not result.stderr, f"{cli.product} init stderr: {result.stderr[:250]!r}")
    if text:
        lines = result.stdout.decode().splitlines()
        hint = (name if cli.product == "python" else "same jar")
        require(lines[0] == "Created pinned profile: " + name and "profile preview" in lines[1] and hint in lines[1],
                f"{cli.product} init recovery instruction: {lines}")
        return None
    output = json.loads(result.stdout)
    if error:
        require(error in output.get("message", ""), f"{cli.product} init refusal: {output}")
        match(output, {"status": "error", "message": output["message"]}, "Init error envelope")
    else:
        match(output, {"status": "ok", "path": name, "profile": model(path.read_text("utf-8"))}, "Init result")
    return output


def read_both(readers, writer, name, expected):
    """Use both installed readers on the exact same authored file and validate all three output models."""
    path = writer.cwd / name
    planned = catalog(path.read_text("utf-8"), expected["current_policies"])
    for reader in readers:
        cli = replace(reader, cwd=writer.cwd)
        match(invoke(cli, ["profile", "preview", name]), planned, f"{writer.product} to {reader.product} preview")
        match(invoke(cli, ["profile", "validate", name]), validate_profile(planned), "Cross-reader validation")
        match(invoke(cli, ["profile", "diff", name]), expected, "Cross-reader review")
    return planned


def check_diff(cli, name, *, text=False, **changes):
    """Compare every diff field and, when requested, the five text categories and recovery instruction."""
    expected = local_report(cli.cwd / name, **changes)
    args = ["profile", "diff", name]
    match(invoke(cli, args), expected, f"{cli.product} diff {list(changes)}")
    if text:
        lines = invoke(cli, args, text=True).splitlines()
        prefix = [key + ": " + (", ".join(expected[key]) or "(none)") for key in CATEGORIES]
        match(lines[:6], prefix + ["Review digest: " + expected["review_digest"]], "Text diff summary")
        if expected["validation_error"]:
            require("Update blocked: " + expected["validation_error"] in lines and "--format json" in lines[-1],
                    f"Missing blocking reason or review action: {lines}")
        else:
            cli_name = "policynim" if cli.product == "python" else "same jar"
            require(cli_name in lines[-1] and "profile update" in lines[-1]
                    and "--expect-digest " + expected["review_digest"] in lines[-1],
                    f"Missing review token in follow-up: {lines}")
    return expected


def check_changes(cli, fixture, name):
    """Exercise old and current revisions, duplicate ownership, decision refusals and full recovery."""
    files = fixture["files"]
    profile = cli.cwd / name
    original = fixture["authoredProfile"]
    clean = check_diff(cli, name, text=True)
    match(summary(clean), fixture["clean"], "Independent clean report")
    profile.write_bytes(original.replace("\n", "\r\n").encode())
    match(check_diff(cli, name), clean, "Physical newline conversion")
    profile.write_bytes((original + "# local review\n").encode())
    commented = check_diff(cli, name)
    require(commented["review_digest"] != clean["review_digest"], "The review token omitted an authored comment.")
    profile.write_bytes(original.encode())
    write_fixture(cli.cwd, fixture["edit"])
    edited = check_diff(cli, name, added=["D"], changed=["A"], removed=["B"], changed_packs=["review.base"])
    match(summary(edited), fixture["editExpected"], "Independent edited report")
    write_fixture(cli.cwd, files)
    (cli.cwd / "base/D.md").unlink()
    team = files["team/pack.toml"]
    base = files["base/pack.toml"]
    member_b = '\n[[policies]]\npolicy_id = "B"\npath = "B.md"\n'
    write_fixture(cli.cwd, {"team/pack.toml": team.replace("1.0.0", "1.1.0")})
    check_diff(cli, name, changed=["C"], changed_packs=["review.team"])
    write_fixture(cli.cwd, {"team/pack.toml": team + member_b, "team/B.md": files["base/B.md"],
                            "base/pack.toml": base.replace(member_b, "")})
    check_diff(cli, name, added=["B"], removed=["B"], changed_packs=["review.base", "review.team"])
    write_fixture(cli.cwd, {"base/pack.toml": base})
    check_diff(cli, name, added=["B"], changed_packs=["review.team"],
               validation_error="Duplicate effective policy IDs across selected packs.")
    write_fixture(cli.cwd, files)
    (cli.cwd / "team/B.md").unlink()
    target = clean["current_policies"][0]["target_digest"]
    exclusion = f'\n[[exclusions]]\ntarget_id = "A"\nexpected_digest = "{target}"\nreason = "Separate ownership."\n'
    conflict = '\n[[conflicts]]\nleft = "A"\nright = "C"\nreason = "Choose one."\n'
    profile.write_bytes((original + conflict).encode())
    check_diff(cli, name, text=True, validation_error="Declared conflict: A / C. Add a reviewed exclusion or replacement.")
    profile.write_bytes((original + exclusion + conflict).encode())
    match(check_diff(cli, name)["current_policies"], clean["current_policies"], "Exclusion preserves authored entries")
    write_fixture(cli.cwd, {"base/A.md": fixture["edit"]["base/A.md"]})
    check_diff(cli, name, changed=["A"], changed_packs=["review.base"], invalidated_decisions=["A"],
               validation_error="Stale target digest for 'A'; review its current content before editing expected_digest.")
    write_fixture(cli.cwd, {"base/pack.toml": base.split('[[policies]]\npolicy_id = "A"')[0]})
    check_diff(cli, name, removed=["A"], changed_packs=["review.base"], invalidated_decisions=["A"],
               validation_error="Unknown target 'A'; review IDs.")
    write_fixture(cli.cwd, files)
    profile.write_bytes(original.replace("schema_version = 1", 'schema_version = "1"').encode())
    invoke(cli, ["profile", "diff", name], error="schema_version")
    profile.write_bytes(original.encode())
    for bad, message in ((base.replace("review.base", "review.changed"), "pack_id changed"),
                         ("schema_version = [", "pack.toml")):
        write_fixture(cli.cwd, {"base/pack.toml": bad})
        invoke(cli, ["profile", "diff", name], error=message)
        invoke(cli, ["profile", "diff", name], text=True, error=message)
    write_fixture(cli.cwd, files)
    match(check_diff(cli, name), clean, "Original token after correcting inputs")


def check_explicit(cli, readers, fixture):
    """Create both pack orders, a pack with a quoted revision key, and a refused destination."""
    write_fixture(cli.cwd, fixture["files"])
    name = fixture["profileDestination"]
    options = ["--id", fixture["profileId"], "--pack", "team", "--pack", "base"]
    initialize(cli, name, options)
    match((cli.cwd / name).read_text("utf-8"), fixture["authoredProfile"], "Portable TOML")
    require((cli.cwd / name).read_bytes() == fixture["authoredProfile"].encode(), "Portable TOML bytes changed")
    read_both(readers, cli, name, local_report(cli.cwd / name))
    initialize(cli, name, options, error="already exists")
    reverse = "profiles/reverse.toml"
    initialize(cli, reverse, ["--pack", "base", "--pack", "team", "--id", "review"], text=True)
    compare = model((cli.cwd / reverse).read_text("utf-8"))
    match(compare["packs"], list(reversed(model(fixture["authoredProfile"])["packs"])), "Reverse pack order")
    read_both(readers, cli, reverse, local_report(cli.cwd / reverse))
    for options, message in ((["--pack", "base", "--id", "Upper Name"], "Invalid profile ID"),
                             (["--pack", "base", "--pack", "base"], "repeated pack"),
                             (["--pack", "builtin:baseline"], "pack.toml")):
        initialize(cli, "absent/output.toml", options, error=message)
        require(not (cli.cwd / "absent").exists(), "Invalid inputs created the output directory")
    # non-English test data
    pid = "Ops.\u00c9"
    write_fixture(cli.cwd, {"quoted pack/pack.toml": fixture["files"]["team/pack.toml"].replace('"C"', f'"{pid}"'),
                            "quoted pack/C.md": fixture["files"]["team/C.md"].replace("C", pid)})
    quoted = "profiles/quoted.toml"
    initialize(cli, quoted, ["--pack", "quoted pack"])
    require(f'"{pid}" =' in (cli.cwd / quoted).read_text("utf-8"), "Dotted policy ID needs a quoted TOML key")
    read_both(readers, cli, quoted, local_report(cli.cwd / quoted))
    shutil.copytree(cli.cwd / "team", cli.cwd / "--help")
    option_name = "profiles/option-value.toml"
    init = initialize(cli, option_name, ["--pack", "--help"])
    match([p["pack_id"] for p in init["profile"]["packs"]], ["review.team"], "An option-shaped local pack name")
    check_changes(cli, fixture, name)
    if cli.product == "java" and os.name == "posix":
        check_literal_pack_paths(cli, readers)


def check_literal_pack_paths(writer, readers):
    """Keep significant spaces in a Java-authored selector and refuse an ambiguous builtin directory."""
    for directory in (" both ", "builtin:baseline"):
        shutil.copytree(writer.cwd / "team", writer.cwd / directory)
    name = "edge-space.toml"
    initialized = initialize(writer, name, ["--pack", " both "])
    match(initialized["profile"]["packs"][0]["path"], "./ both /.", "Edge-space selector")
    read_both(readers, writer, name, local_report(writer.cwd / name))
    for reader in readers:
        error = "reserved for bundled selectors" if reader.product == "java" else "Couldn't access profile"
        initialize(replace(reader, cwd=writer.cwd), "ambiguous.toml", ["--pack", "builtin:baseline"], error=error)
    require(not (writer.cwd / "ambiguous.toml").exists(), "An ambiguous selector published a profile")


def check_competing_writers(readers):
    """Run one writer of each product against the same name and check the winner with both readers."""
    work = readers[0].cwd
    name = "race/nested/winner.toml"

    def compete(cli, pack):
        """Launch one candidate from the shared fixture directory with its own private home."""
        return subprocess.run([*cli.program, "profile", "init", name, "--pack", pack, "--id", pack, "--format", "json"],
                              env=cli.env, cwd=work, capture_output=True, timeout=35, stdin=subprocess.DEVNULL)

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(compete, cli, pack) for cli, pack in zip(readers, ("base", "team"), strict=True)]
        results = [future.result() for future in futures]
    match(sorted(r.returncode for r in results), [0, 1], "One cross-product publisher")
    require(all(not r.stderr for r in results), "Competing writers printed diagnostics outside JSON")
    winner = next(i for i, result in enumerate(results) if result.returncode == 0)
    pack = ("base", "team")[winner]
    path = work / name
    response = json.loads(results[winner].stdout)
    match(response, {"status": "ok", "path": name, "profile": model(path.read_text("utf-8"))}, "Winner's file")
    match([response["profile"]["profile_id"], [pin["pack_id"] for pin in response["profile"]["packs"]]],
          [pack, ["review." + pack]], "Winner's declared pack")
    loser = json.loads(results[1 - winner].stdout)
    require(set(loser) == {"status", "message"} and loser["status"] == "error" and "already exists" in loser["message"],
            "The other writer must report an occupied destination.")
    read_both(readers, readers[0], name, local_report(path))
    require(list(path.parent.iterdir()) == [path], "An initializer left a staging file")
    return {"successes": 1, "refusals": 1, "winner": readers[winner].product, "stagingFiles": 0}


def default_source(pin, selector):
    """Render the aggregate's simple TOML keys using its archived 26 revision pins."""
    fields = [f'schema_version = 1\nprofile_id = "local"\n\n[[packs]]\npath = {json.dumps(selector, ensure_ascii=False)}']
    fields.extend(f'{key} = "{pin[key]}"' for key in ("pack_id", "version", "digest"))
    fields.append("\n[packs.policies]")
    fields.extend(f'{pid} = "{revision}"' for pid, revision in pin["policies"].items())
    return "\n".join(fields) + "\n"


def check_default(cli, readers, baseline, wheel_pack):
    """Check both readers against each literal default selector and its own file-specific review token."""
    name = "profiles/default.toml"
    path = cli.cwd / name
    initialize(cli, name)
    selector = ("builtin:baseline" if cli.product == "java"
                else os.path.relpath(wheel_pack.resolve(), path.parent.resolve()).replace(os.sep, "/"))
    pin = tomllib.loads(baseline[0])["packs"][0]
    text = default_source(pin, selector)
    require(path.read_bytes() == text.encode(), f"{cli.product} default file differs from the pinned pack revisions")
    expected = report(text, model(text), baseline[1]["policies"])
    preview = read_both(readers, cli, name, expected)
    if cli.product == "java":
        match([digest(path.read_bytes()), expected["review_digest"]], [BUILTIN_FILE, BUILTIN_REVIEW], "Builtin identity")
    return {"sourceSha256": digest(path.read_bytes()), "profileDigest": preview["profile_digest"],
            "catalogDigest": preview["digest"], "reviewDigest": expected["review_digest"]}


def check_image(image, workspace, fixture, readers, baseline):
    """Create, inspect and recover a profile through the nonroot Dockerfile entry point."""
    image_id = subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Id}}", image],
                                       text=True, timeout=30).strip()
    inputs = workspace / "image"
    output = inputs / "profiles"
    write_fixture(inputs, fixture["files"])
    output.mkdir()
    for path in (inputs, *inputs.rglob("*")):
        path.chmod(0o755 if path.is_dir() else 0o644)
    output.chmod(0o777)
    run = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
           "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=32m",
           "--mount", f"type=bind,source={inputs},target=/inputs,readonly",
           "--mount", f"type=bind,source={output},target=/inputs/profiles",
           "--workdir", "/inputs", "--env", "POLICYNIM_PROVIDER_NVIDIA_ENABLED=false"]
    name = fixture["profileDestination"]

    def command(args, *, error=None):
        """Verify the exit and JSON envelope under the image's shipped user."""
        child = subprocess.run([*run, image_id, "profile", *args, "--format", "json"], cwd=workspace,
                               capture_output=True, timeout=40, stdin=subprocess.DEVNULL)
        require(child.returncode == (1 if error else 0) and not child.stderr,
                f"Image {args}: exit {child.returncode}, {child.stderr[:250]!r}, {child.stdout[:250]!r}")
        doc = json.loads(child.stdout)
        if error:
            require(set(doc) == {"status", "message"} and doc["status"] == "error" and error in doc["message"],
                    f"Image refusal: {doc}")
        return doc

    command(["init", name, "--pack", "missing"], error="pack.toml")
    initialized = command(["init", name, "--pack", "team", "--pack", "base", "--id", "review"])
    match(initialized, {"status": "ok", "path": name, "profile": model(fixture["authoredProfile"])}, "Image init")
    command(["init", name], error="already exists")
    raw = subprocess.check_output([*run, "--entrypoint", "cat", image_id, name], cwd=workspace, timeout=30)
    require(raw == fixture["authoredProfile"].encode(), "Image emitted different profile bytes")
    for cli in readers:
        file = cli.cwd / "profiles/image.toml"
        file.write_bytes(raw)
        match(invoke(cli, ["profile", "diff", "profiles/image.toml"]), local_report(file), "Image to installed reader")
    for edit, golden in ((None, "clean"), (fixture["edit"], "editExpected"), (fixture["files"], "clean")):
        if edit:
            write_fixture(inputs, edit)
        match(summary(command(["diff", name])), fixture[golden], "Image source review")
    require(subprocess.check_output([*run, "--entrypoint", "cat", image_id, name], timeout=30) == raw,
            "Image diff changed the profile bytes")
    default_name = "profiles/default.toml"
    default_text = default_source(tomllib.loads(baseline[0])["packs"][0], "builtin:baseline")
    match(command(["init", default_name]), {"status": "ok", "path": default_name, "profile": model(default_text)},
          "Image aggregate init")
    binary = subprocess.check_output([*run, "--entrypoint", "cat", image_id, default_name], cwd=workspace, timeout=30)
    require(binary == default_text.encode() and digest(binary) == BUILTIN_FILE, "Image aggregate bytes changed")
    match(command(["diff", default_name]), report(default_text, model(default_text), baseline[1]["policies"]),
          "Image aggregate review")
    write_fixture(inputs, fixture["edit"])
    reviewed = command(["diff", name])
    match(summary(reviewed), fixture["editExpected"], "Image dirty review")
    command(["update", name, "--expect-digest", fixture["clean"]["review_digest"]], error="Review digest changed")
    match(command(["update", name, "--expect-digest", reviewed["review_digest"]]),
          {"status": "ok", "path": name, "diff": reviewed}, "Image reviewed update")
    published = subprocess.check_output([*run, "--entrypoint", "cat", image_id, name], timeout=30)
    expected_profile = "sha256:f275017de668025621f125ed85592e3c17535ff0855e51584328ec9dc357dde7"
    match(digest(published), expected_profile, "Image updated bytes from the pinned profile fixture")
    inspect = workspace / "updated-image-review" / "work"
    write_fixture(inspect, fixture["files"] | fixture["edit"])
    inspected_profile = inspect / name
    inspected_profile.parent.mkdir()
    inspected_profile.write_bytes(published)
    clean_report = local_report(inspected_profile)
    for reader in readers:
        match(invoke(replace(reader, cwd=inspect), ["profile", "diff", name]), clean_report, "Read image update")
    match(command(["diff", name]), clean_report, "Fresh image read")
    checksum = subprocess.check_output([*run, "--entrypoint", "sha256sum", image_id, "/app/policynim.jar"],
                                       cwd=workspace, text=True, timeout=30).split()[0]
    require(len(checksum) == 64 and all(c in "0123456789abcdef" for c in checksum), "Missing image jar checksum")
    return {"imageId": image_id, "imageJarSha256": checksum, "imageReviewedToken": reviewed["review_digest"],
            "imageUpdatedProfileSha256": expected_profile, "imageCleanToken": clean_report["review_digest"]}


def main():
    """Run the native wheel, packaged jar and optional production image on independent file fixtures."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--reference-root", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--image", help="Also test the locally built Dockerfile image")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    revision = jar_hash(jar)
    dist = metadata.distribution("policynim")
    install = json.loads(dist.read_text("direct_url.json") or "{}")
    require(sys.version_info[:3] == (3, 12, 10) and dist.version == "0.3.0"
            and install.get("url", "").endswith(".whl") and "archive_info" in install, "Run the pinned noneditable wheel.")
    scripts = [dist.locate_file(p) for p in dist.files or () if p.parts[-2:] == ("bin", "policynim")]
    require(args.java and len(scripts) == 1, "Supply Java 21 and the installed console script.")
    raw = (FIXTURES / "profile-authoring.json").read_bytes()
    require(digest(raw) == FIXTURE_SHA, "The profile authoring fixture changed")
    fixture = json.loads(raw)
    reference = args.reference_root.resolve(strict=True)
    ref = subprocess.check_output(["git", "-C", str(reference), "rev-parse", "HEAD"], text=True, timeout=10).strip()
    match(ref, REFERENCE_COMMIT, "Python reference checkout")
    for name in ("policynim/services/profiles.py", "policynim/interfaces/profile_cli.py"):
        pinned = subprocess.check_output(["git", "-C", str(reference), "show", ref + ":src/" + name], timeout=10)
        require(dist.locate_file(name).read_bytes() == pinned, f"Installed wheel source changed: {name}")
    with tempfile.TemporaryDirectory() as directory, socket.socket() as sentinel:
        workspace = Path(directory)
        _, _, baseline = shipped_entries(reference, workspace / "aggregate")
        sentinel.bind(("127.0.0.1", 0))
        sentinel.listen(1)
        readers = list(launchers(workspace, args.java, jar, str(scripts[0]), sentinel.getsockname()[1]))
        identities = {}
        for cli in readers:
            cli.env.update(POLICYNIM_MCP_TRANSPORT="stdio", POLICYNIM_CORPUS_DIR="unused-corpus")
        for cli in readers:
            check_explicit(cli, readers, fixture)
            identities[cli.product] = check_default(cli, readers, baseline, dist.locate_file("policynim/policies"))
        publication = check_competing_writers(readers)
        require(not select.select([sentinel], [], [], 0)[0], "Profile authoring opened a JDBC connection.")
        receipt = {"pythonReference": ref, "javaJarSha256": revision, "fixtureSha256": FIXTURE_SHA,
                   "crossReaderProducts": [cli.product for cli in readers],
                   "existingDestinationPreserved": True, "concurrentPublication": publication,
                   "portableCleanToken": fixture["clean"]["review_digest"],
                   "portableChangedToken": fixture["editExpected"]["review_digest"], "defaultProfiles": identities}
        if args.image:
            receipt.update(check_image(args.image, workspace, fixture, readers, baseline))
    match(jar_hash(jar), revision, "Jar after checking profile authoring")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()

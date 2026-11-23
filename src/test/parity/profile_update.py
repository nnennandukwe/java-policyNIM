"""Check reviewed profile updates with the installed Python wheel and Java jar on the native filesystem."""

import argparse
import json
import os
import select
import shutil
import socket
import subprocess
import sys
import sysconfig
import tempfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from importlib import metadata
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import url2pathname

from archived_pack import REFERENCE_COMMIT
from bundled_source import jar_hash
from explicit_pack import file_digests, launchers
from profile_authoring_oracle import local_report
from profile_oracle import catalog, digest
from read_only_profiles import FIXTURES, invoke
from source_catalog import match, require, write_fixture

GOLDEN = FIXTURES / "profile-update"
CHANGES = {"added": ["D"], "changed": ["A"], "removed": ["B"], "changed_packs": ["review.base"]}
NORMAL_TOKEN = "sha256:27e2e250673b89f265136277ffde5ac38bb2afbd24979d9664b0713eb218eb11"
NORMAL_CLEAN = "sha256:049299e0c9b9a6811e1a32451804dc5674de32a9a9a66576db43d495b118544a"
STALE = "sha256:" + "0" * 64


def installed_reference(root):
    """Verify the installed wheel and the three reference source files used by this workflow."""
    dist = metadata.distribution("policynim")
    direct = json.loads(dist.read_text("direct_url.json") or "{}")
    require(sys.version_info[:3] == (3, 12, 10) and dist.version == "0.3.0", "Use the locked Python 3.12.10 runtime")
    require(direct.get("url", "").endswith(".whl"), "Install the pinned, noneditable PolicyNIM wheel")
    origin = urlsplit(direct["url"])
    require(origin.scheme == "file" and not origin.netloc, "Install the locally built reference wheel")
    wheel = jar_hash(Path(url2pathname(origin.path)).resolve(strict=True))
    recorded = direct["archive_info"].get("hashes", {})
    if "sha256" in recorded:
        match(wheel, recorded["sha256"], "Installed wheel archive hash")
    head = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True, timeout=15).strip()
    match(head, REFERENCE_COMMIT, "Pinned reference commit")
    for name in ("policynim/services/profiles.py", "policynim/storage/file_lock.py", "policynim/interfaces/profile_cli.py"):
        raw = subprocess.check_output(["git", "-C", str(root), "show", head + ":src/" + name], timeout=15)
        require(dist.locate_file(name).read_bytes() == raw, "Installed source differs from pin: " + name)
    command = Path(sysconfig.get_path("scripts")) / ("policynim.exe" if os.name == "nt" else "policynim")
    require(command.is_file(), "The installed console script is missing")
    return str(command), wheel


def command(cli, *args, code=0, text=False, message=None):
    """Run the actual command and validate its exit, output channels and error envelope."""
    result = subprocess.run([*cli.program, "profile", *args, *([] if text else ["--format=json"])],
                            cwd=cli.cwd, env=cli.env, stdin=subprocess.DEVNULL, capture_output=True, timeout=45)
    require(result.returncode == code, f"{cli.product} {args}: exit {result.returncode}, "
            f"{result.stdout.decode(errors='replace')[:400]} {result.stderr.decode(errors='replace')[:400]}")
    diagnostic = text and code != 0 or code == 2
    require(not (result.stdout if diagnostic else result.stderr), f"{cli.product} wrote to the wrong stream: {args}")
    body = (result.stderr if diagnostic else result.stdout).decode("utf-8")
    if diagnostic or text:
        if message:
            require(message.lower() in body.lower(), f"Missing recovery text: {body}")
        return body
    doc = json.loads(body)
    if code:
        match(sorted(doc), ["message", "status"], "Domain error fields")
        require(doc["status"] == "error" and message.lower() in doc["message"].lower(), f"Wrong refusal: {doc}")
    return doc


def stage_fixture(cli, fixture, variant, *, crlf=False):
    """Write one portable input and pin independently specified changes to its local packs."""
    write_fixture(cli.cwd, fixture["files"])
    edits = fixture["edit"] if variant != "selected" else {"base/A.md": fixture["edit"]["base/A.md"]}
    write_fixture(cli.cwd, edits)
    path = cli.cwd / "profiles" / "team review.toml"
    path.parent.mkdir(exist_ok=True)
    raw = (GOLDEN / (variant + "-before.toml")).read_bytes()
    path.write_bytes(raw.replace(b"\n", b"\r\n") if crlf else raw)
    return path


def cross_update(readers, writer, peer, fixture, variant):
    """Review with one installed client and apply with the other. Verify physical bytes and both fresh readers."""
    directory = writer.cwd / variant
    directory.mkdir()
    cli = replace(writer, cwd=directory)
    other = replace(peer, cwd=directory)
    path = stage_fixture(cli, fixture, variant, crlf=variant == "selected")
    name = str(path.relative_to(directory))
    changes = CHANGES if variant != "selected" else {"changed": ["A"], "changed_packs": ["review.base"]}
    expected = local_report(path, **changes)
    match(invoke(other, ["profile", "diff", name]), expected, "Review with the other client")
    if variant == "normal":
        match(expected["review_digest"], NORMAL_TOKEN, "Reviewed normal fixture")
    mode = {"normal": 0o640, "inline": 0o600, "selected": 0o644}[variant]
    if os.name == "posix":
        path.chmod(mode)
    before = file_digests(directory)
    command(cli, "update", name, "--expect-digest", STALE, code=1, message="review digest changed")
    lock = path.with_name("." + path.name + ".lock")
    require(lock.is_file(), "A parsed update must use its persistent sibling lock")
    before[lock.relative_to(directory).as_posix()] = digest(b"")
    match(file_digests(directory), before, "Refused update preserved original inputs and empty lock")
    lock_id = lock.stat()
    arg = ["--expect-digest", expected["review_digest"]] if variant == "normal" else ["--expect-digest=" + expected["review_digest"]]
    response = command(cli, "update", name, *arg)
    match(response, {"status": "ok", "path": name, "diff": expected}, "Successful update returns the reviewed diff")
    after = (GOLDEN / (variant + "-after.toml")).read_bytes().replace(b"\n", os.linesep.encode())
    require(path.read_bytes() == after, f"{cli.product} changed unexpected profile bytes ({variant})")
    before[path.relative_to(directory).as_posix()] = digest(after)
    match(file_digests(directory), before, "Only the profile and its lock may change")
    if os.name == "posix":
        match(path.stat().st_mode & 0o777, mode, "Existing profile permissions")
    require(os.path.samestat(lock_id, lock.stat()), "The update changed the identity of its persistent lock")
    clean = local_report(path)
    if variant == "normal":
        match(clean["review_digest"], NORMAL_CLEAN, "Next review token")
    entries = clean["current_policies"]
    if variant == "selected":
        entries = [row | {"state": "excluded", "reason": "Review C at the next release."}
                   if row["policy"]["policy_id"] == "C" else row for row in entries]
    expected_preview = catalog(after.decode("utf-8"), entries)
    for reader in readers:
        fresh = replace(reader, cwd=directory)
        match(invoke(fresh, ["profile", "diff", name]), clean, f"Fresh {reader.product} diff")
        match(invoke(fresh, ["profile", "preview", name]), expected_preview, "Committed policies and decisions")
    command(cli, "update", name, *arg, code=1, text=True, message="review digest changed")
    if variant == "normal":
        for _ in range(2):
            text = command(cli, "update", name, "--expect-digest", clean["review_digest"], text=True)
            require(name in text and "index hasn't changed" in text and "profile preview" in text,
                    f"Missing update or recovery instruction: {text}")
        require(path.read_bytes() == after, "Clean token can be reused")
    match(file_digests(directory), before, "Failed replay and clean update kept the committed bytes")
    match(sorted(p.name for p in path.parent.iterdir()), sorted([path.name, lock.name]), "Staging cleanup")
    return {"token": expected["review_digest"], "nextToken": clean["review_digest"], "bytesSha256": digest(after)}


def check_refusals(cli, fixture):
    """Observe the lock boundary, stale decisions and corrected retries through the public command."""
    base = cli.cwd / "refusals"
    base.mkdir()
    cli = replace(cli, cwd=base)
    command(cli, "update", "absent.toml", code=2, message="--expect-digest")
    require(not (base / ".absent.toml.lock").exists(), "An argument error created the writer lock")
    command(cli, "update", "absent.toml", "--expect-digest", STALE, code=1, message="profile")
    require((base / ".absent.toml.lock").is_file(), "A parsed update didn't acquire the lock")
    path = stage_fixture(cli, fixture, "normal")
    name = str(path.relative_to(base))
    original = path.read_bytes()
    example = '\n[[exclusions]]\ntarget_id = "B"\nexpected_digest = "' + STALE + '"\nreason = "Prior approval"\n'
    path.write_bytes(original + example.encode())
    invalid = invoke(cli, ["profile", "diff", name])
    require(invalid["validation_error"] and invalid["invalidated_decisions"] == ["B"], "Expected a blocked decision")
    command(cli, "update", name, "--expect-digest", STALE, code=1, message="review digest changed")
    command(cli, "update", name, "--expect-digest", invalid["review_digest"], code=1, message=invalid["validation_error"])
    require(path.read_bytes() == original + example.encode(), "A refusal changed an operator decision")
    path.write_bytes(original)
    if os.name == "posix":
        link = path.with_name("profile link.toml")
        link.symlink_to(path.name)
        linked = str(link.relative_to(base))
        command(cli, "update", linked, "--expect-digest", NORMAL_TOKEN, code=1, message="symlink")
        require(link.is_symlink() and path.read_bytes() == original, "Updating a symlink changed its destination")
    command(cli, "update", name, "--expect-digest", NORMAL_TOKEN)
    match(invoke(cli, ["profile", "diff", name])["review_digest"], NORMAL_CLEAN, "Recovery after fixing inputs")


def competing_writers(readers, fixture):
    """Start both actual executables with one reviewed change and verify a fresh read of the winner."""
    base = readers[0].cwd / "competition"
    base.mkdir()
    shared = [replace(cli, cwd=base) for cli in readers]
    path = stage_fixture(shared[0], fixture, "normal")
    name = str(path.relative_to(base))
    args = ["profile", "update", name, "--expect-digest", NORMAL_TOKEN, "--format=json"]

    def update(cli):
        """Attempt the same native update from a separate process and return its full result."""
        return subprocess.run([*cli.program, *args], cwd=base, env=cli.env, stdin=subprocess.DEVNULL,
                              capture_output=True, timeout=45)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(update, shared))
    match(sorted(p.returncode for p in results), [0, 1], "Exactly one changed-token update")
    for p in results:
        require(not p.stderr, "A competing writer emitted diagnostics outside JSON")
        doc = json.loads(p.stdout)
        if p.returncode:
            require(doc["status"] == "error" and "review digest changed" in doc["message"].lower(), "Second writer's token")
        else:
            match(doc["diff"]["review_digest"], NORMAL_TOKEN, "Winner's reviewed change")
    for cli in shared:
        match(invoke(cli, ["profile", "diff", name])["review_digest"], NORMAL_CLEAN, "Readers agree after contention")
    return {"winner": shared[next(i for i, p in enumerate(results) if p.returncode == 0)].product, "exits": [0, 1]}


def main():
    """Run the reviewed-update corpus and emit the source, binary and result identities."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--reference-root", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    revision = jar_hash(jar)
    executable, wheel = installed_reference(args.reference_root.resolve(strict=True))
    fixture = json.loads((FIXTURES / "profile-authoring.json").read_bytes())
    with tempfile.TemporaryDirectory() as temp, socket.socket() as sentinel:
        sentinel.bind(("127.0.0.1", 0))
        sentinel.listen(1)
        readers = list(launchers(Path(temp), args.java, jar, executable, sentinel.getsockname()[1]))
        result = {}
        for cli in readers:
            if os.name == "nt":
                home = cli.cwd.parent / "home"
                cli.env.update(SystemRoot=os.environ["SystemRoot"], USERPROFILE=str(home), APPDATA=str(home / "roaming"),
                               LOCALAPPDATA=str(home / "local"), TEMP=str(home / "tmp"), TMP=str(home / "tmp"),
                               PYTHONUTF8="1", PYTHONIOENCODING="utf-8")
        for writer, peer in (readers, readers[::-1]):
            result[writer.product] = {name: cross_update(readers, writer, peer, fixture, name)
                                      for name in ("normal", "inline", "selected")}
            check_refusals(writer, fixture)
        result["competingWriters"] = competing_writers(readers, fixture)
        from profile_update_locks import check_lock_interop
        result["nativeLockInterop"] = check_lock_interop(readers, jar, fixture)
        require(not select.select([sentinel], [], [], 0)[0], "Profile update attempted to connect to storage")
    match(jar_hash(jar), revision, "Jar changed during the comparison")
    print(json.dumps({"pythonReference": REFERENCE_COMMIT, "wheelSha256": wheel, "javaJarSha256": revision,
                      "platform": sys.platform, **result}, indent=2))


if __name__ == "__main__":
    main()

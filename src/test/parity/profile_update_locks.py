"""Verify that the installed update transactions honor each other's native writer lock."""

import json
import os
import subprocess
import time
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path

from explicit_pack import file_digests
from profile_authoring_oracle import local_report
from read_only_profiles import FIXTURES, invoke
from source_catalog import CatalogCommand, match, require, sha256, write_fixture

# The child imports its own wheel in isolated mode. The second callback follows a refused native attempt.
PYTHON_PEER = """
import os
import sys
from contextlib import contextmanager
from pathlib import Path
from policynim.services import profiles
from policynim.interfaces.cli import main
from policynim.storage.file_lock import exclusive_file_lock

role, marker, name, token = sys.argv[1:]
attempts = 0
def on_attempt():
    \"\"\"Report a retry after the first native lock attempt encountered contention.\"\"\"
    global attempts
    attempts += 1
    if attempts == 2:
        Path(marker).touch(exist_ok=False)

@contextmanager
def observed_lock(path):
    \"\"\"Keep the wheel's lock throughout the real update and signal the requested boundary.\"\"\"
    with exclusive_file_lock(path, on_wait=on_attempt if role == 'wait' else None):
        if role == 'hold':
            Path(marker).touch(exist_ok=False)
            instruction = sys.stdin.buffer.read(1)
            if instruction == b'!':
                os._exit(73)
            if instruction != b'+':
                raise RuntimeError('The test owner stopped before staging.')
        yield

profiles.exclusive_file_lock = observed_lock
sys.argv = ['policynim', 'profile', 'update', name, '--expect-digest', token, '--format', 'json']
main()
"""

TIMEOUT = 40
PEER = "io.policynim.policy.catalog.ProfileUpdateLockPeer"
LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher"


@contextmanager
def peer(cli, jar, role, marker, name, token):
    """Start the real lock owner or waiter and always reap the direct process."""
    if cli.product == "python":
        interpreter = Path(cli.program[0]).with_name("python.exe" if os.name == "nt" else "python")
        require(interpreter.is_file(), "The installed PolicyNIM command needs its adjacent virtualenv interpreter.")
        command = [str(interpreter), "-I", "-B", "-c", PYTHON_PEER]
    else:
        boot_options = cli.program[:cli.program.index("-jar")]
        classes = Path(__file__).resolve().parents[3] / "target/test-classes"
        require((classes / (PEER.replace(".", "/") + ".class")).is_file(), "Compile the Java test lock peer first.")
        command = [*boot_options, f"-Dloader.path={classes}", f"-Dloader.main={PEER}", "-cp", str(jar), LAUNCHER]
    process = subprocess.Popen([*command, role, str(marker), name, token], cwd=cli.cwd, env=cli.env,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        yield process
    finally:
        if process.poll() is None:
            process.kill()
        process.communicate(timeout=TIMEOUT)


def observed(process, marker):
    """Wait for native evidence and report startup failures without relying on a lock-acquisition delay."""
    deadline = time.monotonic() + TIMEOUT
    while not marker.is_file():
        if process.poll() is not None:
            out, err = process.communicate()
            raise AssertionError(f"{marker.name} exited {process.returncode}: {out[:600]!r} {err[:600]!r}")
        require(time.monotonic() < deadline, f"Missing native lock evidence: {marker.name}")
        time.sleep(0.02)


def finish(process, expected_exit, instruction=None):
    """Require the expected status and a single JSON response after the lock decision."""
    out, err = process.communicate(input=instruction, timeout=TIMEOUT)
    require(process.returncode == expected_exit and not err,
            f"Lock transaction exited {process.returncode}: {out[:600]!r} {err[:600]!r}")
    return json.loads(out)


def check_case(readers, jar, fixture, case, signals, *, interrupt=False):
    """Order two writers on one reviewed input, then verify publication or recovery through both CLIs."""
    profile = case / fixture["profileDestination"]
    name = profile.name
    write_fixture(case, fixture["files"] | fixture["edit"])
    profile.parent.mkdir(parents=True, exist_ok=True)
    golden = FIXTURES / "profile-update"
    profile.write_bytes((golden / "normal-before.toml").read_bytes())
    published = (golden / "normal-after.toml").read_bytes().replace(b"\n", os.linesep.encode())
    owner, waiter = [replace(cli, cwd=profile.parent) for cli in readers]
    dirty = local_report(profile, added=["D"], changed=["A"], removed=["B"], changed_packs=["review.base"])
    for reader in (owner, waiter):
        match(invoke(reader, ["profile", "diff", name]), dirty, f"{reader.product} reviewed the expected inputs")
    token = dirty["review_digest"]
    before = file_digests(case)
    relative = profile.relative_to(case).as_posix()
    sibling = profile.with_name("." + name + ".lock")
    held, contended = (signals / f"{case.name}-{event}" for event in ("owned", "contended"))

    with peer(owner, jar, "hold", held, name, token) as first:
        observed(first, held)
        lock_stat = sibling.stat()
        with peer(waiter, jar, "wait", contended, name, token) as second:
            observed(second, contended)
            match(sha256(profile.read_bytes()), before[relative], "Both writers are still before publication")
            if interrupt:
                second.kill()
                second.communicate(timeout=TIMEOUT)
                require(second.returncode != 0 and first.poll() is None, "The waiting process must stop before its owner.")
                out, err = first.communicate(input=b"!", timeout=TIMEOUT)
                require(first.returncode == 73 and not out and not err, "The lock-owning interpreter must exit before staging.")
            else:
                success = finish(first, 0, b"+")
                refusal = finish(second, 1)
                require(set(refusal) == {"status", "message"} and refusal["status"] == "error"
                        and "digest changed" in refusal["message"].lower(), f"Expected a stale token: {refusal}")

    if interrupt:
        match(file_digests(case), before | {sibling.relative_to(case).as_posix(): sha256(b"")},
              "Interrupted processes preserved every original file and the empty lock")
        result = subprocess.run([*waiter.program, "profile", "update", name, "--expect-digest", token, "--format", "json"],
                                cwd=waiter.cwd, env=waiter.env, stdin=subprocess.DEVNULL, capture_output=True, timeout=TIMEOUT)
        require(result.returncode == 0 and not result.stderr, f"Fresh retry: {result.stdout[:400]!r} {result.stderr[:400]!r}")
        success = json.loads(result.stdout)
    match(success, {"status": "ok", "path": name, "diff": dirty}, "Exactly the agreed review was published")
    require(profile.read_bytes() == published, "Lock handoff produced unexpected profile bytes.")
    clean = local_report(profile)
    for reader in (owner, waiter):
        match(invoke(reader, ["profile", "diff", name]), clean, f"{reader.product} sees the published pins")
    after_hash = sha256(published)
    expected = before | {relative: after_hash, sibling.relative_to(case).as_posix(): sha256(b"")}
    match(file_digests(case), expected, "Only the profile and its lock may change")
    match(sorted(item.name for item in profile.parent.iterdir()), sorted((name, sibling.name)), "Staging cleanup")
    final_stat = sibling.stat()
    require(lock_stat.st_ino != 0 and (final_stat.st_dev, final_stat.st_ino) == (lock_stat.st_dev, lock_stat.st_ino),
            "The persistent lock entry changed identity.")
    return {"holder": owner.product, "waiter": waiter.product, "contentionObserved": True, "reviewDigest": token,
            "cleanDigest": clean["review_digest"], "fileSha256": after_hash, "lockPreserved": True,
            **({"waiterInterrupted": True, "holderInterrupted": True, "holderExit": 73, "sameTokenRetry": 0} if interrupt
               else {"ownerExit": 0, "staleExit": 1})}


def check_lock_interop(readers: list[CatalogCommand], jar: Path, fixture: dict) -> dict:
    """Check either first writer, interrupted wait and owner exit, and an ordinary same-token retry."""
    root = readers[0].cwd / "lock-interop"
    signals = root / "signals"
    signals.mkdir(parents=True)
    return {
        "pythonOwner": check_case(readers, jar, fixture, root / "python-owner", signals),
        "javaOwner": check_case(readers[::-1], jar, fixture, root / "java-owner", signals),
        "interrupted": check_case(readers, jar, fixture, root / "interrupted", signals, interrupt=True),
    }

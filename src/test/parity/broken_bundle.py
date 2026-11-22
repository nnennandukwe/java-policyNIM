"""Exercise installed source recovery when a shipped jar is missing a policy member."""

from dataclasses import replace
from pathlib import Path
from zipfile import ZipFile

from source_catalog import match, require


def check_missing_member(cli, moved, fixture):
    """Omit one entry from a disposable jar and exercise help, failure, override and reinstall."""
    member = "security/auth-sensitive-code-review-standard.md"
    resource = f"BOOT-INF/classes/io/policynim/bundled/policies/{member}"
    incomplete = cli.cwd.parent / "incomplete.jar"
    try:
        with ZipFile(moved) as original, ZipFile(incomplete, "w") as copy:
            original.getinfo(resource)
            for item in original.infolist():
                if item.filename != resource:
                    copy.writestr(item, original.read(item))
        command = list(cli.program)
        position = command.index("-jar") + 1
        require(Path(command[position]) == moved, "Expected the relocated Java artifact")
        command[position] = str(incomplete)
        damaged = replace(cli, program=command)
        help_reply = damaged.run("validate", "--help", source=False)
        require(help_reply.returncode == 0 and b"--source" in help_reply.stdout and not help_reply.stderr.strip(),
                "Help must work with an incomplete installation")
        damaged.error("validate", fragments=[f"policies/{member}", "Reinstall", "--corpus-root"])
        match(damaged.json("list", "--corpus-root=custom"), fixture["catalog"], "Explicit source recovery")
        require(cli.json("validate")["catalog_digest"]
                == "sha256:04a26d71f3b54eb63e41e779585177ce397767cdf89da9368de3e92d61667a26",
                "A complete installation must recover the shipped catalog")
    finally:
        incomplete.unlink(missing_ok=True)

"""Verify default and overridden source catalogs in the installed Python wheel, Java jar and Linux image."""

import argparse
import hashlib
import json
import os
import select
import shutil
import socket
import subprocess
import sys
import tempfile
from dataclasses import replace
from importlib import metadata
from pathlib import Path
from zipfile import ZipFile

from archived_pack import ARCHIVE_SHA, REFERENCE_COMMIT, prepare_aggregate
from broken_bundle import check_missing_member
from explicit_pack import FIXTURE, file_digests, launchers
from source_catalog import CatalogCommand, match, require, sha256, validation

BUNDLE = "BOOT-INF/classes/io/policynim/bundled/"
LICENSE = "META-INF/licenses/policynim-bundle/LICENSE"
POLICY_ID = "SEC-AUTH-001"


def jar_hash(path):
    """Hash a packaged jar without reading the entire archive into memory."""
    with path.open("rb") as jar:
        return hashlib.file_digest(jar, "sha256").hexdigest()


def pinned_assets(reference):
    """Read the pinned Git blobs independently of checkout filters and working tree contents."""
    git = ["git", "-C", str(reference)]
    names = subprocess.check_output([*git, "ls-tree", "-rz", "--name-only", REFERENCE_COMMIT, "--",
                                     "policies", "evals", "LICENSE"], timeout=15)
    assets = {}
    for raw in names.rstrip(b"\x00").split(b"\x00"):
        name = raw.decode("utf-8")
        data = subprocess.check_output([*git, "cat-file", "blob", f"{REFERENCE_COMMIT}:{name}"], timeout=15)
        assets[name] = sha256(data)
    return assets


def check_assets(jar, assets, wheel):
    """Compare the complete asset inventory and upstream license with the pinned Git blobs."""
    expected = {name: digest for name, digest in assets.items() if name != "LICENSE"}
    for group, count in (("policies", 55), ("evals", 4)):
        group_expected = {name.removeprefix(group + "/"): digest for name, digest in expected.items()
                          if name.startswith(group + "/")}
        require(len(group_expected) == count, f"Unexpected reference file count under {group}")
        match(file_digests(wheel / group), group_expected, f"Installed wheel {group}")
    with ZipFile(jar) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename.startswith(BUNDLE) and not entry.is_dir()]
        require(len(entries) == len(expected), f"Unexpected number of resources in {jar.name}")
        contents = {entry.filename[len(BUNDLE):]: sha256(archive.read(entry)) for entry in entries}
        match(contents, expected, f"{jar.name} bundle")
        require(sha256(archive.read(LICENSE)) == assets["LICENSE"], "Bundled upstream license differs")
        require(REFERENCE_COMMIT.encode() in archive.read("META-INF/licenses/policynim-bundle/NOTICE.md"),
                "Bundled notice omits the Python source pin")


def check_views(cli, catalog):
    """Compare each public source view and its default text against the reference archive."""
    chosen = next(entry for entry in catalog["policies"] if entry["policy"]["policy_id"] == POLICY_ID)
    views = ((["list"], catalog, [catalog["digest"], POLICY_ID, "policynim.baseline@0.3.0"]),
             (["validate"], validation(catalog), [catalog["digest"], "26"]),
             (["show", POLICY_ID], chosen | {"scope": "source", "generation_id": None},
              [chosen["path"], chosen["policy"]["provenance"]["content_digest"], chosen["body"].strip()]))
    for args, view, fragments in views:
        match(cli.json(*args), view, f"{cli.product} {args} default source")
        result = cli.run(*args)
        require(result.returncode == 0 and not result.stderr.strip()
                and all(part in result.stdout.decode("utf-8") for part in fragments),
                f"{cli.product} {args} text output differs")


def write_override(directory, fixture):
    """Create the established one-policy pack with its separately recorded expected catalog."""
    directory.mkdir()
    (directory / "pack.toml").write_bytes(fixture["manifest"].encode("utf-8"))
    (directory / "policy.md").write_bytes(fixture["member"].encode("utf-8"))
    (directory / "README.md").write_bytes(b"Unlisted Markdown.\n")


def check_config_file(cli, catalog, fixture):
    """Exercise corpus selection through an explicitly named operator config file."""
    config = cli.cwd.parent / "home" / "source.env"
    selected = replace(cli, env=cli.env | {"POLICYNIM_CONFIG_FILE": str(config)})
    corpus = "POLICYNIM_CORPUS_DIR"
    profile = "POLICYNIM_PROFILE"
    custom = fixture["catalog"]
    cases = (
        ("relative file selector", f"{corpus}=custom", {}, custom),
        ("quoted export", "export 'policynim_corpus_dir'=custom # selected", {}, custom),
        ("empty quoted value with comment", f'{corpus}=""# comment', {}, catalog),
        ("quoted spaces", f"{corpus}='   '", {}, catalog),
        ("multiline spaces", f'{corpus}=" \n "', {}, catalog),
        ("duplicate bare selector", f"{corpus}=custom\n{corpus}", {}, catalog),
        ("empty override in file", f"{corpus}=custom\n{corpus}=''", {}, catalog),
        ("unrelated multiline assignment", f'OTHER="first\n{corpus}=missing\nlast"', {}, catalog),
        ("missing interpolation", f"{corpus}=" + "${UNSET_SOURCE}", {}, catalog),
        ("earlier assignment in interpolation", f"DIRECTORY=custom\n{corpus}=" + "${DIRECTORY}", {}, custom),
        ("earlier profile cleared", f"{profile}=missing\n{profile}", {}, catalog),
        ("blank environment wins", f"{corpus}=missing", {corpus: " "}, catalog),
        ("valid environment wins", f"{corpus}=missing", {corpus: "custom"}, custom),
    )
    try:
        for label, contents, env, expected in cases:
            config.write_bytes((contents + "\n").encode("utf-8"))
            command = replace(selected, env=selected.env | env)
            match(command.json("list"), expected, f"{cli.product} {label}")
        for contents, env in ((f"{profile}=''", {}), (f"{profile}=missing", {corpus: "custom"})):
            config.write_bytes((contents + "\n").encode("utf-8"))
            replace(selected, env=selected.env | env).error("list", fragments=[profile])
        config.write_bytes(b"# policynim-literal-values-v1\nDIRECTORY=custom\nPOLICYNIM_CORPUS_DIR=${DIRECTORY}\n")
        selected.error("list", fragments=["${DIRECTORY}"])
        literal = cli.cwd / "${DIRECTORY}"
        write_override(literal, fixture)
        try:
            match(selected.json("list"), custom, f"{cli.product} corrected literal directory")
        finally:
            shutil.rmtree(literal)
    finally:
        config.unlink(missing_ok=True)


def check_selection(cli, catalog, fixture):
    """Check source precedence, failure, corrected retry and the absence of extracted files."""
    override = cli.cwd / "custom"
    write_override(override, fixture)
    write_override(cli.cwd / "policies", fixture)
    generations = cli.cwd / "generations"
    generations.mkdir()
    (generations / "active.json").write_bytes(b"invalid active pointer\n")
    before = file_digests(cli.cwd.parent)
    check_views(cli, catalog)
    check_config_file(cli, catalog, fixture)
    for settings in ({"POLICYNIM_CORPUS_DIR": "  "}, {"POLICYNIM_GENERATIONS_DIR": str(generations)},
                     {"POLICYNIM_GENERATION_ID": "f" * 64}):
        chosen = replace(cli, env=cli.env | settings)
        match(chosen.json("validate"), validation(catalog), f"{cli.product} source {tuple(settings)}")

    relative = replace(cli, env=cli.env | {"POLICYNIM_CORPUS_DIR": "custom"})
    match(relative.json("list"), fixture["catalog"], f"{cli.product} relative override")
    missing = replace(cli, env=cli.env | {"POLICYNIM_CORPUS_DIR": "missing"})
    hint = "POLICYNIM_CORPUS_DIR" if cli.product == "python" else "--corpus-root"
    missing.error("validate", fragments=["missing", hint])
    failure = missing.run("validate")
    require(failure.returncode == 1 and not failure.stdout.strip() and b"missing" in failure.stderr,
            f"{cli.product} missing override must report a text error on stderr")
    if cli.product == "java":
        relative.error("validate", "--corpus-root=missing", fragments=["missing", "--corpus-root"])
        match(missing.json("list", "--corpus-root=custom"), fixture["catalog"], "Java flag wins over missing environment")
    override.rename(cli.cwd / "missing")
    try:
        match(missing.json("list"), fixture["catalog"], f"{cli.product} repaired override")
    finally:
        (cli.cwd / "missing").rename(override)
    match(file_digests(cli.cwd.parent), before, f"{cli.product} files after inspection and recovery")


def check_image(image, assets, wheel, workspace, catalog, fixture):
    """Run the actual image under its installed UID with disabled networking and a read-only root."""
    inspected = subprocess.check_output(["docker", "image", "inspect", image], timeout=30)
    image_id = json.loads(inspected)[0]["Id"]
    flags = ["--read-only", "--network", "none"]
    docker = ["docker", "run", "--rm", "--pull", "never", *flags, "-e", "POLICYNIM_PROVIDER_NVIDIA_ENABLED=false"]
    uid = int(subprocess.check_output([*docker, "--entrypoint", "id", image_id, "-u"], timeout=30))
    require(uid > 0, "The runtime image must launch as its nonroot application user")
    created = subprocess.check_output(["docker", "create", *flags, image_id], text=True, timeout=30).strip()
    jar = workspace / "image.jar"
    try:
        subprocess.run(["docker", "cp", f"{created}:/app/policynim.jar", str(jar)], check=True, timeout=60)
    finally:
        subprocess.run(["docker", "rm", created], stdout=subprocess.DEVNULL, check=True, timeout=30)
    check_assets(jar, assets, wheel)
    cli = CatalogCommand("image", [*docker, image_id], workspace, None, os.environ.copy())
    check_views(cli, catalog)
    custom = workspace / "mounted-pack"
    write_override(custom, fixture)
    custom.chmod(0o755)
    for path in custom.iterdir():
        path.chmod(0o644)
    before = file_digests(custom)
    cli.error("list", "--corpus-root=/corpus", fragments=["/corpus", "--corpus-root"])
    mounted = replace(cli, program=[*docker, "--mount", f"type=bind,source={custom},target=/corpus,readonly",
                                    "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=32m", image_id])
    match(mounted.json("list", "--corpus-root=/corpus"), fixture["catalog"], "Read-only image override")
    match(file_digests(custom), before, "Host pack after read-only image inspection")
    return {"imageId": image_id, "uid": uid, "installedJarSha256": jar_hash(jar)}


def main():
    """Run installed commands from unrelated private directories and print revision-bound evidence."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--reference-root", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--image", help="Also inspect this locally built Dockerfile image")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    reference = args.reference_root.resolve(strict=True)
    dist = metadata.distribution("policynim")
    wheel = Path(dist.locate_file("policynim")).resolve(strict=True)
    install = json.loads(dist.read_text("direct_url.json") or "{}")
    require(sys.version_info[:3] == (3, 12, 10) and dist.version == "0.3.0"
            and install.get("url", "").endswith(".whl") and "archive_info" in install,
            "Use the pinned noneditable Python wheel installation.")
    scripts = [dist.locate_file(path) for path in dist.files or () if path.parts[-2:] == ("bin", "policynim")]
    require(args.java and len(scripts) == 1 and os.access(scripts[0], os.X_OK),
            "Java and the console script from the installed Python wheel are required.")
    fixture = json.loads(FIXTURE.read_bytes())
    with tempfile.TemporaryDirectory() as directory, socket.socket() as sentinel:
        workspace = Path(directory)
        catalog = prepare_aggregate(reference, workspace / "oracle")
        sentinel.bind(("127.0.0.1", 0))
        sentinel.listen(1)
        moved = workspace / "installed.jar"
        shutil.copyfile(jar, moved)
        digest = jar_hash(moved)
        for cli in launchers(workspace, args.java, moved, str(scripts[0]), sentinel.getsockname()[1]):
            cli = replace(cli, root=None)
            cli.env.pop("POLICYNIM_CORPUS_DIR", None)
            if cli.product == "java":
                cli.env.pop("POLICYNIM_CONFIG_FILE")
            check_selection(cli, catalog, fixture)
            if cli.product == "java":
                check_missing_member(cli, moved, fixture)
        require(not select.select([sentinel], [], [], 0)[0], "Source inspection opened a JDBC connection.")
        require(jar_hash(moved) == digest, "The relocated jar changed during inspection.")
        assets = pinned_assets(reference)
        check_assets(moved, assets, wheel)
        receipt = {"referenceCommit": REFERENCE_COMMIT, "archiveSha256": ARCHIVE_SHA, "jarSha256": digest,
                   "catalogDigest": catalog["digest"], "sourcePolicies": 26, "dataFiles": 59, "shownPolicy": POLICY_ID,
                   "overrideCatalogDigest": fixture["catalog"]["digest"]}
        if args.image:
            receipt["docker"] = check_image(args.image, assets, wheel, workspace, catalog, fixture)
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()

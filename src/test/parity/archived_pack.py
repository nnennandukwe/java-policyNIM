"""Compare an installed Java jar's source catalog with the published 26-policy archive."""

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

REFERENCE_COMMIT = "fcc5322a6672b0275dcc6e0c299e631df8e8de19"
ARCHIVE_SHA = "sha256:376ba2727eb73e56696bce91eaf68ac2b6eae20b315a99b87e405cc56a380dce"
MANIFEST_SHA = "sha256:8e2e50dda6947c9a3bb08f8b6e8420a6107e6438f24e2996b4f03530a6edf1e8"
ARCHIVED_CATALOG_SHA = "sha256:56674653259895d2aaa56e2b5e97984ef50ca93a55a04dfbbbe8ef4676ea8357"
AGGREGATE_DIGEST = "sha256:04a26d71f3b54eb63e41e779585177ce397767cdf89da9368de3e92d61667a26"


def verify_digest(data, expected, name):
    """Compare the exact artifact bytes with their independently recorded SHA-256."""
    actual = "sha256:" + hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise ValueError(f"{name}: expected {expected}, received {actual}")


def prepare_aggregate(reference, destination):
    """Copy the pinned policy tree and verify the archived catalog and each selected file's raw bytes."""
    transport = reference / "artifacts/public-generation/generation.zip"
    verify_digest(transport.read_bytes(), ARCHIVE_SHA, "Generation archive")
    with ZipFile(transport) as archive:
        catalog_bytes = archive.read("catalog.json")
    verify_digest(catalog_bytes, ARCHIVED_CATALOG_SHA, "Archived catalog")
    records = json.loads(catalog_bytes)["policies"]
    if len(records) != 26:
        raise ValueError("The reference archive must contain 26 policy records")
    shutil.copytree(reference / "policies", destination, symlinks=True)
    verify_digest((destination / "pack.toml").read_bytes(), MANIFEST_SHA, "Aggregate manifest")
    for record in records:
        path = destination / record["path"]
        verify_digest(path.read_bytes(), record["policy"]["provenance"]["content_digest"], record["path"])
    return {"schema_version": 1, "scope": "source", "generation_id": None, "digest": AGGREGATE_DIGEST,
            "profile_id": None, "profile_digest": None, "pack_pins": [], "authored_count": 26,
            "effective_count": 26, "indexed_count": None, "unlisted_markdown": [], "policies": records}


def check_installed_jar(jar, reference, workspace):
    """Compare list, validate and one full show result through the installed jar in a private directory."""
    root = workspace / "policies"
    catalog = prepare_aggregate(reference, root)
    jar_digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = (Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")).resolve(strict=True)
    home = workspace / "home"
    cwd = workspace / "work"
    tmp = home / "tmp"
    tmp.mkdir(parents=True)
    cwd.mkdir()
    env = {key: os.environ[key] for key in ("SYSTEMROOT", "WINDIR", "PATH") if key in os.environ}
    env.update(HOME=str(home), USERPROFILE=str(home), TMP=str(tmp), TEMP=str(tmp), TMPDIR=str(tmp),
               POLICYNIM_PROVIDER_NVIDIA_ENABLED="false", POLICYNIM_EVAL_WORKSPACE_DIR=str(home / "evaluations"))
    chosen = next(record for record in catalog["policies"] if record["policy"]["policy_id"] == "SEC-AUTH-001")
    validation = {"status": "ok", "scope": "source", "generation_id": None, "catalog_digest": AGGREGATE_DIGEST,
                  "authored_count": 26, "effective_count": 26, "indexed_count": None, "unlisted_markdown": []}
    for args, expected in ((["list"], catalog), (["validate"], validation),
                           (["show", "SEC-AUTH-001"], chosen | {"scope": "source", "generation_id": None})):
        command = [str(java), f"-Duser.home={home}", f"-Djava.io.tmpdir={tmp}", "-jar", str(jar),
                   "policies", *args, "--source", f"--corpus-root={root}", "--format", "json"]
        result = subprocess.run(command, env=env, cwd=cwd, capture_output=True, timeout=30)
        if result.returncode or result.stderr.strip():
            raise ValueError(f"{args}: exit {result.returncode}, stdout={result.stdout[:450]!r}, stderr={result.stderr[:450]!r}")
        actual = json.loads(result.stdout)
        if json.dumps(actual, sort_keys=True) != json.dumps(expected, sort_keys=True):
            raise ValueError(f"{args}: installed response differs from the archived source contract")
    verify_digest(jar.read_bytes(), "sha256:" + jar_digest, "Installed jar after the catalog commands")
    return {"javaJarSha256": jar_digest, "referenceCommit": REFERENCE_COMMIT,
            "archiveSha256": ARCHIVE_SHA, "manifestSha256": MANIFEST_SHA, "archivedCatalogSha256": ARCHIVED_CATALOG_SHA,
            "catalogDigest": AGGREGATE_DIGEST, "policies": len(catalog["policies"]), "shownPolicy": "SEC-AUTH-001",
            "os": platform.system(), "architecture": platform.machine()}


def main():
    """Verify the installed jar against the pinned public checkout and print its evidence."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--reference-root", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as workspace:
        receipt = check_installed_jar(args.jar.resolve(strict=True), args.reference_root.resolve(strict=True), Path(workspace))
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()

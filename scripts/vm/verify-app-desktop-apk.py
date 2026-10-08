"""Bind the app/test APKs and staged factory to independently verified CI producers.

The workflow must verify these immutable producer runs succeeded before downloading
their artifacts. This guard checks the downloaded bytes, not GitHub authentication.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

NATIVE_RUN_ID = 37648306873
NATIVE_SOURCE = "6f27c7123f89251048be272e6ce6e0bbd4095a92"
CANDIDATE_RUN_ID = 37653179199
CANDIDATE_SOURCE = "b6d3b785e2ba367476e15e0deeb4f9b7efd67385"
FIXTURE_FILES = {"kernel": "kernel", "initramfs": "desktop.cpio.gz", "download": "factory.raw.xz"}


def validate_producer(run, run_id, source):
    if run.get("id") != run_id or run.get("head_sha") != source or \
            run.get("path") != ".github/workflows/vm-engine.yml" or \
            run.get("event") != "workflow_dispatch" or \
            run.get("status") != "completed" or run.get("conclusion") != "success" or \
            any(run.get(key, {}).get("full_name") != "lupixele/linex" for key in ("repository", "head_repository")):
        raise ValueError("Desktop proof requires the exact successful immutable producer")


def hash_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def regular(path, maximum):
    if path.is_symlink() or not path.is_file() or not 1 <= path.stat().st_size <= maximum:
        raise ValueError(f"Missing, linked or oversized proof input: {path.name}")


def read_json(path):
    regular(path, 1024 * 1024)
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Proof JSON must be an object")
    return value


def verify(apk, test_apk, native, fixture, manifest_sha256, abis, checkout):
    if not re.fullmatch(r"[a-f0-9]{40}", str(checkout)) or not re.fullmatch(r"[a-f0-9]{64}", manifest_sha256):
        raise ValueError("Explicit source and factory manifest pins are required")
    if not abis or len(abis) != len(set(abis)) or set(abis) - {"arm64-v8a", "x86_64"}:
        raise ValueError("Invalid exact native ABI list")
    for path in (apk, test_apk):
        regular(path, 512 * 1024 * 1024)
    manifest_path = fixture / "manifest.json"
    regular(manifest_path, 65536)
    if hash_file(manifest_path) != manifest_sha256:
        raise ValueError("Factory manifest differs from its external pin")
    manifest = read_json(manifest_path)
    if manifest.get("schema") != 1 or manifest.get("architecture") != "aarch64" or \
            manifest.get("imageId") != "debian-trixie-desktop" or \
            manifest.get("disk", {}).get("format") != "raw" or \
            manifest.get("disk", {}).get("filesystem") != "ext4" or \
            manifest.get("download", {}).get("compression") != "xz":
        raise ValueError("Unexpected production factory format")
    disk = manifest["disk"]
    if type(disk.get("bytes")) is not int or not 128 * 1024 * 1024 <= disk["bytes"] <= 32 * 1024**3 or \
            disk["bytes"] % 4096 or not re.fullmatch(r"[a-f0-9]{64}", str(disk.get("sha256", ""))):
        raise ValueError("Invalid raw factory disk pin")
    assets = {"manifest.json": {"sha256": manifest_sha256, "bytes": manifest_path.stat().st_size}}
    for key, filename in FIXTURE_FILES.items():
        item = manifest[key]
        maximum = 128 * 1024 * 1024 if key != "download" else 2 * 1024**3
        if item.get("file") != filename or type(item.get("bytes")) is not int or not 1 <= item["bytes"] <= maximum or \
                not re.fullmatch(r"[a-f0-9]{64}", str(item.get("sha256", ""))):
            raise ValueError("Unexpected candidate asset name/size/hash")
        path = fixture / filename
        regular(path, maximum)
        if path.stat().st_size != item["bytes"] or hash_file(path) != item["sha256"]:
            raise ValueError(f"Candidate asset changed: {filename}")
        assets[filename] = {"sha256": item["sha256"], "bytes": item["bytes"]}
    packaged = {}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        expected = {f"lib/{abi}/liblinex_qemu_aarch64.so" for abi in abis}
        actual = {name for name in names if name.endswith("/liblinex_qemu_aarch64.so")}
        if actual != expected or any(names.count(name) != 1 for name in expected):
            raise ValueError("APK native ABI set differs from the verified subject")
        if any(name.endswith(("factory.raw", "factory.raw.xz")) for name in names):
            raise ValueError("The factory disk must be staged privately, never embedded in the APK")
        for abi in abis:
            evidence = read_json(native / abi / "elf-evidence.json")
            library = native / abi / "liblinex_qemu_aarch64.so"
            regular(library, 256 * 1024 * 1024)
            digest = hash_file(library)
            if evidence.get("abi") != abi or evidence.get("sha256") != digest:
                raise ValueError("Native artifact differs from its verified ELF evidence")
            with library.open("rb") as stream:
                header = stream.read(20)
            if header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", header, 16) != \
                    (3, {"x86_64": 62, "arm64-v8a": 183}[abi]):
                raise ValueError("Native artifact is not the expected ELF64 shared ABI")
            name = f"lib/{abi}/{library.name}"
            entry = archive.getinfo(name)
            if entry.file_size != library.stat().st_size:
                raise ValueError("Packaged JNI bytes differ from verified producer")
            with archive.open(name) as stream:
                digest_in_apk = hashlib.file_digest(stream, "sha256").hexdigest()
            if digest_in_apk != digest:
                raise ValueError("Packaged JNI bytes differ from verified producer")
            packaged[name] = digest
    with zipfile.ZipFile(test_apk) as archive:
        if archive.namelist().count("AndroidManifest.xml") != 1 or not any(re.fullmatch(r"classes[0-9]*\.dex", name) for name in archive.namelist()):
            raise ValueError("Instrumentation APK is missing its manifest or code")
    return {"schema": 1, "apkSha256": hash_file(apk), "testApkSha256": hash_file(test_apk),
            "proofCheckout": checkout, "nativeRunId": NATIVE_RUN_ID, "nativeSource": NATIVE_SOURCE,
            "candidateRunId": CANDIDATE_RUN_ID, "candidateSource": CANDIDATE_SOURCE,
            "manifestSha256": manifest_sha256, "imageId": manifest["imageId"],
            "diskBytes": disk["bytes"], "fixtureAssets": assets, "native": packaged,
            "runtimeProofPassed": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--abi", action="append", required=True)
    parser.add_argument("--checkout", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    subject = verify(args.apk, args.test_apk, args.native, args.fixture, args.manifest_sha256, args.abi, args.checkout)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(subject, indent=2) + "\n", encoding="utf-8")

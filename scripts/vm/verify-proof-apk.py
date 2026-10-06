"""Verify the actual test APK contains the pinned fixture and verified JNI bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def digest(stream):
    result = hashlib.sha256()
    while chunk := stream.read(1024 * 1024):
        result.update(chunk)
    return result.hexdigest()


def verify(apk: Path, fixture: Path, native: Path, abis: list[str],
           network_fixture: Path | None = None) -> dict:
    fixture_sets = [(fixture, "assets/", "boot-proof.cpio.gz", "boot-proof.initramfs")]
    if network_fixture is not None:
        fixture_sets.append((network_fixture, "assets/network/", "network-proof.cpio.gz",
                             "network-proof.initramfs"))
    result = {"assets": {}, "native": {}, "kernel_boot": "pending"}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()

        def require_entry(name, size):
            if names.count(name) != 1:
                raise ValueError(f"APK must contain exactly one {name}")
            entry = archive.getinfo(name)
            if entry.file_size != size:
                raise ValueError(f"Packaged bytes/size changed: {name}")
            return entry

        for directory, prefix, initramfs_source, initramfs_asset in fixture_sets:
            manifest_bytes = (directory / "manifest.json").read_bytes()
            manifest = json.loads(manifest_bytes)
            manifest_name = prefix + "manifest.json"
            require_entry(manifest_name, len(manifest_bytes))
            if archive.read(manifest_name) != manifest_bytes:
                raise ValueError(f"Packaged fixture manifest changed: {manifest_name}")
            result["assets"][manifest_name] = hashlib.sha256(manifest_bytes).hexdigest()
            expected_assets = {
                prefix + "kernel": (directory / "kernel", manifest["kernel"]),
                prefix + initramfs_asset: (directory / initramfs_source, manifest["initramfs"]),
            }
            for name, (source, item) in expected_assets.items():
                size = item["bytes"]
                expected_hash = item["sha256"]
                if source.stat().st_size != size:
                    raise ValueError(f"Source fixture size mismatch: {source}")
                with source.open("rb") as stream:
                    if digest(stream) != expected_hash:
                        raise ValueError(f"Source fixture hash mismatch: {source}")
                require_entry(name, size)
                with archive.open(name) as stream:
                    if digest(stream) != expected_hash:
                        raise ValueError(f"Packaged fixture hash mismatch: {name}")
                result["assets"][name] = expected_hash
        for abi in abis:
            evidence = json.loads((native / abi / "elf-evidence.json").read_text())
            expected_hash = evidence["sha256"]
            if evidence["abi"] != abi or not re.fullmatch("[0-9a-f]{64}", expected_hash):
                raise ValueError(f"Invalid verified ELF evidence: {abi}")
            library = native / abi / "liblinex_qemu_aarch64.so"
            with library.open("rb") as stream:
                if digest(stream) != expected_hash:
                    raise ValueError(f"Native artifact no longer matches verified ELF: {abi}")
            name = f"lib/{abi}/{library.name}"
            require_entry(name, library.stat().st_size)
            with archive.open(name) as stream:
                if digest(stream) != expected_hash:
                    raise ValueError(f"Packaged JNI hash mismatch: {abi}")
            result["native"][name] = expected_hash
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--network-fixture", type=Path)
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--abi", action="append", choices=["x86_64", "arm64-v8a"], required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    evidence = verify(args.apk, args.fixture, args.native, args.abi, args.network_fixture)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n")

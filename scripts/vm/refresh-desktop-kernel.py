"""Refresh only the boot kernel of the accepted immutable Debian factory.

Package pins come from Ubuntu archive-key authenticated Oct08 noble-updates
metadata. This produces a candidate; it never approves a release/runtime gate.
"""
import argparse
import hashlib
import importlib.util
import json
import lzma
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

BASE_MANIFEST_SHA = "2b1ed272d4948269fefa1019be0bdd1401730e174e5f4aa5673018efd183231b"
VERSION = "6.8.0-146.146"
KERNEL_SHA = "6001f54164a5201cd5a80e0a09bb05c43fa6e910a0abfe0f5a4e8d7d781cc737"
CONFIG_SHA = "21934d8960b1c84ab93e03fa8cb269086e7a6df8a2cf6c1a21b70bd0b3bf0ddf"
PACKAGES = (
    ("pool/main/l/linux-signed/linux-image-6.8.0-146-generic_6.8.0-146.146_arm64.deb",
     18298606, "ee35dffd32c672d6b1814c0d9c8001c1656e06e718ebd5fb15565d0b7e51e418",
     "./boot/vmlinuz-6.8.0-146-generic", 18589606, KERNEL_SHA, "kernel"),
    ("pool/main/l/linux/linux-modules-6.8.0-146-generic_6.8.0-146.146_arm64.deb",
     87060672, "37b3c96d7fc3749eca42d46fa9195de1ad133fa42f5e0462d907c2132b838c3d",
     "./boot/config-6.8.0-146-generic", 337308, CONFIG_SHA, "kernel.config"),
)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked(path, size, sha):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != size or digest(path) != sha:
        raise ValueError("Kernel refresh input bytes differ from their immutable pin: " + path.name)


def refreshed_manifest(base, features):
    value = json.loads(json.dumps(base))
    value["kernel"] = {"file": "kernel", "bytes": 18589606, "sha256": KERNEL_SHA,
        "source": "https://ports.ubuntu.com/ubuntu-ports/" + PACKAGES[0][0],
        "version": VERSION, "packageSha256": PACKAGES[0][2]}
    value["kernelConfigBuiltIn"] = features
    value["kernelConfigSha256"] = CONFIG_SHA
    value["releaseReady"] = False
    value["runtimeProofPassed"] = False
    value["kernelSecurityAuditPending"] = True
    return value


def inflate_disk(compressed, destination, expected_bytes, expected_sha):
    digest_value, offset = hashlib.sha256(), 0
    with lzma.open(compressed, "rb") as source, destination.open("xb") as target:
        while chunk := source.read(1024 * 1024):
            if len(chunk) > expected_bytes - offset:
                raise ValueError("Expanded factory exceeds its immutable disk length")
            digest_value.update(chunk)
            if chunk.strip(b"\0"):
                target.seek(offset)
                target.write(chunk)
            offset += len(chunk)
        if offset != expected_bytes or digest_value.hexdigest() != expected_sha:
            raise ValueError("Expanded factory bytes differ from accepted raw disk")
        target.truncate(expected_bytes)


def refresh(candidate, output, package_cache):
    # Reuse the project's existing root/output and built-in driver checks.
    spec = importlib.util.spec_from_file_location("desktop_prepare", Path(__file__).with_name("prepare-desktop-image.py"))
    prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)
    prepare.builder.require_native_builder()
    manifest_path = candidate / "manifest.json"
    checked(manifest_path, 1708, BASE_MANIFEST_SHA)
    base = json.loads(manifest_path.read_text())
    for key, filename in (("kernel", "kernel"), ("initramfs", "desktop.cpio.gz"), ("download", "factory.raw.xz")):
        checked(candidate / filename, base[key]["bytes"], base[key]["sha256"])
    lock = candidate / "desktop-image-lock.json"
    checked(lock, 766559, base["lockSha256"])
    output = prepare.builder.fresh_output(output)
    downloaded = output / "package-evidence"
    downloaded.mkdir()
    evidence = []
    for relative, size, sha, member_name, member_size, member_sha, filename in PACKAGES:
        package = downloaded / Path(relative).name
        local = package_cache / package.name if package_cache else None
        url = "https://ports.ubuntu.com/ubuntu-ports/" + relative
        if local and local.exists():
            checked(local, size, sha)
            shutil.copyfile(local, package)
        else:
            with urllib.request.urlopen(url, timeout=60) as source, package.open("xb") as target:
                count = 0
                while chunk := source.read(1024 * 1024):
                    count += len(chunk)
                    if count > size: raise ValueError("Kernel package exceeds pin")
                    target.write(chunk)
        checked(package, size, sha)
        archive_path = downloaded / (filename + ".tar")
        try:
            with archive_path.open("xb") as target:
                subprocess.run(["dpkg-deb", "--fsys-tarfile", str(package)], check=True,
                    stdout=target, stderr=subprocess.PIPE, timeout=120)
            with tarfile.open(archive_path) as archive:
                matches = [item for item in archive if item.name == member_name]
                if len(matches) != 1 or not matches[0].isfile() or matches[0].size != member_size:
                    raise ValueError("Missing or linked exact kernel package member")
                with archive.extractfile(matches[0]) as source, (output / filename).open("xb") as target:
                    shutil.copyfileobj(source, target, 1024 * 1024)
            checked(output / filename, member_size, member_sha)
        finally:
            archive_path.unlink(missing_ok=True)
        evidence.append({"url": url, "bytes": size, "sha256": sha,
            "member": member_name, "memberSha256": member_sha})
    config = (output / "kernel.config").read_bytes()
    features = {**prepare.storage.verify_storage_config(config), **prepare.storage.network.verify_network_config(config)}
    for name in ("CONFIG_UNIX", "CONFIG_DEVTMPFS", "CONFIG_BLK_DEV_INITRD", "CONFIG_RD_GZIP"):
        if (name + "=y\n").encode() not in config:
            raise ValueError("Required desktop boot driver is not built in: " + name)
        features[name] = "y"
    for filename in ("desktop.cpio.gz", "factory.raw.xz", "desktop-image-lock.json"):
        shutil.copyfile(candidate / filename, output / filename)
    inflate_disk(output / "factory.raw.xz", output / "factory.raw", base["disk"]["bytes"], base["disk"]["sha256"])
    value = refreshed_manifest(base, features)
    (output / "manifest.json").write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    (output / "kernel-evidence.json").write_text(json.dumps({"kernelVersion": VERSION,
        "baseManifestSha256": BASE_MANIFEST_SHA, "packages": evidence,
        "sourceAuthentication": "Ubuntu archive fingerprint F6ECB3762474EDA9D21B7022871920D1991BC93C",
        "runtimeProofPassed": False}, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package-cache", type=Path)
    args = parser.parse_args()
    refresh(args.candidate, args.output, args.package_cache)

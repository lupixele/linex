#!/usr/bin/env python3
"""Assemble an authenticated provisioned staging root into a private factory disk."""
import argparse
import gzip
import importlib.util
import json
import lzma
import os
from pathlib import Path
import stat
import struct
import subprocess
import sys


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


builder = module("linex_desktop_builder", "build-desktop-image.py")
storage = module("linex_desktop_storage", "build-storage-fixture.py")
fixture = storage.fixture
DISK_BYTES = 4 * 1024 * 1024 * 1024
INIT = storage.SWITCH_INIT.replace(b"/bin/sh /linex-storage-init", b"/usr/bin/python3 /linex-desktop-init.py")
INIT = INIT.replace(b"LINEX_VM_STORAGE_", b"LINEX_VM_DESKTOP_")


def verify_static_busybox(data):
    if not 64 <= len(data) <= 4 * 1024 * 1024 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("Expected bounded ELF64 little-endian BusyBox")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Expected ARM64 BusyBox")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size != 56 or not 1 <= count <= 128 or offset + size * count > len(data):
        raise ValueError("Invalid BusyBox ELF program header table")
    for index in range(count):
        if struct.unpack_from("<I", data, offset + index * size)[0] in (2, 3):
            raise ValueError("Initramfs BusyBox must have no dynamic section or interpreter")
    return data


def initramfs(busybox):
    entries = {name: fixture.Entry(stat.S_IFDIR | 0o755) for name in ("bin", "dev", "proc", "sys", "newroot")}
    entries["bin/busybox"] = fixture.Entry(stat.S_IFREG | 0o755, busybox)
    for name in ("sh", "mount", "sleep"):
        entries["bin/" + name] = fixture.Entry(stat.S_IFLNK | 0o777, b"/bin/busybox")
    entries["init"] = fixture.Entry(stat.S_IFREG | 0o755, INIT)
    entries["dev/console"] = fixture.Entry(stat.S_IFCHR | 0o600, rdevmajor=5, rdevminor=1)
    entries["dev/null"] = fixture.Entry(stat.S_IFCHR | 0o666, rdevmajor=1, rdevminor=3)
    encoded = b"".join(fixture.newc_record(name, entries[name], index + 1) for index, name in enumerate(sorted(entries)))
    encoded += fixture.newc_record("TRAILER!!!", fixture.Entry(0), len(entries) + 1)
    output = bytearray(gzip.compress(encoded, compresslevel=9, mtime=0))
    output[9] = 255
    return bytes(output)


def prepare(provisioned, output):
    builder.require_native_builder()
    for path in [provisioned.absolute()] + list(provisioned.absolute().parents):
        if path.is_symlink():
            raise ValueError("Provisioned source cannot contain symlink ancestors")
    provisioned = provisioned.resolve()
    if not provisioned.is_relative_to((Path.cwd() / "dist").resolve()):
        raise ValueError("Provisioned source must stay within project dist")
    lock_path = provisioned / "desktop-image-lock.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    proof = json.loads((provisioned / "provisioning-proof.json").read_text(encoding="utf-8"))
    if lock.get("stage") != "authenticated-provisioning" or lock.get("imageId") != "debian-trixie-desktop" or \
            proof.get("passed") is not True or proof.get("lockSha256") != builder.sha256(lock_path):
        raise ValueError("Missing authenticated Debian provisioning evidence")
    root = provisioned / "staging/root"
    if root.is_symlink() or root.parent.is_symlink() or not root.is_dir() or root.stat().st_uid != 0:
        raise ValueError("Expected builder-owned fresh Debian root")
    output = builder.fresh_output(output)
    # Refuse already-prepared roots; never overwrite a prior factory or user disk.
    script = root / "linex-desktop-init.py"
    with script.open("xb") as stream:
        stream.write(Path(__file__).with_name("guest").joinpath("desktop-init.py").read_bytes())
    script.chmod(0o755)
    dhcp = root / "linex-dhcp"
    with dhcp.open("xb") as stream:
        stream.write(storage.network.DHCP)
    dhcp.chmod(0o755)
    subprocess.run(["chroot", str(root), "useradd", "--uid", "1000", "--create-home", "--shell", "/bin/bash", "linex"], check=True, timeout=30)
    subprocess.run(["chroot", str(root), "passwd", "--lock", "linex"], check=True, timeout=30)
    # Guest control starts the session directly: no auto-login/password/SSH service.
    home = root / "home/linex"
    config = home / ".config/xfce4/xfconf/xfce-perchannel-xml"
    config.mkdir(parents=True, mode=0o700)
    (config / "xfwm4.xml").write_text('<?xml version="1.0" encoding="UTF-8"?>\n'
        '<channel name="xfwm4" version="1.0"><property name="general" type="empty">'
        '<property name="use_compositing" type="bool" value="false"/></property></channel>\n', encoding="utf-8")
    for path in [home] + list(home.rglob("*")):
        os.chown(path, 1000, 1000, follow_symlinks=False)
    (root / "etc/machine-id").write_bytes(b"")
    (root / "etc/hostname").write_text("linex\n", encoding="ascii")
    # Retain exact cached binaries next to factory output, not duplicated inside guest.
    package_cache = root / "var/cache/apt/archives"
    archived = output / "packages"
    archived.mkdir()
    for package in package_cache.glob("*.deb"):
        package.rename(archived / package.name)
    static_busybox = verify_static_busybox((root / "bin/busybox").read_bytes())
    subprocess.run(["chroot", str(root), "/bin/busybox", "--help"], check=True, capture_output=True, timeout=5)
    boot = initramfs(static_busybox)
    cache = provisioned / "boot-downloads"
    kernel = fixture.download_verified(fixture.KERNEL_URL, fixture.KERNEL_SHA256, cache)
    config_bytes = fixture.verify_bytes(storage.network.read_kernel_config(storage.network.download_package(cache)), storage.network.CONFIG_SHA256)
    features = {**storage.verify_storage_config(config_bytes), **storage.network.verify_network_config(config_bytes)}
    disk = output / "factory.raw"
    with disk.open("xb") as stream:
        stream.truncate(DISK_BYTES)
    subprocess.run(["mke2fs", "-q", "-t", "ext4", "-b", "4096", "-d", str(root), "-L", "LINEX_DEBIAN",
                    "-U", "80523db9-2195-4f40-91a0-99aafabc88d1", "-E",
                    "lazy_itable_init=0,lazy_journal_init=0,root_owner=0:0", "-O", "^orphan_file",
                    str(disk), str(DISK_BYTES // 4096)], check=True, timeout=180)
    subprocess.run(["e2fsck", "-f", "-n", str(disk)], check=True, timeout=60)
    compressed = output / "factory.raw.xz"
    with disk.open("rb") as source, compressed.open("xb") as target:
        compressor = lzma.LZMACompressor(format=lzma.FORMAT_XZ, preset=3)
        while chunk := source.read(1024 * 1024):
            target.write(compressor.compress(chunk))
        target.write(compressor.flush())
    (output / "kernel").write_bytes(kernel)
    (output / "desktop.cpio.gz").write_bytes(boot)
    (output / "desktop-image-lock.json").write_bytes(lock_path.read_bytes())
    manifest = {"schema": 1, "imageId": "debian-trixie-desktop", "architecture": "aarch64",
                "purpose": "Authenticated Debian XFCE/Firefox factory image awaiting actual boot/browser proof",
                "releaseReady": False, "runtimeProofPassed": False, "packageCount": lock["packageCount"],
                "browserVersion": lock["browserVersion"], "lockSha256": builder.sha256(lock_path),
                "kernel": {"file": "kernel", "source": fixture.KERNEL_URL, "sha256": builder.sha256(output / "kernel"), "bytes": len(kernel)},
                "initramfs": {"file": "desktop.cpio.gz", "sha256": builder.sha256(output / "desktop.cpio.gz"), "bytes": len(boot)},
                "disk": {"file": disk.name, "format": "raw", "filesystem": "ext4", "sha256": builder.sha256(disk), "bytes": DISK_BYTES},
                "download": {"file": compressed.name, "compression": "xz", "sha256": builder.sha256(compressed), "bytes": compressed.stat().st_size},
                "kernelConfigBuiltIn": features, "kernelSecurityAuditPending": True,
                "controlProtocol": "bounded JSON lines, launch/stop, per-launch random credential", "consoleGuestPort": 5901}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provisioned", type=Path, default=Path("dist/vm-desktop-packages"))
    parser.add_argument("--output", type=Path, default=Path("dist/vm-desktop-image"))
    args = parser.parse_args()
    prepare(args.provisioned, args.output)

#!/usr/bin/env python3
"""Build a pinned ARM64 Linux boot fixture without extracting a tar on the host.

The Ubuntu kernel and Alpine userspace are proof assets, not a desktop image.
All archive paths remain POSIX names inside generated newc bytes, including links.
"""
import argparse
from dataclasses import dataclass
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import urllib.request

KERNEL_URL = "https://cloud-images.ubuntu.com/noble/20260926/unpacked/noble-server-cloudimg-arm64-vmlinuz-generic"
KERNEL_SHA256 = "71fe6776ef591ea452661a5933e11d2044c5df6aaf76cf39b8d9655fa4e47904"
ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.6-aarch64.tar.gz"
ROOTFS_SHA256 = "821565fa8f3953eefd12497b166b4b50add2f7c57fb312e75862f5867e06fefe"
MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024

INIT = b'''#!/bin/sh
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
mount -t proc proc /proc || exit 1
mount -t sysfs sysfs /sys || exit 1
mount -t devtmpfs devtmpfs /dev || exit 1
mkdir -p /run /tmp
mount -t tmpfs tmpfs /run || exit 1
exec </dev/console >/dev/console 2>&1
children=""
cleanup() {
    for child in $children; do kill "$child" 2>/dev/null || true; done
    for child in $children; do wait "$child" 2>/dev/null || true; done
    children=""
}
shutdown_guest() {
    cleanup
    echo LINEX_VM_SHUTDOWN
    sync
    poweroff -f
}
trap shutdown_guest TERM INT
count=0
while [ "$count" -lt 64 ]; do
    sleep 86400 &
    children="$children $!"
    count=$((count + 1))
done
for child in $children; do
    if ! kill -0 "$child" 2>/dev/null; then
        echo LINEX_VM_CHILD_FAILURE
        cleanup
        poweroff -f
    fi
done
echo "LINEX_VM_KERNEL arch=$(uname -m) version=$(uname -r)"
echo "LINEX_VM_GUEST_CHILDREN count=$count"
echo LINEX_VM_BOOT_OK
# PID 1 retains all 64 children. The private serial controller requests status
# or clean poweroff; no automatic timeout can silently remove the boot proof.
while true; do
    if IFS= read -r command; then
        case "$command" in
            status)
                alive=0
                for child in $children; do
                    if kill -0 "$child" 2>/dev/null; then alive=$((alive + 1)); fi
                done
                echo "LINEX_VM_GUEST_CHILDREN count=$alive"
                ;;
            stop|poweroff) shutdown_guest ;;
            *) echo LINEX_VM_UNKNOWN_COMMAND ;;
        esac
    else
        sleep 1
    fi
done
'''


@dataclass(frozen=True)
class Entry:
    mode: int
    data: bytes = b""
    rdevmajor: int = 0
    rdevminor: int = 0


def normalized_name(name):
    if "\\" in name or "\0" in name or name.startswith("/") or re.match(r"^[A-Za-z]:", name):
        raise ValueError("Non-POSIX or absolute archive path")
    path = PurePosixPath(name)
    if ".." in path.parts:
        raise ValueError("Archive path traversal")
    return str(path)


def verify_bytes(data, expected):
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise ValueError(f"SHA256 mismatch: expected {expected}, got {actual}")
    return data


def download_verified(url, expected, cache):
    target = cache / expected
    if target.exists():
        return verify_bytes(target.read_bytes(), expected)
    request = urllib.request.Request(url, headers={"User-Agent": "Linex-VM-fixture/1"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(MAX_DOWNLOAD_BYTES + 1)
    if len(data) > MAX_DOWNLOAD_BYTES:
        raise ValueError("Boot fixture download exceeds size limit")
    verify_bytes(data, expected)
    cache.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".part")
    temporary.write_bytes(data)
    temporary.replace(target)
    return data


def read_rootfs(data, max_bytes=128 * 1024 * 1024, max_entries=20000):
    entries = {}
    hardlinks = {}
    total = 0
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for index, member in enumerate(archive):
            if index >= max_entries:
                raise ValueError("Too many rootfs entries")
            name = normalized_name(member.name)
            if name == ".":
                continue
            if name in entries or name in hardlinks:
                raise ValueError(f"Duplicate rootfs entry: {name}")
            if member.size < 0 or total + member.size > max_bytes:
                raise ValueError("Uncompressed rootfs exceeds limit")
            total += member.size
            permissions = member.mode & 0o777
            if member.isdir():
                entries[name] = Entry(stat.S_IFDIR | permissions)
            elif member.isfile():
                stream = archive.extractfile(member)
                if stream is None:
                    raise ValueError("Missing archive payload")
                payload = stream.read(member.size + 1)
                if len(payload) != member.size:
                    raise ValueError("Invalid archive payload length")
                entries[name] = Entry(stat.S_IFREG | permissions, payload)
            elif member.issym():
                if "\0" in member.linkname:
                    raise ValueError("Invalid guest symlink")
                entries[name] = Entry(stat.S_IFLNK | permissions, member.linkname.encode())
            elif member.islnk():
                hardlinks[name] = normalized_name(member.linkname)
            else:
                # Rootfs device nodes cannot be created on Windows and are not
                # needed here: /init mounts devtmpfs after the console bootstrap.
                raise ValueError(f"Unsupported rootfs entry type: {name}")
    resolving = set()

    def resolve(name):
        if name in entries:
            entry = entries[name]
            if stat.S_IFMT(entry.mode) != stat.S_IFREG:
                raise ValueError("Hardlink target is not a regular file")
            return entry
        if name not in hardlinks or name in resolving:
            raise ValueError("Missing or cyclic hardlink target")
        resolving.add(name)
        entry = resolve(hardlinks[name])
        resolving.remove(name)
        entries[name] = entry
        return entry

    for name in hardlinks:
        resolve(name)
    if sum(len(entry.data) for entry in entries.values()) > max_bytes:
        raise ValueError("Resolved rootfs payloads exceed limit")
    for name in list(entries):
        for parent in PurePosixPath(name).parents:
            if str(parent) == ".":
                continue
            if str(parent) not in entries:
                entries[str(parent)] = Entry(stat.S_IFDIR | 0o755)
            elif stat.S_IFMT(entries[str(parent)].mode) != stat.S_IFDIR:
                raise ValueError("Archive entry has a non-directory ancestor")
    return entries


def newc_record(name, entry, inode):
    encoded = name.encode() + b"\0"
    values = (inode, entry.mode, 0, 0, 1, 0, len(entry.data), 0, 0,
              entry.rdevmajor, entry.rdevminor, len(encoded), 0)
    header = b"070701" + "".join(f"{value:08x}" for value in values).encode()
    if len(header) != 110:
        raise ValueError("newc metadata exceeds format limits")
    prefix = header + encoded
    return prefix + b"\0" * (-len(prefix) % 4) + entry.data + b"\0" * (-len(entry.data) % 4)


def make_initramfs(root):
    entries = dict(root)
    for directory in ("dev", "proc", "sys", "run", "tmp"):
        entries[directory] = Entry(stat.S_IFDIR | 0o755)
    entries["dev/console"] = Entry(stat.S_IFCHR | 0o600, rdevmajor=5, rdevminor=1)
    entries["dev/null"] = Entry(stat.S_IFCHR | 0o666, rdevmajor=1, rdevminor=3)
    entries["init"] = Entry(stat.S_IFREG | 0o755, INIT)
    data = b"".join(newc_record(name, entries[name], index + 1)
                    for index, name in enumerate(sorted(entries)))
    data += newc_record("TRAILER!!!", Entry(0), len(entries) + 1)
    compressed = bytearray(gzip.compress(data, compresslevel=9, mtime=0))
    # Python 3.11/3.12 may inherit zlib's host OS byte for mtime=0.
    # Normalize it so Linux CI and Windows fixture generation are identical.
    compressed[9] = 255
    return bytes(compressed)


def build(output):
    output.mkdir(parents=True, exist_ok=True)
    cache = output / "downloads"
    print("Downloading and verifying pinned boot assets...", flush=True)
    kernel = download_verified(KERNEL_URL, KERNEL_SHA256, cache)
    rootfs = download_verified(ROOTFS_URL, ROOTFS_SHA256, cache)
    initramfs = make_initramfs(read_rootfs(rootfs))
    (output / "kernel").write_bytes(kernel)
    (output / "boot-proof.cpio.gz").write_bytes(initramfs)
    manifest = {
        "schema": 1,
        "purpose": "ARM64 Linux kernel boot and 64 persistent guest-child proof; not a desktop",
        "architecture": "aarch64",
        "kernel": {"file": "kernel", "source": KERNEL_URL, "sha256": KERNEL_SHA256, "bytes": len(kernel)},
        "rootfs": {"source": ROOTFS_URL, "sha256": ROOTFS_SHA256, "bytes": len(rootfs)},
        "initramfs": {"file": "boot-proof.cpio.gz", "sha256": hashlib.sha256(initramfs).hexdigest(), "bytes": len(initramfs)},
        "guestChildCount": 64,
        "serialBootMarker": "LINEX_VM_BOOT_OK",
        "serialStopCommand": "stop",
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("dist/vm-fixture"))
    build(parser.parse_args().output)

#!/usr/bin/env python3
"""Pinned Alpine raw-ext4/switch-root proof assets; Linux builder, not desktop image."""
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import platform
import stat
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location("linex_storage_network_assets", Path(__file__).with_name("build-network-fixture.py"))
network = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = network
spec.loader.exec_module(network)
fixture = network.fixture

ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz"
ROOTFS_SHA256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
DISK_BYTES = 128 * 1024 * 1024
STORAGE_FEATURES = ("CONFIG_VIRTIO", "CONFIG_VIRTIO_MMIO", "CONFIG_VIRTIO_BLK",
                    "CONFIG_EXT4_FS", "CONFIG_JBD2", "CONFIG_FS_MBCACHE")

SWITCH_INIT = b'''#!/bin/sh
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
exec </dev/console >/dev/console 2>&1
failure() { echo LINEX_VM_STORAGE_MOUNT_FAILED; /bin/busybox poweroff -f; while true; do sleep 1; done; }
mount -t proc proc /proc || failure
mount -t sysfs sysfs /sys || failure
mount -t devtmpfs devtmpfs /dev || failure
count=0
while [ ! -b /dev/vda ]; do
    count=$((count + 1))
    [ "$count" -le 30 ] || failure
    sleep 1
done
# ext4 replays its journal before presenting the writable root. Never format.
mount -t ext4 -o rw /dev/vda /newroot || failure
for directory in proc sys dev; do
    mount --move /"$directory" /newroot/"$directory" || failure
done
echo LINEX_VM_STORAGE_SWITCH_ROOT
exec /bin/busybox switch_root -c /dev/console /newroot /bin/sh /linex-storage-init
'''

ROOT_INIT = b'''#!/bin/sh
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
exec </dev/console >/dev/console 2>&1
stty -echo || exit 1
mount -t tmpfs tmpfs /run || exit 1
mkdir -p /home/linex
chown 1000:1000 /home/linex || exit 1
chmod 0700 /home/linex || exit 1
source=$(awk '$2 == "/" { print $1; exit }' /proc/mounts)
kind=$(awk '$2 == "/" { print $3; exit }' /proc/mounts)
[ "$source" = /dev/vda ] && [ "$kind" = ext4 ] || exit 1
echo "LINEX_VM_STORAGE_ROOT source=$source fstype=$kind"
echo "LINEX_VM_KERNEL arch=$(uname -m) version=$(uname -r)"
for path in / /bin/busybox /etc/shadow /root; do
    echo "LINEX_VM_STORAGE_METADATA path=$path uid=$(stat -c '%u' "$path") gid=$(stat -c '%g' "$path") mode=$(stat -c '%a' "$path")"
done
status() {
    if [ -f /home/linex/nonce ]; then
        nonce=$(cat /home/linex/nonce)
        owner=$(stat -c '%u' /home/linex/nonce)
        echo "LINEX_VM_STORAGE_STATUS nonce=$nonce uid=$owner"
    else
        echo "LINEX_VM_STORAGE_STATUS nonce=none uid=none"
    fi
}
echo LINEX_VM_STORAGE_READY
status
while true; do
    if IFS= read -r command; then
        case "$command" in
            write\\ *)
                nonce=${command#write }
                case "$nonce" in ''|*[!a-f0-9]*) echo LINEX_VM_STORAGE_BAD_NONCE; continue ;; esac
                if [ "${#nonce}" -ne 32 ]; then echo LINEX_VM_STORAGE_BAD_NONCE; continue; fi
                printf '%s\\n' "$nonce" > /run/storage-request
                chmod 0644 /run/storage-request
                if su -s /bin/sh -c /linex-storage-write linex; then
                    stored=$(cat /home/linex/nonce)
                    owner=$(stat -c '%u' /home/linex/nonce)
                    if [ "$stored" = "$nonce" ] && [ "$owner" = 1000 ]; then
                        echo "LINEX_VM_STORAGE_WRITTEN nonce=$stored uid=$owner"
                    else
                        echo LINEX_VM_STORAGE_WRITE_FAILED
                    fi
                else
                    echo LINEX_VM_STORAGE_WRITE_FAILED
                fi
                ;;
            status) status ;;
            stop)
                sync
                # Remount read-only proves an orderly root-filesystem close.
                mount -t ext4 -o remount,ro /dev/vda / || { echo LINEX_VM_STORAGE_STOP_FAILED; continue; }
                echo LINEX_VM_STORAGE_CLEAN_STOP
                poweroff -f
                ;;
            *) echo LINEX_VM_STORAGE_UNKNOWN_COMMAND ;;
        esac
    else
        sleep 1
    fi
done
'''

NONCE_WRITE = b'''#!/bin/sh
set -eu
nonce=$(cat /run/storage-request)
case "$nonce" in ''|*[!a-f0-9]*) exit 1 ;; esac
[ "${#nonce}" -eq 32 ] && [ "$(id -u)" = 1000 ] || exit 1
umask 077
printf '%s\\n' "$nonce" > /home/linex/nonce
sync
'''


def verify_storage_config(config):
    values = dict(line.split("=", 1) for line in config.decode("ascii").splitlines()
                  if line.startswith("CONFIG_") and "=" in line)
    if any(values.get(name) != "y" for name in STORAGE_FEATURES):
        raise ValueError("Storage proof requires every block/ext4 driver built in")
    return {name: values[name] for name in STORAGE_FEATURES}


def write_staging(entries, destination, require_root_ownership=False):
    """Fresh private directory only; validate all parents before creating guest links."""
    if destination.exists() or destination.is_symlink():
        raise ValueError("Storage staging destination must be fresh")
    if require_root_ownership and (os.name != "posix" or os.geteuid() != 0):
        raise RuntimeError("Guest root metadata requires a Linux-root staging builder")
    for name, entry in entries.items():
        if fixture.normalized_name(name) != name or name == ".":
            raise ValueError("Invalid staging path")
        if stat.S_IFMT(entry.mode) not in (stat.S_IFDIR, stat.S_IFREG, stat.S_IFLNK):
            raise ValueError("Unsupported staging entry")
        for parent in PurePosixPath(name).parents:
            if str(parent) != "." and (str(parent) not in entries or
                    stat.S_IFMT(entries[str(parent)].mode) != stat.S_IFDIR):
                raise ValueError("Staging entry has a non-directory ancestor")
    destination.mkdir(mode=0o700)
    for name in sorted(entries, key=lambda value: (len(PurePosixPath(value).parts), value)):
        entry = entries[name]
        if stat.S_IFMT(entry.mode) == stat.S_IFDIR:
            (destination / name).mkdir(mode=0o755)
    for name, entry in entries.items():
        if stat.S_IFMT(entry.mode) == stat.S_IFREG:
            path = destination / name
            with path.open("xb") as stream:
                stream.write(entry.data)
            path.chmod(entry.mode & 0o777)
    for name, entry in entries.items():
        if stat.S_IFMT(entry.mode) == stat.S_IFLNK:
            os.symlink(entry.data.decode("utf-8"), destination / name)
    # Apply directory metadata after populating children, including private0700
    # directories. Do not chmod a guest symlink or follow its absolute target.
    for name, entry in entries.items():
        path = destination / name
        if require_root_ownership:
            os.chown(path, 0, 0, follow_symlinks=False)
        if stat.S_IFMT(entry.mode) == stat.S_IFDIR:
            path.chmod(entry.mode & 0o7777)
    if require_root_ownership:
        os.chown(destination, 0, 0)
    # The enclosing TemporaryDirectory remains0700; guest root itself is0755.
    destination.chmod(0o755)


def make_storage_root(root):
    result = dict(root)
    for name in ("dev", "proc", "sys", "run", "tmp", "home", "home/linex"):
        result[name] = fixture.Entry(stat.S_IFDIR | 0o755)
    result["linex-storage-init"] = fixture.Entry(stat.S_IFREG | 0o755, ROOT_INIT)
    result["linex-storage-write"] = fixture.Entry(stat.S_IFREG | 0o755, NONCE_WRITE)
    result["etc/passwd"] = fixture.Entry(stat.S_IFREG | 0o644,
        b"root:x:0:0:root:/root:/bin/sh\nlinex:x:1000:1000:Linex:/home/linex:/bin/sh\n")
    result["etc/group"] = fixture.Entry(stat.S_IFREG | 0o644, b"root:x:0:\nlinex:x:1000:\n")
    result["etc/shadow"] = fixture.Entry(stat.S_IFREG | 0o600,
        b"root:!:0:0:99999:7:::\nlinex:!:0:0:99999:7:::\n")
    return result


def make_storage_initramfs(root):
    wanted = ("bin/busybox", "lib/ld-musl-aarch64.so.1", "lib/libc.musl-aarch64.so.1")
    if any(name not in root for name in wanted):
        raise ValueError("Storage initramfs requires pinned BusyBox and musl loader")
    entries = {name: root[name] for name in wanted}
    for applet in ("sh", "mount", "sleep"):
        entries[f"bin/{applet}"] = fixture.Entry(stat.S_IFLNK | 0o777, b"/bin/busybox")
    for name in ("bin", "lib", "dev", "proc", "sys", "newroot"):
        entries[name] = fixture.Entry(stat.S_IFDIR | 0o755)
    entries["dev/console"] = fixture.Entry(stat.S_IFCHR | 0o600, rdevmajor=5, rdevminor=1)
    entries["dev/null"] = fixture.Entry(stat.S_IFCHR | 0o666, rdevmajor=1, rdevminor=3)
    entries["init"] = fixture.Entry(stat.S_IFREG | 0o755, SWITCH_INIT)
    payload = b"".join(fixture.newc_record(name, entries[name], index + 1)
                       for index, name in enumerate(sorted(entries)))
    payload += fixture.newc_record("TRAILER!!!", fixture.Entry(0), len(entries) + 1)
    result = bytearray(gzip.compress(payload, compresslevel=9, mtime=0))
    result[9] = 255
    return bytes(result)


def hash_file(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def build(output, asset_cache, mke2fs):
    if platform.system() != "Linux":
        raise RuntimeError("Raw ext4 fixture generation requires Linux mke2fs; no phone root required")
    if os.geteuid() != 0:
        raise RuntimeError("Run the disposable Linux CI builder with sudo to preserve guest UID0 metadata")
    output.mkdir(parents=True, exist_ok=True)
    disk = output / "storage-proof.raw"
    if disk.exists() or disk.is_symlink():
        raise ValueError("Refusing to overwrite an existing storage proof disk")
    config = fixture.verify_bytes(network.read_kernel_config(network.download_package(asset_cache)), network.CONFIG_SHA256)
    features = verify_storage_config(config)
    kernel = fixture.download_verified(fixture.KERNEL_URL, fixture.KERNEL_SHA256, asset_cache)
    archive = fixture.download_verified(ROOTFS_URL, ROOTFS_SHA256, asset_cache)
    root = fixture.read_rootfs(archive)
    initramfs = make_storage_initramfs(root)
    with tempfile.TemporaryDirectory(prefix="linex-storage-build-") as directory:
        staging = Path(directory) / "root"
        write_staging(make_storage_root(root), staging, require_root_ownership=True)
        with disk.open("xb") as stream:
            stream.truncate(DISK_BYTES)
        command = [mke2fs, "-q", "-t", "ext4", "-b", "4096", "-d", str(staging),
                   "-L", "LINEX_STORAGE", "-U", "4dc0e659-1d0c-4a74-a9aa-123cba672f91",
                   "-E", "lazy_itable_init=0,lazy_journal_init=0,root_owner=0:0",
                   "-O", "^orphan_file", str(disk), str(DISK_BYTES // 4096)]
        subprocess.run(command, check=True, timeout=60)
    (output / "kernel").write_bytes(kernel)
    (output / "storage-proof.cpio.gz").write_bytes(initramfs)
    manifest = {
        "schema": 1, "purpose": "raw ext4 switch-root/non-root nonce persistence; not desktop or Android proof",
        "architecture": "aarch64",
        "kernel": {"file": "kernel", "source": fixture.KERNEL_URL, "sha256": fixture.KERNEL_SHA256, "bytes": len(kernel)},
        "rootfs": {"source": ROOTFS_URL, "sha256": ROOTFS_SHA256, "bytes": len(archive)},
        "initramfs": {"file": "storage-proof.cpio.gz", "sha256": hashlib.sha256(initramfs).hexdigest(), "bytes": len(initramfs)},
        "disk": {"file": "storage-proof.raw", "format": "raw", "filesystem": "ext4", "sha256": hash_file(disk), "bytes": DISK_BYTES},
        "kernelConfig": {"packageSource": network.MODULES_URL, "packageSha256": network.MODULES_SHA256,
                         "path": network.CONFIG_PATH, "sha256": network.CONFIG_SHA256, "builtIn": features},
        "licenses": {"kernel": "GPL-2.0", "busybox": "GPL-2.0", "musl": "MIT"},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("dist/vm-storage-fixture"))
    parser.add_argument("--asset-cache", type=Path, default=Path("dist/vm-network-fixture/downloads"))
    parser.add_argument("--mke2fs", default="mke2fs")
    args = parser.parse_args()
    build(args.output, args.asset_cache, args.mke2fs)

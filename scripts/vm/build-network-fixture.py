#!/usr/bin/env python3
"""Separate pinned virtio/SLIRP proof assets; never extracts archive paths on the host."""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import stat
import sys
import tarfile
import urllib.request

spec = importlib.util.spec_from_file_location("linex_serial_fixture", Path(__file__).with_name("build-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = fixture
spec.loader.exec_module(fixture)

MODULES_URL = "https://ports.ubuntu.com/ubuntu-ports/pool/main/l/linux/linux-modules-6.8.0-142-generic_6.8.0-142.142_arm64.deb"
MODULES_SHA256 = "8998efe1301073bb3539063be12150772ef636f28de360972f827c0218751cff"
CONFIG_SHA256 = "217703dcf37786843b5f4f6f36e778dc04fbfe6cbf1f41dae7c09124e96901a4"
CONFIG_PATH = "boot/config-6.8.0-142-generic"
MAX_PACKAGE_BYTES = 128 * 1024 * 1024
MAX_CONFIG_BYTES = 512 * 1024
NETWORK_FEATURES = (
    "CONFIG_VIRTIO", "CONFIG_VIRTIO_NET", "CONFIG_VIRTIO_MMIO", "CONFIG_VIRTIO_PCI",
    "CONFIG_FAILOVER", "CONFIG_NET_FAILOVER", "CONFIG_INET", "CONFIG_PACKET",
)

DHCP = b'''#!/bin/sh
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[ "$interface" = eth0 ] || exit 1
case "$1" in
    deconfig) /bin/busybox ifconfig eth0 0.0.0.0 ;;
    bound|renew)
        [ "$ip" = 10.0.2.15 ] && [ "$subnet" = 255.255.255.0 ] || exit 1
        [ "$router" = 10.0.2.2 ] && [ "$dns" = 10.0.2.3 ] || exit 1
        /bin/busybox ifconfig eth0 "$ip" netmask "$subnet" || exit 1
        /bin/busybox ip route replace default via "$router" dev eth0 || exit 1
        printf 'nameserver %s\n' "$dns" > /etc/resolv.conf
        printf '%s\n' "$dns" > /run/dhcp-dns
        echo "LINEX_VM_DHCP ip=$ip gateway=$router dns=$dns"
        ;;
    *) exit 1 ;;
esac
'''

NETWORK_SETUP = b'''/bin/busybox ifconfig eth0 up || exit 1
/bin/busybox udhcpc -i eth0 -q -n -t 5 -T 2 -s /linex-dhcp || exit 1
address=$(/bin/busybox ip -4 addr show dev eth0 | /bin/busybox awk '/inet / { print $2; exit }')
route=$(/bin/busybox ip -4 route show default | /bin/busybox awk '$1 == "default" && $2 == "via" { print $3; exit }')
dns=$(/bin/busybox cat /run/dhcp-dns)
[ "$address" = 10.0.2.15/24 ] && [ "$route" = 10.0.2.2 ] && [ "$dns" = 10.0.2.3 ] || exit 1
echo "LINEX_VM_NETWORK_ADDRESS address=$address route=$route dns=$dns"
echo LINEX_VM_NETWORK_READY
'''

TCP_CASE = b'''            tcp\\ *)
                port=${command#tcp }
                case "$port" in ''|*[!0-9]*) echo LINEX_VM_TCP_BAD_PORT; continue ;; esac
                if [ "${#port}" -gt 5 ] || [ "$port" -lt 1024 ] || [ "$port" -gt 65535 ]; then
                    echo LINEX_VM_TCP_BAD_PORT
                    continue
                fi
                response=$(printf 'LINEX_VM_TCP_REQUEST\\n' | /bin/busybox timeout 6 \\
                    /bin/busybox nc -w 5 10.0.2.2 "$port" | /bin/busybox head -c 128)
                if [ "$response" = LINEX_VM_TCP_RESPONSE ]; then
                    echo LINEX_VM_TCP_OK
                else
                    echo LINEX_VM_TCP_FAILED
                fi
                ;;
'''

# Generate a distinct init while preserving the passing serial fixture exactly.
NETWORK_INIT = fixture.INIT.replace(b"echo LINEX_VM_BOOT_OK\n", b"echo LINEX_VM_BOOT_OK\n" + NETWORK_SETUP)
NETWORK_INIT = NETWORK_INIT.replace(b"            stop|poweroff)", TCP_CASE + b"            stop|poweroff)")


def read_ar(data):
    if len(data) > MAX_PACKAGE_BYTES or data[:8] != b"!<arch>\n":
        raise ValueError("Invalid or oversized Debian archive")
    members = {}
    position = 8
    while position < len(data):
        header = data[position:position + 60]
        if len(header) != 60 or header[58:] != b"`\n":
            raise ValueError("Invalid Debian member header")
        name = header[:16].decode("ascii").strip().removesuffix("/")
        size_text = header[48:58].decode("ascii").strip()
        if not re.fullmatch(r"[0-9]+", size_text) or name not in ("debian-binary", "control.tar", "data.tar"):
            raise ValueError("Unsupported Debian member")
        size = int(size_text)
        position += 60
        if name in members or position + size + size % 2 > len(data):
            raise ValueError("Duplicate or truncated Debian member")
        members[name] = data[position:position + size]
        position += size + size % 2
        if len(members) > 3:
            raise ValueError("Too many Debian members")
    if members.get("debian-binary") != b"2.0\n" or "data.tar" not in members:
        raise ValueError("Missing Debian package data")
    return members


def read_kernel_config(data):
    payload = read_ar(data)["data.tar"]
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r|") as archive:
            for index, member in enumerate(archive):
                if index >= 20000:
                    raise ValueError("Too many module-package entries")
                name = fixture.normalized_name(member.name)
                if name == CONFIG_PATH:
                    if not member.isfile() or member.size < 1 or member.size > MAX_CONFIG_BYTES:
                        raise ValueError("Invalid kernel config entry")
                    stream = archive.extractfile(member)
                    if stream is None:
                        raise ValueError("Missing kernel config payload")
                    config = stream.read(member.size + 1)
                    if len(config) != member.size:
                        raise ValueError("Truncated kernel config")
                    return config
    except tarfile.TarError as error:
        raise ValueError("Invalid module-package tar") from error
    raise ValueError("Matching kernel config missing")


def verify_network_config(config):
    values = dict(line.split("=", 1) for line in config.decode("ascii").splitlines()
                  if line.startswith("CONFIG_") and "=" in line)
    if any(values.get(feature) != "y" for feature in NETWORK_FEATURES):
        raise ValueError("Networking proof requires verified built-in kernel drivers")
    return {feature: values[feature] for feature in NETWORK_FEATURES}


def download_package(cache):
    path = cache / MODULES_SHA256
    if path.exists():
        if path.stat().st_size > MAX_PACKAGE_BYTES:
            raise ValueError("Cached module package exceeds limit")
        return fixture.verify_bytes(path.read_bytes(), MODULES_SHA256)
    request = urllib.request.Request(MODULES_URL, headers={"User-Agent": "Linex-VM-network-fixture/1"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(MAX_PACKAGE_BYTES + 1)
    if len(data) > MAX_PACKAGE_BYTES:
        raise ValueError("Module package exceeds limit")
    fixture.verify_bytes(data, MODULES_SHA256)
    cache.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".part")
    temporary.write_bytes(data)
    temporary.replace(path)
    return data


def make_network_initramfs(root):
    entries = dict(root)
    for directory in ("dev", "proc", "sys", "run", "tmp"):
        entries[directory] = fixture.Entry(stat.S_IFDIR | 0o755)
    entries["dev/console"] = fixture.Entry(stat.S_IFCHR | 0o600, rdevmajor=5, rdevminor=1)
    entries["dev/null"] = fixture.Entry(stat.S_IFCHR | 0o666, rdevmajor=1, rdevminor=3)
    entries["init"] = fixture.Entry(stat.S_IFREG | 0o755, NETWORK_INIT)
    entries["linex-dhcp"] = fixture.Entry(stat.S_IFREG | 0o755, DHCP)
    data = b"".join(fixture.newc_record(name, entries[name], index + 1)
                    for index, name in enumerate(sorted(entries)))
    data += fixture.newc_record("TRAILER!!!", fixture.Entry(0), len(entries) + 1)
    compressed = bytearray(gzip.compress(data, compresslevel=9, mtime=0))
    compressed[9] = 255
    return bytes(compressed)


def build(output, asset_cache):
    output.mkdir(parents=True, exist_ok=True)
    config = fixture.verify_bytes(read_kernel_config(download_package(output / "downloads")), CONFIG_SHA256)
    features = verify_network_config(config)
    kernel = fixture.download_verified(fixture.KERNEL_URL, fixture.KERNEL_SHA256, asset_cache)
    rootfs = fixture.download_verified(fixture.ROOTFS_URL, fixture.ROOTFS_SHA256, asset_cache)
    initramfs = make_network_initramfs(fixture.read_rootfs(rootfs))
    (output / "kernel").write_bytes(kernel)
    (output / "network-proof.cpio.gz").write_bytes(initramfs)
    manifest = {
        "schema": 1, "purpose": "Separate ARM64 virtio/SLIRP DHCP/default-route/TCP proof; not internet or HTTPS proof",
        "architecture": "aarch64", "guestChildCount": 64,
        "kernel": {"file": "kernel", "source": fixture.KERNEL_URL, "sha256": fixture.KERNEL_SHA256, "bytes": len(kernel)},
        "rootfs": {"source": fixture.ROOTFS_URL, "sha256": fixture.ROOTFS_SHA256, "bytes": len(rootfs)},
        "kernelConfig": {"packageSource": MODULES_URL, "packageSha256": MODULES_SHA256,
                         "sourcePackage": "https://ports.ubuntu.com/ubuntu-ports/pool/main/l/linux/linux_6.8.0-142.142.dsc",
                         "path": CONFIG_PATH, "sha256": CONFIG_SHA256, "builtIn": features},
        "initramfs": {"file": "network-proof.cpio.gz", "sha256": hashlib.sha256(initramfs).hexdigest(), "bytes": len(initramfs)},
        "serialNetworkMarker": "LINEX_VM_NETWORK_READY", "serialTcpCommand": "tcp <port>",
        "tcpProbeHost": "10.0.2.2", "tcpRequest": "LINEX_VM_TCP_REQUEST", "tcpResponse": "LINEX_VM_TCP_RESPONSE",
        "licenses": {"kernel": "GPL-2.0-only", "busybox": "GPL-2.0-only", "alpineMusl": "MIT",
                     "caCertificatesBundle": "MPL-2.0 AND MIT",
                     "rootfsNotices": "Component package metadata retained in lib/apk/db/installed"},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("dist/vm-network-fixture"))
    parser.add_argument("--asset-cache", type=Path, default=Path("dist/vm-fixture/downloads"))
    args = parser.parse_args()
    build(args.output, args.asset_cache)

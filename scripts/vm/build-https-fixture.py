#!/usr/bin/env python3
"""Pinned guest DNS/TLS proof assets. No host extraction or APK scripts execute."""
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
import zlib

spec = importlib.util.spec_from_file_location("linex_network_fixture", Path(__file__).with_name("build-network-fixture.py"))
network = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = network
spec.loader.exec_module(network)
fixture = network.fixture
MAX_APK_BYTES = 16 * 1024 * 1024
MAX_MEMBER_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 96 * 1024 * 1024
PACKAGES_FILE = Path(__file__).with_name("https-fixture-packages.json")
TLS_DIRECTORY = Path(__file__).with_name("https-test-tls")

PROBE = b'''#!/bin/sh
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
port_ok() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    [ "${#1}" -le 5 ] && [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
}
tls_request() {
    host=$1; port=$2; name=$3; ca=$4; output=$5
    printf 'GET / HTTP/1.1\\r\\nHost: %s\\r\\nConnection: close\\r\\n\\r\\n' "$name" > /run/tls-request
    (ulimit -f 128; /bin/busybox timeout 20 /usr/bin/openssl s_client -quiet -ign_eof \
        -connect "$host:$port" -servername "$name" -verify_hostname "$name" \
        -verify_return_error -CAfile "$ca" -no-CApath -no-CAstore \
        < /run/tls-request > "$output" 2> "$output.err")
}
case "$1" in
    dns)
        pids=""; index=0
        for transport in udp tcp; do
            for type in A AAAA; do
                index=$((index + 1))
                if [ "$transport" = tcp ]; then option=+tcp; else option=+notcp; fi
                /bin/busybox timeout 15 /usr/bin/dig @10.0.2.3 example.com "$type" \
                    +qid=4660 "$option" +time=10 +tries=1 +ignore +noedns \
                    > "/run/dns-$index" 2>&1 &
                pids="$pids $!"
            done
        done
        failed=0
        for pid in $pids; do wait "$pid" || failed=1; done
        index=0
        for transport in udp tcp; do
            for type in A AAAA; do
                index=$((index + 1)); output="/run/dns-$index"
                /bin/busybox grep -q 'status: NOERROR, id: 4660' "$output" || failed=1
                /bin/busybox grep -Eq 'ANSWER: [1-9][0-9]*' "$output" || failed=1
                /bin/busybox grep -Eq "^;example[.]com[.]?[[:space:]]+IN[[:space:]]+$type$" "$output" || failed=1
                echo "LINEX_VM_DNS_RESULT transport=$transport type=$type id=4660"
            done
        done
        [ "$failed" -eq 0 ] && echo LINEX_VM_DNS_OK || { echo LINEX_VM_DNS_FAILED; exit 1; }
        ;;
    https)
        if tls_request example.com 443 example.com /etc/ssl/certs/ca-certificates.crt /run/public-tls && \
            /bin/busybox grep -Eq '^HTTP/1[.]1 200[[:space:]]' /run/public-tls; then
            echo LINEX_VM_HTTPS_VERIFIED
        else echo LINEX_VM_HTTPS_FAILED; exit 1; fi
        ;;
    tls)
        port_ok "$2" || exit 2
        if ! tls_request 10.0.2.2 "$2" linex-fixture.test /linex-test-ca.pem /run/valid-tls || \
            ! /bin/busybox grep -q '^LINEX_VM_TLS_RESPONSE' /run/valid-tls; then
            echo LINEX_VM_TLS_VALID_FAILED; exit 1
        fi
        echo LINEX_VM_TLS_VALID
        if tls_request 10.0.2.2 "$2" wrong-host.invalid /linex-test-ca.pem /run/wrong-host; then
            echo LINEX_VM_TLS_WRONG_HOST_ACCEPTED; exit 1
        fi
        /bin/busybox grep -qi 'hostname mismatch' /run/wrong-host.err || exit 1
        echo LINEX_VM_TLS_WRONG_HOST_REJECTED
        if tls_request 10.0.2.2 "$2" linex-fixture.test /etc/ssl/certs/ca-certificates.crt /run/untrusted; then
            echo LINEX_VM_TLS_UNTRUSTED_ACCEPTED; exit 1
        fi
        /bin/busybox grep -Eqi 'unable to get local issuer|unable to verify|self.signed certificate' /run/untrusted.err || exit 1
        echo LINEX_VM_TLS_UNTRUSTED_REJECTED
        ;;
    tls-timeout)
        port_ok "$2" || exit 2
        /bin/busybox timeout 3 /usr/bin/openssl s_client -connect "10.0.2.2:$2" \
            -verify_return_error -verify_hostname linex-fixture.test -CAfile /linex-test-ca.pem \
            -no-CApath -no-CAstore </dev/null >/run/tls-timeout 2>&1
        result=$?
        [ "$result" -eq 143 ] || [ "$result" -eq 124 ] || { echo LINEX_VM_TLS_TIMEOUT_FAILED; exit 1; }
        echo LINEX_VM_TLS_TIMEOUT_OK
        ;;
    *) exit 2 ;;
esac
'''

CASES = b'''            clock\\ *)
                epoch=${command#clock }
                case "$epoch" in ''|*[!0-9]*) echo LINEX_VM_CLOCK_BAD; continue ;; esac
                if [ "${#epoch}" -ne 10 ] || [ "$epoch" -lt 1700000000 ] || [ "$epoch" -gt 2300000000 ]; then
                    echo LINEX_VM_CLOCK_BAD; continue
                fi
                /bin/busybox date -u -s "@$epoch" >/dev/null && echo LINEX_VM_CLOCK_SET
                ;;
            dns|https)
                /linex-network-probe "$command"
                ;;
            tls\\ *|tls-timeout\\ *)
                kind=${command%% *}; port=${command#* }
                /linex-network-probe "$kind" "$port"
                ;;
'''


def read_apk(data):
    """Read the three signed APK v2 gzip members with hard expansion limits."""
    if not data or len(data) > MAX_APK_BYTES:
        raise ValueError("Invalid or oversized APK")
    streams = []
    while data:
        if len(streams) >= 3:
            raise ValueError("Too many APK gzip members")
        decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
        try:
            payload = decoder.decompress(data, MAX_MEMBER_BYTES + 1)
        except zlib.error as error:
            raise ValueError("Invalid APK gzip member") from error
        if len(payload) > MAX_MEMBER_BYTES or decoder.unconsumed_tail or not decoder.eof:
            raise ValueError("Oversized or truncated APK gzip member")
        data = decoder.unused_data
        streams.append(payload)
    if len(streams) != 3:
        raise ValueError("Expected signed APK v2 signature/control/data members")
    metadata = None
    with tarfile.open(fileobj=io.BytesIO(streams[1]), mode="r|") as archive:
        for index, member in enumerate(archive):
            if index >= 64:
                raise ValueError("Too many APK control entries")
            if member.name == ".PKGINFO":
                if not member.isfile() or member.size > 32768 or metadata is not None:
                    raise ValueError("Invalid APK metadata")
                metadata = archive.extractfile(member).read()
    if metadata is None:
        raise ValueError("APK metadata missing")
    # Rootfs reader already bounds names/types/hardlinks/ancestor containment.
    entries = fixture.read_rootfs(gzip.compress(streams[2], mtime=0))
    entries.pop(".PKGINFO", None)
    return entries, metadata


def validate_pins(pins):
    packages = pins["packages"]
    names = {package["name"] for package in packages}
    if len(names) != len(packages) or not {"bind-tools", "openssl"}.issubset(names):
        raise ValueError("Duplicate or missing probe packages")
    providers = {package["name"]: package["version"] for package in packages}
    for package in packages:
        if not re.fullmatch(r"[a-z0-9][a-z0-9+_.-]*", package["name"]) or not re.fullmatch(r"[A-Za-z0-9._+~-]+", package["version"]):
            raise ValueError("Invalid package name or version")
        if not re.fullmatch(r"[0-9a-f]{64}", package["sha256"]) or not 0 < package["bytes"] <= MAX_APK_BYTES:
            raise ValueError("Invalid package pin")
        expected = f"https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/{package['name']}-{package['version']}.apk"
        if package["url"] != expected or not package["license"] or package["architecture"] not in ("aarch64", "noarch"):
            raise ValueError("Invalid package origin or license")
        for capability in package["provides"]:
            name, _, version = capability.partition("=")
            providers[name] = version
    for package in packages:
        for dependency in package["dependencies"]:
            name, _, version = dependency.partition("=")
            if name not in providers or (version and version != providers[name]):
                raise ValueError("Incomplete pinned dependency closure")
    return packages


def make_https_initramfs(root, ca):
    entries = dict(root)
    for directory in ("dev", "proc", "sys", "run", "tmp"):
        entries[directory] = fixture.Entry(stat.S_IFDIR | 0o755)
    entries["dev/console"] = fixture.Entry(stat.S_IFCHR | 0o600, rdevmajor=5, rdevminor=1)
    entries["dev/null"] = fixture.Entry(stat.S_IFCHR | 0o666, rdevmajor=1, rdevminor=3)
    init = network.NETWORK_INIT.replace(b"            stop|poweroff)", CASES + b"            stop|poweroff)")
    entries["init"] = fixture.Entry(stat.S_IFREG | 0o755, init)
    entries["linex-dhcp"] = fixture.Entry(stat.S_IFREG | 0o755, network.DHCP)
    entries["linex-network-probe"] = fixture.Entry(stat.S_IFREG | 0o755, PROBE)
    # Test CA is used explicitly only for fixture connections; never global trust.
    entries["linex-test-ca.pem"] = fixture.Entry(stat.S_IFREG | 0o644, ca)
    if sum(len(entry.data) for entry in entries.values()) > MAX_TOTAL_BYTES:
        raise ValueError("Layered fixture exceeds limit")
    data = b"".join(fixture.newc_record(name, entries[name], index + 1)
                    for index, name in enumerate(sorted(entries)))
    data += fixture.newc_record("TRAILER!!!", fixture.Entry(0), len(entries) + 1)
    compressed = bytearray(gzip.compress(data, compresslevel=9, mtime=0))
    compressed[9] = 255
    return bytes(compressed)


def build(output, cache):
    pins = json.loads(PACKAGES_FILE.read_text(encoding="utf-8"))
    packages = validate_pins(pins)
    output.mkdir(parents=True, exist_ok=True)
    config = fixture.verify_bytes(network.read_kernel_config(network.download_package(cache)), network.CONFIG_SHA256)
    network.verify_network_config(config)
    kernel = fixture.download_verified(fixture.KERNEL_URL, fixture.KERNEL_SHA256, cache)
    rootfs = fixture.download_verified(fixture.ROOTFS_URL, fixture.ROOTFS_SHA256, cache)
    root = fixture.read_rootfs(rootfs)
    ca_before = {name: root[name] for name in ("etc/ssl/cert.pem", "etc/ssl/certs/ca-certificates.crt")}
    package_metadata = []
    for package in packages:
        data = fixture.download_verified(package["url"], package["sha256"], cache)
        if len(data) != package["bytes"]:
            raise ValueError("Pinned package size mismatch")
        entries, metadata = read_apk(data)
        info = dict(line.split(" = ", 1) for line in metadata.decode().splitlines() if " = " in line)
        if info.get("pkgname") != package["name"] or info.get("pkgver") != package["version"] or info.get("arch") != package["architecture"]:
            raise ValueError("Pinned package metadata mismatch")
        root.update(entries)
        root[f"linex-package-notices/{package['name']}.PKGINFO"] = fixture.Entry(stat.S_IFREG | 0o644, metadata)
        package_metadata.append({**package, "aportsCommit": info.get("commit", ""), "upstream": info.get("url", "")})
    root["linex-package-notices"] = fixture.Entry(stat.S_IFDIR | 0o755)
    if any(root.get(name) != entry for name, entry in ca_before.items()):
        raise ValueError("Probe packages changed original system CA")
    tls = {name: (TLS_DIRECTORY / name).read_bytes() for name in ("ca.pem", "server.pem", "server-key.pk8")}
    initramfs = make_https_initramfs(root, tls["ca.pem"])
    (output / "kernel").write_bytes(kernel)
    (output / "https-proof.cpio.gz").write_bytes(initramfs)
    for name, data in tls.items():
        (output / name).write_bytes(data)
    manifest = {
        "schema": 1, "purpose": "Guest UDP/TCP DNS and certificate/hostname verified TLS; test assets only",
        "kernel": {"file": "kernel", "sha256": fixture.KERNEL_SHA256, "bytes": len(kernel)},
        "rootfs": {"source": fixture.ROOTFS_URL, "sha256": fixture.ROOTFS_SHA256},
        "initramfs": {"file": "https-proof.cpio.gz", "sha256": hashlib.sha256(initramfs).hexdigest(), "bytes": len(initramfs)},
        "packages": package_metadata, "packagePinsSha256": hashlib.sha256(PACKAGES_FILE.read_bytes()).hexdigest(),
        "systemCaSha256": hashlib.sha256(ca_before["etc/ssl/certs/ca-certificates.crt"].data).hexdigest(),
        "testTlsAssets": {name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)} for name, data in tls.items()},
        "clockCommandRequired": "clock <current epoch seconds>; certificate time verification stays enabled",
        "commands": ["dns", "https", "tls <controlled server port>", "tls-timeout <blackhole port>"],
        "testCaInstalledGlobally": False, "apkTrust": "Exact SHA256 pins over HTTPS; APK signatures retained, not independently verified by builder",
        "sources": ["https://bind9.readthedocs.io/en/v9.20.23/manpages.html", "https://docs.openssl.org/3.5/man1/openssl-s_client/"],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"initramfs": manifest["initramfs"], "packageCount": len(packages)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("dist/vm-https-fixture"))
    parser.add_argument("--asset-cache", type=Path, default=Path("dist/vm-fixture/downloads"))
    args = parser.parse_args()
    build(args.output, args.asset_cache)

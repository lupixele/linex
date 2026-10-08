#!/usr/bin/env python3
"""Archive exact Debian corresponding sources from a pinned authenticated build lock.

This verifies lock/file pins; authentication of the lock's APT indexes belongs to
build-desktop-image.py. It makes no independent signature or runtime claim.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import ssl
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

NAME = re.compile(r"[a-z0-9][a-z0-9+.-]{0,99}")
VERSION = re.compile(r"[A-Za-z0-9.+:~_-]{1,160}")
SHA256 = re.compile(r"[a-f0-9]{64}")
FILENAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9.+_~-]{0,219}\.(?:dsc|tar\.(?:xz|gz|bz2|zst)|diff\.gz)(?:\.asc)?")
MAX_LOCK = 16 * 1024 * 1024
MAX_ARCHIVE = 2 * 1024 * 1024 * 1024
MAX_TOTAL = 16 * 1024 * 1024 * 1024
HOSTS = {"deb.debian.org", "security.debian.org"}


def hash_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_url(address):
    url = urlsplit(address)
    if url.scheme != "https" or url.hostname not in HOSTS or url.port is not None or \
            url.username or url.password or url.query or url.fragment or "%" in url.path or \
            any(component in (".", "..", "") for component in url.path.split("/")[1:]):
        raise ValueError("Source URL must remain on an official HTTPS archive without path escapes")
    return address


class OfficialRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, address):
        checked_url(address)
        return super().redirect_request(request, response, code, message, headers, address)


def source_plan(lock):
    if lock.get("schema") != 1 or lock.get("imageId") != "debian-trixie-desktop" or \
            lock.get("architecture") != "aarch64" or lock.get("stage") != "authenticated-provisioning":
        raise ValueError("Expected an authenticated Debian ARM64 provisioning lock")
    packages, sources = lock.get("packages"), lock.get("sources")
    if not isinstance(packages, list) or not 1 <= len(packages) <= 800 or \
            not isinstance(sources, list) or not 1 <= len(sources) <= 800:
        raise ValueError("Source/binary closure is outside bounds")
    required = set()
    for package in packages:
        name, version = package.get("source", ""), package.get("sourceVersion", "")
        if not NAME.fullmatch(name) or not VERSION.fullmatch(version):
            raise ValueError("Invalid installed source identity")
        required.add((name, version))
    result, seen, total = [], set(), 0
    for source in sources:
        name, version = source.get("Package", ""), source.get("Version", "")
        key = (name, version)
        if key not in required or key in seen:
            raise ValueError("Source metadata must exactly cover the installed binary closure")
        seen.add(key)
        directory = source.get("Directory", "")
        parts = directory.split("/")
        if not (directory.startswith("pool/main/") or directory.startswith("pool/updates/main/")) or \
                any(not re.fullmatch(r"[a-z0-9][a-z0-9+.-]{0,99}", part) or part in (".", "..") for part in parts):
            raise ValueError("Invalid source repository directory")
        base = "https://security.debian.org/debian-security/" if directory.startswith("pool/updates/") else "https://deb.debian.org/debian/"
        checksums = source.get("Checksums-Sha256", "").strip().splitlines()
        if not 2 <= len(checksums) <= 256:
            raise ValueError("Source metadata requires complete SHA256 source archives")
        folder = name + "-" + hashlib.sha256((name + "=" + version).encode()).hexdigest()[:16]
        files, filenames = [], set()
        for row in checksums:
            values = row.split()
            if len(values) != 3 or not SHA256.fullmatch(values[0]) or not values[1].isdecimal() or \
                    not FILENAME.fullmatch(values[2]) or values[2] in filenames:
                raise ValueError("Invalid or duplicate source archive metadata")
            size = int(values[1])
            if not 1 <= size <= MAX_ARCHIVE:
                raise ValueError("Source archive size outside bounds")
            filenames.add(values[2])
            total += size
            if total > MAX_TOTAL:
                raise ValueError("Total source download exceeds bounds")
            files.append({"file": folder + "/" + values[2], "bytes": size, "sha256": values[0],
                          "url": checked_url(base + directory + "/" + values[2])})
        if sum(item["file"].endswith(".dsc") for item in files) != 1:
            raise ValueError("Each exact source package requires one source control file")
        result.append({"name": name, "version": version, "files": files})
    if seen != required:
        raise ValueError("Installed packages are missing corresponding source records")
    return sorted(result, key=lambda item: (item["name"], item["version"]))


def pinned_lock_bytes(path, expected):
    if not SHA256.fullmatch(expected) or path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_LOCK:
        raise ValueError("Invalid bounded provisioning lock")
    with path.open("rb") as source:
        payload = source.read(MAX_LOCK + 1)
    if len(payload) > MAX_LOCK:
        raise ValueError("Provisioning lock exceeds bounded length")
    if hashlib.sha256(payload).hexdigest() != expected:
        raise ValueError("Provisioning lock SHA256 differs from the reviewed candidate")
    return payload


def load_pinned_lock(path, expected):
    return json.loads(pinned_lock_bytes(path, expected))


def download_file(item, output, opener):
    target = output / item["file"]
    target.parent.mkdir(mode=0o700, exist_ok=True)
    partial = target.with_name(target.name + ".partial")
    if target.exists() or target.is_symlink() or partial.exists() or partial.is_symlink():
        raise ValueError("Source destination already exists; preserving it")
    created = False
    try:
        digest, completed = hashlib.sha256(), 0
        deadline = time.monotonic() + 600
        with opener.open(checked_url(item["url"]), timeout=30) as response, partial.open("xb") as stream:
            created = True
            checked_url(response.geturl())
            if response.status != 200:
                raise ValueError("Source archive response must be HTTP200")
            declared = response.headers.get("Content-Length")
            if declared is not None and (not declared.isdecimal() or int(declared) != item["bytes"]):
                raise ValueError("Source HTTP length differs from authenticated metadata")
            while True:
                if time.monotonic() > deadline:
                    raise TimeoutError("Source archive deadline exceeded")
                chunk = response.read(min(1024 * 1024, item["bytes"] - completed + 1))
                if not chunk:
                    break
                completed += len(chunk)
                if completed > item["bytes"]:
                    raise ValueError("Source archive exceeds pinned length")
                digest.update(chunk)
                stream.write(chunk)
            if completed != item["bytes"] or digest.hexdigest() != item["sha256"]:
                raise ValueError("Corresponding source length/SHA256 mismatch")
        partial.rename(target)
    finally:
        if created and partial.exists():
            partial.unlink()


def transient(error):
    if isinstance(error, urllib.error.HTTPError):
        return error.code in (429, 502, 503, 504)
    if isinstance(error, urllib.error.URLError):
        return not isinstance(error.reason, ssl.SSLError)
    return isinstance(error, (TimeoutError, ConnectionError))


def download_with_retry(item, output, opener):
    for attempt in range(3):
        try:
            return download_file(item, output, opener)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            retryable = transient(error)
            if isinstance(error, urllib.error.HTTPError):
                error.close()
            if attempt == 2 or not retryable:
                raise
            # Retry the same authenticated metadata; never replace a completed archive.
            time.sleep(2)


def fresh_output(path):
    candidate = path.absolute()
    if any(parent.is_symlink() for parent in (candidate, *candidate.parents)):
        raise ValueError("Source output must not contain symlinks")
    candidate = candidate.resolve()
    base = (Path.cwd() / "dist").resolve()
    if not candidate.is_relative_to(base) or candidate == base:
        raise ValueError("Source output must be a fresh project dist subdirectory")
    candidate.mkdir(mode=0o700, parents=True, exist_ok=False)
    return candidate


def archive(lock_path, expected_sha256, output):
    lock_payload = pinned_lock_bytes(lock_path, expected_sha256)
    lock = json.loads(lock_payload)
    plan = source_plan(lock)
    destination = fresh_output(output)
    opener = urllib.request.build_opener(OfficialRedirects())
    started = time.monotonic()
    for index, source in enumerate(plan, 1):
        print(f"Corresponding source {index}/{len(plan)}: {source['name']}={source['version']}", flush=True)
        for item in source["files"]:
            if time.monotonic() - started > 3600:
                raise TimeoutError("Corresponding-source archive deadline exceeded")
            download_with_retry(item, destination, opener)
    (destination / "desktop-image-lock.json").write_bytes(lock_payload)
    manifest = {"schema": 1, "lockSha256": expected_sha256, "sourceCount": len(plan), "sources": plan,
                "completeDebianSourceArchives": True, "kernelSourceIncluded": False,
                "nativeEngineSourceIncluded": False, "releaseReady": False,
                "licenseNotices": "Factory /usr/share/doc/*/copyright and /usr/share/common-licenses; preserve these files."}
    (destination / "sources-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--lock-sha256", required=True)
    parser.add_argument("--output", type=Path, default=Path("dist/vm-desktop-sources"))
    args = parser.parse_args()
    archive(args.lock, args.lock_sha256, args.output)

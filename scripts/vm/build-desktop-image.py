#!/usr/bin/env python3
"""Native ARM Linux, authenticated Debian desktop provisioning; no phone root."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import subprocess
import urllib.request
from urllib.parse import unquote, urlsplit

KEYS = (
    ("archive-key-13", "04B54C3CDCA79751B16BC6B5225629DF75B188BD",
     "6f1d277429dd7ffedcc6f8688a7ad9a458859b1139ffa026d1eeaadcbffb0da7"),
    ("archive-key-13-security", "5E04A1E3223A19A20706E20F9904613D4CCE68C6",
     "844c07d242db37f283afab9d5531270a0550841e90f9f1a9c3bd599722b808b7"),
    ("release-13", "41587F7DB8C774BCCF131416762F67A0B2C39DE4",
     "4d097bb93f83d731f475c5b92a0c2fcf108cfce1d4932792fca72d00b48d198b"),
)
PACKAGES = (
    "busybox-static", "ca-certificates", "curl", "dbus", "dbus-x11", "e2fsprogs",
    "firefox-esr", "fonts-dejavu-core", "iproute2", "libavcodec61", "procps", "python3", "util-linux",
    "tigervnc-standalone-server", "tigervnc-tools", "xfce4", "xfce4-terminal", "x11-utils",
)
BROWSER_MINIMUM = "153.4.0esr"
MAX_LOG_BYTES = 24 * 1024 * 1024
NAME = re.compile(r"[a-z0-9][a-z0-9+.-]*")
VERSION = re.compile(r"[a-zA-Z0-9.+:~_-]+")


def sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def records(text):
    result = []
    current = {}
    field = None
    for line in text.splitlines() + [""]:
        if not line:
            if current:
                result.append(current)
            current, field = {}, None
        elif line[0].isspace():
            if field is None:
                raise ValueError("Orphan Debian metadata continuation")
            current[field] += "\n" + line[1:]
        else:
            field, separator, value = line.partition(":")
            if not separator or field in current:
                raise ValueError("Invalid or duplicate Debian metadata field")
            current[field] = value.lstrip()
    return result


def checked_package(name, version):
    if not NAME.fullmatch(name) or not VERSION.fullmatch(version):
        raise ValueError("Invalid solved package identity")
    return name + "=" + version


def parse_download_plan(text):
    result = {}
    for line in text.splitlines():
        if not line.startswith("'"):
            continue
        parts = shlex.split(line)
        if len(parts) not in (3, 4):
            raise ValueError("Invalid apt URI field count")
        url = urlsplit(parts[0])
        name = unquote(parts[1])
        if url.scheme != "https" or url.hostname not in ("deb.debian.org", "security.debian.org") or \
                url.username or url.password or url.port is not None or url.query or url.fragment:
            raise ValueError("Invalid apt package URL")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_:%~-]*\.deb", name) or \
                not parts[2].isdecimal() or not 1 <= int(parts[2]) <= 256 * 1024 * 1024:
            raise ValueError("Invalid apt cache filename or package size")
        if len(parts) == 4 and not re.fullmatch(r"(?:MD5Sum:[a-fA-F0-9]{32}|SHA256:[a-fA-F0-9]{64})", parts[3]):
            raise ValueError("Invalid optional apt legacy checksum field")
        if name in result:
            raise ValueError("Duplicate apt cache filename")
        result[name] = {"url": parts[0], "bytes": int(parts[2])}
    if not result:
        raise ValueError("Empty exact apt package download plan")
    return result


def require_native_builder():
    if platform.system() != "Linux" or platform.machine() not in ("aarch64", "arm64"):
        raise RuntimeError("Desktop provisioning requires a native ARM64 Linux CI runner")
    if os.geteuid() != 0:
        raise RuntimeError("Run disposable Linux CI builder with sudo; Android root is never needed")


def fresh_output(path):
    workspace_dist = (Path.cwd() / "dist").resolve()
    absolute = path.absolute()
    for parent in [absolute] + list(absolute.parents):
        if parent.is_symlink():
            raise ValueError("Desktop output cannot contain a symlink")
        if parent == Path.cwd():
            break
    absolute = absolute.resolve()
    if not absolute.is_relative_to(workspace_dist) or absolute == workspace_dist:
        raise ValueError("Desktop output must be a new directory beneath this project's dist")
    absolute.mkdir(parents=True, exist_ok=False, mode=0o700)
    return absolute


class Builder:
    def __init__(self, output):
        self.output = output
        self.root = output / "staging" / "root"
        self.root.parent.mkdir(mode=0o700)
        self.evidence = output / "evidence"
        self.evidence.mkdir()
        self.counter = 0

    def run(self, command, timeout=1200, guest=False):
        if guest:
            command = ["chroot", str(self.root)] + command
        self.counter += 1
        logfile = self.evidence / f"command-{self.counter:04d}.log"
        print(f"Desktop build step {self.counter}: {command[0]}", flush=True)
        with logfile.open("xb") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                    env={**os.environ, "LC_ALL": "C", "DEBIAN_FRONTEND": "noninteractive"},
                                    timeout=timeout)
        if logfile.stat().st_size > MAX_LOG_BYTES:
            raise ValueError("Desktop command log exceeds bounded evidence size")
        text = logfile.read_text(encoding="utf-8", errors="replace")
        if result.returncode != 0:
            raise RuntimeError(f"Desktop build command failed ({result.returncode}); see {logfile}")
        return text

    def trusted_keyring(self):
        key_directory = self.output / "keys"
        key_directory.mkdir()
        gpg_home = key_directory / "gnupg"
        gpg_home.mkdir(mode=0o700)
        imported = []
        for name, fingerprint, digest in KEYS:
            source = "https://ftp-master.debian.org/keys/" + name + ".asc"
            with urllib.request.urlopen(source, timeout=60) as response:
                payload = response.read(65537)
            if len(payload) > 65536 or hashlib.sha256(payload).hexdigest() != digest:
                raise ValueError("Pinned Debian archive key digest mismatch")
            key = key_directory / (name + ".asc")
            key.write_bytes(payload)
            listing = self.run(["gpg", "--homedir", str(gpg_home), "--batch", "--no-options", "--with-colons", "--show-keys", str(key)])
            primary = []
            next_primary = False
            for line in listing.splitlines():
                parts = line.split(":")
                if parts[0] == "pub":
                    next_primary = True
                elif parts[0] == "fpr" and next_primary:
                    primary.append(parts[9])
                    next_primary = False
            if primary != [fingerprint]:
                raise ValueError("Pinned Debian key fingerprint mismatch")
            imported.append({"source": source, "sha256": digest, "primaryFingerprint": fingerprint})
        combined = key_directory / "trusted.asc"
        combined.write_bytes(b"\n".join((key_directory / (name + ".asc")).read_bytes() for name, _, _ in KEYS))
        keyring = key_directory / "trusted.gpg"
        self.run(["gpg", "--homedir", str(gpg_home), "--batch", "--no-options", "--dearmor", "--output", str(keyring), str(combined)])
        return keyring, imported

    def provision(self):
        keyring, keys = self.trusted_keyring()
        # force-check-gpg is the older compatible spelling; never allow HTTPS-only fallback.
        self.run(["debootstrap", "--arch=arm64", "--variant=minbase", "--force-check-gpg",
                  "--keyring=" + str(keyring), "--include=ca-certificates", "--keep-debootstrap-dir",
                  "trixie", str(self.root), "https://deb.debian.org/debian"], timeout=1800)
        self.root.chmod(0o755)
        (self.root / "usr/sbin/policy-rc.d").write_text("#!/bin/sh\nexit 101\n", encoding="ascii")
        (self.root / "usr/sbin/policy-rc.d").chmod(0o755)
        (self.root / "etc/apt/sources.list").write_text(
            "deb https://deb.debian.org/debian trixie main\n"
            "deb-src https://deb.debian.org/debian trixie main\n"
            "deb https://deb.debian.org/debian trixie-updates main\n"
            "deb-src https://deb.debian.org/debian trixie-updates main\n"
            "deb https://security.debian.org/debian-security trixie-security main\n"
            "deb-src https://security.debian.org/debian-security trixie-security main\n", encoding="ascii")
        # Fresh authenticated Debian keyring now supplied by minbase, not an Ubuntu stale keyring.
        (self.root / "etc/apt/apt.conf.d/90linex-build").write_text(
            'Acquire::AllowInsecureRepositories "false";\n'
            'APT::Get::AllowUnauthenticated "false";\n'
            'Acquire::Retries "3";\nAcquire::https::Timeout "45";\n'
            'APT::Keep-Downloaded-Packages "true";\n', encoding="ascii")
        self.run(["apt-get", "update", "-o", "Debug::Acquire::gpgv=true"], guest=True)
        self.run(["apt-get", "--yes", "upgrade"], guest=True)
        self.run(["apt-get", "install", "--yes", "--no-install-recommends"] + list(PACKAGES), guest=True)
        browser = self.run(["dpkg-query", "-W", "-f=${Version}", "firefox-esr"], guest=True).strip()
        self.run(["dpkg", "--compare-versions", browser, "ge", BROWSER_MINIMUM], guest=True)
        listing = self.run(["dpkg-query", "-W", "-f=${Package}\t${Version}\t${Architecture}\t${source:Package}\t${source:Version}\n"], guest=True)
        installed = []
        for line in listing.splitlines():
            values = line.split("\t")
            if len(values) != 5 or values[2] not in ("arm64", "all"):
                raise ValueError("Invalid installed native package closure")
            checked_package(values[0], values[1])
            checked_package(values[3], values[4])
            installed.append(dict(zip(("name", "version", "architecture", "source", "sourceVersion"), values)))
        if len(installed) > 800 or not installed:
            raise ValueError("Installed desktop package count outside builder bounds")
        # Re-download every exact solved package so the complete base+desktop closure is archived.
        requests = [checked_package(item["name"], item["version"]) for item in installed]
        cache = self.root / "var/cache/apt/archives"
        # apt only prints missing files; move verified installation cache aside to obtain all URIs.
        saved = self.output / "installation-cache"
        saved.mkdir()
        for package in cache.glob("*.deb"):
            package.rename(saved / package.name)
        plan = self.run(["apt-get", "--print-uris", "--yes", "--download-only", "--reinstall", "install"] + requests, guest=True)
        uris = parse_download_plan(plan)
        self.run(["apt-get", "--yes", "--download-only", "--reinstall", "install"] + requests, guest=True)
        cached = {}
        for candidate in cache.glob("*.deb"):
            identity = (candidate.stat().st_size, sha256(candidate))
            if identity in cached:
                raise ValueError("Duplicate exact package cache")
            cached[identity] = candidate
        closure = []
        sources = {}
        for item in installed:
            record = [entry for entry in records(self.run(["apt-cache", "show", checked_package(item["name"], item["version"])], guest=True))
                      if entry.get("Package") == item["name"] and entry.get("Version") == item["version"]]
            if not record or any(entry.get("SHA256") != record[0].get("SHA256") for entry in record):
                raise ValueError("Exact binary metadata missing or inconsistent")
            metadata = record[0]
            matched = cached.get((int(metadata["Size"]), metadata["SHA256"]))
            planned = uris.get(unquote(matched.name)) if matched is not None else None
            if matched is None or planned is None or planned["bytes"] != int(metadata["Size"]):
                raise ValueError("Downloaded exact package hash/URI missing")
            closure.append({**item, "url": planned["url"], "file": matched.name,
                            "sha256": metadata["SHA256"], "bytes": int(metadata["Size"])})
            source_key = (item["source"], item["sourceVersion"])
            if source_key not in sources:
                source_records = [entry for entry in records(self.run(["apt-cache", "showsrc", item["source"]], guest=True))
                                  if entry.get("Package") == item["source"] and entry.get("Version") == item["sourceVersion"]]
                if not source_records:
                    raise ValueError("Corresponding exact source metadata missing")
                sources[source_key] = source_records[0]
        indexes = []
        for index in sorted((self.root / "var/lib/apt/lists").glob("*InRelease")):
            indexes.append({"file": index.name, "sha256": sha256(index), "bytes": index.stat().st_size})
        if len(indexes) != 3:
            raise ValueError("Expected three authenticated Debian repositories")
        lock = {"schema": 1, "imageId": "debian-trixie-desktop", "architecture": "aarch64",
                "stage": "authenticated-provisioning", "imageReady": False, "runtimeProofPassed": False,
                "distribution": "Debian13 trixie", "browserMinimum": BROWSER_MINIMUM,
                "browserVersion": browser, "archiveKeys": keys, "inRelease": indexes,
                "packageCount": len(closure), "packages": closure,
                "sources": list(sources.values()),
                "securityAudit": {"mozillaAdvisory": "https://www.mozilla.org/en-US/security/advisories/mfsa2026-100/",
                                  "baselineDate": "2026-09-29", "releaseTimeAuditStillRequired": True},
                "licenseNoticesPath": "/usr/share/doc/*/copyright"}
        (self.output / "desktop-image-lock.json").write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        (self.output / "provisioning-proof.json").write_text(json.dumps({
            "passed": True, "proof": "Native ARM64 Debian GPG/APT authenticated package provision; no boot/browser/runtime claim",
            "packageCount": len(closure), "sourceCount": len(sources), "browserVersion": browser,
            "lockSha256": sha256(self.output / "desktop-image-lock.json")}, indent=2) + "\n", encoding="utf-8")
        print(f"Authenticated package closure: {len(closure)} packages, Firefox {browser}; guest boot proof remains pending", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("dist/vm-desktop-packages"))
    args = parser.parse_args()
    require_native_builder()
    Builder(fresh_output(args.output)).provision()

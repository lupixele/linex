#!/usr/bin/env python3
"""Real Linux-host ext4 switch-root/persistence/SIGKILL recovery proof; not Android."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import re
import secrets
import shutil
import subprocess
import tempfile
import time

spec = importlib.util.spec_from_file_location("linex_storage_serial_harness", Path(__file__).with_name("test-boot.py"))
serial = importlib.util.module_from_spec(spec)
spec.loader.exec_module(serial)


def hash_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_fixture(root):
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema") != 1 or manifest.get("architecture") != "aarch64":
        raise ValueError("Unsupported storage fixture manifest")
    if manifest.get("disk", {}).get("format") != "raw" or manifest["disk"].get("filesystem") != "ext4":
        raise ValueError("Storage proof requires a raw ext4 disk")
    paths = []
    for field, name, maximum in (("kernel", "kernel", 128 * 1024 * 1024),
                                 ("initramfs", "storage-proof.cpio.gz", 128 * 1024 * 1024),
                                 ("disk", "storage-proof.raw", 512 * 1024 * 1024)):
        asset = manifest[field]
        path = root / name
        length = asset.get("bytes")
        if asset.get("file") != name or type(length) is not int or not 1 <= length <= maximum or \
                path.is_symlink() or not path.is_file() or path.stat().st_size != length:
            raise ValueError("Invalid storage fixture asset")
        if hash_file(path) != asset.get("sha256"):
            raise ValueError("Storage fixture asset hash mismatch")
        paths.append(path)
    return tuple(paths)


def boot_command(qemu, kernel, initramfs, disk, qmp):
    command = serial.boot_command(qemu, kernel, initramfs, qmp)
    command += ["-blockdev", json.dumps({"driver": "raw", "node-name": "linexdisk",
                 "file": {"driver": "file", "filename": str(disk)}}, separators=(",", ":")),
                "-device", "virtio-blk-device,drive=linexdisk"]
    return command


def needs_recovery(header):
    features = [line.split(":", 1)[1].split() for line in header.splitlines()
                if line.startswith("Filesystem features:")]
    if len(features) != 1:
        raise ValueError("Missing or duplicate ext4 filesystem features")
    return "needs_recovery" in features[0]


def inode_metadata(output):
    mode = re.findall(r"\bMode:\s+([0-7]{3,4})\b", output)
    owners = re.findall(r"\bUser:\s+(\d+)\s+Group:\s+(\d+)\b", output)
    if len(mode) != 1 or len(owners) != 1:
        raise ValueError("Missing or duplicate debugfs inode metadata")
    return {"uid": int(owners[0][0]), "gid": int(owners[0][1]), "mode": int(mode[0], 8)}


def verify_disk_metadata(debugfs, disk, nonce=False):
    expected = [("/", 0, 0, 0o755), ("/bin/busybox", 0, 0, 0o755),
                ("/etc/shadow", 0, 0, 0o600), ("/root", 0, 0, 0o700)]
    if nonce:
        expected.append(("/home/linex/nonce", 1000, 1000, 0o600))
    evidence = {}
    for path, uid, gid, mode in expected:
        result = subprocess.run([debugfs, "-R", f"stat {path}", str(disk)],
                                check=True, capture_output=True, text=True, timeout=10)
        actual = inode_metadata(result.stdout)
        if actual != {"uid": uid, "gid": gid, "mode": mode}:
            raise RuntimeError(f"Unexpected guest inode metadata for {path}: {actual}")
        evidence[path] = actual
    return evidence


def wait_line(process, capture, wanted, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if wanted in capture.text().splitlines():
            return
        if process.poll() is not None:
            raise RuntimeError(f"Storage guest exited before {wanted}: {process.returncode}")
        time.sleep(0.1)
    raise TimeoutError(f"Storage proof timed out waiting for {wanted}")


def run(args):
    if platform.system() != "Linux":
        raise RuntimeError("Storage runtime proof requires Linux QEMU/ext4 tools")
    if not 10 <= args.timeout <= 600:
        raise ValueError("Storage boot timeout outside proof bounds")
    kernel, initramfs, factory = verify_fixture(args.fixture)
    factory_digest = hash_file(factory)
    args.evidence.mkdir(parents=True, exist_ok=True)
    evidence = {"proof": "Linux host raw ext4 switch-root/non-root persistence/SIGKILL recovery; not Android or desktop",
                "passed": False, "boots": []}
    failure = None
    with tempfile.TemporaryDirectory(prefix="linex-storage-proof-") as directory:
        private = Path(directory)
        private.chmod(0o700)
        working = private / "private-instance.raw"
        shutil.copyfile(factory, working)
        working.chmod(0o600)
        first_nonce, second_nonce = secrets.token_hex(16), secrets.token_hex(16)
        try:
            evidence["factoryInodeMetadata"] = verify_disk_metadata(args.debugfs, factory)
            for index in range(4):
                boot = {"index": index, "hostSamples": [], "passed": False}
                evidence["boots"].append(boot)
                qmp = private / f"qmp{index}"
                process = subprocess.Popen(boot_command(args.qemu, kernel, initramfs, working, qmp),
                                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                boot["hostPid"] = process.pid
                capture = serial.SerialCapture(process.stdout)
                try:
                    def sample(phase):
                        observation = serial.host_process_sample(process.pid)
                        observation["phase"] = phase
                        boot["hostSamples"].append(observation)
                        if observation["hostChildPids"]:
                            raise RuntimeError("Storage-enabled QEMU created a host child")

                    sample("before-storage-boot")
                    wait_line(process, capture, "LINEX_VM_STORAGE_READY", args.timeout)
                    wait_line(process, capture, "LINEX_VM_STORAGE_ROOT source=/dev/vda fstype=ext4", 5)
                    wait_line(process, capture, "LINEX_VM_STORAGE_SWITCH_ROOT", 5)
                    for path, mode in (("/", "755"), ("/bin/busybox", "755"),
                                       ("/etc/shadow", "600"), ("/root", "700")):
                        wait_line(process, capture,
                                  f"LINEX_VM_STORAGE_METADATA path={path} uid=0 gid=0 mode={mode}", 5)
                    boot["guestRootMetadata"] = True
                    expected = "none" if index == 0 else first_nonce if index == 1 else second_nonce
                    expected_uid = "none" if index == 0 else "1000"
                    wait_line(process, capture, f"LINEX_VM_STORAGE_STATUS nonce={expected} uid={expected_uid}", 5)
                    boot.update(switchRoot=True, filesystem="ext4", persistedNonce=expected, persistedUid=expected_uid)
                    sample("guest-root-ready")
                    if index == 2:
                        if not any("EXT4-fs (vda): recovery complete" in line for line in capture.text().splitlines()):
                            raise RuntimeError("Kernel did not report ext4 journal recovery after forced stop")
                        boot["kernelJournalRecovery"] = True
                    if index < 2:
                        nonce = first_nonce if index == 0 else second_nonce
                        process.stdin.write(f"write {nonce}\n".encode("ascii"))
                        process.stdin.flush()
                        wait_line(process, capture, f"LINEX_VM_STORAGE_WRITTEN nonce={nonce} uid=1000", 10)
                        boot.update(writtenNonce=nonce, writtenUid=1000)
                        sample("guest-write-verified")
                    sample("before-stop")
                    if index == 1:
                        process.kill()
                        if process.wait(timeout=10) != -9:
                            raise RuntimeError("Forced-stop proof did not deliver host SIGKILL")
                        boot["forcedStop"] = True
                    else:
                        process.stdin.write(b"stop\n")
                        process.stdin.flush()
                        if process.wait(timeout=15) != 0:
                            raise RuntimeError("Storage guest did not stop cleanly")
                        capture.thread.join(timeout=2)
                        if "LINEX_VM_STORAGE_CLEAN_STOP" not in capture.text().splitlines():
                            raise RuntimeError("Guest did not remount root read-only before poweroff")
                        boot["cleanStop"] = True
                    header = subprocess.run([args.dumpe2fs, "-h", str(working)], check=True,
                                            capture_output=True, text=True, timeout=10)
                    dirty = needs_recovery(header.stdout)
                    if dirty != (index == 1):
                        raise RuntimeError("Unexpected ext4 recovery flag after stop")
                    boot["needsRecoveryAfterStop"] = dirty
                    boot["passed"] = True
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=5)
                    capture.thread.join(timeout=2)
                    (args.evidence / f"storage-boot-{index}.log").write_text(capture.text(), encoding="utf-8")
                    process.stdin.close()
                    process.stdout.close()
            check = subprocess.run([args.e2fsck, "-f", "-n", str(working)], capture_output=True, text=True, timeout=60)
            (args.evidence / "e2fsck.log").write_text(check.stdout + check.stderr, encoding="utf-8")
            if check.returncode != 0:
                raise RuntimeError("Final read-only e2fsck found an error")
            evidence["finalInodeMetadata"] = verify_disk_metadata(args.debugfs, working, nonce=True)
            if hash_file(factory) != factory_digest:
                raise RuntimeError("The immutable factory disk was modified")
            if len({boot["hostPid"] for boot in evidence["boots"]}) != 4:
                raise RuntimeError("Storage proof must use four distinct host processes")
            evidence.update(passed=True, cleanRestartPersistence=True, nonRootWriteUid=1000,
                            forcedStopRecovery=True, factoryUnchanged=True, finalFilesystemCheck=True)
        except BaseException as error:
            failure = error
            evidence["error"] = f"{type(error).__name__}: {error}"
            raise
        finally:
            try:
                (args.evidence / "storage-proof.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            except OSError as write_error:
                if failure is None:
                    raise
                failure.add_note(f"Evidence write failed: {type(write_error).__name__}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=Path("dist/vm-storage-fixture"))
    parser.add_argument("--qemu", default="qemu-system-aarch64")
    parser.add_argument("--dumpe2fs", default="dumpe2fs")
    parser.add_argument("--e2fsck", default="e2fsck")
    parser.add_argument("--debugfs", default="debugfs")
    parser.add_argument("--evidence", type=Path, default=Path("dist/vm-storage-host-proof"))
    parser.add_argument("--timeout", type=int, default=180)
    run(parser.parse_args())

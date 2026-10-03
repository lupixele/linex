#!/usr/bin/env python3
"""Linux host proof harness; Android JNI/device verification is a separate gate."""
import argparse
from collections import deque
import hashlib
import json
from pathlib import Path
import platform
import socket
import subprocess
import tempfile
import threading
import time


def verify_fixture(root):
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema") != 1:
        raise ValueError("Unsupported fixture manifest")
    paths = []
    for field, name in (("kernel", "kernel"), ("initramfs", "boot-proof.cpio.gz")):
        asset = manifest[field]
        if asset.get("file") != name:
            raise ValueError("Unexpected fixture asset filename")
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Fixture asset must be a regular file")
        if hashlib.sha256(path.read_bytes()).hexdigest() != asset.get("sha256"):
            raise ValueError(f"Fixture asset failed SHA256 verification: {name}")
        paths.append(path)
    return tuple(paths)


def boot_command(qemu, kernel, initramfs, qmp):
    return [qemu, "-machine", "virt", "-cpu", "cortex-a53", "-accel", "tcg",
            "-m", "256", "-smp", "1", "-nodefaults", "-nic", "none",
            "-kernel", str(kernel), "-initrd", str(initramfs),
            "-append", "console=ttyAMA0 rdinit=/init panic=-1",
            "-display", "none", "-monitor", "none", "-serial", "stdio",
            "-qmp", f"unix:{qmp},server=on,wait=off", "-no-reboot"]


class SerialCapture:
    def __init__(self, stream):
        self.lines = deque()
        self.total_bytes = 0
        self.booted = False
        self.child_count = 0
        self.child_reports = 0
        self.guest_pids = ()
        self.kernel = None
        self.lock = threading.Lock()
        self.thread = threading.Thread(target=self._read, args=(stream,), daemon=True)
        self.thread.start()

    def _read(self, stream):
        while True:
            raw = stream.readline(16385)
            if not raw:
                return
            line = raw.decode("utf-8", errors="replace").rstrip()
            with self.lock:
                self.lines.append(line)
                self.total_bytes += len(line.encode("utf-8")) + 1
                while (self.total_bytes > 1024 * 1024 or len(self.lines) > 4000) and self.lines:
                    removed = self.lines.popleft()
                    self.total_bytes -= len(removed.encode("utf-8")) + 1
                if line == "LINEX_VM_BOOT_OK":
                    self.booted = True
                if line.startswith("LINEX_VM_KERNEL "):
                    self.kernel = line
                if line.startswith("LINEX_VM_GUEST_CHILDREN count="):
                    try:
                        self.child_count = int(line.split("=", 1)[1])
                        self.child_reports += 1
                    except ValueError:
                        pass
                if line.startswith("LINEX_VM_GUEST_PIDS "):
                    try:
                        values = line.split()[1:]
                        self.guest_pids = tuple(int(value) for value in values) if len(values) <= 64 else ()
                    except ValueError:
                        self.guest_pids = ()

    def snapshot(self):
        with self.lock:
            return self.booted, self.child_count, self.child_reports, self.kernel

    def verified_guest_pids(self):
        with self.lock:
            if len(self.guest_pids) != 64 or len(set(self.guest_pids)) != 64 or min(self.guest_pids) < 2:
                raise ValueError("Guest did not report 64 distinct child PIDs")
            return self.guest_pids

    def text(self):
        with self.lock:
            return "\n".join(self.lines) + "\n"


def host_process_sample(pid):
    children = set()
    tasks = list((Path("/proc") / str(pid) / "task").iterdir())
    for task in tasks:
        try:
            children.update(int(value) for value in (task / "children").read_text().split())
        except FileNotFoundError:
            continue  # A worker thread may exit while sampling.
    return {"threadCount": len(tasks), "hostChildPids": sorted(children)}


class Qmp:
    def __init__(self, path):
        self.socket = socket.socket(socket.AF_UNIX)
        self.socket.settimeout(5)
        self.socket.connect(str(path))
        self.stream = self.socket.makefile("rwb", buffering=0)
        self.sequence = 0
        if "QMP" not in json.loads(self.stream.readline(65537)):
            raise RuntimeError("Missing QMP greeting")
        self.command("qmp_capabilities")

    def command(self, name):
        self.sequence += 1
        self.stream.write(json.dumps({"execute": name, "id": self.sequence}).encode() + b"\n")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            raw = self.stream.readline(65537)
            if not raw or len(raw) > 65536:
                raise RuntimeError("Disconnected or oversized QMP response")
            response = json.loads(raw)
            if response.get("id") == self.sequence:
                if "error" in response:
                    raise RuntimeError(f"QMP command failed: {response['error']}")
                return response.get("return")
        raise TimeoutError("QMP response timed out")

    def close(self):
        self.stream.close()
        self.socket.close()


def run(args):
    if platform.system() != "Linux":
        raise RuntimeError("Host process proof requires Linux /proc; Android proof uses instrumentation")
    kernel, initramfs = verify_fixture(args.fixture)
    args.evidence.mkdir(parents=True, exist_ok=True)
    evidence = {"platform": platform.platform(), "proof": "Linux host QEMU CLI; Android JNI not verified",
                "hostSamples": [], "passed": False}
    with tempfile.TemporaryDirectory(prefix="linex-vm-") as directory:
        private = Path(directory)
        private.chmod(0o700)
        qmp_path = private / "qmp"
        process = subprocess.Popen(boot_command(args.qemu, kernel, initramfs, qmp_path),
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        evidence["hostPid"] = process.pid
        capture = SerialCapture(process.stdout)
        qmp = None
        try:
            deadline = time.monotonic() + args.timeout
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"QEMU exited before guest boot: {process.returncode}")
                sample = host_process_sample(process.pid)
                sample["phase"] = "booting"
                evidence["hostSamples"].append(sample)
                if sample["hostChildPids"]:
                    raise RuntimeError("QEMU created a host child process")
                booted, count, _, kernel_line = capture.snapshot()
                if booted and count == 64:
                    evidence["guestChildCount"] = count
                    evidence["guestKernel"] = kernel_line
                    evidence["guestPids"] = capture.verified_guest_pids()
                    break
                time.sleep(0.5)
            else:
                raise TimeoutError("Guest did not boot and retain 64 children within deadline")
            qmp = Qmp(qmp_path)
            qmp.command("stop")
            if qmp.command("query-status")["status"] != "paused":
                raise RuntimeError("QMP pause did not pause the VM")
            qmp.command("cont")
            if qmp.command("query-status")["status"] != "running":
                raise RuntimeError("QMP resume did not resume the VM")
            previous_reports = capture.snapshot()[2]
            process.stdin.write(b"status\n")
            process.stdin.flush()
            status_deadline = time.monotonic() + 10
            while time.monotonic() < status_deadline:
                sample = host_process_sample(process.pid)
                sample["phase"] = "children-active"
                evidence["hostSamples"].append(sample)
                if sample["hostChildPids"]:
                    raise RuntimeError("Guest forks created host child processes")
                _, count, reports, _ = capture.snapshot()
                if reports > previous_reports:
                    if count != 64:
                        raise RuntimeError("Guest children were not retained after resume")
                    break
                time.sleep(0.2)
            else:
                raise TimeoutError("Guest serial status response timed out")
            previous_reports = capture.snapshot()[2]
            process.stdin.write(b"clear\n")
            process.stdin.flush()
            clear_deadline = time.monotonic() + 10
            while time.monotonic() < clear_deadline:
                sample = host_process_sample(process.pid)
                sample["phase"] = "clearing-guest-children"
                evidence["hostSamples"].append(sample)
                if sample["hostChildPids"]:
                    raise RuntimeError("Host children appeared after guest child cleanup")
                _, count, reports, _ = capture.snapshot()
                if reports > previous_reports:
                    if count != 0:
                        raise RuntimeError("Guest clear command did not remove the child processes")
                    evidence["guestChildrenCleared"] = True
                    evidence["guestChildCountAfterClear"] = 0
                    after_clear = host_process_sample(process.pid)
                    after_clear["phase"] = "children-cleared"
                    evidence["hostSamples"].append(after_clear)
                    if after_clear["hostChildPids"]:
                        raise RuntimeError("Host children remained after guest child cleanup")
                    break
                time.sleep(0.2)
            else:
                raise TimeoutError("Guest clear response timed out")
            process.stdin.write(b"stop\n")
            process.stdin.flush()
            process.wait(timeout=15)
            if process.returncode != 0:
                raise RuntimeError(f"Guest stop failed: QEMU exit {process.returncode}")
            evidence["pauseResume"] = True
            evidence["cleanStop"] = True
            evidence["passed"] = True
        except Exception as error:
            evidence["error"] = str(error)
            raise
        finally:
            if process.poll() is None:
                if qmp is not None:
                    try:
                        qmp.command("quit")
                    except (OSError, RuntimeError, TimeoutError):
                        pass
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            if qmp is not None:
                qmp.close()
            capture.thread.join(timeout=2)
            (args.evidence / "serial.log").write_text(capture.text(), encoding="utf-8")
            (args.evidence / "host-proof.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            process.stdin.close()
            process.stdout.close()
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qemu", default="qemu-system-aarch64")
    parser.add_argument("--fixture", type=Path, default=Path("dist/vm-fixture"))
    parser.add_argument("--evidence", type=Path, default=Path("dist/vm-host-proof"))
    parser.add_argument("--timeout", type=int, choices=range(30, 601), default=180)
    run(parser.parse_args())

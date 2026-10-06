#!/usr/bin/env python3
"""Actual Linux-host DHCP/route/TCP proof; Android, DNS and HTTPS are separate gates."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import socket
import subprocess
import tempfile
import threading
import time

spec = importlib.util.spec_from_file_location("linex_serial_harness", Path(__file__).with_name("test-boot.py"))
serial = importlib.util.module_from_spec(spec)
spec.loader.exec_module(serial)


def verify_fixture(root):
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema") != 1:
        raise ValueError("Unsupported network fixture manifest")
    paths = []
    for field, name in (("kernel", "kernel"), ("initramfs", "network-proof.cpio.gz")):
        asset = manifest[field]
        path = root / name
        if asset.get("file") != name or path.is_symlink() or not path.is_file() or path.stat().st_size > 128 * 1024 * 1024:
            raise ValueError("Invalid network fixture asset")
        if hashlib.sha256(path.read_bytes()).hexdigest() != asset.get("sha256"):
            raise ValueError("Network fixture asset hash mismatch")
        paths.append(path)
    return tuple(paths)


def boot_command(qemu, kernel, initramfs, qmp):
    command = serial.boot_command(qemu, kernel, initramfs, qmp)
    index = command.index("-nic")
    command[index:index + 2] = ["-netdev", "user,id=linexnet,ipv6=off",
                                "-device", "virtio-net-device,netdev=linexnet"]
    return command


class TcpChallenge:
    """One owned loopback-only challenge. Both guest and server must verify bytes."""
    def __init__(self):
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(10)
        self.port = self.listener.getsockname()[1]
        self.completed = threading.Event()
        self.request_verified = False
        self.error = None
        self.worker = threading.Thread(target=self._serve, daemon=True)
        self.worker.start()

    def _serve(self):
        try:
            peer, _ = self.listener.accept()
            with peer:
                peer.settimeout(5)
                request = bytearray()
                while len(request) < 129 and b"\n" not in request:
                    chunk = peer.recv(129 - len(request))
                    if not chunk:
                        break
                    request.extend(chunk)
                if bytes(request) != b"LINEX_VM_TCP_REQUEST\n":
                    raise ValueError("TCP challenge request is incorrect or oversized")
                self.request_verified = True
                peer.sendall(b"LINEX_VM_TCP_RESPONSE\n")
        except Exception as error:
            self.error = type(error).__name__
        finally:
            self.completed.set()

    def close(self):
        try:
            self.listener.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.listener.close()
        self.worker.join(timeout=1)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def wait_for(process, capture, predicate, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Guest exited before network proof: {process.returncode}")
        if predicate(capture):
            return
        time.sleep(0.1)
    raise TimeoutError("Guest network proof timed out")


def run(args):
    if platform.system() != "Linux":
        raise RuntimeError("Host proof requires Linux /proc; Android uses its own instrumentation")
    if not 10 <= args.timeout <= 600:
        raise ValueError("Network boot timeout outside proof bounds")
    kernel, initramfs = verify_fixture(args.fixture)
    args.evidence.mkdir(parents=True, exist_ok=True)
    evidence = {"proof": "Linux host DHCP/default-route/controlled TCP; not Android, public-internet, DNS or HTTPS proof",
                "passed": False, "hostSamples": []}
    failure = None
    with tempfile.TemporaryDirectory(prefix="linex-network-") as directory:
        private = Path(directory)
        private.chmod(0o700)
        qmp_path = private / "qmp"
        process = subprocess.Popen(boot_command(args.qemu, kernel, initramfs, qmp_path),
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        evidence["hostPid"] = process.pid
        capture = serial.SerialCapture(process.stdout)
        try:
            def sample(phase):
                observation = serial.host_process_sample(process.pid)
                observation["phase"] = phase
                if len(evidence["hostSamples"]) >= 512:
                    raise RuntimeError("Host process observation limit exceeded")
                evidence["hostSamples"].append(observation)
                if observation["hostChildPids"]:
                    raise RuntimeError("Network-enabled QEMU created a host child")
            sample("before-guest-network")
            wait_for(process, capture, lambda item: "LINEX_VM_NETWORK_READY\n" in item.text(), args.timeout)
            lines = capture.text().splitlines()
            if "LINEX_VM_DHCP ip=10.0.2.15 gateway=10.0.2.2 dns=10.0.2.3" not in lines or \
                    "LINEX_VM_NETWORK_ADDRESS address=10.0.2.15/24 route=10.0.2.2 dns=10.0.2.3" not in lines:
                raise RuntimeError("Guest DHCP/installed address/default route/DNS advertisement were not verified")
            booted, children, _, kernel_line = capture.snapshot()
            if not booted or children != 64:
                raise RuntimeError("Networking fixture must retain all 64 guest children")
            evidence["guestPids"] = capture.verified_guest_pids()
            evidence["guestKernel"] = kernel_line
            evidence.update(dhcp=True, installedAddress="10.0.2.15/24", defaultRoute="10.0.2.2",
                            advertisedDns="10.0.2.3", guestChildrenDuring=64)
            sample("guest-network-ready")
            with TcpChallenge() as challenge:
                process.stdin.write(f"tcp {challenge.port}\n".encode("ascii"))
                process.stdin.flush()
                wait_for(process, capture, lambda item: "LINEX_VM_TCP_OK\n" in item.text(), 10)
                if not challenge.completed.wait(2) or not challenge.request_verified or challenge.error:
                    raise RuntimeError("Host did not independently verify the guest TCP challenge")
                evidence["tcpChallenge"] = True
                sample("tcp-challenge-verified")
            process.stdin.write(b"clear\n")
            process.stdin.flush()
            wait_for(process, capture, lambda item: item.snapshot()[1] == 0, 5)
            evidence["guestChildrenAfter"] = 0
            sample("after-guest-clear")
            process.stdin.write(b"stop\n")
            process.stdin.flush()
            if process.wait(timeout=10) != 0:
                raise RuntimeError("Network proof guest did not stop cleanly")
            evidence["cleanStop"] = True
            evidence["passed"] = True
        except BaseException as error:
            failure = error
            evidence["failureClass"] = type(error).__name__
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            capture.thread.join(timeout=2)
            for stream in (process.stdin, process.stdout):
                stream.close()
            try:
                (args.evidence / "serial.log").write_text(capture.text(), encoding="utf-8")
                (args.evidence / "network-proof.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            except OSError as write_error:
                if failure is None:
                    raise
                failure.add_note(f"Evidence write failed: {type(write_error).__name__}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=Path("dist/vm-network-fixture"))
    parser.add_argument("--qemu", default="qemu-system-aarch64")
    parser.add_argument("--evidence", type=Path, default=Path("dist/vm-network-host-proof"))
    parser.add_argument("--timeout", type=int, default=180)
    run(parser.parse_args())

#!/usr/bin/env python3
"""Actual Linux-host desktop proof; loopback TCP is not Android private Unix proof."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import secrets
import shutil
import socket
import struct
import subprocess
import tempfile
import time

spec = importlib.util.spec_from_file_location("desktop_storage_harness", Path(__file__).with_name("test-storage.py"))
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)


def receive(connection, count):
    if not 0 <= count <= 32000000:
        raise ValueError("RFB receive exceeds framebuffer bounds")
    result = bytearray()
    while len(result) < count:
        chunk = connection.recv(count - len(result))
        if not chunk:
            raise EOFError("RFB connection closed")
        result.extend(chunk)
    return bytes(result)


def authenticate(connection, password):
    from Crypto.Cipher import DES  # Genuine distro python3-pycryptodome on host CI only.
    version = receive(connection, 12)
    if version != b"RFB 003.008\n":
        raise ValueError("Expected TigerVNC RFB3.8")
    connection.sendall(version)
    count = receive(connection, 1)[0]
    if not 1 <= count <= 32:
        raise ValueError("Invalid RFB security type count")
    offered = receive(connection, count)
    if offered != b"\x02":
        raise ValueError("Guest must offer only VNCAuth, no unauthenticated access")
    connection.sendall(b"\x02")
    challenge = receive(connection, 16)
    key = bytes(int(f"{byte:08b}"[::-1], 2) for byte in password.encode("ascii"))
    connection.sendall(DES.new(key, DES.MODE_ECB).encrypt(challenge))
    if receive(connection, 4) != b"\x00\x00\x00\x00":
        raise ValueError("Per-launch VNC authentication failed")
    connection.sendall(b"\x01")
    header = receive(connection, 24)
    width, height = struct.unpack_from(">HH", header)
    name_length = struct.unpack_from(">I", header, 20)[0]
    if name_length > 4096 or (width, height) != (1280, 720):
        raise ValueError("Guest desktop geometry/server name outside proof bounds")
    receive(connection, name_length)
    # 32bpp little endian true colour, R/G/B shifts16/8/0, Raw-only decoder.
    connection.sendall(b"\x00\x00\x00\x00" + struct.pack(">BBBBHHHBBBxxx", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0))
    connection.sendall(struct.pack(">BBHi", 2, 0, 1, 0))
    return width, height


def frame(connection, width, height):
    connection.sendall(struct.pack(">BBHHHH", 3, 0, 0, 0, width, height))
    pixels = bytearray(width * height * 4)
    covered = bytearray(width * height)
    touched = 0
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        kind = receive(connection, 1)[0]
        if kind == 2:  # Bell
            continue
        if kind == 3:  # ServerCutText; never record clipboard contents.
            header = receive(connection, 7)
            length = struct.unpack_from(">I", header, 3)[0]
            if length > 65536:
                raise ValueError("Clipboard event exceeds proof bounds")
            receive(connection, length)
            continue
        if kind != 0:
            raise ValueError("Unsupported RFB proof message")
        rectangles = struct.unpack_from(">H", receive(connection, 3), 1)[0]
        if rectangles > 256:
            raise ValueError("RFB rectangle count exceeds bounds")
        for _ in range(rectangles):
            x, y, w, h, encoding = struct.unpack(">HHHHi", receive(connection, 12))
            if encoding != 0 or w * h < 1 or x + w > width or y + h > height:
                raise ValueError("Invalid Raw desktop rectangle")
            payload = receive(connection, w * h * 4)
            for row in range(h):
                offset = ((y + row) * width + x) * 4
                pixels[offset:offset + w * 4] = payload[row * w * 4:(row + 1) * w * 4]
                area = (y + row) * width + x
                touched += covered[area:area + w].count(0)
                covered[area:area + w] = b"\x01" * w
        if touched >= width * height:
            return bytes(pixels)
    raise TimeoutError("No completed full desktop framebuffer")


def write_ppm(path, pixels, width, height):
    rgb = bytearray()
    for offset in range(0, len(pixels), 4):
        rgb.extend((pixels[offset + 2], pixels[offset + 1], pixels[offset]))
    path.write_bytes(f"P6\n{width} {height}\n255\n".encode("ascii") + rgb)


def run(args):
    if platform.system() != "Linux" or not 60 <= args.timeout <= 600:
        raise ValueError("Actual desktop proof requires Linux and bounded boot timeout")
    manifest = json.loads((args.image / "manifest.json").read_text(encoding="utf-8"))
    for field, name in (("kernel", "kernel"), ("initramfs", "desktop.cpio.gz"), ("disk", "factory.raw")):
        path = args.image / name
        metadata = manifest[field]
        if metadata.get("file") != name or path.is_symlink() or not path.is_file() or \
                path.stat().st_size != metadata["bytes"] or storage.hash_file(path) != metadata["sha256"]:
            raise ValueError("Desktop factory asset validation failed")
    args.evidence.mkdir(parents=True, exist_ok=True)
    evidence = {"proof": "Linux-host authenticated guest VNC/display/input/HTTPS/headless Firefox; host-only loopback TCP",
                "passed": False, "androidPrivateUnixProved": False, "physicalPhonePerformanceProved": False}
    with tempfile.TemporaryDirectory(prefix="linex-desktop-proof-") as directory:
        private = Path(directory)
        working = private / "instance.raw"
        shutil.copyfile(args.image / "factory.raw", working)
        working.chmod(0o600)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        command = storage.boot_command(args.qemu, args.image / "kernel", args.image / "desktop.cpio.gz", working, private / "qmp")
        command[command.index("-m") + 1] = "2048"
        command[command.index("-smp") + 1] = "2"
        command += ["-netdev", f"user,id=net0,hostfwd=tcp:127.0.0.1:{port}-10.0.2.15:5901",
                    "-device", "virtio-net-device,netdev=net0"]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        capture = storage.serial.SerialCapture(process.stdout)
        connection = None
        try:
            storage.wait_line(process, capture, "LINEX_VM_DESKTOP_CONTROL_READY", args.timeout)
            token, password = secrets.token_hex(16), secrets.token_urlsafe(6)
            def send(value):
                process.stdin.write(json.dumps(value, separators=(",", ":")).encode("ascii") + b"\n")
                process.stdin.flush()
            send({"command": "launch", "session": token, "password": password, "width": 1280, "height": 720, "fps": 30})
            storage.wait_line(process, capture, f"LINEX_VM_DESKTOP_READY session={token}", 60)
            connection = socket.create_connection(("127.0.0.1", port), timeout=30)
            width, height = authenticate(connection, password)
            initial = frame(connection, width, height)
            write_ppm(args.evidence / "desktop-initial.ppm", initial, width, height)
            evidence.update(vncAuth=True, width=width, height=height, initialFrameSha256=hashlib.sha256(initial).hexdigest())
            send({"command": "proof", "session": token})
            storage.wait_line(process, capture, "LINEX_VM_DESKTOP_PROOF_STARTED uid=1000", 10)
            time.sleep(8)
            for mask in (1, 0, 4, 0, 8, 0):
                connection.sendall(struct.pack(">BBHH", 5, mask, 150, 150))
                time.sleep(0.1)
            for down in (1, 0):
                connection.sendall(struct.pack(">BBHI", 4, down, 0, ord("a")))
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                output = capture.text()
                if "KeyPress event" in output and "ButtonPress event" in output and "button 1" in output and "button 3" in output and "button 4" in output:
                    evidence["keyboardLeftRightScrollInput"] = True
                    break
                if process.poll() is not None:
                    raise RuntimeError("Guest stopped during input proof")
                time.sleep(0.2)
            if not evidence.get("keyboardLeftRightScrollInput"):
                raise RuntimeError("Actual guest key/left/right/scroll event evidence missing")
            storage.wait_line(process, capture, "LINEX_VM_DESKTOP_HTTPS_VERIFIED uid=1000", 60)
            storage.wait_line(process, capture, "LINEX_VM_DESKTOP_BROWSER_SCREENSHOT uid=1000", args.timeout)
            evidence.update(defaultCaHttps=True, nonRootHeadlessFirefoxScreenshot=True)
            sample = storage.serial.host_process_sample(process.pid)
            if sample["hostChildPids"]:
                raise RuntimeError("Desktop QEMU created host guest-process children")
            evidence["hostSample"] = sample
            final = frame(connection, width, height)
            write_ppm(args.evidence / "desktop-input.ppm", final, width, height)
            connection.close()
            connection = None
            send({"command": "stop", "session": token})
            if process.wait(timeout=45) != 0:
                raise RuntimeError("Guest desktop did not stop cleanly")
            capture.thread.join(timeout=2)
            if "LINEX_VM_DESKTOP_CLEAN_STOP" not in capture.text().splitlines():
                raise RuntimeError("Missing guest read-only root close")
            subprocess.run(["e2fsck", "-f", "-n", str(working)], check=True, capture_output=True, timeout=90)
            evidence.update(passed=True, cleanStop=True, finalFilesystemCheck=True)
        except BaseException as error:
            evidence["error"] = f"{type(error).__name__}: {error}"
            raise
        finally:
            if connection is not None:
                connection.close()
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            capture.thread.join(timeout=2)
            (args.evidence / "desktop-serial.log").write_text(capture.text(), encoding="utf-8")
            (args.evidence / "desktop-proof.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            process.stdin.close()
            process.stdout.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, default=Path("dist/vm-desktop-image"))
    parser.add_argument("--evidence", type=Path, default=Path("dist/vm-desktop-host-proof"))
    parser.add_argument("--qemu", default="qemu-system-aarch64")
    parser.add_argument("--timeout", type=int, default=300)
    run(parser.parse_args())

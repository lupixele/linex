#!/usr/bin/python3
"""Guest PID1: bounded private serial commands and a non-root XFCE/VNC session."""
import json
import os
from pathlib import Path
import re
import select
import signal
import socket
import secrets
import stat
import subprocess
import termios
import time

SESSION = re.compile(r"[a-f0-9]{32}")
PASSWORD = re.compile(r"[A-Za-z0-9_-]{8}")
FPS = (15, 24, 30, 45, 60, 75, 90, 120, 144)
MAX_LINE = 512


def validate_launch(value):
    if not isinstance(value, dict) or set(value) != {"command", "session", "password", "width", "height", "fps", "epochSeconds"}:
        raise ValueError("Invalid typed launch fields")
    if value["command"] != "launch" or not isinstance(value["session"], str) or not SESSION.fullmatch(value["session"]):
        raise ValueError("Invalid session token")
    if not isinstance(value["password"], str) or not PASSWORD.fullmatch(value["password"]):
        raise ValueError("Invalid console credential")
    width, height, fps = value["width"], value["height"], value["fps"]
    if any(type(number) is not int for number in (width, height, fps)):
        raise ValueError("Desktop settings must be integers")
    if not 640 <= width <= 4096 or not 480 <= height <= 4096 or width * height > 8000000 or fps not in FPS:
        raise ValueError("Desktop setting exceeds bounds")
    epoch = value["epochSeconds"]
    if type(epoch) is not int or not 1700000000 <= epoch <= 4102444800:
        raise ValueError("Guest clock must be a bounded host Unix timestamp")
    return value


def run(command):
    subprocess.run(command, check=True, timeout=30)


def nonroot(command):
    return ["/usr/sbin/runuser", "-u", "linex", "--"] + command


def ensure_machine_identity(path):
    if path.is_symlink():
        raise ValueError("Unsafe guest machine identity")
    if path.exists():
        if not stat.S_ISREG(path.stat().st_mode) or path.stat().st_size > 33:
            raise ValueError("Invalid guest machine identity file")
        current = path.read_bytes()
        if current:
            if not re.fullmatch(b"[a-f0-9]{32}\n?", current):
                raise ValueError("Invalid persistent guest machine identity")
            return
    # dbus-uuidgen --ensure does not replace a factory's intentionally empty file.
    # Guest root alone executes this before any guest service/user process starts.
    identifier = secrets.token_hex(16).encode("ascii") + b"\n"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC |
                         getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0), 0o444)
    try:
        if os.write(descriptor, identifier) != len(identifier):
            raise OSError("Short guest identity write")
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    path.chmod(0o444)


def stop_owned(children):
    for child in children:
        if child.poll() is None:
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    for child in children:
        try:
            child.wait(timeout=8)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait(timeout=2)


def wait_display_ready(vnc):
    deadline = time.monotonic() + 20
    last_errno = None
    last_failure = "none"
    while time.monotonic() < deadline:
        if vnc.poll() is not None:
            raise RuntimeError("Guest display process exited before readiness")
        if Path("/tmp/.X11-unix/X1").exists():
            try:
                with socket.create_connection(("10.0.2.15", 5901), timeout=0.5) as probe:
                    banner = bytearray()
                    while len(banner) < 12:
                        chunk = probe.recv(12 - len(banner))
                        if not chunk:
                            raise ConnectionError("Guest RFB listener closed before its banner")
                        banner.extend(chunk)
                    if bytes(banner) != b"RFB 003.008\n":
                        raise RuntimeError("Guest listener is not the expected authenticated desktop protocol")
                    if vnc.poll() is not None:
                        raise RuntimeError("Guest display process exited during readiness")
                    return
            except OSError as error:
                # X11 can be created before TigerVNC binds its RFB port.
                last_errno = error.errno
                last_failure = type(error).__name__
        time.sleep(0.1)
    print(f"LINEX_VM_DESKTOP_DISPLAY_NOT_READY probeFailure={last_failure} probeErrno={last_errno} x11Socket={Path('/tmp/.X11-unix/X1').exists()}", flush=True)
    raise RuntimeError("Guest display did not become ready before deadline")


def launch(value, environment):
    owned = []
    credential = Path("/run/linex/passwd")
    created = False
    try:
        # The minimal kernel need not load a virtual RTC driver. Use the private
        # app-supplied wall clock; HTTPS certificate time validation remains on.
        time.clock_settime(time.CLOCK_REALTIME, value["epochSeconds"])
        secret = subprocess.run(nonroot(["tigervncpasswd", "-f"]),
            input=(value["password"] + "\n").encode("ascii"), capture_output=True, check=True, timeout=5).stdout
        if len(secret) != 8:
            raise ValueError("Invalid generated VNC credential")
        descriptor = os.open(credential, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        created = True
        try:
            if os.write(descriptor, secret) != 8:
                raise OSError("Short credential write")
        finally:
            os.close(descriptor)
        os.chown(credential, 1000, 1000)
        vnc = subprocess.Popen(nonroot(["/usr/bin/Xtigervnc", ":1", "-geometry",
            f'{value["width"]}x{value["height"]}', "-depth", "24", "-SecurityTypes", "VncAuth",
            "-PasswordFile", str(credential), "-localhost", "no", "-interface", "10.0.2.15",
            "-rfbport", "5901", "-FrameRate", str(value["fps"]), "-nolisten", "tcp"]),
            env=environment, start_new_session=True)
        vnc.linex_kind = "vnc"
        owned.append(vnc)
        wait_display_ready(vnc)
        desktop = subprocess.Popen(nonroot(["dbus-run-session", "--", "xfce4-session"]),
                                   env=environment, start_new_session=True)
        desktop.linex_kind = "xfce"
        owned.append(desktop)
        return owned
    except BaseException:
        stop_owned(owned)
        if created:
            credential.unlink(missing_ok=True)
        raise


def main():
    if os.getpid() != 1 or os.geteuid() != 0:
        raise RuntimeError("Guest desktop init must run only as guest PID1")
    console = os.open("/dev/console", os.O_RDWR)
    for descriptor in (0, 1, 2):
        os.dup2(console, descriptor)
    settings = termios.tcgetattr(0)
    settings[3] &= ~(termios.ECHO | termios.ICANON)
    settings[6][termios.VMIN], settings[6][termios.VTIME] = 1, 0
    termios.tcsetattr(0, termios.TCSANOW, settings)
    os.environ["PATH"] = "/usr/sbin:/usr/bin:/sbin:/bin"
    run(["mount", "-t", "tmpfs", "-o", "mode=0755", "tmpfs", "/run"])
    Path("/dev/shm").mkdir(exist_ok=True)
    run(["mount", "-t", "tmpfs", "-o", "mode=1777", "tmpfs", "/dev/shm"])
    Path("/tmp/.X11-unix").mkdir(mode=0o1777, exist_ok=True)
    Path("/tmp/.X11-unix").chmod(0o1777)
    Path("/run/linex").mkdir(mode=0o700)
    os.chown("/run/linex", 1000, 1000)
    Path("/run/user/1000").mkdir(parents=True, mode=0o700)
    os.chown("/run/user/1000", 1000, 1000)
    Path("/run/dbus").mkdir()
    ensure_machine_identity(Path("/etc/machine-id"))
    run(["dbus-daemon", "--system", "--fork", "--nopidfile"])
    run(["/bin/busybox", "ifconfig", "lo", "up"])
    run(["/bin/busybox", "ifconfig", "eth0", "up"])
    run(["/bin/busybox", "udhcpc", "-i", "eth0", "-q", "-n", "-t", "5", "-T", "2", "-s", "/linex-dhcp"])
    run(["ip", "-brief", "address", "show", "lo"])
    run(["ip", "-brief", "address", "show", "eth0"])
    run(["ip", "route", "show"])
    environment = {**os.environ, "HOME": "/home/linex", "USER": "linex", "LOGNAME": "linex",
                   "DISPLAY": ":1", "XDG_RUNTIME_DIR": "/run/user/1000"}
    children, session, buffered, dropping = [], None, bytearray(), False
    print("LINEX_VM_DESKTOP_CONTROL_READY", flush=True)
    while True:
        for child in list(children):
            if child.poll() is not None:
                print(f"LINEX_VM_DESKTOP_PROCESS_EXIT kind={child.linex_kind} code={child.returncode}", flush=True)
                if child.linex_kind == "firefox-proof" and child.returncode == 0:
                    screenshot = Path("/home/linex/browser-proof.png")
                    if screenshot.is_file() and screenshot.stat().st_uid == 1000 and screenshot.stat().st_size > 1024:
                        print("LINEX_VM_DESKTOP_BROWSER_SCREENSHOT uid=1000", flush=True)
                if child.linex_kind == "curl-proof" and child.returncode == 0:
                    page = Path("/home/linex/tls-proof.html")
                    if page.is_file() and page.stat().st_uid == 1000 and page.stat().st_size <= 65536 and \
                            b"Example Domain" in page.read_bytes():
                        print("LINEX_VM_DESKTOP_HTTPS_VERIFIED uid=1000", flush=True)
                children.remove(child)
        # Reap adopted grandchildren without competing with live Popen children.
        adopted = []
        for candidate in Path("/proc").iterdir():
            if candidate.name.isdecimal():
                try:
                    if int((candidate / "stat").read_text().split(") ", 1)[1].split()[1]) == 1:
                        adopted.append(int(candidate.name))
                except (OSError, ValueError, IndexError):
                    pass
        live = {child.pid for child in children}
        for pid in adopted:
            if pid not in live:
                try:
                    os.waitpid(pid, os.WNOHANG)
                except ChildProcessError:
                    pass
        if not select.select([0], [], [], 1)[0]:
            continue
        chunk = os.read(0, 256)
        if not chunk:
            time.sleep(1)
            continue
        for byte in chunk:
            if byte != 10:
                if not dropping:
                    buffered.append(byte)
                    if len(buffered) > MAX_LINE:
                        buffered.clear()
                        dropping = True
                continue
            if dropping:
                dropping = False
                print("LINEX_VM_DESKTOP_CONTROL_REJECTED", flush=True)
                continue
            encoded, buffered = bytes(buffered), bytearray()
            try:
                request = json.loads(encoded)
                if not isinstance(request, dict):
                    raise ValueError("Control payload must be an object")
                if request.get("command") == "launch":
                    value = validate_launch(request)
                    if session is not None:
                        raise ValueError("Desktop already configured")
                    children.extend(launch(value, environment))
                    session = value["session"]
                    print(f"LINEX_VM_DESKTOP_READY session={session}", flush=True)
                elif isinstance(request, dict) and request == {"command": "stop", "session": session} and session is not None:
                    stop_owned(children)
                    run(["sync"])
                    run(["mount", "-t", "ext4", "-o", "remount,ro", "/dev/vda", "/"])
                    print("LINEX_VM_DESKTOP_CLEAN_STOP", flush=True)
                    run(["/bin/busybox", "poweroff", "-f"])
                elif request == {"command": "proof", "session": session} and session is not None:
                    if any(child.linex_kind.endswith("-proof") for child in children):
                        raise ValueError("Proof already active")
                    commands = (
                        ("input-proof", ["xev", "-geometry", "300x200+50+50", "-name", "LinexInputProof",
                                         "-event", "keyboard", "-event", "mouse"]),
                        ("curl-proof", ["curl", "--fail", "--silent", "--show-error", "--max-time", "30",
                                        "--output", "/home/linex/tls-proof.html", "https://example.com/"]),
                        ("firefox-proof", ["firefox-esr", "--headless", "--no-remote", "--screenshot",
                                           "/home/linex/browser-proof.png", "--window-size", "1280,720", "https://example.com/"]),
                    )
                    for kind, command in commands:
                        child = subprocess.Popen(nonroot(command), env=environment, start_new_session=True)
                        child.linex_kind = kind
                        children.append(child)
                    print("LINEX_VM_DESKTOP_PROOF_STARTED uid=1000", flush=True)
                else:
                    raise ValueError("Unsupported typed control command")
            except (ValueError, TypeError, AttributeError, subprocess.SubprocessError, OSError, RuntimeError):
                # Never echo a rejected payload, credential or full subprocess argv.
                print("LINEX_VM_DESKTOP_CONTROL_REJECTED", flush=True)


if __name__ == "__main__":
    main()

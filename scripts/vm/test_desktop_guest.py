import gzip
import importlib.util
from pathlib import Path
import sys
import struct
import io
import unittest
import tempfile
from unittest import mock
from test_fixture import parse_newc


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


prepare = module("test_desktop_prepare", Path(__file__).with_name("prepare-desktop-image.py"))
harness = module("test_desktop_runtime", Path(__file__).with_name("test-desktop-image.py"))
# termios is Linux-only; load validator in a namespace without executing guest imports.
source = Path(__file__).with_name("guest").joinpath("desktop-init.py").read_text(encoding="utf-8")
validator = source[source.index("SESSION ="):source.index("\ndef run(")]
namespace = {"re": __import__("re")}
exec(validator, namespace)
with mock.patch.dict(sys.modules, {"termios": mock.Mock()}):
    guest = module("test_desktop_pid1", Path(__file__).with_name("guest") / "desktop-init.py")


class DesktopGuestTest(unittest.TestCase):
    def test_factory_empty_machine_identity_becomes_unique_and_persists_after_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            first, second = Path(directory) / "first", Path(directory) / "second"
            first.write_bytes(b"")
            guest.ensure_machine_identity(first)
            original = first.read_bytes()
            self.assertRegex(original.decode(), r"^[a-f0-9]{32}\n$")
            guest.ensure_machine_identity(first)
            self.assertEqual(original, first.read_bytes())
            guest.ensure_machine_identity(second)
            self.assertNotEqual(original, second.read_bytes())
            first.chmod(0o644)
            second.chmod(0o644)
            first.write_bytes(b"invalid persistent identity")
            with self.assertRaises(ValueError):
                guest.ensure_machine_identity(first)

    def test_failed_launch_removes_only_fresh_credential_and_reaps_owned_child(self):
        value = {"width": 1280, "height": 720, "fps": 30, "password": "Ab12_-cd"}
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory)
            credential, display = private / "passwd", private / "display"
            display.touch()
            def guest_path(path):
                return credential if str(path) == "/run/linex/passwd" else display
            child = mock.Mock(pid=123)
            child.poll.return_value = None
            with mock.patch.object(guest, "Path", side_effect=guest_path), \
                    mock.patch.object(guest.subprocess, "run", return_value=mock.Mock(stdout=b"12345678")), \
                    mock.patch.object(guest.subprocess, "Popen", side_effect=[child, RuntimeError("spawn failed")]), \
                    mock.patch.object(guest.os, "chown", create=True), \
                    mock.patch.object(guest.os, "killpg", create=True) as kill:
                with self.assertRaises(RuntimeError):
                    guest.launch(value, {})
                self.assertFalse(credential.exists())
                kill.assert_called_once_with(123, guest.signal.SIGTERM)
                child.wait.assert_called_once_with(timeout=8)
            credential.write_bytes(b"existing")
            with mock.patch.object(guest, "Path", side_effect=guest_path), \
                    mock.patch.object(guest.subprocess, "run", return_value=mock.Mock(stdout=b"12345678")):
                with self.assertRaises(FileExistsError):
                    guest.launch(value, {})
            self.assertEqual(b"existing", credential.read_bytes())

    def test_initramfs_busybox_rejects_dynamic_or_foreign_elf(self):
        elf = bytearray(120)
        elf[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<H", elf, 18, 183)
        struct.pack_into("<Q", elf, 32, 64)
        struct.pack_into("<HH", elf, 54, 56, 1)
        struct.pack_into("<I", elf, 64, 1)
        self.assertEqual(elf, prepare.verify_static_busybox(elf))
        for section in (2, 3):
            struct.pack_into("<I", elf, 64, section)
            with self.assertRaises(ValueError):
                prepare.verify_static_busybox(elf)
        struct.pack_into("<H", elf, 18, 62)
        with self.assertRaises(ValueError):
            prepare.verify_static_busybox(elf)

    def test_console_launch_is_typed_bounded_and_rejects_secret_shell_strings(self):
        valid = {"command": "launch", "session": "a" * 32, "password": "Ab12_-cd", "width": 1280, "height": 720, "fps": 60}
        self.assertEqual(valid, namespace["validate_launch"](valid))
        for key, value in (("password", "$(x)xxxx"), ("session", "../escape"), ("width", True),
                           ("height", 4097), ("fps", 1000), ("extra", "x")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                namespace["validate_launch"]({**valid, key: value})
        with self.assertRaises(ValueError):
            namespace["validate_launch"]({**valid, "width": 4096, "height": 4096})
        with self.assertRaises(ValueError):
            namespace["validate_launch"]({**valid, "width": 4096, "height": 2000})

    def test_rfb_completed_frame_tracks_pixel_coverage_and_rejects_outside_rectangles(self):
        class Wire:
            def __init__(self, data):
                self.stream = io.BytesIO(data)
            def recv(self, size):
                return self.stream.read(size)
            def sendall(self, data):
                pass
        def update(x, w, data):
            return b"\x00\x00\x00\x01" + struct.pack(">HHHHi", x, 0, w, 1, 0) + data
        first, second = b"AAAA", b"BBBB"
        wire = Wire(update(0, 1, first) + update(0, 1, first) + update(1, 1, second))
        self.assertEqual(first + second, harness.frame(wire, 2, 1))
        with self.assertRaises(ValueError):
            harness.frame(Wire(update(2, 1, first)), 2, 1)

    def test_small_initramfs_uses_static_busybox_and_switches_to_debian_pid1(self):
        payload = prepare.initramfs(b"static ELF fixture")
        self.assertEqual(payload, prepare.initramfs(b"static ELF fixture"))
        entries = parse_newc(gzip.decompress(payload))
        self.assertEqual(b"static ELF fixture", entries["bin/busybox"][1])
        self.assertIn(b"/usr/bin/python3 /linex-desktop-init.py", entries["init"][1])
        self.assertNotIn(b"mkfs", entries["init"][1])
        self.assertNotIn("lib", entries)


if __name__ == "__main__":
    unittest.main()

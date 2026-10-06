import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("test_boot", Path(__file__).with_name("test-boot.py"))
harness = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = harness
spec.loader.exec_module(harness)


class BootHarnessTest(unittest.TestCase):
    def test_missing_child_evidence_for_live_thread_cannot_report_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "123/task/123").mkdir(parents=True)
            with patch.object(harness, "Path", return_value=root), self.assertRaises(RuntimeError):
                harness.host_process_sample(123)

    def test_host_child_samples_are_bounded_and_validate_pid_text(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            thread = root / "123/task/123"
            thread.mkdir(parents=True)
            children = thread / "children"
            with patch.object(harness, "Path", return_value=root):
                children.write_text("456 789")
                self.assertEqual(harness.host_process_sample(123), {
                    "threadCount": 1, "hostChildPids": [456, 789],
                })
                for invalid in ("x" * 4097, "-1", "0", "2147483648", "malformed"):
                    children.write_text(invalid)
                    with self.subTest(invalid=invalid[:20]), self.assertRaises(RuntimeError):
                        harness.host_process_sample(123)

    def test_thread_that_exits_during_child_read_is_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            thread = root / "123/task/123"
            thread.mkdir(parents=True)
            stable = root / "123/task/124"
            stable.mkdir()
            (stable / "children").write_text("")
            original_open = Path.open
            def exited_thread(path, *args, **kwargs):
                if path == thread / "children":
                    thread.rmdir()
                    raise FileNotFoundError("Thread exited")
                return original_open(path, *args, **kwargs)
            with patch.object(harness, "Path", return_value=root), patch.object(Path, "open", exited_thread):
                self.assertEqual(harness.host_process_sample(123)["hostChildPids"], [])

    def test_too_many_threads_are_rejected_before_child_reads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tasks = root / "123/task"
            tasks.mkdir(parents=True)
            for pid in range(513):
                (tasks / str(pid)).mkdir()
            with patch.object(harness, "Path", return_value=root), self.assertRaisesRegex(RuntimeError, "limit"):
                harness.host_process_sample(123)

    def test_records_distinct_guest_pid_proof_and_clear_reply(self):
        pids = tuple(range(2, 66))
        serial = ("LINEX_VM_GUEST_CHILDREN count=0\nLINEX_VM_GUEST_PIDS "
                  + " ".join(str(pid) for pid in pids)
                  + "\nLINEX_VM_GUEST_CHILDREN count=64\nLINEX_VM_BOOT_OK\n"
                  + "LINEX_VM_GUEST_CHILDREN count=0\n")
        capture = harness.SerialCapture(io.BytesIO(serial.encode()))
        capture.thread.join(timeout=2)
        self.assertEqual(capture.verified_guest_pids(), pids)
        self.assertEqual(capture.snapshot()[:3], (True, 0, 3))
        duplicate = harness.SerialCapture(io.BytesIO(("LINEX_VM_GUEST_PIDS " + " ".join(["2"] * 64) + "\n").encode()))
        duplicate.thread.join(timeout=2)
        with self.assertRaises(ValueError):
            duplicate.verified_guest_pids()

    def test_rejects_modified_fixture_before_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "kernel").write_bytes(b"original")
            (root / "boot-proof.cpio.gz").write_bytes(b"init")
            manifest = {"schema": 1, "kernel": {"file": "kernel", "sha256": hashlib.sha256(b"original").hexdigest()},
                        "initramfs": {"file": "boot-proof.cpio.gz", "sha256": hashlib.sha256(b"init").hexdigest()}}
            (root / "manifest.json").write_text(json.dumps(manifest))
            self.assertEqual(harness.verify_fixture(root)[0], root / "kernel")
            (root / "kernel").write_bytes(b"modified")
            with self.assertRaises(ValueError):
                harness.verify_fixture(root)

    def test_rejects_manifest_path_substitution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.json").write_text(json.dumps({"schema": 1, "kernel": {"file": "../outside"},
                                                          "initramfs": {"file": "boot-proof.cpio.gz"}}))
            with self.assertRaises(ValueError):
                harness.verify_fixture(root)

    def test_headless_command_has_no_network_host_shares_or_shell(self):
        args = harness.boot_command("qemu-system-aarch64", Path("kernel"), Path("init"), Path("private/qmp"))
        self.assertEqual(args[0], "qemu-system-aarch64")
        self.assertIn("virt", args)
        self.assertIn("tcg", args)
        self.assertIn("-nodefaults", args)
        self.assertEqual(args[args.index("-nic") + 1], "none")
        self.assertIn(f"unix:{Path('private/qmp')},server=on,wait=off", args)
        self.assertNotIn("-fsdev", args)
        self.assertNotIn("-netdev", args)


if __name__ == "__main__":
    unittest.main()

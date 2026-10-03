import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("test_boot", Path(__file__).with_name("test-boot.py"))
harness = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = harness
spec.loader.exec_module(harness)


class BootHarnessTest(unittest.TestCase):
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

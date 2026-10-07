import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("vm_storage_harness", Path(__file__).with_name("test-storage.py"))
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)


class StorageHarnessTest(unittest.TestCase):
    def test_debugfs_metadata_parser_requires_actual_mode_uid_gid_fields(self):
        self.assertEqual({"mode": 0o600, "uid": 1000, "gid": 1000}, storage.inode_metadata(
            "Inode: 19 Type: regular Mode: 0600 Flags: 0x0\nUser: 1000 Group: 1000 Size: 33\n"))
        for invalid in ("File not found by ext2_lookup", "Mode: 0755",
                        "Mode: 0755 Mode: 0700 User: 0 Group: 0"):
            with self.subTest(output=invalid), self.assertRaises(ValueError):
                storage.inode_metadata(invalid)

    def test_storage_boot_uses_typed_raw_disk_without_network_or_host_commands(self):
        command = storage.boot_command("qemu", Path("kernel"), Path("initrd"), Path("disk"), Path("qmp"))
        self.assertIn("virtio-blk-device,drive=linexdisk", command)
        block = json.loads(command[command.index("-blockdev") + 1])
        self.assertEqual("raw", block["driver"])
        self.assertEqual("disk", block["file"]["filename"])
        self.assertIn("none", command)
        self.assertNotIn("-netdev", command)
        self.assertFalse(any("hostfwd" in value or "guestfwd" in value for value in command))

    def test_journal_recovery_flag_is_read_from_exact_features_field(self):
        self.assertTrue(storage.needs_recovery("Filesystem features: ext_attr extent needs_recovery\n"))
        self.assertFalse(storage.needs_recovery("Filesystem features: ext_attr extent\nOther: needs_recovery\n"))
        with self.assertRaises(ValueError):
            storage.needs_recovery("missing field")

    def test_actual_pinned_assets_reject_corruption_and_wrong_lengths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {"schema": 1, "architecture": "aarch64"}
            for field, name in (("kernel", "kernel"), ("initramfs", "storage-proof.cpio.gz"), ("disk", "storage-proof.raw")):
                value = b"valid"
                (root / name).write_bytes(value)
                manifest[field] = {"file": name, "sha256": hashlib.sha256(value).hexdigest(), "bytes": len(value)}
            manifest["disk"].update(format="raw", filesystem="ext4")
            (root / "manifest.json").write_text(json.dumps(manifest))
            storage.verify_fixture(root)
            (root / "storage-proof.raw").write_bytes(b"wrong")
            with self.assertRaises(ValueError):
                storage.verify_fixture(root)
            (root / "storage-proof.raw").write_bytes(b"valid")
            manifest["kernel"]["bytes"] += 1
            (root / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                storage.verify_fixture(root)


if __name__ == "__main__":
    unittest.main()

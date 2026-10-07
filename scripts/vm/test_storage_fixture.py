"""Storage fixture archive boundaries and genuine direct-root boot contract."""
import gzip
import importlib.util
import os
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
import unittest

from test_fixture import archive, parse_newc

spec = importlib.util.spec_from_file_location("vm_storage_fixture", Path(__file__).with_name("build-storage-fixture.py"))
storage = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = storage
spec.loader.exec_module(storage)


class StorageFixtureTest(unittest.TestCase):
    def test_storage_drivers_must_all_be_built_in(self):
        config = "".join(f"{name}=y\n" for name in storage.STORAGE_FEATURES).encode()
        self.assertEqual(set(storage.STORAGE_FEATURES), set(storage.verify_storage_config(config)))
        for name in storage.STORAGE_FEATURES:
            with self.subTest(name=name), self.assertRaises(ValueError):
                storage.verify_storage_config(config.replace(f"{name}=y".encode(), f"{name}=m".encode()))

    @unittest.skipIf(os.name == "nt", "Actual guest symlink staging requires the Linux image builder")
    def test_private_staging_preserves_guest_symlinks_without_host_traversal(self):
        root = storage.fixture.read_rootfs(archive([
            ("bin/busybox", tarfile.REGTYPE, b"binary"),
            ("bin/sh", tarfile.SYMTYPE, "/bin/busybox"),
        ]))
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "root"
            storage.write_staging(root, destination)
            self.assertTrue((destination / "bin/sh").is_symlink())
            self.assertEqual(b"binary", (destination / "bin/busybox").read_bytes())
            with self.assertRaises(ValueError):
                storage.write_staging(root, destination)
    def test_private_staging_rejects_symlink_ancestors_before_creating_any_files(self):
        malicious = {
            "bin": storage.fixture.Entry(stat.S_IFDIR | 0o755),
            "bin/sh": storage.fixture.Entry(stat.S_IFLNK | 0o777, b"/outside"),
            "bin/sh/escape": storage.fixture.Entry(stat.S_IFREG | 0o644, b"bad"),
        }
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            storage.write_staging(malicious, Path(directory) / "root")

    def test_switchroot_initramfs_is_small_deterministic_and_does_not_change_other_fixtures(self):
        root = storage.fixture.read_rootfs(archive([
            ("bin/busybox", tarfile.REGTYPE, b"binary"),
            ("lib/ld-musl-aarch64.so.1", tarfile.REGTYPE, b"loader"),
            ("lib/libc.musl-aarch64.so.1", tarfile.SYMTYPE, "ld-musl-aarch64.so.1"),
            ("unneeded", tarfile.REGTYPE, b"omitted"),
        ]))
        serial_before = storage.fixture.make_initramfs(root)
        encoded = storage.make_storage_initramfs(root)
        self.assertEqual(encoded, storage.make_storage_initramfs(root))
        self.assertEqual(serial_before, storage.fixture.make_initramfs(root))
        entries = parse_newc(gzip.decompress(encoded))
        self.assertNotIn("unneeded", entries)
        self.assertIn(b"switch_root", entries["init"][1])
        self.assertIn(b"/dev/vda", entries["init"][1])
        self.assertNotIn(b"mkfs", entries["init"][1])
        self.assertIn(b"LINEX_VM_STORAGE_SWITCH_ROOT", entries["init"][1])

    def test_guest_nonce_write_is_nonroot_and_control_is_bounded(self):
        self.assertIn(b"su -s /bin/sh -c /linex-storage-write linex", storage.ROOT_INIT)
        self.assertIn(b"1000", storage.ROOT_INIT)
        self.assertIn(b"-ne 32", storage.ROOT_INIT)
        self.assertIn(b"*[!a-f0-9]*", storage.ROOT_INIT)
        self.assertIn(b"sync", storage.NONCE_WRITE)
        self.assertNotIn(b"eval", storage.ROOT_INIT)
        self.assertNotIn(b"mkfs", storage.ROOT_INIT)

    @unittest.skipIf(os.name == "nt", "POSIX directory metadata test requires Linux")
    def test_private_directory_modes_survive_staging_population(self):
        entries = {
            "root": storage.fixture.Entry(stat.S_IFDIR | 0o700),
            "root/file": storage.fixture.Entry(stat.S_IFREG | 0o600, b"private"),
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "staging"
            storage.write_staging(entries, root)
            self.assertEqual(0o700, stat.S_IMODE((root / "root").stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE((root / "root/file").stat().st_mode))

    def test_root_ownership_cannot_be_promised_without_linux_root(self):
        from unittest import mock
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(storage.os, "name", "nt"):
            with self.assertRaises(RuntimeError):
                storage.write_staging({}, Path(directory) / "root", require_root_ownership=True)


if __name__ == "__main__":
    unittest.main()

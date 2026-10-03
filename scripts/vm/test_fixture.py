"""Archive safety and reproducibility regressions for the genuine VM fixture."""
import gzip
import importlib.util
import io
from pathlib import Path
import stat
import sys
import tarfile
import unittest

spec = importlib.util.spec_from_file_location("build_fixture", Path(__file__).with_name("build-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = fixture
spec.loader.exec_module(fixture)


def archive(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w:gz") as output:
        for name, kind, value in entries:
            entry = tarfile.TarInfo(name)
            entry.mode = 0o755
            entry.type = kind
            if kind == tarfile.REGTYPE:
                entry.size = len(value)
                output.addfile(entry, io.BytesIO(value))
            else:
                entry.linkname = value
                output.addfile(entry)
    return stream.getvalue()


def parse_newc(data):
    entries = {}
    position = 0
    while True:
        header = data[position:position + 110]
        assert header[:6] == b"070701"
        fields = [int(header[6 + index * 8:14 + index * 8], 16) for index in range(13)]
        size, name_size = fields[6], fields[11]
        position += 110
        name = data[position:position + name_size - 1].decode()
        position = (position + name_size + 3) & ~3
        payload = data[position:position + size]
        position = (position + size + 3) & ~3
        if name == "TRAILER!!!":
            return entries
        entries[name] = (fields, payload)


class FixtureTest(unittest.TestCase):
    def test_rejects_traversal_absolute_windows_and_duplicate_names(self):
        for name in ("../escape", "/absolute", "C:/host", "bad\\host", "safe/../escape"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                fixture.read_rootfs(archive([(name, tarfile.REGTYPE, b"payload")]))
        with self.assertRaises(ValueError):
            fixture.read_rootfs(archive([("a", tarfile.REGTYPE, b"one"), ("./a", tarfile.REGTYPE, b"two")]))

    def test_guest_links_are_not_extracted_and_hardlinks_resolve(self):
        root = fixture.read_rootfs(archive([
            ("bin", tarfile.DIRTYPE, ""),
            ("bin/busybox", tarfile.REGTYPE, b"binary"),
            ("bin/copy", tarfile.LNKTYPE, "bin/busybox"),
            ("bin/sh", tarfile.SYMTYPE, "/bin/busybox"),
        ]))
        self.assertEqual(root["bin/copy"].data, b"binary")
        self.assertEqual(root["bin/sh"].data, b"/bin/busybox")
        self.assertEqual(stat.S_IFMT(root["bin/sh"].mode), stat.S_IFLNK)
        with self.assertRaises(ValueError):
            fixture.read_rootfs(archive([("bin/copy", tarfile.LNKTYPE, "../outside")]))

    def test_rejects_symlink_ancestors_and_hardlink_cycles(self):
        with self.assertRaises(ValueError):
            fixture.read_rootfs(archive([
                ("a", tarfile.SYMTYPE, "/outside"), ("a/payload", tarfile.REGTYPE, b"payload")]))
        with self.assertRaises(ValueError):
            fixture.read_rootfs(archive([("a", tarfile.LNKTYPE, "b"), ("b", tarfile.LNKTYPE, "a")]))

    def test_bound_entries_and_uncompressed_bytes(self):
        data = archive([("a", tarfile.REGTYPE, b"four")])
        with self.assertRaises(ValueError):
            fixture.read_rootfs(data, max_bytes=3)
        with self.assertRaises(ValueError):
            fixture.read_rootfs(data, max_entries=0)
        # Hardlinks remain guest links, but writing independent newc payloads
        # must not multiply a small tar into an unbounded generated archive.
        links = archive([("a", tarfile.REGTYPE, b"four"), ("b", tarfile.LNKTYPE, "a")])
        with self.assertRaises(ValueError):
            fixture.read_rootfs(links, max_bytes=7)

    def test_newc_is_deterministic_preserves_modes_and_has_real_init(self):
        root = fixture.read_rootfs(archive([("bin/busybox", tarfile.REGTYPE, b"binary")]))
        first = fixture.make_initramfs(root)
        second = fixture.make_initramfs(root)
        self.assertEqual(first, second)
        self.assertEqual(first[9], 255)  # Stable gzip OS byte across Python/host versions.
        entries = parse_newc(gzip.decompress(first))
        self.assertEqual(entries["bin/busybox"][1], b"binary")
        self.assertEqual(entries["init"][0][1] & 0o777, 0o755)
        self.assertIn(b"LINEX_VM_BOOT_OK", entries["init"][1])
        self.assertIn(b"LINEX_VM_GUEST_CHILDREN", entries["init"][1])
        self.assertIn(b"sleep 86400 &", entries["init"][1])
        self.assertEqual(entries["dev/console"][0][9:11], [5, 1])
        self.assertTrue(all(item[0][5] == 0 for item in entries.values()))

    def test_digest_check_fails_closed(self):
        with self.assertRaises(ValueError):
            fixture.verify_bytes(b"corrupt", "0" * 64)


if __name__ == "__main__":
    unittest.main()

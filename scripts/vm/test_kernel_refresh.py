import importlib.util
import json
import lzma
from pathlib import Path
import tempfile
import unittest
import hashlib

spec = importlib.util.spec_from_file_location("refresh", Path(__file__).with_name("refresh-desktop-kernel.py"))
refresh = importlib.util.module_from_spec(spec); spec.loader.exec_module(refresh)


class KernelRefreshTest(unittest.TestCase):
    def test_new_kernel_never_changes_the_accepted_guest_disk_or_approves_a_release(self):
        base = {"disk": {"sha256": "a" * 64}, "initramfs": {"sha256": "b" * 64},
            "download": {"sha256": "c" * 64}, "releaseReady": True, "runtimeProofPassed": True}
        original = json.dumps(base)
        value = refresh.refreshed_manifest(base, {"CONFIG_EXT4_FS": "y"})
        self.assertEqual(json.dumps(base), original)
        for field in ("disk", "initramfs", "download"):
            self.assertEqual(value[field], base[field])
        self.assertEqual(value["kernel"]["sha256"], refresh.KERNEL_SHA)
        self.assertFalse(value["releaseReady"])
        self.assertFalse(value["runtimeProofPassed"])
        self.assertTrue(value["kernelSecurityAuditPending"])

    def test_sparse_expansion_hashes_all_zero_and_nonzero_bytes_and_rejects_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = b"\0" * 1048576 + b"data" + b"\0" * 4000
            compressed = root / "disk.xz"
            compressed.write_bytes(lzma.compress(raw))
            expected = hashlib.sha256(raw).hexdigest()
            refresh.inflate_disk(compressed, root / "raw", len(raw), expected)
            self.assertEqual((root / "raw").read_bytes(), raw)
            for size, sha in ((len(raw) - 1, expected), (len(raw) + 1, expected), (len(raw), "0" * 64)):
                with self.subTest(size=size, sha=sha), self.assertRaises(ValueError):
                    refresh.inflate_disk(compressed, root / (str(size) + sha[:4]), size, sha)
            damaged = bytearray(compressed.read_bytes()); damaged[-6] ^= 1
            compressed.write_bytes(damaged)
            with self.assertRaises(lzma.LZMAError):
                refresh.inflate_disk(compressed, root / "bad", len(raw), expected)

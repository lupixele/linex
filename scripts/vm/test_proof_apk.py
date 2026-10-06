"""Packaging guards reject asset transformations and changed native subjects."""
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("verify_proof_apk", Path(__file__).with_name("verify-proof-apk.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class ProofApkTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / "build/test-scratch"
        scratch.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="proof-apk-", dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.fixture = self.root / "fixture"
        self.fixture.mkdir()
        self.native = self.root / "native/x86_64"
        self.native.mkdir(parents=True)
        kernel = b"pinned-kernel-fixture"
        initramfs = gzip.compress(b"newc-proof-userspace")
        library = b"ELF-file-provenance-only-unit-fixture"
        manifest = {}
        for item, name, data in (("kernel", "kernel", kernel), ("initramfs", "boot-proof.cpio.gz", initramfs)):
            (self.fixture / name).write_bytes(data)
            manifest[item] = {"file": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        (self.fixture / "manifest.json").write_text(json.dumps(manifest))
        (self.native / "liblinex_qemu_aarch64.so").write_bytes(library)
        (self.native / "elf-evidence.json").write_text(json.dumps({
            "abi": "x86_64", "sha256": hashlib.sha256(library).hexdigest(),
        }))
        self.entries = {
            "assets/manifest.json": (self.fixture / "manifest.json").read_bytes(),
            "assets/kernel": kernel, "assets/boot-proof.initramfs": initramfs,
            "lib/x86_64/liblinex_qemu_aarch64.so": library,
        }

    def verify(self):
        apk = self.root / "proof.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name, data in self.entries.items():
                archive.writestr(name, data)
        return VERIFIER.verify(apk, self.fixture, self.native.parent, ["x86_64"])

    def test_verified_bytes_preserve_pending_boot_status(self):
        self.assertEqual("pending", self.verify()["kernel_boot"])

    def test_android_gzip_suffix_transformation_is_rejected(self):
        self.entries["assets/boot-proof.cpio"] = gzip.decompress(self.entries.pop("assets/boot-proof.initramfs"))
        with self.assertRaisesRegex(ValueError, "boot-proof.initramfs"):
            self.verify()

    def test_decompressed_bytes_under_alias_are_rejected(self):
        self.entries["assets/boot-proof.initramfs"] = gzip.decompress(self.entries["assets/boot-proof.initramfs"])
        with self.assertRaisesRegex(ValueError, "bytes/size"):
            self.verify()

    def test_same_size_corrupt_kernel_is_rejected(self):
        self.entries["assets/kernel"] = b"x" * len(self.entries["assets/kernel"])
        with self.assertRaisesRegex(ValueError, "fixture hash"):
            self.verify()

    def test_changed_packaged_jni_is_rejected(self):
        name = "lib/x86_64/liblinex_qemu_aarch64.so"
        self.entries[name] = b"x" * len(self.entries[name])
        with self.assertRaisesRegex(ValueError, "JNI hash"):
            self.verify()

    def test_changed_native_artifact_is_rejected(self):
        (self.native / "liblinex_qemu_aarch64.so").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "verified ELF"):
            self.verify()

    def test_missing_jni_is_rejected(self):
        self.entries.pop("lib/x86_64/liblinex_qemu_aarch64.so")
        with self.assertRaisesRegex(ValueError, "lib/x86_64"):
            self.verify()

    def test_changed_packaged_manifest_is_rejected(self):
        self.entries["assets/manifest.json"] = b" " * len(self.entries["assets/manifest.json"])
        with self.assertRaisesRegex(ValueError, "manifest changed"):
            self.verify()


if __name__ == "__main__":
    unittest.main()

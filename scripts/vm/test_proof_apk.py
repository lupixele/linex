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
        self.network_fixture = self.root / "network"
        self.network_fixture.mkdir()
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
        network_initramfs = gzip.compress(b"network-userspace-with-dhcp")
        network_manifest = {
            "kernel": manifest["kernel"],
            "initramfs": {"file": "network-proof.cpio.gz", "bytes": len(network_initramfs),
                          "sha256": hashlib.sha256(network_initramfs).hexdigest()},
        }
        (self.network_fixture / "kernel").write_bytes(kernel)
        (self.network_fixture / "network-proof.cpio.gz").write_bytes(network_initramfs)
        network_bytes = json.dumps(network_manifest).encode()
        (self.network_fixture / "manifest.json").write_bytes(network_bytes)
        self.entries.update({
            "assets/network/manifest.json": network_bytes,
            "assets/network/kernel": kernel,
            "assets/network/network-proof.initramfs": network_initramfs,
        })
        self.https_fixture = self.root / "https"
        self.https_fixture.mkdir()
        tls_assets = {"ca.pem": b"test-ca", "server.pem": b"test-leaf", "server-key.pk8": b"public-test-key"}
        https_manifest = {"kernel": manifest["kernel"], "initramfs": network_manifest["initramfs"],
                          "testTlsAssets": {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                                            for name, data in tls_assets.items()}}
        (self.https_fixture / "kernel").write_bytes(kernel)
        (self.https_fixture / "https-proof.cpio.gz").write_bytes(network_initramfs)
        https_bytes = json.dumps(https_manifest).encode()
        (self.https_fixture / "manifest.json").write_bytes(https_bytes)
        self.entries.update({"assets/https/manifest.json": https_bytes, "assets/https/kernel": kernel,
                             "assets/https/https-proof.initramfs": network_initramfs})
        for name, data in tls_assets.items():
            (self.https_fixture / name).write_bytes(data)
            self.entries["assets/https/" + name] = data

    def verify(self, https=False):
        apk = self.root / "proof.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name, data in self.entries.items():
                archive.writestr(name, data)
        return VERIFIER.verify(apk, self.fixture, self.native.parent, ["x86_64"], self.network_fixture,
                               self.https_fixture if https else None)

    def test_https_assets_include_verified_ca_leaf_and_key(self):
        evidence = self.verify(https=True)["assets"]
        for name in ("https-proof.initramfs", "ca.pem", "server.pem", "server-key.pk8"):
            self.assertIn("assets/https/" + name, evidence)

    def test_missing_https_key_is_rejected(self):
        self.entries.pop("assets/https/server-key.pk8")
        with self.assertRaisesRegex(ValueError, "server-key.pk8"):
            self.verify(https=True)

    def test_corrupted_packaged_https_ca_is_rejected(self):
        name = "assets/https/ca.pem"
        self.entries[name] = b"x" * len(self.entries[name])
        with self.assertRaisesRegex(ValueError, "fixture hash"):
            self.verify(https=True)

    def test_verified_network_bytes_are_included_in_evidence(self):
        self.assertIn("assets/network/network-proof.initramfs", self.verify()["assets"])

    def test_missing_network_kernel_is_rejected(self):
        self.entries.pop("assets/network/kernel")
        with self.assertRaisesRegex(ValueError, "assets/network/kernel"):
            self.verify()

    def test_changed_network_manifest_is_rejected(self):
        name = "assets/network/manifest.json"
        self.entries[name] = b" " * len(self.entries[name])
        with self.assertRaisesRegex(ValueError, "manifest changed"):
            self.verify()

    def test_network_gzip_asset_transformation_is_rejected(self):
        source = self.entries.pop("assets/network/network-proof.initramfs")
        self.entries["assets/network/network-proof.cpio"] = gzip.decompress(source)
        with self.assertRaisesRegex(ValueError, "network-proof.initramfs"):
            self.verify()

    def test_same_size_network_corruption_is_rejected(self):
        name = "assets/network/network-proof.initramfs"
        self.entries[name] = b"x" * len(self.entries[name])
        with self.assertRaisesRegex(ValueError, "fixture hash"):
            self.verify()

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

import gzip
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tarfile
import unittest

spec = importlib.util.spec_from_file_location("https_fixture", Path(__file__).with_name("build-https-fixture.py"))
https = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = https
spec.loader.exec_module(https)


def tar_bytes(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        for name, data in entries.items():
            member = tarfile.TarInfo(name)
            member.size = len(data)
            member.mode = 0o644
            archive.addfile(member, io.BytesIO(data))
    return stream.getvalue()


def apk(data_entries):
    return b"".join(gzip.compress(tar_bytes(entries), mtime=0) for entries in (
        {".SIGN.RSA.fake": b"signature"}, {".PKGINFO": b"pkgname = test\npkgver = 1\n"}, data_entries))


class HttpsFixtureTests(unittest.TestCase):
    def test_signed_apk_members_read_without_extraction(self):
        entries, metadata = https.read_apk(apk({"usr/bin/test": b"ELF payload"}))
        self.assertEqual(entries["usr/bin/test"].data, b"ELF payload")
        self.assertIn(b"pkgname = test", metadata)

    def test_truncated_or_extra_gzip_member_rejected(self):
        valid = apk({"usr/bin/test": b"test"})
        for invalid in (valid[:-1], valid + gzip.compress(b"extra"), valid[:20]):
            with self.assertRaises(ValueError):
                https.read_apk(invalid)

    def test_archive_path_traversal_rejected(self):
        with self.assertRaises(ValueError):
            https.read_apk(apk({"../outside": b"bad"}))

    def test_exact_dependency_closure_has_hashes_and_licenses(self):
        pins = json.loads(https.PACKAGES_FILE.read_text())
        self.assertEqual(len(https.validate_pins(pins)), 20)
        pins["packages"] = [p for p in pins["packages"] if p["name"] != "libssl3"]
        with self.assertRaisesRegex(ValueError, "closure"):
            https.validate_pins(pins)

    def test_package_url_cannot_escape_pinned_repository(self):
        pins = json.loads(https.PACKAGES_FILE.read_text())
        pins["packages"][0]["url"] = "https://example.com/untrusted.apk"
        with self.assertRaisesRegex(ValueError, "origin"):
            https.validate_pins(pins)

    def test_exact_version_dependency_cannot_use_another_version(self):
        pins = json.loads(https.PACKAGES_FILE.read_text())
        openssl = next(p for p in pins["packages"] if p["name"] == "openssl")
        openssl["dependencies"][0] = "libssl3=0.0.0-r0"
        with self.assertRaisesRegex(ValueError, "closure"):
            https.validate_pins(pins)

    def test_fixture_is_deterministic_and_keeps_test_ca_out_of_system_store(self):
        root = {"etc": https.fixture.Entry(stat.S_IFDIR | 0o755)}
        first = https.make_https_initramfs(root, b"test ca")
        self.assertEqual(first, https.make_https_initramfs(root, b"test ca"))
        payload = gzip.decompress(first)
        self.assertIn(b"linex-test-ca.pem\x00", payload)
        self.assertNotIn(b"etc/ssl/certs/ca-certificates.crt\x00", payload)


if __name__ == "__main__":
    unittest.main()

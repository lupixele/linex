import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import ssl
import tempfile
import unittest
from unittest import mock
import urllib.error

spec = importlib.util.spec_from_file_location("desktop_sources", Path(__file__).with_name("archive-desktop-sources.py"))
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


def candidate():
    return {"schema": 1, "imageId": "debian-trixie-desktop", "architecture": "aarch64", "stage": "authenticated-provisioning",
            "packages": [{"source": "sample", "sourceVersion": "1.0-1"}],
            "sources": [{"Package": "sample", "Version": "1.0-1", "Directory": "pool/main/s/sample",
                         "Checksums-Sha256": "\n" + "a" * 64 + " 23 sample_1.0-1.dsc\n" + "b" * 64 + " 99 sample_1.0.orig.tar.xz"}]}


class DesktopSourcesTest(unittest.TestCase):
    def test_corresponding_source_plan_requires_exact_complete_binary_closure(self):
        plan = archive.source_plan(candidate())
        self.assertEqual("sample", plan[0]["name"])
        self.assertEqual(2, len(plan[0]["files"]))
        self.assertEqual("https://deb.debian.org/debian/pool/main/s/sample/sample_1.0-1.dsc", plan[0]["files"][0]["url"])
        for change in (lambda lock: lock["sources"].append(copy.deepcopy(lock["sources"][0])),
                       lambda lock: lock["packages"].append({"source": "missing", "sourceVersion": "2"}),
                       lambda lock: lock["sources"][0].update(Version="2")):
            lock = candidate()
            change(lock)
            with self.assertRaises(ValueError):
                archive.source_plan(lock)

    def test_source_paths_hashes_sizes_and_control_file_are_bounded(self):
        for field, value in (("Directory", "pool/main/../etc"), ("Directory", "https://evil.invalid/sources"),
                             ("Checksums-Sha256", "a 1 sample.dsc\nb 1 sample.tar.xz"),
                             ("Checksums-Sha256", "a" * 64 + " 3 ../sample.dsc\n" + "b" * 64 + " 3 sample.tar.xz"),
                             ("Checksums-Sha256", "a" * 64 + " 99999999999 sample.dsc\n" + "b" * 64 + " 3 sample.tar.xz")):
            lock = candidate()
            lock["sources"][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                archive.source_plan(lock)
        security = candidate()
        security["sources"][0]["Directory"] = "pool/updates/main/s/sample"
        security["sources"][0]["Checksums-Sha256"] += "\n" + "c" * 64 + " 833 sample_1.0.orig.tar.xz.asc"
        self.assertTrue(archive.source_plan(security)[0]["files"][0]["url"].startswith("https://security.debian.org/debian-security/"))
        self.assertEqual(3, len(archive.source_plan(security)[0]["files"]))

    def test_lock_requires_reviewed_hash_before_any_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "lock.json"
            payload = json.dumps(candidate()).encode()
            path.write_bytes(payload)
            self.assertEqual(candidate(), archive.load_pinned_lock(path, hashlib.sha256(payload).hexdigest()))
            with self.assertRaises(ValueError):
                archive.load_pinned_lock(path, "0" * 64)

    def test_redirect_rejects_plaintext_credentials_or_nonofficial_hosts(self):
        for address in ("http://deb.debian.org/debian/file", "https://evil.invalid/source", "https://user@deb.debian.org/file",
                        "https://deb.debian.org/a/../file", "https://deb.debian.org/a/%2e%2e/file", "https://deb.debian.org:443/file"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                archive.checked_url(address)

    def test_stream_exact_bytes_or_failure_leaves_no_published_partial(self):
        class Response(io.BytesIO):
            status = 200
            headers = {}
            def geturl(self):
                return "https://deb.debian.org/debian/pool/main/s/sample/sample.tar.xz"
        payload = b"corresponding source bytes"
        item = {"file": "source/sample.tar.xz", "url": Response().geturl(), "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest()}
        for contents in (payload, payload[:-1], payload + b"x", b"wrong" * 8):
            with self.subTest(contents=contents), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                opener = mock.Mock()
                opener.open.return_value = Response(contents)
                if contents == payload:
                    archive.download_file(item, root, opener)
                    self.assertEqual(payload, (root / item["file"]).read_bytes())
                    with self.assertRaises(ValueError):
                        archive.download_file(item, root, opener)
                else:
                    with self.assertRaises(ValueError):
                        archive.download_file(item, root, opener)
                    self.assertEqual([], list((root / "source").iterdir()))

    def test_retry_never_retries_integrity_or_tls_failures(self):
        for error in (ValueError("wrong hash"), urllib.error.URLError(ssl.SSLCertVerificationError("certificate")),
                      urllib.error.HTTPError("https://deb.debian.org/f", 404, "missing", {}, None)):
            self.assertFalse(archive.transient(error))
            if isinstance(error, urllib.error.HTTPError):
                error.close()
        unavailable = urllib.error.HTTPError("https://deb.debian.org/f", 503, "temporarily unavailable", {}, None)
        with mock.patch.object(archive, "download_file", side_effect=[unavailable, None]) as download, mock.patch.object(archive.time, "sleep"):
            archive.download_with_retry({}, Path("unused"), None)
            self.assertEqual(2, download.call_count)

    def test_output_is_fresh_scoped_to_project_dist(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(archive.Path, "cwd", return_value=Path(directory)):
            for path in (Path(directory) / "outside", Path(directory) / "dist" / "a" / ".." / ".." / "outside"):
                with self.assertRaises(ValueError):
                    archive.fresh_output(path)
            target = Path(directory) / "dist/sources"
            archive.fresh_output(target)
            with self.assertRaises(FileExistsError):
                archive.fresh_output(target)


if __name__ == "__main__":
    unittest.main()

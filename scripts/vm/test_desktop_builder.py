import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("desktop_builder", Path(__file__).with_name("build-desktop-image.py"))
desktop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(desktop)


class DesktopBuilderTest(unittest.TestCase):
    def test_exact_download_retry_is_only_for_transient_transport_failures(self):
        transport = "E: Failed to fetch https://deb.debian.org/debian/pool/libpango.deb OpenSSL system call error: Broken pipe"
        self.assertTrue(desktop.transient_download_failure(transport))
        for security in ("Certificate verification failed", "Hash Sum mismatch", "404 Not Found"):
            self.assertFalse(desktop.transient_download_failure(transport + "\n" + security))
        with tempfile.TemporaryDirectory() as directory:
            builder = desktop.Builder(Path(directory))
            builder.last_log = builder.evidence / "failure.log"
            builder.last_log.write_text(transport)
            with mock.patch.object(builder, "run", side_effect=[RuntimeError("transport"), "success"]) as run, \
                    mock.patch.object(desktop.time, "sleep"):
                self.assertEqual("success", builder.download_exact(["apt-get", "install", "pkg=1"]))
                self.assertEqual(2, run.call_count)
            with mock.patch.object(builder, "run", side_effect=RuntimeError("transport")) as run, \
                    mock.patch.object(desktop.time, "sleep"):
                with self.assertRaises(RuntimeError):
                    builder.download_exact(["apt-get", "install", "pkg=1"])
                self.assertEqual(3, run.call_count)

    def test_actual_apt_three_and_four_field_uri_rows_with_encoded_epochs(self):
        security = "'https://security.debian.org/debian-security/pool/updates/main/f/firefox-esr/firefox-esr_153.4.0esr-1%7edeb13u1_arm64.deb' firefox-esr_153.4.0esr-1~deb13u1_arm64.deb 71874648 "
        main = "'https://deb.debian.org/debian/pool/main/u/util-linux/bsdutils_2.41.5-0%2bdeb13u1_arm64.deb' bsdutils_1%3a2.41.5-0+deb13u1_arm64.deb 109000 MD5Sum:160cd91cacfb6ac308a9d335805e21c1"
        result = desktop.parse_download_plan(security + "\n" + main)
        self.assertEqual(71874648, result["firefox-esr_153.4.0esr-1~deb13u1_arm64.deb"]["bytes"])
        self.assertIn("bsdutils_1:2.41.5-0+deb13u1_arm64.deb", result)
        for invalid in (security.replace("https:", "http:"), security.replace("security.debian.org", "evil.invalid"),
                        security.replace("71874648", "99999999999"), security.replace("firefox-esr_153", "../firefox-esr_153", 2),
                        main.replace("MD5Sum:", "Untrusted:"), main + " extra", security + "\n" + security):
            with self.subTest(line=invalid), self.assertRaises(ValueError):
                desktop.parse_download_plan(invalid)

    def test_debian_metadata_rejects_duplicate_and_orphan_fields(self):
        self.assertEqual([{"Package": "example", "Description": "title\nbody"}],
                         desktop.records("Package: example\nDescription: title\n body\n"))
        for invalid in ("Package: a\nPackage: b", " continuation", "invalid"):
            with self.subTest(record=invalid), self.assertRaises(ValueError):
                desktop.records(invalid)

    def test_solved_package_arguments_cannot_include_flags_or_commands(self):
        self.assertEqual("firefox-esr=153.4.0esr-1~deb13u1",
                         desktop.checked_package("firefox-esr", "153.4.0esr-1~deb13u1"))
        for name, version in (("--allow-unauthenticated", "1"), ("pkg;sh", "1"), ("pkg", "1\n--flag")):
            with self.subTest(name=name), self.assertRaises(ValueError):
                desktop.checked_package(name, version)

    def test_requires_native_linux_and_never_claims_windows_build(self):
        with mock.patch.object(desktop.platform, "system", return_value="Windows"):
            with self.assertRaises(RuntimeError):
                desktop.require_native_builder()

    def test_refuses_existing_output_and_outside_dist(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(desktop.Path, "cwd", return_value=Path(directory)):
            with self.assertRaises(ValueError):
                desktop.fresh_output(Path(directory) / "outside")
            with self.assertRaises(ValueError):
                desktop.fresh_output(Path(directory) / "dist" / "x" / ".." / ".." / "outside")
            self.assertFalse((Path(directory) / "outside").exists())
            destination = Path(directory) / "dist" / "desktop"
            desktop.fresh_output(destination)
            with self.assertRaises(FileExistsError):
                desktop.fresh_output(destination)


if __name__ == "__main__":
    unittest.main()

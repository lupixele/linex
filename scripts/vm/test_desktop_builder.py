import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("desktop_builder", Path(__file__).with_name("build-desktop-image.py"))
desktop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(desktop)


class DesktopBuilderTest(unittest.TestCase):
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

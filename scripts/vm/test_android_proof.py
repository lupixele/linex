"""Prove CI preserves the real test failure while collecting device evidence."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts/vm/run-android-proof.sh"


class AndroidProofRunnerTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / "build/test-scratch"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="android-proof-", dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        self.write_script(self.directory / "gradlew", """#!/usr/bin/env bash
printf '%s\\n' "$@" > gradle-arguments.txt
exit "${STUB_GRADLE_STATUS:-0}"
""")
        self.write_script(self.bin / "adb", """#!/usr/bin/env bash
printf '%s\\n' "$*" >> adb-commands.txt
if [[ "$*" == 'shell getprop' && "${STUB_PREFLIGHT_FAIL:-0}" == 1 ]]; then exit 13; fi
if [[ "$*" == logcat* && "${STUB_CAPTURE_FAIL:-0}" == 1 ]]; then exit 7; fi
printf 'bounded device evidence\\n'
""")

    @staticmethod
    def write_script(path, text):
        path.write_text(text, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def run_proof(self, **options):
        environment = os.environ.copy()
        environment.update(options)
        environment["PATH"] = str(self.bin) + os.pathsep + environment["PATH"]
        bash = environment.get("LINEX_TEST_BASH") or shutil.which("bash")
        self.assertIsNotNone(bash, "Bash is required to execute the actual CI runner")
        return subprocess.run(
            [bash, str(RUNNER)], cwd=self.directory, env=environment,
            capture_output=True, text=True, timeout=30,
        )

    def assert_evidence(self, status):
        evidence = self.directory / "dist/vm-android-proof"
        self.assertEqual(str(status), (evidence / "test-exit-status.txt").read_text().strip())
        self.assertTrue((evidence / "processes-after.txt").is_file())
        self.assertTrue((evidence / "logcat.txt").is_file())
        for launch in (0, 1):
            for name in (f"vm-proof-{launch}.json", f"vm-proof-{launch}.log", f"vm-proof-{launch}-exit.json"):
                self.assertTrue((evidence / name).is_file(), name)

    def test_success_runs_both_real_gradle_gates_and_collects_evidence(self):
        result = self.run_proof()
        self.assertEqual(0, result.returncode, result.stderr)
        arguments = (self.directory / "gradle-arguments.txt").read_text().splitlines()
        self.assertEqual([
            ":vm-engine:testDebugUnitTest", ":vm-engine:connectedDebugAndroidTest",
            "-PvmNativeDir=dist/vm-engine", "--no-daemon",
        ], arguments)
        self.assert_evidence(0)

    def test_test_failure_survives_failed_diagnostic_capture(self):
        result = self.run_proof(STUB_GRADLE_STATUS="37", STUB_CAPTURE_FAIL="1")
        self.assertEqual(37, result.returncode, result.stderr)
        self.assert_evidence(37)
        self.assertIn("logcat", (self.directory / "adb-commands.txt").read_text())

    def test_preflight_failure_still_collects_evidence_and_blocks_tests(self):
        result = self.run_proof(STUB_PREFLIGHT_FAIL="1")
        self.assertEqual(13, result.returncode, result.stderr)
        self.assertFalse((self.directory / "gradle-arguments.txt").exists())
        self.assert_evidence(13)


if __name__ == "__main__":
    unittest.main()

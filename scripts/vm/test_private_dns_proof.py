"""Acceptance guards must reject skipped tests, wrong DNS state and mixed proof subjects."""
import copy
import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("private_dns_proof", Path(__file__).with_name("verify-private-dns-proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)
runner_spec = importlib.util.spec_from_file_location("private_dns_runner", Path(__file__).with_name("run-private-dns-proof.py"))
runner = importlib.util.module_from_spec(runner_spec)
runner_spec.loader.exec_module(runner)


def transcript(code=0):
    return f"""INSTRUMENTATION_STATUS: class=com.linex.vm.VmHttpsProofTest
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.linex.vm.VmHttpsProofTest
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: {code}
INSTRUMENTATION_CODE: -1
"""


def report(mode, boot):
    strict = mode == "strict"
    guest = {name: True for name in proof.REQUIRED_GUEST_FLAGS}
    guest.update(sameDnsTransactionId=4660, hostChildrenAfter=0, privateDnsMatrixVerified=False,
                 expectedPrivateDnsMode=mode, expectedPrivateDnsHostname="dns.google" if strict else "unspecified",
                 privateDnsActiveBefore=strict, privateDnsStrictBefore=strict,
                 privateDnsActiveAfter=strict, privateDnsStrictAfter=strict)
    return {"passed": True, "mode": mode, "provider": "dns.google" if strict else None,
            "bootId": boot, "apkSha256": "a" * 64, "proofCheckout": "b" * 40,
            "installedApkSha256": "a" * 64, "instrumentationAdbStatus": 0,
            "settingsApplied": {"mode": "hostname" if strict else "off", "specifier": "dns.google" if strict else "null"},
            "settingsAfter": {"mode": "hostname" if strict else "off", "specifier": "dns.google" if strict else "null"},
            "nativeSource": proof.NATIVE_SOURCE, "nativeRunId": proof.NATIVE_RUN_ID,
            "guest": guest, "exit": {"androidSdk": 33, "pid": 123, "phase": "https_clean_exit",
                                     "binderAliveBeforeCleanup": False, "binderAliveAfterCleanup": False, "cleanupErrors": []}}


class PrivateDnsProofTest(unittest.TestCase):
    def test_actual_runner_rejects_physical_phone_before_any_write_or_install(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "proof.apk"
            apk.write_bytes(b"downloaded proof subject")
            subject = root / "subject.json"
            subject.write_text(json.dumps({"nativeSource": proof.NATIVE_SOURCE, "nativeRunId": proof.NATIVE_RUN_ID,
                "proofCheckout": "b" * 40, "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest()}))
            observed = []
            def device(command, **options):
                observed.append(command)
                responses = {("adb", "get-serialno"): "physical-phone",
                    ("adb", "shell", "getprop", "ro.kernel.qemu"): "0",
                    ("adb", "shell", "getprop", "ro.build.version.sdk"): "33"}
                self.assertIn(tuple(command), responses, "Physical device must not receive a write/install command")
                return subprocess.CompletedProcess(command, 0, responses[tuple(command)], "")
            args = SimpleNamespace(mode="off", provider="dns.google", subject=subject, apk=apk,
                                   evidence=root / "evidence", adb="adb")
            with mock.patch.dict("os.environ", {"GITHUB_ACTIONS": "true", "GITHUB_SHA": "b" * 40}), \
                    mock.patch.object(runner.subprocess, "run", side_effect=device), self.assertRaises(ValueError):
                runner.run(args)
            result = json.loads((args.evidence / "private-dns-run.json").read_text())
            self.assertFalse(result["passed"])
            self.assertIn("disposable API33", result["error"])
            self.assertEqual(3, len(observed))

    def test_downloaded_apk_bytes_and_producer_identity_must_match_subject(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "proof.apk"
            apk.write_bytes(b"actual downloaded bytes")
            subject = {"nativeSource": proof.NATIVE_SOURCE, "nativeRunId": proof.NATIVE_RUN_ID,
                       "proofCheckout": "b" * 40, "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
            proof.verify_subject(subject, apk, "b" * 40)
            apk.write_bytes(b"changed downloaded bytes")
            with self.assertRaises(ValueError):
                proof.verify_subject(subject, apk, "b" * 40)
            apk.write_bytes(b"actual downloaded bytes")
            with self.assertRaises(ValueError):
                proof.verify_subject(subject, apk, "c" * 40)

    def test_instrumentation_requires_one_actual_success_not_zero_or_skips(self):
        proof.verify_instrumentation(transcript())
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                proof.verify_instrumentation(transcript(code))
        for invalid in ("INSTRUMENTATION_CODE: -1\n", transcript().replace("numtests=1", "numtests=0"),
                        transcript().replace("VmHttpsProofTest", "AnotherTest"), transcript() + "INSTRUMENTATION_CODE: 0\n"):
            with self.subTest(output=invalid), self.assertRaises(ValueError):
                proof.verify_instrumentation(invalid)

    def test_accepts_two_fresh_boots_of_identical_verified_subject(self):
        off = report("off", "11111111-1111-4111-8111-111111111111")
        strict = report("strict", "22222222-2222-4222-8222-222222222222")
        self.assertTrue(proof.verify_matrix(off, strict)["privateDnsMatrixVerified"])

    def test_rejects_weak_mode_missing_hostname_and_guest_failures(self):
        original = report("strict", "22222222-2222-4222-8222-222222222222")
        for field, value in (("privateDnsActiveBefore", False), ("privateDnsStrictAfter", False),
                             ("expectedPrivateDnsHostname", "other.example"), ("verifiedPublicHttps", False),
                             ("hostChildrenAfter", 1), ("privateDnsMatrixVerified", True)):
            changed = copy.deepcopy(original)
            changed["guest"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                proof.verify_run(changed)

    def test_rejects_exit_without_guest_clean_shutdown_or_with_live_binder(self):
        original = report("off", "11111111-1111-4111-8111-111111111111")
        for field, value in (("phase", "guest_tls_validation"), ("binderAliveAfterCleanup", True),
                             ("failureClass", "AssertionError"), ("cleanupErrors", ["timeout"]), ("pid", 0)):
            changed = copy.deepcopy(original)
            changed["exit"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                proof.verify_run(changed)

    def test_rejects_changed_apk_source_native_or_same_boot(self):
        off = report("off", "11111111-1111-4111-8111-111111111111")
        strict = report("strict", "22222222-2222-4222-8222-222222222222")
        for field, value in (("apkSha256", "c" * 64), ("proofCheckout", "d" * 40),
                             ("nativeSource", "e" * 40), ("bootId", off["bootId"]), ("nativeRunId", 123)):
            changed = copy.deepcopy(strict)
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                proof.verify_matrix(off, changed)

    def test_rejects_changed_installation_or_post_test_policy(self):
        original = report("strict", "22222222-2222-4222-8222-222222222222")
        for field, value in (("installedApkSha256", "c" * 64), ("instrumentationAdbStatus", 1),
                             ("settingsAfter", {"mode": "off", "specifier": "null"})):
            changed = copy.deepcopy(original)
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                proof.verify_run(changed)

    def test_phone_guard_and_provider_validation_fail_before_settings_changes(self):
        proof.require_ci_emulator("true", "emulator-5554", "1", "33")
        for arguments in (("false", "emulator-5554", "1", "33"), ("true", "phone123", "0", "33"),
                          ("true", "emulator-5554", "1", "34")):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                proof.require_ci_emulator(*arguments)
        self.assertEqual("dns.google", proof.validate_provider("dns.google"))
        for invalid in ("", "dns.google;reboot", "-bad.example", "good..example", "1.1.1.1", "x" * 64 + ".example"):
            with self.subTest(provider=invalid), self.assertRaises(ValueError):
                proof.validate_provider(invalid)


if __name__ == "__main__":
    unittest.main()

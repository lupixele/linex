"""Guard/runner regressions; synthetic inputs prove rejection rules, not a VM boot."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


guard = load("verify-app-desktop-apk")
runner = load("run-app-desktop-proof")


def inputs(root):
    fixture = root / "fixture"
    fixture.mkdir()
    manifest = {"schema": 1, "architecture": "aarch64", "imageId": "debian-trixie-desktop",
                "disk": {"format": "raw", "filesystem": "ext4", "bytes": 4 * 1024**3, "sha256": "a" * 64}}
    for key, name in guard.FIXTURE_FILES.items():
        data = ("hash guard fixture: " + name).encode()
        (fixture / name).write_bytes(data)
        manifest[key] = {"file": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    manifest["download"]["compression"] = "xz"
    (fixture / "manifest.json").write_text(json.dumps(manifest))
    native = root / "native"
    abi = native / "x86_64"
    abi.mkdir(parents=True)
    elf = b"\x7fELF\x02\x01" + bytes(10) + struct.pack("<HH", 3, 62) + b"byte identity fixture"
    (abi / "liblinex_qemu_aarch64.so").write_bytes(elf)
    (abi / "elf-evidence.json").write_text(json.dumps({"abi": "x86_64", "sha256": hashlib.sha256(elf).hexdigest()}))
    apk = root / "app.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("lib/x86_64/liblinex_qemu_aarch64.so", elf)
    test_apk = root / "test.apk"
    with zipfile.ZipFile(test_apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest identity fixture")
        archive.writestr("classes.dex", b"test code identity fixture")
    return SimpleNamespace(apk=apk, test_apk=test_apk, native=native, fixture=fixture,
        manifest_sha256=guard.hash_file(fixture / "manifest.json"), abi=["x86_64"],
        subject=root / "subject.json", evidence=root / "evidence", adb="adb", apksigner="apksigner", checkout="b" * 40)


def verify(args):
    return guard.verify(args.apk, args.test_apk, args.native, args.fixture, args.manifest_sha256, args.abi, "b" * 40)


def transcript(code=0):
    return f"""INSTRUMENTATION_STATUS: class=com.linex.app.core.VmDesktopProofTest
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.linex.app.core.VmDesktopProofTest
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: {code}
INSTRUMENTATION_CODE: -1
"""


class AppDesktopGuardTest(unittest.TestCase):
    def test_release_source_pin_is_independent_of_workflow_but_receipt_cannot_change_it(self):
        with tempfile.TemporaryDirectory() as directory:
            args = inputs(Path(directory))
            subject = verify(args)
            args.subject.write_text(json.dumps(subject))
            with mock.patch.dict("os.environ", {"GITHUB_SHA": "c" * 40}):
                self.assertEqual(runner.verify_subject(args), subject)
                changed = dict(subject, proofCheckout="c" * 40)
                args.subject.write_text(json.dumps(changed))
                with self.assertRaisesRegex(ValueError, "subject changed"):
                    runner.verify_subject(args)

    def test_producer_rejects_other_sources_repositories_workflows_and_partial_runs(self):
        run = {"id": guard.CANDIDATE_RUN_ID, "head_sha": guard.CANDIDATE_SOURCE,
            "path": ".github/workflows/vm-engine.yml", "event": "workflow_dispatch",
            "status": "completed", "conclusion": "success",
            "repository": {"full_name": "lupixele/linex"}, "head_repository": {"full_name": "lupixele/linex"}}
        guard.validate_producer(run, guard.CANDIDATE_RUN_ID, guard.CANDIDATE_SOURCE)
        for key, value in (("id", 1), ("head_sha", "a" * 40), ("path", "other.yml"),
                ("event", "pull_request"), ("status", "in_progress"), ("conclusion", "failure"),
                ("repository", {"full_name": "other/linex"}), ("head_repository", {})):
            with self.subTest(key=key), self.assertRaises(ValueError):
                guard.validate_producer(dict(run, **{key: value}), guard.CANDIDATE_RUN_ID, guard.CANDIDATE_SOURCE)

    def test_guest_acceptance_rejects_incomplete_restart_children_and_unproved_claims(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = inputs(root)
            subject = verify(args)
            args.evidence.mkdir()
            guest = {"passed": True, "manifestSha256": subject["manifestSha256"], "imageId": subject["imageId"],
                "installedDiskBytes": subject["diskBytes"], "guestFilePersistenceProved": False,
                "browserRuntimeProved": True, "browserMode": "non-root-headless", "glesPresentationProved": False,
                "rejectedLaunchRecovered": True, "bundledCatalogueVerified": True, "launches": []}
            for index in range(2):
                pid = 100 + index
                launch = {"index": index, "pid": pid, "generation": str(index + 1) * 32,
                    "desktopContentVisible": True,
                    "stopped": True, "nonuniformFrame": True, "frameMutation": True, "pauseResume": True,
                    "diskRetained": True, "nonRootHeadlessFirefox": True, "defaultCaHttps": True,
                    "width": 1280, "height": 720, "memoryMiB": 1024, "vcpuCount": 2,
                    "targetFps": 30, "frames": 2, "transport": "private-unix-rfb-vncauth",
                    "hostObservations": [{"complete": True, "pid": pid, "hostChildren": 0, "hostThreads": 8} for _ in range(3)]}
                guest["launches"].append(launch)
                (args.evidence / f"desktop-proof-{index}.png").write_bytes(
                    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR" + struct.pack(">II", 1280, 720))
            runner.verify_guest(guest, subject, args.evidence)
            bad = copy.deepcopy(guest)
            bad["launches"][1]["pid"] = bad["launches"][0]["pid"]
            with self.assertRaises(ValueError): runner.verify_guest(bad, subject, args.evidence)
            bad = copy.deepcopy(guest)
            bad["launches"][0]["hostObservations"][1]["hostChildren"] = 1
            with self.assertRaises(ValueError): runner.verify_guest(bad, subject, args.evidence)
            bad = copy.deepcopy(guest)
            bad["browserRuntimeProved"] = False
            with self.assertRaises(ValueError): runner.verify_guest(bad, subject, args.evidence)
            bad = copy.deepcopy(guest)
            bad["launches"][0]["defaultCaHttps"] = False
            with self.assertRaises(ValueError): runner.verify_guest(bad, subject, args.evidence)
            bad = copy.deepcopy(guest)
            bad["glesPresentationProved"] = True
            with self.assertRaises(ValueError): runner.verify_guest(bad, subject, args.evidence)
            bad = copy.deepcopy(guest)
            bad["rejectedLaunchRecovered"] = False
            with self.assertRaises(ValueError): runner.verify_guest(bad, subject, args.evidence)
            (args.evidence / "desktop-proof-1.png").unlink()
            with self.assertRaises(ValueError): runner.verify_guest(guest, subject, args.evidence)

    def test_exact_bytes_and_producer_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            args = inputs(Path(directory))
            result = verify(args)
            self.assertEqual(result["nativeRunId"], 37648306873)
            self.assertEqual(result["candidateRunId"], 37756061377)
            self.assertFalse(result["runtimeProofPassed"])
            self.assertEqual(set(result["fixtureAssets"]), {"kernel", "desktop.cpio.gz", "factory.raw.xz", "manifest.json"})
            (args.fixture / "kernel").write_bytes(b"changed candidate")
            with self.assertRaisesRegex(ValueError, "Candidate asset changed"):
                verify(args)

    def test_wrong_manifest_external_pin_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            args = inputs(Path(directory))
            args.manifest_sha256 = "0" * 64
            with self.assertRaisesRegex(ValueError, "external pin"):
                verify(args)

    def test_packaged_native_byte_substitution_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            args = inputs(Path(directory))
            with zipfile.ZipFile(args.apk, "w") as archive:
                archive.writestr("lib/x86_64/liblinex_qemu_aarch64.so", b"unverified replacement")
            with self.assertRaisesRegex(ValueError, "Packaged JNI bytes"):
                verify(args)

    def test_wrong_abi_and_embedded_factory_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            args = inputs(Path(directory))
            args.abi = ["arm64-v8a"]
            with self.assertRaisesRegex(ValueError, "ABI set"):
                verify(args)
            args.abi = ["x86_64"]
            with zipfile.ZipFile(args.apk, "a") as archive:
                archive.writestr("assets/factory.raw.xz", b"must remain staged")
            with self.assertRaisesRegex(ValueError, "never embedded"):
                verify(args)

    def test_changed_native_evidence_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            args = inputs(Path(directory))
            evidence = args.native / "x86_64/elf-evidence.json"
            evidence.write_text(json.dumps({"abi": "x86_64", "sha256": "0" * 64}))
            with self.assertRaisesRegex(ValueError, "verified ELF evidence"):
                verify(args)

    def test_one_real_success_required_not_skip_or_zero_test(self):
        runner.verify_instrumentation(transcript())
        for code in (-2, -3, -4):
            with self.assertRaises(ValueError):
                runner.verify_instrumentation(transcript(code))
        for output in ("INSTRUMENTATION_CODE: -1\n", transcript().replace("numtests=1", "numtests=0"),
                       transcript().replace("VmDesktopProofTest", "OtherTest")):
            with self.assertRaises(ValueError):
                runner.verify_instrumentation(output)

    def test_non_ci_runner_never_contacts_device(self):
        with tempfile.TemporaryDirectory() as directory:
            args = inputs(Path(directory))
            with mock.patch.dict("os.environ", {"GITHUB_ACTIONS": "false"}), \
                    mock.patch.object(runner.subprocess, "run") as command, self.assertRaises(ValueError):
                runner.run(args)
            command.assert_not_called()

    def test_physical_or_wrong_sdk_device_never_receives_install_or_clear(self):
        for serial, qemu, sdk in (("physical-device", "0", "33"), ("emulator-5554", "1", "34")):
            with self.subTest(serial=serial, sdk=sdk), tempfile.TemporaryDirectory() as directory:
                args = inputs(Path(directory))
                calls = []
                def device(command, **options):
                    calls.append(command)
                    responses = {("adb", "get-serialno"): serial,
                        ("adb", "shell", "getprop", "ro.kernel.qemu"): qemu,
                        ("adb", "shell", "getprop", "ro.build.version.sdk"): sdk}
                    self.assertIn(tuple(command), responses, "Unexpected physical-device mutation")
                    return subprocess.CompletedProcess(command, 0, responses[tuple(command)], "")
                with mock.patch.dict("os.environ", {"GITHUB_ACTIONS": "true"}), \
                        mock.patch.object(runner.subprocess, "run", side_effect=device), self.assertRaises(ValueError):
                    runner.run(args)
                self.assertEqual(len(calls), 3)

    def test_binary_staging_uses_file_protocol_and_original_instrument_status_survives_capture_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            args = inputs(Path(directory))
            subject = verify(args)
            args.subject.write_text(json.dumps(subject))
            staged = []
            def device(command, **options):
                if command[0] == "apksigner":
                    return subprocess.CompletedProcess(command, 0, "Signer #1 certificate SHA-256 digest: " + "c" * 64 + "\n", "")
                tail = command[1:]
                text = ""
                if tail == ["get-serialno"]: text = "emulator-5554"
                elif tail == ["shell", "getprop", "ro.kernel.qemu"]: text = "1"
                elif tail == ["shell", "getprop", "ro.build.version.sdk"]: text = "33"
                elif tail[:2] == ["install", "--no-streaming"]: text = "Success"
                elif tail[:3] == ["shell", "pm", "path"]: text = "package:/data/app/proof/base.apk"
                elif "sha256sum" in " ".join(tail):
                    path = tail[-1]
                    if path.startswith("files/vm-image-fixtures/"):
                        text = subject["fixtureAssets"][path.split("/")[-1]]["sha256"] + "  " + path
                    else: text = subject["testApkSha256" if runner.TEST_PACKAGE in tail else "apkSha256"] + "  " + path
                elif tail[:3] == ["shell", "pm", "clear"]: text = "Success"
                elif tail[:1] == ["push"]:
                    self.assertNotIn("stdin", options)
                    self.assertRegex(tail[2], r"^/data/local/tmp/linex-desktop-proof-[a-f0-9]{32}/[A-Za-z0-9._-]+$")
                    data = Path(tail[1]).read_bytes()
                    filename = Path(tail[1]).name
                    self.assertEqual(hashlib.sha256(data).hexdigest(), subject["fixtureAssets"][filename]["sha256"])
                    staged.append(filename)
                elif tail[:3] == ["shell", "am", "instrument"]:
                    return subprocess.CompletedProcess(command, 17)
                elif options.get("stdout") not in (None, subprocess.DEVNULL):
                    raise subprocess.CalledProcessError(9, command)
                return subprocess.CompletedProcess(command, 0, text, "")
            with mock.patch.dict("os.environ", {"GITHUB_ACTIONS": "true", "GITHUB_SHA": "b" * 40}), \
                    mock.patch.object(runner.subprocess, "run", side_effect=device), self.assertRaises(SystemExit) as failure:
                runner.run(args)
            self.assertEqual(failure.exception.code, 17)
            self.assertEqual(set(staged), set(subject["fixtureAssets"]))
            report = json.loads((args.evidence / "app-desktop-run.json").read_text())
            self.assertFalse(report["passed"])
            self.assertEqual(report["instrumentationAdbStatus"], 17)
            self.assertTrue(report["captureErrors"])


if __name__ == "__main__":
    unittest.main()

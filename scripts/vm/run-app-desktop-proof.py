"""Run the actual production desktop app test on a disposable API33 CI emulator."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

spec = importlib.util.spec_from_file_location("app_desktop_subject", Path(__file__).with_name("verify-app-desktop-apk.py"))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
PACKAGE = "com.linex.app"
TEST_PACKAGE = "com.linex.app.test"


def require_ci_emulator(actions, serial, qemu, sdk):
    if actions != "true" or not re.fullmatch(r"emulator-[0-9]{4,5}", serial) or qemu != "1" or sdk != "33":
        raise ValueError("Desktop proof may only install/stage on a disposable API33 GitHub CI emulator")


def verify_instrumentation(output):
    codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)\s*$", output, re.MULTILINE)
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", output, re.MULTILINE)
    counts = re.findall(r"^INSTRUMENTATION_STATUS: numtests=([0-9]+)\s*$", output, re.MULTILINE)
    classes = re.findall(r"^INSTRUMENTATION_STATUS: class=(\S+)\s*$", output, re.MULTILINE)
    if codes != ["1", "0"] or terminal != ["-1"] or set(counts) != {"1"} or \
            set(classes) != {"com.linex.app.core.VmDesktopProofTest"}:
        raise ValueError("Instrumentation must complete one real desktop test without skips")


def verify_guest(guest, subject, evidence):
    if guest.get("passed") is not True or guest.get("manifestSha256") != subject["manifestSha256"] or \
            guest.get("imageId") != subject["imageId"] or guest.get("installedDiskBytes") != subject["diskBytes"]:
        raise ValueError("Actual desktop proof does not match the pinned installed factory")
    if any(guest.get(name) is not False for name in ("guestFilePersistenceProved", "glesPresentationProved")):
        raise ValueError("Desktop test must not claim unimplemented file/GLES proof")
    if guest.get("browserRuntimeProved") is not True or guest.get("browserMode") != "non-root-headless":
        raise ValueError("Actual guest non-root browser render proof is required")
    if guest.get("rejectedLaunchRecovered") is not True:
        raise ValueError("A rejected native start must recover before normal desktop launches")
    if guest.get("bundledCatalogueVerified") is not True:
        raise ValueError("Release APK must expose the same validated production image")
    launches = guest.get("launches")
    if not isinstance(launches, list) or len(launches) != 2:
        raise ValueError("Two actual production launches are required")
    for index, launch in enumerate(launches):
        if launch.get("desktopContentVisible") is not True:
            raise ValueError("Cursor-only initial frames cannot prove a visible desktop")
        if launch.get("index") != index or type(launch.get("pid")) is not int or launch["pid"] <= 0 or \
                not re.fullmatch(r"[a-f0-9]{32}", str(launch.get("generation", ""))) or \
                any(launch.get(name) is not True for name in ("stopped", "nonuniformFrame", "frameMutation", "pauseResume", "diskRetained", "nonRootHeadlessFirefox", "defaultCaHttps")) or \
                (launch.get("width"), launch.get("height"), launch.get("memoryMiB"), launch.get("vcpuCount"), launch.get("targetFps")) != (1280, 720, 1024, 2, 30) or \
                type(launch.get("frames")) is not int or launch["frames"] < 2 or \
                launch.get("transport") != "private-unix-rfb-vncauth":
            raise ValueError("Incomplete actual desktop frame/control/shutdown proof")
        observations = launch.get("hostObservations")
        if not isinstance(observations, list) or len(observations) < 3 or any(
                item.get("complete") is not True or item.get("pid") != launch["pid"] or
                type(item.get("hostChildren")) is not int or item["hostChildren"] != 0 or
                type(item.get("hostThreads")) is not int or item["hostThreads"] < 1 for item in observations):
            raise ValueError("Missing complete zero-child native process observations")
        screenshot = evidence / f"desktop-proof-{index}.png"
        guard.regular(screenshot, 32 * 1024 * 1024)
        with screenshot.open("rb") as stream:
            header = stream.read(24)
        if header[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR" or \
                int.from_bytes(header[16:20], "big") != 1280 or int.from_bytes(header[20:24], "big") != 720:
            raise ValueError("Actual frame screenshot is missing or has incorrect dimensions")
    if launches[0]["pid"] == launches[1]["pid"] or launches[0]["generation"] == launches[1]["generation"]:
        raise ValueError("Restart did not use a fresh VM process and private console")


def run(args):
    args.evidence.mkdir(parents=True, exist_ok=True)
    report = {"passed": False}
    allowed = False
    staging = None
    def adb(*command, timeout=30):
        result = subprocess.run([args.adb, *command], check=True, capture_output=True, text=True, timeout=timeout)
        if len(result.stdout) > 1024 * 1024:
            raise ValueError("Oversized adb response")
        return result.stdout.strip()
    def capture(name, *command, timeout=30):
        try:
            with (args.evidence / name).open("wb") as output, (args.evidence / (name + ".error")).open("wb") as errors:
                subprocess.run([args.adb, *command], check=True, stdout=output, stderr=errors, timeout=timeout)
        except (OSError, subprocess.SubprocessError) as error:
            report.setdefault("captureErrors", []).append(f"{name}:{type(error).__name__}")
    try:
        if os.environ.get("GITHUB_ACTIONS") != "true":
            raise ValueError("Desktop proof is restricted to disposable GitHub CI emulators")
        serial = adb("get-serialno")
        require_ci_emulator(os.environ.get("GITHUB_ACTIONS"), serial,
            adb("shell", "getprop", "ro.kernel.qemu"), adb("shell", "getprop", "ro.build.version.sdk"))
        allowed = True
        report["deviceSerial"] = serial
        report["bootId"] = adb("shell", "cat", "/proc/sys/kernel/random/boot_id")
        subject = guard.verify(args.apk, args.test_apk, args.native, args.fixture,
                               args.manifest_sha256, args.abi, os.environ.get("GITHUB_SHA"))
        if guard.read_json(args.subject) != subject:
            raise ValueError("Downloaded app/factory subject changed after APK verification")
        report["subject"] = subject
        certificates = []
        for apk in (args.apk, args.test_apk):
            result = subprocess.run([args.apksigner, "verify", "--verbose", "--print-certs", "--min-sdk-version", "33", str(apk)],
                                    check=True, capture_output=True, text=True, timeout=90)
            fingerprints = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$", result.stdout, re.MULTILINE)
            if len(fingerprints) != 1:
                raise ValueError("Proof APK must have exactly one verified signing certificate")
            certificates.append(fingerprints[0].lower())
        if certificates[0] != certificates[1]:
            raise ValueError("App and instrumentation APKs must use the same CI signing key")
        report["signerSha256"] = certificates[0]
        for apk, package, digest_key in ((args.apk, PACKAGE, "apkSha256"), (args.test_apk, TEST_PACKAGE, "testApkSha256")):
            installed = adb("install", "--no-streaming", "-r", str(apk.resolve()), timeout=120)
            if "Success" not in installed.splitlines():
                raise ValueError("Android did not confirm proof APK installation")
            paths = adb("shell", "pm", "path", package).splitlines()
            if len(paths) != 1 or not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./~+=-]+/base\.apk", paths[0]):
                raise ValueError("Unexpected installed APK path")
            digest = adb("exec-out", "run-as", package, "/system/bin/sha256sum", paths[0][8:]).split()[0]
            if digest != subject[digest_key]:
                raise ValueError("Installed proof APK bytes differ from the verified subject")
            report.setdefault("installed", {})[package] = digest
        if adb("shell", "pm", "clear", PACKAGE) != "Success":
            raise ValueError("Could not clear stale disposable proof data")
        adb("exec-out", "run-as", PACKAGE, "/system/bin/mkdir", "files")
        adb("exec-out", "run-as", PACKAGE, "/system/bin/mkdir", "files/vm-image-fixtures")
        adb("exec-out", "run-as", PACKAGE, "/system/bin/chmod", "700", "files/vm-image-fixtures")
        staging = "/data/local/tmp/linex-desktop-proof-" + uuid.uuid4().hex
        adb("shell", "/system/bin/mkdir", "-m", "755", staging)
        for filename, item in subject["fixtureAssets"].items():
            print(f"Staging {filename} ({item['bytes']} bytes)", flush=True)
            # Use adb's binary file protocol; API33 toybox dd returned EFAULT
            # when reading a large shell-v2 stdin stream. These public factory
            # bytes are copied as the app UID and verified again in private storage.
            adb("push", str((args.fixture / filename).resolve()), staging + "/" + filename, timeout=600)
            adb("shell", "/system/bin/chmod", "644", staging + "/" + filename)
            adb("exec-out", "run-as", PACKAGE, "/system/bin/cp", staging + "/" + filename,
                f"files/vm-image-fixtures/{filename}", timeout=600)
            adb("exec-out", "run-as", PACKAGE, "/system/bin/chmod", "600", f"files/vm-image-fixtures/{filename}")
            staged = adb("exec-out", "run-as", PACKAGE, "/system/bin/sha256sum", f"files/vm-image-fixtures/{filename}").split()[0]
            if staged != item["sha256"]:
                raise ValueError("Staged candidate bytes differ from the pinned subject")
            adb("shell", "/system/bin/rm", staging + "/" + filename)
        command = [args.adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", "com.linex.app.core.VmDesktopProofTest",
                   "-e", "desktopManifestSha256", args.manifest_sha256,
                   "com.linex.app.test/androidx.test.runner.AndroidJUnitRunner"]
        with (args.evidence / "instrumentation.txt").open("wb") as output, (args.evidence / "instrumentation.error").open("wb") as errors:
            result = subprocess.run(command, stdout=output, stderr=errors, timeout=1200, check=False)
        report["instrumentationAdbStatus"] = result.returncode
        if result.returncode:
            raise SystemExit(result.returncode)
        guard.regular(args.evidence / "instrumentation.txt", 1024 * 1024)
        verify_instrumentation((args.evidence / "instrumentation.txt").read_text(encoding="utf-8"))
        for filename in ("vm-desktop-proof.json", "desktop-proof-0.png", "desktop-proof-1.png"):
            capture(filename, "exec-out", "run-as", PACKAGE, "/system/bin/cat", f"files/{filename}")
        report["guest"] = guard.read_json(args.evidence / "vm-desktop-proof.json")
        verify_guest(report["guest"], subject, args.evidence)
        report["passed"] = True
    except BaseException as error:
        report["passed"] = False
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        if allowed:
            if staging:
                # Four fixed files in this run's random directory; never recursive.
                capture("stage-cleanup.txt", "shell", "/system/bin/rm", "-f",
                        *(staging + "/" + name for name in ("manifest.json", *guard.FIXTURE_FILES.values())))
                capture("stage-rmdir.txt", "shell", "/system/bin/rmdir", staging)
            capture("logcat.txt", "logcat", "-b", "all", "-d", "-v", "threadtime", "-t", "5000")
            capture("processes-after.txt", "shell", "ps", "-A")
            for filename in ("vm-desktop-proof.json", "desktop-proof-0.png", "desktop-proof-1.png"):
                if not (args.evidence / filename).exists():
                    capture(filename, "exec-out", "run-as", PACKAGE, "/system/bin/cat", f"files/{filename}")
        try:
            (args.evidence / "app-desktop-run.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        except OSError:
            if report["passed"]:
                raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "test-apk", "native", "fixture", "subject", "evidence"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--abi", action="append", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apksigner", required=True)
    run(parser.parse_args())

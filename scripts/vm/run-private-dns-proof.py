"""Run one real DNS/TLS policy proof on a disposable API33 CI emulator only."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

spec = importlib.util.spec_from_file_location("linex_private_dns_acceptance", Path(__file__).with_name("verify-private-dns-proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


def run(args):
    if args.mode not in ("off", "strict"):
        raise ValueError("Unsupported matrix mode")
    provider = proof.validate_provider(args.provider) if args.mode == "strict" else None
    args.evidence.mkdir(parents=True, exist_ok=True)
    report = {"passed": False, "mode": args.mode, "provider": provider}
    allowed = False

    def adb(*command, timeout=15):
        result = subprocess.run([args.adb, *command], check=True, capture_output=True, text=True, timeout=timeout)
        if len(result.stdout) > 1024 * 1024:
            raise ValueError("Oversized adb text response")
        return result.stdout.strip()

    def settings():
        return {"mode": adb("shell", "settings", "get", "global", "private_dns_mode"),
                "specifier": adb("shell", "settings", "get", "global", "private_dns_specifier")}

    def capture(name, *command, timeout=15):
        try:
            with (args.evidence / name).open("wb") as output, (args.evidence / (name + ".error")).open("wb") as errors:
                subprocess.run([args.adb, *command], check=True, stdout=output, stderr=errors, timeout=timeout)
        except (OSError, subprocess.SubprocessError) as error:
            report.setdefault("captureErrors", []).append(f"{name}:{type(error).__name__}")

    try:
        subject = proof.verify_subject(proof.read_json(args.subject), args.apk, os.environ.get("GITHUB_SHA"))
        report.update({name: subject[name] for name in ("apkSha256", "proofCheckout", "nativeSource", "nativeRunId")})
        serial = adb("get-serialno")
        qemu = adb("shell", "getprop", "ro.kernel.qemu")
        sdk = adb("shell", "getprop", "ro.build.version.sdk")
        proof.require_ci_emulator(os.environ.get("GITHUB_ACTIONS"), serial, qemu, sdk)
        allowed = True
        report["deviceSerial"] = serial
        report["bootId"] = adb("shell", "cat", "/proc/sys/kernel/random/boot_id")
        report["settingsBefore"] = settings()
        if args.mode == "strict":
            adb("shell", "settings", "put", "global", "private_dns_specifier", provider)
            adb("shell", "settings", "put", "global", "private_dns_mode", "hostname")
        else:
            adb("shell", "settings", "put", "global", "private_dns_mode", "off")
            adb("shell", "settings", "delete", "global", "private_dns_specifier")
        expected = {"mode": "hostname" if provider else "off", "specifier": provider or "null"}
        report["settingsApplied"] = settings()
        if report["settingsApplied"] != expected:
            raise ValueError("Android settings readback differs from requested CI policy")
        install = adb("install", "--no-streaming", "-r", str(args.apk.resolve()), timeout=90)
        (args.evidence / "install.txt").write_text(install + "\n", encoding="utf-8")
        if "Success" not in install.splitlines():
            raise ValueError("Android did not confirm proof APK installation")
        package = "com.linex.vm.test"
        locations = adb("shell", "pm", "path", package).splitlines()
        if len(locations) != 1 or not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./~+=-]+/base\.apk", locations[0]):
            raise ValueError("Unexpected installed proof APK location")
        installed = adb("exec-out", "run-as", package, "/system/bin/sha256sum", locations[0][8:]).split()[0]
        report["installedApkSha256"] = installed
        if installed != subject["apkSha256"]:
            raise ValueError("Installed proof APK differs from the verified subject")
        if adb("shell", "pm", "clear", package) != "Success":
            raise ValueError("Could not remove stale proof data before instrumentation")
        command = [args.adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", "com.linex.vm.VmHttpsProofTest",
                   "-e", "expectedPrivateDns", args.mode]
        if provider:
            command += ["-e", "expectedPrivateDnsHostname", provider]
        command += ["com.linex.vm.test/androidx.test.runner.AndroidJUnitRunner"]
        with (args.evidence / "instrumentation.txt").open("wb") as output, (args.evidence / "instrumentation.error").open("wb") as errors:
            result = subprocess.run(command, stdout=output, stderr=errors, timeout=480, check=False)
        report["instrumentationAdbStatus"] = result.returncode
        if result.returncode != 0:
            raise RuntimeError(f"Instrumentation adb command failed with status{result.returncode}")
        output = args.evidence / "instrumentation.txt"
        if output.stat().st_size > 1024 * 1024:
            raise ValueError("Oversized instrumentation response")
        proof.verify_instrumentation(output.read_text(encoding="utf-8"))
        for name in ("vm-https-proof.json", "vm-https-proof.log", "vm-proof-3-exit.json"):
            capture(name, "exec-out", "run-as", package, "cat", f"files/{name}")
        report["guest"] = proof.read_json(args.evidence / "vm-https-proof.json")
        report["exit"] = proof.read_json(args.evidence / "vm-proof-3-exit.json")
        report["settingsAfter"] = settings()
        if report["settingsAfter"] != expected:
            raise ValueError("CI DNS settings changed during proof")
        report["passed"] = True
        proof.verify_run(report)
    except BaseException as error:
        report["passed"] = False
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        if allowed:
            capture("logcat.txt", "logcat", "-b", "all", "-d", "-v", "threadtime", "-t", "5000")
            capture("processes-after.txt", "shell", "ps", "-A")
            for name in ("vm-https-proof.json", "vm-https-proof.log", "vm-proof-3-exit.json"):
                if not (args.evidence / name).exists():
                    capture(name, "exec-out", "run-as", "com.linex.vm.test", "cat", f"files/{name}")
        # Capture/write failures cannot replace an existing instrumentation failure.
        try:
            (args.evidence / "private-dns-run.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        except OSError:
            if report["passed"]:
                raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("off", "strict"), required=True)
    parser.add_argument("--provider", default="dns.google")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--subject", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    run(parser.parse_args())

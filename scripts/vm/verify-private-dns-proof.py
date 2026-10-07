"""Accept only actual off/strict Android13 DNS+TLS runs of one verified APK/native subject."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import re

NATIVE_SOURCE = "5e7e6b5b5331fbab27b2a9fd8ce0fe029adbd03d"
NATIVE_RUN_ID = 37574288473
REQUIRED_GUEST_FLAGS = ("guestDns", "udpAndTcp", "verifiedPublicHttps", "validControlledTls",
                        "wrongHostnameRejected", "untrustedCaRejected", "tlsDeadline", "hostObservationComplete")


def hash_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_subject(subject, apk, checkout):
    if subject.get("nativeSource") != NATIVE_SOURCE or subject.get("nativeRunId") != NATIVE_RUN_ID or \
            not re.fullmatch(r"[a-f0-9]{40}", str(checkout)) or subject.get("proofCheckout") != checkout:
        raise ValueError("APK producer source identity mismatch")
    if apk.is_symlink() or not apk.is_file() or not 1 <= apk.stat().st_size <= 512 * 1024 * 1024 or \
            not re.fullmatch(r"[a-f0-9]{64}", str(subject.get("apkSha256", ""))) or hash_file(apk) != subject["apkSha256"]:
        raise ValueError("Downloaded APK bytes do not match the verified producer")
    return subject


def require_ci_emulator(actions, serial, qemu, sdk):
    if actions != "true" or not re.fullmatch(r"emulator-[0-9]{4,5}", serial) or qemu != "1" or sdk != "33":
        raise ValueError("Private DNS settings may only change on a disposable API33 GitHub CI emulator")


def validate_provider(hostname):
    if not isinstance(hostname, str) or not 3 <= len(hostname) <= 253 or hostname != hostname.lower():
        raise ValueError("Strict CI DNS provider must be a lowercase hostname")
    if len(hostname.split(".")) < 2 or any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
                                          for label in hostname.split(".")):
        raise ValueError("Invalid strict CI DNS provider hostname")
    try:
        ipaddress.ip_address(hostname)
    except ValueError:
        return hostname
    raise ValueError("Strict DNS provider must be a certificate hostname, not an IP address")


def verify_instrumentation(output):
    # AndroidX ResultPrinter reports1=start,0=success,-2=failure,-3=ignored,-4=assumption failure.
    codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)\s*$", output, re.MULTILINE)
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", output, re.MULTILINE)
    counts = re.findall(r"^INSTRUMENTATION_STATUS: numtests=([0-9]+)\s*$", output, re.MULTILINE)
    classes = re.findall(r"^INSTRUMENTATION_STATUS: class=(\S+)\s*$", output, re.MULTILINE)
    if codes != ["1", "0"] or terminal != ["-1"] or set(counts) != {"1"} or set(classes) != {"com.linex.vm.VmHttpsProofTest"}:
        raise ValueError("Instrumentation did not complete exactly one actual HTTPS proof without skips")


def verify_run(report):
    mode = report.get("mode")
    if mode not in ("off", "strict") or report.get("passed") is not True:
        raise ValueError("Missing successful explicit DNS mode")
    if report.get("nativeSource") != NATIVE_SOURCE or report.get("nativeRunId") != NATIVE_RUN_ID:
        raise ValueError("Unexpected immutable native producer")
    for name, length in (("apkSha256", 64), ("proofCheckout", 40)):
        if not re.fullmatch(r"[a-f0-9]{" + str(length) + r"}", str(report.get(name, ""))):
            raise ValueError("Invalid proof subject identity")
    if not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", str(report.get("bootId", ""))):
        raise ValueError("Missing real emulator boot identity")
    strict = mode == "strict"
    provider = validate_provider(report.get("provider")) if strict else None
    if not strict and report.get("provider") is not None:
        raise ValueError("Off mode must not name a provider")
    if report.get("installedApkSha256") != report["apkSha256"] or type(report.get("instrumentationAdbStatus")) is not int or \
            report["instrumentationAdbStatus"] != 0:
        raise ValueError("Installed subject or instrumentation command failed verification")
    policy = {"mode": "hostname" if strict else "off", "specifier": provider or "null"}
    if any(report.get(name) != policy for name in ("settingsApplied", "settingsAfter")):
        raise ValueError("CI settings did not retain the requested system policy")
    guest = report.get("guest", {})
    if any(guest.get(name) is not True for name in REQUIRED_GUEST_FLAGS):
        raise ValueError("Incomplete guest DNS/TLS/host-isolation proof")
    if type(guest.get("sameDnsTransactionId")) is not int or guest["sameDnsTransactionId"] != 4660 or \
            type(guest.get("hostChildrenAfter")) is not int or guest["hostChildrenAfter"] != 0:
        raise ValueError("Guest transaction correlation or process isolation proof missing")
    if guest.get("expectedPrivateDnsMode") != mode or guest.get("expectedPrivateDnsHostname") != (provider or "unspecified"):
        raise ValueError("Guest proof expected a different DNS policy")
    if any(guest.get(name) is not strict for name in ("privateDnsActiveBefore", "privateDnsStrictBefore",
                                                   "privateDnsActiveAfter", "privateDnsStrictAfter")):
        raise ValueError("System LinkProperties did not retain the exact requested DNS mode")
    if guest.get("privateDnsMatrixVerified") is not False:
        raise ValueError("One run cannot claim verification of both DNS modes")
    exited = report.get("exit", {})
    if exited.get("androidSdk") != 33 or type(exited.get("pid")) is not int or exited["pid"] <= 0 or \
            exited.get("phase") != "https_clean_exit" or exited.get("failureClass") or \
            exited.get("binderAliveBeforeCleanup") is not False or exited.get("binderAliveAfterCleanup") is not False or \
            exited.get("cleanupErrors") != []:
        raise ValueError("Missing clean guest shutdown and managed-process exit")
    return report


def verify_matrix(off, strict):
    for report in (off, strict):
        verify_run(report)
    if off["mode"] != "off" or strict["mode"] != "strict":
        raise ValueError("Matrix must contain one off run and one strict run")
    if any(off[name] != strict[name] for name in ("apkSha256", "proofCheckout", "nativeSource", "nativeRunId")):
        raise ValueError("Matrix runs used different APK/source/native subjects")
    if off["bootId"] == strict["bootId"]:
        raise ValueError("Matrix runs must use separate fresh emulator boots")
    return {"privateDnsMatrixVerified": True, "scope": "API33 CI emulator; not physical-device or desktop performance",
            "apkSha256": off["apkSha256"], "proofCheckout": off["proofCheckout"],
            "nativeSource": NATIVE_SOURCE, "nativeRunId": NATIVE_RUN_ID,
            "strictProvider": strict["provider"], "modes": ["off", "strict"],
            "bootIds": [off["bootId"], strict["bootId"]]}


def read_json(path):
    if not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ValueError("Missing or oversized DNS proof JSON")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Invalid DNS proof object")
    return value


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--off", type=Path, required=True)
    parser.add_argument("--strict", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for path in (args.off.with_name("instrumentation.txt"), args.strict.with_name("instrumentation.txt")):
        if not path.is_file() or path.stat().st_size > 1024 * 1024:
            raise ValueError("Missing or oversized raw instrumentation proof")
        verify_instrumentation(path.read_text(encoding="utf-8"))
    result = verify_matrix(read_json(args.off), read_json(args.strict))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

"""Check the actual Android ELF rather than trusting a successful link."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def verify(library: Path, abi: str, tools: Path) -> dict:
    def run(tool, *args):
        return subprocess.check_output([str(tools / tool), *args, str(library)], text=True)
    header = run("llvm-readelf", "-h")
    dynamic = run("llvm-readelf", "-d")
    program = run("llvm-readelf", "-l", "--wide")
    symbols = run("llvm-nm", "-D")
    expected = {"arm64-v8a": "AArch64", "x86_64": "Advanced Micro Devices X86-64"}[abi]
    if expected not in header or not re.search(r"Type:\s+DYN", header):
        raise ValueError("Wrong Android ELF architecture/type")
    needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
    permitted = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
    if not needed or set(needed) - permitted:
        raise ValueError("Unpackaged dynamic dependencies: " + repr(needed))
    alignments = [int(line.split()[-1], 16) for line in program.splitlines() if line.strip().startswith("LOAD ")]
    if not alignments or min(alignments) < 16384:
        raise ValueError("ELF LOAD segments must support 16KiB page alignment")
    if not re.search(r"^\s*[a-fA-F0-9]+\s+[TW]\s+Java_com_linex_vm_NativeVm_run$", symbols, re.MULTILINE):
        raise ValueError("Missing JNI entry point")
    for entry in ("slirp_new", "slirp_input"):
        if not re.search(r"^\s*[a-fA-F0-9]+\s+[TW]\s+" + entry + "$", symbols, re.MULTILINE):
            raise ValueError("Missing statically linked SLIRP backend entry: " + entry)
    imports = [line.split()[-1].split("@")[0] for line in symbols.splitlines() if re.search(r"\bU\b", line)]
    # daemon() forks within libc; wrapping fork at our ELF boundary cannot
    # intercept that internal call. It must itself be wrapped and absent here.
    forbidden = {"fork", "vfork", "clone", "clone3", "daemon", "forkpty", "posix_spawn", "posix_spawnp",
                 "system", "popen", "execve", "execvp", "execv", "execl", "execlp", "execle", "execvpe", "fexecve",
                 "shmat", "shmget", "shmdt", "shmctl"}
    if set(imports) & forbidden:
        raise ValueError("Uncontrolled host helper imports: " + repr(set(imports) & forbidden))
    if b"/data/data/com.termux" in library.read_bytes() or b"@TERMUX_PREFIX@" in library.read_bytes():
        raise ValueError("Hard-coded Termux runtime path")
    return {"abi": abi, "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
            "needed": needed, "load_alignments": alignments, "imports": imports,
            "jni_entry": "Java_com_linex_vm_NativeVm_run", "kernel_boot": "pending",
            "scope": "TCG with static SLIRP; typed serial launch remains NIC-less",
            "user_network": "compiled; launch activation pending Android DNS bridge",
            "android_dns_bridge": "pending"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], required=True)
    parser.add_argument("--tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(verify(args.library, args.abi, args.tools), indent=2) + "\n")

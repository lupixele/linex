"""Failure gates on ELF dependencies, page size and native helper imports."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE_SPEC = importlib.util.spec_from_file_location("verify_engine", Path(__file__).with_name("verify-engine.py"))
VERIFIER = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(VERIFIER)


class ElfBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.library = Path(self.directory.name) / "liblinex_qemu_aarch64.so"
        self.library.write_bytes(b"ELF inspection fixture")
        self.header = "Type: DYN\nMachine: AArch64\n"
        self.dynamic = "(NEEDED) Shared library: [libc.so]\n"
        self.program = "LOAD 0x0 0x0 0x0 0x1 0x1 R E 0x4000\n"
        self.symbols = "00001000 T Java_com_linex_vm_NativeVm_run\n                 U pthread_create\n"
        self.symbols += "00002000 T slirp_new\n00003000 T slirp_input\n"
        self.symbols += "00004000 T slirp_linex_dns_install\n00005000 T slirp_linex_dns_receive\n00006000 T slirp_linex_dns_tick\n"

    def inspect(self):
        with patch.object(VERIFIER.subprocess, "check_output", side_effect=[self.header, self.dynamic, self.program, self.symbols]):
            return VERIFIER.verify(self.library, "arm64-v8a", Path("toolchain"))

    def test_supported_elf_preserves_pending_boot_gate(self):
        self.assertEqual("pending", self.inspect()["kernel_boot"])

    def test_unpackaged_shared_dependency_fails(self):
        self.dynamic += "(NEEDED) Shared library: [libglib-2.0.so]\n"
        with self.assertRaisesRegex(ValueError, "Unpackaged"):
            self.inspect()

    def test_enabled_user_network_must_link_actual_slirp(self):
        self.symbols = self.symbols.replace("00002000 T slirp_new\n", "")
        with self.assertRaisesRegex(ValueError, "SLIRP"):
            self.inspect()

    def test_imported_slirp_cannot_satisfy_static_network_backend(self):
        self.symbols = self.symbols.replace("00003000 T slirp_input", "                 U slirp_input")
        with self.assertRaisesRegex(ValueError, "SLIRP"):
            self.inspect()

    def test_private_android_dns_hook_must_be_defined_in_engine(self):
        self.symbols = self.symbols.replace("00004000 T slirp_linex_dns_install\n", "                 U slirp_linex_dns_install\n")
        with self.assertRaisesRegex(ValueError, "SLIRP"):
            self.inspect()

    def test_four_kib_segment_is_rejected(self):
        self.program = self.program.replace("0x4000", "0x1000")
        with self.assertRaisesRegex(ValueError, "16KiB"):
            self.inspect()

    def test_unmanaged_helper_import_is_rejected(self):
        self.symbols += "                 U posix_spawn\n"
        with self.assertRaisesRegex(ValueError, "host helper"):
            self.inspect()

    def test_libc_daemon_import_is_rejected(self):
        # daemon() can fork inside libc without an ELF import of fork().
        self.symbols += "                 U daemon\n"
        with self.assertRaisesRegex(ValueError, "host helper"):
            self.inspect()

    def test_versioned_libc_daemon_import_is_rejected(self):
        self.symbols += "                 U daemon@LIBC\n"
        with self.assertRaisesRegex(ValueError, "host helper"):
            self.inspect()

    def test_imported_jni_symbol_cannot_satisfy_entry_point(self):
        self.symbols = "                 U Java_com_linex_vm_NativeVm_run\n"
        with self.assertRaisesRegex(ValueError, "JNI"):
            self.inspect()

    def test_architecture_mismatch_fails(self):
        self.header = self.header.replace("AArch64", "ARM")
        with self.assertRaisesRegex(ValueError, "architecture"):
            self.inspect()

    def test_absolute_termux_runtime_path_is_rejected(self):
        self.library.write_bytes(b"/data/data/com.termux/files/usr/bin")
        with self.assertRaisesRegex(ValueError, "Termux"):
            self.inspect()


if __name__ == "__main__":
    unittest.main()

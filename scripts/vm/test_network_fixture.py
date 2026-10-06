import gzip
import importlib.util
import io
from pathlib import Path
import stat
import sys
import tarfile
import unittest

from test_fixture import archive, parse_newc

path = Path(__file__).with_name("build-network-fixture.py")
spec = importlib.util.spec_from_file_location("vm_network_fixture", path)
network = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = network
spec.loader.exec_module(network)


def ar_member(name, payload):
    header = (f"{name + '/':<16}{0:<12}{0:<6}{0:<6}{'100644':<8}{len(payload):<10}`\n").encode()
    return header + payload + (b"\n" if len(payload) % 2 else b"")


def package(config, name="./boot/config-6.8.0-142-generic", kind=tarfile.REGTYPE):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as output:
        entry = tarfile.TarInfo(name)
        entry.type = kind
        entry.size = len(config) if kind == tarfile.REGTYPE else 0
        output.addfile(entry, io.BytesIO(config) if entry.size else None)
    return b"!<arch>\n" + ar_member("debian-binary", b"2.0\n") + ar_member("data.tar", stream.getvalue())


class NetworkFixtureTest(unittest.TestCase):
    def test_reads_config_without_extracting_package_paths(self):
        config = b"CONFIG_VIRTIO_NET=y\n"
        self.assertEqual(config, network.read_kernel_config(package(config)))

    def test_rejects_bad_deb_headers_lengths_and_duplicate_payload(self):
        valid = package(b"config")
        for invalid in (b"not ar", valid[:-1], valid + ar_member("data.tar", b"duplicate")):
            with self.subTest(invalid=invalid[:16]), self.assertRaises(ValueError):
                network.read_kernel_config(invalid)

    def test_rejects_unsafe_config_names_links_and_size(self):
        for name, kind in (("../escape", tarfile.REGTYPE),
                           ("./boot/config-6.8.0-142-generic", tarfile.SYMTYPE)):
            with self.subTest(name=name), self.assertRaises(ValueError):
                network.read_kernel_config(package(b"config", name, kind))
        with self.assertRaises(ValueError):
            network.read_kernel_config(package(b"x" * (network.MAX_CONFIG_BYTES + 1)))

    def test_requires_every_network_driver_built_in(self):
        config = "".join(f"{feature}=y\n" for feature in network.NETWORK_FEATURES).encode()
        network.verify_network_config(config)
        for feature in network.NETWORK_FEATURES:
            with self.subTest(feature=feature), self.assertRaises(ValueError):
                network.verify_network_config(config.replace(f"{feature}=y".encode(), f"{feature}=m".encode()))

    def test_network_initramfs_is_separate_deterministic_and_bounded(self):
        root = network.fixture.read_rootfs(archive([("bin/busybox", tarfile.REGTYPE, b"binary")]))
        serial_before = network.fixture.make_initramfs(root)
        first = network.make_network_initramfs(root)
        self.assertEqual(first, network.make_network_initramfs(root))
        self.assertEqual(serial_before, network.fixture.make_initramfs(root))
        entries = parse_newc(gzip.decompress(first))
        self.assertIn(b"LINEX_VM_NETWORK_READY", entries["init"][1])
        self.assertIn(b"udhcpc", entries["init"][1])
        self.assertIn(b"sleep 86400 &", entries["init"][1])
        self.assertIn(b"tcp\\ *)", entries["init"][1])
        self.assertIn(b"10.0.2.2", entries["init"][1])
        self.assertIn(b"LINEX_VM_TCP_REQUEST", entries["init"][1])
        self.assertEqual(stat.S_IFREG, stat.S_IFMT(entries["linex-dhcp"][0][1]))
        self.assertNotIn(b"-hostfwd", entries["init"][1])


if __name__ == "__main__":
    unittest.main()

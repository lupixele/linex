import importlib.util
from pathlib import Path
import socket
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("vm_network_harness", Path(__file__).with_name("test-network.py"))
network = importlib.util.module_from_spec(spec)
spec.loader.exec_module(network)


class NetworkHarnessTest(unittest.TestCase):
    def test_command_has_fixed_user_network_and_no_host_forwarding(self):
        command = network.boot_command("qemu", Path("kernel"), Path("initrd"), Path("qmp"))
        self.assertIn("virtio-net-device,netdev=linexnet", command)
        self.assertIn("user,id=linexnet,ipv6=off", command)
        self.assertFalse(any("hostfwd" in value or "guestfwd" in value for value in command))

    def test_actual_tcp_challenge_accepts_only_exact_request(self):
        with network.TcpChallenge() as challenge:
            with socket.create_connection(("127.0.0.1", challenge.port), timeout=2) as peer:
                peer.sendall(b"LINEX_VM_TCP_REQUEST\n")
                self.assertEqual(b"LINEX_VM_TCP_RESPONSE\n", peer.recv(128))
            self.assertTrue(challenge.completed.wait(2))
            self.assertTrue(challenge.request_verified)

    def test_bad_and_oversized_tcp_requests_cannot_pass(self):
        for request in (b"wrong\n", b"x" * 129):
            with self.subTest(request=request[:8]), network.TcpChallenge() as challenge:
                with socket.create_connection(("127.0.0.1", challenge.port), timeout=2) as peer:
                    peer.sendall(request)
                self.assertTrue(challenge.completed.wait(2))
                self.assertFalse(challenge.request_verified)

    def test_fixture_must_match_hash_and_fixed_network_asset_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.json").write_text('{"schema":1,"kernel":{"file":"kernel","sha256":"bad"},"initramfs":{"file":"network-proof.cpio.gz","sha256":"bad"}}')
            (root / "kernel").write_bytes(b"bad")
            (root / "network-proof.cpio.gz").write_bytes(b"bad")
            with self.assertRaises(ValueError):
                network.verify_fixture(root)


if __name__ == "__main__":
    unittest.main()

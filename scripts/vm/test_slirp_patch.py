"""Exact upstream edits must install the stack extension and main-loop pump."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/vm' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PinnedPatchTests(unittest.TestCase):
    def test_loopback_option_rejects_changed_qapi_without_partial_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'qapi').mkdir()
            (root / 'net').mkdir()
            schema = root / 'qapi/net.json'
            network = root / 'net/slirp.c'
            schema.write_text("{ 'struct': 'ChangedUpstreamOptions', 'data': {} }\n")
            network.write_text('/* Different QEMU ABI must not be patched. */\n')
            before = {path: path.read_bytes() for path in (schema, network)}
            with self.assertRaises(ValueError):
                load('patch-qemu').host_loopback_updates(root)
            self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_slirp_patch_fails_before_mutating_changed_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'meson.build').write_text('sources = []\n')
            before = (root / 'meson.build').read_bytes()
            with self.assertRaises((ValueError, FileNotFoundError)):
                load('patch-slirp').patch(root, ROOT / 'vm-engine/src/main/jni')
            self.assertEqual(before, (root / 'meson.build').read_bytes())


if __name__ == '__main__':
    unittest.main()

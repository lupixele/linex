"""Exact upstream edits must install the stack extension and main-loop pump."""
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/vm' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PinnedPatchTests(unittest.TestCase):
    def test_main_loop_pump_is_part_of_engine_embedding(self):
        patch = (ROOT / 'scripts/vm/patch-qemu.py').read_text()
        self.assertIn('linex_slirp.inc', patch)
        pump = (ROOT / 'vm-engine/src/main/jni/linex_slirp.inc').read_text()
        self.assertIn('aio_bh_new(qemu_get_aio_context()', pump)
        self.assertIn('QEMU_CLOCK_REALTIME', pump)

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

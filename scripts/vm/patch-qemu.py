"""Version-specific embedding changes; fail rather than silently patch another ABI."""
import argparse
from pathlib import Path
import shutil


def patch(source: Path, shim: Path) -> None:
    if (source / "VERSION").read_text().strip() != "11.0.3":
        raise ValueError("Embedding patch requires QEMU 11.0.3")
    meson = source / "meson.build"
    content = meson.read_text()
    anchor = "  if target.endswith('-softmmu')\n    execs = [{"
    if content.count(anchor) != 1:
        raise ValueError("QEMU shared-library anchor changed")
    # Use the exact same per-target object graph and dependencies as the actual
    # emulator; exclude system/main.c's executable main(). No ELF conversion.
    addition = """  if target == 'aarch64-softmmu'
    shared_library('linex_qemu_aarch64', files('system/linex_jni.c'),
      dependencies: arch_deps,
      objects: lib.extract_all_objects(recursive: true),
      include_directories: target_inc,
      c_args: c_args,
      link_args: link_args + ['-Wl,-z,max-page-size=16384', '-Wl,-Bsymbolic'],
      install: false)
  endif

"""
    content = content.replace(anchor, addition + anchor)
    # Android merges realtime functions into libc and has no librt or POSIX
    # shm_open. The serial VM uses anonymous RAM; our unsupported shm backend
    # returns ENOTSUP. Preserve the real feature probe instead of faking it.
    realtime_anchor = "  if not have_shm_open\n    rt = cc.find_library('rt', required: true)"
    if content.count(realtime_anchor) != 1:
        raise ValueError("QEMU realtime library probe changed")
    content = content.replace(realtime_anchor,
        "  if not have_shm_open and cc.get_define('__ANDROID__') == ''\n    rt = cc.find_library('rt', required: true)")
    meson.write_text(content)
    shutil.copyfile(shim, source / "system/linex_jni.c")
    # Bionic has no POSIX shm declarations. These backends deliberately return
    # ENOTSUP in the shim; the managed launch always uses anonymous guest RAM.
    oslib = source / "util/oslib-posix.c"
    content = oslib.read_text()
    anchor = '#include "qemu/osdep.h"'
    assert content.count(anchor) == 1
    oslib.write_text(content.replace(anchor, anchor + '\nint shm_open(const char *, int, mode_t);\nint shm_unlink(const char *);'))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--shim", type=Path, required=True)
    args = parser.parse_args()
    patch(args.source, args.shim)

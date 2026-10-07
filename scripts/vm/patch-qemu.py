"""Version-specific embedding changes; fail rather than silently patch another ABI."""
import argparse
from pathlib import Path
import shutil


def host_loopback_updates(source: Path) -> dict[Path, str]:
    """Prepare the production policy without altering omitted-option fixtures."""
    updates = {}

    def replace(relative: str, anchor: str, replacement: str) -> None:
        path = source / relative
        content = updates[path] if path in updates else path.read_text()
        if content.count(anchor) != 1:
            raise ValueError("QEMU host loopback policy anchor changed: " + relative)
        updates[path] = content.replace(anchor, replacement)

    replace("qapi/net.json", "# @restrict: isolate the guest from the host\n#\n",
            "# @restrict: isolate the guest from the host\n#\n"
            "# @linex-host-loopback: private Linex policy permitting host loopback\n"
            "#     access; omission preserves historical access (since 11.0)\n#\n")
    replace("qapi/net.json", "    '*restrict':  'bool',",
            "    '*restrict':  'bool',\n    '*linex-host-loopback': 'bool',")
    replace("net/slirp.c", "                          const char *tftp_server_name,\n                          Error **errp)",
            "                          const char *tftp_server_name,\n                          bool host_loopback, Error **errp)")
    replace("net/slirp.c", "    cfg.restricted = restricted;",
            "    cfg.disable_host_loopback = !host_loopback;\n    cfg.restricted = restricted;")
    replace("net/slirp.c", "                         user->tftp_server_name, errp);",
            "                         user->tftp_server_name,\n"
            "                         !user->has_linex_host_loopback || user->linex_host_loopback, errp);")
    return updates


def patch(source: Path, shim: Path) -> None:
    if (source / "VERSION").read_text().strip() != "11.0.3":
        raise ValueError("Embedding patch requires QEMU 11.0.3")
    loopback_updates = host_loopback_updates(source)
    meson = source / "meson.build"
    content = meson.read_text()
    anchor = "  if target.endswith('-softmmu')\n    execs = [{"
    if content.count(anchor) != 1:
        raise ValueError("QEMU shared-library anchor changed")
    # Use the exact same per-target object graph and dependencies as the actual
    # emulator; exclude system/main.c's executable main(). No ELF conversion.
    addition = """  if target == 'aarch64-softmmu'
    shared_library('linex_qemu_aarch64', files('system/linex_jni.c', 'system/linex_dns_transport.c'),
      dependencies: arch_deps,
      objects: lib.extract_all_objects(recursive: true),
      include_directories: target_inc,
      c_args: c_args,
      link_args: link_args + ['-Wl,-z,max-page-size=16384', '-Wl,-Bsymbolic'],
      install: false)
  endif

"""
    content = content.replace(anchor, addition + anchor)
    # QEMU's prefer_static option serves both dependency selection and an
    # executable-only -static-pie/-static flag. Keep dependency archives, but
    # do not force static Android libc into the JNI shared object: Bionic's
    # log/android system APIs exist only as shared libraries in the NDK.
    static_anchor = "if get_option('prefer_static')\n  qemu_ldflags += get_option('b_pie') ? '-static-pie' : '-static'"
    if content.count(static_anchor) != 1:
        raise ValueError("QEMU static executable linker flag changed")
    content = content.replace(static_anchor,
        "if get_option('prefer_static') and cc.get_define('__ANDROID__') == ''\n  qemu_ldflags += get_option('b_pie') ? '-static-pie' : '-static'")
    # Android merges realtime functions into libc and has no librt or POSIX
    # shm_open. The serial VM uses anonymous RAM; our unsupported shm backend
    # returns ENOTSUP. Preserve the real feature probe instead of faking it.
    realtime_anchor = "  if not have_shm_open\n    rt = cc.find_library('rt', required: true)"
    if content.count(realtime_anchor) != 1:
        raise ValueError("QEMU realtime library probe changed")
    content = content.replace(realtime_anchor,
        "  if not have_shm_open and cc.get_define('__ANDROID__') == ''\n    rt = cc.find_library('rt', required: true)")
    # Meson's static find_library search does not use -L from c_link_args.
    # The approved build installs the Android libfdt archive into this prefix;
    # explicitly search that directory rather than any host architecture lib.
    fdt_anchor = "fdt = cc.find_library('fdt', required: fdt_opt == 'system')"
    if content.count(fdt_anchor) != 1:
        raise ValueError("QEMU libfdt library probe changed")
    content = content.replace(fdt_anchor,
        "fdt = cc.find_library('fdt', dirs: [get_option('prefix') / 'lib'], required: fdt_opt == 'system')")
    meson.write_text(content)
    shutil.copyfile(shim, source / "system/linex_jni.c")
    for name in ('linex_dns_transport.c', 'linex_dns_transport.h'):
        shutil.copyfile(shim.parent / name, source / 'system' / name)
    shutil.copyfile(shim.parent / 'linex_dns_transport.h', source / 'net/linex_dns_transport.h')
    shutil.copyfile(shim.parent / 'linex_slirp.inc', source / 'net/linex_slirp.inc')
    slirp = source / 'net/slirp.c'
    content = loopback_updates[slirp]
    replacements = [
        ('#include <libslirp.h>', '#include <libslirp.h>\n#include <linex_slirp_dns.h>\n#include "linex_dns_transport.h"\n#include "qemu/main-loop.h"'),
        ('    GSList *fwd;\n} SlirpState;', '''    GSList *fwd;
    bool linex_active, linex_stopping, linex_dns_dead;
    QEMUBH *linex_input_bh;
    QEMUTimer *linex_dns_timer;
    struct LinexVmPacket *linex_head, *linex_tail;
    size_t linex_bytes, linex_packets;
} SlirpState;

#include "linex_slirp.inc"'''),
        ('    slirp_input(s->slirp, buf, size);', '    linex_slirp_enqueue(s, buf, size);'),
        ('    g_slist_free_full(s->fwd, slirp_free_fwd);', '    linex_slirp_stop(s);\n    g_slist_free_full(s->fwd, slirp_free_fwd);'),
        ('    main_loop_poll_add_notifier(&s->poll_notifier);', '    main_loop_poll_add_notifier(&s->poll_notifier);\n    if (!linex_slirp_start(s, errp)) {\n        goto error;\n    }'),
    ]
    for anchor, replacement in replacements:
        if content.count(anchor) != 1:
            raise ValueError('QEMU main-loop networking anchor changed')
        content = content.replace(anchor, replacement)
    (source / "qapi/net.json").write_text(loopback_updates[source / "qapi/net.json"])
    slirp.write_text(content)
    # Bionic has no POSIX shm declarations. These backends deliberately return
    # ENOTSUP in the shim; the managed launch always uses anonymous guest RAM.
    oslib = source / "util/oslib-posix.c"
    content = oslib.read_text()
    anchor = '#include "qemu/osdep.h"'
    assert content.count(anchor) == 1
    oslib.write_text(content.replace(anchor, anchor + '\nint shm_open(const char *, int, mode_t);\nint shm_unlink(const char *);'))
    # Linux's optional async teardown clones a separate cleanup process. An
    # Android-managed VM must never start such a host helper. Exclude both its
    # object and command-line option instead of supplying a successful stub.
    system_meson = source / "system/meson.build"
    content = system_meson.read_text()
    anchor = "if host_os == 'linux'\n  system_ss.add(files('async-teardown.c'))"
    if content.count(anchor) != 1:
        raise ValueError("QEMU async teardown source selector changed")
    system_meson.write_text(content.replace(anchor,
        "if host_os == 'linux' and cc.get_define('__ANDROID__') == ''\n  system_ss.add(files('async-teardown.c'))"))
    vl = source / "system/vl.c"
    content = vl.read_text()
    anchors = [
        '#if defined(CONFIG_LINUX)\n        {\n            .name = "async-teardown",',
        '#if defined(CONFIG_LINUX)\n                if (qemu_opt_get_bool(opts, "async-teardown", false)) {',
    ]
    for anchor in anchors:
        if content.count(anchor) != 1:
            raise ValueError("QEMU async teardown option/call changed")
        content = content.replace(anchor, anchor.replace(
            '#if defined(CONFIG_LINUX)', '#if defined(CONFIG_LINUX) && !defined(__ANDROID__)'))
    vl.write_text(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--shim", type=Path, required=True)
    args = parser.parse_args()
    patch(args.source, args.shim)

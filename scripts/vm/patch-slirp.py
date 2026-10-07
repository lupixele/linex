"""Narrow DNS extension for pinned libslirp; exact anchors fail closed."""
import argparse
from pathlib import Path
import shutil


def patch(source: Path, jni: Path) -> None:
    updates = {}

    def replace(source, relative, anchor, addition):
        path = source / relative
        content = updates[path] if path in updates else path.read_text()
        if content.count(anchor) != 1:
            raise ValueError("Pinned libslirp anchor changed: " + relative)
        updates[path] = content.replace(anchor, addition)

    replace(source, "meson.build", "sources = [", "sources = [\n  'src/linex_dns.c',\n  'src/linex_slirp_dns.c',")
    replace(source, "meson.build", "install_headers(['src/libslirp.h'], subdir : 'slirp')",
            "install_headers(['src/libslirp.h', 'src/linex_dns.h', 'src/linex_slirp_dns.h'], subdir : 'slirp')")
    replace(source, "src/slirp.h", '#include "libslirp.h"',
            '#include "libslirp.h"\n#include "linex_slirp_dns.h"')
    replace(source, "src/slirp.h", "struct Slirp {", "struct Slirp {\n    struct LinexSlirpDns *linex_dns;")
    # Intrusive slirp queues cast the socket to its first two pointers. Keep
    # so_next/so_prev at offset zero; moving them corrupts every TCP/UDP queue.
    anchor = "    struct socket *so_next, *so_prev; /* For a linked list of sockets */"
    replace(source, "src/socket.h", anchor,
            anchor + "\n    uint64_t linex_dns_id;\n    bool linex_dns_closing;")
    # Checksum validation and reassembly precede this interception. The original
    # packet remains owned/freed by udp_input; only bounded bytes are copied.
    anchor = "    lhost.ss_family = AF_INET;"
    replace(source, "src/udp.c", anchor,
            "    if (slirp_linex_dns_udp_input(slirp, m, ip, uh, iphlen, len)) {\n        goto bad;\n    }\n\n" + anchor)
    # The SYN path establishes a per-socket core ID before using ordinary TCP
    # SYN/ACK/reassembly. Never enter tcp_fconnect for the virtual DNS endpoint.
    anchor = "        if ((tcp_fconnect(so, so->so_ffamily) == -1) && (errno != EAGAIN) &&"
    replace(source, "src/tcp_input.c", anchor,
            "        int linex_dns_result = slirp_linex_dns_tcp_connect(so, af);\n"
            "        if (linex_dns_result > 0) {\n            goto cont_input;\n        }\n"
            "        if (linex_dns_result < 0) {\n            tp = tcp_close(tp);\n            goto dropwithreset;\n        }\n\n" + anchor)
    anchor = "slirp_ssize_t slirp_send(struct socket *so, const void *buf, size_t len, int flags)\n{"
    replace(source, "src/slirp.c", anchor,
            anchor + "\n    if (so->linex_dns_id) {\n        return slirp_linex_dns_tcp_send(so, buf, len);\n    }")
    anchor = "void sofree(struct socket *so)\n{\n    Slirp *slirp = so->slirp;"
    replace(source, "src/socket.c", anchor,
            anchor + "\n    slirp_linex_dns_socket_free(so);")
    anchor = "void slirp_cleanup(Slirp *slirp)\n{"
    replace(source, "src/slirp.c", anchor,
            anchor + "\n    slirp_linex_dns_cleanup(slirp);")
    # Defense in depth: the virtual DNS endpoint must never fall through to
    # host socket resolution, even if an interception path changes upstream.
    anchor = "static bool sotranslate_out4(Slirp *s, struct socket *so, struct sockaddr_in *sin)\n{"
    replace(source, "src/socket.c", anchor,
            anchor + "\n    if (s->linex_dns && so->so_faddr.s_addr == s->vnameserver_addr.s_addr) {\n        return false;\n    }")
    names = ["linex_dns.c", "linex_dns.h", "linex_slirp_dns.c", "linex_slirp_dns.h"]
    for name in names:
        if not (jni / name).is_file():
            raise ValueError("Missing private DNS source: " + name)
    # Validate all upstream anchors before mutating the isolated source tree.
    for path, content in updates.items():
        path.write_text(content)
    for name in names:
        shutil.copyfile(jni / name, source / "src" / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--jni", type=Path, required=True)
    args = parser.parse_args()
    patch(args.source, args.jni)

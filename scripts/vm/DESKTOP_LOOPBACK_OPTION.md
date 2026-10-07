# Production-only SLIRP loopback policy

The new typed desktop launch requires `linex-host-loopback=off`. The existing
QEMU engine does not understand this private option and rejects the launch;
never remove the option to make an older engine boot. Keep production selection
disabled until the updated native engine and real isolation proof pass.

Follow-up owned by the native build author, after the frozen baseline CI:

1. In pinned `qapi/net.json`, add optional Boolean
   `'*linex-host-loopback': 'bool'` to `NetdevUserOptions`, with documentation
   saying omission preserves historical loopback access. Generated C names are
   `has_linex_host_loopback` and `linex_host_loopback`.
2. In `net/slirp.c`, append `bool host_loopback` to `net_slirp_init` immediately
   before its final `Error **errp` parameter. Immediately after initialization
   of the versioned `SlirpConfig`, set
   `cfg.disable_host_loopback = !host_loopback` before `slirp_new`.
3. Its single `net_init_slirp` call currently ends
   `user->tftp_server_name, errp)`. Pass
   `!user->has_linex_host_loopback || user->linex_host_loopback` before `errp`.
4. Make patch anchors count exactly once, update compiler/provenance hash pins,
   and preserve the existing fixtures' network arguments and JNI signature.
5. Add real tests proving the production option blocks guest connections to
   host-loopback TCP and UDP endpoints, while system DNS, verified public HTTPS,
   and the private Unix host forward to guest port5901 still work. Existing TLS
   proof uses host loopback deliberately and must keep the default policy.

Sources: [QEMU11.0.3 network implementation](https://raw.githubusercontent.com/qemu/qemu/v11.0.3/net/slirp.c),
[QEMU11.0.3 typed network schema](https://raw.githubusercontent.com/qemu/qemu/v11.0.3/qapi/net.json).
This file is an implementation handoff, not an active native patch or runtime proof.

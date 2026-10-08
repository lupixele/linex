# Networking checkpoint — completed 2026-10-08

Rootless SLIRP, Android DNS integration and actual guest DNS/HTTPS are implemented
and verified, including off/strict Private DNS. The new VM desktop ships in
v0.6.0-dev, with exact signed Android proof run37780260552. See
[release evidence](../VM-RELEASE-EVIDENCE.md) and [current progress](../PROGRESS.md).
The former RED networking drafts are implemented and pass; they are not blockers.

## Historical resume notes — 2026-10-06

The following snapshot records the earlier incomplete state; its next steps and
pending statements are historical, not the current work queue.

User-approved scope: SPEC-vm-engine.md rootless SLIRP and Android resolver bridge.
The Android serial engine gate is complete: run37421056855, source b811069,
all6instrumentation tests pass. The full VM desktop and networking remain pending.

User subsequently resumed work explicitly until a validated feature-complete APK
release. Packaged current PRoot APK at dist/packaged-2026-10-06/
linex-v0.5.2-dev-current.apk with checksum/build notes; no new VM release because
VM desktop/networking are not integrated. No device/account reset was requested.

All three networking agents reached the account usage limit. No network feature
is implemented or verified. Preserve these unfinished, uncommitted RED tests:

- scripts/vm/test_native_build.py: two new tests require statically defined
  slirp_new/slirp_input; the existing verifier/build does not provide this yet.
- vm-engine/src/test/java/com/linex/vm/network/DnsBridgeFrameTest.kt
- vm-engine/src/test/java/com/linex/vm/network/DnsWireTest.kt

These drafts can fail local test compilation/checks. Do not delete or skip them
to claim completion; finish their implementation in thin verified slices. Do not
publish them as a working network backend. The last green code commit is b811069.

Next slices:

1. Pin real libslirp4.9.5 source at390848f47391f412451b2d259d8527fabb51e5e3.
   Freedesktop download returned anti-bot HTML withHTTP200; validate actual tar
   contents/hash from the official QEMU GitHub mirror before adopting it. Build
   static against existing pinned GLib for both ABIs, enable QEMU user networking,
   retain no-helper wrappers/ELF checks and serial-only default. Native input
   changes require the full vm-engine.yml workflow; reuse must reject them.
2. Implement/query-test the proposed anonymous socketpair DNS frame and DNS wire
   validation. Proposed32-byte big-endian LXDN header: version/kind/transport/status
   at4..7, positive63-bit session generation at8, request ID at16, payload length
   at24, reserved0 at28. Queries≤1232bytes, answers≤8192bytes, errors empty. Review
   contract before native integration; pending request/queue/deadline bounds remain
   in VM_NETWORK_DESIGN.md. Android rawQuery must respect Private DNS; no public
   DNS fallback or exposed listener.
3. Build a separate DHCP/TCP/DNS/HTTPS fixture. Existing verified Alpine BusyBox
   and kernel remain the baseline. Matching Ubuntu ARM64 module package metadata:
   linux-modules-6.8.0-142-generic6.8.0-142.142, SHA256
   8998efe1301073bb3539063be12150772ef636f28de360972f827c0218751cff.
   This candidate was NOT adopted/byte-verified. Package file lists omit virtio_net
   and transports, suggesting built-in drivers; verify the actual kernel config
   before downloading unnecessary modules or adding decompression dependencies.
4. Repeat native helper audit and actual Android tests with networking enabled,
   including real Private DNS off/strict, DHCP/TCP/verified HTTPS and cancellation.
   Preserve original six passing tests. ARM64 phone proof/performance remains open.

Verified runtime evidence: dist/vm-android-run15/dist/vm-android-proof/. Host PID
observations use proc_pid_stat because Android's thread children files are absent
(ENOENT); runtime dumpability1 and the real0→1→0host-child control were verified.
The observations are bounded snapshots alongside source/helper audits, not exploit
containment. Normal Android/OEM memory reclamation can still occur.

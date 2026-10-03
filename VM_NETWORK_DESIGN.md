# VM engine networking design

Status: implementation detail of the approved [engine spec](SPEC-vm-engine.md),
recorded 2026-10-03. Design direction accepted; networking is not implemented or
verified. Implement after the existing Android JNI serial boot proof passes.

## Goal and current boundary

Provide rootless guest DHCP, outbound TCP and DNS that uses Android's resolver,
including Private DNS, inside the disposable Android-managed `:vm` service.
No root settings, TAP interface, VPN service, shell helper, host forwarding,
host share or public/loopback DNS listener is required. Existing PRoot instances
and the serial-only boot fixture remain unchanged.

`NativeVmService` currently supplies `-nic none`; the native recipe does not build
libslirp. Network support must be an explicit typed engine option, not arbitrary
QEMU arguments supplied by callers. Add `INTERNET` and `ACCESS_NETWORK_STATE` to
the library manifest when implementing it so the standalone test target has the
same permissions as the app.

## Source-grounded constraints

Use source-built libslirp **4.9.5**, upstream commit
`390848f47391f412451b2d259d8527fabb51e5e3`, as the candidate dependency. Pin its
archive SHA256 and every local patch before building it. Its Meson build can use
the existing pinned GLib dependency; preserve its BSD notices and corresponding
source. ABI builds must retain the no-helper wrappers and linked-library audit.

The [QEMU user networking options](https://www.qemu.org/docs/master/system/invocation.html)
provide unprivileged SLIRP networking, but `dns=` only advertises a virtual guest
nameserver. It does not select Android's system resolver. Upstream
[libslirp's public interface](https://gitlab.freedesktop.org/slirp/libslirp/-/blob/390848f47391f412451b2d259d8527fabb51e5e3/src/libslirp.h)
has no DNS query callback. Its
[socket translation](https://gitlab.freedesktop.org/slirp/libslirp/-/blob/390848f47391f412451b2d259d8527fabb51e5e3/src/socket.c)
redirects virtual DNS to a host resolver address. `guestfwd` alone is insufficient:
it handles TCP, and the enabled built-in DNS address/port is reserved.

[DnsResolver.rawQuery](https://developer.android.com/reference/android/net/DnsResolver)
is public from API29 and asynchronously returns DNS wire responses. Use
`DnsResolver.getInstance()` and `FLAG_EMPTY`, with no hardcoded public resolver or
app-controlled cleartext fallback. Android13's
[framework adapter](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-13.0.0_r74/framework/src/android/net/DnsResolver.java)
calls the system resolver. Its
[Private DNS implementation](https://android.googlesource.com/platform/packages/modules/DnsResolver/+/refs/tags/android-13.0.0_r74/res_send.cpp)
disallows cleartext fallback in strict mode; opportunistic fallback remains the
system's policy, not an additional Linex policy.

## Minimal transport and ownership

The service creates an anonymous **AF_UNIX SOCK_SEQPACKET socketpair** with
close-on-exec descriptors. Both ends stay in `:vm`; one is handed to the native
engine, and Kotlin owns the other. No discoverable address or listener exists.
The JNI contract must document descriptor duplication/closure explicitly and
accept only the service-owned descriptor, never a caller-selected socket path.

Add a narrowly scoped native extension that captures standard recursive DNS
queries to SLIRP's virtual resolver over **UDP and TCP**. libslirp continues to
own DHCP, NAT, transport state, checksums and guest packet output. Capture UDP
after IP reassembly/UDP validation, and bound TCP DNS length framing and each
connection's state. Do not implement a second general Ethernet/IP stack.

The native side allocates a monotonically unique request ID and stores the
guest transport context; Kotlin receives only a versioned frame containing a
session generation, request ID, transport kind, declared length and DNS bytes.
Replies carry the same identity and a result/error. DNS transaction IDs alone
cannot identify requests from different guest clients. Validate framing and
lengths independently on both sides, with an explicit byte order.

Only the QEMU event-loop thread may access libslirp or its request contexts.
That loop polls the native socket endpoint without blocking. Android resolver
callbacks queue replies to the Kotlin endpoint; they never invoke libslirp
directly or retain native pointers. A reply becomes usable only when the native
loop confirms that its session and request are still active.

The native extension is **unresolved implementation work**: public upstream APIs
do not provide this complete UDP/TCP bridge. Review the exact patch, concurrent
TCP connection handling, cancellation and memory ownership before integrating;
do not claim that adding a CLI option implements it. Keep unsupported helper
and forwarding options unreachable from the typed launch contract.

## Bounds, failure behavior and network changes

Initial limits: 64 pending requests, 1,232-byte DNS queries, 8,192-byte resolver
answers, at most 16KiB per transport frame and a 512KiB aggregate reply queue.
Use a 10-second monotonic request deadline, a bounded worker executor, and
nonblocking native writes. Saturation returns a bounded DNS failure; it must not
stall the QEMU main loop or create one host thread per query.

These are deliberate first-slice limits. Android13's
[JNI resolver adapter](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-13.0.0_r74/framework/jni/android_net_NetworkUtils.cpp)
uses fixed 4,096-byte query and 8,192-byte answer buffers; enforce query bounds
**before** passing guest bytes to `rawQuery`. Do not promise full 65,535-byte DNS
support. Validate one well-formed ordinary question, label/pointer bounds and
opcode; zone transfers and DNS updates are outside this bridge.

Preserve successful wire responses, including NXDOMAIN/NODATA and DNS flags.
For UDP, respect the request's negotiated payload limit; emit a structurally
valid truncated response with `TC` when necessary, so the guest can retry TCP.
Never truncate an arbitrary wire packet mid-record. TCP returns the complete
supported answer with DNS length framing. Errors/timeouts produce SERVFAIL
where a valid question is available; malformed input is rejected safely.

Use the current system default DNS network (`rawQuery` network=null) for each
request, matching ordinary outbound SLIRP sockets. Observe default network and
link-property changes; increment the resolver generation and cancel outstanding
`CancellationSignal`s when connectivity changes. Fail pending requests and let
guests retry on the new network. Do not retain the previous network's answers
in an app cache or bind the whole Android app to one network. Existing TCP
connections may fail on a network switch; new connections must recover.

On stop/death, cancel requests, stop the bounded executor, close both owned
endpoints and drop stale completions. Deadlines remain bounded when the guest
is paused. Diagnostics record counts, latency/error class and Private DNS
status, without DNS names, packet contents or provider secrets.

## API26–28 capability

Keep the existing API26 application floor. On API26–28, public
[Network.getAllByName](https://developer.android.com/reference/android/net/Network#getAllByName(java.lang.String))
can supply a constrained **IN A/AAAA** compatibility mode using the system
resolver. It cannot preserve arbitrary records, DNS TTLs, DNSSEC or distinguish
all negative response reasons. Unsupported types return REFUSED; synthetic
answers use a short conservative TTL and never claim authenticated data.
UnknownHostException must not be automatically represented as NXDOMAIN.

This mode must be explicitly reported as limited DNS. Blocking legacy calls
cannot be forcibly cancelled reliably: use a fixed-size pool, drop stale
results and never replenish blocked workers without a bound. Full wire-response
DNS support is initially API29+. API28 Private DNS still needs a real-device
compatibility check; no hidden APIs or public-DNS bypass are acceptable.

## Verification gates and implementation order

1. Complete actual Android JNI serial boot first. Then pin/build libslirp and
   verify the enabled user backend introduces no host children/helpers.
2. JVM tests cover frames, overflow/oversized rejection before resolver use,
   request identity, deadline/backpressure, malformed DNS compression, valid
   UDP truncation/TCP framing, response errors and cancelled/stale sessions.
3. Native tests exercise the real extension with concurrent UDP/TCP clients,
   reused DNS IDs, fragmented UDP, short TCP reads, invalid lengths, disconnects
   and stop while responses are pending; sanitizer/fuzz coverage targets parsers
   and retained transport contexts. A fake resolver only proves this boundary.
4. Android instrumentation must query through the actual system resolver and
   boot a networking fixture with virtio-net support. Preserve the old serial
   fixture and verify any additional kernel modules/assets by hash.
5. From the actual guest, prove DHCP address/default route/DNS advertisement,
   TCP connectivity and HTTPS with certificate validation, UDP/TCP DNS,
   A/AAAA and another ordinary record type on API29+. Verify deterministic
   negative replies against a controlled authoritative test domain.
6. Run with Private DNS **off and active strict mode**, including unreachable
   strict provider failure without Linex fallback; record read-only
   LinkProperties status. Private DNS configuration is a test-harness/manual
   action, never an app global-settings change. Also exercise Wi-Fi/mobile or
   emulator network switch, offline/recovery, pause/resume and stop/reboot.
7. Repeat before/during/after host-child observations and descriptor/thread
   bounds under DNS load. Keep API26–28 limited-mode tests and physical ARM64
   results separate from API33 x86_64 emulator evidence.

Integration estimate: four reviewable slices—dependency/backend, bounded
Android resolver transport, reviewed native UDP/TCP integration, and actual
networking fixture/device gates. The native extension and Android runtime
matrix dominate uncertainty; there is no defensible calendar completion
estimate until serial JNI boot passes and that extension prototype is reviewed.
Network completion remains pending until the actual runtime gates pass.

# Spec: vm-console

Status: direction reviewed and accepted by the parent agent, 2026-10-07, within
the user's approved VM modules and release authorization; no separate human
approval of every detail is implied. Traces to `VM_MODULES.md`.
Depends on the approved engine's private display/network contract. No VM desktop
rendering, guest GPU acceleration or phone performance is claimed implemented.

## Objective and assumptions

Display the VM desktop entirely inside Linex, in landscape fullscreen, with
per-instance custom geometry, existing physical keyboard/mouse support, direct
touch or touchpad simulation, supported refresh options and an optional readable
resource overlay. Preserve existing PRoot/X11 display behavior. Keep GPU metrics
absent when unavailable; host GPU presentation and guest GPU rendering are distinct.

## Smallest viable transport

Use **guest TigerVNC** through a service-owned private filesystem Unix socket
forwarded by the existing SLIRP engine to guest `10.0.2.15:5901`. Pinned QEMU11.0.3
supports `hostfwd=unix:<path>-10.0.2.15:5901` with libslirp>=4.7; the engine pins4.9.5.
The service alone constructs the rule. No caller chooses a host interface, guest
destination, port, listener path, command, or raw QEMU argument string.
[Pinned QEMU source](https://raw.githubusercontent.com/qemu/qemu/v11.0.3/net/slirp.c).

Create a fresh0700 session directory at `files/vmc/<32-lowercase-hex>/c`, with `c`
as the socket basename, using a generated identity bound to the launch generation.
The pinned QEMU rule parser splits a Unix path at its first hyphen: reject hyphens
and option separators in the complete generated path. Do not reuse an existing
`session-<token>` or `vm-instances` path in this rule. Stay within the Unix
path-length limit, apply0600 to the socket, verify its file type/ownership, and
connect through `LocalSocket` in FILESYSTEM namespace. Never use an Android TCP
listener or an abstract globally discoverable socket. A same-UID Binder contract
returns only the current instance/session endpoint; stale generations fail.
Cleanup removes only that owned session after clients close and QEMU exits.

TigerVNC listens on its guest NIC address, with password authentication enabled
and a per-launch random eight-character ASCII VNC credential generated with
SecureRandom. This credential's effective legacy VNC strength is eight bytes;
filesystem isolation is essential and VNC authentication is not encryption.
Do not use guest `-localhost` with a NIC forward: it would reject the forwarded
connection. Bind only the guest interface, disable other inbound forwards, and
do not export the console over LAN. [TigerVNC parameters](https://tigervnc.org/doc/Xvnc.html).

Supply launch settings through the existing private serial control channel after
the guest control service is ready. Define a bounded typed protocol with session
generation and explicit ACK/NACK, width/height/FPS and VNC credential. Do not
send a shell command or enable `eval`. Disable terminal echo before credentials
arrive, never log request credentials, and retain only secret-free acknowledgments.
The guest writes a0600 runtime password file and launches the non-root desktop.
Session rotation/reconnect must not expose previous-instance credentials.

## Native build comparison

| Approach | Android native additions | Guest additions |
|---|---|---|
| Selected TigerVNC via Unix SLIRP | No new graphics library; existing libslirp and Unix socket support, plus real runtime forwarding proof | TigerVNC/XFCE packages; no virtual DRM/display kernel module |
| QEMU VNC + virtio-gpu2D | Explicit `--enable-vnc --enable-pixman`, hash-pinned static Pixman, audited VNC crypto/build options and device models; optional JPEG/PNG/SASL/TLS disabled unless deliberately added | DRM virtio-gpu module and complete module dependency closure, Xorg modesetting stack, guest mode-change/input devices |

Current kernel has `CONFIG_DRM_VIRTIO_GPU=m`, not built-in. Virtio-gpu2D uses
software guest rendering; virgl/rutabaga require separate host GPU integration.
Do not claim enabling VNC or an Android GL surface enables guest acceleration.
[Official virtio-gpu requirements](https://www.qemu.org/docs/master/system/devices/virtio/virtio-gpu.html).

## RFB and presentation contract

Existing `RfbClient` supports RFB3.8 password auth, Raw/CopyRect/DesktopSize and
bounded input queues, but its connection is TCP-only. Add a typed transport seam
for streams plus owned close; keep loopback PRoot transport and add private Unix
VM transport. No global endpoint/password. Limit handshake/frame dimensions,
rectangle counts, decompressed bytes and in-flight work; malformed servers close
with an instance-scoped diagnostic. Never allocate from unchecked dimensions.

Existing `EmbeddedDesktopView` renders RFB frames through Bitmap/Canvas. The
native-X11-only GL surface is not reusable as an RFB transport. Add a bounded
GLES2 texture presenter for VM RFB, with one decoder-owned framebuffer and at
most two owned complete snapshots/latest-frame mailbox. Upload only on the GL
thread, present complete frames on vsync, drop superseded presentation snapshots,
and preserve buffer ownership through resize/close/context recreation. Reuse
texture storage when geometry is unchanged; no renderer reads pixels while the
decoder mutates them. Hide/background pauses new requests and rendering work.

Start proof with existing Raw/CopyRect. Record raw bandwidth and decode/upload
time before adding Hextile/Tight compression; compressed encodings require their
own bounded decoder tests. GL presentation improves host composition only; TCG
and guest/RFB copying can still limit scrolling/video performance.

## Geometry, input and metrics

Use the existing geometry validator and explicit maximum framebuffer budget.
Default new VM geometry1280x720, preserving aspect ratio inside the actual
fullscreen insets; support existing valid custom landscape sizes with no silent
rounding. The simplest first slice applies saved geometry on next desktop launch.
For live changes, implement negotiated RFB SetDesktopSize/ExtendedDesktopSize,
validate ACK/status and actual server dimensions, and rebuild buffers atomically.
Existing DesktopSize receive support alone is not client resize support.

Reuse landscape/fullscreen system-bar handling, sidebar controls, hardware
keyboard modifier/key-release tracking, mouse buttons/wheel/relative movement,
and direct/touchpad mapping. Tap=left click, two-finger tap=right click,
two-finger move=scroll; drags and keyboard focus remain consistent through scaled
letterboxing. Sidebar/dialog input never leaks to the desktop. Release held
keys/buttons on focus loss, disconnect, mode change and disposal. Android-reserved
keys remain subject to the OS; do not promise that every system shortcut reaches
the guest.

Retain existing15–144FPS choices as a maximum update/presentation target; default
new VM30FPS until measurement warrants more. Select a supported Android surface
refresh request and restore it on leaving. Higher selected cadence is not evidence
of actual guest frames. Measure presented completed frames over monotonic time.
Resource overlay consumes typed per-session telemetry from vm-app/engine: actual
presented FPS, guest RAM used/allocated, guest CPU utilization when supported.
Host VM RSS/CPU, if shown, are separately labeled. Omit GPU when unsupported.
The console does not invent RAM allocation; production engine allocation belongs
to vm-app, initially1GiB default, with a measured2GiB recommendation where device
memory allows, presets and a validated custom field.

## Commands, structure and code style

Existing regression command:
`./gradlew :app:testDebugUnitTest :vm-engine:testDebugUnitTest :app:lintDebug`.
Planned real console verification after implementation:

```sh
python3 scripts/vm/test-desktop-image.py --manifest dist/vm-desktop-image/manifest.json --qemu qemu-system-aarch64 --output dist/vm-desktop-proof
./gradlew :vm-engine:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Typed engine endpoints belong in `vm-engine`; reusable RFB transports, owned
frame buffers and the GLES presenter live in the independent `vm-console`
Android library. The existing app decoder/input controller adapts to that
library while retaining its loopback transport. Test guest fixtures/probes live in `scripts/vm/`.
Follow existing explicit ownership and immutable settings conventions:

```kotlin
data class VmConsoleGeometry(val width: Int, val height: Int, val targetFps: Int)
// The service owns its endpoint; the viewer owns and closes its connection.
```

## Runtime acceptance and ordered slices

1. Native forwarding proof: real guest TCP server, host filesystem Unix client,
   exact bytes both directions, listener permissions, no Android TCP listener,
   client disconnect/reconnect and session cleanup. Prove this with the actual
   Android JNI build before trusting Linux-host-only forwarding.
2. Console proof: real Xvnc framebuffer, failed/wrong password, known pixel pattern,
   keyboard/pointer/scroll confirmed inside guest, and two independent instance
   identities. Frame acquisition alone is not input proof.
3. App display proof: landscape fullscreen actual screenshot, custom resolution
   reflected by guest and renderer, resize failure recovery, pause/resume,
   disconnect/stop/restart, rotation/context recreation, stale-session rejection,
   direct/touchpad and hardware input. Preserve all PRoot display regressions.
4. Stability/performance proof: repeated browser scrolling/video and30-minute
   session, bounded FD/thread/buffer counts and measured decode/upload/presented
   FPS. Record browser launch and guest OOM/host death separately; do not assert
   no tearing,60FPS or Snapdragon suitability from compilation or emulator boot.

Always retain actual frame/input/lifecycle evidence and private credentials.
Never expose unauthenticated host ports, pass arbitrary caller arguments, disable
browser isolation, require Android root, or invent unsupported GPU metrics.
Parent accepted the direction; individual slices require review/runtime evidence. Open issues: Android Unix
SLIRP forwarding runtime, new GL presenter and real browser performance.

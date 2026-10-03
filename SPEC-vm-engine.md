# Spec: vm-engine

Status: explicitly approved by the user on 2026-10-03 for implementation,
including the native dependency and Linux CI build. Implements the first module
in VM_MODULES.md.

## Objective

Run an ARM64 Linux kernel using full-system QEMU inside an Android-managed,
nonexported `:vm` service. Linux forks must stay inside the emulated kernel rather
than creating Android child processes. This is the foundation for the user's
permanent no-root desktop; a kernel boot alone is not a finished desktop.

Assumptions: Android API26+, ARM64 phones initially; CPU emulation without KVM;
one active VM per app; a new VM instance later rather than automatic conversion.
Existing PRoot instances remain usable. These follow the approved module map.

## Tech stack and build approach

- Kotlin, existing AGP8.3.2, compile/target34 and NDK26.1.10909125.
- New Android library module `vm-engine`, JNI shim and QEMU11.0.3
  `aarch64-softmmu` with TCG. Match this version's initialization and lock ABI.
- Reuse maintained Termux Android portability patches as audited build inputs;
  do not copy its absolute Termux paths or ship an unmanaged executable.
- The pinned portability recipe uses NDKr30. Use that pinned Linux toolchain for
  QEMU builds, while leaving the existing app's NDK26 configuration intact.
  Verify JNI/link compatibility, packaged dependencies and 16KiB alignment;
  do not assume the old native toolchain can build the modern recipe.
- QEMU source archive SHA256:
  `da5fcffc32762820568b828ed430a728864d34d50b6d2f30358597760cbb0523`.
  Pin every dependency/patch and publish corresponding source and notices with
  any distributed binary. No unversioned binary downloads.
- A Linux GitHub Actions runner builds the engine. Existing Windows JDK/SDK
  remain the app build tools; no WSL installation or host reboot is required.
- An x86_64 Android build may provide emulator integration coverage while
  emulating the same ARM64 guest. It does not prove Snapdragon performance.

## Commands

Existing app regression/build commands, from the repository root:

```powershell
$env:JAVA_HOME = 'P:/Android/Java17/PFiles64/Microsoft/jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
```

Deliver these entry points as part of the implementation; they do not exist yet:

```bash
python3 scripts/vm/build-fixture.py --output dist/vm-fixture
bash scripts/vm/build-engine.sh --abi arm64-v8a --output dist/vm-engine
bash scripts/vm/build-engine.sh --abi x86_64 --output dist/vm-engine
./gradlew :vm-engine:testDebugUnitTest :vm-engine:connectedDebugAndroidTest --no-daemon
```

The build script must require the pinned configured NDK, fail on unsupported hosts or
hash mismatches, and return a nonzero status on build/verification errors. The
instrumentation command requires an Android device/emulator; absent hardware
must be reported as untested rather than a passed gate.

## Project structure

- `vm-engine/src/main/java/com/linex/vm/`: validated launch contract, service,
  Binder client, QMP control and bounded diagnostics.
- `vm-engine/src/main/jni/`: version-matched embedding shim, no fork/exec launcher.
- `vm-engine/src/test/`: configuration, protocol and state/lifecycle tests.
- `vm-engine/src/androidTest/`: genuine kernel boot and host-process observation.
- `scripts/vm/`: pinned native build and verified fixture generation.
- `.github/workflows/vm-engine.yml`: manually dispatched build/proof jobs with
  read-only repository permissions, bounded runtime and evidence artifacts.
- `SPEC-vm-engine.md`: engine boundary contract; `tasks/` plans after review.

## Interface and code style

Use explicit Kotlin types and argument arrays; never accept a raw user-supplied
QEMU command or interpolate a shell command. For example:

```kotlin
data class VmBootRequest(
    val instanceId: String,
    val verifiedKernel: File,
    val verifiedInitramfs: File,
    val memoryMiB: Int,
    val vcpuCount: Int,
)
```

The service independently validates IDs, private canonical paths, actual regular
files and asset digests. Launch limits for the fixture: 256–1024MiB RAM, 1–2 CPUs.
Desktop allocation limits are a later vm-app decision with host overhead included.
JNI consumes validated argv on a worker thread. Preserve the existing app's style.

One launch owns one fresh `:vm` PID and session token. Duplicate active starts
fail clearly; stale callbacks/stop requests cannot affect a newer session. Terminal
events distinguish requested stop, startup failure, unexpected death and kernel
panic. `BOOTING` becomes guest-ready only on a verified fixture handshake; QMP
socket availability alone does not mean Linux booted. Binder death is surfaced.

Pause/resume/stop use private QMP, with negotiated capabilities and bounded
requests. Graceful shutdown gets a deadline, then only the owned managed service
process may be terminated. Restart always uses a fresh PID because QEMU has
process-global state and native exit paths; never restart it in the same process.

QMP/serial sockets live inside app-private directories with owner-only access;
no public TCP listeners, host shares, root commands or helper executables. The
service is nonexported; verify caller UID where applicable. Keep kernel output
bounded and scoped to the instance, exclude secrets and redact private paths.

The engine exposes a private display endpoint contract for vm-console; no public
VNC listener or selectable VM desktop ships in this milestone. Existing native
X11 acceleration does not imply guest GPU acceleration.

## Testing strategy and success criteria

1. Reproducible hash-checked Android native builds, loadable in the managed
   service, with actual linked dependency inspection and no Termux path reliance.
   A first serial-only boot slice may omit display/network dependencies; those
   remain pending rather than being replaced with success-returning stubs.
2. Real Linux boot using a verified kernel and generated initramfs, reporting
   kernel architecture/version and `LINEX_VM_BOOT_OK` from guest `/init`.
3. Guest creates 64 simultaneous persistent children. Observe all service host
   threads' child-PID lists before/during/after; no Android children appear.
   Record service PID, guest child count and bounded host thread count. Audit
   enabled QEMU backends for helper spawn: periodic sampling alone is not proof.
4. Pause/resume, bounded stop, process death and a second boot with a fresh PID
   work under instrumentation. Reject corrupt assets, invalid limits/paths and
   concurrent starts. No lingering owned service after stop.
5. Unit tests cover QMP framing, errors, disconnects/timeouts and lifecycle races;
   instrumentation tests prove JNI/kernel behavior. Mocks cannot satisfy boot.
6. Rootless SLIRP network implementation and DNS bridge belong to this engine:
   verify DHCP, TCP and DNS with Android Private DNS on/off before calling network
   complete. Guest-visible `dns=` alone is not an Android resolver bridge.
7. Existing app tests/build/lint continue passing; no PRoot metadata or rootfs is
   migrated, removed or silently reclassified.
8. Preserve evidence: source/dependency manifest, build logs, serial boot log,
   process observations and actual device/emulator identity. Keep unmet gates
   explicitly pending. No APK release advertising VM support before these pass.

## Boundaries and limitations

- Always: validate assets/configuration, keep diagnostics bounded, preserve user
  data, review changes and run checks appropriate to each slice.
- Ask first: change the approved module boundaries, migrate existing instances,
  install host system components requiring reboot, or require privileged access.
- Never: bypass Android policy/signatures, weaken browser isolation, use user-mode
  QEMU as the full-system backend, expose control sockets, or claim a mock boot.
- The new QEMU dependency and scoped CI build are necessary to the approved VM
  direction; this specification makes their implementation concrete for review.
- Android/OEM memory reclamation remains possible. CPU speed, guest GPU, video
  decode and desktop FPS need measurements; no all-phone performance guarantee.
- No phone is attached. CI emulator proof and physical ARM64 proof must remain
  separately identified; missing device evidence is an open completion gate.

Sources: [QEMU ARM virt](https://www.qemu.org/docs/master/system/arm/virt.html),
[QEMU11 entry point](https://github.com/qemu/qemu/blob/v11.0.3/system/main.c),
[Android portability recipe](https://github.com/termux/termux-packages/blob/27da60be397a6e9eb898bba8d03bcd648782ccd5/packages/qemu-system-x86-64-headless/build.sh).

# vm-engine tasks

- [x] Verified boot fixture (independent)
  - Real Linux CI proof passes, including expanded distinct-PID and child-clear
    evidence in run37126054694. Ten focused Python tests pass. This is host proof.
  - Files: scripts/vm/build-fixture.py, fixture unit tests, boot test harness.
  - Acceptance: pinned assets verified, deterministic safe initramfs, actual
    guest init handshake and64 persistent children; no unsafe host extraction.
  - Verify: Python unit tests; Linux host QEMU boot (fixture evidence only).
- [ ] Native Android engine build (independent)
  - Files: scripts/vm/build-engine.sh, embedding shim/patch, source manifest,
    .github/workflows/vm-engine.yml.
  - Acceptance: genuine QEMU11 ARM64guest JNI library for both host ABIs,
    hash-pinned dependencies, no hardcoded Termux prefix/helper processes.
  - Verify: Linux CI cross-build, ELF/JNI/dependency inspection.
- [ ] Managed boot service (depends on native build and fixture for runtime)
  - Implemented and source reviewed; fourteen request/host observation tests pass.
    Actual JNI-backed service boot remains pending native build.
  - Files: vm-engine/build.gradle.kts, manifest, NativeVm.kt,
    NativeVmService.kt, VmBootRequest.kt (+ focused request tests).
  - Acceptance: nonexported :vm service, sameUID controls, independently validated
    private paths/hashes/limits, foreground ownership, clear startup failures.
  - Verify: validation tests, Android library build/lint; genuine instrumentation
    boot after the native library is available.
- [x] Build integration checkpoint
  - Files: settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml.
  - Acceptance: engine library builds independently; app remains functional.
  - Verify: library tests/build/lint and existing app regression/build checks.
  - Result:112app+22VM tests pass, library/app/testAPK assemble;VM lint0errors5warnings.
    Instrumentation manifest verified targetSDK34. Missing JNI is not a boot pass.
- [ ] QMP control and lifecycle proof (depends on managed service)
  - Implemented;8protocol tests pass (framing, deadline, errors/events). Actual
    two-boot/control/fork64 proof is compiled and awaits Android emulator execution.
  - Files: QMP protocol/client and tests, service instrumentation tests.
  - Acceptance: bounded framing/timeouts, valid capabilities, pause/resume,
    owned stop, stale-session rejection, fresh PID restart and failure cleanup.
  - Verify: unit protocol tests plus real connected boot/guestfork64/hostchildren
    evidence. No device → connected test remains pending.
- [ ] Rootless networking (depends on boot/control)
  - Acceptance: in-process SLIRP and Android DNS resolver bridge; actual guest
    DHCP, TCP, HTTPS and Private DNS on/off proof.
  - Verify: connected networking instrumentation and physical-device evidence.
  - Files: selected after boot proof identifies the minimal integration boundary.
- [ ] Engine acceptance checkpoint
  - Acceptance: every engine success criterion recorded with real evidence;
    source/licenses packaged before binaries are distributed.
  - Verify: SPEC-vm-engine.md success criteria reviewed individually. Then begin
    vm-images/vm-console specifications; existing instances remain unchanged.

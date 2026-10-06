# vm-engine tasks

- [x] Verified boot fixture (independent)
  - Real Linux CI proof passes, including expanded distinct-PID and child-clear
    evidence in run37126054694. Ten focused Python tests pass. This is host proof.
  - Files: scripts/vm/build-fixture.py, fixture unit tests, boot test harness.
  - Acceptance: pinned assets verified, deterministic safe initramfs, actual
    guest init handshake and64 persistent children; no unsafe host extraction.
  - Verify: Python unit tests; Linux host QEMU boot (fixture evidence only).
- [x] Native Android engine build (independent)
  - Run37415996410 builds both ABIs and passes the strengthened daemon-helper
    audit. Android-managed JNI/kernel proof now passes in run37421056855.
  - Files: scripts/vm/build-engine.sh, embedding shim/patch, source manifest,
    .github/workflows/vm-engine.yml.
  - Acceptance: genuine QEMU11 ARM64guest JNI library for both host ABIs,
    hash-pinned dependencies, no hardcoded Termux prefix/helper processes.
  - Verify: Linux CI cross-build, ELF/JNI/dependency inspection.
- [x] Managed boot service (depends on native build and fixture for runtime)
  - Implemented and source reviewed; fourteen request/host observation tests pass.
    Actual remote invalid-launch Binder tests pass onAPI33/target34 (run12).
    Run37421056855 proves real JNI-backed boot twice,64guest processes and
    complete before/during/after host observations; actual invalid launches rejected.
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
- [x] QMP control and lifecycle proof (depends on managed service)
  - Run37421056855 passes all6actual Android tests: pause/resume, clean stop,
    fresh PID restart, guest-child clear, owned force stop, concurrent/stale controls
    and a real host-child observation positive control. Source b811069.
  - Implemented;8protocol tests pass (framing, deadline, errors/events). Actual
    two-boot/control/fork64 proof is compiled and awaits Android emulator execution.
    Separate remote Binder tests cover rejected launch, stale/duplicate controls,
    owned force stop and concurrent valid first STARTs; test APK compilation passes.
    Run11 never reached tests due to broken Unix Gradle launcher. Official wrapper
    and single-process proof runner repaired; bounded exit-history evidence added.
    Run12 executes tests but AAPT strips/decompresses the fixture .gz asset;
    opaque asset alias and incremental Sync input91b2333 preserve pinned bytes.
    Actual packaged asset/JNI preflight75cef79 passes locally; run37417801412 retries.
    Run13 APK preflight passes in CI, but host observation is incomplete.
    dc04e0f adds verified-visibility PID-stat fallback and method/detail evidence;
    a97bc12 adds a real instrumentation-only host-child positive control.
    Run37420219442 reuses native artifacts only after immutable provenance checks.
  - Files: QMP protocol/client and tests, service instrumentation tests.
  - Acceptance: bounded framing/timeouts, valid capabilities, pause/resume,
    owned stop, stale-session rejection, fresh PID restart and failure cleanup.
  - Verify: unit protocol tests plus real connected boot/guestfork64/hostchildren
    evidence. CI emulator connected tests pass; physical ARM64 evidence separate.
- [ ] Rootless networking (depends on boot/control)
  - Source-grounded VM_NETWORK_DESIGN.md records anonymous socketpair transport,
    Android resolver bounds and unresolved native libslirp UDP/TCP DNS extension.
    No network backend is implemented; existing fixture remains serial-only.
    Networking agents hit the account usage limit after beginning RED tests.
    Preserve local unfinished tests; resume tasks/pending-network.md.
  - Acceptance: in-process SLIRP and Android DNS resolver bridge; actual guest
    DHCP, TCP, HTTPS and Private DNS on/off proof.
  - Verify: connected networking instrumentation and physical-device evidence.
  - Files: selected after boot proof identifies the minimal integration boundary.
- [ ] Engine acceptance checkpoint
  - Acceptance: every engine success criterion recorded with real evidence;
    source/licenses packaged before binaries are distributed.
  - Verify: SPEC-vm-engine.md success criteria reviewed individually. Then begin
    vm-images/vm-console specifications; existing instances remain unchanged.

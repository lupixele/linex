# Linex v0.6.0-dev verification

The new VM runs an ARM64 Linux kernel and guest processes inside a private Android
managed service. Existing PRoot storage and its development signing certificate
are retained. This is a development release; physical-phone performance and
long-running interactive Firefox/video workloads remain unmeasured.

## Immutable subjects

- Native Android engine producer: run37648306873,
  source6f27c7123f89251048be272e6ce6e0bbd4095a92; both ABIs and15 actual Android tests.
- Updated Debian factory/kernel producer: run37756061377,
  sourcebe88885e836edbd66aca575175367497eea11657.
- Candidate manifest SHA256:
  b403701a08f2e67e5fa72f5a06e87544f30fad69c287352efe853faf864e0b32.
- Debian package/source lock SHA256:
  ce683914608e5ac749e7006aeb1077ac789e0d10cfe9852b8a7c1affc338020d.
- Factory4 GiB raw ext4 SHA256:
  4f2cf6385f02b3ef1651f3b7f81a3d7a0b1ab8fdb9a8b1aec18c18376ef38e20.
- Linux6.8.0-146.146 kernel SHA256:
  6001f54164a5201cd5a80e0a09bb05c43fa6e910a0abfe0f5a4e8d7d781cc737.
- Android signing certificate SHA256:
  3e4f665b952b7a741053bb9db6299687d92f444257b6a0c302b3d5c41dc96ecc.

The kernel source audit uses authenticated Ubuntu archive metadata, exact source
and config correspondence, and concrete fixes absent in the older142 kernel.
See [the dated audit](scripts/vm/KERNEL-AUDIT-20261008.md); it is not a promise
that all vulnerabilities are absent.

## Actual runtime evidence

- Exact signed release APK [run 37780260552](https://github.com/lupixele/linex/actions/runs/37780260552)
  passed on a fresh Android 13 emulator. Workflow source
  fb67f51d01209cf4466b46ca1776a9fe99fdd0a4 independently pins the APK runtime
  source 87d3e6c778e7226182c708f0da3bb534f09fb02f. Installed app SHA256 is
  d791f7374c2bc4f7e60acb85b014ba5ad91abd7505dcc9c3988d05a54b692e33;
  installed test APK SHA256 is
  6856b06bebf8bc95e6f3c9d23a84d7083401b5815e51ea70325bfafd7aab8509.
  Both signatures match the retained development certificate. Two fresh VM
  processes (6784 and 6978) produced 19 and 18 framebuffer updates respectively,
  visible XFCE content, non-root headless Firefox and verified HTTPS. Private
  console authentication, frame mutation, pause/resume, startup rejection
  recovery, bundled catalogue identity, clean stop and disk retention pass.
  Four complete process samples per boot have zero host children. The real
  instrumentation test passed without skips in 268.945 seconds. Screenshots and
  JSON were downloaded and independently checked; these frame counts are not FPS.
- Android13 app desktop run37758612885/source890ab2d passed two freshVM process
  boots,1280×720 authenticated private Unix RFB, real framebuffer changes,
  non-root headless Firefox153.4 ESR screenshot, default-CA verified HTTPS,
  pause/resume, confirmed clean process exit and retained instance disk.
  Four complete process samples per boot show zero host children; real native
  ownership rejection recovers before the successful launches. Its initial PNG
  was captured before XFCE painted, so the final APK check additionally requires
  visible desktop content and the matching bundled catalogue.
- Android13 console run37758315170/source1411277 passes7 actual tests including
  silent-peer blocked-read cancellation, real EGL pixels, resize and context
  recovery. It does not measure physical Adreno performance.
- Installer run37753164499/sourcedae5119 passes7 actual tests each onAPI26/API33,
  including mutable clone, interrupted setup, path/ownership/socket guards,
  installation reuse and safe delete.
- Private DNS run37646926429 proves guest DNS/HTTPS with Private DNS off and
  strict dns.google on distinct freshAndroid13 boots using identical APK bytes.
  Integrated networking/native hooks are unchanged in this APK.
- Native loopback isolation run37647791186 tests actual SLIRP v4/v6 direct and
  gateway bypass negatives plus bidirectional private Unix forwarding under
  sanitizers. The desktop server is not exposed as a phone TCP port.
- Storage run37572594902 verifies real guest-file persistence over restart,
  forced-stop ext4 recovery and factory immutability on Linux-host boots.
  Android app disk retention does not independently prove guest-file persistence.
- Host desktop run37756061377 verifies updated-kernel input, non-root HTTPS and
  headless Firefox, clean stop and readonly filesystem integrity checks.

The release includes the compact final receipt as release-proof.json.
A build, checksum, GLES test or headless Firefox screenshot
alone is not proof of smooth interactive browser/video performance on a phone.

## Source assets

The release supplies actual patched QEMU/component sources, captured configs and
relinkable objects for both ABIs; complete254 Debian source-package archives;
kernel source/packaging/config/licenses and signed provenance; pinned upstream
Termux-X11 sources/dependencies; and PRoot/talloc/shmem sources with the immutable
Termux build recipe snapshot. Each source asset contains rebuild instructions,
hash manifests and the limits of the surviving producer receipts. No Linex
signing key is included. Source artifacts are optional for normal installation.

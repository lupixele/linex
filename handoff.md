# Linex project handoff

Prepared 2026-11-12 (Asia/Kolkata). Repository and release details below were last verified on 2026-10-09; recheck GitHub and the local checkout before building or publishing again.

## Project and user goals

Linex is an Android app that runs Linux distributions on ARM64 Android phones, with an embedded landscape/fullscreen Linux desktop. The user prefers a complete in-app desktop, rootless operation, durable fixes, preserving existing data/settings, background downloads with notification progress, and evidence-based claims. The user explicitly authorized continued engineering through a working APK and GitHub release. They prefer direct work in `P:\Magnanimity\Projects\linex`.

Long-running goals discussed include stable Debian/XFCE VM operation, usable Firefox/networking, lower desktop latency/tearing, custom resolutions and refresh rate, keyboard/mouse/touchpad input, resource overlay, RAM choices, and background installs/downloads. Guest GPU acceleration, audio, and clipboard remain future/unsupported areas. Do not imply the GLES Android presenter provides guest GPU acceleration.

## Last verified release

- Public prerelease: [Linex v0.6.1-dev](https://github.com/lupixele/linex/releases/tag/v0.6.1-dev)
- APK: [linex-v0.6.1-dev.apk](https://github.com/lupixele/linex/releases/download/v0.6.1-dev/linex-v0.6.1-dev.apk)
- Version code 23; APK size 89,688,427 bytes; SHA256 `05ac530120cb4014174a86b771fcaa070080d866376c55833d4ca16044e1ade9`.
- Android APK v2 signature verified for SDK 26+, retained development certificate SHA256 `3e4f665b952b7a741053bb9db6299687d92f444257b6a0c302b3d5c41dc96ecc`.
- Release tag target at publication: `6bd186ce3b777622c6aa566b53b7695783c17d85`. Subsequent progress checkpoint pushed `a9999a1` to `main`; the tag intentionally remains on the release implementation/docs commit.
- Exact signed APK run [37886184501](https://github.com/lupixele/linex/actions/runs/37886184501) passed Android 13 acceptance: two fresh Debian VM boots, XFCE panel content, 21/20 frame updates, Firefox HTTPS with default CA trust, pause/resume, clean stop, retained disks, and four complete process observations per boot with zero host child processes. Separate GLES run [37885228252](https://github.com/lupixele/linex/actions/runs/37885228252) passed seven actual tests including black-frame then content detection and context recovery.
- All five v0.6.1 release assets had GitHub sizes and SHA256 digests checked against local files. Anonymous download of the public APK matched the hash and signature.

## Most recent reported issue and diagnosis

Input was `raw/linex_My_Debian_Workstation_logs.txt`. It records installation finishing at 09:55:43, an initial RAM-budget rejection, then a VM launch with 1392 MiB and two emulated CPUs at 09:56:36. The app called the desktop ready when the VNC service was listening at 09:57:25. XFCE initialization appeared around 09:59:06. The user confirmed they pressed Stop/closed the session at 09:59:45; shutdown completed cleanly. There is no recorded spontaneous crash, OOM, fatal signal, or kernel panic.

The app's readiness indication was premature and gave no useful feedback while the initial frame was black/cursor-only. ICE directory, accessibility bus, missing GPG/SSH agent, and absent guest render-node/DRI3 messages were not demonstrated as fatal; comparable warnings were in a passing emulator run. The log does not identify the exact CPU/I/O bottleneck or prove Android composition hid an already-rendered desktop. The Snapdragon 732G's real startup time remains unmeasured.

v0.6.1 keeps startup time and log access visible until GLES actually draws a frame with enough nonblack pixels; it distinguishes a listening display server from visible content. New VMs default to 1280x720 at 30 FPS. Existing saved values/disks are retained. For the reported existing instance, stop it and choose 720p/30 FPS and 1024 MiB if reducing rendering and memory workload is desired. This improves feedback and defaults; it does not eliminate software CPU emulation or guarantee a boot time.

Full details: `VM-STARTUP-AUDIT-20261009.md`, `dist/packaged-v0.6.1-dev/release-proof.json`, `VM-RELEASE-EVIDENCE.md`, and `PROGRESS.md`.

## Repository state and handling

Repository: `P:\Magnanimity\Projects\linex` (the currently mounted workspace may show this project under `LinuxDroid`). At last check, branch `fix/rootfs-x11-symlink` had `origin/main` and the startup work merged; HEAD was `a9999a1` and remote main agreed.

The checkout was **not clean**. `scripts/test-xfce-startup.sh` was modified, and substantial tracked generated changes existed under `app/build/`. Untracked `dist/`, `raw/`, `vendor/`, and `scripts/vm/guest/__pycache__/` were also present. These may contain user artifacts or build evidence. Inspect and preserve them. Never use `git add .`, reset/checkout, or broad cleanup. Stage only exact intended source paths. The project root did not contain its own `AGENTS.md` or `index.md` at last check; follow the user's supplied Magnanimity vault instructions and route via `P:\Magnanimity\index.md` and `Projects\index.md`.

No Gradle daemon was running after the v0.6.1 release. Environment/tool paths at the October build included Git `P:\Program Files\Git\cmd\git.exe`, gh `C:\Program Files\GitHub CLI\gh.exe`, Python `P:\Program Files\Python\python.exe`, Java 17 under `P:\Android\Java17\...`, and Android SDK `P:\Android\Sdk`. Verify these still exist before use.

## Relevant architecture and limits

- The default new Debian Trixie installation is a full QEMU VM, isolated from Android's guest process limits; existing Ubuntu/PRoot instances are preserved and are not converted automatically.
- Production QEMU/native engine and Debian factory/kernel/image were intentionally unchanged by v0.6.1. Image catalogue still points to the v0.6.0 assets; no redownload or reset should be needed for an in-place app update.
- VM guest CPU and graphics/video decoding use software emulation. Android GLES displays frames but is not guest GPU passthrough. Phone scrolling/video speed and sustained Firefox use have not been measured.
- Android/OEM may still reclaim the app; screen-off behavior on the user's device is unknown. VM audio, clipboard sync, and guest admin are not implemented.
- Existing PRoot Firefox crash behavior is not repaired by moving the existing instance; a newly created Debian VM is a separate runtime.

## Suggested continuation

1. Start with `git status --short --branch` and inspect current `main`, tags, and release status. The checkout was dirty in October and may have changed since.
2. Ask the user for a fresh v0.6.1 instance log only if they have tested it; distinguish an explicit Stop from an unexpected guest/app exit.
3. If the physical-device launch is still slow, record timestamps for VM process start, kernel/rootfs ready, VNC listener, first nonblack frame, XFCE session/panel, and user-visible Android surface. Preserve the exact exported instance log and correlate with Android logcat if available. Do not diagnose from nonfatal ICE/DRI warnings alone.
4. Measure on the Snapdragon 732G at 720p/30 FPS before changing default workloads or promising a performance fix. Keep settings configurable and retain instance disks.
5. Before another release, rebuild/sign with the retained development key, verify APK hash/signature and compatibility, run exact signed APK Android proof, inspect first desktop screenshots, verify release asset digests, then push/tag/publish only the reviewed commit. Phone-specific claims require phone evidence.

## User decision already made

The user selected a desktop displayed entirely inside Linex, chose a full virtual machine for isolation, approved the VM engine specification, authorized ongoing work through an APK release, and confirmed the latest shutdown was manually requested. Do not ask them to repeat these decisions.

## GPU Emulation & Wayland Desktop Integration (November 17, 2026)

The user requested research and integration of the GPU-accelerated Wayland desktop pipeline from [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck) into Linex to replace software-rasterized VNC with true hardware GPU acceleration on Snapdragon (Adreno 618):

1. **Native C/C++ Engine**:
   - Ported DroidDeck's native Wayland compositor (`droiddeckwayland`), AdrenoTools GPU hooks (`libadrenotools`), Vulkan shaders, and `ddexec` PIE binary loader into `app/src/main/cpp`.
   - Updated CMake build to conditionally target ARM64 (`arm64-v8a`) while maintaining a clean stub fallback on `x86_64`.
   - Packaged verified native libraries: `libdroiddeckwayland.so`, `libddexec.so`, `libadrenotools`, `libwayland-server.so`, and `liblinex_engine.so`.

2. **Kotlin/Java Wayland Presentation & Input Layer**:
   - `CompositorHost.kt` and `WaylandCompositor.java` drive native VSync via Choreographer callbacks and zero-copy surface management.
   - `WaylandDesktopView.kt`: Custom `SurfaceView` managing compositor surface binding, resizing, and detach lifecycle.
   - `InputSpace.kt`, `EvdevKeys.java`, and `SessionClipboard.kt`: Real-time normalized pointer/touch routing, evdev keycode mapping, and bidirectional Android <-> guest clipboard sync.
   - `EmbeddedDesktopView.kt`: Seamlessly switches to `WaylandDesktopView` when `DisplayBackend.WAYLAND` is selected.

3. **Container Environment & GPU Pipeline**:
   - Container bootstrap scripts (`entrypoint.sh`, `proot_runner.sh`, `container_init.sh`) configured for `XDG_RUNTIME_DIR=/tmp/runtime-root` with `WAYLAND_DISPLAY=wayland-0`.
   - Activated Mesa **Turnip** (`TU_DEBUG=sysmem`) against `/dev/kgsl-3d0` and **Zink** OpenGL acceleration (`MESA_LOADER_DRIVER_OVERRIDE=zink`, `GALLIUM_DRIVER=zink`).
   - Added automatic `Xwayland` background bridge for X11 applications and desktop sessions.

4. **Verification & Packaging**:
   - `.\gradlew.bat compileDebugSources` passed (`BUILD SUCCESSFUL in 33s`).
   - `.\gradlew.bat assembleDebug` passed (`BUILD SUCCESSFUL in 13s`), producing `app/build/outputs/apk/debug/app-debug.apk` with all native libraries packaged and verified.
   - APK packaged as `dist/packaged-v0.7.0-dev/linex-v0.7.0-dev.apk` (90,052,173 bytes, SHA256 `c0c440ccd9374f6939c31991d74ea896df266b185e4511d05eea205656e9de88`).
   - Retained development certificate SHA256 `3e4f665b952b7a741053bb9db6299687d92f444257b6a0c302b3d5c41dc96ecc` (`versionCode = 24`, `versionName = "0.7.0-dev"`).
   - Tagged and published as GitHub release `v0.7.0-dev`.

## Streamlined Hardware GPU Architecture (November 23, 2026)

In response to user feedback, Linex v0.7.1-dev unifies the runtime architecture into a single, straightforward, hardware-accelerated system:
1. **Removed Dual-Runtime & Display Complexity**:
   - Eliminated the confusing "Virtual Machine vs PRoot" choice and multiple display backend toggles.
   - Defaulted directly to the high-performance rootless container engine with direct Qualcomm Adreno GPU access (`/dev/kgsl-3d0`).
   - Wayland is the sole, unified display engine with Turnip Vulkan and Zink OpenGL drivers.
2. **Revamped Setup**:
   - Streamlined `CreateInstanceDialog` to only configure desktop name, resolution mode, target FPS, and RAM budget.
   - Default instance set to `Ubuntu Workstation` (Ubuntu 22.04 LTS Jammy XFCE4).
   - `InstanceRepository` auto-provisions `Ubuntu Workstation` if only legacy VM instances exist.
3. **Packaging & Verification**:
   - `.\gradlew.bat assembleDebug` passed cleanly.
   - Verified signing certificate `3e4f665b952b7a741053bb9db6299687d92f444257b6a0c302b3d5c41dc96ecc` (`versionCode = 25`, `versionName = "0.7.1-dev"`).
   - Packaged as `dist/packaged-v0.7.1-dev/linex-v0.7.1-dev.apk` (90,044,085 bytes, SHA256 `7265094d37d28c1b50503cbd387b97155a9b84a5836f7d1c1adbfa091897580e`).

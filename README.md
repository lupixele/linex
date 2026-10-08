# Linex

Android app for running Linux desktops in an embedded, rootless virtual machine, with PRoot retained for existing instances. Package: `com.linex.app`.

## Current development build

`0.6.0-dev` introduces a managed full virtual machine with Debian 13, XFCE and
Firefox ESR. New instances default to the VM; choose **New → Full virtual machine**
and install its image. The download is about 320 MiB and expands to a 4 GiB disk;
allow at least 5 GiB free for setup. Existing PRoot instances stay intact and can
still be launched. Their files are not automatically imported into a VM.

The Linux kernel and its processes run inside one private Android service, so
Firefox's Linux subprocesses do not become Android phantom processes. No root
or changes to Android's phantom-process settings are required. The foreground
service owns the session, installation, cancellation and notification progress.
Android can still reclaim an app under memory pressure or OEM power policies.

The embedded VM viewer presents complete frames with GLES. Landscape/fullscreen,
manual resolution, 15–144 FPS limits, keyboard/mouse, touchpad gestures, and a
toggleable FPS/RAM/CPU text overlay are available. Unsupported GPU statistics are
omitted. VM RAM presets/custom values allocate guest memory; PRoot RAM settings
remain advisory. FPS options set a limit, not a guaranteed rendered frame rate.
Guest graphics and video decoding use software; host GLES presentation does not
provide guest GPU acceleration. Snapdragon 732G performance has not been measured.

The release retains exact image/download hashes and supplies Debian, kernel,
QEMU/dependency/relinkable and existing native sources. See
[VM release evidence](VM-RELEASE-EVIDENCE.md) for actual tests and their limits.

## Earlier development builds

`0.3.4-dev` adds per-instance desktop FPS limits: 15, 30, 60, 90, 120 and 144. Edit instance settings while stopped, save, then launch. The viewer uses the selected pacing and requests a supported Android refresh rate for the session; actual FPS depends on hardware/workload and the OS may ignore refresh preferences. Existing instances retain 15 FPS.

`0.3.3-dev` reduces framebuffer allocation and hidden-view rendering, disables incompatible XFCE applets/compositing, fixes cancellation cleanup and concurrent instance saves, and detects guest exit independently of log EOF. Adds bounded log backlog and host/guest diagnostics. Delayed guest SIGKILL remains unattributed; see DELAYED_CRASH_AUDIT.md.

`0.3.2-dev` moves download, extraction/configuration, clone and delete work into the foreground service. Notifications show stages, percentages or file counts, completion and errors; setup/copy can be cancelled. Leaving the screen or dismissing progress keeps work running. Enable notification permission to see progress. Force-stop or system process termination interrupts work; reopening reports interruption and completed archives remain available for retry. This build uses the same signing key as 0.3.1-dev and can update it in place. Device verification of screen-off behavior remains required.

`0.3.1-dev` adds manual resolution fields (apply on restart), fullscreen/landscape controls, a dedicated /dev/shm mount for browsers, active-network DNS refresh and 15fps frame pacing. Guest exit137 remains under diagnosis. This development release uses a new signing key after the PC reset. Uninstall the older app before installing it; uninstalling deletes local instances. Subsequent builds will retain this new key.

`0.3.0-dev` adds an embedded RFB desktop viewer and an authenticated loopback TigerVNC display server inside the guest. First launch installs missing TigerVNC packages using apt (internet and working distribution repositories required). Existing installed instances are reused. POSIX signal traps and supervisor exit status are fixed.

`0.2.9-dev` displays live archive-read progress, extracted entry count, elapsed time and inactivity age, keeps the screen awake during setup, and removes redundant extraction filesystem work. Archive-read percentage is not a time estimate; Android speedup is not yet measured.

`0.2.8-dev` additionally fixes rejection of valid POSIX filenames and link targets containing literal backslashes, such as systemd unit names. Traversal and root containment checks remain enforced. `0.2.7-dev` fixed extraction of the Ubuntu archive's `/usr/bin/X11 -> .` symlink: the previous build converted its target to an empty path. Symlink failures now include the archive entry, target, and exception type. Install this update over the existing app and retry the same instance to reuse its completed download.

The build also includes the installation, diagnostics, and instance-management improvements from `0.2.6-dev`:

- Rootfs archives are streamed through a JVM tar reader, with gzip, xz and bzip2 detection. Installation is staged, paths and links are checked, and readiness requires the completed-install marker plus a usable shell.
- Completed downloads carry a URL/length/SHA-256 receipt and remain available after extraction failure. Retry reuses a matching archive. A partial HTTP download is restarted, not resumed with Range requests.
- Logs are isolated by instance, persisted locally, restored at startup, searchable, and exportable. Clear affects only the selected instance.
- Instance cards expose Logs, setup errors, retry, settings, clone, and confirmed deletion. Live process state drives session controls.

Audio and clipboard synchronization are not implemented for the VM. Phosh/Wayland
is not supported by the XFCE/X11 image. The existing PRoot native X11 backend is
separate from the VM's private RFB/GLES display path.

## Build and verify

Requirements: JDK 17, Android SDK 34/build-tools 34.0.0, NDK 26.1.10909125, CMake 3.22.1. Set `sdk.dir` in local.properties to your SDK path.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PvmNativeDir=dist/vm-engine-production --no-daemon
.\gradlew.bat --stop
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Supply the accepted ARM64 and x86_64 runtime artifacts under the directory passed
to `vmNativeDir`. The app build rejects missing or changed runtime bytes when a
VM catalogue is bundled. Native source rebuilding requires Linux and the pinned
NDK r30/tool versions in `scripts/vm/native-sources.json` and `build-tools.txt`;
the normal app build uses the verified native artifacts, not Windows QEMU.

Unit tests cover archive safety/readiness and persistent log isolation. Hardware validation still needs an ARM64 Android device: install APK, download an image, interrupt/retry setup, reopen logs after force-stop, export logs, and exercise instance management. A successful build is not proof that PRoot or desktop rendering works on a device.

## Diagnostics and storage

Each instance uses `files/instances/<id>/rootfs`. During setup, extraction writes `rootfs.installing` before replacing the target; failed setup retains the downloaded archive. Existing files are not cleared at the start of extraction.

Diagnostics use `files/logs/instance_<id>.log`, a serial background writer, up to 1,000 live entries per instance, and bounded disk journals. Abrupt process termination can lose output still waiting in the write queue. Export shares a text snapshot through Android FileProvider.

## Upstream projects

- [PRoot](https://github.com/termux/proot)
- [Termux-X11](https://github.com/termux/termux-x11)
- [Udroid](https://github.com/RandomCoderOrg/fs-manager-udroid)

The older SYSTEM_DESIGN.md describes intended architecture; it is not a verified feature inventory.

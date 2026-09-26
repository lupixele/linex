# Linex

Android app for managing rootless Linux instances with PRoot. Package: `com.linex.app`.

## Current development build

`0.2.8-dev` additionally fixes rejection of valid POSIX filenames and link targets containing literal backslashes, such as systemd unit names. Traversal and root containment checks remain enforced. `0.2.7-dev` fixed extraction of the Ubuntu archive's `/usr/bin/X11 -> .` symlink: the previous build converted its target to an empty path. Symlink failures now include the archive entry, target, and exception type. Install this update over the existing app and retry the same instance to reuse its completed download.

The build also includes the installation, diagnostics, and instance-management improvements from `0.2.6-dev`:

- Rootfs archives are streamed through a JVM tar reader, with gzip, xz and bzip2 detection. Installation is staged, paths and links are checked, and readiness requires the completed-install marker plus a usable shell.
- Completed downloads carry a URL/length/SHA-256 receipt and remain available after extraction failure. Retry reuses a matching archive. A partial HTTP download is restarted, not resumed with Range requests.
- Logs are isolated by instance, persisted locally, restored at startup, searchable, and exportable. Clear affects only the selected instance.
- Instance cards expose Logs, setup errors, retry, settings, clone, and confirmed deletion. Live process state drives session controls.

**This is not a complete Linux desktop app yet.** The embedded X11 surface and native input bridge are placeholders. The session screen now says this explicitly instead of displaying a false “display active” message. Bundled rootfs images and PRoot libraries target ARM64; other architectures cannot run them. The minimal image has no desktop packages. Desktop profiles in older saved instances do not guarantee the required packages exist.

## Build and verify

Requirements: JDK 17, Android SDK 34/build-tools 34.0.0, NDK 26.1.10909125, CMake 3.22.1. Set `sdk.dir` in local.properties to your SDK path.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
.\gradlew.bat --stop
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests cover archive safety/readiness and persistent log isolation. Hardware validation still needs an ARM64 Android device: install APK, download an image, interrupt/retry setup, reopen logs after force-stop, export logs, and exercise instance management. A successful build is not proof that PRoot or desktop rendering works on a device.

## Diagnostics and storage

Each instance uses `files/instances/<id>/rootfs`. During setup, extraction writes `rootfs.installing` before replacing the target; failed setup retains the downloaded archive. Existing files are not cleared at the start of extraction.

Diagnostics use `files/logs/instance_<id>.log`, a serial background writer, up to 1,000 live entries per instance, and bounded disk journals. Abrupt process termination can lose output still waiting in the write queue. Export shares a text snapshot through Android FileProvider.

## Upstream projects

- [PRoot](https://github.com/termux/proot)
- [Termux-X11](https://github.com/termux/termux-x11)
- [Udroid](https://github.com/RandomCoderOrg/fs-manager-udroid)

The older SYSTEM_DESIGN.md describes intended architecture; it is not a verified feature inventory.

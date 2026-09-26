# Linex progress

## Current State
Local checkout: P:/Magnanimity/Projects/linex. Working branch: fix/rootfs-x11-symlink; baseline main 363e9b4 (published v0.2.6-dev). Development version 0.2.7-dev (code 9) fixes same-directory symlink target conversion in rootfs extraction. The user's device log confirms download/cache reuse works, but both extraction attempts stop at /usr/bin/X11. Streaming inspection confirms upstream archive entry 903 is /usr/bin/X11 -> .; relativize previously converted that target into an empty path.

## Verification
The new regression fails against the old conversion (expected '.', got empty). All 19 unit tests pass; debug APK assembly succeeded; lint reports 0 errors and 27 warnings. Windows cannot run actual symlink creation under this process's privileges, so new tests exercise the exact target conversion; archive, cache and logging tests remain. No Android device is attached; on-device extraction remains unverified.

## Next Steps
1. Install app/build/outputs/apk/debug/app-debug.apk on ARM64 Android and test real-image extraction, cancellation/retry, force-stop/reopen logs, export, clone/delete and stop/restart.
2. Implement actual embedded X11 server lifecycle, Surface attachment and input bridge; current UI explicitly discloses the missing renderer.
3. Add end-to-end HTTP interruption and Android filesystem tests; validate PRoot loader and versioned talloc alias on-device.

## Open Questions / Blockers
- X11SurfaceView and native input bridge are unimplemented; no complete desktop is claimed.
- Download setup is tied to the screen lifecycle. Complete archives survive retry; partial HTTP transfers restart. Log writer is ordered but asynchronous, so abrupt process death can lose queued tail entries.
- An older marker plus usable shell preserves legacy instances, but cannot prove the old installer fully unpacked every file.
- Repository already tracks generated Gradle/CMake/build files; builds modify these. Source commits exclude generated outputs.

## Last Updated
2026-09-26

## Recent Decisions
- [2026-09-26] Work directly in the user-specified linex checkout; replace unreliable Android tar extraction with bounded streaming JVM extraction; preserve old ready roots and scope diagnostics per instance.
- [2026-09-26] Confirmed upstream archive entry /usr/bin/X11 -> . was converted to an empty symlink target. Preserve literal dot and report symlink target/type on failures; retain download cache and user instance data.

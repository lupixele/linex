# Linex progress

## Current State
Local checkout: P:/Magnanimity/Projects/linex. Published main/v0.2.7-dev at 41c655b. Working version 0.2.8-dev (code 10) fixes POSIX backslash validation for archive filenames and link targets. The updated device log confirms X11 extraction now succeeds, then fails after 19,000 entries on system-systemd\x2dcryptsetup.slice. This is a valid literal POSIX filename, previously rejected by Windows-oriented validation. Backslashes remain rejected on non-POSIX filesystems; traversal and canonical containment checks remain unchanged.

## Verification
The exact systemd filename regression fails with the old validation. All 22 unit tests pass; APK assembly and lint succeed; APK signature verifies. Windows tests cover explicit POSIX/Windows validation rules; actual Android extraction remains unverified because no device is connected and WSL is unavailable.
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
- [2026-09-26] Updated device log confirms X11 fix works. Preserve literal backslashes in POSIX archive filenames and link targets; do not decode systemd escapes or weaken root containment.

# Linex progress

## Current State
Local checkout: P:/Magnanimity/Projects/linex. Working version 0.2.9-dev (code 11) adds live archive-read percentage, entry count, elapsed/inactivity indicators, and setup screen wake. Removed duplicate per-file canonicalization and repeated same-parent creation, while preserving containment checks and invalidating the parent cache on symlink changes. Input buffering increased to 128 KiB. Detailed progress now reaches the UI instead of being discarded.

## Verification
Updated device log from 0.2.8 reached 359,000 entries in 12m10s with no error and ongoing progress. New tests cover byte-counter bounds/monotonicity and no false completion on corrupt archives. Initial counter test caught mark/reset double-counting; fixed by counting below the buffer. All 24 unit tests pass; debug build/lint succeed and APK signature verifies. No device speedup or complete extraction claimed; Android device unavailable.
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
- [2026-09-26] Device log reached 359,000 entries in 12m10s without error. Added archive-byte/stage progress, elapsed/inactivity UI, active-setup screen wake, and removed duplicate canonical checks/repeated parent creation. No Android speedup claimed without measurement.

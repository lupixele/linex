# Linex progress

## Current State
Local checkout: P:/Magnanimity/Projects/linex. Working branch: fix/instance-setup-and-diagnostics; baseline 171159a. Development version 0.2.6-dev (code 8) implements staged JVM rootfs extraction, validated cached-download reuse, persistent scoped logs, improved setup/error UI and functional settings/session state. Existing ready legacy installations remain accepted to avoid replacing user data merely for an old marker version.

## Verification
17 JVM tests pass (archive safety/readiness/links, cache receipts, log persistence/isolation). Debug APK builds; lint has no errors (existing dependency/deprecation warnings remain). Shell syntax and packaged LF line endings are checked. No Android device or emulator is attached; PRoot startup, visual layouts and sharing are not hardware-verified.

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

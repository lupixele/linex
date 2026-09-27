# Linex progress

## Current State
Working 0.3.1-dev (code13), based on released main f274964. User confirms embedded desktop works but guest/browser stops, custom size fields absent and fullscreen absent. Implemented custom width/height validation, fullscreen/landscape controls, dedicated guest /dev/shm mount, active-network DNS refresh and guest lookup diagnostics, 15fps display request pacing, and exit137 memory diagnostics.

## Verification
Log proves Chromium fatal missing /dev/shm, then guest exit137; no evidence establishes the kill cause. Shell syntax passes. 39 tests pass; debug assembly and lint succeed after correcting fullscreen API reference. New-key APK signature verifies but differs from installed release. No Android device test. Private DNS cannot be inherited by guest libc; existing settings retained with diagnostic rather than claiming encrypted support.

## Next Steps
1. Publish new-key 0.3.1-dev; user accepts fresh install and no restoration of old instance data.
2. Verify custom resolution after restart, fullscreen/rotation, browser shared memory/DNS and any remaining guest kill on Android.

## Open Questions / Blockers
- Exit137 cause remains unconfirmed; memory/process-policy attribution needs device logs. User says desktop/browser stops, not Linex.
- New signing key is backed up at P:/Android/Signing/linex-development.keystore outside Git. Old app must be uninstalled before installing new-key APK.
- JDK17, Git, gh restored and GitHub authenticated. SDK34/build-tools34, NDK26.1 and CMake3.22.1 present; ANDROID_HOME and tool paths configured.
- Generated files already tracked remain excluded; user raw logs remain private/untracked.
## Last Updated
2026-09-27
## Recent Decisions
- [2026-09-26] Work directly in the user-specified linex checkout; replace unreliable Android tar extraction with bounded streaming JVM extraction; preserve old ready roots and scope diagnostics per instance.
- [2026-09-26] Confirmed upstream archive entry /usr/bin/X11 -> . was converted to an empty symlink target. Preserve literal dot and report symlink target/type on failures; retain download cache and user instance data.
- [2026-09-26] Updated device log confirms X11 fix works. Preserve literal backslashes in POSIX archive filenames and link targets; do not decode systemd escapes or weaken root containment.
- [2026-09-26] Device log reached 359,000 entries in 12m10s without error. Added archive-byte/stage progress, elapsed/inactivity UI, active-setup screen wake, and removed duplicate canonical checks/repeated parent creation. No Android speedup claimed without measurement.
- [2026-09-26] User requires the desktop entirely inside Linex. Device extraction completed at 671,719 entries (~21m22s); startup failed at POSIX SIGTERM trap and absent display server. Implement embedded authenticated loopback RFB viewer plus guest TigerVNC; preserve installed rootfs.
- [2026-09-27] Fixed confirmed missing guest shared-memory mount; added custom resolution/fullscreen, network DNS diagnostics and bounded display cadence. Reset lost usable GitHub login and original signing key; new key cannot update released APK.
- [2026-09-27] User explicitly accepts new development signing key and no restore. Backed up key outside Git; GitHub login restored; publish 0.3.1-dev with reinstall notice.

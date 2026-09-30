# Linex progress

## Current State
0.3.2-dev published; 0.3.3-dev delayed-crash audit fixes verified locally. Foreground service owns setup/copy/delete, notifications expose phase/count progress, and UI dismissal no longer cancels work. Interrupted operations are reported on next launch.

## Verification
Background ownership regression tests added. 50 tests pass; debug assembly succeeds; lint has 0 errors and 32 warnings. APK signature matches 0.3.1-dev. JDK17 and Gradle cache restored after reset. Android screen-off and notification verification still required.

## Next Steps
1. Publish verified 0.3.3-dev and test delayed crash on device.
2. Verify background download/extraction, notification cancel/open and screen-off behavior on Android.
3. Recheck guest browser/DNS and exit137 using device logs.

## Open Questions / Blockers
- Guest exit137 cause remains unconfirmed; no Android device attached.
- Existing new signing key and matching backup survived reset. Same key as 0.3.1-dev.
- Java17 now at P:/Android/Java17/PFiles64/Microsoft/jdk-17.0.20.101-hotspot; JAVA_HOME configured. Git/gh/SDK/NDK/CMake present; GitHub authenticated.
- Generated files already tracked excluded; raw logs remain private/untracked.

## Last Updated
2026-09-30

## Recent Decisions
- [2026-09-26] Work directly in the user-specified linex checkout; replace unreliable Android tar extraction with bounded streaming JVM extraction; preserve old ready roots and scope diagnostics per instance.
- [2026-09-26] Confirmed upstream archive entry /usr/bin/X11 -> . was converted to an empty symlink target. Preserve literal dot and report symlink target/type on failures; retain download cache and user instance data.
- [2026-09-26] Updated device log confirms X11 fix works. Preserve literal backslashes in POSIX archive filenames and link targets; do not decode systemd escapes or weaken root containment.
- [2026-09-26] Device log reached 359,000 entries in 12m10s without error. Added archive-byte/stage progress, elapsed/inactivity UI, active-setup screen wake, and removed duplicate canonical checks/repeated parent creation. No Android speedup claimed without measurement.
- [2026-09-26] User requires the desktop entirely inside Linex. Device extraction completed at 671,719 entries (~21m22s); startup failed at POSIX SIGTERM trap and absent display server. Implement embedded authenticated loopback RFB viewer plus guest TigerVNC; preserve installed rootfs.
- [2026-09-27] Fixed confirmed missing guest shared-memory mount; added custom resolution/fullscreen, network DNS diagnostics and bounded display cadence. Reset lost usable GitHub login and original signing key; new key cannot update released APK.
- [2026-09-27] User explicitly accepts new development signing key and no restore. Backed up key outside Git; GitHub login restored; publish 0.3.1-dev with reinstall notice.
- [2026-09-30] Move setup/copy/delete to service-owned work with foreground notification progress, explicit cancellation and persisted interruption; restore JDK after reset and retain existing signing key.
- [2026-09-30] Published v0.3.2-dev APK and pushed bda859f to main; 50 tests passed, lint 0 errors/32 warnings, signature matches v0.3.1-dev. Android background behavior still requires device verification.
- [2026-09-30] Delayed crash log establishes guest SIGKILL137, not the killer. Fixed viewer allocation/hidden work, process-exit detection, metadata races and cancellation cleanup; lightweight XFCE and bounded logging. 50 tests plus shell regression/build/lint pass; same signing key. See DELAYED_CRASH_AUDIT.md.
- [2026-09-30] Published v0.3.3-dev and pushed306d2bf to main. Root cause of external guest SIGKILL remains unconfirmed pending device system logs; do not claim crash resolved from build success.

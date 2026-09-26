# Linex progress

## Current State
Working version 0.3.0-dev (code 12) fixes POSIX signal traps and adds an embedded Kotlin RFB viewer with guest TigerVNC on authenticated loopback. First launch installs missing server/tools via bounded apt commands; no external viewer app is needed. Existing installed rootfs is preserved. Source baseline main d32a11e (v0.2.9-dev).

## Verification
User device log confirms rootfs ready after 671,719 entries (~21m22s), followed by dash SIGTERM trap failure and no X11 server. Final unit suite: 34 tests pass; debug APK built and signature verified. Lint succeeds with 0 errors. Shell syntax/supervisor status tests pass. Real Android display rendering and guest package installation remain unverified.

## Next Steps
1. Install updated APK over existing app, start XFCE; allow display package install if needed, inspect instance logs and retry display connection.
2. Verify keyboard/touch, reconnect/detach, stop/restart, package availability and actual desktop performance on ARM64 device.
3. Add audio/clipboard, efficient encodings and wider input compatibility after validating basic display.

## Open Questions / Blockers
- Software RFB rendering only; no GPU acceleration, audio or clipboard integration. Phosh/Wayland unsupported; native X11 stubs unused.
- Guest apt repositories must work if TigerVNC is absent; distro source rewriting is not automatic.
- Build outputs/caches already tracked in repository remain excluded from source commits; raw user logs are untracked/private.
- No Android device is connected. Mock server protocol tests do not establish full guest runtime compatibility.

## Last Updated
2026-09-26
## Recent Decisions
- [2026-09-26] Work directly in the user-specified linex checkout; replace unreliable Android tar extraction with bounded streaming JVM extraction; preserve old ready roots and scope diagnostics per instance.
- [2026-09-26] Confirmed upstream archive entry /usr/bin/X11 -> . was converted to an empty symlink target. Preserve literal dot and report symlink target/type on failures; retain download cache and user instance data.
- [2026-09-26] Updated device log confirms X11 fix works. Preserve literal backslashes in POSIX archive filenames and link targets; do not decode systemd escapes or weaken root containment.
- [2026-09-26] Device log reached 359,000 entries in 12m10s without error. Added archive-byte/stage progress, elapsed/inactivity UI, active-setup screen wake, and removed duplicate canonical checks/repeated parent creation. No Android speedup claimed without measurement.
- [2026-09-26] User requires the desktop entirely inside Linex. Device extraction completed at 671,719 entries (~21m22s); startup failed at POSIX SIGTERM trap and absent display server. Implement embedded authenticated loopback RFB viewer plus guest TigerVNC; preserve installed rootfs.

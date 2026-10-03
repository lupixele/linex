# Linex progress

## Current State
0.5.0-dev published: https://github.com/lupixele/linex/releases/tag/v0.5.0-dev (implementation commit119d74d on main). Embedded host EGL/GLES presentation replaces RFB in Automatic mode when the GPU probe and native startup succeed; RFB remains selectable. Linux application GL is still software-rendered and hardware video decoding is not implemented. Android phantom-process and Private DNS workarounds remain confirmed.

## Verification
93 unit tests and XFCE shell regressions pass; APK assembly and lint succeed (0 errors, 34 warnings). Signing certificate matches 0.3.1-dev onward. Reviewed server isolation, authenticated sockets, lifecycle ownership, native input and EGL context cleanup. No Android device attached; actual phone FPS and visual quality remain unverified.

## Next Steps
1. Compare native display scrolling/video on Snapdragon 732G; record backend logs and compatibility fallback behavior.
2. Implement and validate a guest GPU bridge with capability-based driver selection; keep software fallback for unsupported phones.
3. Add native FPS telemetry and Android resolver bridging after device validation.

## Open Questions / Blockers
- Device scrolling/video artifacts remain unverified; Android guest-kill workaround is confirmed by the user.
- Existing new signing key and matching backup survived reset. Same key as 0.3.1-dev.
- Java17 now at P:/Android/Java17/PFiles64/Microsoft/jdk-17.0.20.101-hotspot; JAVA_HOME configured. Git/gh/SDK/NDK/CMake present; GitHub authenticated.
- Generated files already tracked excluded; raw logs remain private/untracked.

## Last Updated
2026-10-03

## Recent Decisions
- [2026-09-26] Work directly in the user-specified linex checkout; replace unreliable Android tar extraction with bounded streaming JVM extraction; preserve old ready roots and scope diagnostics per instance.
- [2026-09-26] Confirmed upstream archive entry /usr/bin/X11 -> . was converted to an empty symlink target. Preserve literal dot and report symlink target/type on failures; retain download cache and user instance data.
- [2026-09-26] Updated device log confirms X11 fix works. Preserve literal backslashes in POSIX archive filenames and link targets; do not decode systemd escapes or weaken root containment.
- [2026-09-26] Device log reached 359,000 entries in 12m10s without error. Added archive-byte/stage progress, elapsed/inactivity UI, active-setup screen wake, and removed duplicate canonical checks/repeated parent creation. No Android speedup claimed without measurement.
- [2026-09-26] User requires the desktop entirely inside Linex. Device extraction completed at 671,719 entries (~21m22s); startup failed at POSIX SIGTERM trap and absent display server. Implement embedded authenticated loopback RFB viewer plus guest TigerVNC; preserve installed rootfs.
- [2026-09-27] Fixed confirmed missing guest shared-memory mount; added custom resolution/fullscreen, network DNS diagnostics and bounded display cadence. Reset lost usable GitHub login and original signing key; new key cannot update released APK.
- [2026-09-27] User explicitly accepts new development signing key and no restore. Backed up key outside Git; GitHub login restored; publish 0.3.1-dev with reinstall notice.
- [2026-09-30] Move setup/copy/delete to service-owned work with foreground notification progress, explicit cancellation and persisted interruption; restore JDK after reset and retain existing signing key.
- [2026-09-30] Published v0.3.2-dev APK and pushed bda859f to main; 54 tests passed, lint 0 errors/32 warnings, signature matches v0.3.1-dev. Android background behavior still requires device verification.
- [2026-09-30] Delayed crash log establishes guest SIGKILL137, not the killer. Fixed viewer allocation/hidden work, process-exit detection, metadata races and cancellation cleanup; lightweight XFCE and bounded logging. 50 tests plus shell regression/build/lint pass; same signing key. See DELAYED_CRASH_AUDIT.md.
- [2026-09-30] Published v0.3.3-dev and pushed306d2bf to main. Root cause of external guest SIGKILL remains unconfirmed pending device system logs; do not claim crash resolved from build success.
- [2026-09-30] Added per-instance FPS presets15-144; preserve default15 for existing instances, configure both RFB pacing and TigerVNC FrameRate, request supported Android refresh and restore preference on leaving. Hardware FPS not measured.
- [2026-09-30] v0.3.4-dev published. New launch log exits137 after11s; Firefox was manually opened and user also reports spontaneous crashes. Dropped saved-session theory; added5s startup samples and app-UID process lower bound. Android phantom-process trimming remains a hypothesis pending system logcat; no USB device attached.
- [2026-09-30] Implemented landscape fullscreen default, Back/sidebar controls, persistent resource overlay (actual frame FPS/visible guest RSS/CPU; omit GPU), hardware keyboard/mouse and touchpad gestures. RAM selectors are advisory planning targets, preserving legacy settings.72 tests/build pass, lint0 errors32 warnings; same signing key. Device verification and guest SIGKILL source remain pending.
- [2026-09-30] Published v0.4.0-dev APK and code367a2b9 on main. Uploaded APK SHA256 matches local e46abbff0babad6ea05a0f2f06a8a6137da623499deb3401ec3f1d4b4ea2e953. Release https://github.com/lupixele/linex/releases/tag/v0.4.0-dev; physical-device checks still pending.

- [2026-10-01] System-log diagnosis confirmed: at 13:39:09.179 Android ActivityManager killed libproot.so, Xtigervnc and xfce4-session for 'Trimming phantom processes'. Earlier Sep30 15:49:33 kill matches exit137. Rooted Android13 workaround: settings put global settings_enable_monitor_phantom_procs false (system-wide); device verification pending. No APK change can directly override this privileged policy without root/shell access.
- [2026-10-01] User confirms Private DNS Off restores internet and disabling phantom-process monitoring prevents crashes. Severe scrolling/video artifacts remain with 60 FPS selected. Fix confirmed scheduling and raw-scrolling overhead; preserve compositor choice for controlled comparison. Phone improvement is not yet measured.
- [2026-10-01] Published v0.4.1-dev and pushed c1f5e2d to main. 79 tests and XFCE shell regression pass; lint 0 errors/32 warnings; existing signing certificate matches. GitHub asset digest matches local 7b47dff74b6c494aa72527c6b0d370f3f9438ffcb95c13a9745d5a4bdb37736d. Device tearing improvement remains unverified.
- [2026-10-03] User confirms v0.4.1-dev still has display artifacts and requests hardware acceleration. Feasibility audit: Android acceleration already enabled; RFB path remains CPU decoded/uploaded. Existing X11/JNI placeholders cannot activate bundled libXlorie without matching upstream classes and lifecycle integration. Recommend embedded native X11 backend plus device-compatible guest GPU rendering; video decoding separate. Exact current device/GPU confirmation pending.
- [2026-10-03] Implemented native X11 host GPU presentation with capability probe and RFB fallback, authenticated isolated server and lifecycle/input fixes. 93 tests/shell regressions/build pass; lint 0 errors34 warnings; signing key unchanged. Guest GPU rendering/video decoding and phone performance remain unverified. Native corresponding source archive must accompany APK.
- [2026-10-03] Published v0.5.0-dev APK and matching native source archive. APK SHA256 0a6b685a30b5fb7ebf15c01aed1796f7ec905e49f22ad86575f502d641d643ac; native source db4bf1740f6b0e465d584108107c01a51fa854723fd037016802c60efbe557d1. Guest GPU acceleration and physical-device artifact verification remain next steps.
- [2026-10-03] User reports Android rejects v0.5.0-dev with package appears invalid. Re-downloaded GitHub APK hash matches local; apksigner v2, ZIP CRC, manifest parsing, resources/native packaging and zipalign pass. Independent packaging review found no rejection cause. Requested rooted Termux pm install -r output; device installation remains unverified and no speculative code change made.

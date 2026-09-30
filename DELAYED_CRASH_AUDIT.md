# Delayed guest termination audit - 2026-09-30

## Evidence and limits
The supplied instance log ends with display EOF at14:55:23, Firefox IPC errors, then guest exit137. Startup was14:53:48 (about95seconds). This establishes forced guest termination, not the sender of SIGKILL. Post-exit available RAM2261MiB and lowMemory=false do not rule out earlier pressure. No Android host crash stack or system kill attribution is present. Missing system D-Bus/DPMS/GL warnings are not themselves proof of the kill cause. No device is connected for logcat.

## Fixed defects
- RFB snapshot allocation: reuse returned snapshots instead of allocating a2214x1080 IntArray each update (9.56MB/frame;143MB/s theoretical at15fps, not measured device throughput).
- Hidden viewer: stop requesting frames while its window is hidden; wake paused reader on close.
- Dead guest detection: observe process exit independently from stdout EOF, which surviving descendants can delay.
- Background cancellation: retain operation exclusivity until cleanup actually completes.
- Instance metadata: share repository synchronization and mutate the latest per-instance disk state rather than writing stale UI lists over concurrent clone/delete changes.
- XFCE overhead: disable incompatible hardware/system autostarts through user-level overrides (preserve existing user overrides), and disable software compositing.
- Logging: cap queued log jobs at256 and surface skipped-message counts rather than allowing unlimited backlog.
- Diagnostics: Android host exit history at startup; guest version/device/runtime, heap/memory and bounded visible process-group samples every30seconds. Process counts are lower bounds under restricted /proc.

## Validation and follow-up
Run unit tests, shell regression, debug build, lint and signature verification. Device runtime/performance remains unverified. Android's ApplicationExitInfo describes host exits; it does not necessarily attribute guest child kills: https://developer.android.com/reference/android/app/ApplicationExitInfo

For lower rendering cost, use1280x720 instead of2214x1080 and restart the instance. Keep the compositor off and avoid restoring many browser tabs on launch. Do not disable browser sandboxes or globally alter Android process limits as an unproven fix. Capture system logcat around a recurrence to identify the killer before claiming a final root-cause fix.
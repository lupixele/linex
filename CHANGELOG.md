# Changelog

## 0.5.2-dev — 2026-10-03

- Correct the XFCE printer autostart exclusion to `print-applet.desktop`; preserve explicit overrides and dangling symlinks using atomic creation without replacement.
- Run session D-Bus as a foreground child instead of allowing it to create a separate process group. Wait for readiness, avoid exporting a dead bus address, and reap the child at shutdown.
- Track positively attributed PRoot children every five seconds, including daemonized children outside the original group. Revalidate UID and process start time before shutdown signals; avoid recycled PIDs/groups and unrelated Android processes. Bound the registry to 4,096 identities.
- Include bounded, sanitized kernel process-name counts in diagnostics, without recording command arguments or environment variables.

The new phone log confirms native GPU presentation works at both 15 and 60 FPS targets. Guest SIGKILL137 still occurs after 13 and 65 seconds; the first precedes Firefox output. Memory remains available with `lowMemory=false`, but the log does not identify the killer. These permanent no-root changes address concrete cleanup and autostart bugs; Android policy kills remain possible. See [crash audit](GPU_CRASH_AUDIT.md).

## 0.5.1-dev — 2026-10-03

- Start native X11 on Android's main Looper, matching its Choreographer requirement. The previous worker thread caused native startup to fail and Automatic mode to fall back to VNC.
- Capture bounded native stdout/stderr in the parent app and record matching Android exit information when available, so early display failures reach instance logs.
- Show the active display backend and configured FPS target in session controls; report Automatic fallback. New instances default to 60 FPS; existing settings remain intact.
- Apply permanent guest-only Firefox defaults to reduce content-process demand and disable speculative spare processes. Preserve user preferences, browser sandbox and site isolation.
- Suppress additional unused XFCE system applets while preserving explicit autostart overrides.
- Read the installed package version for labels, logs and download requests, fixing stale version labels left by incremental compilation. Version code 20; same signing certificate.

These app-side changes require no root or Android policy modifications. Process defaults reduce demand; they cannot exempt unrestricted PRoot workloads from Android's global process limits. The latest guest SIGKILL remains unattributed. Native presentation uses the host GPU; Linux application OpenGL and video decoding remain software paths. Phone crash behavior and scrolling still need verification.

Validation: clean build, 100 unit tests, six process-default shell scenarios and XFCE startup regressions pass. Lint: 0 errors, 34 warnings. APK v2 signature and alignment verified.

## 0.5.0-dev — 2026-10-03

- Add an embedded native X11 display presented through Android EGL/GLES, with per-instance Automatic, Native X11 and RFB compatibility selection.
- Probe host GPU drawing and texture limits before choosing native presentation; Automatic falls back when native startup fails.
- Isolate the X server in a private Android process, authenticate local X11 connections, disable TCP and incompatible MIT-SHM, and clean up stale connections and guest processes.
- Preserve resolution, landscape/fullscreen, physical input and touchpad controls. Native FPS telemetry is unavailable and omitted.
- Include pinned Termux:X11 license notices and corresponding native source with the release.

Linux OpenGL remains software-rendered; guest GPU acceleration and hardware video decoding are separate pending work. Android phone performance and artifact reduction have not been measured. Existing phantom-process and Private DNS workarounds still apply.

Validation: 93 unit tests, XFCE shell regressions and APK assembly pass; lint reports 0 errors and 34 warnings. Signing certificate matches previous development releases.

## 0.4.1-dev — 2026-10-01

### Fixed

- Coalesce desktop updates into one Android animation callback, presenting the latest
  complete frame without repeatedly cancelling pending presentation.
- Support overlap-safe RFB CopyRect to avoid retransmitting copied scrolling regions
  as raw pixels. Video still needs changed pixels to be transferred and decoded.
- Preserve the user's XFCE compositing choice across restarts rather than forcing it
  off; lightweight defaults apply only when the setting is absent.

### Device findings

- System logs confirm Android phantom-process trimming caused the observed guest
  SIGKILL. The rooted user reports stable operation with monitoring disabled.
- The user confirms networking works with Android Private DNS Off. Android encrypted
  DNS is not yet bridged into Linux.
- Scrolling/video artifacts still need a phone comparison. Frame-delivery improvements
  do not guarantee hardware-accelerated video, artifact elimination or actual 60 FPS.

## 0.4.0-dev — 2026-09-30

### Added

- Sidebar resource text overlay with measured desktop FPS and available Linux RAM/CPU
  data. Unsupported metrics are omitted; preferences are saved per instance.
- Physical keyboard and mouse input, including modifiers, function/navigation keys,
  mouse hover, right/middle clicks and wheel scrolling.
- Touchpad mode with a visible relative cursor, left/right tap, two-finger scrolling
  and hold-to-drag.
- Default, recommended and custom RAM budget settings. Budgets are advisory because
  rootless Linux shares Android memory; they do not reserve or enforce memory limits.

### Changed

- Desktop sessions open in landscape fullscreen, with Back opening the sidebar.
- Native desktop resolution uses the full landscape screen dimensions. Custom sizes
  keep their aspect ratio.

### Fixed

- Input releases when focus is lost or controls open, preventing held guest keys/buttons.
- Fractional touchpad movement and small scroll gestures no longer cause lost motion
  or accidental right clicks.
- Startup diagnostics sample earlier and count visible app processes to help investigate
  short guest kills. The previous SIGKILL crash is not yet confirmed resolved.

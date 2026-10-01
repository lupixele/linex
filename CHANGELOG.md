# Changelog

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

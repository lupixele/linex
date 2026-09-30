# Changelog

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

# Desktop controls

Sessions open in landscape fullscreen. Back or the small menu button opens the sidebar;
use **Back to instances** to leave the display while Linux keeps running. The sidebar
can turn fullscreen off or return orientation to device rotation. Leaving the session
restores Android's bars, screen orientation, cutout behavior and refresh preference.

Native Phone Screen now uses the full landscape display dimensions, including the space
previously occupied by system bars. Other profiles and custom resolutions retain their
configured size. The viewer fits that size without stretching; different aspect ratios
can still produce black borders. Stop and start an existing session to apply new geometry.

## Input

Physical USB/Bluetooth keyboards and mice work in either touchscreen mode. Mouse hover,
left/right/middle buttons and vertical/horizontal scrolling are forwarded to the desktop.
Keyboard modifiers, function keys, keypad/navigation keys and common dead accents use X11 key symbols. Android retains
its navigation keys and shortcuts that it intercepts before delivering events to Linex.
Opening controls, losing focus or hiding the app releases held guest keys and buttons.

Select **Mode: Virtual Trackpad** in the sidebar:

- Move one finger to move the visible cursor relative to its current position.
- Tap for left click; tap two fingers for right click.
- Move two fingers to scroll vertically or horizontally.
- Hold one finger, then move it to drag; lifting it releases the button.

Direct Touch remains available. Input mode and the monitor preference are remembered per
instance. The Keyboard button opens or hides Android's keyboard.

## Resource overlay

Toggle **Resource monitor** in the sidebar for text in the upper-left corner. Sampling
runs only while the overlay and session are visible. It adds no guest processes.

- FPS counts new desktop frames actually drawn, not the configured cap or screen refresh.
  A static desktop can show 0 FPS because its pixels do not need updating.
- RAM is aggregate resident memory (RSS) for readable processes in the guest process group.
  Android can hide processes and children can change groups; coverage is partial. Shared
  pages can be counted in more than one process, so this is not unique physical memory.
- CPU uses deltas of those visible processes' CPU time. One core fully used is 100%; more
  than one core can exceed 100%. New, exited and hidden processes can be undercounted.
- Unsupported metrics are omitted. No portable Linux GPU utilization metric is available
  through this embedded software-rendered desktop, so the overlay does not invent one.

## RAM settings

Instance settings provide **Default**, **Recommended** and **Custom** RAM budgets in MiB.
Default is 2048 MiB, bounded by detected device RAM. Recommended considers desktop needs
and approximately one-third of device RAM, capped at 4096 MiB. Custom values are validated
against device RAM. Legacy custom values are preserved when editing other settings.

These are advisory planning targets. PRoot processes share Android's memory; Linex cannot
reserve a separate RAM pool or enforce an aggregate guest limit without access to a
delegated memory controller. Per-process address-space limits would not implement such a
pool and could break browsers. See the [Linux cgroup memory controller documentation](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html)
and [Android memory information](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo).

## Device verification

72 unit tests and debug assembly pass; lint reports 0 errors and 32 warnings. The development
APK still needs a phone check for fullscreen with rotation/cutouts, sidebar
focus, USB/Bluetooth shortcuts and keypad/dead-key input, IME dismissal, gesture scrolling
and dragging, idle/active FPS, and resource polling stopping in the background. The earlier
guest SIGKILL crash remains an independent investigation requiring Android system logs.

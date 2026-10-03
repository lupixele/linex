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

- On RFB, FPS counts new desktop frames actually drawn, not the configured cap or screen refresh.
  A static desktop can show 0 FPS because its pixels do not need updating.
- Native X11 frame telemetry is currently unavailable and omitted. The displayed backend
  identifies Native X11 or RFB; the sidebar FPS target is a configured maximum.
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

## Display motion and troubleshooting

The instance FPS setting is a maximum update rate, not a promise of that many new
frames. New instances default to 60 FPS; existing instances keep their saved setting.
The sidebar identifies the active backend and target, and reports Automatic fallback.
Native X11 presents through Android EGL/GLES; Linux application rendering and video
decoding remain software paths. RFB still decodes and transfers pixels through the CPU.
High resolution and refresh targets increase work. Compare measured FPS on RFB through
the resource overlay; native frame telemetry remains pending.

Incoming complete updates are coalesced to Android's animation cadence. RFB CopyRect
can copy scrolling regions locally rather than retransmitting those pixels. Video
still needs changed pixels to be transferred and decoded; this is not video hardware
acceleration or a guarantee against artifacts already present in guest drawing.

For an XFCE compositing comparison, run inside the Linux terminal:

```sh
xfconf-query -c xfwm4 -p /general/use_compositing -s true
```

Use `false` to undo. Linex now preserves this choice on restart. Newly unset settings
retain the lightweight compositing-off default. Compare both image quality and actual
FPS because software compositing can add CPU work.

## Android compatibility findings

Earlier Android system logs identified `Trimming phantom processes` as the cause of
PRoot/display/desktop kills. The latest SIGKILL has no corresponding system cause in
the supplied log. Linex now reduces unused XFCE applets and Firefox content-process
demand automatically, without root or changes to Android global settings. These defaults
preserve user choices, site isolation and the browser sandbox. They cannot exempt
unrestricted PRoot workloads from Android process management; see [process defaults](PROCESS_BUDGET.md).

The user also confirmed Linux networking works with Android Private DNS Off. Guest
libc currently does not inherit Android's encrypted resolver. Turning it off is a
workaround; an Android resolver bridge remains future work.

## Device verification

100 unit tests, process-default shell tests, XFCE startup regressions and clean debug
assembly pass; lint reports 0 errors and 34 warnings. APK certificate matches previous
development releases. Native startup, no-root process behavior, scrolling and video
still need phone verification. Guest GPU acceleration and an Android DNS bridge remain
pending; the Private DNS Off workaround was previously confirmed by the user.


## Native X11 display (0.5.0-dev)

Instance settings now include Desktop display: Automatic, Native X11 (experimental), or RFB compatibility. Stop and start the instance after changing it. Automatic probes host GLES drawing and attempts embedded native X11 before falling back on startup failure. If the native desktop fails after launch, stop the instance and choose RFB compatibility.

Native X11 uses the selected desktop resolution and FPS target with the existing landscape/fullscreen, keyboard, mouse and touchpad controls. Actual native FPS is not available in the resource overlay and is omitted. This accelerates host display presentation; guest OpenGL and video decoding are not yet hardware accelerated. Phone results are unverified.

The matching upstream native source archive is included in the release; provenance and build instructions are in third_party/termux-x11/README.md.

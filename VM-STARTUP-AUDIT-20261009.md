# Debian VM black-screen startup investigation — 2026-10-09

The supplied `raw/linex_My_Debian_Workstation_logs.txt` shows a slow startup, not
a guest crash. The user confirms pressing Stop/closing the session. The guest
then reaps its processes, remounts the disk readonly, emits CLEAN_STOP and powers
down. No OOM, fatal signal, kernel panic or unrequested process exit is recorded.

| Phone time | Observation |
| --- | --- |
| 09:55:43 | Image installation completes after recovered GitHub DNS/download errors. |
| 09:56:05 | First launch correctly rejects RAM exceeding the current device budget. |
| 09:56:36 | VM launches with 1392 MiB and two emulated CPUs. |
| 09:57:25 | VNC listens at 1920×1080/60 FPS; the app prematurely calls this desktop readiness. |
| 09:57:29–35 | VNC authenticates and GLES reports presentation; this does not establish nonblack XFCE content. |
| 09:59:06 | XFCE's first initialization diagnostics appear, about 100 seconds after the readiness label. |
| 09:59:45–48 | User-requested shutdown completes cleanly while XFCE is still initializing. |

The guest uses software CPU emulation and software graphics. This log cannot
quantify a particular bottleneck or prove that Android composition hid a completed
desktop. It does establish that the app hid startup feedback on its initial black
frame before XFCE initialized. The observed delay is substantially longer than CI;
emulator acceptance does not predict Snapdragon 732G startup time.

`Could not find any render nodes`/DRI3 warnings are consistent with the unaccelerated
guest. The ICE directory, accessibility-bus and missing GPG/SSH agent warnings also
occur in the passing exact-APK Android run37780260552; they do not independently
prove a fatal desktop failure. There is no evidence here that more download retries
or privileged Android settings would fix this launch.

## Change

- The presenter retains a startup content flag only after an actual GLES draw of
  a frame with more than sparse nonblack pixels. A bounded grid samples at most
  2304 pixels; this is a startup hint, not an application-health check. Cursor-only
  black frames keep the elapsed progress indicator and instance-log controls visible.
- Connection failure stops the startup indicator; a connection handshake alone
  cannot hide it. Once content arrives, the flag remains latched so a later black
  video or wallpaper does not reopen startup UI.
- Service logs distinguish display-server readiness from visible desktop content.
- Newly created/default VMs use 1280×720 and 30 FPS. Existing custom resolution,
  FPS, RAM and disks are retained. For the reported existing instance, select
  720p/30 FPS and 1024 MiB RAM while stopped if a lower workload is desired.

This patch retains the exact accepted kernel, factory, native engines and image
URLs from v0.6.0. No image redownload or instance reset is required. It does not
remove the software-emulation cost. First-start completion and sustained phone
performance still require a new phone run; no fixed completion time is promised.

## Validation

Local app124 and console10 unit tests pass with zero failures/errors/skips,
including black/cursor-only versus dark desktop-panel frames at720p/1080p.
App/test APK packaging and lint pass (0errors). The actual EGL test now draws a
black frame before colored content and asserts the readiness flag and framebuffer
pixels across subsequent resize/context recovery.

[Android console run37885228252](https://github.com/lupixele/linex/actions/runs/37885228252),
source8ccbb513beae75231ce765cd5440f986cb325534, passes7 actual tests with zero
failures/errors/skips. Downloaded XML was checked independently. This validates
real GL pixels and the content latch, not composition or speed on the user's phone.
The exact signed update APK proof is recorded below when complete.

# Guest crashes after native display repair

Evidence: user-supplied `raw/crash with gpu fix .txt`, Android 13, app 0.5.1-dev
code20, Snapdragon732G / Adreno618. The raw log remains outside source control.

Native display startup is device-confirmed: connected at17:12:50 with a15FPS target,
then at17:13:35 with a60FPS target. This confirms host GLES presentation, not measured
FPS or GPU rendering inside Firefox. XFCE still reports guest llvmpipe rendering.

| Launch | Uptime at exit | Exit | Available Android memory after exit |
| --- | --- | --- | --- |
| 17:12:49 | 13 seconds | 137 / SIGKILL | 1,988 MiB; lowMemory=false |
| 17:13:34 | 65 seconds | 137 / SIGKILL | 1,688 MiB; lowMemory=false |

The first exit precedes Firefox output. The second follows Firefox audio/IPC warnings;
those warnings do not establish who killed the guest. No native-service failure or
requested stop is logged before either exit. Display disconnection follows guest cleanup.

Phantom-process monitoring is enabled. The second launch reaches52 visible app-UID
processes, including Android-managed processes, and23 in the initial guest group.
These lower bounds are not Android's monitored phantom count. Earlier system logs
proved phantom-process trimming, making recurrence a strong hypothesis; this new log
has no matching system kill record. Available memory does not rule out every OEM policy.

Confirmed code issues fixed in0.5.2:

- XFCE excluded `system-config-printer.desktop`, but the tray applet starts from
  `print-applet.desktop`. Both launches still logged that applet. The correct name
  is now suppressed without replacing user settings or following dangling symlinks.
- D-Bus `--fork` can create a separate session outside the launcher's group. It now
  uses `--nofork` as a direct background child, bounded readiness polling and reaping.
  Startup no longer exports an address merely because an empty PID file exists.
- Group-only shutdown missed children that changed groups. Linex records same-UID
  tracees of the verified PRoot PID while its start identity matches, retaining their
  PID/start-time identities through tracer death. It revalidates ownership immediately
  before group and individual signals. Recycled identities and unrelated Android
  processes are excluded. The bounded registry prefers visible owned children over
  inaccessible history. Resource logs add sanitized short names, without argv/env.

Tracking requires readable `/proc`. Children born and detached between samples can
be missed, and inaccessible history can be evicted at the registry bound. PRoot already
uses `--kill-on-exit`; its userland cleanup cannot run when the tracer itself is killed.
These changes need no root or Android setting changes, but cannot exempt unrestricted
guest workloads from Android policy. Actual crash prevention remains unverified.

Primary references: [OpenPrinting applet entry](https://github.com/OpenPrinting/system-config-printer/blob/master/print-applet.desktop.in),
[autostart filename precedence](https://specifications.freedesktop.org/autostart/latest/),
[D-Bus daemon support](https://dbus.freedesktop.org/doc/api/html/group__DBusSysdeps.html),
[Android13 process management](https://android.googlesource.com/platform/frameworks/base/+/android13-release/services/core/java/com/android/server/am/PhantomProcessList.java).

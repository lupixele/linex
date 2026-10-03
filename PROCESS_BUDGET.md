# Guest process defaults

Linex applies guest process defaults without Android root, shell permissions or
changes to global Android settings. They reduce Linux child-process demand; they
do not make PRoot exempt from Android process management.

On each startup, `process_budget.sh` recognizes standard Firefox and Firefox-ESR
installations under `/usr/lib`, `/usr/lib64` or `/opt/firefox`. It installs three
lower-priority application defaults:

- `dom.ipc.processCount=2` for the shared web content pool.
- `dom.ipc.processCount.webIsolated=1` **per site** for isolated content.
- `dom.ipc.processPrelaunch.enabled=false` to avoid speculative spare processes.

These are not a total-browser process cap. Firefox keeps its separate browser,
graphics, network, media and other process types, and different sites can each
require a content process. Site isolation, multiprocess browsing, graphics and
sandbox preferences are unchanged. [Mozilla process model](https://firefox-source-docs.mozilla.org/dom/ipc/process_model.html).

The script uses application `defaults/preferences/linex-process-budget.js`,
under the runtime's `browser` folder when present. Application preferences load
after packaged defaults. User values from `about:config`, `prefs.js` and
`user.js` take precedence; the script never edits those files. An existing
Linex defaults file, including a user customization or symlink, is preserved.
Other browsers and unsupported install paths are left unchanged.
[Mozilla preference loading](https://github.com/mozilla-firefox/firefox/blob/main/modules/libpref/Preferences.cpp),
[application directory provider](https://github.com/mozilla-firefox/firefox/blob/main/toolkit/xre/nsXREDirProvider.cpp),
[preference precedence](https://firefox-source-docs.mozilla.org/modules/libpref/index.html).

XFCE separately suppresses unused system applets via per-user autostart
overrides, preserving existing explicit overrides. Native X11 runs as an
Android-managed service rather than another guest X server process.

Android 13's AOSP implementation defaults to 32 monitored phantom processes
**across the monitored process list**, and sorts candidates using parent
process importance. Other apps and device policy can affect the available
budget. A foreground service improves parent importance; it does not exempt
arbitrary Linux child processes. Rootless PRoot cannot guarantee survival for
unlimited browser tabs or unrestricted Linux workloads on all phones.
[Android 13 process constants](https://android.googlesource.com/platform/frameworks/base/+/android13-release/services/core/java/com/android/server/am/ActivityManagerConstants.java),
[Android 13 phantom-process management](https://android.googlesource.com/platform/frameworks/base/+/android13-release/services/core/java/com/android/server/am/PhantomProcessList.java).

Verification: `scripts/test-process-budget.sh` checks unsupported browsers,
created defaults, preservation of user choices and custom defaults, repeated
startup, ESR and symlink overrides. `scripts/test-xfce-startup.sh` verifies
the per-user applet defaults. Actual Android process counts and crash behavior
still require testing on a device with normal system policy enabled.

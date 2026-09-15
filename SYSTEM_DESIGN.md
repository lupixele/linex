# SYSTEM_DESIGN.md — Linex (Autonomous Linux Container Manager for Android)

## 1. System Overview & Problem Statement
Existing solutions for running Linux on Android (e.g., Termux + PRoot + Termux-X11) require users to manually install packages, orchestrate background display daemons (`termux-x11 :1 -ac &`), configure environment variables (`DISPLAY=:1`), and manage multiple separate APKs.

**Linex** is an all-in-one Android container management platform (similar to LDPlayer / BlueStacks on Windows, or UTM on macOS). It packages:
1. A Jetpack Compose GUI Instance Manager (Hub).
2. Embedded rootless container engine (`libproot.so`).
3. Embedded X11 display server rendered directly onto an Android `SurfaceView`.
4. Embedded PulseAudio audio sink connected directly to Android AudioTrack/AAudio.
5. In-session Back-Gesture Navigation Sheet (shutdown, suspend, restart, input toggles, live telemetry).
6. Resumable execution loop via POSIX process freezing (`SIGSTOP`/`SIGCONT`).
7. Auto-detection for hardware mouse/keyboard and Samsung DeX / external displays.

---

## 2. High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Linex Android App                           │
│                                                                             │
│  ┌───────────────────────────────┐     ┌─────────────────────────────────┐  │
│  │     Hub Screen (Compose)      │     │    Session Screen (Compose)     │  │
│  │  - Instance Grid              │     │  - Embedded Hardware X11 View   │  │
│  │  - Create Instance Modal      │◄───►│  - Native Touch/Mouse Bridge    │  │
│  │  - Live Hardware Telemetry    │     │  - Back-Gesture Control Sheet   │  │
│  │  - Image Downloader & Unpack  │     │    (Shutdown, Suspend, Restart) │  │
│  └──────────────┬────────────────┘     └────────────────┬────────────────┘  │
│                 │                                       │                   │
│  ┌──────────────▼───────────────────────────────────────▼────────────────┐  │
│  │                    ContainerService (Foreground Service)              │  │
│  │  - Process Lifecycle Manager (START, SIGSTOP, SIGCONT, SIGTERM)       │  │
│  │  - Display Configuration Dispatcher (xrandr dynamic geometry)         │  │
│  │  - DeX / External Display State Monitor                               │  │
│  └──────────────────────────────┬────────────────────────────────────────┘  │
│                                 │ JNI / ProcessBridge                       │
│  ┌──────────────────────────────▼────────────────────────────────────────┐  │
│  │                        Native Engine Layer                            │  │
│  │  ┌──────────────────────┐  ┌───────────────────┐  ┌────────────────┐  │  │
│  │  │   Embedded X11 C     │  │     PRoot 5.x     │  │   PulseAudio   │  │  │
│  │  │   Display Server     │  │   (libproot.so)   │  │   AAudio Sink  │  │  │
│  │  └──────────┬───────────┘  └─────────┬─────────┘  └───────┬────────┘  │  │
│  └─────────────┼────────────────────────┼────────────────────┼───────────┘  │
│                │ UNIX Socket            │ Rootfs Bind Mount  │ Audio Socket │
│  ┌─────────────▼────────────────────────▼────────────────────▼───────────┐  │
│  │           Container Filesystem Sandbox (/data/data/.../instances/)    │  │
│  │  - Ubuntu 22.04 LTS (Jammy) / Debian 12 / Phosh Touch Shell           │  │
│  │  - Desktop Environments: XFCE4, GNOME Flashback, Full GNOME, Phosh    │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Core Subsystems & Technical Implementation

### 3.1 Instance Management & Multi-Container Isolation
* **Storage Model:** Each instance is stored under the app's isolated internal storage:
  `/data/user/0/com.linex.app/files/instances/{instance_id}/rootfs/`
* **Metadata Store:** SQLite database managed via Room (`instances.db`) tracking:
  - `id`: UUID
  - `name`: Human-readable label (e.g., "Ubuntu Workstation")
  - `distro`: Ubuntu Jammy 22.04 / Debian Bookworm
  - `desktop_env`: XFCE4, GNOME Flashback, GNOME Shell, Phosh
  - `display_mode`: Phone Native, 1080p, 720p, Custom (WxH), DeX Auto
  - `scaling_dpi`: Integer (96 to 240)
  - `input_mode`: Trackpad Emulation, Direct Touch, Pointer Lock
  - `state`: STOPPED, RUNNING, SUSPENDED
  - `last_snapshot_path`: Absolute path to cached screenshot

### 3.2 Instant Pause & Resume (Zero-Cold-Start Engine)
Android kernels do not permit unprivileged userland checkpointing via CRIU. Linex implements high-efficiency **Process Group Freezing**:
1. **Suspend Trigger:** When the user navigates away or taps "Suspend":
   - The native bridge queries the child process group ID (PGID) spawned by PRoot.
   - Issues `kill(-pgid, SIGSTOP)`.
   - The embedded X11 server grabs the final frame buffer and writes an optimized JPEG/PNG snapshot to cache.
   - The display surface is detached. Background CPU consumption drops to 0%.
   - ContainerService holds a persistent low-priority foreground notification with a lightweight partial wake-lock.
2. **Resume Trigger:** When the user taps "Resume":
   - The container surface re-attaches to the existing UNIX X11 domain socket (`@/tmp/.X11-unix/X0`).
   - The native bridge issues `kill(-pgid, SIGCONT)`.
   - Audio and input event dispatchers wake up instantly (<200ms latency).

### 3.3 The Back-Gesture Navigation Sheet
Replaces clunky floating buttons that interfere with desktop windows.
* Hooks Android 13+ `OnBackPressedCallback` / Compose `BackHandler`.
* Intercepts back navigation:
  - Container does **not** terminate.
  - Releases pointer capture (if physical mouse was grabbed).
  - Slides open a modal side sheet containing:
    - **Session Control:** Resume, Suspend (Freeze), Soft Reboot, Clean Shutdown.
    - **Input Control:** Virtual Keyboard toggle, Trackpad/Direct Touch switch.
    - **Display Tuning:** Dynamic resolution switcher, DPI scale selector.
    - **Live Diagnostics:** CPU load, RAM allocation, X11 frame rate.
  - Swiping the sheet back or tapping the dimmed session surface immediately dismisses the sheet and re-grabs pointer input.

### 3.4 Input Subsystem (Pointer & Keyboard Capture)
1. **Physical Mouse / Trackpad (OTG or Bluetooth):**
   - When detected, the view invokes Android's `View.requestPointerCapture()`.
   - Intercepts raw relative mouse motion events (`MotionEvent.AXIS_RELATIVE_X / Y`).
   - Translates directly into X11 core protocol motion events without Android system cursor interference.
   - Right-click (`BUTTON_SECONDARY`) and Middle-click (`BUTTON_TERTIARY`) pass unaltered to Linux.
2. **Physical Keyboard:**
   - Intercepts raw key events in `dispatchKeyEvent()`.
   - Passes standard keycodes (Super/Windows key, Alt+Tab, Ctrl combinations) directly to the XKB keymap engine, preventing Android from swallowing system shortcuts.
3. **Touchscreen:**
   - **Direct Touch Mode (Ubuntu Touch / Phosh):** Coordinates map 1:1 to display surface touch events.
   - **Trackpad Mode (Desktop XFCE / GNOME):** Relative finger drag drives virtual cursor; single tap = left-click, two-finger tap = right-click, two-finger drag = smooth scroll wheel.

### 3.5 Display Server & Samsung DeX Integration
* **Embedded X11 Surface:**
  - An in-process X11 server compiled from Termux-X11 C sources directly into a shared JNI library (`libxserver.so`).
  - Connects to an Android `SurfaceHolder` backing an OpenGL ES / Vulkan texture.
* **Samsung DeX / External Display Engine:**
  - Registers an Android `DisplayManager.DisplayListener`.
  - When an HDMI or wireless display connects, the app detects the secondary `Display`.
  - Fires an in-session `xrandr` geometry adjustment script into the container:
    ```bash
    xrandr --output default --mode "${WIDTH}x${HEIGHT}" --rate 60.00
    ```
  - Routes the X11 surface to the external monitor while turning the primary device screen into an interactive touch controller / trackpad.

### 3.6 Desktop Environments & Mobile Shells
1. **Ubuntu Desktop (XFCE4):** Default lightweight desktop, sub-400MB memory footprint, ideal for productivity and multitasking.
2. **GNOME Flashback (`gnome-session-flashback`):** Traditional GNOME 2 layout modernized on GTK3, zero systemd dependency, very low CPU usage.
3. **Modern GNOME Shell 44+:** Packaged for high-end chipsets with Turnip Vulkan / VirGL hardware acceleration.
4. **Ubuntu Touch (Phosh Mobile Shell):** Pure touch-first shell with mobile navigation gestures, app drawer, and auto-invoked Maliit on-screen keyboard.

---

## 4. Source Tree & Code Layout

```
Linex/
├── SYSTEM_DESIGN.md
├── build.gradle.kts
├── settings.gradle.kts
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/linex/app/
│   │       │   ├── MainActivity.kt
│   │       │   ├── core/
│   │       │   │   ├── ContainerManager.kt
│   │       │   │   ├── ProcessController.kt
│   │       │   │   ├── StorageEngine.kt
│   │       │   │   └── InputBridge.kt
│   │       │   ├── data/
│   │       │   │   ├── InstanceModel.kt
│   │       │   │   ├── DistroCatalog.kt
│   │       │   │   └── InstanceRepository.kt
│   │       │   ├── service/
│   │       │   │   └── LinuxContainerService.kt
│   │       │   └── ui/
│   │       │       ├── hub/
│   │       │       │   ├── HubScreen.kt
│   │       │       │   ├── InstanceCard.kt
│   │       │       │   └── CreateInstanceDialog.kt
│   │       │       ├── session/
│   │       │       │   ├── SessionScreen.kt
│   │       │       │   ├── X11SurfaceView.kt
│   │       │       │   └── BackGestureSidebar.kt
│   │       │       └── theme/
│   │       │           └── Theme.kt
│   │       ├── cpp/ (Native Engine)
│   │       │   ├── CMakeLists.txt
│   │       │   ├── proot_bridge/
│   │       │   └── xserver_bridge/
│   │       └── res/
│   │           └── values/
```

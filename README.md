# Linex 🐧

**All-in-One Autonomous Linux Container & Desktop Manager for Android.**

Run real Ubuntu and Debian desktop environments on your Android device right out of the box — **no Termux, no external X11 APK, no command lines, and zero root required.**

[![Release](https://img.shields.io/github/v/release/lupixele/linex?include_prereleases&label=beta)](https://github.com/lupixele/linex/releases/tag/v0.2.0-beta)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20(ARM64)-green.svg)](https://github.com/lupixele/linex)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## ⚡ Highlights

- 🚀 **Zero Terminal Setup:** Tap "Launch" and the app bootstraps PRoot, mounts filesystems, initializes display sockets, and starts the desktop.
- 📺 **Embedded Display Engine:** Native X11 server engine (`libXlorie.so`) renders directly onto an Android `SurfaceView` without needing Termux-X11.
- ⏸️ **Instant Pause & Resume:** Freeze container CPU usage to 0% with native POSIX `SIGSTOP`/`SIGCONT` process group signaling. Switch back in under 150ms with full state intact.
- 🔙 **Back-Gesture Control Sheet:** No floating overlay buttons. Use Android's native back gesture to slide out session controls (Resume, Freeze, Reboot, Shutdown, Virtual Keyboard, Touch Mode).
- 🖱️ **Hardware Peripherals:**
  - **Mouse / Trackpad:** Automatic `requestPointerCapture()` locks the cursor, passing raw motion deltas and native right/middle clicks.
  - **Keyboard:** Intercepts hardware keys (Super/Windows, Alt+Tab, Ctrl shortcuts) before Android consumes them.
  - **Touchscreen:** Seamlessly switch between **Virtual Trackpad** (relative cursor motion) and **Direct Touch** (absolute coordinates).
- 🖥️ **Samsung DeX & External Display Ready:** Automatically detects secondary screens and fires live `xrandr` geometry updates to fill standard 16:9 displays without black bars.
- 📦 **Pre-Configured Environments:** Integrated download manager for full Ubuntu 22.04 LTS (Jammy XFCE4) and minimal base images.

---

## 📥 Download & Installation

1. Go to the [**Latest Releases**](https://github.com/lupixele/linex/releases/tag/v0.1.0-beta).
2. Download **`linex-v0.1.0-beta.apk`**.
3. Install the APK on your Android device (Android 8.0+ / ARM64 recommended).
4. Launch the app, pick **Ubuntu Desktop (XFCE4)** or **Ubuntu Mobile (Phosh)**, and hit **Launch**.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 Linex All-in-One APK                   │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │             Container Manager UI (Compose)            │  │
│  │  - Multi-instance management (Create, Clone, Delete)  │  │
│  │  - Resolution profiles (Native, 1080p, 720p, DeX)     │  │
│  │  - Real-time rootfs download pipeline with progress   │  │
│  │  - Back-Gesture Session Navigation Sheet              │  │
│  └──────────────────────────┬────────────────────────────┘  │
│                             │ JNI Signals & Bridge          │
│  ┌──────────────────────────▼────────────────────────────┐  │
│  │             Embedded Engine Subsystems                │  │
│  │  1. PRoot 5.1.x (libproot.so + libtalloc.so)          │  │
│  │     - Simulates rootfs/chroot without Android root    │  │
│  │  2. Embedded X Server Surface (libXlorie.so)          │  │
│  │     - Hardware-accelerated X11 rendering via JNI      │  │
│  │  3. Input Engine                                      │  │
│  │     - Pointer capture & raw scancode translator       │  │
│  │  4. Process Controller (SIGSTOP / SIGCONT)            │  │
│  │     - Instant freeze / unfreeze container engine      │  │
│  └──────────────────────────┬────────────────────────────┘  │
│                             │ mounts & boots                │
│  ┌──────────────────────────▼────────────────────────────┐  │
│  │ Container Storage (/data/data/.../instances/<id>/)    │  │
│  │  - rootfs/ (Ubuntu 22.04 LTS Jammy)                   │  │
│  │  - tmp/    (UNIX domain sockets: .X11-unix, etc.)     │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

For the complete design document, see [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md).

---

## 🛠️ Building from Source

### Prerequisites
- **JDK 17+** (e.g., Microsoft OpenJDK 17)
- **Android SDK** with `platforms;android-34`, `build-tools;34.0.0`, `cmake;3.22.1`, and `ndk;26.1.10909125`

### Build Steps
```bash
# Clone the repository
git clone https://github.com/lupixele/linex.git
cd linex

# Configure your SDK directory in local.properties
echo "sdk.dir=/path/to/your/android/sdk" > local.properties

# Build debug APK
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📜 License & Credits

- PRoot engine adapted from [Termux PRoot](https://github.com/termux/proot).
- Embedded X11 rendering adapted from [Termux-X11](https://github.com/termux/termux-x11).
- Distro packaging inspired by [Udroid](https://github.com/RandomCoderOrg/fs-manager-udroid).
- Licensed under the [MIT License](LICENSE).

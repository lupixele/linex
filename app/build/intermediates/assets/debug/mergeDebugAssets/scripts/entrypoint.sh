#!/bin/sh
# ==============================================================================
# LinuxDroid Container Entrypoint Launcher
# Host-side entrypoint executed on Android to initialize runtime directories,
# sanitize X11 sockets, and bootstrap the PRoot isolation layer.
# ==============================================================================

set -e

ROOTFS_PATH="$1"
TMP_PATH="$2"
START_COMMAND="$3"
DISPLAY_WIDTH="${4:-1920}"
DISPLAY_HEIGHT="${5:-1080}"
DPI_SCALING="${6:-120}"
EXTRA_BINDS="${7:-}"
BOOTSTRAP_DIR="${8:-}"
if [ -z "$BOOTSTRAP_DIR" ]; then
    BOOTSTRAP_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd || dirname "$0")"
fi

if [ -z "$ROOTFS_PATH" ] || [ -z "$TMP_PATH" ]; then
    echo "[LinuxDroid:Entrypoint] ERROR: Missing required arguments."
    echo "Usage: $0 <rootfs_path> <tmp_path> <start_command> [width] [height] [dpi] [extra_binds] [bootstrap_dir]"
    exit 1
fi

echo "[LinuxDroid:Entrypoint] Initializing container runtime environment..."
echo "[LinuxDroid:Entrypoint] Rootfs: $ROOTFS_PATH"
echo "[LinuxDroid:Entrypoint] Tmp: $TMP_PATH"
echo "[LinuxDroid:Entrypoint] Display: ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} @ ${DPI_SCALING} DPI"
echo "[LinuxDroid:Entrypoint] Start command: $START_COMMAND"

# 1. Sanitize & Prepare Host Runtime Directories
mkdir -p "$TMP_PATH"
mkdir -p "$TMP_PATH/.X11-unix"
mkdir -p "$TMP_PATH/pulse"
mkdir -p "$TMP_PATH/runtime-root"
chmod 1777 "$TMP_PATH" 2>/dev/null || true
chmod 1777 "$TMP_PATH/.X11-unix" 2>/dev/null || true
chmod 0700 "$TMP_PATH/runtime-root" 2>/dev/null || true

# 2. Clean stale X11 locks from previous abnormal terminations
echo "[LinuxDroid:Entrypoint] Purging stale X11 lock files..."
rm -f "$TMP_PATH/.X0-lock" "$TMP_PATH/.X1-lock"
rm -f "$TMP_PATH/.X11-unix/X0" "$TMP_PATH/.X11-unix/X1"

# 3. Verify Rootfs Integrity
if [ ! -d "$ROOTFS_PATH" ]; then
    echo "[LinuxDroid:Entrypoint] ERROR: Rootfs directory does not exist: $ROOTFS_PATH"
    exit 2
fi

if [ ! -f "$ROOTFS_PATH/bin/sh" ] && [ ! -f "$ROOTFS_PATH/usr/bin/sh" ]; then
    echo "[LinuxDroid:Entrypoint] ERROR: No valid shell found inside rootfs!"
    exit 3
fi

# 4. Check if first boot setup is required
if [ ! -f "$ROOTFS_PATH/.linuxdroid_initialized" ]; then
    echo "[LinuxDroid:Entrypoint] First-boot marker not found. Running first_boot_setup.sh..."
    if [ -f "$BOOTSTRAP_DIR/first_boot_setup.sh" ]; then
        sh "$BOOTSTRAP_DIR/first_boot_setup.sh" "$ROOTFS_PATH" || {
            echo "[LinuxDroid:Entrypoint] WARNING: First boot setup encountered non-fatal warnings."
        }
    fi
fi

# 5. Resolve PRoot Binary
PROOT_BIN=""
if [ -n "$LINUXDROID_PROOT_BIN" ] && [ -x "$LINUXDROID_PROOT_BIN" ]; then
    PROOT_BIN="$LINUXDROID_PROOT_BIN"
elif [ -n "$APP_LIB_DIR" ] && [ -f "$APP_LIB_DIR/libproot.so" ]; then
    PROOT_BIN="$APP_LIB_DIR/libproot.so"
elif command -v proot >/dev/null 2>&1; then
    PROOT_BIN="$(command -v proot)"
fi

if [ -z "$PROOT_BIN" ]; then
    echo "[LinuxDroid:Entrypoint] ERROR: PRoot executable (libproot.so) not located."
    exit 4
fi

echo "[LinuxDroid:Entrypoint] PRoot binary resolved: $PROOT_BIN"

# 6. Initialize X11 Socket Environment
DISPLAY_NUM="${DISPLAY_NUM:-0}"
if [ -f "$BOOTSTRAP_DIR/x11_socket_setup.sh" ]; then
    echo "[LinuxDroid:Entrypoint] Calling x11_socket_setup.sh on display :${DISPLAY_NUM}..."
    sh "$BOOTSTRAP_DIR/x11_socket_setup.sh" "$TMP_PATH" "$DISPLAY_NUM" || {
        echo "[LinuxDroid:Entrypoint] WARNING: x11_socket_setup.sh returned non-zero status"
    }
fi

# 7. Execute PRoot Runner
CONFIG_DIR="${CONFIG_DIR:-${BOOTSTRAP_DIR%/scripts}/config}"
if [ ! -d "$CONFIG_DIR" ] && [ -d "$BOOTSTRAP_DIR/../config" ]; then
    CONFIG_DIR="$BOOTSTRAP_DIR/../config"
fi
export CONFIG_DIR
export ROOTFS_PATH
export TMP_PATH
export START_COMMAND
export DISPLAY_NUM
export DISPLAY_WIDTH
export DISPLAY_HEIGHT
export DPI_SCALING
export EXTRA_BINDS
export BOOTSTRAP_DIR
export PROOT_BIN

exec sh "$BOOTSTRAP_DIR/proot_runner.sh"

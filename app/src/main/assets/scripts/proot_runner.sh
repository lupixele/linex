#!/bin/sh
# ==============================================================================
# LinuxDroid PRoot Runner
# Assembles the full PRoot invocation with optimal mount points, SysV IPC,
# link2symlink emulation, and isolated process sandboxing on Android.
# ==============================================================================

set -e

# Validate required variables
: "${ROOTFS_PATH:?ROOTFS_PATH is required}"
: "${TMP_PATH:?TMP_PATH is required}"
: "${PROOT_BIN:?PROOT_BIN is required}"
: "${BOOTSTRAP_DIR:?BOOTSTRAP_DIR is required}"

DISPLAY_NUM="${DISPLAY_NUM:-0}"
DISPLAY_WIDTH="${DISPLAY_WIDTH:-1920}"
DISPLAY_HEIGHT="${DISPLAY_HEIGHT:-1080}"
DPI_SCALING="${DPI_SCALING:-120}"

# Resolve original desktop start command
ORIGINAL_START_CMD="${START_CMD:-${START_COMMAND:-startxfce4}}"
export DESKTOP_START_CMD="$ORIGINAL_START_CMD"
START_CMD="/linuxdroid/container_init.sh"

echo "[LinuxDroid:PRoot] Constructing PRoot isolation boundary..."

# Ensure internal container script directory exists and is executable
IN_CONTAINER_DIR="$BOOTSTRAP_DIR/in_container"
mkdir -p "$IN_CONTAINER_DIR"
chmod 755 "$IN_CONTAINER_DIR" 2>/dev/null || true
chmod +x "$IN_CONTAINER_DIR"/*.sh 2>/dev/null || true

# Locate Config directory on host
CONFIG_DIR="${CONFIG_DIR:-${BOOTSTRAP_DIR%/scripts}/config}"
if [ ! -d "$CONFIG_DIR" ] && [ -d "$BOOTSTRAP_DIR/../config" ]; then
    CONFIG_DIR="$BOOTSTRAP_DIR/../config"
elif [ ! -d "$CONFIG_DIR" ] && [ -d "$BOOTSTRAP_DIR/config" ]; then
    CONFIG_DIR="$BOOTSTRAP_DIR/config"
fi

# Ensure /linuxdroid/config exists inside the bind mount
if [ -d "$CONFIG_DIR" ]; then
    mkdir -p "$IN_CONTAINER_DIR/config"
    cp -r "$CONFIG_DIR"/* "$IN_CONTAINER_DIR/config/" 2>/dev/null || true
fi

# Ensure host-side DNS and host files exist for bind-mounting
RESOLV_CONF="$TMP_PATH/resolv.conf"
HOSTS_FILE="$TMP_PATH/hosts"

if [ ! -f "$RESOLV_CONF" ]; then
    if [ -f "$CONFIG_DIR/resolv.conf" ]; then
        cp -f "$CONFIG_DIR/resolv.conf" "$RESOLV_CONF"
    else
        cat << 'EOF' > "$RESOLV_CONF"
nameserver 1.1.1.1
nameserver 8.8.8.8
nameserver 8.8.4.4
options timeout:2 attempts:3
EOF
    fi
fi

if [ ! -f "$HOSTS_FILE" ]; then
    if [ -f "$CONFIG_DIR/hosts" ]; then
        cp -f "$CONFIG_DIR/hosts" "$HOSTS_FILE"
    else
        cat << 'EOF' > "$HOSTS_FILE"
127.0.0.1   localhost localhost.localdomain linuxdroid
::1         localhost ip6-localhost ip6-loopback
EOF
    fi
fi

# Build PRoot Argument List
PROOT_ARGS=""

# 1. Root and rootfs specification
PROOT_ARGS="$PROOT_ARGS -0"
PROOT_ARGS="$PROOT_ARGS -r $ROOTFS_PATH"
PROOT_ARGS="$PROOT_ARGS -w /root"

# 2. Kernel and filesystem emulation flags
PROOT_ARGS="$PROOT_ARGS --kill-on-exit"
PROOT_ARGS="$PROOT_ARGS --link2symlink"
PROOT_ARGS="$PROOT_ARGS --sysvipc"

# 3. Core Android Virtual Filesystem Bind Mounts
PROOT_ARGS="$PROOT_ARGS -b /dev"
PROOT_ARGS="$PROOT_ARGS -b /proc"
PROOT_ARGS="$PROOT_ARGS -b /sys"

# 4. IPC, Temp, and Sockets
PROOT_ARGS="$PROOT_ARGS -b $TMP_PATH:/tmp"
PROOT_ARGS="$PROOT_ARGS -b $RESOLV_CONF:/etc/resolv.conf"
PROOT_ARGS="$PROOT_ARGS -b $HOSTS_FILE:/etc/hosts"

# 5. LinuxDroid Runtime In-Container Tools
PROOT_ARGS="$PROOT_ARGS -b $IN_CONTAINER_DIR:/linuxdroid"

# 6. Android Shared Storage Mounts (if accessible)
if [ -d "/sdcard" ] && [ -r "/sdcard" ]; then
    PROOT_ARGS="$PROOT_ARGS -b /sdcard:/sdcard"
elif [ -d "/storage/emulated/0" ] && [ -r "/storage/emulated/0" ]; then
    PROOT_ARGS="$PROOT_ARGS -b /storage/emulated/0:/sdcard"
fi

# 7. Dynamic Extra Binds (passed from ContainerManager, e.g. custom mounts)
if [ -n "$EXTRA_BINDS" ]; then
    for bind in $EXTRA_BINDS; do
        PROOT_ARGS="$PROOT_ARGS -b $bind"
    done
fi

# 8. Set Up Environment for Container Processes
export PROOT_TMP_DIR="$TMP_PATH"
export HOME="/root"
export USER="root"
export LOGNAME="root"
export TERM="xterm-256color"
export LANG="C.UTF-8"
export LC_ALL="C.UTF-8"
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export DISPLAY=":${DISPLAY_NUM}"
export PULSE_SERVER="tcp:127.0.0.1:4713"
export XDG_RUNTIME_DIR="/tmp/runtime-root"
export TMPDIR="/tmp"
export MOZ_FAKE_NO_SANDBOX="1"
export CHROME_DEVEL_SANDBOX=""
export QT_X11_NO_MITSHM="1"
export LIBGL_ALWAYS_SOFTWARE="1"
export LINUXDROID_WIDTH="$DISPLAY_WIDTH"
export LINUXDROID_HEIGHT="$DISPLAY_HEIGHT"
export LINUXDROID_DPI="$DPI_SCALING"
export LINUXDROID_START_COMMAND="$DESKTOP_START_CMD"
export DESKTOP_START_CMD

echo "[LinuxDroid:PRoot] Spawning container with init script: /linuxdroid/container_init.sh (desktop command: $DESKTOP_START_CMD)"

# Execute PRoot passing execution to the in-container supervisor
exec "$PROOT_BIN" $PROOT_ARGS /linuxdroid/container_init.sh

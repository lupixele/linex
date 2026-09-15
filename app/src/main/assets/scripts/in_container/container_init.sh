#!/bin/sh
# ==============================================================================
# Linex In-Container Init & Supervisor Daemon
# Runs inside the PRoot rootfs to initialize system services, IPC, D-Bus,
# PulseAudio, and supervise the desktop environment process.
# ==============================================================================

set -e

echo "[Linex:ContainerInit] In-container bootstrap commencing..."

# 1. Base Environment Sanity
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export HOME="/root"
export USER="root"
export LOGNAME="root"
export TERM="${TERM:-xterm-256color}"
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
export DISPLAY="${DISPLAY:-:0}"
export PULSE_SERVER="${PULSE_SERVER:-tcp:127.0.0.1:4713}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-root}"
export TMPDIR="/tmp"

# 2. Setup POSIX Shared Memory (/dev/shm)
mkdir -p /tmp/shm
chmod 1777 /tmp/shm
if [ ! -d /dev/shm ]; then
    ln -s /tmp/shm /dev/shm 2>/dev/null || true
fi

# 3. Setup Runtime Directory
mkdir -p "$XDG_RUNTIME_DIR"
chmod 0700 "$XDG_RUNTIME_DIR"

# 4. Generate Machine ID if missing
if [ ! -f /etc/machine-id ] && [ ! -f /var/lib/dbus/machine-id ]; then
    echo "[Linex:ContainerInit] Generating machine ID..."
    if command -v dbus-uuidgen >/dev/null 2>&1; then
        dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true
    else
        cat /proc/sys/kernel/random/uuid | tr -d '-' > /etc/machine-id 2>/dev/null || true
    fi
    mkdir -p /var/lib/dbus
    ln -sf /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true
fi

# 5. D-Bus Session Daemon Setup
DBUS_PID=""
if command -v dbus-daemon >/dev/null 2>&1; then
    echo "[Linex:ContainerInit] Initializing D-Bus Session Bus..."
    rm -f /tmp/dbus-session-socket
    dbus-daemon --session --fork --address="unix:path=/tmp/dbus-session-socket" --print-pid > /tmp/dbus.pid 2>/dev/null || true
    if [ -f /tmp/dbus.pid ]; then
        DBUS_PID="$(cat /tmp/dbus.pid)"
        export DBUS_SESSION_BUS_ADDRESS="unix:path=/tmp/dbus-session-socket"
        echo "[Linex:ContainerInit] D-Bus started with PID $DBUS_PID"
    fi
elif command -v dbus-launch >/dev/null 2>&1; then
    eval "$(dbus-launch --sh-syntax --exit-with-session)"
fi

# 6. PulseAudio Client Configuration
mkdir -p "$HOME/.config/pulse"
cat << 'EOF' > "$HOME/.config/pulse/client.conf"
default-server = tcp:127.0.0.1:4713
autospawn = no
EOF

# 7. Await X11 Display Server Socket
echo "[Linex:ContainerInit] Verifying X11 display socket at /tmp/.X11-unix/X0..."
WAIT_COUNT=0
while [ ! -e "/tmp/.X11-unix/X0" ] && [ $WAIT_COUNT -lt 30 ]; do
    sleep 0.1
    WAIT_COUNT=$((WAIT_COUNT + 1))
done

if [ -e "/tmp/.X11-unix/X0" ]; then
    echo "[Linex:ContainerInit] Connected to X11 socket successfully."
else
    echo "[Linex:ContainerInit] WARNING: X11 socket not detected after 3s. Proceeding anyway..."
fi

# 8. Configure Screen Geometry & DPI via xrandr / xrdb
if [ -n "$LINUXDROID_WIDTH" ] && [ -n "$LINUXDROID_HEIGHT" ]; then
    if command -v xrandr >/dev/null 2>&1; then
        echo "[Linex:ContainerInit] Setting display geometry to ${LINUXDROID_WIDTH}x${LINUXDROID_HEIGHT}..."
        xrandr --output default --mode "${LINUXDROID_WIDTH}x${LINUXDROID_HEIGHT}" 2>/dev/null || \
        xrandr -s "${LINUXDROID_WIDTH}x${LINUXDROID_HEIGHT}" 2>/dev/null || true
    fi
fi

if [ -n "$LINUXDROID_DPI" ]; then
    if command -v xrdb >/dev/null 2>&1; then
        echo "[Linex:ContainerInit] Setting Xft.dpi to $LINUXDROID_DPI..."
        echo "Xft.dpi: $LINUXDROID_DPI" | xrdb -merge 2>/dev/null || true
    fi
fi

# 9. Signal Handling & Clean Shutdown Traps
SESSION_PID=""

cleanup() {
    echo "[Linex:ContainerInit] Clean shutdown signal intercepted. Gracefully terminating processes..."
    
    if [ -n "$SESSION_PID" ] && kill -0 "$SESSION_PID" 2>/dev/null; then
        echo "[Linex:ContainerInit] Sending SIGTERM to desktop session (PID $SESSION_PID)..."
        kill -15 "$SESSION_PID" 2>/dev/null || true
        wait "$SESSION_PID" 2>/dev/null || true
    fi

    if [ -n "$DBUS_PID" ] && kill -0 "$DBUS_PID" 2>/dev/null; then
        echo "[Linex:ContainerInit] Terminating D-Bus daemon (PID $DBUS_PID)..."
        kill -15 "$DBUS_PID" 2>/dev/null || true
    fi

    echo "[Linex:ContainerInit] Flushing filesystem buffers..."
    sync 2>/dev/null || true
    
    # Clean socket and locks
    rm -f /tmp/dbus-session-socket /tmp/dbus.pid
    echo "[Linex:ContainerInit] Container shutdown sequence complete."
    exit 0
}

trap cleanup SIGTERM SIGINT SIGHUP

# 10. Execute Target Desktop / User Command
CMD="${DESKTOP_START_CMD:-${LINUXDROID_START_COMMAND:-/linex/start_xfce.sh}}"

# If requested command is startxfce4, redirect to the container launcher wrapper
if [ "$CMD" = "startxfce4" ] && [ -f "/linex/start_xfce.sh" ]; then
    CMD="/linex/start_xfce.sh"
elif [ "$CMD" = "gnome-session-flashback" ] && [ -f "/linex/start_gnome_flashback.sh" ]; then
    CMD="/linex/start_gnome_flashback.sh"
elif [ "$CMD" = "phosh" ] && [ -f "/linex/start_phosh.sh" ]; then
    CMD="/linex/start_phosh.sh"
fi

echo "[Linex:ContainerInit] Launching primary desktop payload: $CMD"

# Launch in background and wait so signal traps remain active
sh -c "$CMD" &
SESSION_PID=$!

echo "[Linex:ContainerInit] Session active under PID $SESSION_PID. Awaiting termination."

wait "$SESSION_PID"
EXIT_CODE=$?

echo "[Linex:ContainerInit] Session process exited with code $EXIT_CODE."
cleanup

#!/bin/sh
unset LD_LIBRARY_PATH LD_PRELOAD
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

# 9. Signal Handling & Clean Shutdown Traps
SESSION_PID=""
VNC_PID=""
DBUS_PID=""

cleanup() {
    # Preserve the desktop/startup result even if a cleanup command fails.
    SHUTDOWN_STATUS="$1"
    trap - 0
    trap '' TERM INT HUP
    set +e
    echo "[Linex:ContainerInit] Gracefully terminating processes..."

    if [ -n "$SESSION_PID" ] && kill -0 "$SESSION_PID" 2>/dev/null; then
        echo "[Linex:ContainerInit] Sending SIGTERM to desktop session (PID $SESSION_PID)..."
        kill -15 "$SESSION_PID" 2>/dev/null || true
        wait "$SESSION_PID" 2>/dev/null || true
    fi

    if [ -n "$VNC_PID" ] && kill -0 "$VNC_PID" 2>/dev/null; then
        echo "[Linex:ContainerInit] Stopping embedded display server..."
        kill -15 "$VNC_PID" 2>/dev/null || true
        wait "$VNC_PID" 2>/dev/null || true
    fi

    if [ -n "$DBUS_PID" ]; then
        echo "[Linex:ContainerInit] Terminating D-Bus daemon (PID $DBUS_PID)..."
        kill -15 "$DBUS_PID" 2>/dev/null || true
        wait "$DBUS_PID" 2>/dev/null || true
    fi

    echo "[Linex:ContainerInit] Flushing filesystem buffers..."
    sync 2>/dev/null || true

    # Clean socket and locks
    rm -f /tmp/dbus-session-socket /tmp/dbus.pid /tmp/dbus.address /tmp/linex-vnc.secret /tmp/linex-vnc.passwd
    echo "[Linex:ContainerInit] Container shutdown sequence complete."
    exit "$SHUTDOWN_STATUS"
}

# POSIX sh (including dash) requires signal names without the SIG prefix.
trap 'cleanup "$?"' 0
trap 'exit 143' TERM
trap 'exit 130' INT
trap 'exit 129' HUP

# 2. Verify the host runner supplied writable POSIX shared memory.
# A symlink in Android's read-only /dev cannot repair a missing bind.
if [ ! -d /dev/shm ] || [ ! -w /dev/shm ] || [ ! -x /dev/shm ]; then
    echo "[Linex:ContainerInit] ERROR: /dev/shm is unavailable or not writable. Restart the instance to refresh runtime mounts."
    exit 1
fi
echo "[Linex:ContainerInit] Shared memory directory is writable."

# 3. Setup Runtime Directory
echo "[Linex:Network] Checking guest DNS (a failed check does not stop desktop startup)..."
if command -v timeout >/dev/null 2>&1 && command -v getent >/dev/null 2>&1; then
    if timeout 8 getent ahosts example.com >/dev/null 2>&1; then
        echo "[Linex:Network] Guest DNS lookup succeeded. This does not verify browser or HTTPS connectivity."
    else
        echo "[Linex:Network] Guest DNS lookup failed or timed out. Check the active Android network, VPN and resolver settings."
    fi
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
    . /linex/session_bus.sh
    start_session_bus
elif command -v dbus-launch >/dev/null 2>&1; then
    eval "$(dbus-launch --sh-syntax --exit-with-session)"
fi

# 6. PulseAudio Client Configuration
mkdir -p "$HOME/.config/pulse"
cat << 'EOF' > "$HOME/.config/pulse/client.conf"
default-server = tcp:127.0.0.1:4713
autospawn = no
EOF

# 7. Start the app's local display, or wait for an externally managed X server.
if [ "${LINEX_EMBEDDED_DISPLAY:-0}" = "1" ]; then
    . /linex/start_embedded_display.sh
    start_embedded_display
else
# 7. Await X11 Display Server Socket
echo "[Linex:ContainerInit] Verifying X11 display socket at /tmp/.X11-unix/X0..."
WAIT_COUNT=0
while [ ! -e "/tmp/.X11-unix/X0" ] && [ $WAIT_COUNT -lt 30 ]; do
    sleep 0.1
    WAIT_COUNT=$((WAIT_COUNT + 1))
done

if [ -S "/tmp/.X11-unix/X0" ]; then
    echo "[Linex:ContainerInit] Connected to X11 socket successfully."
else
    if [ "${LINEX_DISPLAY_BACKEND:-rfb}" = "native_x11" ]; then
        echo "[Linex:ContainerInit] ERROR: Native X11 display socket is unavailable."
        exit 1
    fi
    echo "[Linex:ContainerInit] WARNING: X11 socket not detected after 3s. Proceeding anyway..."
fi

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

# 10. Execute Target Desktop / User Command
# Browser process demand contributes to Android's global native-child budget.
# Apply guest-only defaults before any desktop can launch a browser; never
# require root access or modify Android's system settings.
if [ -f /linex/process_budget.sh ]; then
    . /linex/process_budget.sh
    apply_browser_process_defaults
fi
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

# A failing wait must not trigger set -e before its status is captured.
EXIT_CODE=0
wait "$SESSION_PID" || EXIT_CODE=$?
SESSION_PID=""

echo "[Linex:ContainerInit] Session process exited with code $EXIT_CODE."
exit "$EXIT_CODE"

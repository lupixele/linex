#!/bin/sh
# ==============================================================================
# LinuxDroid X11 Socket Configuration & Validation Helper
# Prepares the UNIX domain socket directory, cleans stale locks, and manages
# the socket bridge between host Android surface and PRoot guest X clients.
# ==============================================================================

set -e

TMP_PATH="$1"
DISPLAY_NUM="${2:-0}"

if [ -z "$TMP_PATH" ]; then
    echo "Usage: $0 <tmp_path> [display_num]"
    exit 1
fi

X11_DIR="$TMP_PATH/.X11-unix"
SOCKET_FILE="$X11_DIR/X${DISPLAY_NUM}"
LOCK_FILE="$TMP_PATH/.X${DISPLAY_NUM}-lock"

echo "[LinuxDroid:X11Setup] Initializing X11 socket environment on display :${DISPLAY_NUM}..."

# Create socket directories with POSIX sticky permissions
mkdir -p "$X11_DIR"
chmod 1777 "$TMP_PATH" 2>/dev/null || true
chmod 1777 "$X11_DIR" 2>/dev/null || true

# Remove stale locks
if [ -f "$LOCK_FILE" ]; then
    echo "[LinuxDroid:X11Setup] Removing stale X11 lock: $LOCK_FILE"
    rm -f "$LOCK_FILE"
fi

if [ -S "$SOCKET_FILE" ] || [ -f "$SOCKET_FILE" ]; then
    echo "[LinuxDroid:X11Setup] Purging dangling socket: $SOCKET_FILE"
    rm -f "$SOCKET_FILE"
fi

echo "[LinuxDroid:X11Setup] X11 socket directory ready at: $X11_DIR"
exit 0

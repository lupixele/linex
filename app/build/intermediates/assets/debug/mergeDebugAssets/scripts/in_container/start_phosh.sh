#!/bin/sh
# ==============================================================================
# LinuxDroid Phosh Mobile Touch Shell Launcher
# Wayland/XWayland mobile environment adapted for touchscreens.
# ==============================================================================

set -e

export XDG_CURRENT_DESKTOP="Phosh:GNOME"
export DESKTOP_SESSION="phosh"
export GDK_BACKEND="x11"

# Execute phosh shell
if command -v phosh >/dev/null 2>&1; then
    exec phosh
elif command -v phoc >/dev/null 2>&1; then
    exec phoc -E phosh
else
    echo "[LinuxDroid:Phosh] phosh binary not found, falling back to xterm..."
    exec xterm
fi

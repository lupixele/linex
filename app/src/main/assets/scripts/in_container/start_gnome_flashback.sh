#!/bin/sh
# ==============================================================================
# Linex GNOME Flashback Session Launcher
# Classic GNOME panel interface with Metacity window manager.
# Low overhead, ideal for keyboard/mouse multi-window workflows.
# ==============================================================================

set -e

# Disable screen blanking
if command -v xset >/dev/null 2>&1; then
    xset s off 2>/dev/null || true
    xset -dpms 2>/dev/null || true
fi

export XDG_CURRENT_DESKTOP="GNOME-Flashback:GNOME"
export DESKTOP_SESSION="gnome-flashback-metacity"

if ! command -v gnome-session-flashback >/dev/null 2>&1; then
    echo "[Linex:GNOME] ERROR: gnome-session-flashback is missing. Install GNOME Flashback in this instance or choose an installed desktop command."
    exit 127
fi
exec gnome-session-flashback

#!/bin/sh
# ==============================================================================
# Linex XFCE4 Session Launcher
# Starts XFCE4 with lightweight defaults while preserving desktop preferences.
# ==============================================================================

set -e

# Disable screen blanking & DPMS
if command -v xset >/dev/null 2>&1; then
    xset s off 2>/dev/null || true
    xset -dpms 2>/dev/null || true
fi

# Set default cursor
if command -v xsetroot >/dev/null 2>&1; then
    xsetroot -cursor_name left_ptr 2>/dev/null || true
fi

# Export desktop session properties
export XDG_CURRENT_DESKTOP="XFCE"
export DESKTOP_SESSION="xfce"

if ! command -v xfce4-session >/dev/null 2>&1; then
    echo "[Linex:XFCE] ERROR: xfce4-session is missing. Install XFCE in this instance or choose an installed desktop command."
    exit 127
fi

# These hardware/system-service applets cannot work inside a rootless guest.
# XDG per-user overrides avoid modifying distro packages, and preserve explicit
# user overrides (including users who deliberately enable an applet).
AUTOSTART_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/autostart"
mkdir -p "$AUTOSTART_DIR"
for APPLET in xfce4-power-manager xfce4-screensaver light-locker xscreensaver \
    polkit-gnome-authentication-agent-1 system-config-printer gnome-shell-overrides-migration; do
    if [ ! -e "$AUTOSTART_DIR/$APPLET.desktop" ]; then
        printf '[Desktop Entry]\nType=Application\nName=Linex unused system service\nHidden=true\n' \
            > "$AUTOSTART_DIR/$APPLET.desktop"
    fi
done

# Default to lower-cost rendering, but preserve an explicit compositor choice.
# Compositing can help users test tearing behavior in their desktop session.
if command -v xfconf-query >/dev/null 2>&1; then
    if ! xfconf-query -c xfwm4 -p /general/use_compositing >/dev/null 2>&1; then
        xfconf-query -c xfwm4 -p /general/use_compositing -n -t bool -s false 2>/dev/null || true
    fi
fi
echo "[Linex:XFCE] Applied lightweight session defaults (system applets disabled; existing compositor preference preserved)."

# Execute xfce4-session
exec xfce4-session

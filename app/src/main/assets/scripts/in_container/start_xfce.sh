#!/bin/sh
# ==============================================================================
# Linex XFCE4 Session Launcher
# Starts XFCE4 with compositing and power-saving disabled (unneeded on mobile)
# for maximum frame-rate and lowest memory usage.
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

# Execute xfce4-session
exec xfce4-session

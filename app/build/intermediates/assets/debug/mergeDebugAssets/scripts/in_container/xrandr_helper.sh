#!/bin/sh
# ==============================================================================
# LinuxDroid XRandR Dynamic Geometry Resizer
# Dynamically reconfigures X11 display resolution and DPI scaling on-the-fly
# when Android orientation changes or Samsung DeX monitor connects/disconnects.
# ==============================================================================

set -e

WIDTH="$1"
HEIGHT="$2"
DPI="$3"

if [ -z "$WIDTH" ] || [ -z "$HEIGHT" ]; then
    echo "Usage: $0 <width> <height> [dpi]"
    exit 1
fi

echo "[LinuxDroid:XRandR] Requesting resolution update to ${WIDTH}x${HEIGHT} (DPI: ${DPI:-default})..."

if ! command -v xrandr >/dev/null 2>&1; then
    echo "[LinuxDroid:XRandR] ERROR: xrandr utility not installed inside container."
    exit 2
fi

# Detect primary output
OUTPUT=$(xrandr | grep " connected" | head -n 1 | cut -d ' ' -f 1)
if [ -z "$OUTPUT" ]; then
    OUTPUT="default"
fi

echo "[LinuxDroid:XRandR] Detected target display output: $OUTPUT"

# Attempt standard switch first
if ! xrandr --output "$OUTPUT" --mode "${WIDTH}x${HEIGHT}" 2>/dev/null; then
    # Fallback: calculate modeline using cvt/gtf if supported
    MODE_NAME="${WIDTH}x${HEIGHT}_60.00"
    if command -v cvt >/dev/null 2>&1; then
        MODELINE=$(cvt "$WIDTH" "$HEIGHT" 60 | grep "Modeline" | sed 's/Modeline //')
        xrandr --newmode $MODELINE 2>/dev/null || true
        xrandr --addmode "$OUTPUT" "$MODE_NAME" 2>/dev/null || true
        xrandr --output "$OUTPUT" --mode "$MODE_NAME" 2>/dev/null || true
    else
        # Direct dimension set
        xrandr -s "${WIDTH}x${HEIGHT}" 2>/dev/null || true
    fi
fi

# Apply DPI update if specified
if [ -n "$DPI" ] && [ "$DPI" -gt 0 ] 2>/dev/null; then
    if command -v xrdb >/dev/null 2>&1; then
        echo "Xft.dpi: $DPI" | xrdb -merge 2>/dev/null || true
        echo "[LinuxDroid:XRandR] Applied Xft.dpi: $DPI via xrdb"
    fi
    
    # Update XFCE settings if available
    if command -v xfconf-query >/dev/null 2>&1; then
        xfconf-query -c xsettings -p /Xft/DPI -s "$DPI" 2>/dev/null || true
    fi
fi

echo "[LinuxDroid:XRandR] Display geometry update complete."

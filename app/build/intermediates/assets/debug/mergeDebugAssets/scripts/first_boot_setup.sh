#!/bin/sh
# ==============================================================================
# Linex First Boot Setup Script
# Runs once upon first instance creation to strip conflicting systemd units,
# disable incompatible PAM restrictions, configure fallback DNS/hosts,
# and write the `.linex_initialized` marker.
# ==============================================================================

set -e

ROOTFS="${1:-}"

if [ -z "$ROOTFS" ] || [ "$ROOTFS" = "/" ]; then
    # Running inside the container rootfs directly
    ROOTFS=""
elif [ ! -d "$ROOTFS" ]; then
    echo "[Linex:FirstBoot] ERROR: Valid rootfs directory path required: $ROOTFS"
    exit 1
fi

echo "[Linex:FirstBoot] Applying first-boot customizations to $ROOTFS..."

# 1. Neutralize systemd init units that hang or crash in PRoot rootless containers
SYSTEMD_SERVICES="systemd-udevd.service systemd-journald.service systemd-timesyncd.service systemd-resolved.service"
for svc in $SYSTEMD_SERVICES; do
    if [ -d "$ROOTFS/etc/systemd/system" ]; then
        ln -sf /dev/null "$ROOTFS/etc/systemd/system/$svc" 2>/dev/null || true
    fi
done

# 2. Configure Apt & Dpkg flags for unprivileged PRoot operations
mkdir -p "$ROOTFS/etc/dpkg/dpkg.cfg.d"
cat << 'EOF' > "$ROOTFS/etc/dpkg/dpkg.cfg.d/01_linex_nodoc"
# Minimize rootfs footprint by skipping documentation
path-exclude /usr/share/doc/*
path-include /usr/share/doc/*/copyright
path-exclude /usr/share/man/*
path-exclude /usr/share/groff/*
path-exclude /usr/share/info/*
EOF

mkdir -p "$ROOTFS/etc/apt/apt.conf.d"
cat << 'EOF' > "$ROOTFS/etc/apt/apt.conf.d/99linex"
# In rootless PRoot, drop privileges sandbox is unnecessary and triggers setuid failures
APT::Sandbox::User "root";
Dir::Cache::pkgcache "";
Dir::Cache::srcpkgcache "";
Acquire::Languages "none";
EOF

# 3. Configure Fallback Network Resolution
mkdir -p "$ROOTFS/etc"

CONFIG_SOURCE="/linex/config"
if [ ! -d "$CONFIG_SOURCE" ]; then
    SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd || dirname "$0")"
    if [ -n "$ROOTFS" ] && [ -d "$ROOTFS/linex/config" ]; then
        CONFIG_SOURCE="$ROOTFS/linex/config"
    elif [ -d "$SCRIPT_DIR/../config" ]; then
        CONFIG_SOURCE="$SCRIPT_DIR/../config"
    elif [ -n "$BOOTSTRAP_DIR" ] && [ -d "$BOOTSTRAP_DIR/../config" ]; then
        CONFIG_SOURCE="$BOOTSTRAP_DIR/../config"
    elif [ -n "$CONFIG_DIR" ] && [ -d "$CONFIG_DIR" ]; then
        CONFIG_SOURCE="$CONFIG_DIR"
    fi
fi

if [ -f "$CONFIG_SOURCE/resolv.conf" ]; then
    echo "[Linex:FirstBoot] Copying resolv.conf from $CONFIG_SOURCE/resolv.conf..."
    cp -f "$CONFIG_SOURCE/resolv.conf" "$ROOTFS/etc/resolv.conf"
else
    echo "[Linex:FirstBoot] Falling back to builtin resolv.conf..."
    cat << 'EOF' > "$ROOTFS/etc/resolv.conf"
nameserver 1.1.1.1
nameserver 8.8.8.8
nameserver 8.8.4.4
options timeout:2 attempts:3
EOF
fi

if [ -f "$CONFIG_SOURCE/hosts" ]; then
    echo "[Linex:FirstBoot] Copying hosts from $CONFIG_SOURCE/hosts..."
    cp -f "$CONFIG_SOURCE/hosts" "$ROOTFS/etc/hosts"
else
    echo "[Linex:FirstBoot] Falling back to builtin hosts..."
    cat << 'EOF' > "$ROOTFS/etc/hosts"
127.0.0.1   localhost localhost.localdomain linex
::1         localhost ip6-localhost ip6-loopback
EOF
fi

# 4. Configure Audio (ALSA redirect to PulseAudio)
if [ -f "$CONFIG_SOURCE/asound.conf" ]; then
    echo "[Linex:FirstBoot] Copying asound.conf from $CONFIG_SOURCE/asound.conf..."
    cp -f "$CONFIG_SOURCE/asound.conf" "$ROOTFS/etc/asound.conf"
else
    cat << 'EOF' > "$ROOTFS/etc/asound.conf"
pcm.!default {
    type pulse
    fallback "sysdefault"
}
ctl.!default {
    type pulse
    fallback "sysdefault"
}
EOF
fi

# 5. Configure Shell Environment & Welcome Banner
mkdir -p "$ROOTFS/etc/profile.d"
cat << 'EOF' > "$ROOTFS/etc/profile.d/linex.sh"
# Linex Container Environment Profile
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export TERM="xterm-256color"
export PULSE_SERVER="tcp:127.0.0.1:4713"

if [ -n "$BASH_VERSION" ]; then
    PS1='\[\033[01;32m\]\u@linex\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
fi
EOF

# 6. Ensure Base System Directories Exist
mkdir -p "$ROOTFS/tmp" "$ROOTFS/dev/shm" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/root" "$ROOTFS/sdcard"
chmod 1777 "$ROOTFS/tmp" 2>/dev/null || true

# 7. Write Initialization Sentinel Marker
cat << EOF > "$ROOTFS/.linex_initialized"
VERSION=1.0.0
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || echo "INIT")
STATUS=READY
EOF

echo "[Linex:FirstBoot] First boot configuration successfully completed."

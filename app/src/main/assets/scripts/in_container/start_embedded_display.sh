#!/bin/sh
# Sourced by container_init.sh so its EXIT trap owns the display process.

find_display_server() {
    command -v Xtigervnc 2>/dev/null || command -v Xvnc 2>/dev/null
}

find_password_tool() {
    command -v tigervncpasswd 2>/dev/null || command -v vncpasswd 2>/dev/null
}

start_embedded_display() {
    case "${LINEX_VNC_PORT:-}" in
        ''|*[!0-9]*) echo "[Linex:Display] ERROR: Invalid local display port."; exit 64 ;;
    esac
    if [ "$LINEX_VNC_PORT" -lt 1024 ] || [ "$LINEX_VNC_PORT" -gt 65535 ]; then
        echo "[Linex:Display] ERROR: Local display port is out of range."
        exit 64
    fi
    if [ ! -f /tmp/linex-vnc.secret ] || [ ! -s /tmp/linex-vnc.secret ]; then
        echo "[Linex:Display] ERROR: Local display credentials are missing. Stop and start this instance again."
        exit 66
    fi
    chmod 600 /tmp/linex-vnc.secret

    DISPLAY_SERVER="$(find_display_server)" || DISPLAY_SERVER=""
    PASSWORD_TOOL="$(find_password_tool)" || PASSWORD_TOOL=""
    if [ -z "$DISPLAY_SERVER" ] || [ -z "$PASSWORD_TOOL" ]; then
        if ! command -v apt-get >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1; then
            echo "[Linex:Display] ERROR: Install tigervnc-standalone-server and tigervnc-tools in this Linux distribution to enable the embedded display."
            exit 69
        fi
        echo "[Linex:Display] Installing the embedded display server (first launch only). Internet access is required; each package step has a 5 minute timeout."
        export DEBIAN_FRONTEND=noninteractive
        if ! timeout 300 apt-get -o Acquire::Retries=1 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 -o APT::Update::Error-Mode=any update; then
            echo "[Linex:Display] ERROR: Package index update failed or timed out. Check your connection and retry starting this instance."
            exit 69
        fi
        if ! timeout 300 apt-get -o Acquire::Retries=1 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install -y --no-install-recommends tigervnc-standalone-server tigervnc-tools; then
            echo "[Linex:Display] ERROR: Display server installation failed or timed out. See package output above and retry starting this instance."
            exit 69
        fi
        DISPLAY_SERVER="$(find_display_server)" || DISPLAY_SERVER=""
        PASSWORD_TOOL="$(find_password_tool)" || PASSWORD_TOOL=""
        if [ -z "$DISPLAY_SERVER" ] || [ -z "$PASSWORD_TOOL" ]; then
            echo "[Linex:Display] ERROR: Package installation completed without an Xvnc server or password tool."
            exit 69
        fi
    fi

    # Never expose the session credential in command arguments or diagnostics.
    umask 077
    if ! "$PASSWORD_TOOL" -f < /tmp/linex-vnc.secret > /tmp/linex-vnc.passwd; then
        echo "[Linex:Display] ERROR: Could not prepare local display authentication."
        exit 70
    fi
    chmod 600 /tmp/linex-vnc.passwd
    rm -f /tmp/linex-vnc.secret

    DISPLAY_WIDTH="${LINUXDROID_WIDTH:-1920}"
    DISPLAY_HEIGHT="${LINUXDROID_HEIGHT:-1080}"
    case "$DISPLAY_WIDTH:$DISPLAY_HEIGHT" in
        *[!0-9:]*|:*|*:) echo "[Linex:Display] ERROR: Invalid display geometry."; exit 64 ;;
    esac
    export DISPLAY=:0
    echo "[Linex:Display] Starting authenticated local display at ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}..."
    "$DISPLAY_SERVER" :0 -rfbport "$LINEX_VNC_PORT" -localhost yes \
        -SecurityTypes VncAuth -PasswordFile /tmp/linex-vnc.passwd \
        -geometry "${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}" -depth 24 \
        -AlwaysShared -ac -nolisten tcp &
    VNC_PID=$!

    DISPLAY_WAIT=0
    while [ "$DISPLAY_WAIT" -lt 100 ]; do
        if ! kill -0 "$VNC_PID" 2>/dev/null; then
            echo "[Linex:Display] ERROR: Embedded display server exited before becoming ready. See server output above."
            exit 70
        fi
        if [ -S /tmp/.X11-unix/X0 ]; then
            echo "[Linex:Display] Local display socket is ready."
            echo "__LINEX_DISPLAY_READY__"
            return 0
        fi
        sleep 0.1
        DISPLAY_WAIT=$((DISPLAY_WAIT + 1))
    done
    echo "[Linex:Display] ERROR: Embedded display did not create its X11 socket within 10 seconds."
    exit 70
}

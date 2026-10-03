#!/bin/sh
# Keep D-Bus in the guest's owned process group; daemon --fork calls setsid.
start_session_bus() {
    LINEX_BUS_DIR="${1:-/tmp}"
    DBUS_PID=""
    unset DBUS_SESSION_BUS_ADDRESS
    rm -f "$LINEX_BUS_DIR/dbus-session-socket" "$LINEX_BUS_DIR/dbus.address"
    dbus-daemon --session --nofork --address="unix:path=$LINEX_BUS_DIR/dbus-session-socket" \
        --print-address > "$LINEX_BUS_DIR/dbus.address" 2>/dev/null &
    DBUS_PID=$!
    LINEX_BUS_WAIT=0
    while kill -0 "$DBUS_PID" 2>/dev/null && [ ! -s "$LINEX_BUS_DIR/dbus.address" ] && [ "$LINEX_BUS_WAIT" -lt 40 ]; do
        sleep 0.05
        LINEX_BUS_WAIT=$((LINEX_BUS_WAIT + 1))
    done
    if kill -0 "$DBUS_PID" 2>/dev/null && [ -s "$LINEX_BUS_DIR/dbus.address" ]; then
        export DBUS_SESSION_BUS_ADDRESS="unix:path=$LINEX_BUS_DIR/dbus-session-socket"
        echo "[Linex:ContainerInit] D-Bus started as owned foreground child with PID $DBUS_PID"
    else
        stop_session_bus
        echo "[Linex:ContainerInit] WARNING: Session D-Bus failed to become ready; no dead bus address exported."
    fi
}

stop_session_bus() {
    if [ -n "$DBUS_PID" ]; then
        kill -15 "$DBUS_PID" 2>/dev/null || true
        wait "$DBUS_PID" 2>/dev/null || true
        DBUS_PID=""
    fi
}

#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/linex-bus-test.XXXXXX")
bus_pid=""
cleanup_test() {
    [ -z "$bus_pid" ] || kill "$bus_pid" 2>/dev/null || true
    case "$work" in "${TMPDIR:-/tmp}"/linex-bus-test.*) rm -rf "$work" ;; esac
}
trap cleanup_test EXIT INT TERM
mkdir -p "$work/bin"
cat > "$work/bin/dbus-daemon" <<'EOF'
#!/bin/sh
case " $* " in *' --nofork '*) ;; *) exit 91 ;; esac
printf 'unix:path=test-session-socket\n'
trap 'exit 0' TERM INT
while :; do sleep 0.1; done
EOF
chmod +x "$work/bin/dbus-daemon"
PATH="$work/bin:$PATH"
export PATH
. "$root/app/src/main/assets/scripts/in_container/session_bus.sh"
start_session_bus "$work"
bus_pid="$DBUS_PID"
test -n "$bus_pid"
kill -0 "$bus_pid"
test "$DBUS_SESSION_BUS_ADDRESS" = "unix:path=$work/dbus-session-socket"
stop_session_bus
if kill -0 "$bus_pid" 2>/dev/null; then echo 'Owned bus survived shutdown' >&2; exit 1; fi
bus_pid=""
cat > "$work/bin/dbus-daemon" <<'EOF'
#!/bin/sh
exit 92
EOF
start_session_bus "$work"
test -z "$DBUS_PID"
test -z "${DBUS_SESSION_BUS_ADDRESS:-}"
echo 'Session bus ownership and failed-start regressions passed'

#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
LINEX_TEST_TMP_BASE=$(CDPATH= cd -- "${TMPDIR:-/tmp}" && pwd -P)
work=$(mktemp -d "$LINEX_TEST_TMP_BASE/linex-xfce-startup.XXXXXX")
cleanup_xfce_test() {
    [ -d "$work" ] || return 0
    LINEX_TEST_TMP_TARGET=$(CDPATH= cd -- "$work" && pwd -P) || return 0
    case "$LINEX_TEST_TMP_TARGET" in
        "$LINEX_TEST_TMP_BASE"/linex-xfce-startup.*) rm -rf -- "$LINEX_TEST_TMP_TARGET" ;;
        *) echo 'Refusing cleanup outside verified temporary directory' >&2 ;;
    esac
}
trap cleanup_xfce_test EXIT
mkdir -p "$work/bin" "$work/home/.config/autostart"
printf '#!/bin/sh\nexit 0\n' > "$work/bin/xfce4-session"
cat > "$work/bin/xfconf-query" << 'EOF'
#!/bin/sh
printf '%s\n' "$*" >> "$TRACE"
case "$*" in
    *' -s false'*) printf 'false\n' > "$COMPOSITOR_STATE" ;;
    *) [ -f "$COMPOSITOR_STATE" ] || exit 1; cat "$COMPOSITOR_STATE" ;;
esac
EOF
chmod +x "$work/bin/"*
printf 'user-custom-override\n' > "$work/home/.config/autostart/xfce4-power-manager.desktop"
export XDG_CONFIG_HOME="$work/home/.config" PATH="$work/bin:$PATH" TRACE="$work/trace" COMPOSITOR_STATE="$work/compositor"
sh "$root/app/src/main/assets/scripts/in_container/start_xfce.sh"
grep -q 'Hidden=true' "$XDG_CONFIG_HOME/autostart/xfce4-screensaver.desktop"
if ! grep -q 'Hidden=true' "$XDG_CONFIG_HOME/autostart/print-applet.desktop"; then
    echo 'Printer autostart uses print-applet.desktop, not system-config-printer.desktop' >&2
    exit 1
fi
for applet in geoclue-demo-agent update-notifier blueman nm-applet xiccd; do
    grep -q 'Hidden=true' "$XDG_CONFIG_HOME/autostart/$applet.desktop"
done
grep -q 'user-custom-override' "$XDG_CONFIG_HOME/autostart/xfce4-power-manager.desktop"
grep -q '/general/use_compositing -n -t bool -s false' "$TRACE"
test "$(cat "$COMPOSITOR_STATE")" = false
printf 'user-enabled-bluetooth\n' > "$XDG_CONFIG_HOME/autostart/blueman.desktop"
printf '[Desktop Entry]\nType=Application\nHidden=false\nExec=system-config-printer-applet\n' > "$XDG_CONFIG_HOME/autostart/print-applet.desktop"
cp "$XDG_CONFIG_HOME/autostart/print-applet.desktop" "$work/printer-before"
: > "$TRACE"
sh "$root/app/src/main/assets/scripts/in_container/start_xfce.sh"
test "$(cat "$COMPOSITOR_STATE")" = false
grep -q 'user-enabled-bluetooth' "$XDG_CONFIG_HOME/autostart/blueman.desktop"
cmp "$work/printer-before" "$XDG_CONFIG_HOME/autostart/print-applet.desktop"
if grep -q ' -s ' "$TRACE"; then
    echo 'Existing disabled compositor choice was overwritten' >&2
    exit 1
fi
printf 'true\n' > "$COMPOSITOR_STATE"
: > "$TRACE"
sh "$root/app/src/main/assets/scripts/in_container/start_xfce.sh"
test "$(cat "$COMPOSITOR_STATE")" = true
if grep -q ' -s ' "$TRACE"; then
    echo 'Existing compositor choice was overwritten' >&2
    exit 1
fi
# A user override can be a dangling symlink. Never follow it and create its target.
mkdir -p "$work/symlink-config/autostart"
ln -s "$work/missing-user-printer.desktop" "$work/symlink-config/autostart/print-applet.desktop"
XDG_CONFIG_HOME="$work/symlink-config" sh "$root/app/src/main/assets/scripts/in_container/start_xfce.sh"
test -L "$work/symlink-config/autostart/print-applet.desktop"
if [ -e "$work/missing-user-printer.desktop" ]; then
    echo 'A dangling user autostart override was followed and overwritten' >&2
    exit 1
fi
echo 'XFCE startup regression checks passed'

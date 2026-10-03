#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
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
export HOME="$work/home" PATH="$work/bin:$PATH" TRACE="$work/trace" COMPOSITOR_STATE="$work/compositor"
sh "$root/app/src/main/assets/scripts/in_container/start_xfce.sh"
grep -q 'Hidden=true' "$HOME/.config/autostart/xfce4-screensaver.desktop"
for applet in geoclue-demo-agent update-notifier blueman nm-applet xiccd; do
    grep -q 'Hidden=true' "$HOME/.config/autostart/$applet.desktop"
done
grep -q 'user-custom-override' "$HOME/.config/autostart/xfce4-power-manager.desktop"
grep -q '/general/use_compositing -n -t bool -s false' "$TRACE"
test "$(cat "$COMPOSITOR_STATE")" = false
printf 'user-enabled-bluetooth\n' > "$HOME/.config/autostart/blueman.desktop"
: > "$TRACE"
sh "$root/app/src/main/assets/scripts/in_container/start_xfce.sh"
test "$(cat "$COMPOSITOR_STATE")" = false
grep -q 'user-enabled-bluetooth' "$HOME/.config/autostart/blueman.desktop"
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
echo 'XFCE startup regression checks passed'

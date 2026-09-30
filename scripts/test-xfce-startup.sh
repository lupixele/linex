#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/home/.config/autostart"
printf '#!/bin/sh\nexit 0\n' > "$work/bin/xfce4-session"
printf '#!/bin/sh\nprintf "%%s\\n" "$*" >> "$TRACE"\n' > "$work/bin/xfconf-query"
chmod +x "$work/bin/"*
printf 'user-custom-override\n' > "$work/home/.config/autostart/xfce4-power-manager.desktop"
export HOME="$work/home" PATH="$work/bin:$PATH" TRACE="$work/trace"
sh "$root/app/src/main/assets/scripts/in_container/start_xfce.sh"
grep -q 'Hidden=true' "$HOME/.config/autostart/xfce4-screensaver.desktop"
grep -q 'user-custom-override' "$HOME/.config/autostart/xfce4-power-manager.desktop"
grep -q '/general/use_compositing.*false' "$TRACE"
sh "$root/app/src/main/assets/scripts/in_container/start_xfce.sh"
echo 'XFCE startup regression checks passed'

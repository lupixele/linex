#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
LINEX_TEST_TMP_BASE=$(CDPATH= cd -- "${TMPDIR:-/tmp}" && pwd -P)
work=$(mktemp -d "$LINEX_TEST_TMP_BASE/linex-process-budget.XXXXXX")
cleanup_process_budget_test() {
    [ -d "$work" ] || return 0
    LINEX_TEST_TMP_TARGET=$(CDPATH= cd -- "$work" && pwd -P) || return 0
    case "$LINEX_TEST_TMP_TARGET" in
        "$LINEX_TEST_TMP_BASE"/linex-process-budget.*) rm -rf -- "$LINEX_TEST_TMP_TARGET" ;;
        *) echo 'Refusing test cleanup outside verified temporary directory' >&2 ;;
    esac
}
trap cleanup_process_budget_test EXIT
profile="$root/app/src/main/assets/scripts/in_container/process_budget.sh"

# A missing or unsupported browser must not prevent desktop startup.
mkdir -p "$work/empty"
. "$profile"
apply_browser_process_defaults "$work/empty"
test ! -e "$work/empty/usr/lib/firefox"

mkdir -p "$work/guest/usr/lib/firefox/browser" "$work/home/.mozilla/firefox/test.default"
printf '#!/bin/sh\nexit 0\n' > "$work/guest/usr/lib/firefox/firefox"
chmod +x "$work/guest/usr/lib/firefox/firefox"
printf '[App]\nName=Firefox\n' > "$work/guest/usr/lib/firefox/application.ini"
printf 'user_pref("dom.ipc.processCount", 7);\n' > "$work/home/.mozilla/firefox/test.default/prefs.js"
printf 'user_pref("dom.ipc.processPrelaunch.enabled", true);\n' > "$work/home/.mozilla/firefox/test.default/user.js"
cp "$work/home/.mozilla/firefox/test.default/prefs.js" "$work/prefs-before"
cp "$work/home/.mozilla/firefox/test.default/user.js" "$work/user-before"
LINEX_TEST_PROFILE_HOME="$work/home"
apply_browser_process_defaults "$work/guest"
defaults="$work/guest/usr/lib/firefox/browser/defaults/preferences/linex-process-budget.js"
test -f "$defaults"
grep -q 'pref("dom.ipc.processCount", 2);' "$defaults"
grep -q 'pref("dom.ipc.processCount.webIsolated", 1);' "$defaults"
grep -q 'pref("dom.ipc.processPrelaunch.enabled", false);' "$defaults"
cmp "$work/prefs-before" "$LINEX_TEST_PROFILE_HOME/.mozilla/firefox/test.default/prefs.js"
cmp "$work/user-before" "$LINEX_TEST_PROFILE_HOME/.mozilla/firefox/test.default/user.js"
# Do not alter site isolation, multiprocess architecture, GPU support or sandbox.
if grep -E 'fission\.|sandbox\.|layers\.|gfx\.|remote.autostart|user_pref|lockPref' "$defaults"; then
    echo 'Process defaults must not weaken browser isolation or override users' >&2
    exit 1
fi

# Preserve an explicitly edited defaults file and distro-owned preferences.
printf '// custom budget\npref("dom.ipc.processCount", 5);\n' > "$defaults"
printf '// distro custom defaults\n' > "$(dirname "$defaults")/vendor.js"
apply_browser_process_defaults "$work/guest"
grep -q 'pref("dom.ipc.processCount", 5);' "$defaults"
grep -q 'distro custom defaults' "$(dirname "$defaults")/vendor.js"

# ESR without a separate app/browser directory uses runtime app defaults.
mkdir -p "$work/esr/usr/lib/firefox-esr"
printf '#!/bin/sh\nexit 0\n' > "$work/esr/usr/lib/firefox-esr/firefox-esr"
chmod +x "$work/esr/usr/lib/firefox-esr/firefox-esr"
apply_browser_process_defaults "$work/esr"
grep -q 'processPrelaunch.enabled", false' "$work/esr/usr/lib/firefox-esr/defaults/preferences/linex-process-budget.js"

# Broken symlink overrides are intentional entries; never replace their target.
mkdir -p "$work/symlink/usr/lib/firefox/browser/defaults/preferences"
cp "$work/guest/usr/lib/firefox/firefox" "$work/symlink/usr/lib/firefox/firefox"
ln -s "$work/missing-user-file" "$work/symlink/usr/lib/firefox/browser/defaults/preferences/linex-process-budget.js"
apply_browser_process_defaults "$work/symlink"
test ! -e "$work/missing-user-file"
echo 'Browser process-budget checks passed (unsupported browser, defaults, user choices, repeat startup, ESR and symlink override).'

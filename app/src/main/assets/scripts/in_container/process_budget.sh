#!/bin/sh
# Conservative guest defaults for Android's monitored native child processes.
# This reduces process demand; it cannot override Android's global limits.

apply_browser_process_defaults() {
    # The optional root prefix permits filesystem regression tests without
    # touching the host. Normal startup supplies no prefix: paths are guest paths.
    LINEX_PROFILE_ROOT="${1:-}"
    LINEX_BROWSER_COUNT=0
    LINEX_BROWSER_FOUND=0
    for LINEX_BROWSER_DIR in /usr/lib/firefox /usr/lib/firefox-esr \
        /usr/lib64/firefox /usr/lib64/firefox-esr /opt/firefox; do
        LINEX_BROWSER_DIR="$LINEX_PROFILE_ROOT$LINEX_BROWSER_DIR"
        if [ ! -x "$LINEX_BROWSER_DIR/firefox" ] && [ ! -x "$LINEX_BROWSER_DIR/firefox-esr" ]; then
            continue
        fi
        LINEX_BROWSER_FOUND=$((LINEX_BROWSER_FOUND + 1))

        # Firefox loads app preferences after GRE preferences. A separate
        # browser directory is the app location in standard desktop packages.
        if [ -d "$LINEX_BROWSER_DIR/browser" ]; then
            LINEX_DEFAULTS_DIR="$LINEX_BROWSER_DIR/browser/defaults/preferences"
        else
            LINEX_DEFAULTS_DIR="$LINEX_BROWSER_DIR/defaults/preferences"
        fi
        LINEX_DEFAULTS_FILE="$LINEX_DEFAULTS_DIR/linex-process-budget.js"
        if [ -e "$LINEX_DEFAULTS_FILE" ] || [ -L "$LINEX_DEFAULTS_FILE" ]; then
            # Existing files, including custom modifications, belong to users.
            continue
        fi
        if ! mkdir -p "$LINEX_DEFAULTS_DIR"; then
            echo "[Linex:ProcessBudget] Browser defaults directory is unavailable; leaving browser unchanged."
            continue
        fi
        LINEX_DEFAULTS_TMP=$(mktemp "$LINEX_DEFAULTS_DIR/.linex-budget.XXXXXX") || continue
        if cat > "$LINEX_DEFAULTS_TMP" << 'EOF'
// Linex process-budget defaults v1. User about:config/user.js choices take precedence.
// Shared web content uses at most two processes in its pool.
pref("dom.ipc.processCount", 2);
// Isolated web content: one process PER SITE, not one for the entire browser.
pref("dom.ipc.processCount.webIsolated", 1);
// Avoid spare content processes before a page actually needs them.
pref("dom.ipc.processPrelaunch.enabled", false);
EOF
        then
            chmod 0644 "$LINEX_DEFAULTS_TMP" 2>/dev/null || true
            # Atomic creation without replacing an entry created concurrently.
            if ln "$LINEX_DEFAULTS_TMP" "$LINEX_DEFAULTS_FILE" 2>/dev/null; then
                LINEX_BROWSER_COUNT=$((LINEX_BROWSER_COUNT + 1))
            fi
        fi
        rm -f "$LINEX_DEFAULTS_TMP"
    done
    if [ "$LINEX_BROWSER_COUNT" -gt 0 ]; then
        echo "[Linex:ProcessBudget] Installed Firefox defaults: shared web pool=2, isolated web pool=1 per site, spare prelaunch disabled. User choices, site isolation and sandbox remain intact."
    elif [ "$LINEX_BROWSER_FOUND" -gt 0 ]; then
        echo "[Linex:ProcessBudget] Existing Firefox defaults retained; custom preferences take precedence. No Android system policy was changed."
    else
        echo "[Linex:ProcessBudget] No supported Firefox runtime found; custom browsers remain unchanged."
    fi
}

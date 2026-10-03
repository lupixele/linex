#!/bin/sh
# Run in rooted Termux. Installs the existing release without changing its bytes.
set -eu

EXPECTED='0a6b685a30b5fb7ebf15c01aed1796f7ec905e49f22ad86575f502d641d643ac'
URL='https://github.com/lupixele/linex/releases/download/v0.5.0-dev/linex-v0.5.0-dev-verified.apk'

verify_apk() {
    [ -f "$1" ] || { echo 'APK file is missing.' >&2; return 1; }
    digest=$(sha256sum -- "$1") || return 1
    digest=${digest%% *}
    echo "APK SHA256: $digest"
    if [ "$digest" != "$EXPECTED" ]; then
        echo 'APK content differs from the signed release. Installation stopped.' >&2
        return 1
    fi
}

if [ "${1:-}" = '--check-file' ] && [ "$#" -eq 2 ]; then
    verify_apk "$2"
    exit
fi
[ "$#" -eq 0 ] || { echo 'Usage: sh install-verified-v0.5.0.sh [--check-file APK]' >&2; exit 1; }
for tool in curl sha256sum su; do
    command -v "$tool" >/dev/null || { echo "Missing command: $tool" >&2; exit 1; }
done

download_dir=$(mktemp -d "${TMPDIR:-/data/data/com.termux/files/usr/tmp}/linex-download.XXXXXX")
trap 'rm -f "$download_dir/base.apk"; rmdir "$download_dir"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
echo 'Downloading a fresh APK directly from GitHub...'
curl --fail --location --proto '=https' --tlsv1.2 --connect-timeout 15 \
    --max-time 300 --retry 2 --retry-delay 2 --output "$download_dir/base.apk" "$URL"
verify_apk "$download_dir/base.apk"
echo 'Download verified. Checking the root-staged copy before updating Linex...'

# Pass the verified binary through stdin; no user-controlled path enters root code.
su -c '
set -eu
umask 077
stage_dir=$(mktemp -d /data/local/tmp/linex-install.XXXXXX)
trap '\''rm -f "$stage_dir/base.apk"; rmdir "$stage_dir"'\'' EXIT
trap '\''exit 130'\'' INT
trap '\''exit 143'\'' TERM
cat > "$stage_dir/base.apk"
digest=$(sha256sum "$stage_dir/base.apk")
digest=${digest%% *}
echo "Staged APK SHA256: $digest"
if [ "$digest" != "0a6b685a30b5fb7ebf15c01aed1796f7ec905e49f22ad86575f502d641d643ac" ]; then
    echo "Staged APK changed. Installation stopped." >&2
    exit 1
fi
chmod 755 "$stage_dir"
chmod 644 "$stage_dir/base.apk"
pm install -r "$stage_dir/base.apk"
' < "$download_dir/base.apk"

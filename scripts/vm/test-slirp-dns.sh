#!/usr/bin/env bash
# Execute the private adapter against the actual pinned libslirp with ASan/UBSan.
set -euo pipefail
VM_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
VM_WORK=$(mktemp -d "${TMPDIR:-/tmp}/linex-slirp-proof-XXXXXXXX")
python3 - "$VM_ROOT" "$VM_WORK" <<'PY'
import hashlib, json, pathlib, tarfile, urllib.request, sys
root, work = map(pathlib.Path, sys.argv[1:])
item = json.loads((root/'scripts/vm/native-sources.json').read_text())['sources']['libslirp']
archive = work/'source.tar.gz'
with urllib.request.urlopen(item['url'], timeout=120) as response:
    data = response.read(16 * 1024 * 1024 + 1)
if len(data) > 16 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != item['sha256']:
    raise SystemExit('Pinned SLIRP source hash/size mismatch')
archive.write_bytes(data)
source = work/'source'
source.mkdir()
with tarfile.open(archive) as package:
    selected=[]
    for member in package.getmembers():
        member.name = '/'.join(member.name.split('/')[1:])
        if member.islnk():
            member.linkname = '/'.join(member.linkname.split('/')[1:])
        if member.name:
            selected.append(member)
    package.extractall(source, members=selected, filter='data')
PY
python3 "$VM_ROOT/scripts/vm/patch-slirp.py" --source "$VM_WORK/source" --jni "$VM_ROOT/vm-engine/src/main/jni"
meson setup "$VM_WORK/build" "$VM_WORK/source" -Ddefault_library=static -Db_sanitize=address,undefined -Db_lundef=false
ninja -C "$VM_WORK/build" libslirp.a
gcc -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer -g \
  -I"$VM_WORK/source/src" -I"$VM_WORK/build" $(pkg-config --cflags glib-2.0) \
  "$VM_ROOT/scripts/vm/test_native_slirp_dns.c" "$VM_WORK/build/libslirp.a" \
  $(pkg-config --libs glib-2.0) -o "$VM_WORK/linex-slirp-dns"
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 "$VM_WORK/linex-slirp-dns"
gcc -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer -g \
  -I"$VM_WORK/source/src" -I"$VM_WORK/build" $(pkg-config --cflags glib-2.0) \
  "$VM_ROOT/scripts/vm/test_native_slirp_loopback.c" "$VM_WORK/build/libslirp.a" \
  $(pkg-config --libs glib-2.0) -o "$VM_WORK/linex-slirp-loopback"
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 "$VM_WORK/linex-slirp-loopback"

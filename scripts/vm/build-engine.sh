#!/usr/bin/env bash
# Linux-only, source-built Android QEMU. A successful compile is not a boot proof.
set -euo pipefail
VM_ABI=''
VM_OUTPUT=''
while (($#)); do
  case "$1" in
    --abi) VM_ABI="${2:?Missing ABI}"; shift 2 ;;
    --output) VM_OUTPUT="${2:?Missing output directory}"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Requires a Linux x86_64 build host.' >&2; exit 2; }
case "$VM_ABI" in
  arm64-v8a) VM_TRIPLE=aarch64-linux-android; VM_CPU=aarch64 ;;
  x86_64) VM_TRIPLE=x86_64-linux-android; VM_CPU=x86_64 ;;
  *) echo 'Supported ABIs: arm64-v8a, x86_64' >&2; exit 2 ;;
esac
[[ -n "$VM_OUTPUT" ]] || { echo '--output is required' >&2; exit 2; }
VM_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
VM_NDK=${LINEX_VM_NDK:?Set LINEX_VM_NDK to a verified Android NDK r30 Linux directory}
grep -Eq '^Pkg.Revision *= *30\.' "$VM_NDK/source.properties" || { echo 'NDK r30 is required.' >&2; exit 2; }
VM_TOOLS="$VM_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
for VM_COMMAND in python3 meson ninja make cmake pkg-config patch curl tar gcc; do
  command -v "$VM_COMMAND" >/dev/null || { echo "Missing tool: $VM_COMMAND" >&2; exit 2; }
done
VM_OUTPUT=$(realpath -m "$VM_OUTPUT")
mkdir -p "$VM_OUTPUT/$VM_ABI"
VM_CACHE=${LINEX_VM_CACHE:-"$VM_ROOT/.vm-native-cache"}
mkdir -p "$VM_CACHE"
# A fresh isolated tree prevents reuse of partially patched/configured objects.
VM_WORK=$(mktemp -d "$VM_CACHE/build-$VM_ABI-XXXXXXXX")
VM_PREFIX="$VM_WORK/prefix"
mkdir -p "$VM_PREFIX/lib/pkgconfig" "$VM_PREFIX/include" "$VM_WORK/src"
exec > >(tee "$VM_OUTPUT/$VM_ABI/build.log") 2>&1
echo "Building QEMU11.0.3 Android $VM_ABI in $VM_WORK"
export CC="$VM_TOOLS/${VM_TRIPLE}26-clang"
export CXX="$VM_TOOLS/${VM_TRIPLE}26-clang++"
export AR="$VM_TOOLS/llvm-ar"
export RANLIB="$VM_TOOLS/llvm-ranlib"
export STRIP="$VM_TOOLS/llvm-strip"
export CFLAGS="-O2 -fPIC -I$VM_PREFIX/include"
export CXXFLAGS="$CFLAGS"
export CPPFLAGS="-I$VM_PREFIX/include"
export LDFLAGS="-L$VM_PREFIX/lib -Wl,-z,max-page-size=16384"
export PKG_CONFIG_LIBDIR="$VM_PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH=''
export SOURCE_DATE_EPOCH=1790985600
VM_JOBS=${LINEX_VM_JOBS:-2}

# Archives and every vendored portability input are hash checked before use.
python3 - "$VM_ROOT" "$VM_CACHE" "$VM_WORK/src" <<'PY'
import hashlib, json, pathlib, tarfile, urllib.request, sys
root, cache, destination = map(pathlib.Path, sys.argv[1:])
manifest = json.loads((root/'scripts/vm/native-sources.json').read_text())
for name, item in manifest['sources'].items():
    archive = cache / (name + '-' + item['sha256'] + '.archive')
    if not archive.exists():
        temporary = archive.with_suffix('.partial')
        print('Downloading verified source:', name, flush=True)
        with urllib.request.urlopen(item['url'], timeout=120) as response, temporary.open('wb') as output:
            import shutil
            shutil.copyfileobj(response, output)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != item['sha256']:
            raise SystemExit('Source hash mismatch: ' + name)
        temporary.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != item['sha256']:
        raise SystemExit('Cached source hash mismatch: ' + name)
    folder = destination/name
    folder.mkdir()
    with tarfile.open(archive) as package:
        members = package.getmembers()
        prefixes = {m.name.split('/')[0] for m in members}
        if len(prefixes) != 1:
            raise SystemExit('Archive must have one top-level source directory')
        selected = []
        for member in members:
            # QEMU's firmware source carries an absolute X11IncludeHack link.
            # It is irrelevant to a serial direct-kernel boot and must never be
            # followed/extracted onto the build host. Keep data_filter enabled.
            if member.issym() and pathlib.PurePosixPath(member.linkname).is_absolute():
                print('Omitting nonportable absolute source link:', name, member.name, flush=True)
                continue
            member.name = '/'.join(member.name.split('/')[1:])
            if not member.name:
                continue
            if member.islnk():
                member.linkname = '/'.join(member.linkname.split('/')[1:])
            selected.append(member)
        package.extractall(folder, members=selected, filter='data')
for relative, expected in manifest['patches'].items():
    if hashlib.sha256((root/relative).read_bytes()).hexdigest() != expected:
        raise SystemExit('Portability input hash mismatch: ' + relative)
PY
cat > "$VM_WORK/android.ini" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = ['pkg-config', '--static']
[host_machine]
system = 'android'
cpu_family = '$VM_CPU'
cpu = '$VM_CPU'
endian = 'little'
[properties]
needs_exe_wrapper = true
[built-in options]
c_args = ['-O2', '-fPIC', '-D__BIONIC__=1', '-I$VM_PREFIX/include']
cpp_args = ['-O2', '-fPIC', '-D__BIONIC__=1', '-I$VM_PREFIX/include']
c_link_args = ['-L$VM_PREFIX/lib', '-Wl,-z,max-page-size=16384']
cpp_link_args = ['-L$VM_PREFIX/lib', '-Wl,-z,max-page-size=16384']
EOF

(cd "$VM_WORK/src/zlib"; ./configure --static --prefix="$VM_PREFIX"; make -j"$VM_JOBS"; make install)
for VM_DEP in libiconv libffi pcre2; do
  mkdir "$VM_WORK/$VM_DEP-build"
  case "$VM_DEP" in
    libiconv) VM_OPTIONS=(--disable-nls) ;;
    libffi) VM_OPTIONS=(--disable-multi-os-directory ac_cv_func_memfd_create=no) ;;
    pcre2) VM_OPTIONS=(--disable-pcre2grep --disable-pcre2test --disable-jit) ;;
  esac
  (cd "$VM_WORK/$VM_DEP-build"; "$VM_WORK/src/$VM_DEP/configure" --host="$VM_TRIPLE" --prefix="$VM_PREFIX" --disable-shared --enable-static "${VM_OPTIONS[@]}"; make -j"$VM_JOBS"; make install)
done
# QEMU's Linux osdep header includes sys/shm.h, absent from the NDK. Only the
# compatibility declarations are needed: user-mode/ivshmem/SysV backends are
# disabled. Do not ship Termux's keyed shared-memory helper/runtime path.
mkdir -p "$VM_PREFIX/include/sys"
cp "$VM_WORK/src/android-shmem/shm.h" "$VM_PREFIX/include/sys/shm.h"
# GLib carries GVDB in its release archive; supply its hash-pinned gettext stub
# explicitly and disallow unpinned Meson fallback downloads.
cp -a "$VM_WORK/src/proxy-libintl" "$VM_WORK/src/glib/subprojects/proxy-libintl-0.5"
for VM_PATCH in "$VM_ROOT"/scripts/vm/patches/glib/*.patch; do
  patch -d "$VM_WORK/src/glib" -p1 --batch --forward < "$VM_PATCH"
done
meson setup "$VM_WORK/glib-build" "$VM_WORK/src/glib" --cross-file "$VM_WORK/android.ini" \
  --prefix "$VM_PREFIX" --libdir lib --wrap-mode=nodownload -Ddefault_library=static \
  -Dintrospection=disabled -Dlibmount=disabled -Dselinux=disabled -Dxattr=false \
  -Dtests=false -Dinstalled_tests=false -Ddocumentation=false -Dman-pages=disabled \
  -Dnls=disabled -Dsysprof=disabled -Dlibelf=disabled
ninja -C "$VM_WORK/glib-build" -j"$VM_JOBS" install
make -C "$VM_WORK/src/dtc" -j"$VM_JOBS" CC="$CC" AR="$AR" CFLAGS="$CFLAGS" libfdt/libfdt.a
cp "$VM_WORK/src/dtc/libfdt/libfdt.a" "$VM_PREFIX/lib/"
cp "$VM_WORK/src/dtc/libfdt/"{fdt.h,libfdt.h,libfdt_env.h} "$VM_PREFIX/include/"

# Only Android patches affecting enabled system/TCG code apply here. The full
# user-mode, virtfs, curses and plugin patches are intentionally inapplicable.
for VM_PATCH in 0000-android-config-support.patch 0010-add-missing-arch_prctl.patch 0014-disable-signalfd.patch; do
  patch -d "$VM_WORK/src/qemu" -p1 --batch --forward < "$VM_ROOT/scripts/vm/patches/qemu/$VM_PATCH"
done
python3 "$VM_ROOT/scripts/vm/patch-qemu.py" --source "$VM_WORK/src/qemu" --shim "$VM_ROOT/vm-engine/src/main/jni/qemu_jni.c"
VM_SETJMP=''
if [[ $VM_ABI == arm64-v8a ]]; then
  mkdir -p "$VM_WORK/setjmp/private"
  cp "$VM_ROOT/scripts/vm/patches/setjmp-aarch64/setjmp.S" "$VM_WORK/setjmp/"
  for VM_HEADER in "$VM_ROOT"/scripts/vm/patches/setjmp-aarch64/private-*.h; do
    cp "$VM_HEADER" "$VM_WORK/setjmp/private/${VM_HEADER##*/private-}"
  done
  "$CC" $CFLAGS -I"$VM_WORK/setjmp" -c "$VM_WORK/setjmp/setjmp.S" -o "$VM_WORK/setjmp.o"
  "$AR" rcs "$VM_PREFIX/lib/libandroid-setjmp.a" "$VM_WORK/setjmp.o"
  VM_SETJMP='-landroid-setjmp'
fi
VM_WRAP=''
for VM_SYMBOL in fork vfork forkpty posix_spawn posix_spawnp system popen execve execvp execv execvpe execl execlp execle fexecve; do
  VM_WRAP+=" -Wl,--wrap=$VM_SYMBOL"
done
mkdir "$VM_WORK/qemu-build"
(cd "$VM_WORK/qemu-build"; "$VM_WORK/src/qemu/configure" \
  --target-list=aarch64-softmmu --without-default-features --disable-download \
  --cross-prefix="$VM_TRIPLE-" --cc="$CC" --cxx="$CXX" --host-cc=gcc \
  --extra-cflags="$CFLAGS" --extra-cxxflags="$CXXFLAGS" \
  --extra-ldflags="$LDFLAGS $VM_SETJMP -llog -landroid $VM_WRAP" \
  --enable-tcg --enable-fdt=system --enable-iconv --disable-rust --disable-modules \
  --disable-tools --disable-guest-agent --disable-docs --audio-drv-list= \
  -Db_staticpic=true -Dprefer_static=true --prefix="$VM_PREFIX")
ninja -C "$VM_WORK/qemu-build" -j"$VM_JOBS" liblinex_qemu_aarch64.so
cp "$VM_WORK/qemu-build/liblinex_qemu_aarch64.so" "$VM_OUTPUT/$VM_ABI/"
python3 "$VM_ROOT/scripts/vm/verify-engine.py" --library "$VM_OUTPUT/$VM_ABI/liblinex_qemu_aarch64.so" \
  --abi "$VM_ABI" --tools "$VM_TOOLS" --output "$VM_OUTPUT/$VM_ABI/elf-evidence.json"
cp "$VM_ROOT/scripts/vm/native-sources.json" "$VM_OUTPUT/$VM_ABI/"
cp "$VM_WORK/qemu-build/config-host.h" "$VM_OUTPUT/$VM_ABI/"
cp "$VM_WORK/qemu-build/compile_commands.json" "$VM_OUTPUT/$VM_ABI/"
# Preserve the actual inputs, exact patches/shim and relinkable objects to meet
# static LGPL distribution obligations. Retain this alongside any binary.
tar -C "$VM_WORK" -czf "$VM_OUTPUT/$VM_ABI/corresponding-source.tar.gz" src
tar -C "$VM_WORK" -czf "$VM_OUTPUT/$VM_ABI/relinkable-build.tar.gz" qemu-build prefix
cp -a "$VM_ROOT/scripts/vm" "$VM_OUTPUT/$VM_ABI/build-scripts"
cp -a "$VM_ROOT/vm-engine/src/main/jni" "$VM_OUTPUT/$VM_ABI/build-scripts/jni"
echo "Native ELF verified. Android JNI load, kernel boot and process proof remain required."

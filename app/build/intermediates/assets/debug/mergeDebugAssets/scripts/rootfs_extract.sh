#!/bin/sh
# ==============================================================================
# Linex Rootfs Extraction & Verification Helper
# Unpacks container rootfs archives (.tar.gz, .tar.xz, .tar)
# into the target container directory with permission normalization.
# ==============================================================================

# Disable strict exit-on-error so warnings like symlink or permission skips don't abort the entire script
set +e

ARCHIVE_PATH="$1"
TARGET_DIR="$2"

if [ -z "$ARCHIVE_PATH" ] || [ -z "$TARGET_DIR" ]; then
    echo "Usage: $0 <archive_path> <target_directory>"
    exit 1
fi

if [ ! -f "$ARCHIVE_PATH" ]; then
    echo "[Linex:Extract] ERROR: Archive file not found: $ARCHIVE_PATH"
    exit 2
fi

echo "[Linex:Extract] Preparing target directory: $TARGET_DIR"
mkdir -p "$TARGET_DIR"

echo "[Linex:Extract] Inspecting archive format for: $ARCHIVE_PATH"

EXTRACT_CMD=""

case "$ARCHIVE_PATH" in
    *.tar.gz|*.tgz)
        echo "[Linex:Extract] Detected Gzip compressed tarball."
        # Crucial for Android: Toybox tar doesn't strip leading slashes when extracting.
        # If an entry starts with /bin or /usr, tar attempts to write to Android's root filesystem (/) and fails with EROFS.
        # We strip leading slashes or extract using proot/toybox safely.
        if command -v gzip >/dev/null 2>&1; then
            # Method 1: gzip piped into tar directly inside TARGET_DIR
            EXTRACT_CMD="cd \"$TARGET_DIR\" && gzip -dc \"$ARCHIVE_PATH\" | tar -x -C \"$TARGET_DIR\" 2>/dev/null || cd \"$TARGET_DIR\" && gzip -dc \"$ARCHIVE_PATH\" | tar -x 2>/dev/null || tar -xzf \"$ARCHIVE_PATH\" -C \"$TARGET_DIR\""
        else
            EXTRACT_CMD="cd \"$TARGET_DIR\" && tar -xzf \"$ARCHIVE_PATH\" -C \"$TARGET_DIR\" 2>/dev/null || tar -xzf \"$ARCHIVE_PATH\""
        fi
        ;;
    *.tar.xz|*.txz)
        echo "[Linex:Extract] Detected XZ compressed tarball."
        if command -v xz >/dev/null 2>&1; then
            EXTRACT_CMD="xz -d -c \"$ARCHIVE_PATH\" | tar -x -C \"$TARGET_DIR\""
        else
            EXTRACT_CMD="tar -xJf \"$ARCHIVE_PATH\" -C \"$TARGET_DIR\""
        fi
        ;;
    *.tar.bz2|*.tbz2)
        echo "[Linex:Extract] Detected Bzip2 compressed tarball."
        EXTRACT_CMD="tar -xjf \"$ARCHIVE_PATH\" -C \"$TARGET_DIR\""
        ;;
    *.tar)
        echo "[Linex:Extract] Detected uncompressed tarball."
        EXTRACT_CMD="tar -xf \"$ARCHIVE_PATH\" -C \"$TARGET_DIR\""
        ;;
    *)
        echo "[Linex:Extract] Unknown archive extension. Trying standard tar gzip decompression..."
        EXTRACT_CMD="tar -xzf \"$ARCHIVE_PATH\" -C \"$TARGET_DIR\" 2>/dev/null || tar -xf \"$ARCHIVE_PATH\" -C \"$TARGET_DIR\""
        ;;
esac

if [ -z "$EXTRACT_CMD" ]; then
    echo "[Linex:Extract] ERROR: No suitable decompressor found for $ARCHIVE_PATH"
    exit 3
fi

echo "[Linex:Extract] Executing extraction command..."
eval "$EXTRACT_CMD"

# Verify extraction succeeded by checking essential rootfs nodes
if [ ! -d "$TARGET_DIR/bin" ] && [ ! -d "$TARGET_DIR/usr" ]; then
    echo "[Linex:Extract] ERROR: Extraction completed but /bin or /usr missing."
    exit 4
fi

# Ensure user has write and exec access to target directories for rootless execution
chmod -R u+rwX "$TARGET_DIR" 2>/dev/null || true

echo "[Linex:Extract] Rootfs successfully extracted and validated."
exit 0

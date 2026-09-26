#!/bin/sh
# Extraction now runs through RootfsArchive in the app, using bundled gzip/XZ/tar
# readers, validated paths, staged installation, and explicit completion checks.
# Do not revive the old Android tar fallback: it hid unpack failures and could
# mark a partially extracted rootfs as ready.
echo '[Linex:Extract] This legacy helper is retired. Retry setup from the updated Linex app.' >&2
exit 1

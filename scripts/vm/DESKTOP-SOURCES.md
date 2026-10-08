# Corresponding Debian source archives

`archive-desktop-sources.py` consumes the exact `desktop-image-lock.json` from
the authenticated factory build. Supply its reviewed SHA-256, not a hash fetched
from an unrelated server at installation time:

```sh
python3 scripts/vm/archive-desktop-sources.py \
  --lock dist/vm-desktop-packages/desktop-image-lock.json \
  --lock-sha256 "$REVIEWED_LOCK_SHA256" \
  --output dist/vm-desktop-sources
```

The output must be fresh, under the project's `dist/`. The script checks that
every solved binary package has its exact corresponding source/version record.
It downloads every source-control file, source archive, Debian patch archive and
upstream detached signature listed in `Checksums-Sha256`, with exact length/hash
validation. Only official Debian HTTPS archive URLs and redirects are accepted.
Limits are two GiB per file, sixteen GiB total, ten minutes per file and one hour
for the collection. Transient HTTP429/502/503/504 or network failures receive at
most three attempts at the same authenticated bytes. TLS and integrity failures
are never bypassed or retried as successful downloads.

Authentication of the original APT indexes belongs to the image builder's
OpenPGP/APT verification. This script verifies the supplied lock pin and source
file pins; it does not independently claim to verify detached PGP signatures.
It emits `sources-manifest.json` only after the entire collection succeeds.
The tested candidate contains254 source packages,920 files and1,885,190,374
download bytes. These counts are measured from that candidate, not fixed
requirements for future builds.

The 2026-10-07 accepted host-desktop candidate's real source collection completed
at `dist/vm-desktop-sources-run11` with lock SHA-256
`ce683914608e5ac749e7006aeb1077ac789e0d10cfe9852b8a7c1affc338020d`.
The complete tar contains922 entries (920 source files and the two manifests).
Every tar entry was independently reread and checked against its original pin:
`dist/linex-debian-trixie-ce683914608e5ac7-sources.tar`,1,886,986,240 bytes,
SHA-256 `c47aee827c8926d5ce55011acd4e8711ddfca04e3aec1deaab4452d184ea516f`.
The adjacent `.proof.json` records those measurements. This is source-delivery
evidence only; it does not approve an APK release or cover the separate kernel.

Keep `desktop-image-lock.json`, `sources-manifest.json` and all downloaded
subdirectories together when packaging release assets. If asset size requires
splitting a tar file, publish every part and an exact checksum list. Preserve
the factory's `/usr/share/doc/*/copyright` and `/usr/share/common-licenses` files;
the source archives also retain their original license notices and build files.

This output covers the solved Debian guest packages only. The Ubuntu guest
kernel's exact corresponding source/configuration and the patched QEMU/native
engine's source/relinkable materials are separate release requirements.
`kernelSourceIncluded`, `nativeEngineSourceIncluded` and `releaseReady` remain
false in this script's output. Source collection alone cannot approve an APK or
claim browser/Android runtime stability.

Official format/trust references:
[Debian source format](https://www.debian.org/doc/debian-policy/ch-controlfields.html#debian-source-control-files-dsc)
and [APT trust chain](https://manpages.debian.org/trixie/apt/apt-secure.8.en.html).

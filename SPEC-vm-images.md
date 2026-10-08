# Spec: vm-images

Status: direction reviewed and accepted by the parent agent, 2026-10-07, within
the user's approved VM modules and authorization to continue through release.
This is not a separate human approval of every implementation detail. The thin
storage proof is being implemented; no desktop image/runtime proof is claimed.

## Objective and assumptions

Provide a verified ARM64 bootable Linux desktop disk for new VM instances,
with XFCE, a supported Firefox package, durable user files, offline first boot,
and resumable setup under the existing foreground operation service. Existing
Ubuntu/other PRoot instances retain their engine and data. Label the first new
VM image **Debian Desktop**; never call it Ubuntu or silently convert instances.
CPU emulation is the baseline. Root inside the guest does not require Android root.

## Chosen first image

Build a plain ext4 raw disk in Linux CI, using the already-proved direct-boot
Ubuntu ARM64 kernel and a small dedicated switch-root initramfs. Its verified
kernel config has `CONFIG_VIRTIO_BLK=y`, `CONFIG_EXT4_FS=y`, `CONFIG_JBD2=y`,
and `CONFIG_FS_MBCACHE=y`. Keep both existing proof initramfs fixtures unchanged.

Use Debian **13 trixie** ARM64 userspace with authenticated stable, updates and
security repositories. Parent accepted this provider correction on2026-10-07
inside the approved raw-ext4/guest-TigerVNC module boundary. Official Alpine3.24
currently mirrors FirefoxESR140.12 while Mozilla has issued high severity fixes
through ESR153.4 (2026-09-29); absence from Alpine secdb is not security clearance.
Do not publish the stale browser. Debian trixie-security offers ARM64
`firefox-esr153.4.0esr-1~deb13u1`, confirmed against its actual package index.
The Alpine3.24.2 thin storage prototype remains unchanged and useful;
it is not the production browser provider. Existing PRoot Ubuntu instances
remain separate. [Debian ARM64 Firefox](https://packages.debian.org/trixie/arm64/firefox-esr),
[Mozilla ESR153.4 advisory](https://www.mozilla.org/en-US/security/advisories/mfsa2026-100/).

Image release1 is a 4GiB raw filesystem, compressed for download. A Linux build
can use `mke2fs -t ext4 -b 4096 -d staging/rootfs -L LINEX_ROOT rootfs.raw 1048576`
after creating a 4GiB file. Record feature flags/UUID/build inputs.
No partition table, UEFI, host mount, QCOW2 overlay, or phone image-builder is
needed for this slice. Raw writes are private to one instance; sparse zero-range
creation is an optimization whose logical length/content must still verify.

## Compared alternatives

| Route | Concrete benefit | Additional work before desktop proof |
|---|---|---|
| Debian ext4 + direct kernel + guest TigerVNC | Maintained ARM64 browser, built-in storage drivers, private SLIRP transport | Resolve/freeze APT closure, actual XFCE/browser boot proof |
| Ubuntu Noble cloud QCOW2 | Familiar Ubuntu identity and upstream disk | Current ARM64 image is about591MiB before desktop; direct kernel/initrd or firmware boot, cloud-init seed, QCOW2 private writable copy, provisioning and browser packaging |

The Ubuntu cloud image is a server image, not a prepared XFCE browser desktop.
Keep an Ubuntu provider possible through the same typed asset contract, but do
not advertise it as implemented by this Debian desktop provider. [Official Noble artifacts](https://cloud-images.ubuntu.com/noble/20260926/).

## Supply chain and build recipe

1. Use native `ubuntu-24.04-arm` CI. Run genuine debootstrap minbase with
   mandatory OpenPGP verification against SHA256/fingerprint-pinned Debian13
   archive, security and release keys; never fall back to HTTPS-only trust.
2. Provision supported HTTPS trixie/main, trixie-updates and trixie-security
   with genuine APT signature/index/package verification and install scripts.
   No `--allow-unauthenticated`, custom signature parser or skipped postinstalls.
   Host root in disposable CI is permitted; phone root is never required.
3. Install minimal XFCE, TigerVNC, FirefoxESR, fonts, CA/curl, Python3 guest control,
   BusyBox-static, network/resource/repair tools. Require browser version at least
  153.4 and a fresh release-time Mozilla/Debian security audit. The genuine solver
   outputs the complete exact package URL/version/SHA256/size and source metadata
   lock; package counts/pins are unknown until it actually runs.
4. Configure guest PID1 with bounded JSON-line typed control, locked login accounts,
   non-root `linex` XFCE/TigerVNC, per-launch random VNC credentials in private tmpfs,
   generated machine identity, DHCP and `/run`/`/dev/shm`. Keep Firefox sandbox
   intact. No SSH/auto-login/fixed-password/browser sandbox bypass.
5. Create a small static BusyBox initramfs: await virtio disk, mount journaled ext4,
   move `/proc`/`/sys`/`/dev`, switch-root into Debian Python PID1. Mount failure
   emits a recovery diagnostic and powers off; never format user data. Full offline
   filesystem repair integration remains a separate verified slice.
6. Build a fresh 4GiB raw factory via `mke2fs -d`, preserve guest ownership and
   file modes, check with `e2fsck -f -n`, then xz-compress. Record both expanded
   and compressed hashes/lengths plus immutable kernel/initramfs hashes. Build
   manifest stays `releaseReady=false` until real guest/browser/Android proof.
7. Export exact binary closure, authenticated InRelease evidence, source metadata,
   distro copyright notices, kernel/QEMU source materials and build provenance.
   Actual corresponding source payload publication is required before release.

## Provider contract and storage

`VmImageDescriptor` is a compiled-in or authenticated catalog record: stable
image ID, revision, architecture, supported engine contract version, immutable
HTTPS asset URLs/hashes/lengths, compression type, expanded disk length, kernel
and initramfs digests, source/license links and minimum RAM/storage.

`PreparedVmDisk` identifies a private regular writable raw disk plus the verified
immutable boot assets. The production engine receives this typed record, not an
arbitrary block URI, host path, boot command or QEMU argument string. Use a new
production request contract; the fixture's 256–1024MiB bounds stay intact.

Store assets in `files/vm-images/<image-id>/<revision>/` and private disks in
`files/vm-instances/<instance-id>/`. Reject symlinks/path escape, unsafe URL
redirects, excess compressed or expanded data and concurrent writers. Hold a
per-instance launch/write lock. Hash factory bytes during installation; a
mutable user disk naturally changes after boot and must not be compared with
the original factory hash on every launch.

Download into `.part` using validated HTTPS and byte counts. Persist a matching
ETag/length/range checkpoint; if identity changes, restart that asset with a
visible reason. After complete hash/decompression verification, fsync and rename
within the private filesystem. Publish READY metadata last. Cancellation or
process death must not create a READY entry or trigger a silent download loop.
Reuse the existing notification operation contract for download, verify, expand,
prepare and interrupted/retry states; do not invent parallel global progress.

## HTTPS and security verification

Guest `curl` must use its verified distro CA bundle and default peer/hostname
verification. Prove HTTPS success against a reachable controlled valid endpoint
and failure against wrong-host and untrusted-certificate endpoints. A BusyBox
TLS connection or `curl -k` does not prove certificate verification. Test public
DNS/HTTPS through the Android resolver bridge, including a strict Private DNS
network; no hard-coded public DNS or root settings changes are allowed.

Before publishing, compare locked packages with Debian security tracker and current Mozilla
ESR advisories, retain the audit snapshot and update unsupported/vulnerable
browser builds. Preserve `about:support`/sandbox evidence. Kernel packages also
need a release-time support/security check; a previously booted kernel is not
automatically a current production security baseline.
[Debian security tracker](https://security-tracker.debian.org/),
[Mozilla ESR advisories](https://www.mozilla.org/en-US/security/known-vulnerabilities/firefox-esr/).

Distribution includes QEMU's native corresponding source/relinkable materials,
kernel corresponding source, image scripts, each package's license texts and
the exact distro source recipes/tarballs needed by applicable copyleft licenses.
Firefox combines MPL/LGPL/GPL components; do not summarize the complete image
as one permissive license. Preserve vendor trademarks/notices and document edits.

## Commands and project structure

Existing executable checks: `python -m unittest discover -s scripts/vm -p 'test_*.py'`
and `./gradlew :vm-engine:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug`.

Planned commands, implemented and tested before use:

```sh
sudo python3 scripts/vm/build-desktop-image.py --output dist/vm-desktop-packages
sudo python3 scripts/vm/prepare-desktop-image.py --provisioned dist/vm-desktop-packages --output dist/vm-desktop-image
python3 scripts/vm/test-desktop-image.py --manifest dist/vm-desktop-image/manifest.json --qemu qemu-system-aarch64 --output dist/vm-desktop-proof
./gradlew :vm-engine:connectedDebugAndroidTest
```

Image scripts/locks/tests live in `scripts/vm/`; image installation logic and unit
tests live under `app/src/main/java/com/linex/app/core/vm/` and matching test paths.
Guest init/control sources live in `scripts/vm/guest/`. Generated disks/downloads
stay in ignored `dist/`, outside source control. Use strict typed Kotlin records,
bounded streams and explicit operation stages, following existing project style:

```kotlin
data class PreparedVmDisk(val instanceId: String, val imageId: String, val disk: File)
// READY is written only after all pinned factory bytes verify and files commit.
```

## Acceptance, boundaries and ordered slices

1. Storage proof: actual ext4 disk boot, write a nonce as `linex`, clean shutdown,
   restart, nonce retained; forced stop also exercises journal recovery without
   factory reset. No Android guest-process child multiplication.
2. Desktop proof: authenticated private console reaches XFCE, Firefox opens a
   controlled page as non-root, closes/reopens, and survives a30-minute browser/
   idle cycle in the Android-managed VM. Record guest OOMs and host service death
   separately. Physical Snapdragon performance remains a separate measured check.
3. App setup proof: cancellation, corrupt/truncated assets, low storage, changed
   ranges, background/notification progress, process restart, retry and two private
   instance disks cannot corrupt user data or redownload READY instances.

Always validate actual inputs and retain runtime evidence; never ship unresolved
pins, hard-coded credentials, root/device workarounds or a fake Ubuntu image.
Preserve PRoot and existing user data. Parent accepted the direction and reviews
each concrete slice plus lock/security results before release; no additional old
engine approval is required. Open issues: exact package closure/security outcome and
physical-device performance are unproved, not silently waived.

# Native ARM desktop image

The provider is Debian13 trixie with maintained FirefoxESR, XFCE and guest
TigerVNC. Existing PRoot instances and the Alpine storage fixture are preserved.
No phone root is required. Build as root only on a disposable Linux ARM64 CI VM.

For the Linux-host console/browser proof, provision guest `x11-utils` for `xev`
and install host `qemu-system-arm` plus `python3-pycryptodome`. Then run:

```sh
sudo /usr/bin/python3 scripts/vm/test-desktop-image.py \
  --image dist/vm-desktop-image --evidence dist/vm-desktop-host-proof --timeout 300
```

This harness uses loopback TCP only on disposable host CI because Ubuntu24.04
QEMU8 lacks Unix host-forward syntax. Its evidence explicitly keeps Android
private-Unix and physical-phone performance gates false. It verifies guest VNC
authentication, complete Raw framebuffer coverage, keyboard/mouse/scroll events,
non-root default-CA curl HTTPS, non-root headless Firefox screenshot creation,
zero QEMU host children, and clean shutdown/read-only filesystem consistency.
It does not prove Firefox's displayed page DOM or30-minute Android GUI stability.

The GitHub standard public-repository runner label is `ubuntu-24.04-arm`.
Run from the Linex repository root:

```sh
sudo apt-get update
sudo apt-get install --yes debootstrap gnupg ca-certificates e2fsprogs xz-utils
sudo python3 scripts/vm/build-desktop-image.py --output dist/vm-desktop-packages
sudo python3 scripts/vm/prepare-desktop-image.py \
  --provisioned dist/vm-desktop-packages --output dist/vm-desktop-image
```

Both outputs must be fresh directories below this project's `dist/`; existing
outputs are rejected. Keep failed build evidence and use a fresh path for retry.
Runner steps reading root-owned outputs may use sudo; upload published artifacts
from runner-owned copies. Do not change ownership of the staging root before
`mke2fs -d`: guest inode owners must retain their configured UID/GID.

Provisioning downloads SHA256/fingerprint-pinned Debian signing keys into its
private GnuPG home, requires debootstrap OpenPGP validation, then runs genuine
APT signature/index/package checks over HTTPS with install scripts enabled.
It records every solved binary URI/version/hash/size, source metadata, and
authenticated InRelease hashes in `desktop-image-lock.json`. The lock is a real
solver output; no placeholder package count or invented hashes are committed.
Minimum Firefox version153.4 matches Mozilla's Sept29,2026 security baseline;
renew this baseline and check Debian/kernel security before each release.

The separate assembly step verifies static ARM64 BusyBox, stages a non-root
desktop user, preserves file modes, builds a fresh4GiB raw ext4 disk, checks it
read-only, and xz-compresses it. `manifest.json` describes immutable kernel and
initramfs plus expanded/compressed disk hashes. `releaseReady=false` and
`runtimeProofPassed=false` remain until actual boot/browser/Android tests pass.
This build does not prove guest GPU acceleration or physical phone FPS.

Guest PID1 mounts runtime tmpfs, generates its machine identity, runs DHCP using
the proven SLIRP address/gateway/DNS contract, and reads private serial JSON lines
with a512-byte bound. A launch request has exactly these typed fields:

```json
{"command":"launch","session":"32 lowercase hexadecimal characters","password":"8 random characters from A-Z a-z 0-9 _ -","width":1280,"height":720,"fps":30}
```

The service must generate a fresh credential for each launch and keep it out of
logs. PID1 returns `LINEX_VM_DESKTOP_CONTROL_READY` before configuration and
`LINEX_VM_DESKTOP_READY session=<token>` after starting guest VNC and XFCE.
Credentials are encrypted into VNC's legacy eight-byte password file stored in
private tmpfs; private Unix transport isolation remains essential. XFCE and
VNC execute as UID1000. Firefox's sandbox is not disabled.

Stop uses exactly `{"command":"stop","session":"<current token>"}`. Guest
control stops owned session process groups, syncs, remounts ext4 read-only, emits
`LINEX_VM_DESKTOP_CLEAN_STOP`, and powers off. Host forced-stop journal recovery
requires a separate runtime proof using the final image; passing the earlier
Alpine four-boot fixture alone is not sufficient.

The guest VNC port5901 must be reachable only through the service's fixed Unix
SLIRP host forward, using the approved short private `files/vmc/<32hex>/c` path.
Never expose Android TCP listeners, arbitrary host-forward strings or a static
VNC credential. Source package metadata is retained, but corresponding source
payloads and copyright/relinkable materials must be packaged before publication.

Sources: [GitHub ARM runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
[Debian debootstrap](https://manpages.debian.org/trixie/debootstrap/debootstrap.8.en.html),
[APT trust chain](https://manpages.debian.org/trixie/apt/apt-secure.8.en.html),
[Debian Firefox ARM64](https://packages.debian.org/trixie/arm64/firefox-esr),
[Mozilla baseline](https://www.mozilla.org/en-US/security/advisories/mfsa2026-100/).

# Ubuntu ARM64 kernel source and security audit — 2026-10-08

Recommendation: replace bundled 6.8.0-142.142 with authenticated 6.8.0-146.146, then rerun host and Android acceptance. The collected evidence does not establish release readiness or phone performance.

## Exact bundled kernel correspondence

Cloud image URL: https://cloud-images.ubuntu.com/noble/20260926/unpacked/noble-server-cloudimg-arm64-vmlinuz-generic

The 18,585,914-byte kernel SHA256 `71fe6776ef591ea452661a5933e11d2044c5df6aaf76cf39b8d9655fa4e47904` exactly equals `./boot/vmlinuz-6.8.0-142-generic` extracted from authenticated ARM64 `linux-image-6.8.0-142-generic_6.8.0-142.142_arm64.deb`. Its package record identifies source `linux-signed` and `Built-Using: linux (= 6.8.0-142.142)`.

Both exact source packages are collected: `linux_6.8.0.orig.tar.gz`, `linux_6.8.0-142.142.diff.gz`, `linux_6.8.0-142.142.dsc`, `linux-signed_6.8.0-142.142.tar.xz`, and its `.dsc`. The original archive plus Ubuntu diff constitutes the complete source package, including packaging/build rules and configuration annotations. Use `dpkg-source -x linux_6.8.0-142.142.dsc` on a Linux host with its normal build prerequisites. No rebuild/reproducibility assertion is made.

Exact config comes from authenticated modules package: `modules-boot_config-6.8.0-142-generic`, SHA256 `217703dcf37786843b5f4f6f36e778dc04fbfe6cbf1f41dae7c09124e96901a4`. Upstream COPYING, full LICENSES tree, binary copyright, signed-source packaging, Ubuntu copyright/config/build-rule diffs, and reconstructed selected source files are retained. COPYING states GPL-2.0 WITH Linux-syscall-note; retained license files contain the complete terms and exception.

## Authentication and freshness

The Ubuntu archive fingerprint was pinned to `F6ECB3762474EDA9D21B7022871920D1991BC93C`; the public key was retrieved from Ubuntu's HTTPS keyserver and its fingerprint checked. Both InRelease signatures validate. Public fingerprint corroboration: https://irclogs.ubuntu.com/2018/10/26/%23ubuntu-release.html

Signed noble-security Date: 2026-10-08 05:09:54 UTC. Signed noble-updates Date: 2026-10-08 05:10:46 UTC. Each compressed Packages/Sources index matches the release SHA256 and length. All source and binary artifacts match those authenticated indexes, not approximate archive URLs. Individual source `.dsc` maintainer signatures are not independently trusted; their entire files are authenticated through Sources → InRelease.

## Security findings

Security-pocket generic version remains142. Ubuntu's official USN-8817-1 JSON (published2026-09-24) lists142 as its security update: https://ubuntu.com/security/notices/USN-8817-1.json

Updates-pocket generic dependency is now146. Its authenticated source changelog lists133 CVE identifiers absent from142's changelog. This count is a changelog comparison, not a claim that all133 are exploitable in this guest.

Selected source files were reconstructed by applying every unified-diff hunk to the authenticated original with exact context/count assertions. Concrete fixes absent142 and present146:

- CVE-2026-53275: IPv6 multicast query group address is copied rather than kept as a pointer across potential skb reallocation; `CONFIG_IPV6=y`.
- CVE-2026-53249: IPv4 source-route options require CAP_NET_RAW; `CONFIG_INET=y`.
- CVE-2026-52910: classic BPF reuseport program freeing waits for an RCU grace period; BPF/networking built in.
- CVE-2026-53183/63867: MPTCP receive-window/TOCTOU fixes; `CONFIG_MPTCP=y`.

Canonical OVAL snapshot https://security-metadata.canonical.com/oval/com.ubuntu.noble.cve.oval.xml.bz2 has generator timestamp2026-10-07T07:53:05 and describes these defects. Its noble/linux criteria still have no fixed-version condition for these CVEs; do not represent the source patch evidence as an already updated security-tracker status. Saved relevant OVAL definitions include referenced tests, objects, and states. Optional individual CVE `.json` URLs returned404; the official OVAL and signed source comparison provide the evidence instead. USN HTML retrieval returned504; official USN JSON succeeded.

Upgrade requirements are concrete in `UPGRADE-PINS.json`: exact official HTTPS package URLs, sizes/SHA256, kernel/config payload pins, complete corresponding source references, and21 verified built-in storage/network/boot features. `upgrade-packages-selected.txt` preserves authenticated package records. The factory/app candidate was not modified.

## Verification

`verify-evidence.py` passes both signatures/freshness, all saved download hashes, authenticated source/package linkage, exact bundled-kernel equality, upgrade kernel hash, actual missing142/present146 source-fix assertions, and built-in requirements. `verification.txt` records its output. `SOURCE-PROVENANCE.json` and `SHA256SUMS` bind the retained files. Whole-kernel vulnerability exhaustiveness, new-kernel boot, host/Android acceptance and phone performance remain outside this source audit.

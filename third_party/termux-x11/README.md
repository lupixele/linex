# Embedded Termux:X11 native library

Linex embeds Termux:X11's X server and EGL renderer. No external app is required.

Upstream: https://github.com/termux/termux-x11
Pinned source: `0e1ebb4c180f4e8e7a14a80f7cd0db8301791b6d`
Verified from the resolved `nightly` tag and APK version `1.03.01-0e1ebb4-01.10.26`;
the release API's `target_commitish` is stale and must not be used as provenance.
Downloaded official `nightly` APK SHA-256:
`97e1c5d471cbb6af84267d7d2d3c475f3862ad2cc2aecebfe98ed0097a0164c0`

Libraries extracted unchanged from that APK:

| ABI | SHA-256 |
| --- | --- |
| arm64-v8a | c5ae6a56ca6dae026a234383fc7366d87c18a2830f37726c5a72aa487f9f8d81 |
| x86_64 | 1e30e8807b443c1a68a66be1916d985fac507b345ac13524d2ef086919dde872 |

Upstream is GPL-3.0; `LICENSE` contains its license. Its X.org dependencies have
their own notices in their pinned source checkouts. Distribution of a combined
application must comply with GPL-3.0 and supply its corresponding source, including
native dependency sources and build scripts. This document is not a substitute
for supplying that source alongside binaries.

`Acquire-NativeSource.ps1` retrieves the exact source tree and all pinned native
submodules. Do not substitute the moving `nightly` tag's later libraries without
updating source provenance, hashes and JNI compatibility checks.

Release attachment: `linex-termux-x11-native-source-0e1ebb4.tar.gz` contains the
complete pinned upstream tree and all 16 dependency source checkouts, including
their licenses and build scripts. SHA-256:
`db4bf1740f6b0e465d584108107c01a51fa854723fd037016802c60efbe557d1`.
It contains upstream's publicly distributed `testkey_untrusted.jks` test fixture;
it contains no Linex signing key. `SOURCE_COMMITS.txt` records dependency revisions.
Extract the archive and run upstream's `./gradlew assembleDebug` in a configured
Linux/Unix build environment. Linex source is supplied by the corresponding Git
release tag; publish this native-source archive alongside every APK using these
libraries.

Native build requirements from the pinned upstream project: Android NDK
29.0.14206865, CMake 3.22+, Python 3, Bison, Bash and patch. On Windows this build
needs a Unix-compatible tool environment; normal Linex builds use the verified
prebuilt libraries above. Linex's thin JNI adapters retain upstream class names
and signatures while integrating with Linex lifecycle and controls.

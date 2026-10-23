# Fast Lane third-party notices (draft)

This file covers components distributed with, or required by, the Fast Lane
sealed toolbox and the cross-lane distcc path. It is a build/release draft and
does not replace legal review. The project's own license is the root `LICENSE`
(GPL-3.0).

## Shipped in the v1 minimal toolbox

### BusyBox 1.36.1-r31

- Copyright: BusyBox contributors.
- License: GPL-2.0-only.
- Binary source: Alpine Linux 3.20 `busybox-static` aarch64 package
  `busybox-static-1.36.1-r31.apk`, SHA256
  `1d8e7a7fc2ed69bdb8eb7be9d962489c21a0f75b72d8a325437c8a09d4cfac70`.
- Extracted binary: `bin/busybox.static`, SHA256
  `ebd2865edcab0b590c7d0edb70d3e782cbfb541e518a390ced3a3e186509bc7f`.
- Corresponding source: upstream `busybox-1.36.1.tar.bz2`, SHA256
  `b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314`,
  plus the Alpine 3.20-stable packaging (APKBUILD and patches) at
  <https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.20-stable/main/busybox>.
- Distribution obligation: any release artifact that contains this binary must
  also publish the corresponding source archive (upstream tarball plus the
  Alpine patches and build instructions) at the same distribution location, or
  provide a written offer valid for at least three years. Do not publish the
  APK alone. Legal review is required before the first public release.

#### Corresponding-source delivery for BusyBox

The v1 repository stores only the pinned package and binary SHA256 values; it
does not vendor the BusyBox source tree. Before a public APK is published, the
release owner must do one of the following and record the choice in the release
ticket:

1. Attach `busybox-1.36.1.tar.bz2` (SHA256
   `b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314`) and
   the Alpine 3.20-stable `main/busybox` APKBUILD plus patches next to the APK;
   or
2. Publish a written offer, valid for at least three years, to provide the same
   archive and packaging metadata to any third party at no more than the cost of
   distribution.

The source bundle must include the exact Alpine revision used to build the
package, the compiler/build instructions, and any patches applied by Alpine.
Linking to `busybox.net` alone is not a substitute for delivering the source
that corresponds to the shipped binary.

## Build inputs and reproducibility

- The toolbox build is deterministic: no timestamps are written into
  `bin/manifest.json`, file modes are fixed at `0755`, and injected zip entries
  use the fixed `SOURCE_DATE_EPOCH` recorded in the runtime manifest.
- `tools/fastlane-build/reproduce.py` builds the toolbox twice and compares a
  canonical tree digest. A release must record the digest and both SHA256
  values in the P03 report; a mismatch blocks packaging.
- The toolbox must not exceed the 80 MiB starting budget. The v1 minimal
  profile is roughly 2.13 MiB of entries plus the generated manifest.

## Built for the cross-lane distcc path (not shipped in the APK)

### distcc 3.4 (Android arm64 distccd)

- License: GPL-2.0-or-later. distccd is executed as a separate process and is
  never linked into the app process; this keeps the GPL boundary at the process
  boundary.
- Source: upstream tarball
  <https://github.com/distcc/distcc/releases/download/v3.4/distcc-3.4.tar.gz>,
  SHA256 `2b99edda9dad9dbf283933a02eace6de7423fe5650daa4a728c950e5cd37bd7d`.
- Local patch: `tools/fastlane-build/distcc-reproducible.patch` replaces the
  `__DATE__` / `__TIME__` build stamp so two builds are byte-identical. It changes
  no behaviour.
- Build: `tools/fastlane-build/build-distccd.sh` with Android NDK
  27.0.12077973, static Bionic, 16 KB `PT_LOAD` and 64-byte `PT_TLS`. The TLS
  alignment shim is `tools/fastlane-build/distcc-tls-align.c`.
- Artifact: `build/fastlane-distccd/bin/distccd` (generated, never committed),
  SBOM `build/fastlane-distccd/SBOM.spdx.json`, checksum
  `build/fastlane-distccd/SHA256SUMS`.
- Bundled components inside the same source tarball: popt (MIT) and miniLZO
  2.10 (GPL-2.0-or-later). The binary also contains static Bionic objects from
  the Android NDK; the NDK `NOTICE.txt` must ship with the source bundle.
- Corresponding source: a release artifact that contains distccd must publish,
  next to the artifact, the upstream tarball above, the local patch, both build
  scripts, and the NDK `NOTICE.txt`. If that bundle is not attached, the
  release owner must instead publish a written offer valid for at least three
  years to provide the same corresponding source at no more than the cost of
  distribution. The release ticket must record which option was used. Linking
  to distcc.org alone does not satisfy this obligation.
- Status: build, ELF, SBOM and `--version` under qemu-user are verified in
  zero-device CI; execution on a real Android device and the DV-03 compiler/ABI
  matrix remain not-tested.

## Pending profiles (NOT shipped in v1; do not treat as verified)

### clang / LLVM

- License: Apache-2.0 WITH LLVM-exception.
- Source: <https://github.com/termux/termux-packages/tree/master/packages/libllvm>.
- Status: not-tested. No binary is pinned or shipped. APK size and compiler
  consistency are open decisions.

## Not distributed, but relevant to the design

- AOSP `zipfuse` (Virtualization guest): Apache-2.0. The Android platform
  provides the execute mode for APK-root `bin/`; this project does not ship
  `zipfuse` code.
- gvproxy / gvisor-tap-vsock: Apache-2.0. Not used by the Fast Lane distcc
  path; QEMU Full Lane networking is unchanged.
- PRoot: GPL-2.0. Not used or shipped by Fast Lane; research/Userspace
  Compatibility only. Any future distribution needs its own source compliance.
- JUnit 4.13.2: EPL-1.0, test-only dependency.

## Logging and privacy

The distcc host bridge emits counters only (outcome, byte counts, duration).
It does not log preprocessed source, object bytes, argv contents, or tokens.

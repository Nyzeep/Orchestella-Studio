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

## Pending profiles (NOT shipped in v1; do not treat as verified)

### distcc 3.4-4 (Termux package)

- License: GPL-2.0-or-later.
- Source: <https://github.com/termux/termux-packages/tree/master/packages/distcc>.
- Status: not-tested. No binary is pinned or shipped. If enabled later, the
  corresponding source and Termux patches must ship with the APK, and the
  compiler/ABI matrix must pass DV-03.

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

# UML GPL Sources — Orchestella Studio (W3-05 / R-05, 2026-11-24)

This record describes the UML (User Mode Linux) engine object code shipped
inside the Orchestella Studio APK (`lib/arm64-v8a/libuml_kernel.so`,
`lib/arm64-v8a/libuml_stub.so`, `lib/arm64-v8a/libuml_net.so`,
`lib/arm64-v8a/libuml_passt.so`, `lib/arm64-v8a/liborchestella_uml_pty.so`,
and the Alpine minirootfs baseline `alpine-minirootfs-3.22.2-aarch64.tar.gz`),
together with their pinned upstream inputs, build control scripts, and
corresponding-source path. It accompanies `app/src/main/assets/NOTICE.UML`,
`app/src/main/assets/UML-BUILD-INPUTS`, and the written offer in
`guest-assets/SOURCE-OFFER.txt` under the project's root `LICENSE`
(GPL-3.0-or-later) and ADR-0014 licensing obligations. No public binary
release is authorized by this file alone; the public release tag and matching
source archive are published by the named Release Maintainer before any public
release gate opens.

## 1. UML Kernel and 16 KB Stub (GPL-2.0 / Project Stub Recipe)

- Upstream fork: `Raymer8639/linux-um-arm64`
- Version / Git identity: Linux `7.2.0-rc4`, public tag `um-arm64-v0.3.0`,
  commit `169b17ff3f66d507cb137b770ceb1fd9f4547a32` (upstream Linux baseline
  `2f88f5689de1a039764d00f466209acf0c010eaf`)
- Source tarball SHA-256:
  `08578a494412125dfc6b8f203e984b68d20e313173319d6a6c115e2875e0116c`
- Build entrypoint and control scripts (preferred form for modification,
  GPL-2.0 §2):
  - Fast-profile build script: `research/uml-fastboot/build-fast.sh`
    (SHA-256 `adea5c3fdf0c09cbb04ce6f1b30ab65cc45b1ada4c89615b510c4823cb13703c`)
  - 16 KB stub relink recipe: `research/uml-kernel-build/build-stub-16k.sh`
    (SHA-256 `522bb30c160bde38c024efb72a77b37bdb0812546a499503112342798e3fb508`;
    `STUB_EXE_LDFLAGS="-Wl,--no-rosegment -static -Wl,-z,max-page-size=16384"`)
  - Base defconfig: `research/uml-kernel-build/defconfigs/linux-bionic.config`
    (SHA-256 `2344f4e0c8b9763633b7ae12d2dc172d8e42f37f5ba9fa8b670d4efa57bfb3dc`)
  - Fast-profile defconfig and fragment: `research/uml-fastboot/defconfig-fast`
    (SHA-256 `863de65f04ab18d141f98867b3cf20591e88acf57f3a3cc4d2e8ea416d0bd26e`)
    and `research/uml-fastboot/defconfig-fast.fragment`
    (SHA-256 `58487196e5cba7d576058ef141007ca5e8530b80329480b57d72d0ad43e881e5`)
  - Effective config digests recorded in `research/uml-fastboot/manifest.md` §3:
    `kernel.config` (`8b854fe2d9dd9f19328a9ac6ce755b283a4523d74a8b55dfaa82e46f07fe6051`)
    and `kernel-noinitrd.config` (`863de65f04ab18d141f98867b3cf20591e88acf57f3a3cc4d2e8ea416d0bd26e`)
  - 16 KB PT_LOAD check script: `research/uml-kernel-build/check-16kb-kernel.sh`
- Build environment: Ubuntu 24.04 container (`u1-build:24.04`, GCC 13.3.0 /
  GNU Make 4.3 / GNU ld 2.42), Android NDK r29 (`android-ndk-r29-linux.zip`,
  SHA-256 `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf`,
  clang 21.0.0, target `aarch64-linux-android30`), `SOURCE_DATE_EPOCH=0`,
  `KBUILD_BUILD_TIMESTAMP="Thu Jan 1 00:00:00 UTC 1970"`.
- Reproducibility: two clean rebuilds on the same container path layout are
  byte-identical (`research/uml-fastboot/manifest.md` §5); cross-path variance
  on `linux-fast` is tracked openly in §5 below.
- Pinned ARM64 UP products (`app/src/main/java/io/github/nyzeep/orchestella/UmlKernelAssets.java`,
  `FastProfile.PIN_MANIFEST`):
  - `lib/arm64-v8a/libuml_kernel.so` (`linux-fast`):
    `21e1c3f97269a831fa8bab5fd3ff0e8a0e9edc59554d17cce34913924f584389` (9,517,760 bytes)
  - `linux-fast-noinitrd` (external-rootfs control variant; not shipped in APK):
    `a5efce2b9197071a2db89735e247fa3cf548644b7d036ea99bf688b14eff17fb` (8,862,400 bytes)
  - `lib/arm64-v8a/libuml_stub.so` (`stub_exe`, 16 KB relinked):
    `87666f9167de09d0bf84b414618c39d9f460417a641f212f5f0f28c498a09a12` (2,056 bytes;
    currently distributed under root `LICENSE` GPL-3.0-or-later pending final
    licensing-attribution ruling — see §5)
  - `initramfs.cpio.gz` (embedded in `linux-fast`, gzip-only):
    `7523c9a20b956a3104d04540559422a7b566ef630a592c9d4ca8fdb2e5bd8381` (661,899 bytes)

## 2. Embedded Initramfs and Alpine Minirootfs Baseline

| Component | Version / Pin | License | Source and Verified SHA-256 |
| --- | --- | --- | --- |
| busybox-static (aarch64) | 1.37.0-r20 (Alpine 3.22) | GPL-2.0 | `https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/busybox-static-1.37.0-r20.apk`, APK SHA-256 `ee469aee2958feffd7f64dd96655704025ff60af614f77a1d7323dc237c34da2`, `bin/busybox.static` SHA-256 `a6bb1e6e349cbdfa690a78cd0017413c5446bdae02ee003ceb682d4ae1a004ff` |
| busybox-static (x86_64 host-ref) | 1.37.0-r20 (Alpine 3.22) | GPL-2.0 | Build-side reference only (not shipped in APK): APK SHA-256 `488ad6efd04b5a722719e79f8e0dcc2c24afd6758867af3ce41b04839e60c74b`, binary SHA-256 `a477b4c1b332bb9b5d926dbfc75e0efc9b53193708d8be83295bfbb376fa9839` |
| Initramfs init script | Project source | GPL-3.0-or-later | `research/uml-fastboot/initramfs/init`, SHA-256 `7e5dc45ff65e72065b7523468035531c67977a4a55016eacb1356f8bc8460a5b` |
| External-root launcher script | Project source | GPL-3.0-or-later | `research/uml-fastboot/initramfs/um-command.sh`, SHA-256 `761c9368e74ef42c1d302d5ec0b2da9738dc61c077d52786070acbf017668947` |
| Alpine minirootfs baseline | 3.22.2 (aarch64) | Upstream Alpine package licenses (in archive apk database) | `https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.2-aarch64.tar.gz`, SHA-256 `6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489` (3,981,043 bytes; shipped as APK root resource `alpine-minirootfs-3.22.2-aarch64.tar.gz` and verified fail-closed at runtime) |

The provisioner injects `/um-command.sh` (from `research/uml-fastboot/initramfs/um-command.sh`)
into the extracted minirootfs tree; its complete source resides in this
repository.

## 3. umnet / passt Network Helpers (GPL-2.0-or-later)

- Fork / source pin: `passt-android` fork at its `v0.2.0` baseline and `umnet`
  harness, pinned commits `25f9c1f+1a0c657` (`app/build.gradle.kts`,
  `umlNetHelperPins`).
- Project modification: `umnet.c` loopback-only inbound forward patch
  (`-t 127.0.0.1/3632`) for the guest `distccd` compile route; standalone UML
  launches invoke `libuml_passt.so` directly for IPv4 egress, DNS, and explicit
  loopback TCP forwards.
- Build recipe: Android NDK API 30, `-static`, 16 KB page alignment; packaged
  under `lib/arm64-v8a/` with `.so` names per jniLibs convention.
- Shipped binary hashes:
  - `lib/arm64-v8a/libuml_net.so` (`umnet_bionic`):
    `858efb9b9e5ae6ba17207b93079bf7794283c954aa5d95e7ec458fc87d6367ae`
  - `lib/arm64-v8a/libuml_passt.so` (`passt_bionic`):
    `f42171416bc1e0d78029fd16cf3ec3cff53b642a62246b4507c1464fabf6f9f9`

## 4. Host JNI PTY and Control-Pipe Bridge (GPL-3.0-or-later)

- Shipped library: `lib/arm64-v8a/liborchestella_uml_pty.so`
- Source: `app/src/main/cpp/uml_pty_jni.cpp`
  (SHA-256 `b3b979ceda06823761f89bb86085e8cb5ebccb990969051b6c33fb486e339169`)
- Build control: `app/src/main/cpp/CMakeLists.txt`
  (SHA-256 `1677efe8a0e98f2fe45228acef3ab87cfa8116ffaa1802658f4332e4921489cb`),
  linked with `-Wl,-z,max-page-size=16384` (16 KB PT_LOAD aligned).
- License: GPL-3.0-or-later (project root `LICENSE`).

## 5. Corresponding Source Offer and Remaining Pre-Release Closure Items

The complete corresponding source for every GPL work listed above consists of
the pinned upstream kernel, passt-android, and Alpine sources (§1–§3), the
build and configuration scripts in `research/uml-fastboot/` and
`research/uml-kernel-build/`, the JNI bridge source in
`app/src/main/cpp/uml_pty_jni.cpp`, the machine-readable build-input pin record
`app/src/main/assets/UML-BUILD-INPUTS`, `app/src/main/assets/NOTICE.UML`, and
this manifest. Written-offer requests ride `guest-assets/SOURCE-OFFER.txt`.

Remaining human and release-flow closure items (kept honestly open in
`app/src/main/assets/NOTICE.UML` and enforced fail-closed by
`tools/release/check_uml_payload.py --fullpkg --require-release-attribution-closed`):

1. **`linux-fast` cross-path reproducibility variance**: the device-window
   build of `linux-fast` (with embedded initramfs) produced a different digest
   (`572c3433…` vs pinned container-build `21e1c3f9…`, same byte size,
   difference isolated to the embedded initramfs segment). The container-build
   pin governs at runtime until cross-path variance is resolved or formally
   dispositioned.
2. **`stub_exe` (`libuml_stub.so`) final licensing-attribution ruling**:
   currently distributed under root `LICENSE` (GPL-3.0-or-later) as current
   practice; the final attribution verdict is reserved to the project's
   licensing decision record.
3. **One-to-one public release corresponding-source packaging**: publishing the
   immutable public release tag and matching corresponding-source archive is a
   release-flow obligation executed by the named Release Maintainer before any
   public binary release; this document does not substitute for that delivery.

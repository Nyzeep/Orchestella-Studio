# QEMU Android JNI 供应链与 APK 分发策略

> 状态：项目级 active policy（2026-09-07）。适用于 Ticket 26 首次 QEMU/TCG 切片及其后任何
> QEMU 版本、构建输入、APK 打包或 native 依赖闭包更新。
>
> 本策略落实 ADR-0005 的 CI 自建、固定输入、APK 内分发，与 ADR-0014 的 GPL 交付地板；
> 不修改 Full Linux Contract、桥接命令集、暴露面或任何已接受 ADR。

## 1. 目的与边界

QEMU 是 Android APK 内随应用分发的 GPL 二进制，而不是可从应用可写目录下载后执行的工具。
Ticket 26 必须以可审计的 source → Android/NDK build → arm64-v8a APK artifact → installed
nativeLibraryDir execution → GPL delivery 链路交付它。

本策略固定决策输入与失败即停门禁，并不预先声称 Android/NDK build、以 .so 命名的 QEMU main
ELF 执行，或 TCG runtime code generation 已在真机成功。QEMU 官方 supported-build-platform
文档未把 Android 列为受支持平台；这些未知项必须由 Ticket 26 的 CI 和 arm64 真机验证裁决，
不能被静态文档替代。

禁止把 Podroid、Termux 或其他第三方的 QEMU 源码/二进制作为输入；只可采用下列 official
QEMU release source 及本策略规定的受控依赖。

## 2. Ticket 26 初始不可变输入

以下值是 Ticket 26 的初始 source/toolchain pin。它们不是 latest、浮动 branch 或构建机
已有版本；实现开始后必须写入机器可读 lock，CI 必须在使用前重新校验。

| 输入 | 固定值 / 来源 | 校验与说明 |
| --- | --- | --- |
| QEMU component | qemu-system-aarch64 / 11.1.1 | 只构建 aarch64-softmmu；其他 target 必须显式拒绝或另行走更新流程。 |
| QEMU release archive | https://download.qemu.org/qemu-11.1.1.tar.xz | SHA-256：079ffbff8a7111bbc89022107cbabf3bbfd614d5fc9d7cc675991196aca12482。该值于 2026-09-07 从上述官方 archive 的字节流计算；它是 lock 锚，不能替代 CI 对签名的认证。 |
| QEMU signature / signer | https://download.qemu.org/qemu-11.1.1.tar.xz.sig / CEACC9E15534EBABB82D3FA03353C9CEF108B584 | CI fetch gate 必须验证 detached signature 与此完整 fingerprint；hash 与 signature 两者缺一即失败。 |
| QEMU Git identity | tag v11.1.1，commit c3d48b7d1e89604920e5b81b91140c2ad39a1943 | 仅作为 release/source-correspondence 记录；release archive 仍是 build source。 |
| Android NDK | r28c / revision 28.2.13676358 | Linux builder archive：https://dl.google.com/android/repository/android-ndk-r28c-linux.zip；官方 repository metadata SHA-1：a7b54a5de87fecd125a17d54f73c446199e72a64；2026-09-07 对该官方 archive 字节流计算的 SHA-256：dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f。CI 必须再验证两项；不得只使用 SHA-1。 |
| Builder base | mcr.microsoft.com/devcontainers/base@sha256:3612b800eaae8513e7dca54f21d2e4eda48eda57426f3746a78677f342ac7ee9 | linux/amd64 immutable base，与已验收 guest-assets builder input 同源；新增系统包只能来自被版本/快照锁定的输入。 |

QEMU archive 的 release signature、NDK package metadata 和具体 builder image 是三个不同的
provenance 层；任何一个通过都不能替代另两个。实现不得把本表中的 source archive 解压结果、
构建缓存或 binary 直接提交为成品。

## 3. 必须存在的 lock 与可追溯记录

Ticket 26 的首次 QEMU build commit 必须新增由该票拥有的 tools/qemu-android/qemu-jni.lock
（格式可为 JSON、TOML 或 line-oriented text，但不得含浮动字段）和生成的 QEMU-BUILD-INPUTS。
每次 CI build 必须 fail closed 地验证至少：

- schema-version、component-id 与 target-list；
- upstream archive URL、SHA-256、.sig URL、signer fingerprint、release tag/commit；
- ordered patches 的 repo-relative path + SHA-256；empty list 也必须显式存在；
- builder image digest/platform/package snapshot，NDK revision/acquisition identity/SHA-256；
- 全部 build tools、build-phase network policy、SOURCE_DATE_EPOCH、完整 ordered configure/Meson args；
- APK output path/ABI/SHA-256、DT_NEEDED dependency closure、runtime-data closure 及其 source/license；
- APK packaging mode、artifact-manifest schema 和 generated path。

Build phase may not resolve unpinned APT, Meson wrap, Cargo, pip or other network inputs. Any Android
compatibility patch is part of corresponding source: it must be versioned, hashed, documented and included
in the GPL source record. A missing value is a build failure, not a value for the Worker to infer.

## 4. Android ABI、APK 与 W^X 规则

1. Production ABI is only arm64-v8a. The expected QEMU APK entry is
   lib/arm64-v8a/libdroidlayer_qemu.so; every other packaged native dependency must be listed in the closure
   manifest. x86/x86_64 emulator artifacts never satisfy this policy.
2. The implementation must select an explicit, installer-extracted native-library packaging mode and prove it
   in the built APK and installed application. It may not rely on an AGP default. The installed execution path
   comes only from ApplicationInfo.nativeLibraryDir, is canonicalized, and is matched to the artifact manifest
   before execution.
3. QEMU must never be copied to or executed from filesDir, cacheDir, external storage, a download directory,
   or another app-writable directory. This enforces Android target API 29+ execute restrictions and the Ticket
   26 nativeLibraryDir requirement. Launch uses a fixed argument array, never a shell command string.
4. CI must use NDK llvm-readelf on every shipped .so: ELF64, Machine AArch64, expected interpreter where
   dynamically linked, DT_NEEDED closure, and GNU_RELRO. Host glibc loaders/libraries are forbidden.
5. Every shipped arm64 native library must have PT_LOAD alignment of at least 2^14; the final APK must pass
   zipalign -c -P 16 -v 4 before signing, then apksigner verify --verbose --print-certs after signing. NDK r28c
   is selected because it has the Android 16 KB-page default; this still requires artifact-level verification.

The following remain Ticket 26 real-device gates: an executable-main QEMU ELF packaged with a .so name
actually executes from nativeLibraryDir; TCG runtime code generation operates under Android targetSdk 36
W^X/SELinux constraints; and the full dependency closure runs on arm64. A failure is not papered over by
executing from a writable directory.

## 5. Artifact manifest、runtime integrity 与 CI gates

Ticket 26 must create the narrowly owned workflow .github/workflows/qemu-android.yml. Its required jobs
are provenance/lock verification, reproducible native build, ELF/closure and 16 KB inspection, APK layout/
signature and tamper-rejection checks, GPL material verification, and the arm64 device gate. The device job
may remain pending while B4 is blocked, but it must not be marked passed, skipped-as-success, or substituted
with an emulator.

CI must generate a versioned, non-self-referencing runtime-artifacts-v1.sha256 manifest covering:

- QEMU and every APK-bundled native dependency;
- Ticket 25 kernel Image, initramfs and their source mapping;
- QEMU runtime data/firmware/configuration, or an explicit none;
- QEMU-BUILD-INPUTS, QEMU GPL records, notices and GPLv2 text.

Before first QEMU exec, runtime verifies the installed QEMU path and digest against this manifest. Before
copying/extracting kernel or initramfs to private storage, it verifies their expected digest. Any mismatch
rejects startup with a supply-chain error; there is no download/execute fallback.

| Gate | Required evidence |
| --- | --- |
| Provenance | official archive SHA-256 plus detached-signature/fingerprint verification; tag/commit and patches recorded |
| Hermetic input | immutable builder image, NDK identity, locked tool/dependency inputs; no build-phase floating fetch |
| Reproducibility | two clean builds with identical QEMU output, artifact manifest, input record and license/source record |
| ABI / closure | target list, AArch64 ELF, dynamic dependency/data closure and 16 KB checks all pass |
| APK | expected lib/arm64-v8a layout, artifact-manifest match, zipalign and apksigner pass |
| W^X | static review plus installed-path evidence prove execution only from nativeLibraryDir |
| Tamper rejection | altered QEMU/kernel/initramfs/dependency is rejected before startup |
| Device | arm64 boot-to-shell shows no exec, W^X or SELinux denial; it remains subject to Ticket 26 HITL gate |
| GPL delivery | source record, license/NOTICE, complete corresponding-source path and written offer are present and reviewed |

## 6. GPL、NOTICE 与 corresponding source

QEMU is GPLv2. For every APK/release containing its object/executable code, CI must produce and package or
publish alongside the release:

1. GPLv2 text, upstream QEMU LICENSE, and attribution/license records for every bundled dependency;
2. QEMU-GPL-SOURCES.md: source URL, tag/commit, archive hash, signature/fingerprint, patches, configure
   arguments, builder/NDK identity, build entrypoint, shipped binary/data hashes and dependency closure;
3. the ADR-0014 public corresponding-source channel: an approved public repository's fixed release tag plus
   a matching Corresponding Source Release bundle, including scripts/configuration controlling compilation
   and installation. A real written offer remains available as a fallback, but a generic publisher/support
   reference cannot replace the public tag and bundle. The channel and recoverable retention record remain
   available for at least three years after the last applicable object-code distribution;
4. a release gate that confirms these materials are actually present, not merely referenced by a stale
   Ticket 25 record.

guest-assets/GPL-SOURCES.md and SOURCE-OFFER.txt are examples only; they do not cover QEMU and may not be
relabeled as QEMU evidence. Legal interpretation of unusual static/LGPL linkage remains subject to appropriate
compliance review; this policy sets the engineering delivery floor.

## 7. Update and escalation discipline

Any change to QEMU archive/tag/commit/signature, patch, builder, NDK, build arguments, dependency/data
closure, ELF layout, packaging mode/path, output digest or license classification creates a new component
identity. The lock, input record, source offer and notices must change atomically and every section 5 gate
reruns. Automatic upstream following or URL-only updates are prohibited.

An ordinary failed Android build may be addressed within Ticket 26 by a recorded configuration or compatibility
fix. Stop and use ARCHITECTURE_CHANGE_POLICY.md if a proposed workaround requires Root, execution from a
writable directory, a new guest-to-host exposure, a change to the policy-neutral contract, or abandonment of
APK-contained QEMU. Ticket 35 may harden the full-product SBOM/CVE/release process, but may not defer this
first-source-pin, ABI/W^X or GPL delivery floor.

References: QEMU Download, QEMU license, Android execute restriction, Android ABIs, Android 16 KB page sizes,
and GPLv2 section 3. The formal source URLs are recorded in
docs/research/2026-09-07-qemu-android-jni-supply-chain.md.

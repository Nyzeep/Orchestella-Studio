# Ticket 26：QEMU Android JNI 供应链与 APK 分发最小契约

> 日期：2026-09-07（Asia/Shanghai）
> 原始 child scope：Ticket 26 readiness blocker B3 的只读研究；research child 不选择、下载、
> 构建或分发 QEMU，也不修改 ADR/spec/tracker。其后 Orchestrator addendum 的最小 lock 选择和
> active policy 变更在本文末单独标明，不能倒写成 child 的操作。
> 执行者：qemu_supply_chain_research child（GPT-5.6 Terra / max）；Orchestrator addendum
> 的当前会话 model/reasoning 未向报告接口公开。
> 证据访问日期：除另注外均为 2026-09-07。

## 结论

**B3 分类：RESOLVED（作为派工输入/验证合同问题）**。现有已批准的
`docs/adr/0005-guest-kernel-contract.md` 与 `docs/adr/0014-licensing.md` 已经决定了
"CI 自建、pin、校验、APK 内分发、对应源码与书面 offer"的方向；把这些约束具体化为
QEMU 的锁文件、构建输入、APK 清单和门禁，**不需要**重开 ADR，也不需要外部主体批准。

这不等于 QEMU Android 端口已被证明可用。QEMU 官方的受支持构建宿主页不列 Android，且明确说
未列平台“不代表不能工作”；因此 Android/NDK 编译、从安装后的 native library directory 执行，
以及 TCG 实际启动，都必须是 Ticket 26 的 fail-closed 验证门，而不是未经证实的前提。若这些
门失败而修复方案会改变无 Root、APK 内置执行物或策略中性契约，则按
`docs/agent-handoff/ARCHITECTURE_CHANGE_POLICY.md` 停止并提 ACR。

本 research child 完成时，仓库尚未选择具体 QEMU/NDK lock，因而建议保持 needs-triage。
随后 Orchestrator 已以本报告的一手来源为输入，固定 initial pin、scope contract 与 active
policy；该治理缺口现已解决。Ticket 26 当前保持 blocked 的唯一 readiness blocker 是 B4
真机/HITL 条件，不是 QEMU source/version 的临场选择。

## 已核实的输入与边界

### 仓库事实

- Ticket 26 要求 QEMU “以 jniLib 形态打包、`nativeLibraryDir` 执行，规避 W^X”；当前
  `.scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md` 未给出 QEMU
  source/version/checksum、NDK 构建入口、APK artifact manifest 或许可证输出。
- `app/build.gradle.kts` 当前已固定 `minSdk = 26`、`targetSdk = 36` 和
  `abiFilters += "arm64-v8a"`，但没有 `externalNativeBuild`、`jniLibs` source set、
  `packaging.jniLibs` 或 QEMU 依赖声明；`app/src/main/AndroidManifest.xml` 也没有 native
  packaging 的显式策略。因此现有 skeleton 不能被误报为已完成 QEMU 打包。
- Ticket 25 提供可复用的供应链形状，而非 QEMU 产物：
  `guest-assets/inputs/inputs.lock`（版本/URL/SHA256）、`guest-assets/build.sh`（受控下载与
  `BUILD-INPUTS`）、`guest-assets/scripts/check-reproducible.sh`（两次干净构建逐字节比较）、
  `guest-assets/GPL-SOURCES.md` 与 `guest-assets/SOURCE-OFFER.txt`（对应源码与 offer）。
  `guest-assets/out/`、`guest-assets/.work/` 在 `.gitignore` 中，不能作为 APK 分发输入或提交物。
- ADR-0005 要求 MVP 将 kernel/initramfs 打入 APK 并在解压时 SHA256 自检；ADR-0014 要求
  对随 App 分发的 QEMU/GPL 二进制由 CI 生成精确版本、完整构建脚本、LICENSE、第三方许可页和
  书面源码 offer。本文将同一地板扩展到 QEMU，不改变它们。

### 外部一手事实

| 事实 | 一手来源与含义 |
| --- | --- |
| 官方 QEMU release tarball 有 detached signature；下载页给出 release-manager 公钥指纹 `CEACC9E15534EBABB82D3FA03353C9CEF108B584`，也说明 stable branch 命名为 `stable-X.YY`。 | [QEMU Download](https://www.qemu.org/download/)；可用作 source provenance 的双锚（release archive SHA-256 + 签名/指纹），但本研究**没有**下载或验证任一 archive。 |
| QEMU 整体为 GPLv2；部分文件有各自、GPLv2-compatible 的许可证，应看 source 中的 `LICENSE` 和 file header。 | [QEMU License](https://www.qemu.org/docs/master/about/license.html) 与 [upstream LICENSE](https://gitlab.com/qemu-project/qemu/-/raw/master/LICENSE)。不能把“QEMU = 单一 GPL 行”替代依赖/文件级 notice 审计。 |
| QEMU 的官方 supported-build-platform 文档列出 Linux/macOS/FreeBSD/NetBSD/OpenBSD/Windows；Android 未列。该页同时明确：未列平台不意味着一定不能工作。 | [Supported build platforms](https://www.qemu.org/docs/master/about/build-platforms.html)。故 Android/NDK 路径是待验证实现风险，不能声称为 upstream-supported。 |
| 对 target API 29+ 的不可信 App，Android 禁止对可写 app home 目录文件直接 `execve()`；官方理由正是 W^X，并说此类 App 应只加载嵌入 APK 的二进制。也禁止将已 `dlopen()` 文件的可执行代码修改写回磁盘。 | [Android 10 behavior changes — execute permission](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)。本项目 `targetSdk=36`，所以不得把 QEMU 拷贝到 `filesDir`/`cacheDir` 后执行。 |
| Android 的 NDK ABI 文档规定 native library APK entry 为 `/lib/<abi>/lib<name>.so`；Package Manager 找到 `lib/<primary-abi>/lib<name>.so` 后会复制到应用的 `<nativeLibraryDir>`。`arm64-v8a` 是 AArch64 ABI，Android native executable/shared libraries 都是 ELF。 | [Android ABIs](https://developer.android.com/ndk/guides/abis#native-code-in-app-packages)。它支持“APK 内 `lib/arm64-v8a/` + 安装后的 nativeLibraryDir”这条路径；它**没有**承诺任意 main-style ELF 改名为 `.so` 后一定可由 `execve` 成功，故仍须真机门禁。 |
| 对 AGP 8.13，`packaging.jniLibs` 是 `.so` packaging DSL；`useLegacyPackaging` 意为压缩所有 `.so`，值为 `null` 时 `minSdk >= 23` 的 `.so` 默认未压缩且 page-aligned。manifest 文档说明 `extractNativeLibs=false` 时库留在 APK 并直接加载，`true` 时 installer 提取。 | [AGP 8.13 JniLibsPackaging](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/JniLibsPackaging)；[application / extractNativeLibs](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs)。不能依赖“默认值碰巧给出可执行路径”；需选择并检验安装后提取策略。 |
| 16 KB page-size 设备从 Android 15 起存在；含 NDK 库的 App 应重建。官方给出 `llvm-readelf` ELF LOAD alignment、`zipalign -c -P 16 -v 4` APK 检查；NDK r28+ 默认生成 16 KB ELF 对齐，r27 及更低版本需 linker flags。 | [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)。当前 `gradle/libs.versions.toml` 的 AGP 8.13.2 已高于官方所述 8.5.1 packaging 地板，但 QEMU/所有 prebuilt 仍要验证。 |
| GPLv2 §3 对 object/executable distribution 要求三选一；§3(a) 是随附完整对应源码，§3(b) 是有效至少三年的书面 offer。它把 complete source 定义为所有模块源码、关联 interface definition files、以及控制编译和安装的脚本（含 OS major-component 例外）。 | [GNU GPL v2 §3](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html#section3)。ADR-0014 已选择“CI 清单 + LICENSE + 书面 offer”的项目流程；这不是仅给一个 upstream URL 就完成。 |
| `apksigner verify` 用于确认 APK 签名在其支持 Android 版本上可成功验证；对 APK 做 zipalign 必须在签名之前。 | [apksigner](https://developer.android.com/tools/apksigner)。APK 签名与 component SHA-256 是不同层：前者锚定整包，后者让 CI/运行时能定位具体 QEMU 输入与输出。 |

## 最小可审计供应链合同（Ticket 26 dispatch 必填）

以下是**实施建议**，不是已发生的构建事实。其 initial 实值已随后固定在
docs/agent-handoff/QEMU_JNI_SUPPLY_CHAIN_POLICY.md；Worker 开始前，scope contract 必须为每个
`<...>` 写入实值，禁止 `latest`、浮动 branch、未哈希 URL 或“按构建机已有版本”。建议将
QEMU pipeline 放入一个新的、明确由 26 所有的目录（例如 `tools/qemu-android/`；实际路径须在
dispatch 固定），并同时明确它对 `app/**` 的有限接线所有权。不得使用 Podroid、Termux 或任意
第三方预编译 QEMU binary。

```text
component.id              = qemu-system-aarch64
upstream.archive.url      = https://download.qemu.org/qemu-<X.Y.Z>.tar.xz
upstream.archive.sha256   = <64 lower-case hex>
upstream.signature.url    = ...tar.xz.sig
upstream.signer.fpr       = CEACC9E15534EBABB82D3FA03353C9CEF108B584
upstream.release.tag      = v<X.Y.Z>
upstream.commit           = <immutable commit that matches the selected release>
source-tree.sha256        = <defined normalized-tree hash, or an explicit reproducible method>
patches                   = <ordered repo-relative patch path + SHA256; empty is explicit>

builder.image             = <OCI image digest>
builder.platform          = <platform>
ndk.version/archive.sha256= <exact revision and verified acquisition identity>
other build tools         = <meson/ninja/python/rust/etc. exact locks or image digest>
build.network             = <fetch phase only; build phase has no unpinned fetch>
source_date_epoch         = <integer>
configure/meson args      = <complete ordered argument list>
enabled QEMU target       = aarch64-softmmu
enabled runtime features  = <only ticket-26-required TCG/virt/block/console set>
disabled features         = <explicitly recorded non-required UI/network/accelerator features>

native output             = lib/arm64-v8a/libdroidlayer_qemu.so
output.sha256             = <64 lower-case hex>
dynamic dependency closure= <each DT_NEEDED library, APK location, SHA256, source/license record>
runtime data closure      = <each QEMU data/firmware/config file or explicit “none”, SHA256>
apk packaging mode        = installer-extracted native library (not an implicit default)
apk artifact manifest     = <path + schema/version + generated SHA256 list, manifest excluded from self-hash>
```

### 1. Provenance、pin 与可复现性

1. **只接受 official QEMU release source archive。**CI fetch phase 必须同时验证 archive SHA-256
   和 detached PGP signature，且把 signer fingerprint、release tag、commit、access time 写进
   generated `QEMU-BUILD-INPUTS`。archive SHA-256 不是签名的替代；签名也不是 lock 的替代。
   选中某个维护分支或 master 是不合格输入。
2. **完整依赖闭包锁定。**QEMU 的 source、patch、build image digest、NDK、host tools、QEMU
   configure/Meson arguments、所有随 APK 交付的 native `.so` 与 data files 必须可追溯。
   任何 `pip`、Cargo、Meson wrap、APT 或网络下载只能出现在受 pin/hash/snapshot 约束的 fetch
   阶段；实际 build 不得静默向网络解析新版本。此原则与
   `guest-assets/inputs/inputs.lock` 和 `guest-assets/run-in-builder.sh` 的已验收做法一致。
3. **补丁即源码的一部分。**对 upstream source 的 Android/Bionic 兼容 patch、build wrapper、
   launcher 及配置生成器必须在仓库、逐个 SHA-256、带说明，并进入 GPL corresponding-source
   bundle。不得只记录“在 CI 中改过”。
4. **可复现目标须分层。**至少两次干净、同锁定输入的 build 要逐字节比较 QEMU native output、
   runtime artifact manifest、`QEMU-BUILD-INPUTS`、license/source records；失败即失败。签名后的
   release APK 另输出 SHA-256、版本、签名证书 digest 和内容 manifest；不要在未验证 signing
   工具确定性前承诺 byte-identical signed APK。

### 2. Android ABI、APK 与 W^X 合同

1. **唯一 production ABI：`arm64-v8a`。**APK 必须仅含预期的
   `lib/arm64-v8a/libdroidlayer_qemu.so`（以及已列入 closure 的 arm64 `.so`）。不能以
   x86/x86_64 simulator binary 伪代真实验收；这与 ADR-0004 和现有 Gradle `abiFilters` 一致。
2. **安装后 native directory 是唯一 exec source。**运行时路径应从
   `ApplicationInfo.nativeLibraryDir` 取得、canonicalize，并只执行该目录中 manifest 对应的
   QEMU 文件。不得从 `filesDir`、`cacheDir`、外部存储或网络下载目录执行；不得先复制 native
   binary 到可写目录。argv 应为非 shell 的固定参数数组，避免 shell 解释引入新的攻击面。
3. **显式选择 installer extraction，而非依赖 AGP 默认。**当前 AGP/minSdk 的默认倾向是
   未压缩/page-aligned `.so`；这适合直接 load from APK，却不自动证明可作为独立 QEMU process
   的 filesystem executable。dispatch 应要求实施者选择可审计的提取配置（现有 DSL 下
   `packaging { jniLibs { useLegacyPackaging = true } }` 是待验证的候选，因为它选择压缩 `.so`；
   仍需验证最终 manifest 和安装结果），然后确认该文件实际位于 `nativeLibraryDir`。如果选用
   另一种配置，也必须以同样的 APK + 真机证据证明，不能只凭配置名声称成功。
4. **ELF/依赖 closure 检查。**CI 对每个交付 `.so` 以 NDK `llvm-readelf` 检查：ELF64、
   `Machine: AArch64`、预期 `PT_INTERP`（若动态链接）、所有 `DT_NEEDED` 都在 Android system
   ABI 或受控 APK closure 中，并记录 `GNU_RELRO`。禁止把 host glibc loader/host `.so` 混入。
   静态/动态链接并非本报告替 Worker 选择；每一项的选择、许可证和 source obligation 必须在
   closure manifest 中明确。
5. **16 KB 静态门从 26 开始。**所有交付 arm64 `.so` 的 `LOAD` segment 对齐至少为
   `2**14`，APK 通过 `zipalign -c -P 16 -v 4 <apk>`。若 NDK 低于 r28，完整 linker flags
   必须被锁定和记录；不能只检查 QEMU 主文件而遗漏依赖。
6. **两项关键待实测不得伪装为文档事实。**(a) main-style QEMU ELF 被以 `.so` 名称放入
   installer-extracted directory 后，能否在目标设备上 `execve`；(b) QEMU TCG 的 runtime code
   generation 是否在 targetSdk 36 的 W^X/SELinux 环境下正常工作。二者均以实际 arm64 boot-to-
   shell gate 裁决；“QEMU-as-jniLib”是项目选定路线，不是本研究已经证明的 Android 平台保证。

### 3. APK 内 artifact manifest 与运行时自检

生成一个**不自引用**的 versioned manifest（例如 `runtime-artifacts-v1.sha256`），至少列出：

- QEMU 主 native artifact 和所有 APK-bundled dynamic native dependencies；
- kernel `Image`、`initramfs.cpio.gz` 及其与 Ticket 25 `SHA256SUMS` 的来源映射；
- QEMU 必需 runtime data files；
- `QEMU-BUILD-INPUTS`、QEMU GPL source/notice records 和完整 GPLv2 text。

CI 在签名 APK 上解包、比对 manifest、确认每项 APK path/ABI/sha256；运行时在第一次 exec
QEMU 前校验安装后的 native file 的 SHA-256，并在 kernel/initramfs 被复制或解压到私有存储前
校验其 expected digest。任一 mismatch 必须拒绝启动并给出不可执行的供应链错误，绝不回退为
从 writable directory 下载/执行。APK 最后还必须通过：

```text
zipalign -c -P 16 -v 4 <release.apk>
apksigner verify --verbose --print-certs <release.apk>
```

这让 APK 签名、内置 manifest 和 installed native file 三个层次都可审计。真正的 checksum 值
由 CI 生成并随 release artifact 保存，不能手填在源码中再假定它仍对应构建输出。

### 4. GPL、NOTICE 与 corresponding-source 地板

每个包含 QEMU object/executable 的 APK/release 必须至少有以下 release gate：

1. `GPL-2.0` 全文、QEMU `LICENSE`、各 bundled dependency 的 license/attribution、以及 App
   内可访问的 Third-party Licenses 页面；不能把 Apache-2.0 host code 与 QEMU 的 GPL source
   record 混成一个许可证结论。
2. 生成 `QEMU-GPL-SOURCES.md`：upstream URL、release tag/commit、archive SHA-256、PGP key
   fingerprint/signature URL、全部 patch 的路径/哈希、每个 shipped binary/data 的哈希、最终
   configure/Meson 选项、builder/NDK identity、完整 build entrypoint、dependency closure 及各自
   source/license 位置。
3. 随二进制提供项目 ADR-0014 所要求的书面 offer，明示任何第三方可获得 complete
   corresponding source、包括控制 compilation/installation 的 scripts/configuration，且在最后
   一次 applicable object-code distribution 后至少三年有效。发布前需有真实的公开 source/支持
   渠道；若该渠道尚未选定，release gate 必须失败，而不是留下未填占位符。
4. release CI 校验 QEMU source record/offer/许可证文本全部真的进入最终 APK 和 release
   companion artifacts，并对 license diff 进行人工审阅。GPL/LGPL 边界、静态链接或特殊例外的
   最终法律判断应由合规负责人复核；本报告只陈述 GPLv2 文本和项目已批准的交付地板，不替代
   法律意见。

现有 `guest-assets/GPL-SOURCES.md` 与 `guest-assets/SOURCE-OFFER.txt` 可作为格式参照，但它们
只覆盖 Ticket 25 的 kernel/BusyBox/e2fsprogs/musl，不能被重命名后冒充 QEMU 对应源码记录。

## CI / 验收门禁清单

| 门禁 | 通过证据 | Ticket 26 中是否必需 |
| --- | --- | --- |
| Source provenance | 官方 release URL、SHA-256、`.sig` 通过、固定 fingerprint、tag+commit、patch hash | 是，build 前 |
| Hermetic input | builder digest、NDK/tool pin、无浮动网络解析的 build log/lock diff | 是 |
| Reproducibility | 两次干净 build 的 QEMU outputs、manifest、license records 逐字节相同 | 是 |
| Feature/closure | `aarch64-softmmu`、完整 configure args、`DT_NEEDED`/runtime data/许可证闭包无遗漏 | 是 |
| ABI/ELF | 每个交付 `.so` 为 AArch64 ELF64；dynamic loader 与 Android 兼容；RELRO、16 KB LOAD alignment | 是 |
| APK layout/signature | 仅预期 `lib/arm64-v8a/**`、manifest hash match、`zipalign` 与 `apksigner verify` 通过 | 是 |
| W^X path | code review/static test 证明不从 writable directory 执行；运行时 canonical path = `nativeLibraryDir` | 是 |
| Tamper rejection | 修改 QEMU/kernel/initramfs/dependency 任一文件时 hash mismatch 拒绝启动 | 是 |
| arm64 device smoke | 安装后 QEMU `--version`/受控启动、TCG boot、Alpine provisioning、root shell；记录无 exec/W^X/SELinux 拒绝 | 是；也受 B4 真机准备阻塞 |
| GPL delivery | APK/release 内 license、source manifest、offer 完整且审阅通过 | 是，任何可分发 build |
| 组件升级 | source/patch/config/NDK/dependency/output 任何变化均生成新 identity，重跑上述全部门禁 | 是，写入 update policy |

`app` 目前不存在上述设备路径或 APK；本研究没有运行 Gradle、NDK、QEMU、ADB 或真机测试。

## 更新再验证规则

QEMU 版本升级不是普通 dependency refresh。以下任一个变化都必须创建新的 component identity，
更新 manifest/source offer/NOTICE，并重新执行全表门禁：upstream archive/tag/commit/signature、
patch、configure/Meson args、builder image、NDK/host tool、静态或动态依赖、runtime data、ELF
layout、APK packaging mode/path、artifact checksum、license classification。原子地审阅 lock +
binary manifest + license/source diff；任一缺失就不能发布。自动跟随 upstream、仅更新 URL、或复用
旧 `QEMU-GPL-SOURCES.md` 均不合格。

## Ticket 26 与 Ticket 35 的边界

| 归属 | 不可延期的内容 |
| --- | --- |
| **Ticket 26** | 选定并锁定一个 official QEMU source release；Android/NDK build entry、patch/dependency closure；`arm64-v8a` JNI/APK layout；installer-extracted `nativeLibraryDir` exec 的 W^X 约束；component hash manifest + runtime rejection；最小 GPL/NOTICE/source-offer 输出；两次 build、ELF/zip/signature、真实 arm64 boot-to-shell 证据；上述升级再验证规则。没有它们就无法声称第一 QEMU 垂直切片可审计。 |
| **Ticket 35** | 在 26–37 全部合入后进行全产品 SBOM/许可证总账和 UI/发布物走查、所有组件的 CVE/安全硬化政策、更多 OEM/Android/16 KB 真机矩阵、性能/大小/符号/攻击面深度扫描、公开 release source 服务可用性与长期 offer 演练、全量回归。它不能替 26 补第一次 QEMU source pin、GPL record、ABI、W^X 或 boot gate。 |

## 对 scope / matrix / ticket wording 的影响

1. **需要 scope-contract/dispatch packet 补充，不能让 Worker 自行决定。**新增一个明确的
   `QEMU Android supply-chain` in-scope 区域：实际所有路径、一个固定 QEMU release、所有锁定字段、
   permitted build dependencies、APK artifact/许可证输出和上述门禁。`app/**` 接线与新 pipeline
   目录应在 ownership 表中列出；禁止范围需包括 `guest-assets/out/`、`guest-assets/.work/`、
   Podroid/Termux/prebuilt binaries、`engine-avf` 和任何未列出的 host/system 修改。
2. **现有 matrix 应补一行或在 Ticket 26 的 owned modules 注明该新 pipeline。**
   `docs/agent-handoff/PARALLELIZATION_MATRIX.md` 现写 `engine-qemu`，却没有 QEMU 的 build/
   license/package 目录；这是 ownership 可审计性缺口，不是产品架构变更。该增补与 B1 的
   `guest-assets` 下游例外分开处理，本文不扩大 Ticket 25。
3. **Ticket 文案产品意图无需改写，验收需可执行化。**保留现有“jniLib + nativeLibraryDir”目标，
   但应补充“无 hash/provenance/license/ABI/W^X gate 则不得启动或验收”的验收条目，以及上述
   真机记录格式。`Blocked by: 25` 的裸字段修正和 26/28 lifecycle 切分仍分别属于 audit B1/B2，
   本报告不替它们裁决。
4. **无需 ACR。**本建议没有增加 guest→host 暴露面、没有改变桥协议命令、没有改变 APK-first
   kernel 决策，也没有把 QEMU 特性泄漏进 Full Linux 契约；它只是执行 ADR-0005/0014 已有的
   供应链与分发纪律。

## 未证实项与停止条件

- 本报告未验证某个具体 QEMU version 在 Android NDK/Bionic 上能编译，未验证 dynamic dependency
  closure，未验证 `.so` 名称的 QEMU main ELF 可执行，未验证 TCG JIT 与 targetSdk 36 的 W^X
  行为，也未验证 16 KB 真机。它们已被转换成 Ticket 26 的可复现、失败即停的门禁。
- 如果这些验证仅需要修复 pin、build script、QEMU configuration 或 Android-compatible dependency，
  仍属 Ticket 26 implementation；如果需要从 APK/nativeLibraryDir 路径退回 writable executable、
  引入 Root/特权、改变隔离/暴露面，或使策略中性契约无法满足，则停止扩大实现并走
  `ARCHITECTURE_CHANGE_POLICY.md`。

## Orchestrator addendum：initial lock selection

本节在 research child 交回报告后由 Orchestrator 于 2026-09-07 新增，用于把“需要具体值”
转化为受审计的 active policy；它不声称 QEMU 已成功在 Android 上构建或运行。

Orchestrator 为记录 initial archive identity，只读流式读取两个 official archive 的字节以计算
SHA-256；未将 archive 写入工作区、未解压、未构建、未执行，也未验证 QEMU detached signature。
后者仍是 future CI 的必经 gate。

- QEMU source：official qemu-11.1.1.tar.xz，SHA-256
  079ffbff8a7111bbc89022107cbabf3bbfd614d5fc9d7cc675991196aca12482；
  detached signature URL 为同名 .sig，release-manager fingerprint 为
  CEACC9E15534EBABB82D3FA03353C9CEF108B584；Git identity 为 tag v11.1.1 /
  c3d48b7d1e89604920e5b81b91140c2ad39a1943。
- NDK input：r28c / 28.2.13676358，official Linux archive
  android-ndk-r28c-linux.zip；official repository metadata SHA-1 为
  a7b54a5de87fecd125a17d54f73c446199e72a64，Orchestrator 对该 official archive 字节流
  计算的 SHA-256 为
  dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f。
- 选择和完整门禁见 QEMU_JNI_SUPPLY_CHAIN_POLICY.md。future CI 必须重新验证 archive
  checksum 与 QEMU detached signature，且仍必须通过 Android build、ELF/closure、APK、
  W^X、TCG、GPL 和真机 gates；本 addendum 不是这些验证的替代。

## 执行记录与治理边界

- 背景 / scope：为 Ticket 26 readiness 的 B3 建立最小、可审计的 QEMU Android JNI
  供应链输入与验证合同；研究 child 的原始范围为只读，Orchestrator addendum 只将已核验的
  source/toolchain 身份固化为后续 policy 输入。
- 实际完成：核验项目内缺口及 Android、QEMU、GNU 官方一手资料；形成 source、ABI、APK、
  W^X、可复现性、runtime integrity 与 GPL 的失败即停门禁，并记录 initial pin 的来源。
- 未完成：未下载到工作区、解压、构建、签名、安装或运行 QEMU；未验证 detached signature、
  Android/NDK build、main ELF 执行、TCG、dependency closure 或真机行为。
- 修改文件：本研究报告；以它为证据的治理文件、scope contract 与 ticket wording 由
  Orchestrator 在独立的 blocker-resolution 变更中记录。
- 测试与验证：只读检查仓库文本/工具链盘点并核验官方来源；Orchestrator addendum 对两个
  official archive 作未落盘的流式 SHA-256 计算。没有产品测试或外部 runtime 操作。
- branch / 基线 / commit：main / 83309ec / 同批治理提交待创建；不以待创建 commit 冒充已提交。
- 越界与 Architecture Change Policy：未修改 ADR、spec、CONTEXT、tracker 或产品代码；未扩大
  暴露面。现有 ADR 足以约束本合同，未触发 ACR。
- 风险与后续动作：所有 Android/TCG 可运行性仍须由未来 Ticket 26 的 fail-closed CI 与 arm64
  真机门裁决；若 workaround 要求 Root、可写目录执行、契约泄漏或放弃 APK 内分发，必须停下并
  走 Architecture Change Policy。

报告人：Codex research child + Orchestrator addendum ｜ 日期：2026-09-07 ｜ 耗时：未单独计时

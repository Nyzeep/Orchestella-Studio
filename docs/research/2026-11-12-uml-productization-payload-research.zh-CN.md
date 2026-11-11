# UML payload 产品化调研（ACR 双方案对比素材：内核/rootfs 内置 vs 下载）

- 日期：2026-11-12
- 性质：联网调研报告；不改产品代码、不合并 main、不改版本号/签名/发布通道
- 分支：`feat/uml-research-r2`（基于 `8fa035e2`）
- 输入：仓库既有合规模式（`app/src/main/assets/NOTICE.QEMU`、`QEMU-GPL-SOURCES.md`、`LICENSE.GPL-2.0`、`guest-assets/SOURCE-OFFER.txt`、`guest-assets/GPL-SOURCES.md`）、`research/uml-fastboot/`（ARM64 UML 内核产物与 pin）、`research/uml-rootfs-prebake/`（verify.sh 校验链）、`runtime/network`（ResilientDownloadEngine 管线）、`app/.../DistroMirrorCatalog.kt`（镜像目录）、`docs/adr/0014-licensing.md`
- 来源分级：一手 = 仓库源码实读（GitHub raw/API）或官方许可文本/协议原文；二手 = 官方页面本窗不可达、经搜索摘要转述（逐条标注）

## 0. 结论一览

| 问题 | 结论 | 关键依据 |
| --- | --- | --- |
| 同业主流模式 | 三种并存：**内置最小环境**（Termux bootstrap 嵌入 native 库、Podroid v1.3.0 内置 UML 内核）、**运行时下载**（UserLAnd per-distro GitHub Releases、proot-distro per-distro TARBALL_URL+SHA256）、**无 asset pack 依赖**——没有一家用 Play Asset Delivery 分发 Linux payload | §1.1–§1.4 |
| >100MB asset 在 Play 的现实路径 | 内置走 **install-time asset pack**（基础模块 ≤150MB 口径为二手转述），下载则完全绕开 Play 体积限制；F-Droid 只有纯 APK、无 AAB/PAD（fdroiddata 一手证实 UserLAnd 以普通 gradle APK 构建） | §1.5 |
| 「更新一个内核版本」的操作面 | 内置模式 = 发新版 APK（Termux 模式）；下载模式 = 更新 manifest/Release 不动 APK（UserLAnd 改 Releases、proot-distro 改插件）；asset pack 更新走 Play 发布但用户无 app 更新弹窗 | §1.6 |
| GPL-2.0 内核随 APK 的源码交付 | 三种合规形态（GPLv2 §3a 随包 / §3b 书面要约 / §3c 转交要约）；本仓库自建 fork（out-of-tree 配置）必走 **§3a 公开固定 tag + Release 源码包**，与 ADR-0014 现行流程一致；补丁与最终 .config 属"控制编译/安装的脚本"必须交付 | §2.1–§2.3 |
| UML 版 NOTICE 需补条目 | 10 条清单：内核 fork pin、defconfig-fast fragment、构建入口/环境、stub_exe 许可归属、busybox/initramfs、方案 B 下载件同等义务、written offer、runtime-artifacts manifest 扩展等 | §2.4 |
| 下载版 rootfs 校验 | 推荐三层管线：L1 传输完整性（Content-Length + RFC 7233 If-Range 续传 + `.part` 原子 rename）→ L2 官方签名/固化 pin（Alpine tarball 自带 `.asc`，ncopa 指纹）→ L3 内容自检（复用 verify.sh 的 manifest/tree digest/首启清洁检查）；既有 `ResilientDownloadEngine` 已覆盖 L1 与 fail-closed | §3.1–§3.4 |
| ACR 双方案关键差异 | 首启延迟（0 vs 分钟级下载）、体积合规（150MB 门槛 vs 无门槛）、更新路径（发 APK vs 热更新 manifest）、失败模式（签名保证 vs 网络依赖）、GPL 交付时点（发布前必须就绪 vs 可后补但要约先入包） | §4 |

## 1. 任务一：内核/VM 资产随 APK 分发的同业实践与升级路径

### 1.1 UserLAnd（CypherpunkArmory）——纯下载模式，无哈希校验

实读仓库（`old-master` 分支 app 源码；`master` 分支现为品牌壳仓库，主源码在 `old-master`）：

- **分发结构**：每个 distro 一个独立仓库 `CypherpunkArmory/UserLAnd-Assets-<distro>`（如 `UserLAnd-Assets-debian`），GitHub Releases `latest` tag；每个 release 内含按架构命名的清单 `<arch>-assets.txt` 与资产 `<arch>-assets.tar.gz`、`<arch>-rootfs.tar.gz`。`GithubApiClient.kt` 从 `https://api.github.com/repos/CypherpunkArmory/UserLAnd-Assets-$repo/releases/latest` 解析 `browser_download_url`，URL 全部来自 API 响应而非本地构造。
  来源（一手）：https://github.com/CypherpunkArmory/UserLAnd/blob/old-master/app/src/main/java/tech/ula/model/remote/GithubApiClient.kt
- **下载与校验**：`AssetDownloader.kt` 只包一层 Android `DownloadManager`；**全文无任何 sha256/md5/digest 校验**——`STATUS_SUCCESSFUL` 即信任并解包。完整性完全依赖 HTTPS + GitHub 平台。失败原因码映射（`cannot_resume`/`http_error`）后取消全部排队下载。
  来源（一手）：https://github.com/CypherpunkArmory/UserLAnd/blob/old-master/app/src/main/java/tech/ula/utils/AssetDownloader.kt
- **版本判定与离线兜底**：`AssetRepository.kt` 用本地缓存版本 >= 远端 release tag 判定"已最新"；网络异常时 rootfs 已存在则**默认视为最新**（保证离线可用）；`rootfs.tar.gz.part` 残件在下次成功后清理；资产落盘 `filesDir/<repo>/`（scoped storage `Android/data/tech.ula/files/`）。
  来源（一手）：https://github.com/CypherpunkArmory/UserLAnd/blob/old-master/app/src/main/java/tech/ula/model/repositories/AssetRepository.kt
- **F-Droid 侧**：fdroiddata 的 `metadata/tech.ula.yml`（一手：https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/tech.ula.yml）显示全部版本以普通 `gradle` APK 构建——无 asset pack / AAB；rootfs 依然走运行时下载。
- **内核（proot）本身内置 APK**：`UserLAnd-Assets-Support` 仓库只承担"全部 distro 共用的 proot/工具链"构建（MIT），rootfs 不在其中。
  来源（一手）：https://github.com/CypherpunkArmory/UserLAnd-Assets-Support

对 Orchestella 的含义：UserLAnd 的"下载 + 不校验"是本仓库方案 B 要**超越**的基线——`ResilientDownloadEngine` 的 sha256 fail-closed + `DistroMirrorCatalog` 的"候选必须逐字节一致"规则已经优于该基线。

### 1.2 Termux——内置模式（bootstrap 嵌入 native 库），热更新走包仓库

实读仓库：

- **bootstrap 打包**：`scripts/generate-bootstraps.sh`（一手：https://github.com/termux/termux-packages/blob/master/scripts/generate-bootstraps.sh）把最小 `$TERMUX_PREFIX`（bash、apt、coreutils 等预装包）打成 `bootstrap-<arch>.zip`；符号链接不进 zip，改写为 `SYMLINKS.txt`（`<target>←<link>` 行）；md5 校验只在包内生成 `*.md5sums`（apt 生态文件），zip 本身无独立校验。
- **随 APK 分发方式**：bootstrap 不走 assets 目录，而是**编译进 native 库** `libtermux-bootstrap.so`（`app/src/main/cpp/termux-bootstrap-zip.S` 汇编嵌入 + `System.loadLibrary("termux-bootstrap")` 后 `native byte[] getZip()`），安装时经 staging 目录逐条解包、`chmod 0700`、建符号链接后 `renameTo($PREFIX)`。zip 完整性由 APK 签名链保证，代码内无哈希校验。
  来源（一手）：https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java
- **变体选择**：`TermuxBootstrap.java` 从 BuildConfig 读 `TERMUX_PACKAGE_VARIANT`（`apt-android-7`/`apt-android-5`）决定打包哪份 bootstrap。
  来源（一手）：https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxBootstrap.java
- **升级路径**：bootstrap 内容更新 = 发新版 APK；环境内容更新走 apt 仓库（不改 APK）。bootstrap zip 也按日期 tag 单独发布到镜像（如 `bootstrap-2026.11.01-r1+apt.android-7/bootstrap-arm.zip`，SourceForge 镜像）供离线安装。
- **两段式启示**：内置件刻意"最小"（占位几十 MB 内），真正的大内容用包管理器增量拉取——这是"内置快路径 + 内容热更新"分层的产品化先例。

### 1.3 proot-distro（Termux 生态）——下载 + pin sha256 + fail-closed

- **bash 版（v3.3.0，广泛部署）**（一手：https://github.com/termux/proot-distro/blob/v3.3.0/proot-distro.sh）：per-distro 插件声明 `TARBALL_URL["<arch>"]` + `TARBALL_SHA256["<arch>"]`；下载 `curl --fail --retry 5 --retry-connrefused --retry-delay 5 --location --output <tarball>.tmp` → rename 原子落盘；下载后 `sha256sum` 与 pin 比对，**mismatch 即删除缓存 tarball 并失败**（"Integrity checking failed"），绝不落到损坏文件上；sha256 未声明时显式打印"完整性检查已禁用"而非静默。
- **Python 重写版（master）**：`helpers/download.py` 用 urllib + `atomic_write` + 重试 5 次（尊重 429/503 `Retry-After`，上限 60s），完整性兜底为 `Content-Length` 全量比对（`require_complete_body`）；**无 Range 断点续传**（每次重试从零开始）；`sha256_file()` 提供给其他路径做摘要校验。
  来源（一手）：https://github.com/termux/proot-distro/blob/master/proot_distro/helpers/download.py

与 Orchestella 方案 B 的对应：`TARBALL_URL+TARBALL_SHA256` ≈ `DistroManifest(source 固定 HTTPS + sha256 pin)`；proot-distro 的 `.tmp→rename` ≈ 既有 `.part` 流程；本项目多出镜像测速/优先级与 fail-closed（防污染镜像链）两层。

### 1.4 Podroid——第一个把 UML 内核内置进 APK 的同业（probe-then-fallback）

- Issue #111（一手：https://github.com/ExTV/Podroid/issues/111）：请求以 UML 为主引擎、QEMU 兜底；引用 `zalexdev/linux-um-arm64`（Linux 7.2，`ARCH=um SUBARCH=arm64`）。维护者于 2026-11-09 关闭：**"UML shipped in v1.3.0"**——已随 APK 内置 UML 内核，首启前做兼容性探测，失败/panic/超时自动回退 QEMU 或 AVF。
- **为何 opt-in 而非默认**：仅两台设备验证过；guest 进程计入 Android 子进程配额；有 AVF 的设备 AVF 快约 10 倍（npm install 1.9s vs 20.6s vs 25.7s）。无 AVF 设备上 UML 比 QEMU 快约 2 倍（npm install）到 4 倍（CPU 密集）。
- 对 Orchestella 的含义：与 wave2 实测（fast kernel T3 中位 330–410 ms）方向一致；Podroid 的"先探测后回退"是 flag 门控 + 双关设计的同业先例。

### 1.5 大体积 asset 在 Google Play 与 F-Droid 的合规路径

- **Google Play**：官方页面（support.google.com 9859372 与 developer.android.com asset-delivery）本窗不可达，以下数字为**二手转述**：基础模块（install-time 下载）限约 **150 MB**（个别情形 200 MB）；install-time asset pack 合计 **4 GB**（游戏 Partner 项目可放宽）；on-demand/fast-follow 累计约 **30 GB**、单包上限口径 1.5–4 GB、最多 **100 个** asset pack。install-time asset pack 随 app 安装下载、**不计入 150 MB 基础模块限制**，但计入总安装体积。
  来源（二手）：https://support.google.com/googleplay/android-developer/answer/9859372 、https://developer.android.com/guide/playcore/asset-delivery 、https://stackoverflow.com/questions/61636760/
- **F-Droid**：只构建分发**纯 APK**，无 AAB/PAD 概念；无公开硬性大小上限（官方文档页本窗不可达，此点为二手转述，并以 fdroiddata 元数据一手佐证——UserLAnd/大量 Linux 环境类应用均以直接 APK 构建）。
  来源（一手佐证）：https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/tech.ula.yml
- **现实结论**：>100MB 内置 payload 若走 Play，工程上等价于把 `linux-fast`+rootfs 放进一个 install-time asset pack（内核与 stub 也可留在基础模块 native lib，如 QEMU 线现状）；走 GitHub Release APK / F-Droid / 自托管侧载则无任何体积门槛。**没有同业为 Linux payload 使用 PAD**——UserLAnd/Termux/proot-distro/Podroid 全部回避了 PAD 的 API 复杂度。

### 1.6 各家「更新一个内核版本」的操作面对比

| 项目 | 更新内核/rootfs 的操作 | 用户侧动作 | 是否需发新版 APK |
| --- | --- | --- | --- |
| Termux（内置 bootstrap） | 改 bootstrap 内容 → 发新版 APK（bootstrap 嵌入 native lib，无法独立更新） | 更新 app | **是** |
| Termux（内容热更新） | apt 仓库推新包 | app 内 `apt upgrade` | 否 |
| UserLAnd（下载 rootfs） | 在 `UserLAnd-Assets-<distro>` 发新 Release tag | 会话启动时自动下载新版本（版本号落后即触发） | 否 |
| proot-distro | 改插件 `TARBALL_URL/SHA256` | `proot-distro install/reinstall` | 否 |
| Podroid（内置 UML 内核） | 发新版 APK | 更新 app | **是** |
| Orchestella QEMU 线（现状） | 改 `guest-assets` 产物 → 发新版 APK + 固定 tag 源码包 | 更新 app | **是** |
| Orchestella 方案 B（复用 distro 管线） | 更新 `DistroManifest`/镜像目录（pin sha256 + 源 URL） | 首次创建实例时下载；manifest 更新后可触发重下载 | 否（资产）；是（manifest 代码若属 APK 则随版） |

要点：**方案 A/B 的更新面差异本质是"资产是否绑定 APK 版本"**；方案 B 的 manifest 仍随 APK 编译，故"改 pin"也发 APK，但"换镜像/发新 rootfs 构建同 pin"可不动 APK（镜像目录可托管于 GitHub Release 并只更新资产文件）。

## 2. 任务二：GPL-2.0 内核随 APK 分发的源码交付合规清单

### 2.1 GPLv2 §3 三种合规形态（一手原文，本地 `LICENSE.GPL-2.0`）

| 形态 | 原文要点 | 在本仓库的适用性 |
| --- | --- | --- |
| §3a 随包交付 | 随附**完整对应机器可读源码**，以"惯于软件交换的介质"分发，且按 §1/§2 许可交付 | 公开固定 tag + Release 源码包（ADR-0014 现行路径）；对 APK 场景，"介质"即公开 URL（见 GNU FAQ §6(d)） |
| §3b 书面要约 | 随附书面要约：**≥3 年有效**、向**任何第三方**提供、收费**不超过物理分发成本** | `guest-assets/SOURCE-OFFER.txt` 已按此模板；UML 件需同样书面要约 |
| §3c 转交要约 | 仅限非商业分发、且自己收到时带 §3b 要约，随附所收到的要约信息 | 从上游二次分发未修改 GPL 件（如原版 Alpine 包）时可走此形态 |

补充（GNU GPL FAQ，一手：https://www.gnu.org/licenses/gpl-faq.en.html）：

- 对应源码 = "**the source from which users can rebuild the same binary**"（#DistributeExtendedBinary）；**不要求**重建二进制逐字节同 hash（#MustSourceBuildToMatchExactHashOfBinary）；**只给 diff 不够**，须完整源码（#DistributingSourceIsInconvenient）。
- 源码可放**不同站点**（§6(d) 允许），但必须给出清晰获取指引（#SourceAndBinaryOnDifferentSites）；仓库/版本控制链接可接受，但获取难度须与二进制相当。
- §3b 要约必须传递给任何第三方，**再分发者有义务随附要约副本**（#WhatDoesWrittenOfferValid）——APK 被第三方转分发时 NOTICE/要约必须还在包内。
- 静态/动态链接均为 combined work（#GPLStaticVsDynamic）；APK 容器打包不影响"单一程序"判定（#AggregateContainers）。QEMU 线以"独立程序/虚拟化边界隔离"论证（ADR-0014）同样适用于 UML 内核。

### 2.2 内核分发者（非修改者）与修改者的义务差异

- **分发未修改的 GPL 件**：可仅随附 license 文本 + §3b/§3c 要约；若上游本来就有源码公开渠道（kernel.org），固定版本 URL + 指引即可满足。
- **分发修改版（本仓库情形）**：内核 pin 是 `Raymer8639/linux-um-arm64` tag `um-arm64-v0.3.0`（7.2.0-rc4，`linux-fast` 由 `defconfig-fast.fragment` 相对 pinned 配置生成）——**这是基于 GPL 程序的修改版**，§2+§3 要求交付"完整对应源码"，即：完整 fork 源码树（含全部 out-of-tree 修改）、最终 `.config`、`build-fast.sh` 等控制编译/安装的脚本。**out-of-tree 补丁/配置 fragment 不是可选交付物，是 §2 定义的工作本体的一部分**。
- fork 已公开在 GitHub（Raymer8639/linux-um-arm64 tag）→ 天然满足"公开 URL 形态"，但必须与二进制**一一对应**（tag 不可变；若重打 tag 即违反"rebuild the same binary"）。
- **方案 B（下载）不改变 GPL 义务**：分发者义务随"分发"行为触发，与 HTTP 传输还是 APK 内置无关——下载的内核/rootfs 同样需要 NOTICE/要约/对应源码路径。差别只在**时点**：方案 A 要求发布前 tag+源码包就绪（ADR-0014 门禁）；方案 B 可在 app 发布后、资产首次分发前补齐（但 app 内必须已内置要约/manifest 指引）。

### 2.3 Linux Foundation / kernel.org 对分发者的口径

kernel.org 与 Linux Foundation 没有超出 GPLv2 文本的附加要求；内核社区惯例是分发者在 NOTICE 中保留版权与许可声明、提供对应源码路径即可（此点未见一手官方"分发者指南"文档，按 GPLv2 原文执行即为安全口径；标注：无专门一手来源，结论基于 GPLv2 §2/§3 原文）。

### 2.4 UML 版 NOTICE 需要补的条目清单（对照 `NOTICE.QEMU`/`QEMU-GPL-SOURCES.md` 模式）

| # | 条目 | 对应既有模式 | 说明 |
| --- | --- | --- | --- |
| 1 | **UML 内核本体**：`Raymer8639/linux-um-arm64` tag `um-arm64-v0.3.0`、commit、上游 `linux 7.2.0-rc4`、源 URL、tarball sha256、与上游 kernel.org 的关系（fork） | `QEMU-GPL-SOURCES.md` §1 的"Source URL/版本/git identity/SHA-256"段 | fork 本身是 CCS 主体；`linux-fast`/`linux-fast-noinitrd` 二进制 sha256（wave2：UP `572c3433…`，noinitrd `a5efce2b…`） |
| 2 | **配置交付**：`defconfig-fast`（pinned `linux-bionic.config` + `defconfig-fast.fragment` 经 olddefconfig 的完整 `.config`）与最终产物 `kernel*.config` | §1 "Configure arguments" 段 | "控制编译的脚本/配置"，§2 义务内交付物 |
| 3 | **构建入口与环境**：`research/uml-fastboot/build-fast.sh`、NDK r29（zip sha256）、构建容器镜像身份、`SOURCE_DATE_EPOCH`、两次干净重建逐字节一致的复现性记录 | §1 "Build environment/Builder identity/Reproducibility" 段 | `docs/research/2026-11-11-uml-arm64-device-window-wave2.zh-CN.md` §1.1 已有数字可复用 |
| 4 | **stub_exe**：本项目自研（wave2 sha256 `87666f91…`）——须明确其许可证归属（项目 GPL-3.0-or-later 根 LICENSE）与 16 KB 对齐检查（`check-16kb-kernel.sh`） | `QEMU-GPL-SOURCES.md` §2 "DroidLayer shm stub" 行 | 自研件无 GPL 交付义务，但 NOTICE 须声明归属 |
| 5 | **initramfs 内容**：busybox-static（GPL-2.0，双架构 pin `ee469aee…`/`488ad6ef…`）、`initramfs/init`/`um-command.sh`（本项目脚本）、gzip 解压器说明 | `guest-assets/GPL-SOURCES.md` 的 BusyBox 段 | busybox 源 URL+sha256；init 脚本按项目许可随包 |
| 6 | **方案 B 下载件同等义务**：下载的 `linux-fast`/rootfs 与内置件走同一 NOTICE 记录；资产 manifest（如 `runtime-artifacts-v1.sha256` 的 UML 扩展）随 APK 或随下载清单 | `runtime-artifacts-v1.sha256` + `RuntimeArtifactManifest` 校验模式 | 下载发生时才触发分发义务，但 NOTICE/manifest 必须先随 APK |
| 7 | **书面要约**：`SOURCE-OFFER.txt` 模板扩展为 UML 件版本（≥3 年、成本上限、请求通道、`BUILD-INPUTS` 识别码） | `guest-assets/SOURCE-OFFER.txt` | §3b 形态兜底，即使走公开 tag 也建议保留 |
| 8 | **Release 门禁衔接**：`docs/adr/0014-licensing.md` 的"固定 tag + 一一对应 Release 源码包（含补丁/配置/脚本/LICENSE/NOTICE/校验记录）"，组件升级原子更新 tag | ADR-0014 决策原文 | UML 内核升级 = 新 tag + 新源码包 + NOTICE 更新，一次原子变更 |
| 9 | **rootfs 许可清单**：Alpine 3.22.8 aarch64（预烘焙）——未修改上游包按 license 清单转述（apk db 自带每包许可证）；**注入的 `init-zero-install`/`ready.sh` 及改动文件按 §2 属"修改版"，须随交付**（`verify.sh` 的 `treeSha256` 已可直接引用） | `guest-assets/GPL-SOURCES.md` e2fsprogs 段的逐库 NOTICE 模式 | 预烘焙≠再分发上游构建物：注入文件使整树成为项目产物 |
| 10 | **16K/复现性质量记录**（非 GPL 义务，属交付质量证据）：16 KB ELF 检查、跨路径复现性 open 项（wave2 §2）不混入 NOTICE | wave2 报告 §1/§2 | NOTICE 只载许可与源码交付事实，避免与质量证据混淆 |

## 3. 任务三：下载式 rootfs 的校验/签名/断点续传实践

### 3.1 发行版官方校验链（一手实读）

- **Alpine minirootfs**（v3.22 全系 point release）：每个 `alpine-minirootfs-3.22.<n>-aarch64.tar.gz` 配套 `.sha256`、`.sha512`、`.asc`——**GPG detached 签名直接签在 tarball 上**（不是签在 checksum 文件上）；签名密钥为 Natanael Copa (ncopa)，指纹 `0482 D840 22F5 2DF1 C4E7 CD43 293A CD09 07D9 495A`（https://alpinelinux.org/downloads/ 提供 `keys/ncopa.asc`）。
  来源（一手）：https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/ （目录实读）+ https://alpinelinux.org/downloads/
- **Debian cloud images**（bookworm/latest 目录实读）：存在 `SHA512SUMS`；本窗抓取的目录列表未见到 `SHA512SUMS.sign`/`.asc` 签名文件（可能为页面解析遗漏或发布形态变化，诚实标注：**签名文件存疑**，使用前应以官方页核对）。设计上不应假设 Debian 提供与 Alpine 同构的单文件 detached 签名。
  来源（一手）：https://cloud.debian.org/images/cloud/bookworm/latest/
- **HTTPS-only vs 签名验证的取舍**：Alpine 的 `.asc` 使"任何镜像分发同一 tarball"都可验证（镜像只需透传文件）；Debian 的 `SHA512SUMS` 若无签名则仅 HTTPS 可信。**对安卓客户端**：在设备上跑 GPG 验证成本高（gpg 移植/密钥轮换），业界通行的替代是"**构建期验证一次 + 把 sha256 固化进 app（pin），运行期只校验 pin**"——本仓库 `DistroManifest`（固定 HTTPS 源 + sha256 pin + 常量时间比较）正是该模式；proot-distro 的插件 `TARBALL_SHA256` 同理。

### 3.2 Android 端断点续传与完整性实践

- **RFC 7233 If-Range（一手协议原文：https://www.rfc-editor.org/rfc/rfc7233.txt）**：客户端带 `Range` + `If-Range`（强 ETag 或强 HTTP-date）续传时，validator 匹配 → 服务端**必须**返回 206 分段；不匹配 → **必须忽略 Range 返回完整 200**。这保证"断点续传不会把陈旧字节拼到新内容上"——是下载管线里比"下载完再验 sha256"更早拦截内容变更的机制（sha256 校验兜底不可省，If-Range 省的是重下流量）。
- **本仓库现状（一手实读）**：`runtime/network/.../DownloadEngine.java` 注释即声明 "HTTP Range resumption, and streaming SHA-256 verification"；`ResilientDownloadEngine` 会在目标文件已存在且 sha256 匹配时直接复用、不匹配时重下；下载走 `.part` 路径；**sha256 mismatch 是 fail-fast/fail-closed（不换镜像重试）**，只有网络类错误触发镜像轮换——与 `DistroMirrorCatalog` "候选必须逐字节一致"的规则闭环（防污染镜像链把 tampered 内容轮番喂给用户）。
  来源（一手）：https://github.com/Nyzeep/Orchestella-Studio 中 `runtime/network/src/main/java/io/github/nyzeep/orchestella/runtime/network/download/{DownloadEngine,ResilientDownloadEngine,MirrorPriorityList}.java`、测试 `RangeResumeTest`/`Sha256StreamingValidationTest`/`MirrorFailoverTest`
- **WorkManager + If-Range**：官方文档（developer.android.com）本窗不可达（二手转述）：WorkManager 适合"网络约束 + 断点重试"的后台下载编排，配合 `setRequiredNetworkType(CONNECTED)` 与指数退避；分片/续传本身仍由 HTTP Range + If-Range 承担。同业实践（proot-distro/UserLAnd）都**未用** WorkManager：前者直接同步 curl/urllib，后者用系统 `DownloadManager`（自带续传与重试，但失去流式校验控制权）。

### 3.3 对照本地 `research/uml-rootfs-prebake/verify.sh` 的既有校验模式

`verify.sh`（一手，本地）五层检查：① manifest 结构与字段合法；② 产物 sha256/size 与 manifest 一致；③ 首启状态清洁（init/ready 可执行、无包管理器调用、无网络命令、无 provisioning 标记、`machine-id`/`resolv.conf` 空、无 ssh host key、证书 bundle 在位、无 apk/apt 队列/缓存/锁）；④ 整树 tree digest（含符号链接/模式归一化）；⑤ 包数据库（`lib/apk/db/installed` / `dpkg status`）与 manifest 的 packageSet 逐包一致。

这套检查已覆盖"内容自检"层，且对下载版同样适用（下载版与预烘焙版共用同一 manifest schema）。

### 3.4 推荐设计：三层校验管线（方案 B）

```
L1 传输完整性（每次下载，运行时）
   HTTPS-only 源（DistroManifest.requireFixedHttpsSource 已强制）
   + Content-Length 全量比对（对齐 proot-distro require_complete_body 口径）
   + Range 续传带 If-Range 强 ETag（RFC 7233；现有 Range 续传补 If-Range 头）
   + .part → rename 原子落盘（现状已具备）
L2 官方签名 / 固化 pin（身份层，构建期与发布期）
   2a. 构建/发布期：人工在 CI 验证 Alpine .asc（ncopa 指纹）→ 把验证过的 sha256
       固化进 DistroManifest / distro-catalog Release（现状即此，补上 .asc 验证日志）
   2b. 运行期：客户端只校验 pin（常量时间比较，现状已具备），不在设备跑 GPG
   2c. 镜像轮换只允许"网络类失败"，sha256 mismatch fail-closed（现状已具备）
L3 内容自检（首次落盘后，一次性）
   复用 verify.sh 口径：manifest 字段 → 产物 sha256/size → 包数据库与 packageSet
   逐包一致 → tree digest → 首启清洁（无包管理器队列/锁、machine-id 空、无 ssh
   host key、证书 bundle）→ 输出结构化 VERIFY_OK / 失败原因码（对齐 UM_INIT_ERROR
   风格），失败即删除落盘文件回退重下载或切内置件
```

与既有实现的 gap 清单：① `DownloadEngine` 续传请求补 `If-Range`（需服务端 ETag 支持；无 ETag 时退化为"续传后整体 sha256 校验"，现状已正确）；② `verify.sh` 目前是构建机 bash/python 工具，L3 需要一个 Android 端等价物（Kotlin 移植 tree digest + 包数据库解析，或首启由 guest 内 `ready.sh` 自检输出 READY 判据——后者已存在）；③ `.sha256`/`.asc` 副产物的存档（构建证据目录）。

## 4. ACR 双方案对比表素材（方案 A 内置 vs 方案 B 下载）

体积数字来源：wave2 报告 §1（一手实测：arm64-up `linux-fast` 9,517,760 B、`linux-fast-noinitrd` 8,862,400 B、`stub_exe` 2,056 B、`initramfs.cpio.gz` 661,899 B；rootfs `alpine-3.22.8-aarch64.tar.gz` / `.ext4` 体积以任务给定口径 106 MB / 430 MB 计入）。

| 维度 | 方案 A：内置（fast kernel + rootfs 随 APK） | 方案 B：下载（复用发行版目录管线） | 依据 |
| --- | --- | --- | --- |
| APK/交付体积 | 内核+stub ≈ 10 MB；rootfs tar 106 MB（ext4 430 MB 不宜内置，运行时生成或仅 tar 解包）→ Play 基础模块 150 MB 门槛受压，需 install-time asset pack；F-Droid/GitHub 无门槛 | APK 仅 +内核/stub ≈ 10 MB（甚至内核也可下载）；rootfs 首次创建时下载 106 MB，Play/F-Droid 均无额外合规面 | §1.5 |
| 首启延迟 | **零下载**：wave2 实测 T3 中位 330 ms（UP）/410 ms（SMP），安装即可用 | 首次 106 MB 下载（网络相关分钟级）+ 校验 + 解包/落盘；断点续传与镜像测速缓解；之后与方案 A 同 | §1.4、wave2 §0 |
| 合规/源码交付 | 发布前即触发：tag + Release 源码包必须先于二进制发布（ADR-0014 门禁）；rootfs 注入文件属修改版须随交付 | 义务在首次分发（下载）时触发；app 内仍须预置 NOTICE/manifest/要约指引；tag 时点可后移但流程不变 | §2.1–§2.2 |
| 更新路径 | 内核/rootfs 更新 = 发新版 APK（Termux bootstrap 同款操作面）；asset pack 可独立发版但走 Play 流程 | 换 rootfs 构建/镜像不动 APK（UserLAnd/proot-distro 同款）；改 pin（DistroManifest 代码）仍发 APK；可按实例灰度切换 | §1.6 |
| 失败模式 | APK 签名 + `RuntimeArtifactManifest` sha256 校验兜底；无网络依赖；校验失败=整包不可用（可审计） | 网络失败/镜像死亡（有镜像轮换兜底）、sha256 mismatch fail-closed（不降级到坏镜像）、L3 内容自检失败可回退内置件；弱网/断网用户不可用 | §3.2–§3.4、DistroMirrorCatalog 规则 |
| 版本一致性 | 二进制与 pin 强绑定，审计路径短（manifest 内 sha256） | pin 与镜像内容可能漂移：候选非逐字节一致即禁止入列（既有规则）；下载件需与 NOTICE 的 tag 对应 | §3.1、§2.2 |
| 镜像/基建成本 | 无下载基建 | 需要长期存活的 pinned 资产（上游滚动仓库不可 pin——DistroMirrorCatalog 已记录 linuxcontainers 404 教训）；GitHub Release + 代理列表已就位 | DistroMirrorCatalog.kt KDoc |
| 同业先例 | Termux bootstrap（native lib 嵌入）、Podroid v1.3.0（UML 内核内置 + 探测回退） | UserLAnd（Releases 下载、无哈希校验）、proot-distro（URL+SHA256 pin、fail-closed） | §1.1–§1.4 |
| 双关+flag 门控落点 | 内置件作为快路径（有 AVF/测过设备直接用）；flag off 时彻底不加载 | 下载件作为大体积替代/内置缺失时的降级路径；Podroid 式"先探测内置件可用性，失败转下载" | §1.4 |

Top5 关键差异行（供 ACR 决策引用）：**首启延迟**、**Play 150 MB 门槛下的体积合规**、**更新操作面（发 APK vs 热更新资产）**、**失败模式（无网络依赖 vs fail-closed 校验链）**、**GPL 交付时点（发布门禁 vs 可后补）**。

## 5. 来源清单与证据分级

一手（本窗实读）：

1. UserLAnd `GithubApiClient.kt` / `AssetDownloader.kt` / `AssetRepository.kt`（old-master 分支）：https://github.com/CypherpunkArmory/UserLAnd/tree/old-master/app/src/main/java/tech/ula
2. UserLAnd-Assets-Support：https://github.com/CypherpunkArmory/UserLAnd-Assets-Support
3. UserLAnd F-Droid metadata：https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/tech.ula.yml
4. Termux `TermuxInstaller.java` / `TermuxBootstrap.java` / `termux-bootstrap-zip.S`：https://github.com/termux/termux-app
5. Termux `generate-bootstraps.sh`：https://github.com/termux/termux-packages/blob/master/scripts/generate-bootstraps.sh
6. proot-distro v3.3.0（bash）与 master（Python）：https://github.com/termux/proot-distro
7. Podroid issue #107/#111：https://github.com/ExTV/Podroid/issues/111 、https://github.com/ExTV/Podroid/issues/107
8. Alpine releases 目录与签名说明：https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/ 、https://alpinelinux.org/downloads/
9. Debian cloud images 目录：https://cloud.debian.org/images/cloud/bookworm/latest/
10. GNU GPL FAQ：https://www.gnu.org/licenses/gpl-faq.en.html
11. RFC 7233（If-Range）：https://www.rfc-editor.org/rfc/rfc7233.txt
12. 本仓库一手代码：`runtime/network`（DownloadEngine/ResilientDownloadEngine/MirrorPriorityList/测试）、`runtime/distro`（DistroManifest）、`app/.../DistroMirrorCatalog.kt`、`app/.../startup/RuntimeVerification.kt`、`research/uml-rootfs-prebake/verify.sh`、`research/uml-fastboot/README.md`、`app/src/main/assets/*`、`guest-assets/*`、`docs/adr/0014-licensing.md`
13. Alpine tarball 官方体积（minirootfs 原始包 ~4 MB；Orchestella 预烘焙 106 MB/430 MB 为任务口径，源自 U2 研究线）

二手（官方页面本窗不可达，经搜索摘要转述，逐条已标注）：

1. Google Play 体积限制数字（150 MB 基础模块 / install-time asset pack 合计 4 GB / on-demand 累计 30 GB / 100 包）：https://support.google.com/googleplay/android-developer/answer/9859372 、https://developer.android.com/guide/playcore/asset-delivery
2. F-Droid 无 AAB/大小上限的政策表述（以 fdroiddata 一手佐证构建形态）：https://f-droid.org/en/docs/FAQ/
3. WorkManager 网络约束下载编排（developer.android.com 不可达）

本地既有报告参照：`docs/research/2026-11-11-uml-arm64-device-window-wave2.zh-CN.md`（格式）、`docs/research/2026-11-10-uml-artifacts-packaging.zh-CN.md`（打包线）。

## 6. 边界与诚实记录

- Play/F-Droid 官方政策页本窗不可达（SSL/超时），限制数字与政策表述为二手转述；ACR 决策若以 150 MB 门槛为关键输入，发布前应在可访问网络复核一次。
- Debian cloud 目录的 `SHA512SUMS` 签名文件状态存疑（§3.1），使用 Debian 官方 rootfs 前需重新核对签名形态。
- `research/uml-fastboot` 与 `uml-rootfs-prebake` 的 ARM64 数据以 wave2 报告为准；本报告不重复采集、不外推新数字。
- 本报告只做调研与素材，不产生产品代码；`stub_exe` 许可归属（清单第 4 条）需 U5/许可负责人最终裁定。

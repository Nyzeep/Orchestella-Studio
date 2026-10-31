
---
title: 平台政策核实矩阵——16KB 日期口径、Android 17 行为变更、Play 分发合规、国内商店与 GPL 先例
date: 2026-11-02
status: complete（fabric-batch1 工单 02 交付；零设备批次，全部为联网核实，无真机数据）
scope: 工单 .scratch/orchestella-uml-fabric/issues/02-platform-policy-verification.md 的「必须回答」五项 + 对 ACR-draft-four-lanes-20261029.md §6 八项的三类判定。不改 CONTEXT.md/ADR/代码。
evidence-discipline: 证据等级标注 [官方文档]/[官方源码]/[社区]/[未找到证据]；本批次无设备，真机项一律 not-tested；性能数字纪律不涉及（本文无性能数字）。
---

# 平台政策核实矩阵（2026-11-02）

**访问与取证方式声明**：本次核实于 2026-10-31（UTC）执行。developer.android.com、source.android.com、android.googlesource.com（转储解码）可直连抓取原文；support.google.com 与 play.google.com 直连超时（curl exit 28 / WebFetch 失败），其内容经搜索引擎缓存片段取证并逐字引用，已按停止条件协议并列标注来源与访问日期。所有 URL 的访问日期均为 **2026-10-31**，下文不再逐条重复。

---

## 0. 结论速览

1. **16KB 两口径不矛盾，是同一要求的时间线演进**：2025-11-01（新应用+更新，博客口径）→ 2026-05-31（更新宽限到期）→ **2027-02-01（更新硬截止，当前官方文档唯一在册日期）**。对 ACR 的可用口径：targetSdk 35+ 的 Play 渠道应用必须支持 16KB；新应用/更新的合规义务已于 2026-05-31 过期生效，更新硬截止 2027-02-01。GitHub/F-Droid 渠道无此提交门槛。
2. **Android 17 MemoryLimiter 有官方阈值表**：cgroup v2 软限制（memory.high + swap.max），按设备 RAM 分五档；12GB 设备上限 visible 8 GiB / not-visible 4 GiB。UML `mem=512M~2G` 的 guest 内存在 12/16GB 设备上远低于阈值；在 6GB/4GB 设备上后台态会触限（软限制→回收/降速，swap 耗尽才杀）。目标设备执行状态 not-tested。
3. **Android 17 本地网络/loopback 对 gvproxy/passt 当前形态无影响**：被禁的是**跨 profile** loopback；同一 profile（本 App UID 内 gvproxy↔QEMU/UML）不受影响。`ACCESS_LOCAL_NETWORK` 仅 targetSdk ≥37 强制，且管的是 LAN 地址流量、不含 loopback——是未来升 37 时的前置项，不是当前阻塞项。
4. **原生库只读（Safer Native DCL—C）不波及内核 .so exec 链路**：条款文本限定 `System.load()` 路径（违者 `UnsatisfiedLinkError`）且仅 targetSdk ≥37 生效；本项目 UML/QEMU 走 `execve` / `mmap PROT_EXEC` + nativeLibraryDir，不在条款字面范围内。既有调研判定「System.load 限制不影响 execve/mmap」与官方文本一致，**未被推翻**；ART/linker 实现级确认记为开放项（源码窗口，非设备）。
5. **Play「Device and Network Abuse」合规矩阵**：内核 .so 随扩展包分发 = 低风险；**运行时下载内核 = 高风险（政策明文禁止从 Play 以外来源下载可执行代码，违例示例点名 native code）**；rootfs 首次下载 = 中风险（数据包内含 ELF 的文本覆盖争议，实践有先例但无官方豁免文本）。
6. **国内商店（华为）**：不收录类型清单明文禁 **root 功能类应用**；**未找到「模拟器/虚拟机类应用」的明文禁令条款**；APP 备案为硬门槛；「街机游戏内容」条款与模拟器无关（远程实体机直播）。
7. **GPL 先例**：「GPL-2.0 内核随 APK 分发」的公开先例**已找到**（drolin，GPL-2.0 全仓；Stryker，GitHub releases APK），均为 GitHub 渠道、社区项目；**经 Play 分发的先例 [未找到证据]**。
8. **ACR §6 八项判定**：2 项可更正（§6.1 全部、§6.2 部分）、1 项政策侧新增输入（§6.6 维持需法务但补先例）、5 项不属政策口径转实测/设备工单。详见 §5。

---

## 1. 16KB 两口径的权威裁决

### 1.1 三日期时间线表

| 日期 | 要求内容 | 适用对象 | 状态（2026-10-31 时点） | 来源与等级 |
|---|---|---|---|---|
| **2025-11-01** | 新应用和应用更新必须支持 16KB（提交合规） | targetSdk 35+（Android 15+）64 位 | **已生效并过期** | Play 官方博客口径，经 support.google.com/googleplay/android-developer/thread/368982598（官方 Community Manager Sakshi S. 澄清帖）逐字引述：「As per the initial blog post, the correct deadline is Nov 1 2025.」[官方文档（社区管理员转述）] |
| **2026-05-31** | 申请延期的更新宽限到期；「After that, all app updates must be compatible with 16 KB memory page sizes」 | 申请了延期的存量应用 | **已过期**。另有 2025-08-28 开发者帖引 Play Console 文案原文：「From May 31, 2026, if your app updates do not support 16 KB memory page sizes, you won't be able to release these updates.」 | 同上 thread 368982598 + thread 368386720 [官方文档（社区帖引述 Console 文案）] |
| **2027-02-01** | 「Starting February 1, 2027, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates.」 | **所有** targetSdk 35+ 的 Play 应用（不区分是否曾申请延期） | **未来日期，当前官方文档唯一在册的更新硬截止** | developer.android.com/guide/practices/page-sizes「Google Play compatibility requirement」节原文（直连抓取全文）[官方文档] |

### 1.2 「对谁、何时、什么要求」收敛口径

developer.android.com 当前页（访问 2026-10-31）的现行表述：

> 「To ensure your app works correctly on the latest versions of Android, all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play. Starting February 1, 2027, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates.」

判定：**两说并存但可按时间线收敛，无需二选一**。2025-11-01 / 2026-05-31 是早期博客与 Console 文案时间表，2027-02-01 是当前官方文档在册的更新硬截止。16KB 要求的适用面（targetSdk 35+、64 位、Google Play 渠道）在两说中一致。

**残余不确定（按停止条件如实记录，不裁决）**：Play Console 当前对「不兼容 16KB 的更新」是拦截还是仅告警，两说文案有差异（2025-08 Console 文案写 2026-05-31 拦截，当前 developer.android.com 写 2027-02-01 拦截）。本项目无 Play 开发者账号可实测 → **not-tested**。ACR 引用建议：以 developer.android.com 当前页为权威（2027-02-01），并注明「Console 实际拦截行为未实测」。

### 1.3 本项目三渠道约束映射

| 渠道 | 16KB 提交门槛 | 16KB 运行期约束 | targetSdk 约束 |
|---|---|---|---|
| **GitHub Releases（主）** | 无（非 Play 渠道） | 16KB 宿主设备上运行仍需处理：Android 16+ 有 16KB backcompat 模式（条件：.so LOAD 段 4KB 对齐 + zip 内未压缩 ELF 4KB ZIP 对齐；启用则首次启动显示 compat 警告），developer.android.com/guide/practices/page-sizes [官方文档] | 无 Play 要求；受 Android 平台安装下限约束（见 §5.2） |
| **F-Droid（主）** | 无（同上） | 同上 | 同上；F-Droid 自身的构建/许可政策本轮未核（见 §8） |
| **Play（可选）** | **有**：targetSdk 35+ 应用必须支持 16KB；新应用/更新义务已于 2026-05-31 前生效，更新硬截止 2027-02-01 [官方文档] | 同上；且提交合规要求**全部 native 库（含 UML 内核 .so、QEMU、gvproxy）16KB 对齐重链**，backcompat 模式是运行期缓解、不是提交合规替代 | targetSdk 36 已生效（见 §5.1） |

对本项目的直接含义（引用 ACR §1.1 既有事实）：主包 targetSdk 36 落在 35+ 区间 → **若走 Play，16KB 兼容是提交必答题**；`libuml_kernel.so`（9.6MB）、QEMU、gvproxy 等 native 产物需按 16KB 对齐构建。GitHub/F-Droid 主渠道下该门槛不适用，16KB 宿主运行问题退回技术验证轨道（ACR §3.4 的「16K 宿主 + 4K guest」未验证项）。

---

## 2. Android 17 三项行为变更逐条影响

来源基准：developer.android.com/about/versions/17/behavior-changes-all（all apps，直连抓取）与 /behavior-changes-17（targetSdk 37+，直连抓取），访问 2026-10-31 [官方文档]；source.android.com/docs/core/perf/memory-limiter（直连抓取）[官方文档]；android.googlesource.com/platform/system/sepolicy main 分支（转储解码）[官方源码]。

### 2.1 ① MemoryLimiter 对 mem=512M~2G guest 的实际阈值

**机制**（source.android.com/docs/core/perf/memory-limiter，Android 17+）[官方文档]：

- 系统服务，基于 **cgroup v2** 监控与限制应用进程内存；依赖内核 cgroup v2 memory controller。
- 使用两个属性：`memory.high`（**软限制**：超出后进程被 throttle、内核主动回收该进程页）与 `memory.swap.max`（ZRAM swap 上限）。
- 监控对象：**UID ≥ 10000 的全部应用进程**，无应用侧 allowlist：「Limits apply uniformly to all application processes ... including preinstalled system and OEM apps」。
- 超限后果分级：先回收 clean file 页 + 换出 inactive anon 页（性能损失）；「In extreme situations—such as an uncontrolled memory leak ... the app fails to allocate memory and is terminated」。被杀时 `ApplicationExitInfo` reason=REASON_OTHER、description 含「MemoryLimiter:AnonSwap」（behavior-changes-all 原文）。
- 应用**无法**运行期查询自己的限额（「Absence of runtime query APIs」）。
- 设备采纳依赖性：behavior-changes-all 原文「(These commands have no effect on a device which does not impose memory limits.)」——即 MemoryLimiter 是否启用是设备侧行为，OEM 可不实施。

**官方标准平台阈值表**（/system/etc/memory-limiter-config.xml，按 MemTotal MiB 区间 [floor, ceiling) 分档）[官方文档]：

| 目标物理 RAM（MemTotal 区间 MiB） | Visible 内存上限 | Not-visible 内存上限 | Visible swap 上限 | Not-visible swap 上限 |
|---|---|---|---|---|
| 16 GB [14336, 18432) | 10,240 MiB | 5,120 MiB | 5,120 MiB | 5,120 MiB |
| 12 GB [9216, 14336) | 8,192 MiB | 4,096 MiB | 4,096 MiB | 4,096 MiB |
| 8 GB [6800, 9216) | 5,120 MiB | 3,072 MiB | 3,072 MiB | 3,072 MiB |
| 6 GB [4800, 6800) | 4,096 MiB | 2,048 MiB | 2,048 MiB | 2,048 MiB |
| 4 GB [3200, 4800) | 2,048 MiB | 1,024 MiB | 1,024 MiB | 1,024 MiB |

**对 UML 车道的判定**（标注：以下为阈值表与既有 mem 配置的对照推算，**非实测**）：

UML guest 内存是 App 进程的匿名内存（physmem mmap/memfd），全额计入该进程 cgroup 计数。对照 `mem=512M~2G`：

| 设备档位 | UML 前台（visible） | UML 后台（not-visible） |
|---|---|---|
| 12/16 GB（目标设备属此档） | 8/10 GiB 上限：2G guest 富余 | 4/5 GiB 上限：2G guest 富余 |
| 8 GB | 5 GiB：富余 | 3 GiB：富余 |
| 6 GB | 4 GiB：富余 | **2,048 MiB：2G guest 恰好压线**，加 stub/工具链开销即触限 |
| 4 GB | 2 GiB：2G guest 压线 | **1 GiB：2G guest 必触限** |

触限后果是**软限制**（回收+swap+降速），不是立即杀死；只有 swap（ZRAM）耗尽后分配失败才终止。结论：

- **不是 UML 在 Android 17 上的准入阻塞**，而是低内存设备（≤8GB，尤其后台驻留）上的性能/稳定性风险项；
- 长驻场景（ACR §3.4 的 30 min 稳定性验证）若落在 ≤8GB 设备且进程被切后台，应把 MemoryLimiter 触发纳入观察项；
- 缓解通道：`am memory-limiter ignore <uid>` 需 shell 权限（adb/shell uid 2000）——经 Shizuku 执行在权限上可行，但属产品设计决策且设备侧是否实施 MemoryLimiter 未知 → **not-tested，仅记录通道存在**。

### 2.2 ② 本地网络/loopback 权限化对 gvproxy 端口转发与 passt 的影响

两条独立变更，须分开：

**A. 跨 profile loopback 被禁（behavior-changes-all，Privacy 节）**[官方文档]：

> 「Block cross profile loopback traffic — Beginning with Android 17, cross-profile loopback traffic is no longer permitted by default. **Loopback traffic within the same profile is not affected.** This change applies to all apps running on Android 17 or higher, regardless of what API level the app targets.」

判定：本项目 gvproxy 监听 127.0.0.1、QEMU/UML guest 出网经同 App 进程（同 UID、同 profile）内 loopback → **不在被禁范围**。跨 profile 仅在使用 Android 工作资料把 App 与其 VM 组件分置两个 profile 时才触发——本项目无此形态。**当前无影响**。

**B. ACCESS_LOCAL_NETWORK 运行时权限（behavior-changes-17，仅 targetSdk ≥37）**[官方文档]：

> 「Local network permission required for apps targeting Android 17 — Android 17 introduces the ACCESS_LOCAL_NETWORK runtime permission ... Because this falls under the existing NEARBY_DEVICES permission group ... Note: In Android 16, apps could opt in ... Beginning with Android 17, enforcement is mandatory for apps that target Android 17 (API level 37) or higher.」

适用范围（developer.android.com/privacy-and-security/local-network-permission，访问 2026-10-31）[官方文档]：

- 「Traffic to and from a **local network address** requires local network access permission」：出站/入站 TCP、UDP 单播/多播/广播，实现在网络栈深层、覆盖所有 API。
- 条款全文**未提及 loopback/localhost**；loopback 由上一条（cross-profile）单独管辖。设备本地 DNS 服务器（端口 53）流量豁免。
- Android 16 为 opt-in，Android 17 对 targetSdk 37+ 强制；targetSdk 36 的 App 继续按 Android 16 的「临时使用」态。

判定：

- **当前（targetSdk 36）无影响**；升 targetSdk 37 时成为必答题。
- 对 gvproxy/passt 的具体映射：同进程 loopback（guest↔host 端口转发、passt 桥接）不触权限；**guest 出站访问 LAN 地址**（如 guest 主动连 NAS/打印机）与**把 guest 服务暴露给 LAN**（如从 PC 直连 guest SSH 端口）属于 LAN 地址流量，targetSdk 37 时需声明并请求 `ACCESS_LOCAL_NETWORK`。
- 产品影响一句话：权限申请成本一次性（NEARBY_DEVICES 组），但「无感知不弹窗」的产品承诺需在升 37 时重估。

### 2.3 ③ 原生库只读（含 W^X）对「内核 .so 走 nativeLibraryDir exec 链路」的影响

**条款原文**（behavior-changes-17，Safer Native DCL—C）[官方文档]：

> 「If your app targets Android 17 (API level 37) or higher, the Safer Dynamic Code Loading (DCL) protection introduced in Android 14 for DEX and JAR files now extends to native libraries. **All native files loaded using System.load() must be marked as read-only. Otherwise, the system throws UnsatisfiedLinkError.**」

**逐层判定**：

| 检查点 | 结论 | 依据 |
|---|---|---|
| 触发条件 | 仅 targetSdk ≥ 37 的 App。**本项目主包 targetSdk 36 → 现阶段完全不触发** | 条款首句 [官方文档] |
| 约束路径 | 条款字面仅覆盖 `System.load()` 的装载路径。UML 内核（`libuml_kernel.so` + stub 经 nativeLibraryDir）与 QEMU/gvproxy（nativeLibraryDir exec）走 **execve / mmap PROT_EXEC**，不经 `System.load()` | 条款文本 [官方文档] |
| 文件属性 | 即使假设被覆盖：nativeLibraryDir 内文件由安装器部署、App 进程无写权限，天然满足「read-only」要求 | 平台机制 [官方文档级推断] |
| 与既有 W^X 三分结论的一致性 | 复核 AOSP main `private/domain.te`（访问 2026-10-31）[官方源码]：execmod neverallow 豁免面仍是 `untrusted_app_25` / `untrusted_app_27` 两域（neverallow 块约 :1344-1351，与既有调研引用行号一致）；`execute` / `execute_no_trans` 边界未被本变更触碰 | [官方源码] |
| 既有调研判定「System.load 限制不影响 execve/mmap」 | **与官方文本一致，未被推翻**。未做 ART/linker 实现级源码确认（见 §6 开放项），结论强度为「官方文档级确认 + 源码级待核」 | 本文判定 |

**附带修正**：WorkBuddy 调研（工单背景内联）把「原生库只读」列为 Android 17 行为变更，准确名称是 **Safer Native DCL—C**（Safer Dynamic Code Loading 扩展到 native libraries），且是 **targetSdk 37+ 条件性变更**、不在 all-apps 清单。ACR 引用时建议使用准确名称与条件，避免被读成「Android 17 全量收紧 W^X」。

---

## 3. Play「Device and Network Abuse」政策 vs 三种 UML 分发形态合规矩阵

政策原文（support.google.com/googleplay/android-developer/answer/9888379「Device and Network Abuse」全文，经搜索缓存逐字取证，直连超时已声明）[官方文档]：

> 「An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism. **Likewise, an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play.** This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser).」

违例示例清单同页原文：

> 「Apps or third party code (for example, SDKs) that **download executable code, such as dex files or native code**, from a source other than Google Play.」

（中文镜像：support.google.com/googleplay/android-developer/answer/16559646，逐字对应：「应用不得从 Google Play 以外的来源下载可执行代码（例如 dex、JAR 和 .so 文件）。这项限制不适用于在虚拟机或解释器中运行且通过这种方式间接访问 Android API 的代码（例如 WebView 或浏览器中的 JavaScript）」[官方文档]；另与 storage.googleapis.com 政策全文快照三方一致）

### 3.1 合规矩阵

| # | 分发形态 | 政策判定 | 风险分级 | 依据与引文 |
|---|---|---|---|---|
| ① | **内核 .so 随扩展包分发**（jniLibs / Play Asset Delivery / Play Feature Delivery，经 Play 提交） | **不触犯下载禁令**：代码随 APK/资产包经 Play 分发，不属于「从 Google Play 以外的来源下载」 | **低**（Play 渠道合规；非 Play 渠道无此政策。GPL 义务走 ADR-0014 流程，见 §4.2） | 上引条款反读：禁令射程是「from a source other than Google Play」[官方文档] |
| ② | **运行时下载内核**（首次启动从 GitHub/CDN 拉 kernel ELF/.so） | **政策明文射程内**：内核 ELF 属可执行代码（native），从非 Play 来源下载即违例。VM/解释器例外不适用——例外要求代码「在虚拟机或解释器中运行且（由 VM/解释器）间接访问 Android API」，典型对象是 WebView 内 JS 等解释型内容；UML 内核是宿主 CPU **原生执行**的 ELF，不满足例外定义。违例示例清单另点名 native code | **高（Play 渠道禁止）**。GitHub/F-Droid 渠道无此政策（F-Droid 政策本轮未核，见 §8） | 上引两句原文 [官方文档]。**解释性灰区如实记录**：「virtual machine」例外的边界无官方判例文本，本判定基于条款目的（针对解释型/字节码内容）而非字面穷举；若 ACR 需要更硬结论，并入 §5.6 法务项 |
| ③ | **rootfs 首次下载**（首次运行从镜像源拉发行版 rootfs） | **文本覆盖存在争议**：rootfs 归档名义上是「数据」，但内容含 ELF 可执行文件与 .so——严格字面读法落在「download executable code」射程内；宽松读法认为它是被挂载的数据资产。**无官方豁免文本**，也无公开的 Play 审核判例文本 | **中**（Play 渠道：字面风险存在、执法口径未证；GitHub/F-Droid：低）。缓解路径：rootfs 改为随扩展包/资产包经 Play 分发（等同形态①），或首启仅下载数据且可执行文件全部来自包内 | 条款原文 + 无判例文本 [官方文档 + 未找到证据（判例）]。实践参照：UserLAnd 等发行版工具长期在 Play 采用运行时拉取 rootfs 模式（[社区] 方向互证，非执法结论，不作决策依据） |

**矩阵适用渠道说明**：本矩阵仅对 Play 渠道有约束力。本项目主渠道 GitHub/F-Droid 下，三形态均无 Play 政策射程；矩阵价值在于**若 ACR 未来把 Play 列为可选渠道，②形态（运行时下载内核）必须排除**，发行形态只能走 ①（或 ③ 的资产包化变体）。

---

## 4. 国内商店限制现状与 GPL 先例

### 4.1 华为应用市场（AppGallery）

核实页面（站点为 SPA，直连无正文；内容经搜索缓存取证，已声明）：

| 条款 | 原文/要点 | 来源 |
|---|---|---|
| **root 类应用不收录** | 「应用含可破坏系统安全的root功能、以及需要申请root权限或引导对手机进行root刷机的内容」列入暂不收录类型 | developer.huawei.com/consumer/cn/devservice/doc/50117《不收录类型》[官方文档] |
| **VPN/专线** | 「应用含未经批准自行建立或租用专线（含虚拟专用网络VPN）等其他信道开展跨境经营活动的内容」——针对跨境经营场景，非通用 VPN 禁令 | 同上 [官方文档] |
| **「模拟器/虚拟机类应用」明文禁令** | **[未找到证据]**。已核《不收录类型》（50117）与《部分类型应用注意事项》（50115）全部可见条目：25 项注意事项中无模拟器/虚拟机条目；「应用含街机游戏内容」条目限定「在线操控线下实体机的直播玩法内容」（远程抓娃娃等），与软件模拟器无关 [官方文档] | developer.huawei.com/consumer/cn/doc/50115 [官方文档] |
| **APP 备案硬门槛** | 「APP主办者应按照相关规定履行核准（备案）手续。未履行核准（备案）手续的，不得从事APP互联网信息服务」 | developer.huawei.com/consumer/cn/doc/80301《应用资质审核要求》[官方文档] |
| **实践参照** | VMOS（Android 虚拟机类 App，自带 root 环境）2025 年仍在迭代（百度百科条目，最后更新 2025-12）；其在华为应用市场的上架状态**未核实** | vmos.cn / 百度百科 [社区 + 未找到证据] |

**判定**：华为侧对本项目形态（无 root、App 内运行 Linux guest）**无明文禁令**；实际约束是备案门槛与分类审查（模拟器内容若被归为游戏需版号——「分类由平台审核判定」为社区项目经验 [社区]，仅方向互证）。其他国内商店（小米/OPPO/vivo/应用宝）本轮未逐家核条款 → 如实记录为未核。

### 4.2 GPL 先例检索

| 先例 | 事实 | 证据等级 |
|---|---|---|
| **fahrulseptiana/drolin** | 公开 GitHub 仓库（License: **GPL-2.0**，最后推送 2026-10-24）；README 自述 Android App「packaged engine and Ubuntu image」——UML 内核与镜像打包进 App 分发 | [一手仓库事实]（GitHub API 直查） |
| **zalexdev/strykerapp** | 公开 GitHub 仓库（License: GPL-3.0，最后推送 2026-10-17），经 GitHub releases 分发含 UML 内核的 APK（作者 Telegram 频道 strykerapp 多帖宣传「custom universal kernel ... Boots Debian」）；内核源码树 linux-um-arm64 公开（License 字段 NOASSERTION，内核树按 GPL-2.0 释出属常识，未逐文件核） | [社区]（分发事实一手，合规完整性未核验） |
| **经 Play 分发的先例** | **[未找到证据]**。检索未发现任何「GPL-2.0 内核随 APK 经 Google Play 分发」的公开案例 | [未找到证据] |

**判定**：「GPL-2.0 内核随 APK 分发」的公开先例**存在**（社区级、GitHub 渠道、2026 年仍活跃）；但两个先例均为社区项目，其「对应源码交付完整性」未核验，不能作为本项目合规依据，仅能证明「技术上与社区实践上该分发形态存在」。ADR-0014「独立程序边界」的法务问题不受此影响（见 §5.6）。

---

## 5. 对 ACR 草案 §6 未取数清单的逐项更正建议

判定三类：【已可更正】（新证据 + 来源）/【仍取不到】（维持）/【不属政策口径】（转其他工单，本票不处理）。

### §6.1 Play 对 targetSdk 的最低要求版本号与生效日期 —【已可更正】

新证据 [官方文档]（support.google.com/googleplay/android-developer/answer/11926878 zh-Hans + developer.android.com/google/play/requirements/target-sdk，两源一致，访问 2026-10-31）：

- **自 2026-08-31 起（已生效）**：新应用和应用更新必须 targetSdk ≥ **36** 才能提交 Google Play；Wear OS / Android Automotive OS ≥ 35；Android TV / XR ≥ 34。
- 可申请延期至 **2026-11-01**（Play Console 延期表单）。
- **可用性规则**：targetSdk ≤ 34 的应用对新用户仅可在「设备 OS 版本 ≤ 应用 targetSdk」上使用 → targetSdk 28 应用对新用户实际上只在 Android 9（API 28）及以下设备可见，即 **targetSdk 28 无现实 Play 渠道**（与 ACR §2.2 引用的 Termux 退出 Play 互证）。

ACR §2.2 的「未取数」可替换为上表；§5.1 撤销条件 6 的「补齐日期」前置已满足。

### §6.2 Android 何时收回 targetSdk 28 的 legacy 域 —【部分更正 + 仍取不到（日期）】

可更正部分 [官方源码 + 官方文档]：

- **域映射现状**：AOSP main `private/seapp_contexts`（访问 2026-10-31）：`minTargetSdkVersion=28 → untrusted_app_27`、`minTargetSdkVersion=26 → untrusted_app_27`、`minTargetSdkVersion=34 → untrusted_app`——targetSdk 28 → `untrusted_app_27` 映射与 ACR §1.1 一致，且该域**在 AOSP main 仍存在**。
- **收回机制澄清**：legacy 域的退场机制是「最低可安装 targetSdk 上调」而非域删除。已证实时间线 [官方文档]：Android 14 起安装下限 targetSdk 23、**Android 15 起 24**（behavior-changes-all 原文 + `INSTALL_FAILED_DEPRECATED_SDK_VERSION` 文案 + adb `--bypass-low-target-sdk-block` 逃生门，已安装应用不被卸载）；**Android 16 与 17 的行为变更页均无进一步上调记载**（两页全文检索无最低 targetSdk 条款，访问 2026-10-31）。
- **execmod 豁免面**：main `private/domain.te` 的 execmod neverallow 仍仅豁免 `untrusted_app_25` / `untrusted_app_27`——28 域的 W^X 豁免地位在当前 main 未变。

仍取不到：**untrusted_app_27 域本身的删除日期无官方出处** [未找到证据]。ACR 引用建议：把「28 域何时没了」改写为「安装下限已到 24（Android 15），16/17 未再上调；域删除无官方时间表；Termux 官方自述兼容模式 may end in some future Android version（既有引用不变）」。

### §6.3 28 域失效后 proot 车道的替代路径（system_linker_exec 在自研包名上是否可用） —【不属政策口径（转其他工单）】

本项是运行期实测/实现验证问题（termux-exec 式 execve /system/bin/linker64 + 只读前缀）。政策侧本轮唯一新增输入：AOSP main SELinux 的 execmod neverallow 豁免面与 execute 边界复核未见与本项冲突的收紧（`private/domain.te`，访问 2026-10-31）[官方源码]。**判定维持 ACR §5.1 撤销条件 2 的表述：门未闭合，需实测** → 转实测类工单（非本票）。

### §6.4 QEMU 车道跑 dockerd 的实测结论 —【不属政策口径（转实测工单）】

纯实测项。政策侧无新增（无任何商店政策条款落在「容器内跑 dockerd」的射程内；本项与分发形态无关）。维持「未取数」，转 QEMU 实测工单。

### §6.5 UML 长驻 / Docker / 链接三块的任何数字 —【不属政策口径（转实测工单）】

纯实测项（ACR §3.4 表已列）。政策侧唯一关联：MemoryLimiter 触限观察应并入长驻稳定性测试场景（见 §2.1），但数字本身仍须实测取得。维持「未取数」。

### §6.6 UML 内核以 .so 形式分发是否仍满足 ADR-0014「独立程序边界」—【维持：需法务，非本票可裁】

法务判定维持「未取数」。政策侧新增输入（供法务参考，不预答结论）：

- Play 分发政策对「内核 .so 随扩展包」形态本身无阻碍（§3.1 ①），但对「运行时下载内核」是禁区（§3.1 ②）——即 **ADR-0014 的发布边界形态讨论应限定在「随包分发」变体**，运行时下载变体在 Play 渠道已可排除。
- GPL 先例：社区先例存在（§4.2），无 Play 渠道先例 [未找到证据]，无法务判例价值。
- 华为等国内商店无内核类明文禁令（§4.1）。

### §6.7 同夹具四列车道的同一轮数据 —【不属政策口径（转实测工单）】

ACR §1.4 口径纪律的实测项。政策核实不产生性能数字。维持。

### §6.8 扩展包在目标机型的可安装性 —【不属政策口径（转设备窗口工单）】

HyperOS 安装拦截为设备侧事实（ACR §2.4 选项 C 已述），需设备窗口复测。政策侧无新增。维持。

---

## 6. 设备窗口清单（本批次无设备，以下不得猜测）

| # | 项 | 需要的窗口 | 关联 |
|---|---|---|---|
| W-1 | Play Console 对非 16KB 更新的**实际拦截行为**（告警 vs 拒审）与 targetSdk 36 延期表单可用性 | Play 开发者账号（非设备） | §1.2、§6.1 |
| W-2 | 目标设备（Android 17，12GB 档）MemoryLimiter 是否实施：`am memory-limiter status`；UML guest mem=1G/2G 前后台触限行为 | 设备 + adb/shell（Shizuku 路径验证） | §2.1 |
| W-3 | 扩展包在 HyperOS 目标机型可安装性复测 | 设备 | §6.8 |
| W-4 | 16KB 宿主设备上 UML/QEMU 车道运行（backcompat 模式启用与否、警告表现） | 16KB 宿主设备 | §1.3 |

**源码窗口（非设备）**：Safer Native DCL—C 在 ART/linker 的实现位置与精确射程（确认 execve / mmap PROT_EXEC 路径零波及）——阅读 AOSP art/bionic 源码即可闭合，无需设备。

---

## 7. 来源清单（全部访问日期 2026-10-31）

| 来源 | URL | 等级 | 取证方式 |
|---|---|---|---|
| 16KB 页大小要求（现行官方文档） | https://developer.android.com/guide/practices/page-sizes | [官方文档] | 直连全文抓取 |
| 16KB 截止澄清（官方社区管理员帖） | https://support.google.com/googleplay/android-developer/thread/368982598 | [官方文档（社区管理员转述博客）] | 搜索缓存逐字 |
| 16KB Console 文案引述（开发者帖 2025-08-28） | https://support.google.com/googleplay/android-developer/thread/368386720 | [社区帖引述 Console 文案] | 搜索缓存 |
| Play targetSdk 要求（帮助中心） | https://support.google.com/googleplay/android-developer/answer/11926878 | [官方文档] | 搜索缓存逐字（zh-Hans / zh-HK 两源一致） |
| Play targetSdk 要求（开发者文档镜像） | https://developer.android.com/google/play/requirements/target-sdk | [官方文档] | 搜索缓存逐字（zh-cn / zh-tw 两源一致） |
| Android 17 行为变更（all apps） | https://developer.android.com/about/versions/17/behavior-changes-all | [官方文档] | 直连全文抓取 |
| Android 17 行为变更（targetSdk 37+） | https://developer.android.com/about/versions/17/behavior-changes-17 | [官方文档] | 直连全文抓取 |
| Android 15 行为变更（安装下限 24） | https://developer.android.com/about/versions/15/behavior-changes-all | [官方文档] | 搜索缓存逐字 |
| Android 16 行为变更（无安装下限上调） | https://developer.android.com/about/versions/16/behavior-changes-all | [官方文档] | 直连全文抓取（否定性结论基于全文检索） |
| Memory Limiter（平台文档，含标准阈值表） | https://source.android.com/docs/core/perf/memory-limiter | [官方文档] | 直连全文抓取 |
| 本地网络权限 | https://developer.android.com/privacy-and-security/local-network-permission | [官方文档] | 直连全文抓取 |
| Play 开发者政策 · Device and Network Abuse（EN） | https://support.google.com/googleplay/android-developer/answer/9888379 | [官方文档] | 搜索缓存逐字 |
| Play 开发者政策 · 设备和网络滥用（中文） | https://support.google.com/googleplay/android-developer/answer/16559646 | [官方文档] | 搜索缓存逐字（与 EN 本及 storage.googleapis.com 政策全文快照三方一致） |
| AOSP sepolicy · seapp_contexts（main） | https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts | [官方源码] | 转储解码 |
| AOSP sepolicy · domain.te（main，execmod neverallow） | https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/domain.te | [官方源码] | 转储解码 |
| 华为《不收录类型》 | https://developer.huawei.com/consumer/cn/devservice/doc/50117 | [官方文档] | 搜索缓存逐字（站点 SPA，直连无正文） |
| 华为《部分类型应用注意事项》 | https://developer.huawei.com/consumer/cn/doc/50115 | [官方文档] | 搜索缓存逐字 |
| 华为《应用资质审核要求》 | https://developer.huawei.com/consumer/cn/doc/80301 | [官方文档] | 搜索缓存逐字 |
| 华为《应用审核指南》索引 | https://developer.huawei.com/consumer/cn/doc/50104 | [官方文档（索引）] | 搜索缓存 |
| drolin 仓库（GPL-2.0，Android 打包引擎） | https://github.com/fahrulseptiana/drolin | [一手仓库事实] | GitHub API 直查 |
| strykerapp 仓库（GPL-3.0，releases 分发 APK） | https://github.com/zalexdev/strykerapp + t.me/s/strykerapp | [社区] | GitHub API + 频道缓存 |
| linux-um-arm64 仓库 | https://github.com/zalexdev/linux-um-arm64 | [一手仓库事实] | GitHub API 直查 |

## 8. 本轮未验证项与停止条件执行记录

- **未验证**：Play Console 实际拦截行为（无账号，W-1）；Safer Native DCL—C 的 ART/linker 实现级射程（源码窗口）；华为以外国内商店逐家条款；F-Droid 发布政策细节；VMOS 在华为应用市场上架状态；strykerapp 的 GPL 对应源码交付完整性。
- **停止条件执行记录**：
  1. support.google.com / play.google.com 直连不可达 → 降级为搜索缓存逐字取证，来源与访问日期已标注，未强行裁决。
  2. 16KB 两口径**已收敛**（时间线演进解释成立，非真冲突）；Play Console 现状差异部分按并列标注处理，未裁决。
  3. 华为「模拟器明文禁令」检索无果 → 按 [未找到证据] 记录，未自造。
- 本文档由 fabric-batch1 Subagent B（平台政策分析员）产出；不执行任何 git add/commit/push，入库由用户处理。

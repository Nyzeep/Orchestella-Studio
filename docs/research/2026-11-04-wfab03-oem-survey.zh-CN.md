# W-FAB-03 · OEM 可用性普查——AOSP AVF 在主流 OEM 设备上的第三方可达性（零设备联网轮）

- 日期：2026-11-04（工单 W-FAB-03；ACR §8.2 缺项 5「OEM 普查」的联网取证半边，设备实测半边归真机窗口工单）
- 基线：`docs/research/2026-11-05-pvm-fabric-route.md` §2.4（W-FAB-03 定义与双轨判词）、`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md`（T13 负样本）、`docs/research/2026-11-02-platform-policy-matrix.md`（16K 政策日期与 Android 17 行为变更）、`docs/research/ACR-draft-four-lanes-20261029.md` §1.1/§7.2
- 证据纪律：四级置信【实测】（本项目真机，均引用仓库既有文档，本轮无设备）/【官方文档】/【社区证据】（含固件转储）/【推断】（必须标）；每条给来源 URL + 访问日期
- 商业公开设备使用真实型号名；本项目自有测试设备一律称 device-mey / device-myron，不写型号与序列号

**取证方式声明**：本轮于 2026-11-04 执行。github.com（discussions/仓库树）、androidauthority.com（3 篇）、club.honor.com（1 帖）、reddit（1 帖，经 reader 通道）**直连取全文**；source.android.com、developer.android.com、android.stackexchange.com、reddit（WebFetch 通道）**直连失败（超时/403）**，相关内容经搜索引擎缓存片段取证并逐字引述，与仓库 2026-11-02 文档 2026-10-31 直连抓取的同类页面互证。置信级按取证通道就低标注。

---

## 0. 一句话结论

截至 2026-11-04 的公开证据：**「Play 渠道第三方 App 在任何 AVF 设备上都没有 pVM 生产通道」判词无反例、无需修订**；AVF 管理面（AOSP `virtualizationservice`）在 Pixel、三星 One UI 8、小米 HyperOS 3 的 2025–2026 旗舰上在位（高通机型 protected-only、non-protected 缺位），但 **HONOR MagicOS 10 是「AOSP AVF 整体缺失、仅 vendor.qti.qvirt」的公开可复现反例**（与 T13 实测互证），轨道 B（non-protected）的现实设备面收敛为 **Tensor G1+ / Exynos 2500+ / Dimensity 9400+ 三条芯片线**（OPPO 对 MTK 机型仍缺 AVF），16K 宿主的公开通道**仅 Pixel 系开发者选项**——截至 Android 17 beta 无任何出货设备以 16K 为默认页大小。

---

## 1. 判定矩阵

### 1.0 三个维度先立标

- **强制基线**：Google 工程师口径「AVF is mandatory starting from Android 16」（Mishaal Rahman 转述，2025-02）；另有「Google 对 Android 15 起发布的新芯片组强制 AVF 支持」的转述（Samsung 社区帖引 Android Authority）。两者均指向**芯片组/新设备强制**，非全体存量设备强制——Galaxy S25（2025-02 发布）即未完整启用。【社区证据（转述官方）】https://x.com/MishaalRahman/status/1887535813811908846 ，访问 2026-11-04；eu.community.samsung.com 相关帖（见 1.2）
- **权限面（全 OEM 统一，无反例）**：`MANAGE_VIRTUAL_MACHINE` = signature|preinstalled|development（仓库官方源码判词，`docs/research/2026-11-05-pvm-fabric-route.md` §2.4，访问 2026-11-05 时点）；本轮复核：Termux 官方仓 2026-02 issue 明言该权限「not available to third-party apps」，全部社区 VM 工具（koiTerminal / U-AVF / Podroid）走 `adb shell pm grant` 开发通道。【社区证据（一手 issue）】https://github.com/termux/termux-app/issues/4960 ，访问 2026-11-04（搜索片段）→ **无反例确认**
- **非保护 VM 芯片白名单（Terminal 应用实证口径）**：仅「Tensor G1 及以后、Dimensity 9400+ 及以后、Exynos 2500」三类芯片支持 non-protected VM；Qualcomm 发言人原话：「we remain poised to support emerging requirements including non-secure use cases like the Linux Terminal, should market demand arise」（= 无计划）。【官方文档级（媒体实测+厂商发言人引语）】https://www.androidauthority.com/snapdragon-chips-android-linux-terminal-3608648 ，访问 2026-11-04（直连全文）

### 1.1 主矩阵（OEM × AVF × 阉割形态 × 16K × 置信 × 引用）

| OEM / ROM（版本） | AOSP AVF 管理面 | non-protected VM | 阉割/替换形态 | 16K page | 置信 | 关键引用 |
|---|---|---|---|---|---|---|
| **Pixel / Google**（Tensor，Android 13→17） | ✅ 全量（AVF 自 Android 13 落地 Pixel；Terminal 在营） | ✅（Podroid non-protected 自定义内核 VM 已交付在营） | 无 | ⚠️ 开发者选项：Pixel 8/8 Pro/8a（Android 15 QPR1+）、Pixel 9 Pro XL（17 QPR1 beta，时有时无）、Pixel 10（Tensor G5 支持但 **4K 默认**） | 【官方文档】+【社区证据】 | ①android-developers.googleblog.com/2023/12/virtual-machines-as-core-android-primitive.html ②developer.android.com/guide/practices/page-sizes（访问 2026-11-04，搜索片段；全文直连版本见仓库 2026-11-02 文档）③issuetracker.google.com/issues/441556099 ④Podroid（仓库 2026-11-05 文档 §2.4 已引 extv.github.io/Podroid/guide/backends.html） |
| **Samsung One UI 8**（S25/Fold7 = SD 8 Elite；Z Flip 7 = Exynos 2500） | ✅ 服务在位（Z Flip 7 Terminal 实证跑通 = 管理面完整；S25「cannot get virtualization access」为能力/feature 层缺位，非服务删除） | ❌ QTI 机型（8 Elite）/ ✅ Exynos 2500（**首台非 Pixel Terminal 机型**）；r/Android 口径：三星阵营仅 Exynos 2500/2600 + Tab S11 | 形态 F2（芯片能力缺位）+ 疑似 F3（Samsung「在具备能力的硬件上禁用该功能直到 One UI 8，或为修 Knox 不兼容」——媒体推测）；有社区帖称 S25 未声明 `android.software.virtualization_framework`（低置信） | ❓ 未找到公开证据 | 【官方文档级媒体实测】+【社区证据】 | ①androidauthority.com/galaxy-z-flip-7-linux-terminal-3578675 ②forbes.com/sites/paulmonckton/2025/01/31/samsung-galaxy-s25-series-omits-important-android-feature-report-claims ③eu.community.samsung.com/t5/galaxy-z-fold-z-flip/linux-terminal-still-not-working-on-stable-one-ui-8/td-p/13238621 ④eu.community.samsung.com/.../oneui-8-linux-terminal-not-working/td-p/13342290 ⑤androidheadlines.com/2026/03/samsung-one-ui-8-5-linux-terminal-improvements.html（One UI 8.5 计划扩展）——均访问 2026-11-04 |
| **Samsung Tab S11 / S11 Ultra**（Dimensity 9400/+） | ✅ | ✅（**首台 MTK Terminal 机型**；Android 17 下仍支持） | 无 | ❓ 未找到公开证据 | 【官方文档级媒体实测】 | androidauthority.com/galaxy-tab-s11-linux-terminal-3602231；sammyfans.com/2025/09/30/samsung-galaxy-tab-s11-can-now-work-like-a-real-linux-pc；sammyguru.com/android-17-linux-terminal-gets-better——访问 2026-11-04 |
| **Xiaomi HyperOS 3**（17 Pro = SD 8 Elite Gen 5；15T Pro = Dimensity 9400+） | ✅ 服务在位（媒体实测 Xiaomi 17 Pro：Terminal 报「non-protected VMs are not supported on this device」= 框架应答正常；系统属性 protected=true / non-protected 空） | ❌ QTI（8 Elite Gen 5 实测）/ ✅ MTK 9400+（15T Pro） | 形态 F2（芯片侧）；**qvirt vendor 栈与 AOSP AVF 并存**（固件转储见 §1.4） | ❌ 仓库实测 device-myron（HyperOS/Android 17）无 16K boot 通道；无 OEM 16K 公告 | 【官方文档级媒体实测】+【社区证据（固件转储）】+【实测（仓库）】 | ①androidauthority.com/snapdragon-chips-android-linux-terminal-3608648（Xiaomi 17 Pro 实测，直连全文）②babadook.keenetic.link/dumps/xiaomi/byron/...（HyperOS 3.0/Android 16 固件含 vendor.qti.qvirt-service.rc）③仓库 docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md §0 T6 行 |
| **OPPO / OnePlus ColorOS 16 / OxygenOS** | ⚠️ 组件被裁剪后部分恢复（维护者原话：「OPPO 系直接把一些组件给砍了（后面的更新加回来了）」；「OPPO 的 MediaTek 设备仍缺 AVF」，2026-08 时点） | ❌ OnePlus Pad 2（Dimensity 9400+）实测不可用（root 后直跑 crosvm protected VM 不稳定）；OnePlus 13T（SD 8 Elite）Gunyah 崩溃 | 形态 F3（Terminal App 砍除，「即使 Root 也不补全」）+ MTK 侧 AVF 缺位 | ❓ 未找到公开证据 | 【社区证据（一手实测帖）】 | github.com/lfdevs/run-linux-on-android-guide/discussions/1（直连全文，访问 2026-11-04） |
| **vivo OriginOS 6**（X300/iQOO 15，Android 16） | ❓ 未找到公开证据 | ❓（一条社区转述称 OriginOS 6 随 Linux Terminal 交付，本轮两轮独立检索均无法复现该说法） | ❓ | ❓ 未找到公开证据 | 【社区证据（未复现转述）】——置信最低 | github.com/lfdevs/run-linux-on-android-guide/discussions/1（搜索摘要转述层）；反证检索：中文两轮（关键词 OriginOS 6 + 终端/AVF/虚拟机）无果，访问 2026-11-04 |
| **HONOR MagicOS 10**（Magic7 Pro 等，Android 16，QTI） | ❌ **AOSP AVF 整体缺失**：`service list` 无 `virtualizationservice`，仅 `vendor.qti.qvirt.IVirtualizationService/default`（+vmtethering/virtualdevice） | ❌ | **形态 F1（服务级替换）**：AOSP 管理面删除，vendor qvirt 栈替代 | ❌（T12 not-testable，device-mey 4K 页） | 【实测（仓库 T13）】+【社区证据（公开互证）】 | 仓库 docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md §0/§5；club.honor.com/cn/thread-29707075-1-1.html（2025-09-27 用户帖：MagicOS 10 删除 Android 16 AVF/终端特性，官方仅走反馈通道无承诺；直连全文访问 2026-11-04） |
| **Huawei EMUI / HarmonyOS**（非 NEXT） | ❓ 无 AVF 公开证据 | ❓ | HarmonyOS NEXT 已移除 AOSP/Linux 内核代码（不兼容 APK，非本工单射程）；EMUI 存量机基于 Android 12 及以前内核，AVF 尚不存在 | ❓ | 【社区证据】+【推断】 | HarmonyOS NEXT 移除 AOSP：smzdm 2026-04 转述（搜索片段，访问 2026-11-04）；EMUI 无 AVF = 推断（平台代际决定，未找到反向证据） |
| **Motorola**（Hello UI，Android 15/16） | ❓ **未找到公开证据**（整行） | ❓ | ❓ | ❓ | 【未找到证据】 | 本轮两组检索（Motorola + AVF/Terminal/vm info）无任何命中，访问 2026-11-04 |
| **Nothing / CMF**（Nothing OS 3.x，Android 15/16） | ⚠️ 分化：Phone (3a)（QTI）`/dev/gunyah` 存在但「AVF 加载失败」、Terminal 开箱不可用；CMF Phone 2 Pro Terminal 失败；Phone (3) 有问题报告 | ❌（已报机型均失败） | 形态 F2 + 疑似 F1 混合（3a 的描述接近服务加载失败） | ❓ 未找到公开证据 | 【社区证据】 | nothing.community/d/29621-how-to-enable-the-terminal-app-help；reddit.com/r/NOTHING/comments/1pzcagr/；makeuseof.com/used-new-linux-terminal-on-android-impressed（评论提及 Phone (3)）——访问 2026-11-04（搜索片段） |

**阉割形态图例**：F1 = AOSP `virtualizationservice` 服务被删/由 vendor VM 栈替代（HONOR 实锤；Nothing 3a 疑似）；F2 = 服务在、芯片侧 non-protected 能力缺位（全量 QTI 旗舰）；F3 = Terminal/组件层裁剪（OPPO ColorOS 16、Samsung S25 疑似）。「service 在但 SELinux/vendor policy 拒第三方」形态（任务维度 1 的第二种）：**未找到公开证据**——社区实践里第三方触达失败均以 F1/F2/F3 或权限层解释，无一例指认 SELinux policy 级拒第三方。

### 1.2 AVF/virtualizationservice 可用性的两类型态（对应任务维度 1）

1. **「framework 类存在但 service 被删」**：HONOR 为公开可复现样本（T13 实测 + 荣耀社区帖，§1.1 行 7）。【实测+社区证据】
2. **「service 在但能力/政策拒第三方」**：QTI 旗舰普遍形态——小米 17 Pro 的报错来自 AVF 框架自身（「non-protected VMs are not supported on this device」），说明服务活着、是能力位关闭。【官方文档级媒体实测】
3. **平台侧方向**：Android 16 release 新增 AVF LL-NDK、提前启动 VM 等特性（source.android.com whatsnew，zh 镜像搜索片段）；Android 16 起 AVF 对新芯片组强制（§1.0）。【官方文档/转述】

### 1.3 pVM 权限面（任务维度 2）——反例排查结果

- 仓库判词（`docs/research/2026-11-05-pvm-fabric-route.md` §2.4）：AVF Java API 全部 @SystemApi；`MANAGE_VIRTUAL_MACHINE` = signature|preinstalled|development；`USE_CUSTOM_VIRTUAL_MACHINE` = signature|development（声明原文含「Not for use by third-party applications」）。【官方源码】
- 本轮独立复核**未发现任何反例**：Termux 2026-02 issue 确认第三方不可得；全部在营第三方 VM 应用（koiTerminal、U-AVF、Podroid、Kaliroid）均以 `adb shell pm grant` 开发通道交付。【社区证据】https://github.com/termux/termux-app/issues/4960 ，访问 2026-11-04
- 判定：**「第三方 Play 渠道在任何设备都拿不到生产通道」在全部已查 OEM 上成立，无 OEM 级放宽案例**。（本轮未重验 protectionLevel 源文件——直连 googlesource 该文件路径 404，沿用仓库 2026-11-05 官方源码判词；搜索摘要将其转写为 signature|privileged|development，语义同向：privileged 预装/签名 + adb development，均非 Play 生产通道。）

### 1.4 厂商替换形态：qvirt / Gunyah / GenieZone（任务维度 4）

- **Gunyah**（QTI Type-1 hypervisor，EL2）：与 AVF 集成支撑 protected VM（Qualcomm 2024-01/2024-08 博客口径）；当前全部骁龙旗舰（8 Gen 3 / 8 Elite / 8 Elite Gen 5）**仅 protected**——与仓库内部基线一致并升级为媒体实测级。【官方文档级媒体实测】androidauthority.com/snapdragon-chips-android-linux-terminal-3608648
- **qvirt**（QTI vendor 用户态 VM 栈，非 Gunyah 本体）：`vendor.qti.qvirt-service.rc`（+ `_rs` 变体）出现在小米 uke、Redmi onyx（HyperOS 2/Android 15）、小米 byron（HyperOS 3/Android 16）固件转储 product/etc/init；OnePlus 15R（sm8850-common，lineage-23.2）设备树列 `vendor.qti.qvirt-V2-ndk.so` / `libVirtClientInterface.so` / `libidv.so`。**在 device-mey（HONOR）上它是唯一 VM 服务；在小米 17 Pro 上与 AOSP AVF 并存**——qvirt 是新一代 QTI 固件的标配 vendor 组件，是否遮蔽 AOSP 管理面由 OEM 决定。【社区证据（固件转储一手）】babadook.keenetic.link/dumps/{xiaomi/byron,xiaomi/uke,redmi/onyx}/...；github.com/OnePlus-15R-Development/android_device_oneplus_sm8850-common/blob/lineage-23.2/proprietary-files.txt，访问 2026-11-04（搜索片段）
- **MTK GenieZone（/dev/gzvm）**：Tab S11（Dimensity 9400+）Terminal 跑通 = AVF-over-GenieZone 在三星 MTK 设备上框架级可用（比仓库既有 W-FAB-05「待核实」前进一格）；但同一芯片在 OPPO（OnePlus Pad 2）上 AVF 缺位——**GenieZone 在不等于 AVF 暴露**的既有判词保持成立。【官方文档级媒体实测】+【社区证据】
- **Samsung TEE 系**：媒体推测 One UI 8 前禁用 Terminal「或为修 Knox 不兼容」；未找到 Samsung 官方说明。【社区证据（推测转述）】
- **root 免 flag 的 QTI protected VM 通道（旁证）**：polygraphene/gunyah-on-sd-guide 实证在 Lenovo Legion Y700 gen4（SD 8 Elite、Android 15、root）上 `crosvm --protected-vm-without-firmware` 走 /dev/gunyah 可启动 Linux guest（需 `ulimit -l unlimited` + magiskpolicy 放行）——证明 QTI protected 硬件面在，仅缺框架/权限面。【社区证据（一手指南）】github.com/polygraphene/gunyah-on-sd-guide，直连全文访问 2026-11-04

### 1.5 16K page（任务维度 3）

| 项 | 判定 | 证据 |
|---|---|---|
| 出货默认 16K 设备 | **0 台**（截至 Android 17 beta，2026-03 报道口径：「4KB is still the default on my Pixel running the beta and 16KB is a hidden developer opt-in」） | androidauthority.com/android-page-sizes-explained-3644598（直连全文，2026-11-04） |
| Pixel 开发者选项机型 | 「Boot with 16 KB page size」：Pixel 8/8 Pro/8a（Android 15 QPR1+）；Pixel 9 Pro XL（Android 17 QPR1 beta，时有时无）；Pixel 10/10a（Tensor G5 支持但 4K 默认，需开发者选项或 fastboot，官方文档缺指引） | 同上 + developer.android.com/guide/practices/page-sizes（访问 2026-11-04 搜索片段）+ issuetracker.google.com/issues/441556099 |
| Android 17 平台侧 | 稳定版 2026-06；模拟器 16K 默认；新增「对每个 App 强制关 16K backcompat」能力（官方文档 2026-10-16 更新版口径）；默认切换预计不在 Android 17 到来 | 同上 + goyaljai.medium.com（AOSP 17 构建）+ r/android_beta 讨论（社区对默认切换持怀疑） |
| Play 侧 | targetSdk 35+ 16KB 提交义务 2025-11-01 起；更新硬截止 2027-02-01（时间线细节以仓库 2026-11-02 文档 §1 为权威） | 仓库 docs/research/2026-11-02-platform-policy-matrix.md §1.1 |
| 非 Pixel OEM | **未找到任何 OEM 16K 适配公告**（Samsung/Xiaomi/OPPO/vivo/HONOR 全查无果）；仓库实测 device-myron（HyperOS/Android 17）与 device-mey（MagicOS/Android 16）均 4K 且无 16K boot 通道 | 两组检索无果，访问 2026-11-04；仓库 2026-11-07/11-08 文档 T6/T12 |
| 对「4K 页设备无 16K 通道」既有判词 | **升级**：从单机判定升级为平台级口径——非 Pixel OEM 截至当前无公开 16K 通道，16K 宿主窗口只能来自 Pixel 系开发者选项【推断（平台口径 + 双机实测一致）】 | 本文判定 |

---

## 2. 对轨道 A / 轨道 B 判词的影响

- **轨道 A（protected VM / AOSP AVF 管理面）——判词不修订，覆盖面上修**：管理面在 Pixel、Samsung（双 ISA）、Xiaomi（QTI+MTK）2025–2026 旗舰在位，「mandatory from Android 16」意味着覆盖率趋势向上；HONOR（F1 型态）是唯一已实锤的「整体缺失」主流厂商。生产通道为零的判词维持（§1.3 无反例）；轨道 A 的开发通道（adb/Shizuku grant）设备面比上一轮认知更宽——QTI 旗舰（Gunyah protected-only）也可走 protected Microdroid worker，而非只有 non-protected 设备才可用。
- **轨道 B（non-protected 自有内核 VM / UML 不受影响；non-protected pVM 收紧到三条芯片线）**：Tensor G1+ / Exynos 2500+ / Dimensity 9400+ 之外（含全部现役 QTI 旗舰）无 non-protected 通道，且 Qualcomm 无计划；OEM 层另有 OPPO 砍组件、HONOR 砍框架。轨道 B 的 pVM worker 现实硬件面 = Pixel 系 + Samsung Exynos 机型 + Tab S11 系 + MTK 旗艦（各 OEM 逐一验证 AVF 暴露）。**UML/自有内核作为不依赖厂商放行的第三面，其「不受 OEM 阉割矩阵影响」的定位被本轮证据反向加强**（9 个 OEM 行中 5 行是 ❓/❌，唯一全绿行是 Pixel 且仍仅开发通道）。
- **16K 判词**：ACR §3.4「16K 宿主 + 4K guest」验证项的设备窗口锁定为 Pixel 8+/10 系开发者选项；「等一台 16K 宿主」在非 Pixel 渠道无近期解。【推断】

## 3. 与仓库既有负样本的互证

| 仓库样本 | 公开证据互证 | 结论 |
|---|---|---|
| **T13（device-mey，MagicOS/Android 16）**：`service list` 无 AOSP `virtualizationservice`，仅 `vendor.qti.qvirt.IVirtualizationService/default` | 荣耀俱乐部官方论坛帖（2025-09-27，Magic7 Pro/MagicOS 10）用户指认 MagicOS 10 删除 Android 16 AVF/终端特性，官方仅反馈通道无恢复承诺（64 人投票五五开）——**独立公开证据与仓库实测同向** | HONOR = F1 型态成立，且非孤例悬案：有公开社区指认 + 无官方否认 |
| **T13 对照事实**：小米 17 Pro（HyperOS 3，同为 QTI）AVF 框架应答正常（protected=true / non-protected 空） | 说明 QTI 阵营内 OEM 分化：小米保留 AOSP AVF 管理面（与 qvirt 并存），HONOR 删面留 qvirt——「vendor.qti.qvirt 存在」不能反推「OEM 阉割了 AVF」，必须逐 OEM 看 | W-FAB-03 普查的判据应保持「service list + getCapabilities()」双探针，qvirt 出现与否仅作辅助 |
| **T6（device-myron，HyperOS/Android 17）**：无 16K boot 选项 | AA 2026-03 报道「无出货设备 16K 默认」+ 非 Pixel OEM 无 16K 公告 | 「4K 页设备无 16K 通道」从单机事实升级为平台口径（§1.5） |
| **内部基线「Gunyah protected-only」**（仓库 2026-11-05 文档） | AA 全文实测（8 Gen 3/8 Elite/8 Elite Gen 5）+ Qualcomm 发言人引语 + 安兔兔中文报道同口径 | 基线升级：内部基线 → 媒体实测级（protected-only 至 2026-11 无变化） |

## 4. not-verified 清单（如实登记，未取得证据不判定）

1. **所有矩阵格的设备实测**（本轮零设备）：`service list` / `getprop ro.boot.hypervisor.*supported` / `getCapabilities()` 五点探针仍需真机窗口（W-FAB-03 设备半边）。
2. **vivo OriginOS 6**：「随 Linux Terminal 交付」的社区转述未能复现（两个独立检索无果）——整行 ❓，置信最低。
3. **Motorola**：整行无任何公开证据（未找到 ≠ 不支持）。
4. **Samsung QTI 机型 AOSP 管理面在位与否**：仅能从 Terminal 报错反推，未取得 S25 上 `virtualizationservice` 注册的直接证据；「S25 未声明 virtualization_framework feature」为单一低置信片段。
5. **One UI 8.5 Terminal 扩展**的实际范围与 QTI 机型的 non-protected 是否随行解锁（2026-03 报道为计划口径）。
6. **OnePlus 15 / 15R（8 Elite Gen 5，OxygenOS 16）** 的 Terminal/AVF 状态：未检索到实测帖。
7. **Xiaomi Pad 7 系「内置 Linux 终端」**：单一社区讨论提及，机型/通道（AVF 还是小米自研）未核实。
8. **Pixel 11 / Tensor G6 的 16K 默认**：仅 Reddit 猜测层。
9. **qvirt 在 HONOR 上对第三方 App 的可达性**：vendor AIDL 稳定性与 vendor SELinux policy 未验（决定 vendor 栈能否成为替代通道，倾向不可用但未取证）。
10. **HONOR 是否在后续 MagicOS 恢复 AVF**：官方无承诺；「mandatory from Android 16」与该反例如何收敛（豁免、罚款还是补齐）无公开信息。
11. **CDD 条款级原文**（AVF 强制性的准确 CDD 章节/措辞）：今日 source.android.com 直连超时，仅取得转述级证据；引用建议标注「转述待源文核」。
12. **「SELinux/vendor policy 拒第三方」型态**：未找到公开指认（§1.1 图例说明）——按停止条件记为未找到证据，不脑补。

## 5. 来源清单（全部访问日期 2026-11-04）

| # | 来源 | 等级 | 取证方式 |
|---|---|---|---|
| 1 | https://www.androidauthority.com/snapdragon-chips-android-linux-terminal-3608648 | 官方文档级媒体实测（含 Qualcomm 发言人引语） | 直连全文 |
| 2 | https://www.androidauthority.com/galaxy-z-flip-7-linux-terminal-3578675 | 同上 | 直连全文 |
| 3 | https://www.androidauthority.com/android-page-sizes-explained-3644598 | 媒体实测（2026-03-03） | 直连全文 |
| 4 | https://github.com/lfdevs/run-linux-on-android-guide/discussions/1 | 社区证据（一手实测帖） | 直连全文 |
| 5 | https://github.com/polygraphene/gunyah-on-sd-guide | 社区证据（一手指南） | 直连全文 |
| 6 | https://club.honor.com/cn/thread-29707075-1-1.html | 社区证据（荣耀官方论坛，2025-09-27） | 直连全文 |
| 7 | https://www.reddit.com/r/samsunggalaxy/comments/1rf4dgi/ | 社区证据 | reader 通道 |
| 8 | https://x.com/MishaalRahman/status/1887535813811908846 | 社区证据（转述官方） | 搜索片段 |
| 9 | https://github.com/termux/termux-app/issues/4960 | 社区证据（一手 issue，2026-02） | 搜索片段 |
| 10 | https://babadook.keenetic.link/dumps/xiaomi/byron/-/tree/missi-user-16-BP2A.250605.031.A3-OS3.0.302.0.WAFCNDM-release-keys/product/etc/init （及 uke/onyx 两 dump） | 社区证据（固件转储一手） | 搜索片段 |
| 11 | https://github.com/OnePlus-15R-Development/android_device_oneplus_sm8850-common/blob/lineage-23.2/proprietary-files.txt | 社区证据（设备树一手） | 搜索片段 |
| 12 | https://developer.android.com/guide/practices/page-sizes | 官方文档 | 搜索片段（全文直连版本见仓库 2026-11-02 文档，访问 2026-10-31） |
| 13 | https://issuetracker.google.com/issues/441556099 | 官方追踪器 | 搜索片段 |
| 14 | https://eu.community.samsung.com/t5/galaxy-z-fold-z-flip/linux-terminal-still-not-working-on-stable-one-ui-8/td-p/13238621 ；https://eu.community.samsung.com/t5/mobile-apps-services/oneui-8-linux-terminal-not-working/td-p/13342290 | 社区证据（Samsung 官方社区） | 搜索片段 |
| 15 | https://www.forbes.com/sites/paulmonckton/2025/01/31/samsung-galaxy-s25-series-omits-important-android-feature-report-claims | 媒体 | 搜索片段 |
| 16 | https://www.androidheadlines.com/2026/03/samsung-one-ui-8-5-linux-terminal-improvements.html | 媒体（计划口径） | 搜索片段 |
| 17 | https://www.androidauthority.com/galaxy-tab-s11-linux-terminal-3602231 ；https://www.sammyfans.com/2025/09/30/samsung-galaxy-tab-s11-can-now-work-like-a-real-linux-pc ；https://sammyguru.com/android-17-linux-terminal-gets-better | 媒体实测 | 搜索片段 |
| 18 | https://nothing.community/d/29621-how-to-enable-the-terminal-app-help ；https://www.reddit.com/r/NOTHING/comments/1pzcagr/ ；https://www.makeuseof.com/used-new-linux-terminal-on-android-impressed | 社区证据 | 搜索片段 |
| 19 | https://www.antutu.com/doc/135454 | 媒体（中文口径） | 搜索片段 |
| 20 | https://android-developers.googleblog.com/2023/12/virtual-machines-as-core-android-primitive.html ；https://source.android.com/docs/whatsnew/android-16-release | 官方博客/文档 | 搜索片段 |
| 21 | 仓库互证：`docs/research/2026-11-05-pvm-fabric-route.md` §2.4/工单表；`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §0/§5；`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §0 T6；`docs/research/2026-11-02-platform-policy-matrix.md` §1；`docs/research/ACR-draft-four-lanes-20261029.md` §1.1/§7.2 | 【实测】/【内部基线】/【官方源码】 | 仓库文件 |

---

*本文档由 W-FAB-03 工单（零设备联网轮）产出；不执行任何 git 操作，不改仓库其它文件。*

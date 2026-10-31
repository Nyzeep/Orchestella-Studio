---
title: UML 方向调研与下一步建议（四子智能体联网调研 × 会话 21–23 现状合并）
date: 2026-10-31
status: research
scope: 调研综合与方向建议；不改 CONTEXT.md/ADR/代码，不构成实施承诺。ACR 投票后才具备效力。
method: 内部文档基线（docs/research/ 会话 21–23，2026-10-29）+ 2026-10-31 四路并行联网调研（上游 / 下游生态 / 平台策略 / 竞品替代）+ Play 政策专项补查。
evidence: [官方事实] 官方文档/源码/策略；[社区自述] 第三方仓库/社区基准，未独立复现；[本项目实测] 本仓库真机证据（会话 21–23）；[未核实] 检索不到或不可读。
---

## 0. 结论摘要

**推荐方案一句话：不要把「UML 产品化」当作新的主攻方向。把开发重心放在「targetSdk 36 主包收敛 + 四车道 ACR 落地」（三个小改动即可解锁当前产品），UML 维持时间盒研究线——补齐「长驻稳定性 / Docker / 链接」三块后再由 ACR 决定是否开集成；UML 的编译角色维持撤回。**

四条支撑：

1. **UML 的两个原始卖点，一死一窄。** 「替代 QEMU 编译」已被本项目真机数据撤回（2.04×，会话 22）；「现代 App 跑不了」被证伪后（会话 21 三探针全过 + 本轮源码闭环），UML 真正独占的价值只剩「≈330ms 快启动的真内核 + Docker 候选」，而这块有三块关键能力未验证（30min 长驻、D9 Docker、链接）。在验证前投入集成开发是赌注。
2. **36 域收敛是已验证、小改动、高回报的主线。** 包可见性修复（一行 manifest）+ Landlock 规避 + AVF 服务排查，就能把当前产品推进到现代 targetSdk 形态；且 Play 的 targetSdk 36 硬期限（2026-08-31 起新应用/更新强制，[官方事实]）为 ACR 的 targetSdk 选项补上了此前缺失的硬数据。
3. **上游不可依赖。** arm64 UML 不会被 mainline 接收：RFC 之后无 v2，UML 维护者 Richard Weinberger 明确回复「Patches are welcome, but don't expect others to do the hard work」（2026-08-16，[官方事实]）。产品化 = 自担 fork 维护，这个成本只有在 UML 三块验证通过后才值得付。
4. **三车道结论被外部证据强化。** 高通到 8 Elite Gen 5 仍 protected-only、AVF 无放宽；Podroid（3.5k+ stars）复刻的正是 QEMU TCG 车道，证明「能跑 Linux VM」已不是差异化，差异化在生命周期/多实例/桌面/GPU 与发行版管线。

## 1. 项目内部事实基线（会话 21–23，2026-10-29；比两份 10-28 文档新）

本轮调研的起点不是 10-28 的两份探究文档，而是它们之后的三个真机会话。以下为已入库事实（来源均为 `docs/research/2026-10-29-*.md`）：

| # | 事实 | 状态 | 来源 |
|---|---|---|---|
| 1 | **D1 判定翻转**：targetSdk 36 域（`untrusted_app_34`）三探针全过（filesDir RWX mmap / 匿名 exec / memfd exec）——10-28 文档预测全败，实测全过 | [本项目实测] | 会话 21 §4 |
| 2 | **UML 内核在 App 域启动成功**：kernel 7.2.0-rc4，Android 17，`UMARM_BOOT_OK`；启动中位 ≈330ms（vs QEMU 分钟级） | [本项目实测] | 会话 21 §5 |
| 3 | **UML 真实编译 = 2.04×**（940ms vs native 460ms，同二进制同夹具，产物逐字节相同）→ 落 1.5–3× 区间，**编译定位撤回**，改为「长驻/Docker 归属」；「compute 1.08× 不可外推到编译」（归因：页错误密集负载每个 guest 页错误落到宿主） | [本项目实测] | 会话 22 §5 |
| 4 | **QEMU 车道 36 域条件式解封**：`libgvproxy.so`/`libdroidlayer_qemu.so` 从 `nativeLibraryDir` exec 成功、执行类 AVC=0、guest systemd 到 root shell；前提 `useLegacyPackaging=true`（主包不带 payload）。guest 出网已验证；装配卡在 guest 内核无 Landlock（pacman 7 沙箱），可修 | [本项目实测] | 会话 22 §4、会话 23 §2 |
| 5 | **pVM 车道设备面阻断**：`virtualizationservice` 未注册（HyperOS 设备 537 服务中无此服务）→ `ACCELERATOR_UNAVAILABLE` 回落 QEMU；缺的是服务不是权限 | [本项目实测] | 会话 23 §3/§4、§7.1 |
| 6 | **包可见性缺陷**：`<queries>` 未声明扩展包，targetSdk ≥30 包可见性过滤生效 → 36 域下 `AccelExtensionResolver` 恒判 `EXTENSION_MISSING`；两包拆分在 36 域运行期失效。修复方向：manifestPlaceholders 注入扩展包名（一行级） | [本项目实测] | 会话 23 §3.2、§7.2 |
| 7 | **四车道 ACR 草案已成文待投票**：`ACR-draft-four-lanes-20261029.md`（含选项 A–D、撤销条件、回退路径、文案纪律） | draft | 会话 23 §5 |

**含义**：10-28 文档的 go/no-go 框架（Z1+D1 → ACR）已被执行过半——D1 已过，ACR 材料已备；当前卡点不再是「UML 能不能跑」，而是「UML 跑起来之后值不值」与「主包 targetSdk 形态」。

## 2. 本轮联网调研结论（2026-10-31）

### 2.1 R1 · 上游状态：不接收，且不要再等

- mainline `arch/um/Makefile` 仍只映射 x86/x86_64/i386；`arch/arm64/um` 不存在（404）。[官方事实]
- 2026-08-15 RFC 之后**无 v2、无 review 结论、未合入**；uml/next 的 7.4 pull（2026-10-22，38 commits）不含 arm64 提交。UML 维护者唯一回复（Richard Weinberger，2026-08-16）：「Patches are welcome, but don't expect others to do the hard work」。[官方事实]
- UML SMP 已进 mainline（`1e4ee5135d81`）但 x86-only；arm64 UML 无 SMP 可言。[官方事实]
- mainline stub 现状：`memfd_create(MFD_EXEC)` 失败自动回退 tempfile，**无 `stub_exe=` 参数**（该参数是树外移植的）；physmem 仍 `check_tmpexec()` + tempfile 落盘；guest 页仍 4K only。[官方事实]
- 注意一项新上游变化：`um: Tighten exec permissions on page mappings`（f42c887，Tiwei Bie）已合入——树外移植跟进 rebase 时需评估其影响。[官方事实]

**结论**：「等 mainline」从计划选项中排除；fork 是唯一路径，安全维护责任自担。

### 2.2 R2 · 下游生态：比 10-28 基线更活跃，关键缺口收窄一半

| 仓库 | 最新活动 | 要点 |
|---|---|---|
| zalexdev/linux-um-arm64 | 2026-10-17 | 5 新 commit（含 7.2 in-tree 移植、rtl8xxxu）；基线仍 7.2-rc4+38 commits；作者虽声明不维护但仍在提交 [社区自述] |
| Raymer8639/linux-um-arm64 | 2026-10-23 | v0.2.0/0.2.1 带设备门证据（IPv4/IPv6/soak）；PR #22 cgroup2 挂载 + 内核瘦身 136→13 MiB [社区自述] |
| michaelamoetoevsky/uml4termux | 2026-10-30 | 冷快照/恢复、威胁模型文档、soak 证据生成器；Termux 打包 PR 进行中 [社区自述] |
| fahrulseptiana/drolin | 2026-10-24 | **targetSdk 35 独立 Kotlin APK**（build 脚本 `--target-sdk-version 35` 实证），内核钉 **7.2.12 stable**；UML 内核以 `liblinux.so` 走 jniLibs→nativeLibraryDir [社区自述] |
| AAQWQ11/umnetx（新） | 2026-10-01 | 用户态网络栈；自述 Termux 下 passt 因 SELinux 拒 `NETLINK_ROUTE` bind 不可用（与其他 fork 自述矛盾，环境差异未仲裁）[社区自述] |

- **缺口 A（现代 targetSdk App 跑 UML）**：出现首个候选答案——drolin（targetSdk 35 APK）。实现与本项目会话 21/22 的 jniLibs 路线同构，方向互证；但其 guest RAM 后备细节未说明，仍是自述，不能替代本项目实证。[社区自述]
- **缺口 B（16K 宿主）**：全生态**零实测**。所有已记录宿主（Poco F3 / S26 Ultra / K90 Pro Max）均 4K host；「16K」全部指 guest 页。[未核实]
- 生态新事实：Alpine 4K 对齐二进制可在 16K guest 正常运行（本项目会话 22 亦独立实证）。[本项目实测]

### 2.3 R3 · 平台策略：10-28 文档的核心矛盾已从源码闭环

- **`app_data_file execute` 矛盾解开**：`private/untrusted_app_all.te:27` 明文 `allow untrusted_app_all app_data_file:file { r_file_perms execute };`（:28 另有 auditallow）。b/237289679 注释所指的「向后兼容」就是这个 allow。与本项目会话 21 三探针全过互相印证。[官方事实]
- **W^X 边界精确化**（三者不可混）：
  - `execute`（文件 mmap PROT_EXEC）：**允许**（上条）；
  - `execute_no_trans`（execve 数据目录文件）：**neverallow**（targetApi ≥ 29）；
  - `execmod`（可写文件执行映射）：**neverallow**（现代 App 全体，仅豁免 25/27，`private/domain.te:1344-1351`）。
  - 对 UML 的直接含义：`check_tmpexec` 门通过（已实证）；stub 必须 `stub_exe=` 走 nativeLibraryDir（已实证）；**内核 .so 与 rootfs 在运行期不可「先写后执行」**——ext4 只读镜像通道与产品形态一致。
- **`vm.memfd_noexec`**：AOSP main 无任何 setter，默认 0（MFD_EXEC 放行）；memfd 可 mmap exec 但不可 execve（appdomain_tmpfs 无 execute_no_trans）→ 与 stub_exe 结论一致。[官方事实]
- **seccomp**：main / android16-release / android17-release 的 blocklist **逐字节一致**，不含 ptrace/process_vm_* → 10-28 文档担心的收紧不存在。[官方事实]
- **16 KB 页**：targetSdk 35+ 应用 **2027-02-01 起** Play 更新必须兼容 16K（developer.android.com/guide/practices/page-sizes，[官方事实]）；Pixel 8/9 系有 16K boot 开发者选项；Android 16 提供 4K→16K backcompat 模式。**「Android 16 起新设备强制 16K」未找到官方出处** [未核实]。
- b/237289679 正文需登录，不可读 [未核实]。

### 2.4 R4 · 竞品与替代路线：三车道结论强化，两个观察项

- **高通 protected-only 延续**：至 8 Elite Gen 5 仍仅 protected VM；Gunyah 实现受 XBL 签名锁定，解锁 bootloader 也不能换固件。S26 Ultra 同样不支持 non-protected。[社区自述（技术实测）]
- **AVF 上游无放宽**：custom_vm.md 仍要求 rooted + adb root 流程；无 non-root 自定义 VM、test key、文件标签的放宽。[官方事实]
- **联发科观察项**：GenieZone（`/dev/gzvm`）自述支持非内存保护 VM（天玑 9000 起，Android 16 Terminal 可跑 Debian）；天玑 9500 未核实。若确认，「自有内核 + 硬件 vCPU」在 MTK 机型出现非默认交付形态（需用户一次性 adb grant）——不改三车道，但可能给 MTK 机型加可选加速分支。[社区自述 / 部分未核实]
- **Podroid（GPL-2.0，3.5k+ stars）** 复刻 QEMU TCG 车道（无 Root Alpine VM + Docker/LXC + X11），protected-only 设备自动回退 QEMU → 本项目 QEMU 车道的差异化必须落在生命周期/多实例/Wayland 桌面/VirGL 与发行版管线上。[官方事实（项目文档）]
- Android 16 官方 Terminal（Debian VM + VirGL）已在 Pixel 与部分 MTK/Exynos 设备可用 → 平台在吃掉一部分基础场景。[官方事实]
- Termux 官方维持 targetSdk ≤28（Play 版冻结弃更）；proot-rs 仍 WIP。[官方事实]
- gVisor on Android：未发现任何 App 域移植或可行性讨论。[未核实]

### 2.5 专项补查 · Play targetSdk 时间线（闭环 ACR §6.1 未取数第 1 项）

- **自 2026-08-31 起**（已生效）：新应用和应用更新必须 targetSdk ≥ **36** 才能提交 Google Play（Wear OS/ATS ≥35，TV/XR ≥34）；可申请延期至 **2026-11-01**。[官方事实：support.google.com answer 11926878]
- targetSdk ≤ 34 的应用对新用户只能在「设备 OS ≤ 其 target API」上可用；≤28 本来就不能上 Play。[官方事实]
- **含义**：若走 Play，主包 36 不是选择题（ADR-0003 倾向 GitHub/F-Droid，故对当前渠道影响有限，但它是 ACR 选项 A–D 投票的硬前置数据）。**28 域在 OS 层的失效日期仍无官方时间表**（维持未取数）——proot 车道存续依旧是有风险的赌注。

## 3. 可选 UML 方案对比

### 3.1 角色对比（四车道视角，接 ACR 草案 §1）

| 车道 | 定位 | 证据状态（2026-10-31） | 本轮新增信息 |
|---|---|---|---|
| **QEMU Full** | 默认车道：完整契约 + GPU 路线 | 36 域条件式解封；Landlock 可修 | Play 36 期限已生效；Podroid 证明该车道是行业标准做法，护城河在工程深度 |
| **pVM Fast** | 编译/工具卸载 | 28 域链路已验证；本机 AVF 服务缺失 | 高通 protected-only 至 Gen 5；MTK GenieZone 观察项 |
| **proot/bare** | Userspace Compatibility | 28 域专用；36 域结构性消失 | 28 失效日期仍无官方来源 |
| **UML Native** | **长驻/容器候选（编译角色已撤回）** | 启动 + 单文件 `-c` 编译已实测；长驻/Docker/链接/SMP 未验证 | 生态活跃 + drolin 互证 + 上游不接收（自担 fork 成本确定化） |

### 3.2 实现基线对比（若 ACR 通过产品化，需裁定的一条）

| 路线 | 基线 | 优势 | 劣势 | 适用 |
|---|---|---|---|---|
| **A. 沿用现用下游 release**（kernel 7.2.0-rc4，会话 21/22 已实证） | zalexdev 系 v0.3.0 `linux-bionic` | 与全部已有真机证据连续，零切换成本 | 7.2-**rc** 预发布基线；作者声明不维护 | 研究线延续（默认） |
| **B. Raymer8639 fork** | Android/Termux 工程化 fork | 证据工程文化活跃（soak/门禁），内核瘦身成果（136→13 MiB） | Termux 域导向，需裁剪适配 App 域 | 工程化参考与补丁来源 |
| **C. drolin 线** | kernel **7.2.12 stable** + targetSdk 35 APK | 唯一「stable 基线 + 现代 targetSdk APK」先例；基线比 A 新 | 单人项目、成熟度低、guest RAM 细节未公开 | 现代 App 域集成与 stable rebase 参考 |
| **D. 回移补丁到项目 pinned 内核** | 项目 ADR-0005 契约 | 满足「单一 pinned 版本」契约 | 33+ 补丁移植 + 持续维护，上游补丁仍在动 | 仅当 ACR 强制统一内核基线 |
| **E. 等 mainline** | — | — | 维护者明确不接（R1） | **排除** |

### 3.3 适用场景（按已实证边界）

- **适合 UML**：快启动（≈330ms）真内核长驻场景——终端、常驻服务、Docker（候选，未验证）；进程创建密集型（fork+exec 1.49×）。
- **不适合 UML**：编译/构建（2.04×，已撤回）；syscall 密集型（`apk add`/`npm install` 类，8×）；GPU（UNSUPPORTED）；16K 宿主设备（全生态未验证）；任何需要硬件隔离承诺的场景（进程级隔离，按 ADR-0010 L1 措辞）。

## 4. 推荐方向及理由

**主方向：「targetSdk 36 主包收敛 + 四车道 ACR 落地」。UML 维持时间盒研究线（第二优先），按 §5 的三块清单推进；补齐后才谈集成。**

理由：

1. **投入产出比**：主方向上的三个改动（包可见性、Landlock、AVF 排查）都有明确的「已验证根因 → 已知修法」，完成后当前产品直接获得现代 targetSdk 形态；而 UML 集成的三个前置验证（长驻/Docker/链接）结果未知，任何一行失败都让集成工作沉没。
2. **决策窗口**：ACR 草案已备齐，且本轮补上了两个硬日期（Play 36 期限已生效、16K 兼容期限 2027-02-01）——拖着不投，28 域失效风险和 16K 适配风险都在累积。
3. **UML 的真实价值需要三块数据才能定价**：30min 长驻稳定性、D9 Docker、链接阶段。这三块是「长驻/容器车道」定位的最低验证集，也是 ACR §3.4 明确要求的。验证用现成 runner（`research/uml-runner/`）即可，无需产品代码。
4. **上游成本已确定化**：R1 证实 mainline 不接收，产品化 = 项目自担 fork（含安全跟进、stable rebase）。这个成本是否值得付，只有在三块验证给出「长驻场景可用」的答案后才可评估。

> 若你决定仍以 UML 为主攻方向（而非主方向之上的研究线）：直接执行 §5 P1 第 5 条的三块验证时间盒作为第一步，通过即开 ACR + 集成 spec——这条路径与上述推荐**只差优先级排序，不冲突**。

## 5. 后续实施步骤（按性价比排序）

**P0 · 零设备/小改动（可立即做）**

| # | 事项 | 判据 |
|---|---|---|
| 1 | **ACR 会话**：四车道草案投票。输入已齐：ACR-draft-four-lanes-20261029.md + 本文档（Play 36 期限、16K 2027-02-01、untrusted_app_all.te:27 闭环、上游不接收） | 四车道定位 + targetSdk 选项 A–D + 文案纪律（ADR-0001/0003「硬件加速」措辞清理）形成决议 |
| 2 | **修包可见性**：`<queries>` 按渠道注入扩展包名（manifestPlaceholders） | 36 域设置页 pVM 卡显示「扩展包就绪」 |
| 3 | **Landlock 裁定**：三选一——rootfs 预置 `DisableSandbox` / guest 内核补 `CONFIG_SECURITY_LANDLOCK` / 装配改 `pacman -S --needed`（建议组合：预置 + 改定点安装） | 36 域 QEMU 装配跑完 `package-install` 终态 |

**P1 · 设备窗口（下一轮串行）**

| # | 事项 | 判据 |
|---|---|---|
| 4 | **AVF 服务排查**：`virtualizationservice` 未注册是 ROM 级缺失还是可唤起（比对 `vendor.qti.AvfQcvmManager` 路径） | pVM 行按「设备面不可用」重写，或确认可唤起路径 |
| 5 | **UML 时间盒第二轮**（用 `research/uml-runner/`，全部同轮同夹具）：① 30min 长驻稳定性（ext4/ubd0 通道）；② D9 Docker（dockerd + overlay2 + cgroup v2 + hello-world）；③ 链接阶段（ld/lld，不只 `-c`）；④ SMP 内核试跑 | 三块全过 → UML 进 ACR 的「长驻/容器车道」投票；任一不可恢复失败 → 负结果归档 |
| 6 | **16K 宿主探针**（若可借 Pixel 16K boot 模式设备）：UML 16K guest on 16K host + QEMU 二进制形态核查 | 全生态空白项；做出即首个数据点，直接支撑 Play 16K 期限应对 |

**P2 · ACR 通过后**

| # | 事项 | 流程 |
|---|---|---|
| 7 | UML 集成（若投票通过）：执行策略 `Native Kernel (UML)` 进能力矩阵与 Lane Router；GPL 对应源码交付物与独立 APK 形态评估（ADR-0014 边界先过法务） | `/grill-with-docs` 收敛契约 → `/to-spec` → `/to-tickets` → `/implement` |
| 8 | UML 不通过：负结果归档（本文档 + 会话 21–23 证据保留），研究线挂起 | 不改 CONTEXT.md |

## 6. 待确认风险点

| # | 风险 | 状态 | 缓解/确认方式 |
|---|---|---|---|
| 1 | **arm64 UML 无上游维护者**：mainline 不接收，安全跟进全自担 | 已确定（R1） | 基线选型在 ACR 裁定（§3.2 表）；rebase 跟随 7.2.x stable（drolin 已示范 7.2.12）；评估上游 f42c887 exec 权限收紧的影响 |
| 2 | **16K 宿主全生态未验证** + Play 16K 兼容期限 2027-02-01 | 已确定缺口 | P1-6 探针；目标设备清单里 16K 机型优先级上升 |
| 3 | **execmod neverallow**：内核 .so / rootfs 运行期不可「先写后执行」 | 平台硬约束 | ext4 只读镜像为产品默认通道（会话 21 §8.1 已建议）；hostfs 只做研究/会话 |
| 4 | **网络通道分歧**：umnetx 自述 passt 在部分域被 SELinux 拒（NETLINK_ROUTE），与 Raymer/uml4termux 自述矛盾 | 未仲裁 | 必须本项目自测（passt / vec / umnetx 三候选），不可照抄下游 |
| 5 | **28 域失效日期无官方时间表** | 维持未取数 | ACR 选项 A/D 的固有赌注；继续跟踪 Android 版本说明 |
| 6 | **GPL/ADR-0014 边界**：UML 内核以 `.so` 由 App 进程装载是否满足「独立程序边界」 | 法务未取数（ACR §6.6） | 集成前过法务；备选「独立 UML APK」形态 |
| 7 | **单设备样本偏差**：AVF 服务缺失、HyperOS 安装确认等均为 K90 Pro Max/Android 17 单机结论 | 样本限制 | 多设备复测前，ACR 结论按「设备限定」引用 |
| 8 | **社区数据可信度**：drolin/umnetx/Raymer 等均为自述，无独立复现 | 证据等级限制 | 只作方向互证，不作决策依据；关键行为以本项目实测为准 |
| 9 | **性能数字口径**：2.04× 不能与跨会话数字（313ms/2809ms）相除；compute 行含稳定条件偏置 | 方法学约束（ACR §1.4） | 任何新结论必须「同轮四列」重测 |

## 7. 来源索引

**内部（本仓库）**

- `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md`（会话 21）
- `docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md`（会话 22）
- `docs/research/2026-10-29-36domain-wrapup-and-acr-material.zh-CN.md`（会话 23）
- `docs/research/ACR-draft-four-lanes-20261029.md`
- 2026-10-28 两份基线：`2026-10-28-native-kernel-three-lane-exploration.zh-CN.md`、`2026-10-28-userspace-kernel-native-execution.zh-CN.md`

**外部（访问日期 2026-10-31）**

- sepolicy `private/untrusted_app_all.te`（:27 execute allow）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_all.te
- sepolicy `private/app_neverallows.te`（:69-80 execute_no_trans + b/237289679 注释）、`private/domain.te`（:1344-1351 execmod 豁免）、`public/te_macros`（app_domain :240-263）、`microdroid/system/private/domain.te`（:271-272）：同仓 refs/heads/main
- Play target API 要求（2026-08-31 生效 targetSdk 36，延期至 2026-11-01）：https://support.google.com/googleplay/android-developer/answer/11926878 、https://developer.android.com/google/play/requirements/target-sdk
- 16 KB 页大小（targetSdk 35+ 2027-02-01 期限、Pixel boot 选项、Android 16 backcompat）：https://developer.android.com/guide/practices/page-sizes 、https://source.android.com/docs/core/architecture/16kb-page-size/16kb
- LKML RFC（2026-08-15）与 Weinberger 回复（2026-08-16）：https://lkml.iu.edu/hypermail/linux/kernel/2608.1/14307.html 、https://lkml.iu.edu/2608.2/00477.html
- 内核源码（GitHub 镜像）：torvalds/linux `arch/um/Makefile`、`arch/um/os-Linux/mem.c`、`arch/um/os-Linux/skas/process.c`、`arch/um/Kconfig`、commit `1e4ee5135d81`、f42c887
- 下游生态：https://github.com/zalexdev/linux-um-arm64 、https://github.com/Raymer8639/linux-um-arm64 、https://github.com/michaelamoetoevsky/uml4termux 、https://github.com/fahrulseptiana/drolin 、https://github.com/AAQWQ11/umnetx 、https://github.com/quangdz-comeback/uml-kernel-build
- 竞品/平台：https://github.com/ExTV/Podroid 、https://extv.github.io/Podroid/guide/limitations.html 、https://android.googlesource.com/platform/packages/modules/Virtualization/+/HEAD/docs/custom_vm.md 、https://blog.lyc8503.net/post/android-virtualization-on-snapdragon/ 、https://source.android.com/docs/core/virtualization/tryavf
- Termux/工具链：https://termux-wiki.vercel.app/google-play 、https://github.com/MatiasHiltunen/proot-rs

---

报告人：CodeBuddy（ask-matt 流程，4 子智能体分工：R1 上游 / R2 下游生态 / R3 平台策略 / R4 竞品替代；Play 政策专项补查由主会话完成）｜ 2026-10-31 ｜ 本文未运行设备/构建；所有「本项目实测」结论引自会话 21–23 已入库证据。

---
title: ACR 决策材料草案：四车道定位、主包 targetSdk 36 取舍、UML 定位与文案纪律
date: 2026-10-29
status: draft
scope: 四车道（proot/bare、pVM、QEMU、UML）定位与 targetSdk 36 取舍的决策材料；不含任何实现、不改 CONTEXT.md/ADR/代码
---

> **本文件是草案，供 ACR 决策会话使用，不构成实施承诺。**
> 它不改 `CONTEXT.md`、不改 `docs/adr/`、不写产品代码、不产生工单。所有判定在 ACR 会话上重新投票后才具备效力。
> 本文只做三件事：把已实测的事实排成可比较的形态、把每个数字的来源钉到具体章节、把「什么情况下这些结论会失效」写清楚。

---

## 0. 阅读约定

1. **数字必须有出处**。正文中每个性能数字/内存/包体/版本号后紧跟来源标注，形如（来源：`docs/research/xxx.md §5.2`）。凡本仓库无记载的，一律写「未取数」，不估算、不外推、不取整成好看的数。
2. **来源路径基准**：`docs/…` 相对仓库根 `Orchestella_dev/`；`../…` 指工作区根（`Orchestella开发目录/`）。
3. **四态标注**：每条车道结论标 `已验证` / `条件式` / `未验证` / `未取数`，四者不混用。
4. **跨会话数字不可直接横比**。本仓库的性能数字来自不同会话、不同夹具、不同编译器版本；§1 表内已单独标注「口径」列，混读会产生错误结论（见 §1.4）。

---

## 1. 四车道定位表

### 1.1 主表

| 维度 | proot / bare | pVM（protected Microdroid payload） | QEMU（TCG） | UML（`ARCH=um`） |
|---|---|---|---|---|
| **targetSdk 域前提** | 必须落在 legacy 域：targetSdk 28 → `untrusted_app_27`；36 → `untrusted_app_34`，`app_data_file` 无 `execute`（来源：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §4、§8.1） | 机制上与域无关：payload 由 AVF loader 从 APK 未压缩条目装载，不走 `nativeLibraryDir`（来源：`../handoff/prompts-archive/会话18-报告-两包拆分与感知修复-20261028.md` §2.1）；但现网扩展包是按 targetSdk 28 构建的（同上 §2.2），**36 域下的装载行为未验证**（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §7） | **条件式**：36 域可用，但前提是主包 `useLegacyPackaging=true`（不带 payload 目录），`nativeLibraryDir` 下才有带 x 位的实体 `.so`（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §4.1） | 需在 `nativeLibraryDir` 执行内核与 stub；`check_tmpexec()` 走 `mmap RWX`，在 36 域三探针全过（来源：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §4） |
| **执行机制** | 宿主 ARM64 指令原生执行 + `ptrace` 拦截 syscall 重写路径；guest 无内核（来源：`docs/research/2026-10-21-host-native-lane-evaluation.md` §4.1） | EL2 硬件 vCPU 执行平台信任链接受的 guest 内核（Microdroid GKI）（来源：`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §1.3、§2.3） | QEMU TCG 软件翻译 guest 指令（来源：`CONTEXT.md`「软件虚拟化」条） | 真 Linux 内核作为普通 App 进程运行，guest 指令由宿主 CPU 原生执行，guest 二进制由 guest 内核 ELF loader 加载、不经宿主 `execve`（来源：`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §0、§1.4） |
| **性能实测数字** | bare ≈313 ms/文件 p50（2,120–2,260 ms/7 文件，n=3）；proot ≈337 ms/文件 p50，proot−bare ≈7.8%（来源：`../项目状况总结-20261027.md` §3.2）；`-j7` 批墙钟塌缩到 370–410 ms（≈1,105 文件/分，已近 `/proc/uptime` 厘秒量化地板）（同上） | 单 job 端到端 2,809 ms（distcc 卸载 payload，n=1 复用）（来源：`../项目状况总结-20261027.md` §3.2）；payload 内 clang 编译 P50 98 ms（来源：同上 §2 M1 行） | 单文件全编 32,750 ms（TCG，含预处理，n=1 复用）（来源：`../项目状况总结-20261027.md` §3.2） | 编制 2,000 函数夹具：UML 中位 940 ms vs native 中位 460 ms = **2.04×**（15 样本；三轮 2.02×/2.22×/2.04×）（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §5.2、§5.4）；启动 220/320/340/400 ms，中位 ≈330 ms（来源：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §6） |
| **能力边界：完整内核** | ❌ 无 guest 内核，无真实 namespace/cgroup（来源：`docs/adr/0001-two-runtime-classes.md`；`docs/research/2026-10-21-cross-lane-reference-survey.md` §3.3） | ⚠️ 有内核但不可自定义——`Available OS list: ["microdroid"]`，pvmfw 只启动 Google/OEM 签名镜像（来源：`../路线判定-原生车道与pVM卸载-20261027.md` §11.1；`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §2.3） | ✅ 完整内核，两执行策略共用同一 pinned 内核（来源：`docs/adr/0005-guest-kernel-contract.md`）；36 域实测 guest systemd 启动至 `root@orchestella:~ #`（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §4.3） | ✅ 真内核已实测启动（kernel 7.2.0-rc4，`UMARM_BOOT_OK`）（来源：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §5.1） |
| **能力边界：Docker** | ❌ 不支持（`proot-distro` 明确「starting service supervisors (`systemd`, `OpenRC`…) is generally not possible」）（来源：`docs/research/2026-10-21-host-native-lane-evaluation.md` §4.1） | ❌ 不支持；payload 为 `microdroid_app` 域、caps 清空（来源：`docs/research/2026-10-21-cross-lane-reference-survey.md` §5.2） | 未取数（本仓库无 QEMU 车道跑 `dockerd` 的实测记录；仅 systemd 启动已实测，见上） | 未验证（D9 未做；下游 fork 自述 `dockerd` + overlay2 可用，属社区自述，非本项目复现）（来源：`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §2.1、§6.2） |
| **能力边界：GPU** | ❌ 否（可借宿主 X/Wayland，非产品承诺）（来源：`docs/research/2026-10-21-host-native-lane-evaluation.md` §6） | ❌ 否；protected 无 virtio-net、Microdroid 无 graphics HAL（来源：`docs/research/2026-10-21-cross-lane-reference-survey.md` §5.2） | ⚠️ VirGL 设计在案但**未完成真机验收**（来源：`docs/research/2026-10-21-host-native-lane-evaluation.md` §6 能力矩阵；`CONTEXT.md`「显示通道」条） | ❌ `GPU=UNSUPPORTED`（写死进能力矩阵）（来源：`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §4.2） |
| **发布形态约束** | 只能 targetSdk 28 → 上架通道受限；不可与 36 主包共存于同一 APK（来源：`../路线判定-原生车道与pVM卸载-20261027.md` §3.5） | 必须外置为扩展包：扩展包 126.7 MB（`132,898,402 B`），主包不带 pVM 资产（来源：`../handoff/prompts-archive/会话18-报告-两包拆分与感知修复-20261028.md` §2.3） | 内核 Image 49MB + QEMU 属主包不可减基线；主包两包形态 101.8 MB（`106,735,720 B`），对照旧单包 338 MB（来源：`../handoff/prompts-archive/会话18-报告-两包拆分与感知修复-20261028.md` §2.3） | 内核 `libuml_kernel.so` 9.6 MB（`9,648,496 B`）+ stub 2 KB（`2,000 B`），二进制由 `.gitignore` 排除、不入库（来源：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §5.3；`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §3）；GPL-2.0 分发义务见 §3.3 |
| **当前状态** | **条件式**（28 域已验证；36 域不可用） | **条件式**（28 域链路已验证；36 域同机复测**未验证**，扩展包在 HyperOS 装不上）（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §7） | **条件式**（36 域平台阻塞已实证解除；guest 出网与装配**未验证**）（同上 §4.4、§7） | **未验证为主**（启动与 `-c` 编译已验证；长驻、链接、Docker 未验证）（同上 §7） |

### 1.2 三条倍数关系（同一轮、同一夹具才算数）

| 关系 | 值 | 口径与来源 |
|---|---|---|
| 原生 vs pVM | **9.0×** | 2,809 / 313（来源：`../项目状况总结-20261027.md` §3.2） |
| pVM vs QEMU | **11.7×** | 32,750 / 2,809（同上；注意 §6 列出的 11.1× 已作废） |
| 原生 vs QEMU | **≈105×** | 32,750 / 313（同上） |
| UML vs native（编译） | **2.04×** | 940 / 460，同二进制同输入同设备、产物逐字节相同（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §5.2） |

### 1.3 「车道」与「Runtime 类别」不是一回事

`CONTEXT.md` 只有两类 Runtime：Full Linux 与 Userspace Compatibility。四车道里只有 proot/bare 属于后者；pVM/QEMU/UML 三者都是 Full Linux 的执行策略（来源：`CONTEXT.md`「Runtime 类别」「执行策略」条；`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §5.1）。**不要把「四条车道」写成「四个 Runtime 类别」**，那会直接推翻 ADR-0001。

### 1.4 跨会话口径警告（本表最大的方法论风险）

四个车道的数字来自三个不同会话：

- QEMU 32,750 ms / pVM 2,809 ms / bare 313 ms / proot 337 ms 来自 2026-10-27 会话 16，夹具为 92 KB `.i`（来源：`../路线判定-原生车道与pVM卸载-20261027.md` §4；`../项目状况总结-20261027.md` §3.2）；
- UML 940 ms / native 460 ms 来自 2026-10-29 会话 22，夹具为 90,045 B `.c`、命令 `clang -c -O1`（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §2）；
- 两侧工具链不同（Alpine clang 22.1.3 musl 静态 vs payload clang 22.1.8）（同上 §5.2；`../项目状况总结-20261027.md` §6）。

因此 **`2.04×` 只能与同轮的 `460 ms` 配对使用，不能与 `313 ms` 或 `2,809 ms` 相除**。任何「UML 比 proot 快/慢 N 倍」的说法，目前都缺同一轮的共夹具数据——`未取数`。

---

## 2. 主包 targetSdk 36：收益 / 代价与选项

### 2.1 先修正一个前提（否则整个议题问错了）

开工材料把「主包升 36」当成待决事项（来源：`../会话23-开工Prompt-36域收尾与ACR材料-20261029.md` §2 T-4）。**代码现状不是这样**：

```kotlin
targetSdk = if (buildChannel == "qa") {
    providers.gradleProperty("orchestella.qaTargetSdk")... ?: 36
} else { 36 }
```

release / dev / QA 默认**都是 36**；28 只是一个 QA 专用逃生门 `-Porchestella.qaTargetSdk=28`（来源：`app/build.gradle.kts:139-148`）。

所以真正待决的不是「升不升 36」，而是三个问题：

1. **28 逃生门留不留**（决定 proot/bare 车道在产品里是否存在）；
2. **留的话，它是不是一条只能侧载的通道**（决定发布渠道形态）；
3. **不留的话，`CONTEXT.md` 的 Userspace Compatibility 类别怎么办**（`CONTEXT.md` 明确写「当前候选：PRoot」）。

### 2.2 收益

| 收益 | 证据强度 | 来源 |
|---|---|---|
| 平台侧 exec/W^X 阻塞在 36 域**已实证解除**：`libgvproxy.so`/`libdlayer_qemu.so` 从 `nativeLibraryDir` exec 成功，执行类 AVC 计数 0 | 强（真机） | `docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §4.3 |
| 36 域下 QEMU 车道可保持完整语义（guest systemd 启动到 root shell） | 强（真机） | 同上 §4.3 |
| 满足新 targetSdk 门槛的上架通道 | **弱/存疑** | 见下 |

**「Play 通道」这一条必须打折**。本仓库两处记录与之冲突：

- ADR-0003 明确「渠道策略（Play 上架意义存疑）推至发布渠道决策，但本题选择使其偏向 GitHub Releases / F-Droid」（来源：`docs/adr/0003-rootless-tiers.md`「Consequences」）；
- 既有调研记录项目当前走 GitHub/F-Droid 发布，且 Play 不允许 hidden API 使用（来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §127 行附近「分发与合规」条）。

而「targetSdk 28 上不了 Google Play」这一条本身是有记载的（Termux 因此退出 Play）（来源：`../路线判定-原生车道与pVM卸载-20261027.md` §3.5）。**但 Play 对 targetSdk 的具体最低版本号与生效日期，本仓库无记载 → `未取数`**。ACR 若要按「合规时间线」投票，必须先补这个日期，否则是在用未取数的假设换一个不可逆决定。

### 2.3 代价

| 代价 | 说明 | 来源 |
|---|---|---|
| 28 域豁免在收缩 | Android 15+ 规划把最低 targetSdk 抬到 29；部分机型即便 targetSdk ≤28 也限制 `execve`；本设备目前仍放行，属**设备特定**而非稳定契约 | `../路线判定-原生车道与pVM卸载-20261027.md` §3.5 |
| Termux 官方自己承认豁免会终止 | 官方说明该 backward compatibility mode「may end in some future Android version」 | `docs/research/2026-10-21-host-native-lane-evaluation.md` §2.1 |
| 36 域下 proot 车道的核心动作被禁 | 36 域只能执行 `nativeLibraryDir`（`apk_data_file`）；proot 需要在可写目录里 exec 发行版二进制，而 `nativeLibraryDir` 只读、只收 `.so` | `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §8.1；`app/build.gradle.kts:139-142` 注释 |
| 36 + payload 同包互斥 | `useLegacyPackaging = fastlanePayloadLibDir == null`：带 payload 属性 → `extractNativeLibs=false` → `nativeLibraryDir` 为空 → 36 域 exec 被拒 | `app/build.gradle.kts:365`；`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §4.1 表 |
| **升 36 让两包拆分在运行期失效（包可见性）** | 包可见性过滤自 targetSdk ≥30 生效。`AndroidManifest.xml:18-20` 的 `<queries>` 只声明了 Shizuku，**没有声明扩展包**；`AccelExtensionResolver` 用 `getApplicationInfo(<主包名>+".accel", 0)` 探测，在 36 域必然抛 `NameNotFoundException` → 判定恒为 `EXTENSION_MISSING`。28 域（targetSdk 28）不受此过滤，所以这条缺陷只在升 36 后出现 | 现场实测：`app/src/main/AndroidManifest.xml:18-20`（仅 Shizuku）、`AccelExtensionResolver.kt:38-46`、`AccelExtensionRules.kt:69-70`；设备截图 `evidence/unlock36-20261029/round23/accel-card-01.png`（扩展包已装，卡片仍显示「未安装 pVM 加速扩展」） |
| 「放弃 proot」等于改写已发布承诺 | `CONTEXT.md` 把 Userspace Compatibility 定义为独立类别，PRoot 是其当前候选；删除它需要 ADR-0001 修订，不是删一个 flag | `CONTEXT.md`「Userspace Compatibility Runtime」条；`docs/adr/0001-two-runtime-classes.md` |

**核心矛盾一句话**：36 域让 QEMU 车道**更稳**（打包模式可控），却让 proot 车道**结构性消失**（不是变慢，是无法 exec）。二者不在同一维度上竞争，不能用「性能取舍」的框架去谈。

### 2.4 选项（≥3，均给触发条件与代价，本文件不推荐）

#### 选项 A · 保留 28 双轨（发布 36 + QA/侧载 28）

- **做法**：主包维持 36；`qaTargetSdk=28` 逃生门保留，proot/bare 车道只在 28 通道存在。
- **触发条件**：ACR 判定 Userspace Compatibility 仍有产品价值（低内存设备、无内核场景），且接受该车道**永不进入商店通道**。
- **代价**：双通道构建与验收成本常驻；28 通道只能侧载/自签（来源：`../路线判定-原生车道与pVM卸载-20261027.md` §3.5）；28 域随时可能失效，届时 proot 车道**无预警消失**，产品里会留下一张点不亮的卡。
- **失效风险**：ACR 无法回答「28 域什么时候没了」——这个日期 `未取数`。

#### 选项 B · 全量升 36 并放弃 proot 车道

- **做法**：删除 `qaTargetSdk` 逃生门，release/QA 统一 36；proot 相关能力矩阵行删除或标 `UNSUPPORTED` 永久。
- **触发条件**：ACR 判定 Play/商店通道是主渠道，或已确认 28 域在目标机型上不可依赖。
- **代价**：`CONTEXT.md` 的 Userspace Compatibility 类别失去唯一候选实现 → 必须改 ADR-0001（把「未来交付」改成「不交付」），属**契约级变更**；同时 36 域下 QEMU 车道还依赖「主包不带 payload」这一打包前提（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §1），一旦哪天需要把 payload 打回主包，就会与 36 直接冲突。
- **未验证的连带风险**：pVM 在 36 域的同机复测**未验证**（扩展包装不上）（同上 §7）。选 B 等于把「三车道全部 36」押在一个未验证项上。

#### 选项 C · 拆分双 APK（36 主包 + 28 兼容包，独立 applicationId）

- **做法**：主线 36 上架；兼容包独立 `applicationId`、targetSdk 28，承载 proot/bare。
- **触发条件**：两条通道都要，且 ACR 接受「两个 App 的数据不互通」。
- **代价**：ADR-0001 已明确否决「同一实例跨类别静默降级」，理由是「VM 实例是磁盘镜像、兼容实例是目录树，数据模型不可互换」（来源：`docs/adr/0001-two-runtime-classes.md`）。双 APK 等于把这个否决**从产品内搬到产品间**，用户侧的数据迁移问题不会消失，只是没人负责。另外扩展包已占用 `.accel` 后缀与 `versionCode` 锁定（来源：`../handoff/prompts-archive/会话18-报告-两包拆分与感知修复-20261028.md` §2.2），再加一条并行包链会让签名/发版/体积矩阵翻倍。

#### 选项 D · 主包降回 28（放弃商店通道）

- **触发条件**：ACR 判定 proot 车道的「原生指令 + 低内存」是不可替代的核心价值。
- **代价**：上架通道关闭（来源：`../路线判定-原生车道与pVM卸载-20261027.md` §3.5）；且与 36 域解封成果（会话 22 已实测的平台阻塞解除）方向相反，等于主动放弃一条刚打通的路。

### 2.5 每个选项都缺的同一块数据

四个选项的取舍都压在「28 域还能活多久」上，而这个日期 `未取数`。**ACR 若在没有该日期的前提下投票，选出的不是方案，是一个赌注。** 建议把「补齐 28 域失效时间线」列为 ACR 的前置输入项，而非会后跟进项。

---

## 3. UML 定位修正

### 3.1 编译设想：已撤回

| 阶段 | 结论 | 依据 |
|---|---|---|
| 会话 21（2026-10-29） | **GO**：「纯 CPU 1.08×、fork+exec 1.49×，编译落在 1.08–1.49× 区间，满足 ≤1.5× 判据」→ 建议替代 QEMU 承担编译 | `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1、§9.1 |
| 会话 22（2026-10-29） | **撤回**：真实跑 2,000 函数编译，UML 940 ms vs native 460 ms = **2.04×**，落 1.5–3× 区间 → 不进编译车道 | `docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §5.2、§5.4 |
| 归因 | 「compute 1.08× 不可外推到编译」：sha256 是顺序流式、页错误极少；编译是大量小对象分配 + `mmap/munmap` + 频繁页错误，每个 guest 页错误都要落到宿主 | 同上 §5.3 |

会话 21 的 GO 属**方法学可预期的误判**：它自己就写了「本轮未在 guest 内跑真实编译器（无工具链），以 `sha256` 代表纯用户态 CPU、以 `fork+exec` 代表进程创建」（来源：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1 口径说明）。2.04× 不是「UML 变慢了」，是**探针选错了**。这一点必须写进 ACR，否则下次还会用两端夹逼去推第三个负载。

### 3.2 新定位：长驻 / 容器车道

- 判据原文：`≤1.5×` → ACR GO；`1.5–3×` → 长驻/Docker 归属、编译仍归 pVM/proot；`>3×` → 负面入档（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §5.4）。
- 2.04× 落中段 → UML 的价值区间是「长驻 Linux 用户态 / Docker / 非 syscall 密集型负载」，与「替代 QEMU 的编译角色」相反（同上）。
- 这条定位与 UML 的结构性短板相容：UP 单核（`cpu count=1`）、syscall 8×、`seccomp=on` 已是快路径仍慢一个量级（来源：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §7.1、§8.1）。
- **与编译无关的一条正面事实**：Alpine 4K 对齐二进制可在 16K guest 正常加载执行（musl 1.2.6、busybox 1.37、clang 22.1.3 + 168 MB LLVM 库全部工作）（来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §6.1）。此前「16K 页对齐是最大未知」的担心不成立。

### 3.3 许可与发布形态约束

- UML 内核来自下游 `zalexdev/linux-um-arm64`（base 7.2-rc4，GPL-2.0，作者明确不维护）（来源：`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §2.1、§9.2）。
- 本项目自身为 GPL-3.0-or-later，且已确立「随 App 分发的 GPL 二进制由 CI 生成对应源码清单；发布时提供与该 tag 一一对应的 Release 源码包，含完整对应源码、补丁、配置、编译脚本」（来源：`docs/adr/0014-licensing.md` 第 3 条）。
- ADR-0014 的隔离机制表述是「随包 GPL 二进制（kernel/QEMU）通过独立程序/虚拟化边界隔离，履行对应源码义务」（同上第 4 条）。**UML 内核以 `.so` 形式由 App 进程装载，与 QEMU「独立子进程」的边界形态不同**；它是否仍落在「独立程序边界」内，属法务判断，本仓库无结论 → `未取数`。
- 因此 ACR 需要裁定的是**发布边界形态**，而不是「要不要开源」：可选项包括「UML 独立 APK（自带对应源码交付物）」与「并入主包但单独维护一份 UML 内核源码包」。本文不推荐，只指出：**独立 APK 的成本不在体积，而在多一条包链与一份独立源码交付物**（扩展包已经证明一条并行包链会被 HyperOS 装机策略卡住，见 §2.4 选项 C）。

### 3.4 未验证项（不得在 ACR 里当作已具备）

| 项 | 状态 | 原因 | 来源 |
|---|---|---|---|
| 30 min+ 长驻稳定性 | **未验证** | 时间盒；本轮全部是「启动→跑完→退出」短周期 | `docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §7；`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §8.2 |
| 链接阶段（`-c` 之外，调 `ld`/`lld`） | **未验证** | 本轮只做 `-c` | 同上 §7 |
| UML 内 systemd/Docker（D9） | **未验证** | stretch，未进时间盒 | `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §8.2 |
| SMP（多 vCPU） | **未验证** | 本轮 `--cpus=1`；需换 `linux-bionic-smp` | 同上 §8.1 第 4 条 |
| 16K 宿主 + 4K guest | **未验证** | 下游只验证 4K 宿主；目标设备实测宿主页 4096（`getconf PAGE_SIZE`） | 同上 §3 |
| 长驻场景的性能 | **未取数** | 现有 2.04× 是编译负载，长驻/Docker 类负载无本项目数据 | — |

**结论**：UML 目前的证据只够支撑「**可以启动、可以跑单文件编译、编译不划算**」。它离「长驻/容器车道」还差 30 min 稳定性 + Docker + 链接三块，**在补齐前不应写进产品能力矩阵的任何 `SUPPORTED` 单元格**。

---

## 4. 文案纪律

### 4.1 三个词的定义与适用车道

| 词 | 定义 | 适用车道 | 禁用车道 |
|---|---|---|---|
| **原生指令执行** | guest 二进制就是宿主 CPU 的指令，没有翻译层；但**没有硬件虚拟化边界**，syscall 可能被 `ptrace` 拦截或落到 guest 内核 | proot/bare、UML | pVM（pVM 也是原生指令，但用词应落在「硬件 vCPU」上） |
| **硬件 vCPU** | 由 EL2 hypervisor（本机为 Gunyah）提供的虚拟 CPU，guest 内核在该 vCPU 上执行 | pVM | proot/bare、UML、QEMU（三者都拿不到 vCPU，见 `docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §3.1） |
| **翻译执行** | 指令逐条经软件翻译后才执行 | QEMU（TCG） | 其余三条 |

**为什么必须分**：`CONTEXT.md` 把「硬件虚拟化」定义为「由 CPU 硬件直接执行 guest 指令的执行策略」（来源：`CONTEXT.md`「硬件虚拟化」条）。按字面，proot 和 UML **也**是「CPU 硬件直接执行」，会被误读成硬件虚拟化。**这三个词的存在就是为了堵这个字面漏洞**：区分标准不是「谁在跑指令」，而是「**guest 内核跑在谁的 CPU 上、有没有 EL2 边界**」。

### 4.2 禁用「硬件加速」

- 禁令来源：UML 车道「**不得写 protected / 硬件隔离 / 硬件加速**」（来源：`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §4.4）；能力矩阵要求「性能徽章写『原生指令，syscall 有开销』」（同上）。
- proot 同理：「**不能把 proot 叫『硬件加速』**；产品文案应写『原生指令，syscall 有开销』」（同上 §1.1）。
- 已发布文案里的历史包袱：ADR-0001 曾用「完整 Linux（硬件加速 / 软件模拟·较慢）」作为徽章（来源：`docs/adr/0001-two-runtime-classes.md`「Considered Options」第 2 条），且 ADR-0003 的「提升到硬件加速」入口文案仍在（来源：`docs/adr/0003-rootless-tiers.md`「Consequences」）。**ACR 应一并裁定这两处是否改词**——它们是当前唯一还在对外使用「硬件加速」的地方。
- 「硬件加速」唯一合法的技术含义是 GPU 侧的硬件加速（`docs/adr/0019-gpu-hardware-acceleration.md`），与执行策略无关；混用会把显卡话题和执行策略话题搅在一起。

### 4.3 正例 / 反例

**原生指令执行（proot / bare）**

- ✅ 正例：「兼容模式：Linux 程序以原生 ARM64 指令运行，不经过指令翻译；文件与包管理操作有额外开销。」
- ❌ 反例：「兼容模式采用硬件加速，接近原生速度。」——错在两处：没有 EL2 边界，且 `openat` 类路径实测明显变慢（来源：`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §2.1 表：`openat` proot 28.005 µs vs native 2.503 µs）。

**硬件 vCPU（pVM）**

- ✅ 正例：「pVM 编译加速：编译在硬件 vCPU 上执行，需单独安装加速扩展。」
- ❌ 反例：「AVF 硬件加速让 Linux 整体变快。」——`Available OS list: ["microdroid"]` 决定它只能加速被卸载的那部分计算，不能加速 shell / I-O / 包管理（来源：`../路线判定-原生车道与pVM卸载-20261027.md` §11.4 边界段）。

**翻译执行（QEMU）**

- ✅ 正例：「完整内核模式：guest 指令经软件翻译执行，CPU 密集负载较慢，但具备完整内核、systemd 与 GPU 通道（GPU 未完成真机验收）。」
- ❌ 反例：「QEMU 模拟器。」——`CONTEXT.md` 明确「模拟器」为禁用词（来源：`CONTEXT.md`「软件虚拟化」条 `_Avoid_` 行）。

**跨车道混写（最常见的一种错）**

- ❌ 反例：「本产品支持硬件加速的 Linux 环境。」——一句话同时踩中：把 pVM 的能力说成产品能力、把 QEMU 说成硬件加速、把 proot 说成有边界。
- ✅ 正例：「完整内核模式（软件翻译执行）为默认；pVM 编译加速需单独安装扩展；兼容模式为原生指令执行、无独立内核。」

---

## 5. 撤销条件与回退路径

### 5.1 逐条结论的撤销条件

| # | 结论 | 若观察到 X，则本结论失效 |
|---|---|---|
| 1 | 36 域下 QEMU 车道可用（条件式） | ① 不带 payload 属性的构建在 36 域出现执行类 AVC 拒绝；② `useLegacyPackaging` 与「主包必须内嵌 payload」重新变成硬绑定（即两包拆分被撤销）；③ 目标机型 ROM 把 `apk_data_file` 的 execute 也收掉 |
| 2 | 36 域下 proot 车道不可用 | 出现任一实证：36 域能在可写目录 exec 发行版二进制；或 `nativeLibraryDir` 被证明可承载可写 rootfs；或 `termux-exec` 式 `system_linker_exec` 在自研包名 + 只读前缀上跑通（后者门未闭合，见 `docs/research/2026-10-21-host-native-lane-evaluation.md` §7 G1） |
| 3 | pVM 仍是唯一稳定契约的高速通道 | ① 36 域 pVM 同机复测出数且显著劣于 28 域；② 扩展包在目标机型稳定装不上（当前已是 HyperOS 拦截状态，来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §7）；③ pVM 11.7× 在同夹具复测中跌到 <1.5× |
| 4 | UML 不进编译车道 | 同夹具复测（同一 clang、同一夹具、同设备、n≥10、控温）稳定 ≤1.5×（判据见 `docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §5.4；本次实测 2.04×，同上 §5.2）。注意：**必须同一轮四列**，不允许再用跨会话数字拼表（见 §1.4） |
| 5 | UML 可作长驻/容器车道 | ① 30 min+ 出现不可恢复 panic；② D9 Docker 不可用；③ 链接阶段在 16K guest 失败；④ 16K 宿主设备上无法启动（四项均为 §3.4 表列的未验证项） |
| 6 | 「Play 通道」是升 36 的收益 | ACR 补齐 Play 的 targetSdk 最低要求与生效日期后（当前 `未取数`），若项目实际发布渠道判定为 GitHub/F-Droid，则此收益归零（`docs/adr/0003-rootless-tiers.md` 已倾向后者） |
| 7 | 「原生 vs pVM = 9.0×」 | pVM 侧与 bare 侧不再同夹具（如 pVM 换成含预处理口径），或任一侧重新采样后比值 <1.5×（9.0× 的原始值见 `../项目状况总结-20261027.md` §3.2） |
| 8 | 四车道数字可比 | 任一行更换夹具/编译器版本而未标注 —— 本结论即时失效，表须重做为「同轮四列」 |

### 5.2 回退路径（步骤级，不写代码）

**R-1 · 若「36 域 QEMU 可用」被推翻（结论 1 失效）**

1. 冻结 36 域相关的发布形态变更，不回退 targetSdk（回退会连带打死其他车道）。
2. 用 `-Porchestella.qaTargetSdk=28` 出 QA 包，确认 28 域下 QEMU 仍可启动——这一步只用于**定位是域问题还是打包问题**。
3. 抓 `dmesg | grep avc` 与 `NativeExecutableStore.ensure()` 的返回路径，判定失败发生在「`.so` 未解包」还是「解包后被拒执行」。
4. 若为「未解包」→ 回退到打包模式排查（`useLegacyPackaging` / `orcExtractNativeLibs`）。
5. 若为「被拒执行」→ 结论 1 失效入档，QEMU 车道回到 28 域，与 proot 车道同命运，§2 的选项空间整体重画。

**R-2 · 若「proot 在 36 不可用」被推翻（结论 2 失效）**

1. 记录使能的机制（哪条 exec 路径、哪个域、哪条 AVC 为 0），写成可复现步骤。
2. 在 28/36 两域各跑一次同一 rootfs 的 `apk add`，确认不是单机偶然。
3. 若成立 → §2 的选项 A/C 失去必要性，可收敛为「单包 36」；但需先在 ACR 里撤回 ADR-0001 的「兼容实例是目录树、不可与 VM 实例互换」这一数据模型前提。

**R-3 · 若 UML 编译复测翻盘（结论 4 失效）**

1. 复核三件事是否同时满足：同一二进制、同一夹具、同一设备——缺一项则结论不成立，回到步骤 2。
2. 复核产物等价性（两侧 `.o` 的 sha256 是否仍逐字节相同，本轮为 `b43f22e0…4e8b064`，来源：`docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §5.2）。
3. 复核温度与降频（本轮 39→57 °C 未触降频，`thermal_zone27`，同上 §5.2）。
4. 三项全过 → 结论 4 失效，UML 重新进入编译车道候选；此时**先补 30 min 稳定性**再谈角色，因为编译车道的长驻语义比一次性编译更严。

**R-4 · 若 pVM 在 36 域确认不可用（结论 3 失效）**

1. 判定失败点：扩展包装不上（HyperOS/安装策略）vs 装上但 payload 装载失败 vs 装载成功但执行被拒。三者回退路径完全不同。
2. 若为「装不上」→ 这是分发问题不是技术问题：评估把扩展包改为「同一 APK 内的可选下载资产」，但那会与 36 + `useLegacyPackaging` 冲突（§2.3），需回到 §2 重新投票。
3. 若为「装载失败」→ 记录 `setApkPath` 反射调用的返回与 `AccelExtensionRules` 五态判定，属实现层修复，不改车道定位。
4. 若为「执行被拒」→ pVM 与 proot 同命运，三车道收缩为「QEMU（完整但慢）+ UML（真内核但未验证）」两条，产品叙事需重写。

**R-5 · 通用回退纪律**

1. 任何回退只改**发布形态与能力矩阵**，不改 `CONTEXT.md`；契约变更必须单独走 ADR 修订。
2. 回退前后各留一份「同轮四列」数据，否则下一次决策又会回到跨会话拼表（§1.4）。
3. 回退不得以「降低 targetSdk」作为默认手段——它一次性打死两条车道，且是单向门。

---

## 6. 本文件的自限（给 ACR 主持人的清单）

以下条目在本仓库**取不到数**，ACR 若要基于它们投票，需先补数据：

1. Play 对 targetSdk 的最低要求版本号与生效日期。
2. Android 何时收回 targetSdk 28 的 legacy 域（会话 21 只写「后续 Android 版本」，无版本号）。
3. 28 域失效后 proot 车道的替代路径（`system_linker_exec` 在自研包名上是否可用）。
4. QEMU 车道跑 `dockerd` 的实测结论。
5. UML 长驻 / Docker / 链接三块的任何数字。
6. UML 内核以 `.so` 形式分发是否仍满足 ADR-0014 的「独立程序边界」——法务结论。
7. 同夹具四列车道（proot / pVM / QEMU / UML）的同一轮数据。
8. 扩展包在目标机型的可安装性（当前被 HyperOS 拦截，非技术结论）。

---

## 7. 会话 23 现场补充（2026-10-29，同一设备）

本节是草案成文后追记的实测结果，**推翻或收紧上面两处判断**，ACR 前必读。

### 7.1 pVM 车道的阻断点不是「扩展包装不上」，是 AVF 服务不存在

- 扩展包**已经装上**（`<主包>.accel`，versionCode 503 = 主包 503，`PVM-COMPILER-MANIFEST` 的 `engine=clang`、`compiler_version=22.1.8`），会话 18 起的「HyperOS 拒新装」在本次更新路径下未复现。
- 真正的阻断在决策层：`OrchestellaAccelerator: budget_rejected ACCEL ACCELERATOR_UNAVAILABLE`，随后 `lane_started QEMU`——加速车道被拒、回落 QEMU。
- 平台侧原因：`service list`（537 个服务）中**没有 AVF 的 `virtualizationservice`**，只有 `android.system.vmtethering.IVmTethering` 与厂商 `vendor.qti.AvfQcvmManager.IAvfQcvmManager`。设备虽声明 `feature:android.software.virtualization_framework`，但虚拟机管理服务未注册。
- `pm grant <pkg> android.permission.MANAGE_VIRTUAL_MACHINE` 可以执行且 `granted=true`，**但不改变上述结论**——缺的是服务，不是权限。
- 因此 §1.1 表中 pVM 行的「当前状态」应读作：**28 域链路已验证；36 域受限于本机 AVF 服务缺失，非打包/权限问题**；§5.1 第 3 条的失效条件需再加一条：④ 设备不注册 `virtualizationservice`。

### 7.2 升 36 的隐性代价：两包拆分在运行期不可见

见 §2.3 新增的「包可见性」行。一句话：`<queries>` 没声明扩展包，targetSdk ≥30 起包可见性过滤生效，`AccelExtensionResolver` 永远探测不到扩展包。**这条在 28 域构建里不存在**，所以它不是「新发现的旧 bug」，而是「升 36 才触发的新代价」——恰好落在本次 ACR 要裁的那个决定上。

### 7.3 QEMU 车道的「装配失败」与网络无关（对 §1.1 QEMU 行状态的收紧）

- guest 出网实测健康：网关与上游 ICMP 0% 丢包、DNS 解析成功、`nc` 到 223.5.5.5:53/80 均 connected、`pacman -Sy` 下载 10.2 MiB @ 0.8–1.7 MB/s。
- 装配失败的根因是 **pacman 7 的下载沙箱**：`error: restricting filesystem access failed because Landlock is not supported by the kernel!`（guest `/sys/kernel/security/lsm` 只有 `capability`）。
- 在 guest 内加 `DisableSandbox` 后 `pacman -Sy` 成功。**即 QEMU 车道的 36 域状态应从「guest 出网未验证」上修为「出网已验证；装配受 guest 内核无 Landlock 阻断，可修」。**
- 附带更正一条会话 22 的经验：gvproxy **会**转发 ICMP；`ping 192.168.127.254` 100% 丢包是因为 hostIP 被映射为 `127.0.0.1` 后回包源地址被改写，与 TCP 无关。

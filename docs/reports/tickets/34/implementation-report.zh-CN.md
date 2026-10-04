# Ticket 34 实施报告（Implementation Report）

- **工单编号**：Ticket 34
- **工单标题**：AVF 执行策略——同一契约跑硬件虚拟化（AVF Execution Strategy & Authorization Wizard）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（严格遵循 TDD 节奏，代码实现与契约自测闭环）
- **工作区与特性分支**：`DroidLayer_dev_t34`，分支 `ticket/34-avf-execution-strategy`（基于 `main`，Commit `6547a32`）
- **看板跟踪项**：`.scratch/droidlayer-platform/issues/34-impl-avf-execution-strategy.md`（已置 `ready-for-review`）
- **当前状态**：**Rectification Completed（独立评审 CHANGES_REQUESTED 整改轮 2026-10-05 完成，JVM 模拟层）· 提请二次复审 · 真机验收待 HITL**

---

## 1. 任务背景与核心目标

Ticket 26 交付的 QEMU 纯软模拟（L1）已在真机完成全量验收。本票（Ticket 34）兑现 ADR-0001 的"第二执行策略"承诺：在具备 AVF/pKVM 条件的设备上（L2），**同一实例资产**经硬件虚拟化运行，性能徽章切换为"完整 Linux · 硬件加速"。

本票刻意不等待票 27–33 的 AVF 档对齐（出站网络/转发/共享/恢复由票 36/37 承接），只证明契约核心穿过与 26 相同的切片：**能力检测与授权向导 → Custom VM（自定义内核/initrd/raw 根盘）→ vsock 桥接传输 → shell**，且实例资产在两个策略间互换（同一根盘两处可启动）。

按派工单 AC-4 的裁定，本票的契约验证在 **AVF 适配层模拟实现**（JVM）上完成；真机 AVF 冒烟属 HITL 协作事项（依赖原型票 17 的真机数据，该票仍为 open），不在本会话执行。

## 2. 模块边界与红线遵从核对（Ownership & Boundaries Verification）

| 约束项 | 规范要求 | 实际遵从情况 | 验证结论 |
| :--- | :--- | :--- | :--- |
| **工作区物理隔离** | 独立隔离工作树 | 独立 Git Worktree `DroidLayer_dev_t34`，分支 `ticket/34-avf-execution-strategy`（基线 `main` @ `6547a32`） | **100% 隔离** |
| **Owned: engine-avf** | `runtime/engine-avf`（创建） | 新建纯 JVM `java-library` 模块并注册 `settings.gradle.kts`；`main` 源码零 Android 类引用、零 `java.*` 以外的运行时依赖 | **完全受控** |
| **Owned: capability** | `runtime/capability`（扩展） | 新增 `AvfCapabilityEvaluator`、`AvfAuthorizationWizardModel`，未触碰既有 `DeviceCapabilityEvaluator` 行为 | **完全受控** |
| **Owned: contract** | 契约注册 | **零改动**——`runtime/contract` 源码一个字节未动；AVF 直接复用既有 `BridgeClient`/`BridgeHandshake`/`FullLinuxContract`，是"契约策略中性"的最强兑现 | **零侵入** |
| **Owned: bridge(vsock)** | vsock 传输 | 传输细节全部封装在 `runtime/engine-avf`；宿主侧仅新增 `BridgeConnector.serveStreams` 流式重载（不关闭调用方流，原抽象插槽路径逐字节不变） | **完全受控** |
| **Owned: ui(向导/徽章)** | 向导/徽章 | `MainActivity` 接入向导与动态徽章；`TerminalActivity` 经注册表消费 AVF 流；Manifest/strings/layout 最小增量 | **完全受控** |
| **Owned: tests** | 契约测试套件 | engine-avf 6 个 main 式测试任务 + capability 2 个 + app 4 个新 JUnit 类 | **完全受控** |
| **Forbidden: storage** | 严禁改动 `storage/` 与实例元数据模型 | `runtime/instance` 零改动（未向实例元数据加策略字段——策略即会话属性，不落盘） | **零冲突** |
| **Forbidden: guest-assets / Class A 资产** | 严禁改动 34 项设备证据、`.toolchains/`、官方 Alpine rootfs 等 | 零触碰；未提交任何 `.log` 文件（批末负例检查 = 0） | **零冲突** |
| **编译纪律** | Java 17 + `-Xlint:all -Werror` 零告警 | 全部新模块/改动模块零告警通过（见 §6） | **100% 合规** |
| **提交纪律** | Conventional Commits + `[ticket-NN]` | 3 个提交均带 `[ticket-34]` 标记，仅含本票工作 | **100% 合规** |

## 3. 架构设计细节

### 3.1 三层切片与类型安放（ADR-0001/0002 兑现）

- **契约层（`runtime:contract`，零改动）**：`ExecutionStrategy`（既有策略中性枚举）、`CapabilityMatrix`（UI 徽章唯一数据源）、`BridgeClient/BridgeHandshake`（桥接 v1 线协议）全部原样复用。AVF 适配没有在契约层留下任何策略专属类型。
- **策略层（`runtime:engine-avf`，新建）**：`AvfLaunchSpec`（Custom VM 装配规格）与 `QemuLaunchSpec` 同级同层，装下所有"策略专属"细节——guest 命令行（`console=hvc0`、`droidlayer.vsock_control_port=9100`、`droidlayer.vsock_terminal_port=9101`）、双 vsock 端口、内存。
- **产品层（`runtime:capability` + `app`）**：能力检测、授权向导状态机、策略选择与回退协调。UI 只消费 `CapabilityMatrix.performanceBadge()`（"完整 Linux · 硬件加速" vs "完整 Linux · 软件模拟（较慢）"），文案零 AVF/QEMU 技术名词（ADR-0001"类别诚实、后端隐形"，测试显式断言拒绝理由不含引擎名词）。

### 3.2 能力检测与授权向导（AC-1，ADR-0003 L2 门）

- `AvfCapabilityEvaluator.evaluate(AvfHostFacts)` 组合五类事实给出三态裁决：
  1. SDK ≥ 33（Android 13+，AVF 时代地板）；
  2. ABI 含 `arm64-v8a`；
  3. **硬件封锁名单**：SoC 提示命中即判不支持——当前名单 `sm8750`（骁龙 8 Elite：Gunyah 固件级封锁非保护 VM，即使 `ro.virtualization.supported=true` 亦不开放 custom VM 通道；来源 `docs/research/2026-10-01-android-linux-ecosystem-and-hardware-acceleration.md` 与票 19），并支持调用方注入补充封锁提示；
  4. 平台声明：`ro.virtualization.supported` 显式值优先，不可读时回退 `android.software.virtualization_framework` feature 声明，两者皆无 → 不支持；
  5. `MANAGE_VIRTUAL_MACHINE` 授权状态：未授权 → `NEEDS_AUTHORIZATION`（展示向导），已授权 → `READY`（硬件徽章）。
- `AvfAuthorizationWizardModel`：不可变状态机（`NOT_SHOWN → SHIZUKU_GUIDANCE ⇄ ADB_GUIDANCE → AUTHORIZED / CLOSED`）。**不支持设备 `wizardEligible=false`，向导根本不展示**（ADR-0003"避免无效折腾"）。Shizuku 步骤优先；ADB 兜底步骤生成逐字可复制的 `adb shell pm grant <pkg> android.permission.MANAGE_VIRTUAL_MACHINE`，授权复检通过才推进状态。
- Manifest 显式声明 `android.permission.MANAGE_VIRTUAL_MACHINE`，使 `pm grant` 路径可用。

### 3.3 反射隔离的 Custom VM 引擎（AC-2）

- `AvfVirtualMachineApi` 是引擎与设备 SDK 之间的接缝；`ReflectiveAvfVirtualMachineApi` 是生产实现：
  - 以 `ClassLoader` 装载 `android.system.virtualmachine.*`（`VirtualMachineManager` / `VirtualMachineConfig` / `VirtualMachineCustomImageConfig$Builder` / `VirtualMachine`），缺失即 `REFLECTION_UNAVAILABLE`（fail-closed）；
  - 经 `getSystemService("virtualization_service")` 取管理器，经 Custom VM Builder 反射装配 `setKernelPath` / `setInitrdPath` / `addDisk(path, writable=true)`（双签名回退）/ 内存（`setMemoryMbs(int)` → `setMemoryBytes(long)` 回退）/ `setProtected(false)`（非保护 VM）/ `setNetworkSupported(true)`；
  - **必选步骤失败 → 带稳定原因码的 `AvfEngineException`**；可选步骤失败 → 记入 `reflectionNotes` 诊断（绝不静默）；
  - `createVm → getOrCreateVm` 双签名尝试；创建异常映射为 `HARDWARE_UNSUPPORTED`（含 `UnsupportedOperationException`/消息含 "not supported"，对应 8 Elite 类封锁的真机表现）、`PERMISSION_DENIED`、`VM_CREATE_FAILED`；
  - 构造器保留 `hiddenApiExemption` 一次性钩子：Android 15+ 公开 SDK 直接可用；Android 13/14 的隐藏 API 强制约束需宿主在设备切片提供 HiddenApiBypass 类豁免，未提供时在 notes 里如实记录（见 §7 偏差说明）；
  - vsock 通道：`connectVsock(port)` 返回对象经反射适配出 `getInputStream()/getOutputStream()` 流对，不锁死具体返回类型。
- `AvfVirtualMachineEngine` 会话化封装：惰性建立控制/终端双 vsock 通道（guest 启动窗口内 100 次 × 200ms 重试）、`waitFor()`（优先轮询 `getStatus()`，不可读时退化为"宿主 stop 后返回"并如实记录）、`stop()`、`closeChannels()`。

### 3.4 资产互通（AC-3，ADR-0001）

- **同一资产零转换**：`AvfInstanceLauncher` 复用与 QEMU 完全相同的 `QemuRuntimeBootstrapper` 引导链（manifest 校验的内核 Image、嵌入 provision 输入的 initramfs、经 `pm grant` 无关的同一 raw 根盘文件），仅 VM 装配方式不同；
- `AvfQemuInteropTest` 在规格级锁定互换承诺：QEMU `-drive file=<root>` 与 AVF `addDisk(<root>)` 引用**同一根盘路径字符串**（即同一文件字节），内核/initramfs 路径一致，`droidlayer.input_tag=droidlayer-input` 双引擎同值——策略切换是"路径换接"，不是"资产改写"；
- **透明回退**：`InstanceStrategyLauncher` 硬件优先——`AvfEngineException`（反射初始化/VM 创建/权限/硬件拒绝）→ 先经 `AvfDegradationLog` 追加记录（`avf-degradations.txt`，遵守证据扩展名纪律不产生 `.log`）→ 自动回退 `QemuInstanceLauncher` 并在 UI 给出"硬件加速启动失败（原因码），已自动回退软件模拟"；**共享资产装配失败（IOException）不降级、如实上抛**（它会以同样方式杀死 QEMU 路径，静默降级只会掩盖真实故障）；
- 未向实例元数据写入策略字段：执行策略是**会话属性**，每次启动由当前能力裁决，天然支持"同一实例今天软件跑、授权后硬件跑"。

### 3.5 vsock 桥接传输与终端（AC-4 支撑）

- 宿主侧 `BridgeConnector.serveStreams(InputStream, OutputStream, ...)`：与抽象插槽路径**同一条** HELLO/READY → PING/PONG → RESIZE → 控制环代码路径，仅传输从 `LocalSocket` 换为调用方持有的流（AVF vsock 通道），该方法不关闭流（生命周期归传输属主）；
- `TerminalActivity` 经 `AvfTerminalStreamsRegistry`（进程内注册表，Intent 无法携带流）取得 AVF 会话的终端/控制通道，QEMU 路径完全保持原逻辑；
- guest 侧 vsock 服务代理（guest agent 的 vsock 通道）属票 36/37 范围（矩阵裁定"bridge vsock 侧 → 36/37"），本票在 `AvfLaunchSpec` 命令行中公布端口约定供其消费。

## 4. 验收标准逐项落地与实测证据

### 4.1 AC-1（能力检测与授权向导）— 已达成（代码 + JVM 测试）
- `runtime/capability` 新增 2 个 main 式测试任务全绿：
  - `AVF_CAPABILITY_EVALUATOR_TEST`：10 个场景（SDK 33 地板、ABI、属性显式 false 优先于 feature、feature 单独可满足、封锁 SoC 静默关向导、注入封锁提示、未授权 NEEDS_AUTHORIZATION + 徽章保持软件档、理由文案零引擎名词）；
  - `AVF_AUTHORIZATION_WIZARD_MODEL_TEST`：7 个场景（不支持设备禁止 start、Shizuku 优先、ADB 兜底往返、复检门控、decline 终态、pm grant 命令逐字匹配）。
- 支持设备提供 Shizuku 引导（打开 Shizuku 管理器 + 步骤文案）与 ADB 指令一键复制（ClipboardManager）。**偏差**：Shizuku 应用内"一键授权 API"需要引入 `dev.rikka.shizuku:api` 新依赖，受网络端点限制未引入，Shizuku 步骤以引导授权代替（见 §7）。

### 4.2 AC-2（AVF 引擎实现与契约履行）— 已达成（反射引擎 + 模拟实现测试）
- `:runtime:engine-avf` 注册进 `settings.gradle.kts`，6 个测试任务 100% 绿（§6 证据行）；`AVF_VIRTUAL_MACHINE_ENGINE_TEST` 通过假 SDK 类加载器**原样执行生产反射代码**，断言 VM 装配（内核/initramfs/根盘 writable/内存/非保护/网络声明）与四类失败原因映射。

### 4.3 AC-3（资产互通与透明回退）— 已达成（规格级 + 回退链路测试）
- `AVF_QEMU_INTEROP_TEST_OK same_kernel=1 same_initramfs=1 same_root_volume=1`；
- app `InstanceStrategyLauncherTest` 4 场景全绿：硬件成功不碰 QEMU、AVF 失败回退 QEMU 且记录原因、软件偏好零 AVF 尝试、共享失败不静默降级；`AvfDegradationLogTest` 验证追加式原因账本与 `.txt` 纪律。

### 4.4 AC-4（全量契约测试绿灯）— 已达成（模拟实现上 100% 绿）
- 同一套 `BridgeClient` 原线协议在模拟 guest 上全绿（HELLO 精确线、PONG、RESIZE、NOTIFY、OPEN-URL、错误 token 拒绝）；
- 终端字节流回显、8KB 大块、guest 侧关闭→宿主 EOF 全绿；
- 实例生命周期 `CREATED → PROVISIONING → STOPPED → RUNNING（含桥接握手）→ 观察退出 → STOPPED` 全绿；
- 全量构建零编译告警、无死锁（`-Xlint:all -Werror`、`--offline` 全链路）。

## 5. Git 提交记录（Conventional Commits + [ticket-34]）

| Commit | 内容 |
| --- | --- |
| `866f033` | feat(capability): AVF 就绪度评估与授权向导状态模型 |
| `91e2e8c` | feat(engine-avf): AVF 执行策略引擎与 vsock 桥接传输 |
| `551a965` | feat(app): 授权向导、策略选择与透明回退链路 |

交付物提交（本报告 + 看板更新）随后追加，报告路径 `docs/reports/tickets/34/implementation-report.zh-CN.md`。

## 6. 测试执行证据

**运行环境**：Windows + Git Bash；`java` = D:\JDK17（BellSoft 17.0.10+13-LTS）；Gradle 8.13 wrapper + 温热 GRADLE_USER_HOME；`--offline` 全程（零网络访问，符合端点纪律）。

**已知宿主陷阱与处置**：本机以 `D:\…（含 CJK 与空格）`路径直接运行 `:app:testDebugUnitTest` 时，Gradle Test worker 对全部测试类报 `ClassNotFoundException`（UTF-8 @argfile 与 GBK 启动器编码错位，与记忆中 Ticket 26 的既有陷阱同类；主仓 `DroidLayer_dev` 复测同样失败，证明与本次改动无关）。处置：以 `subst X:` ASCII 盘符别名挂载工作区后运行，测试正常执行。**本票全部测试命令均在 `X:` 路径下执行**（`subst` 为会话级临时映射，不改变仓库字节）。

**门禁命令与结果**（均在 worktree 根执行）：

1. `./gradlew --offline :runtime:capability:check :runtime:contract:check :runtime:engine-qemu:check`（基线，改动前）→ **BUILD SUCCESSFUL**；
2. `./gradlew --offline :runtime:engine-avf:check --rerun-tasks` → 6/6 任务绿：
   - `AVF_LAUNCH_SPEC_TEST_OK defaults=1 validation=6`
   - `AVF_VIRTUAL_MACHINE_ENGINE_TEST_OK assembly=1 retry=1 reflection_unavailable=1 manager_unavailable=1 hardware_unsupported=1 permission_denied=1`
   - `AVF_BRIDGE_OVER_VSOCK_CONTRACT_TEST_OK hello=1 ping_resize=1 unauthorized=1 notify_open_url=1`
   - `AVF_TERMINAL_STREAM_CONTRACT_TEST_OK echo=1 large_chunk=1 guest_eof=1`
   - `AVF_INSTANCE_LIFECYCLE_CONTRACT_TEST_OK created=1 provisioning=1 running=1 stopped=1 bridge=1`
   - `AVF_QEMU_INTEROP_TEST_OK same_kernel=1 same_initramfs=1 same_root_volume=1`
3. `./gradlew --offline check`（全仓）→ 除下述既有失败外全部绿：capability/distro/instance/contract/network/engine-qemu 全部既有测试 + 本票新增测试全绿；`app:testDebugUnitTest` 40 例中 37 绿、3 失败（见 §7 既有问题）；`compileJava`/`compileKotlin` 全部零告警；
4. `./gradlew --offline :app:lintDebug` → **BUILD SUCCESSFUL**（`abortOnError=true`，零 lint 错误；`Build.SOC_MODEL` 已按 API 31 守卫）；
5. `./gradlew --offline :app:assembleDebug` → **BUILD SUCCESSFUL**，产出 `app-debug.apk`（991,320 字节；无 QEMU payload 属性时按既有设计打包轻量 APK，AVF 策略本身不需要 payload，QEMU 回退路径沿用既有 payload 打包渠道）；
6. 批末负例检查：`git status --porcelain --untracked-files=all | grep '\.log$'` 计数 = **0**（未新增任何 `.log` 证据文件）。

**TDD 纪律记录**：Slice 1 先写测试再实现——`avfCapabilityEvaluatorTest` 首跑 RED（77 个编译错误，类不存在），实现后 GREEN；Slice 2/3 同节奏，过程性失败（装箱断言、`OK resize` 响应线精确匹配、guest 关闭时序）均已修正并保持 RED 证据在会话记录中。

## 7. 偏差、既有问题与新发现（如实记录）

1. **既有问题（非本票引入）**：`AndroidSafExportAdapterTest` 3 项在 JVM 上因 mockable android.jar 的 `Uri` 返回 null 而 NPE 失败（`rejectsExportWhenInstanceIsRunning` / `exportsToTreeUriAsThreeVerifiedFiles` / `exportsToDocumentUriAsVerifiedArchive`）。定界证据：`git diff 6547a32 HEAD -- <SAF 适配器与测试>` 为 **0 行**——本票未触碰该代码；且该失败在不含本票改动的运行组合中同样出现。属票 33 范围，按 Worker 契约不代为修复，提请 Orchestrator 另立修复切片（建议 `testOptions.unitTests` 配置或 RoboElectric 化）。
2. **宿主环境陷阱**：CJK 路径下 Gradle Test worker `ClassNotFoundException`（§6），建议写入工程环境文档；长期方案为 subst 别名或 ASCII 构建路径。
3. **Shizuku 一键授权偏差**：`dev.rikka.shizuku:api` 为新增依赖，需用户批准端点与许可后引入；当前 Shizuku 步骤为"打开 Shizuku 管理器引导授权 + 授权复检"，ADB 兜底步骤完整（逐字可复制命令 + 一键复制）。**新发现**，建议随票 35（硬化）或独立小票补齐。
4. **隐藏 API 豁免钩子**：`ReflectiveAvfVirtualMachineApi` 保留 `hiddenApiExemption` 一次性钩子；Android 13/14 设备需 HiddenApiBypass 类实现（同样是新依赖，待端点批准）；Android 15+ 公开 SDK 表面无需豁免。未提供豁免时 notes 如实记录，不伪造可用性。
5. **真机验收边界（HITL）**：看板第 1 条 AC（真机创建/启动 AVF 档实例并进入 shell、徽章显示）本会话**未执行**——依赖：用户持有的 AVF 设备 + P1 原型票 17（仍 open）的实测数据（custom VM 网络形态、instance 身份随更新行为、内核 virtio-console/hvc0 与 virtio-blk 驱动可用性）。本票已把真机冒烟所需的全部宿主侧代码与端口约定（vsock 9100/9101）备妥。
6. **guest 侧 vsock 代理**：票 36/37 范围（矩阵裁定），本票只定义端口约定与宿主传输；真机 shell 打通需 guest agent 支持 vsock 通道后才能闭环。
7. **`waitFor()` 语义**：AVF 无 QMP 等价物，guest 退出观察优先轮询 `getStatus()`；若反射面不可读则退化为"宿主 stop 后返回"，该退化在 notes 中留痕，不冒充正常退出观察。

## 8. 结论

- **代码实现**：AC-1/2/3 全部落地，AC-4 在"AVF 适配层模拟实现"上 100% 达成；全量构建零告警、零 `.log` 新增、零 Class A 资产触碰、`runtime/contract` 零改动。
- **测试**：engine-avf 6 任务 + capability 2 任务 + app 4 新 JUnit 类（12 例）全绿；全仓 40 例 app 单测中 37 绿、3 项既有失败已定界（票 33）。
- **移交**：① 提交独立 Reviewer 按 Standards+Spec 双轴复评（本实现者不自评）；② 真机 AVF 冒烟待 HITL 与票 17 数据；③ §7 新发现请 Orchestrator 裁量立项。

## 9. 独立评审整改轮（2026-10-05，CHANGES_REQUESTED → 提请二次复审）

独立评审报告（`docs/reports/reviews/34/review-report.zh-CN.md`，提交 `eced25d` 归档）裁定 CHANGES_REQUESTED。本轮按整改核对清单逐项完成如下。

### 9.1 整改项 1：架构解耦（ADR-0002，阻断级）— 已完成

- **新增策略中立会话接口 `ExecutionSession`**（`app` 启动器/适配层）：暴露 `waitFor()`、`terminalInput()`（宿主写入 shell 键入）、`terminalOutput()`（宿主读取控制台输出）、`controlInput()` / `controlOutput()`（Bridge 控制通道流对）与 `close()`（尽力而为，绝不抛出）。
- **`InstanceStrategyLauncher.Outcome` 仅暴露中立会话与策略标识**：`avfSession` / `qemuSession` 双字段删除，改为单一 `ExecutionSession session` + `strategy`（契约枚举）；`AvfLaunch` / `QemuLaunch` 接缝签名改为返回 `ExecutionSession`，策略专属会话类型不再越过启动器层。
- **`TerminalActivity` 彻底去策略化**：删除 `AvfVirtualMachineEngine` 导入与全部三处 `if (avfSession != null)` 分支，终端流与 Bridge 控制流统一从 `ExecutionSessionRegistry`（进程内中立会话注册表，替代原 `AvfTerminalStreamsRegistry`）消费；QEMU 与 AVF 两条传输路径收敛为同一代码路径。Intent 中不再携带 socket 名额外参数（由 QEMU 会话实现自行持有连接目标）。
- **`MainActivity` 去分支**：`if (avfSession != null)` 的会话分支删除，等待退出统一 `outcome.session.waitFor()`；UI 文案分支仅依据策略标识数据（`outcome.hardwareAccelerated()` / `degradationReason`），符合整改单"仅暴露 ExecutionSession 与策略标识"的裁定。
- **适配说明**：QEMU 策略会话（`QemuInstanceLauncher.Session`）与 AVF 策略会话（`AvfInstanceLauncher.AvfSession`）均实现 `ExecutionSession`，各自的 LocalSocket / vsock 传输细节（含惰性连接与重试）全部封装在启动器层实现内；`AvfVirtualMachineEngine.AvfVmSession` 不再被 `app` 的 UX 类引用。
- 附带落实评审"Data Clumps（参数泥团）"建议：`AvfInstanceLauncher.launch` / `QemuInstanceLauncher.launch` 改收 `LaunchParameters` 对象，消除 6 个平铺 String 参数。

### 9.2 整改项 2：`reflectGuestStopped` 真机反射修复（阻断级）— 已完成

`ReflectiveAvfVirtualMachineApi.reflectGuestStopped` 现在同时支持两类返回面：
- **`Number`（真机 AOSP 面）**：`getStatus()` 返回 `@Status int` 经反射装箱为 `Integer`——`0`（STOPPED）与 `2`（DELETED）判定为 guest 已退出，返回退出状态 `0`；其余（如 `1`=RUNNING）继续等待；
- **`Enum<?>`（JVM 假桩面）**：沿用名称匹配（含 `STOP`/`DEAD`）。

测试：假 SDK `VirtualMachine.getStatus()` 改为可同时模拟两种面（默认枚举桩不变；新增 `withNumericStatus(IntSupplier)` 状态源驱动桩），`AVF_VIRTUAL_MACHINE_ENGINE_TEST` 新增 `numeric_status` 场景——RUNNING(1) 下等待线程保持阻塞、置 0 后即时返回 0、置 2（DELETED）即时返回 0，覆盖真机装箱 Integer 面。

### 9.3 整改项 3：vCPU 核心数装配（规格补齐）— 已完成

- `AvfLaunchSpec` 新增 `cpuCount`（默认 `DEFAULT_CPU_COUNT = 1`，builder 校验 `[1, 32]`——32 为远超手机核心数的理智上限，超出宿主物理核数仍由平台在 VM 创建时拒绝）；
- `ReflectiveAvfVirtualMachineApi` 新增 `applyCpuCount`：安全反射调用 `setCpuCount(int)`，成功记 `setCpuCount(int): applied`；方法不存在或调用失败按可选步骤纪律写入 `reflectionNotes`（绝不静默）；
- 假 SDK `VirtualMachineCustomImageConfig.Builder` 补 `setCpuCount(int)` 记录面；测试断言默认装配 `cpuCount=1` 与显式 `.cpuCount(4)` 两条路径（`cpu_count` 场景）。

### 9.4 整改项 4：体验与防卫性修复 — 已完成

1. **`TerminalActivity.onDestroy`**：仅 `isFinishing()` 为 true 时解绑注册表并 `close()` 会话流——屏幕旋转等配置变更引发的销毁不再关闭 VM 的 vsock/本地通道，后台会话与终端重连得以保留；
2. **`AvfVirtualMachineEngine` 连接防卫**：重试参数构造器对 `connectAttempts <= 0` 抛 `IllegalArgumentException`（同时补 `connectBackoffMs < 0` 防卫），杜绝 `connectWithRetry` 中 `last.reason()` 的潜在 NPE（测试 `connect_guard`：0 / -3 / 负 backoff 三例拒绝）；
3. **`BridgeConnector.Session.socket` 契约恢复**：`serve(...)` 抽出私有 `serveControl` 计算结果后以 `new Session(outcome, socket)` 返回，抽象插槽路径重新携带已连接的 `LocalSocket` 句柄；`serveStreams(...)`（调用方持有流路径）保持 `null` 语义不变；
4. **`MainActivity` 重复代码消除**：11 行状态渲染逻辑提炼为 `renderRuntimeStatus(assessment)`，`onCreate` 与 `rerenderRuntimeStatus()` 共用单份实现。

### 9.5 范围裁定记录（评审指出但不在整改清单内）

评审 Spec 轴 AC-2 备注提到"超前装配了 `setNetworkSupported(true)`（属票 36 范围）"，但整改核对清单（1–5）与派工整改单均未包含该项。本轮**未移除**该调用，避免越权行为变更；在此如实登记，请 Orchestrator/Reviewer 裁量：若裁定应随票 36 移除，届时该可选装配步骤随网络档对齐一并处理。

### 9.6 整改轮测试证据

运行环境与 `X:` 别名配方同 §6。门禁命令与结果（均 `--offline`）：

1. `./gradlew --offline :runtime:engine-avf:check` → 全绿，新增两行证据：
   - `AVF_LAUNCH_SPEC_TEST_OK defaults=1 cpu_count=1 validation=9`
   - `AVF_VIRTUAL_MACHINE_ENGINE_TEST_OK assembly=1 cpu_count=1 retry=1 connect_guard=1 numeric_status=1 reflection_unavailable=1 manager_unavailable=1 hardware_unsupported=1 permission_denied=1`
2. `./gradlew --offline :runtime:capability:check :runtime:contract:check :runtime:engine-qemu:check :runtime:instance:check :runtime:distro:check` → 全绿（`runtime/contract` 保持零改动）；
3. `./gradlew --offline :app:testDebugUnitTest`（subst `X:` 下）→ 40 例中 37 绿、3 失败，失败项与整改前完全一致（`AndroidSafExportAdapterTest` 3 例，票 33 范围，`git diff` 0 行定界）；
4. `./gradlew --offline :app:lintDebug` → BUILD SUCCESSFUL（零 lint 错误）；
5. `./gradlew --offline :app:assembleDebug` → BUILD SUCCESSFUL，`app-debug.apk` 1,031,325 字节（整改前 991,320 字节，增量来自中立会话层与 QEMU 会话实现的新增代码）；
6. 批末负例检查：`git status --porcelain --untracked-files=all | grep '\.log$'` 计数 = **0**。

### 9.7 整改轮提交

| Commit | 内容 |
| --- | --- |
| `47a8b01` | fix(engine-avf): 真机 getStatus 数值面感知、vCPU 装配与连接重试防卫（含假 SDK 与测试） |
| `fea79b0` | refactor(app): 提炼策略中立 ExecutionSession，根除 UX 层策略泄漏（含 MainActivity 去重与参数对象化） |
| `0c9f8d1` | fix(app): BridgeConnector 恢复抽象插槽路径 Session.socket 句柄传递 |
| 本提交 | docs(ticket-34): 实施报告整改轮章节与看板状态更新 |

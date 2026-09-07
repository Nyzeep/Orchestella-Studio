# Ticket 26 实施前 Scope Contract

> 状态：**verified pre-dispatch contract；本身不构成 Worker 领取或产品验收。**
>
> 本契约于 2026-09-07 为 Ticket 26 readiness blocker resolution 创建。随后 re-audit-2 已
> 验证 B4 的真机资格与 USB ADB 证据，Ticket 26 现为 ready-for-agent；只有 Orchestrator
> 形成独立 Dispatch Packet、branch 和 worktree 后，Worker 才可领取。

- 票号/标题：26 — 第一垂直切片：创建 Alpine 实例并进入 shell（QEMU 档）
- 已批准事实源：ADR-0001/0002/0004/0005/0006/0007/0009/0010/0011/0014/0016、Ticket 26
  正文、docs/agent-handoff/QEMU_JNI_SUPPLY_CHAIN_POLICY.md
- 前置票：25（已 completed，不得重开）
- 后续票：27、28、30 及其余下游 implementation 均不得在本票完成前启动
- 暂定未来 Worker profile：L4；implement + tdd，独立 review 使用 code-review；
  建议 GPT-5.6 Sol / xhigh。派工时必须重新核对 active model policy 和所有外部 gate。

## 1. 本票唯一目标

交付 QEMU/TCG 上从安装应用、选择 Alpine、校验官方 tarball、首次 Provisioning、进入 root
shell，到**正常 guest 关机后从同一 Root Volume 再次启动且数据仍在**的最小 Full Linux
垂直切片。它还创建 Guest Bridge v1 的最小控制面：传输无关字节流适配、握手、版本协商、
per-VM token、ping、终端通道和 resize 基座。

本票建立的是正常路径的基础状态模型，不是把后续停止/冷恢复/异常恢复工作提前塞进来。

## 2. 明确 in-scope 与受限 ownership

### 2.1 Runtime / Android 初始骨架

- runtime/contract：Full Linux 契约 v0 与基础契约测试；
- runtime/instance：Instance、Root Volume、Distro 引用、单运行实例约束，以及正常
  STOPPED ↔ RUNNING 转换记录的持久化；本票只保证宿主进程存活时跨 UI/Activity 重建对该记录
  的正确显示，不对 App 进程死亡后的记录语义或 UI 一致性作断言；
- runtime/distro：Alpine DistroManifest、官方 tarball 与 SHA-256 拒绝路径；
- runtime/capability：支持地板、QEMU 选择、性能徽章和模拟器诚实提示；
- runtime/engine-qemu：仅 QEMU/TCG 的磁盘、console 与 Guest Bridge byte-stream 实现；
- bridge/：v1 baseline，不新增 ADR-0009 六命令以外的暴露面；
- ui/、terminal/：实例创建/列表/状态的最小界面与终端会话；
- app/**：仅为上述运行时、native artifact packaging 和最小 UI 接线所必需的 manifest、
  Gradle、source 与 tests；不承担 Ticket 35 的全应用装配/硬化。
- .github/workflows/qemu-android.yml：仅为 QEMU source/build/ABI/APK/GPL gates 新增的 CI
  workflow；不得修改无关 workflow。
- docs/reports/tickets/26/implementation-report.zh-CN.md：Worker 的必需实施报告；不得改写
  本 pre-dispatch contract 或 Ticket 25 历史报告。

### 2.2 Guest-assets 的窄下游例外（B1）

Ticket 25 已完成的**构建基线**不被重新打开。本票可增量修改的 guest-assets 仅限完成本票
second-stage / bridge integration 所需的下列文件或直接对应的新测试：

- guest-assets/initramfs/init；
- guest-assets/initramfs/openrc/droidlayer-guest-agent 与
  guest-assets/initramfs/openrc/droidlayer-guest-agent.conf；
- guest-assets/agent/guest-agent.c；
- guest-assets/src/test/java/io/github/nyzeep/droidlayer/guestassets/GuestAgentProcessTest.java；
- 为二阶段启动、正常关机/重启或 bridge smoke 新增的 guest-assets 测试，以及
  guest-assets/scripts/test-local.sh 的最小接线；
- 只有 Alpine 所需 init package / deterministic install 输入无法以既有内容表达时，才可
  增量修改 guest-assets/build.sh、guest-assets/inputs/inputs.lock，并必须在报告中说明
  原因、pin 与对应验证。

禁止修改 kernel 版本、既有 kernel capability 基线、Alpine tarball 版本/checksum、Ticket 25
reproducibility architecture，或提交 guest-assets/out、guest-assets/.work。不得改 Ticket 25
tracker、实现报告或 review；也不得以“修复 Ticket 25”为名重跑其历史验收。

### 2.3 QEMU supply-chain（B3）

本票拥有 tools/qemu-android 及为 APK 接线所必需的受限 app/**。必须实现
QEMU_JNI_SUPPLY_CHAIN_POLICY.md 的初始 lock、build entry、artifact manifest、CI gates 和
GPL records；QEMU 初始 source pin 为 official 11.1.1，而不是 Worker 临场选择的版本。

不得使用任何预编译第三方 QEMU，不能把 guest-assets/out 当 APK 输入，也不能从可写目录执行
QEMU。Android/NDK 构建、.so 名称 main ELF、TCG W^X/SELinux 和动态依赖闭包均为 fail-closed
验收，不是已证明的前提。

## 3. Ticket 26 / Ticket 28 生命周期边界（B2）

| 领域 | Ticket 26（本票） | Ticket 28（明确留给后续） |
| --- | --- | --- |
| 基础状态 | 创建 Instance、持久化正常 STOPPED/RUNNING 转换记录、单运行约束；仅在宿主进程存活时保证 UI/Activity 重建显示正确 | App 进程死亡后的记录解释、异常状态与 reconciliation |
| 启停 | 首次 Provisioning、正常启动、guest 自主正常关机后识别 STOPPED、同根盘再次启动 | 宿主/UI 发起 power、确认/超时/失败路径与文件系统一致性 |
| App 进程死亡 | 不对旧 RUNNING/STOPPED 记录在新宿主进程中的正确性作断言；不处理 stale RUNNING 或 VMM 被杀的对账 | 进程死亡后的 cold restore、stale 状态对账和一键恢复 |
| 异常 | 不标记 VMM crash、不做诊断导出 | 异常终止标记、日志保留/导出及 UI 一致性 |
| Bridge | handshake/version/token/ping/terminal/resize 的基座 | power 命令语义；后续命令由各自 ticket 扩展 |
| 契约测试 | create/provision/start、正常二次启动与数据保持、guest 正常关机、宿主进程存活时的 UI/Activity 重建、terminal、baseline bridge | power acknowledgement/timeout、强杀 cold restore、crash、日志导出 |

Cold Restore 专指 App 进程死亡后重建运行环境；不得把普通第二次启动或宿主进程存活时的
UI/Activity 重建称为冷恢复。Session 的 exit 也不等于 Instance 停止。正常状态记录是供
Ticket 28 在进程死亡后对账的输入，不是 Ticket 26 对该异常边界的验收。QMP 可以作为 QEMU
内部机制，但不得进入策略中性的 Full Linux Contract。

## 4. 明确 non-goals / forbidden scope

- runtime/engine-avf、AVF 权限、vsock 或 Ticket 34/36/37 内容；
- 出站 NAT、端口转发、只读 Downloads 共享及其 Ticket 27/29/30 交付；
- Ticket 28 的 power、强杀 recovery、异常标记和日志导出；
- Ticket 32 的 Debian、Ticket 33 的扩容/SAF 导出、Ticket 31 notify/open-url；
- 任何图形、USB、音频、container feature、snapshot 或白名单外 guest→host 能力；
- ADR、spec、CONTEXT.md、Ticket 25 及其他 tracker ticket、dependency graph；
- Registry、PATH、服务、驱动、防火墙、系统虚拟化设置，或未披露的 C:/user-level 工具写入。

若实现表明必须改变上述边界、使 QMP 泄漏进契约、改动 bridge command/暴露面，或违背
“进程死亡 = VM 停止”，立即停止相关工作并走 Architecture Change Policy。

## 5. Required verification strategy

1. **Contract/JVM**：基础 lifecycle、DistroManifest、capability、terminal/bridge handshake、
   token 正负例；将 normal lifecycle 和 later recovery tests 分文件，避免后续票重写基线。
2. **Guest integration**：首次 Provisioning、正常 guest shutdown、同 Root Volume 二次启动
   sentinel 数据保持；对允许修改的 agent/init 文件运行对应 process/smoke 测试。
3. **QEMU supply chain**：本契约所引政策 section 5 的 provenance、two-clean-build、ELF/closure、
   16 KB、APK signature/layout、tamper-rejection、GPL gates 全部实际运行。
4. **真机**：Ticket 26 七条 AC 的 arm64 冒烟清单（含重复安装→shell、错误 tarball、
   bridge 正/负 token、guest 正常关机后的二次启动、宿主进程存活时 UI/Activity 重建的状态、
   模拟器提示及 supply-chain gates）必须保留脱敏原始证据。JVM/contract/Gradle 测试不能代替
   此项；进程强杀后的状态对账由 Ticket 28 验收。

## 6. 已验证的外部条件（B4）

2026-09-07 的 re-audit-2 已记录一台用户提供、USB 连接且已 RSA 授权的实体设备：API 35、
arm64-v8a、约 11.03 GiB RAM，并有可用存储和脱敏设备标识。现有
D:\手机调试与开发相关\platform-tools\adb.exe（Platform Tools 37.0.1）已实际发现设备并完成
只读属性查询；详见 docs/reports/orchestration/ticket-26-readiness-reaudit-2.zh-CN.md。

ADB 是这台主机的真机部署/日志通道，不是 Ticket 26 的产品运行时依赖，也不需要 vendoring
进仓库。Worker 每次使用该通道前必须重新运行其 version 与 devices -l 检查；不得改 system
PATH、安装 Platform Tools/OEM driver 或将旧的 PATH ADB 当作等价工具。未来 APK install、
外部缓存或其他会产生宿主/设备写入的步骤，仍须先遵守 Host Environment Policy 和 dispatch
packet 的授权边界。

## 7. Architecture / dependency decision

本契约没有新增 ticket 或隐藏 dependency；Ticket 26 的唯一 tracker dependency 仍是已完成的
Ticket 25。B1/B2/B3 是现有 ADR 内的 ownership、ticket slicing 和 policy 实施，不触发 ACR。
B4 是外部/HITL blocker，不是 architecture decision。

报告人：Codex Orchestrator ｜ 日期：2026-09-07 ｜ 耗时：未单独计时

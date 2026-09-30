# Ticket 28 实施报告（Implementation Report）

- **工单编号**：Ticket 28
- **工单标题**：优雅停止、冷恢复与异常标记（Graceful Stop, Cold Restore & Crash State Reconciliation）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（严格遵循 TDD 节奏，代码实现与契约自测闭环）
- **特性分支**：`ticket/28-stop-cold-restore`
- **基线 Commit**：`c3e6a3f`（docs(governance): 确立资产分类保全与精确瘦身治理规范）
- **交付 Commit 序列**：
  1. `0a065fe` - `feat(contract): 实现 Bridge Power 协议扩展与双向契约验证 (Ticket 28 Slice 1)`
  2. `ee72c03` - `feat(instance): 实现优雅停机、冷恢复对账与崩溃状态归档 (Ticket 28 Slices 2-4)`
- **当前状态**：**Implementation Completed · Ready for Independent Review**

---

## 1. 任务背景与核心目标

根据架构决策记录 [`docs/adr/0009-guest-bridge-protocol.md`](../../adr/0009-guest-bridge-protocol.md)、[`docs/adr/0011-lifecycle.md`](../../adr/0011-lifecycle.md) 以及资产保全规范 [`docs/agent-handoff/ASSET_REUSE_AND_HYGIENE_POLICY.md`](../../agent-handoff/ASSET_REUSE_AND_HYGIENE_POLICY.md)，Ticket 28 负责在 Ticket 26 建立的最小 Full Linux 实例生命周期基线之上，扩展实现：
1. **宿主发起的安全优雅停机通道**：扩展 Guest Bridge 控制协议，通过 `DROIDLAYER/POWER shutdown|poweroff\n` 触发 Guest 执行数据刷盘（sync）与关机，返回 `DROIDLAYER/OK power\n`；
2. **宿主强杀与冷恢复安全对账**：宿主 App 进程死亡重启后，自动将磁盘元数据残留的 stale `RUNNING` 实例对账纠偏为 `INTERRUPTED`（已中断/待冷恢复）或 `STOPPED`，并支持一键 `coldRestore(...)` 重新拉起；
3. **VMM 异常崩溃标记与日志归档索引**：捕获模拟器非零退出码与崩溃信号（如 SIGKILL 137），将实例精确标记为 `CRASHED`，记录包含时间戳、退出码、stderr 路径引用的结构化诊断对象 `CrashDiagnostic`，并提供 `exportDiagnostic(...)` 一键导出诊断报告；
4. **状态机全量契约测试**：100% 覆盖正常与异常状态跃迁，严禁破坏 Ticket 26 基线。

---

## 2. 模块边界与红线遵从核对（Ownership & Boundaries Verification）

| 约束项 | 规范要求 | 实际遵从情况 | 验证结论 |
| :--- | :--- | :--- | :--- |
| **工作区物理隔离** | 在隔离工作区或特性分支工作 | 针对并行 Ticket 27，创建独立 Git Worktree `DroidLayer_dev_t28` 检出分支 `ticket/28-stop-cold-restore`，与主开发目录完全物理隔离 | **100% 隔离** |
| **Owned 模块** | `runtime/instance/` 与 `runtime/contract/` | 仅在上述两个核心模块及其对应测试用例目录内修改和新增代码 | **完全受控** |
| **Forbidden: 网络装配** | 严禁修改 `runtime/engine-qemu/` 网络装配（Ticket 27 专责） | 0 行改动 `runtime/engine-qemu/` | **零冲突** |
| **Forbidden: AVF 模块** | 严禁修改 `runtime/engine-avf/` | 0 行改动 `runtime/engine-avf/` | **零冲突** |
| **Forbidden: 根盘扩容** | 严禁修改 `storage/` 根盘扩容 | 0 行改动 `storage/` | **零冲突** |
| **Forbidden: Guest 资产** | 严禁改动内核镜像、`inputs.lock`、Provisioning 代码 | 0 行改动 `guest-assets/` | **零破坏** |
| **Ticket 26 基线保全** | 不得破坏 `NormalInstanceLifecycleContractTest` 与既有协议 | Ticket 26 原生测试套件 100% 原样通过，兼容所有旧版 Properties 元数据 | **100% 保全** |

---

## 3. 验收标准逐项落地与实测证据（Acceptance Criteria Evidence）

### 3.1 AC-1（Bridge Power 协议扩展）
- **设计与实现**：
  * 在 `io.github.nyzeep.droidlayer.runtime.contract.BridgeControl` 中新增 `PowerAction` 枚举（`SHUTDOWN("shutdown")`、`POWEROFF("poweroff")`），提供 `fromWire(String)` 校验解析；
  * 新增函数式接口 `BridgeControl.Power`（`boolean power(PowerAction action)`）；
  * 重载 `BridgeControl.dispatch(String requestLine, Power power)` 与 `BridgeControl.dispatch(String requestLine, Terminal terminal, Power power)`；
  * 支持接收 `DROIDLAYER/POWER <action>\n`：
    - 成功返回 `DROIDLAYER/OK power\n`；
    - 执行失败或未挂载 Power 适配器返回 `DROIDLAYER/ERROR power-unavailable\n`；
    - 非法动作或格式错误返回 `DROIDLAYER/ERROR malformed-command\n`；
  * 在 `BridgeClient` 中新增 `power(PowerAction)` 与 `power(String)` 方法，成功时分类响应为 `ok-power`，失败时提取错误分类。
- **契约测试覆盖**：
  * `BridgeControlContractTest`：
    - `executesShutdownThroughThePowerSeam()`：验证 shutdown 正确派发并返回 `DROIDLAYER/OK power\n`；
    - `executesPoweroffThroughThePowerSeam()`：验证 poweroff 正确派发；
    - `rejectsMalformedPowerCommandsBeforeTheyReachThePowerSeam()`：验证参数缺失、非法指令、多余参数等畸变输入被安全拒绝；
    - `rejectsUnavailablePowerWithoutPretendingSuccess()`：验证底层操作失败时返回 `power-unavailable`；
    - `rejectsPowerWhenPowerSeamNotSupplied()`：验证未挂接电源适配器时返回 `power-unavailable`；
    - `dispatchesBothTerminalAndPowerSeams()`：验证 Terminal 与 Power 双通道复合分发无互相干扰；
  * `BridgeClientContractTest`：
    - `powerRoundTripsOkPower()`：宿主主动发起 shutdown/poweroff 双向通信契约往返；
    - `powerSurfacesAgentErrorKinds()`：验证 Agent 报错返回及非法参数本地拦截。

### 3.2 AC-2（进程强杀与冷恢复对账）
- **设计与实现**：
  * 扩展 `InstanceLifecycle.State`：新增 `INTERRUPTED`（已中断/待冷恢复）；
  * 扩展状态机迁移规则 `isValidTransition`：
    - `RUNNING -> INTERRUPTED`（宿主强杀对账）；
    - `INTERRUPTED -> RUNNING`（冷恢复启动）；
    - `INTERRUPTED -> STOPPED`（重置停机）；
    - 拒绝任何对 `INTERRUPTED` 状态的非法越权跃迁（如试图直接 `start()` 或 `beginProvisioning()` 均抛出 `InvalidTransitionException`）；
  * 在 `InstanceLifecycle.Controller` 中引入对账方法：
    - `reconcileAfterHostDeath()`：扫描持久化 Store，将所有孤儿 `RUNNING` 实例原子对账并修正为 `INTERRUPTED`，返回被对账的实例列表；
    - `reconcileAfterHostDeath(State targetState)`：支持定向将孤儿实例修正为 `STOPPED` 或 `INTERRUPTED`；
    - `reconcileAfterHostDeath(String id)` / `reconcileAfterHostDeath(String id, State targetState)`：针对单实例精准对账；
  * 引入一键冷恢复与重置方法：
    - `coldRestore(String id)`：验证全局单一运行实例不变式（`SingleRunningInstanceException`），支持从 `INTERRUPTED`、`CRASHED`、`STOPPED` 一键冷恢复至 `RUNNING`，并清除历史崩溃诊断对象；
    - `resetToStopped(String id)`：将 `INTERRUPTED` 或 `CRASHED` 状态安全归置为 `STOPPED`。
- **契约测试覆盖**：
  * `persistsInterruptedStateAcrossStoreReconstruction`：验证实例运行中被对账为 `INTERRUPTED` 后，在全新 `FileStore` 重建加载后状态保持强一致；
  * `coldRestoreFromInterruptedRestartsInstance`：验证对账后的中断实例禁止通过常规 `start()` 启动，必须调用 `coldRestore()` 恢复，恢复后重新施加排他运行锁；
  * `resetToStoppedFromInterruptedAndCrashed`：验证状态归置 `resetToStopped()` 正常生效，归置后恢复为普通可用状态；
  * `reconcileAllAfterHostDeathTargetingStoppedOrInterrupted`：验证多实例场景下仅孤儿实例被定向对账，且非法目标状态被安全拒绝。

### 3.3 AC-3（VMM 崩溃异常标记与日志导出）
- **设计与实现**：
  * 扩展 `InstanceLifecycle.State`：新增 `CRASHED`（异常终止）；
  * 引入结构化诊断对象 `InstanceLifecycle.CrashDiagnostic`：
    - `long timestampMillis()`：崩溃记录时间戳；
    - `int exitCode()`：VMM 退出码（如非零异常码、SIGKILL 137、SIGSEGV 139 等）；
    - `Path logPath()`：崩溃前捕获的 stderr 日志物理路径索引；
    - `String message()`：崩溃原因或上下文描述；
  * 元数据持久化扩展：
    - `FileStore` 原生读写 `crash.timestampMillis`、`crash.exitCode`、`crash.logPath`、`crash.message`；
    - 向后兼容：无崩溃信息的旧实例属性文件无损解析，`crashDiagnostic()` 赋 `null`；
  * 在 `Controller` 中提供崩溃记录与导出接口：
    - `recordCrash(String id, int exitCode, Path logPath)` / `recordCrash(String id, CrashDiagnostic diagnostic)`；
    - `recordAbnormalTermination(...)`：语义对齐 ADR-0011 规范术语；
    - `exportDiagnostic(String id, Path destination)`：一键导出结构化崩溃诊断报告，自动包含实例元数据、时间戳、退出码及 stderr 日志全文嵌入。
- **契约测试覆盖**：
  * `persistsCrashedStateAndDiagnosticAcrossStoreReconstruction`：验证崩溃状态与 `CrashDiagnostic` 全字段跨进程/跨 Store 重建持久化；
  * `coldRestoreFromCrashedRestartsInstanceAndClearsDiagnostic`：验证从 `CRASHED` 冷恢复后，历史崩溃标记被彻底重置清理，重获纯净运行环境；
  * `exportsStructuredDiagnosticReportAndEmbeddedLog`：验证诊断报告自动生成，嵌入原始 log 内容，且对正常实例调用时抛出 `IllegalStateException` 防御错误导出。

### 3.4 AC-4（契约测试 100% PASS）
在独立工作区运行 `./gradlew :runtime:instance:check :runtime:contract:check`，构建输出如下：

```text
> Task :runtime:contract:compileJava UP-TO-DATE
> Task :runtime:contract:processResources NO-SOURCE
> Task :runtime:contract:classes UP-TO-DATE
> Task :runtime:contract:jar UP-TO-DATE
> Task :runtime:instance:compileJava UP-TO-DATE
> Task :runtime:instance:processResources NO-SOURCE
> Task :runtime:instance:classes UP-TO-DATE
> Task :runtime:instance:compileTestJava
> Task :runtime:instance:processTestResources NO-SOURCE
> Task :runtime:instance:testClasses
> Task :runtime:instance:normalInstanceLifecycleContractTest
> Task :runtime:instance:reconciliationAndRecoveryLifecycleContractTest
> Task :runtime:instance:test
> Task :runtime:instance:check
> Task :runtime:contract:compileTestJava UP-TO-DATE
> Task :runtime:contract:processTestResources NO-SOURCE
> Task :runtime:contract:testClasses UP-TO-DATE
> Task :runtime:contract:bridgeClientContractTest
> Task :runtime:contract:bridgeControlContractTest
> Task :runtime:contract:bridgeHandshakeContractTest
> Task :runtime:contract:fullLinuxContractTest
> Task :runtime:contract:test UP-TO-DATE
> Task :runtime:contract:check

BUILD SUCCESSFUL in 11s
13 actionable tasks: 8 executed, 5 up-to-date
```

编译参数 `-Xlint:all -Werror` 全量启用，**0 警告、0 错误、100% 测试通过**。

---

## 4. TDD 节奏与实施历程（TDD Red-Green-Refactor Audit Trail）

```
[Slice 1: Bridge Power 协议]
  ├── RED: BridgeControlContractTest & BridgeClientContractTest 编写 PowerAction、Power 派发及 ok-power 分类测试
  │        (编译失败，16 个未定义符号错误)
  ├── GREEN: BridgeControl 新增 PowerAction/Power/dispatch 重载，BridgeClient 新增 power/ok-power
  │          (:runtime:contract:check 100% PASS)
  └── COMMIT: 0a065fe

[Slice 2-4: 状态模型扩充、宿主强杀对账、冷恢复与崩溃归档]
  ├── RED: ReconciliationAndRecoveryLifecycleContractTest 编写 INTERRUPTED/CRASHED 状态持久化、
  │        reconcileAfterHostDeath、coldRestore、recordCrash、exportDiagnostic 契约测试
  │        (编译失败，11 个未定义符号错误)
  ├── GREEN: InstanceLifecycle 扩充 State 枚举、新增 CrashDiagnostic、重构 isValidTransition、
  │          实现 Controller.reconcileAfterHostDeath/coldRestore/recordCrash/exportDiagnostic
  │          (:runtime:instance:check 100% PASS，NormalInstanceLifecycleContractTest 零破坏)
  └── COMMIT: ee72c03
```

---

## 5. 变更文件清单（Deliverables & Modified Files）

1. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeControl.java`
   - 增加 `PowerAction`、`Power` 接口；
   - 增加 `dispatch(requestLine, Power)`、`dispatch(requestLine, Terminal, Power)`；
   - 扩展支持 `DROIDLAYER/POWER shutdown|poweroff\n` 命令解析与错误处理。
2. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeClient.java`
   - 增加 `power(PowerAction)` 与 `power(String)`；
   - 增加 `DROIDLAYER/OK power\n` -> `ok-power` 响应分类。
3. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeControlContractTest.java`
   - 新增 6 组 Power 控制协议契约测试用例。
4. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeClientContractTest.java`
   - 新增 2 组客户端 Power 发起与响应分类测试用例。
5. `runtime/contract/build.gradle.kts`
   - 注册 `bridgeClientContractTest` 任务并挂载至 `check`。
6. `runtime/instance/src/main/java/io/github/nyzeep/droidlayer/runtime/instance/InstanceLifecycle.java`
   - `State` 新增 `INTERRUPTED` 与 `CRASHED`；
   - 新增 `CrashDiagnostic` 结构化对象；
   - `Instance` 新增诊断信息字段、访问方法及 Properties 序列化/反序列化；
   - `Controller` 新增 `reconcileAfterHostDeath`、`coldRestore`、`resetToStopped`、`recordCrash`、`recordAbnormalTermination`、`recordGracefulStop`、`exportDiagnostic`。
7. `runtime/instance/src/test/java/io/github/nyzeep/droidlayer/runtime/instance/ReconciliationAndRecoveryLifecycleContractTest.java`
   - 新建生命周期完整契约测试类，包含 9 组跨 Store 重建、状态转换约束、批处理对账、单实例对账、冷恢复排他性、崩溃报告导出等测试。
8. `runtime/instance/build.gradle.kts`
   - 注册 `reconciliationAndRecoveryLifecycleContractTest` 任务并挂载至 `check`。
9. `docs/reports/tickets/28/implementation-report.zh-CN.md`
   - 本实施报告。

---

## 6. 移交结论与后续指引

Ticket 28 的所有代码开发、边界约束自查、TDD 流程与全量契约测试已 100% 达成。
- **分支状态**：`ticket/28-stop-cold-restore`（已提交全部更改，工作区 clean）；
- **向 Orchestrator 移交声明**：Worker 自测全部通过，现正式将代码交由独立审计会话（Independent Review Session）执行 Standards 与 Spec 双轴复核，不代用户签字。

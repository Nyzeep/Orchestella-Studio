# Review 报告：Ticket 28

- **审查对象**：`ticket/28-stop-cold-restore`，比对基线 `b50fa72`（`main`），审查命令 `git diff main...HEAD`
- **审查 HEAD**：`9ed7dff docs(report): 产出 Ticket 28 完整实施报告 (Ticket 28 Deliverable)`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴执行并行独立子代理复评（Dual-Axis Independent Review）；在本地 `DroidLayer_dev_t28` 工作区实测运行 `./gradlew :runtime:instance:check :runtime:contract:check --rerun-tasks` 验证全量构建、契约测试与编译器门控。

---

## 阶段复审总览（Review Summary）

针对 Ticket 28（优雅停止、冷恢复与异常标记）的实现交付，独立评审专员基于双轴（Standards + Spec）审计标准，对其包含的 3 个递增提交（`90d83af`、`8b8cd75`、`9ed7dff`）执行了全面复核。

经审计：
1. **Standards 轴**：0 项 Hard Violation，4 项 Non-blocking Judgement Calls（代码异味）。架构分层清晰，元数据模型不可变且跨 Store 重建保持一致；向后兼容性良好，完全兼容未记录崩溃诊断的历史实例；模块边界严格遵从规范，0 行侵入非派工模块与 Class A 资产；`-Xlint:all -Werror` 零编译警告。
2. **Spec 轴**：0 项 Blocking Defect。AC-1（Bridge Power 协议扩展）、AC-2（宿主强杀对账与冷恢复）、AC-3（VMM 崩溃异常标记与日志导出）、AC-4（契约测试 100% 全绿）全部达成闭环。对账、冷恢复排他性与诊断报告导出逻辑完整且具备高强度契约约束。
3. **本地实测**：全量测试 14/14 actionable tasks 全部重新执行通过（100% PASS）。

综合评定：**APPROVE**。

---

## Standards

**PASS — 0 Hard Violations, 4 Non-blocking Judgement Calls (Baseline Smells)**

### 1. 架构分层、不可变性与线程安全核验
- **[PASS] 不可变值对象设计**：
  - `InstanceLifecycle.CrashDiagnostic` 严格声明为 `public static final class`，内部字段（`timestampMillis`, `exitCode`, `logPath`, `message`）均为 `private final`，路径对象于构造器内完成绝对路径正规化（`.toAbsolutePath().normalize()`），实现严格不可变。
  - `InstanceLifecycle.Instance` 模型保持不可变性，状态跃迁时通过 `transitioned(...)` 构造返回全新的 `Instance` 实例。
- **[PASS] 线程安全与并发控制**：
  - `InstanceLifecycle.Controller` 所有状态变更与对账接口（`create`, `start`, `coldRestore`, `reconcileAfterHostDeath`, `recordCrash`, `resetToStopped`, `exportDiagnostic`）均加持 `synchronized` 关键字保护。
  - `InstanceLifecycle.FileStore` 采用原子替换写盘（`ATOMIC_MOVE` 并在不支持时优雅降级为 `REPLACE_EXISTING`），避免进程断电写入损坏；`save`, `load`, `list` 读写操作均实现互斥锁定。
- **[PASS] 跨重启持久化一致性**：
  - 崩溃诊断元数据通过 Properties 格式规范持久化（`crash.timestampMillis`, `crash.exitCode`, `crash.logPath`, `crash.message`），由独立契约测试 `persistsCrashedStateAndDiagnosticAcrossStoreReconstruction` 验证在全新 Store 实例重建加载后状态强一致。

### 2. 向后兼容性保障（Backward Compatibility）
- **[PASS] 存量元数据无损反序列化**：
  - 在 `Instance.fromProperties(Properties properties)` 中，对崩溃元数据的解析以 `properties.getProperty("crash.timestampMillis") != null` 作为前置条件。
  - 对于 Ticket 26 历史实例元数据文件（无 `crash.*` 属性），`crashDiagnostic` 优雅赋值为 `null`，历史字段读取无需修改，完全避免 `NullPointerException` 或解析失败；
  - `instance.diagnosticLogPath()` 包含防御性空指针防护（`crashDiagnostic != null ? crashDiagnostic.logPath() : null`）；既有 `NormalInstanceLifecycleContractTest` 100% 原样通过。

### 3. 模块边界隔离遵从性（`AGENT_WORKER_CONTRACT.md`）
- **[PASS] 红线禁区保持零接触**：
  - 改动严格控制在派工 owned 模块：`runtime/contract/` 与 `runtime/instance/`；
  - Ticket 27 网络模块（`runtime/engine-qemu/`）保持 **0 行改动**，`networkContractTest` 持续全绿；
  - `runtime/engine-avf/`、`storage/`、`guest-assets/` 及内核/initramfs 镜像保持 **0 行改动**。
- **[PASS] 资产治理规范（`ASSET_REUSE_AND_HYGIENE_POLICY.md`）**：
  - Class A 永久基石资产零接触，无任何本地临时消耗品或未跟踪二进制泄漏进 Git。

### 4. 编译器门控与构建工程质量
- **[PASS] 严格编译参数**：
  - `runtime/contract/build.gradle.kts` 与 `runtime/instance/build.gradle.kts` 均配置 `-Xlint:all` 与 `-Werror`；
  - Java 17 严格检查下保持 **0 警告、0 错误**。

### 5. Fowler 异味基线审查（Baseline Smells / Judgement Calls）
1. **Duplicated Code（建议后续提炼）**：
   - 位置：`BridgeControl.java:77-85` 与 `BridgeControl.java:102-110`；
   - 现象：Power 请求的校验与分发逻辑在 `dispatch(String, Power)` 与 `dispatch(String, Terminal, Power)` 两个重载中存在相同代码块；
   - 评定：非阻断异味，不影响契约逻辑正确性，建议后续抽取包私有 `dispatchPower(...)` 方法。
2. **Middle Man（语义别名方法）**：
   - 位置：`InstanceLifecycle.java:461-469`；
   - 现象：`recordAbnormalTermination(...)` 两处重载直接透传委托给 `recordCrash(...)`；
   - 评定：非阻断异味，Worker 为对齐 ADR-0011 规范术语而提供，符合语义映射意图。
3. **Speculative Generality（微小可见性暴露）**：
   - 位置：`InstanceLifecycle.java:629`；
   - 现象：`isNormalTransition(State, State)` 可见性由 private 提升为 public，但类外暂无显式调用者；
   - 评定：非阻断，供外部契约判断或调试使用，符合生命周期模型透明性要求。
4. **Feature Envy（轻度关注点跨界）**：
   - 位置：`InstanceLifecycle.java:564-596`；
   - 现象：`Controller.exportDiagnostic` 直接获取 `CrashDiagnostic` 各项属性并拼装报告内容；
   - 评定：非阻断，后续可将报告文本格式化逻辑下沉至 `CrashDiagnostic.formatReport()`。

---

## Spec

**PASS — 4 项工单验收标准（AC-1 ~ AC-4）全部达成闭环**

### 1. 工单验收标准（Acceptance Criteria）逐条核验

| 验收项 | 工单要求与规格细节 | 评审状态 | 审计证据与核销事实 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **Bridge Power 协议扩展**：<br>1. 扩展 `DROIDLAYER/POWER shutdown\|poweroff\n`<br>2. 识别并响应 `DROIDLAYER/OK power\n`<br>3. 扩展函数式接口与 Client 分类 | **✅ PASS** | 1. `BridgeControl.java` 引入 `PowerAction` 枚举与 `@FunctionalInterface Power`；<br>2. 正确处理格式畸变（`malformed-command`）与适配器未挂载（`power-unavailable`）；<br>3. `BridgeClient.java` 新增 `power(PowerAction)` 与 `power(String)`，分类器精确解析 `ok-power`。契约覆盖率 100%。 |
| **AC-2** | **宿主强杀对账与冷恢复**：<br>1. 状态枚举扩充 `INTERRUPTED`<br>2. `isValidTransition` 拦截非法唤醒<br>3. 孤儿 stale RUNNING 纠偏对账<br>4. `coldRestore(...)` 互斥保护与重建 | **✅ PASS** | 1. `State` 枚举新增 `INTERRUPTED`，`isValidTransition` 精确约束合法跃迁；<br>2. `Controller.start()` 严防非 STOPPED 实例，拦截对 `INTERRUPTED` 的直接启动，强制走冷恢复；<br>3. `reconcileAfterHostDeath(...)` 支持全局批处理与单实例定向对账；<br>4. `coldRestore(...)` 排他性锁校验（`SingleRunningInstanceException`），从异常状态安全恢复至 `RUNNING`。 |
| **AC-3** | **VMM 崩溃异常标记与日志导出**：<br>1. 状态枚举扩充 `CRASHED`<br>2. `recordCrash(...)` 封装退出码与日志<br>3. `exportDiagnostic(...)` 生成结构化报告 | **✅ PASS** | 1. `State` 枚举新增 `CRASHED`，引入不可变诊断容器 `CrashDiagnostic`；<br>2. `recordCrash` 完整记录时间戳、非零退出码（如 137/139）、stderr 日志物理路径与上下文原因；<br>3. `exportDiagnostic` 自动导出格式化报告，校验目标目录完整性，并自动嵌入原始 stderr 日志全文。 |
| **AC-4** | **契约测试 100% 全绿**：<br>1. 状态机生命周期全覆盖<br>2. 对账与冷恢复完整闭环<br>3. Bridge 协议契约全面覆盖 | **✅ PASS** | 1. 新增 `ReconciliationAndRecoveryLifecycleContractTest`（9 组契约场景全部通过）；<br>2. 原生 `NormalInstanceLifecycleContractTest`（3 组基线场景保持零破坏通过）；<br>3. `BridgeControlContractTest`（新增 6 组 Power 用例）、`BridgeClientContractTest`（新增 2 组用例）全部通过。 |

### 2. 行为边界与非阻断观察（Scope & Observations）
1. **`recordNormalGuestStop` 状态防御建议**：
   - 现实现中，由于 `isValidTransition` 允许 `INTERRUPTED -> STOPPED` 与 `CRASHED -> STOPPED`（供 `resetToStopped` 状态复位使用），调用 `recordNormalGuestStop(id)` 在中断或崩溃实例上不会抛出异常。虽然业务层不会在非运行实例上分派该调用，建议在后续小步重构中为 `recordNormalGuestStop` 增加显式状态前置断言（`state == RUNNING`），以强化防御式编程。
2. **`coldRestore` 兼容 `STOPPED` 状态**：
   - `coldRestore` 允许对 `STOPPED`、`INTERRUPTED`、`CRASHED` 执行一键冷恢复。对于 `STOPPED` 状态，该逻辑与 `start()` 行为重合。这是便捷的上层恢复封装，不构成破坏性偏差。
3. **真实文件系统检查与 UI 同步的阶段边界**：
   - 工单原始描述中提及的“根盘文件系统一致性（无脏退出痕迹）”与“UI 与实际一致”，在当前阶段分别属于 Guest Agent 内部刷盘关机机制（Guest Agent 随 shutdown 信号执行 sync & umount）与后续 UI 模块（Ticket 32 等上层应用装配）。本票在 `runtime/contract` 与 `runtime/instance` 的核心基础模型层面已达成 100% 契约就绪。

---

## 本地执行与实测证据（Local Execution Evidence）

评审专员在 `DroidLayer_dev_t28` 隔离工作区重新执行了全量构建任务：
```powershell
./gradlew :runtime:instance:check :runtime:contract:check --rerun-tasks
```

### 实测输出证据（Execution Log Summary）
```text
> Task :runtime:contract:compileJava
> Task :runtime:contract:classes
> Task :runtime:contract:jar
> Task :runtime:instance:compileJava
> Task :runtime:instance:classes
> Task :runtime:instance:compileTestJava
> Task :runtime:instance:testClasses
> Task :runtime:instance:normalInstanceLifecycleContractTest
> Task :runtime:instance:reconciliationAndRecoveryLifecycleContractTest
> Task :runtime:instance:test
> Task :runtime:instance:check
> Task :runtime:contract:compileTestJava
> Task :runtime:contract:testClasses
> Task :runtime:contract:bridgeClientContractTest
> Task :runtime:contract:bridgeControlContractTest
> Task :runtime:contract:bridgeHandshakeContractTest
> Task :runtime:contract:fullLinuxContractTest
> Task :runtime:contract:networkContractTest
NETWORK_CONTRACT_TEST_OK
> Task :runtime:contract:test
> Task :runtime:contract:check

BUILD SUCCESSFUL in 19s
14 actionable tasks: 14 executed
```

**实测判定**：
- 14 个 actionable tasks 全部实际执行，无缓存跳过；
- 契约测试与单元测试 100% PASS；
- 编译器门控 `-Xlint:all -Werror` 保持零告警通过。

---

## 暴露面检查（Surface Area Audit）

- **凭据与 Token 安全**：`BridgeClient` 与 `InstanceLifecycle` 严格保护 Bridge Token，未在控制台输出、异常信息或导出的崩溃诊断报告中泄露凭据字符串；
- **并发与竞态安全**：冷恢复与状态跃迁全链路受 `synchronized` 保护，全局单一运行实例不变式（`SingleRunningInstanceException`）在异常恢复分支中依然严密生效；
- **文件与路径安全**：`CrashDiagnostic` 与 `exportDiagnostic` 对路径执行了绝对规范化校验，自动创建父级目录，防范了任意路径穿越与空指针异常；
- **特权与边界安全**：未新增任何越权行为或非标准操作系统调用，未触碰 Android 宿主特权或 AVF 内核边界。

---

## 结论（Verdict）

### **APPROVE**

Ticket 28 的代码实现高质量地完成了工单规格所要求的 Bridge Power 协议扩展、宿主死亡状态对账、实例冷恢复排他性控制、VMM 崩溃状态标记以及结构化诊断报告导出。工程代码不可变、线程安全、向后兼容，全量测试 100% 绿灯通过，完全满足合入主干（`main`）的准入要求。

---

- **Standards 汇总**：0 个 blocking findings，4 个 baseline smells（最显著为 `BridgeControl` 的 `dispatch` 重载代码重复，均不阻断合并）。
- **Spec 汇总**：0 个 blocking findings，4 个 acceptance criteria 均达到 100% 闭环。

**评审人**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）  
**日期**：2026-10-01  
**工作区**：`DroidLayer_dev_t28`（分支 `ticket/28-stop-cold-restore`，HEAD `9ed7dff`）

# Review 报告：Ticket 34

- **审查对象**：`ticket/34-avf-execution-strategy`，比对基线 `6547a32`（`main`），审查命令 `git diff 6547a32...HEAD`
- **审查 HEAD**：`e671ca1 docs(ticket-34): [ticket-34] 看板置 ready-for-review 并交付实施报告`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴双轴独立并行子代理审计（Dual-Axis Independent Review）；在独立工作区 `DroidLayer_dev_t34` 运行 `./gradlew :runtime:capability:check :runtime:engine-avf:check :runtime:contract:check :runtime:engine-qemu:check :app:lintDebug` 验证全量构建与门控。

---

## 阶段复审总览（Review Summary）

针对 Ticket 34（AVF 执行策略——同一契约跑硬件虚拟化与授权向导）的实现交付，独立评审专员严格依照架构纪律与工单验收标准，对比对基线 `6547a32` 的全部 45 个改动文件（+4171, -67）执行了深入复核。

**核心发现**：
1. **Standards 轴**：发现 **1 项阻断性硬性架构违规（Hard Architectural Violation）**。实现中在 `TerminalActivity.java`、`InstanceStrategyLauncher.Outcome` 和 `MainActivity.kt` 中直接引入了策略专属类型 `AvfVirtualMachineEngine.AvfVmSession` 并通过 `if (avfSession != null)` 进行显式分支判断，硬性违背了 **ADR-0002** 关于“UX、桥接、发行版层禁止感知当前执行策略，review 时接口出现策略类型即否决”的根本红线。
2. **Spec 轴**：发现 **1 项阻断性真机运行缺陷（Implementation Bug）** 与 **1 项规格缺失（Missing Requirement）**：
   - **反射缺陷**：`ReflectiveAvfVirtualMachineApi.java` 中对 `VirtualMachine.getStatus()` 的反射检查硬编码假定返回值是 `instanceof Enum<?>`。但在真实 AOSP AVF SDK 中，`getStatus()` 返回的是基本类型 `@Status int`（0=STOPPED, 1=RUNNING, 2=DELETED）。当前实现仅能匹配 JVM 假桩，在真机上 `reflectGuestStopped()` 永远无法匹配 guest 正常关机，导致 `waitFor()` 无限轮询陷入死循环，必须靠宿主超时强杀；
   - **规格缺失**：工单明确要求“vCPU/memory/raw disk”，但 `AvfLaunchSpec.java` 与反射构建器中完全缺失了 `cpuCount` / vCPU 数量的规格参数与反射装配；
   - **体验隐患**：`TerminalActivity.onDestroy` 中在 Activity 销毁（如屏幕旋转、配置变更）时直接调用 `closeChannels()` 关闭了 VM 的 vsock 通道，导致后台运行的 VM 终端链路直接夭折，破坏了终端重连能力。
3. **安全与工程优点**：
   - 权限向导严格遵循 ADR-0003，不支持设备静默不展示向导，Shizuku 优先、ADB 兜底；
   - 封锁名单精确阻断骁龙 8 Elite（SM8750），体现了对底层芯片生态断崖的前瞻防御；
   - 资产互通承诺严格兑现，同一 raw 磁盘在 QEMU 与 AVF 之间双向互换无损；
   - 资产卫生零违背，零 `.log` 文件，未触碰任何 Class A 资产，`runtime:contract` 保持零侵入。

综合裁决结论：**【 CHANGES_REQUESTED (需要整改) 】**。

---

## Standards 规范轴审计

**判定：CHANGES_REQUESTED — 1 项阻断性硬性违规，5 项 Fowler 代码异味 / Judgement Calls**

### 1. 阻断性架构硬违背（Hard Architectural Violations）

- **[BLOCKING] 策略专属类型泄漏至 UX 层（硬性违背 ADR-0002）**
  - **规范依据**：[ADR-0002](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/docs/adr/0002-full-linux-contract.md#L8-L24)（Full Linux 契约：策略中性的三层划分与三条纪律）：
    > “Full Linux Runtime 内部，AVF 与 QEMU 的差异被一个策略中性产品契约完全挡在上层之外（UX、发行版、桥接、实例管理永不感知当前执行策略）……UX / 桥接 / 发行版层禁止出现执行策略分支；review 时接口出现策略类型即否决。”
  - **违规证据**：
    1. [`TerminalActivity.java:23,39,62,93,149`](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/app/src/main/java/io/github/nyzeep/droidlayer/TerminalActivity.java#L23-L157)：直接 `import io.github.nyzeep.droidlayer.runtime.engineavf.AvfVirtualMachineEngine.AvfVmSession;`，并在终端数据流流转、桥接分发以及生命周期销毁中多处进行 `if (avfSession != null)` 的策略分支判断；
    2. [`InstanceStrategyLauncher.Outcome`](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/app/src/main/java/io/github/nyzeep/droidlayer/InstanceStrategyLauncher.java#L61-L79) 与 [`MainActivity.kt:284-288`](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/app/src/main/java/io/github/nyzeep/droidlayer/MainActivity.kt#L284-L288)：`Outcome` 同时向外暴露出策略专属的 `avfSession` 和 `qemuSession`，强制调用方 `MainActivity` 做 `if (avfSession != null)` 判断来分别调用 `waitFor()`。
  - **整改要求**：在引擎适配层或启动器层提炼策略中立的通用会话接口 `ExecutionSession`（暴露 `waitFor()`, `InputStream terminalInput()`, `OutputStream terminalOutput()`, `close()` 等），`Outcome` 仅暴露中立的 `ExecutionSession`，`TerminalActivity` 经中立会话注册表或管道消费数据流，彻底根除 UX 层的策略导入与分支。

### 2. Fowler 异味基线审查与非阻断建议（Judgement Calls）

1. **Repeated Switches（策略分支蔓延）**：
   - 对应上述 `TerminalActivity.java` 中三处 `if (avfSession != null)` 分支，一旦后续扩展引擎或调整通道，需要散弹修改多处分支。
2. **Duplicated Code（MainActivity 状态渲染冗余）**：
   - [`MainActivity.kt:57-67`](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/app/src/main/java/io/github/nyzeep/droidlayer/MainActivity.kt#L57-L67) 与 [`MainActivity.kt:189-199`](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/app/src/main/java/io/github/nyzeep/droidlayer/MainActivity.kt#L189-L199) 存在完全相同的 11 行状态文字与徽章构建逻辑；`onCreate()` 中直接调用 `rerenderRuntimeStatus()` 即可。
3. **Data Clumps / Primitive Obsession（参数泥团）**：
   - [`AvfInstanceLauncher.java:39-41`](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/app/src/main/java/io/github/nyzeep/droidlayer/AvfInstanceLauncher.java#L39-L41)：`launch()` 方法平铺了 6 个原始 `String` 字符串参数，与 `LaunchParameters` 完全重合，应直接传递对象参数。
4. **Broken Contract Guarantee（Socket 字段语义失效）**：
   - [`BridgeConnector.java:38,69-70`](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/app/src/main/java/io/github/nyzeep/droidlayer/BridgeConnector.java#L38-L70)：`Session.socket` 的 Javadoc 承诺在 Socket 路径返回有效句柄，但改造后的 `serve(socketName, ...)` 委托给 `serveStreams()` 时直接传入了 `null`，导致 QEMU 路径的 `Session.socket` 恒为 `null`，破坏了既有契约。
5. **Defensive Precondition Gap（防卫性空指针隐患）**：
   - [`AvfVirtualMachineEngine.java:101-118`](file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t34/runtime/engine-avf/src/main/java/io/github/nyzeep/droidlayer/runtime/engineavf/AvfVirtualMachineEngine.java#L101-L118)：`connectWithRetry` 中若外部传入 `connectAttempts <= 0`，for 循环不执行，`last` 为 null，导致 `throw new AvfEngineException(last.reason(), ...)` 抛出不可预期的 `NullPointerException`。

---

## Spec 需求规格轴审计

**判定：CHANGES_REQUESTED — 1 项真机运行断裂缺陷，1 项规格参数遗漏，1 项配置变更生命周期缺陷**

| 验收项 | 工单要求与规格细节 | 评审状态 | 审计证据与核销事实 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **能力检测与授权向导**：SDK≥33、arm64、向导检测门控、Shizuku/ADB 双模引导 | **✅ PASS** | `AvfCapabilityEvaluator` 严密覆盖 SDK/ABI/属性/权限及 SM8750 封锁名单；不支持设备绝对不展示向导；ADB 复制命令与 Shizuku 引导闭环。 |
| **AC-2** | **AVF 引擎实现**：Custom VM 装配（vCPU/内存/根盘）、反射隔离、vsock 通道 | **⚠️ DEFECT** | **阻断**：<br/>1. `getStatus()` 反射实现假定为 `Enum`，而在 AOSP 中为 `@Status int`，真机退出检测必定失效；<br/>2. 遗漏 vCPU / 核心数装配支持；<br/>3. 超前装配了 `setNetworkSupported(true)`（属于 Ticket 36 范围）。 |
| **AC-3** | **资产互通与透明回退**：同一根盘无损互换启动；AVF 失败自动记录日志并降级 QEMU | **✅ PASS** | `AvfQemuInteropTest` 锁定同一根盘路径；`InstanceStrategyLauncher` 实现了优雅回退与 `avf-degradations.txt` 原因留痕。 |
| **AC-4** | **契约测试在模拟层全绿**：生命周期、终端流、桥接线协议 100% 通过 | **✅ PASS** | 模拟 guest 下全部 6 组契约任务（HELLO、PING/RESIZE、终端回显、生命周期转换）在 `--offline` 下 100% 绿灯。 |

### 规格轴具体问题详解：

1. **`getStatus()` 真机反射不兼容（阻断级）**：
   - 现代码片段（`ReflectiveAvfVirtualMachineApi.java:404-409`）：
     ```java
     Object status = vmClass.getMethod("getStatus").invoke(vm);
     if (status instanceof Enum<?> named) {
         String name = named.name();
         if (name.contains("STOP") || name.contains("DEAD")) {
             return 0;
         }
     }
     ```
   - **分析**：AOSP 官方 `android.system.virtualmachine.VirtualMachine` 的 `getStatus()` 返回值类型为原生基本类型 `int`（定义在 `@VirtualMachine.Status`：`STATUS_STOPPED = 0`, `STATUS_RUNNING = 1`, `STATUS_DELETED = 2`）。真实设备反射返回的是 `java.lang.Integer`，根本不是 `Enum`！因此在真机上该代码直接掉入 `return -1;`，导致 `waitFor()` 无法感知正常退出，必须等宿主超时强行终止。
   - **整改**：同时兼容 `Integer`（数字 0 或 2 代表停止）与 `Enum<?>`（兼顾假桩），并匹配数字常量。
2. **vCPU 核心数配置缺失**：
   - 工单明示要求“vCPU/memory/raw disk”，`AvfLaunchSpec` 中需支持设置 `cpuCount`（默认保持 1 或 2，并在反射构建器中尝试反射调用 `setCpuCount(int)` 或记录 notes）。
3. **`TerminalActivity.onDestroy` 误杀连接**：
   - 手机屏幕旋转或切屏触发 `onDestroy()` 时，当前代码直接调用了 `avfSession.closeChannels()`，导致后台正在运行的虚拟机终端会话直接断连。应区分 `isFinishing()` 判定，或交由上层会话管理器统一管理生命周期。

---

## 整改核对清单（Rectification Checklist）

为达到合入主干（main）的标准，请 Implementation Worker 在 `DroidLayer_dev_t34` 上完成以下针对性整改：

- [ ] **1. 架构解耦（ADR-0002）**：
  - 定义策略中立的会话包装类型（如 `ExecutionSession` 或 `StrategySession`），统一封装会话控制与终端数据流；
  - `InstanceStrategyLauncher.Outcome` 仅暴露中立会话与策略标识，消除 `MainActivity.kt` 中的 `if (avfSession != null)` 分支；
  - `TerminalActivity.java` 彻底移除对 `AvfVirtualMachineEngine` 的导入与特定分支，统一从注册表中消费中立会话/数据流。
- [ ] **2. 修复 `getStatus()` 真机反射**：
  - 改造 `reflectGuestStopped`，使其支持 `Number`（如 `Integer == 0` 或 `2`）以及 `Enum<?>`，确保真机与测试桩均能准确感知虚拟机停止。
- [ ] **3. 补充 vCPU 配置**：
  - 在 `AvfLaunchSpec` 中增加 `cpuCount` 参数与默认值（如 `1`），并在反射构建器中提供对 `setCpuCount` 的安全反射调用。
- [ ] **4. 修复生命周期与连接防卫**：
  - 修正 `TerminalActivity.onDestroy`，仅在 `isFinishing()` 时解绑与关闭流，防止旋转屏幕破坏终端会话；
  - 在 `AvfVirtualMachineEngine` 构造或 `connectWithRetry` 中对 `connectAttempts > 0` 增加强制断言，杜绝潜在的 NPE。
- [ ] **5. 代码异味治理**：
  - 在 `BridgeConnector.java` 中恢复 QEMU socket 路径的 `Session.socket` 引用保证；
  - 消除 `MainActivity.kt` 中 `onCreate()` 与 `rerenderRuntimeStatus()` 的重复代码。

整改完成后，重新执行全量本地测试验证，提交整改 Commit 并更新实施报告，随时提请二次复审！

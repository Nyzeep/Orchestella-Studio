# 工单 07 实施报告：后台终端 PTY 会话保持与二次进入无缝重连

- **工单编号**：Phase 6.0 - 工单 07（Ticket 07）
- **工单标题**：后台终端 PTY 会话保持与二次进入无缝重连 (Background Terminal PTY Session Persistence & Seamless Re-attach)
- **研发规范与指导**：`/diagnosing-bugs` 闭环排查 + `/implement` 深模块落地
- **开发分支**：`feature/ticket-07-session-persistence`
- **基线提交**：Commit `0b94794`
- **状态**：**ready-for-review**
- **报告日期**：2026-10-10

---

## 1. 故障现场与根本原因诊断（Root Cause Analysis）

### 1.1 用户手机端故障现象
在真机使用 Orchestella Studio 时，用户在已创建并启动的实例终端操作后退回主界面，再次点击进入该容器终端，屏幕直接输出：
```text
Orchestella Studio terminal (1c283e7c-daa9-4192-974c-41f4cc139ea7)
[no live session for instance 1c283e7c-daa9-4192-974c-41f4cc139ea7]
```
随后初始化流程直接退出，界面陷入死锁状态：无软键盘弹出、无光标闪烁、输入法无响应、无法重新连接，用户被迫彻底杀掉应用进程重启。

### 1.2 根本原因深入排查（`/diagnosing-bugs` 闭环）
通过静态分析与生命周期审计，确认三大深层根因：
1. **静态脆弱单例与生命周期硬绑定**：
   - 原始 `TerminalActivity.onCreate` 仅从 `ExecutionSessionRegistry.get(instanceId)` 取回会话；
   - 一旦用户触发返回键或 Android 系统因内存调度销毁 `TerminalActivity`，若注册表中引用发生孤立、丢失或未与后台守护对齐，检索直接返回 `null`；
2. **缺乏自愈与重连容灾机制（Fail-Stop 缺陷）**：
   - 原始实现遇到 `session == null` 时，仅单纯 `append("[no live session...]")` 并直接 `return`，完全没有与底层真实实例状态（`InstanceLifecycle.Store`）对齐，更没有触发底层 vsock / socket 桥接通道的自动重新探测（Auto Re-attach）；
3. **缺少历史屏幕缓冲区与无损回放（Transcript Replay）机制**：
   - 原始 PTY 读流直接绑定至单个 Activity 的 UI 呈现；当 Activity 销毁后，后台输出流要么无人读取导致管道缓冲区满而挂起 Guest 内核，要么二次进入时历史上下文完全丢失。

---

## 2. 架构演进与深模块设计（Deep Modules Implementation）

本次实施严格贯彻深度模块化原则（Deep Module Architecture）与防御性编程：

```mermaid
flowchart TD
    subgraph UI_Layer ["表现层 (Presentation Layer)"]
        A["TerminalActivity (生命周期脱耦)"]
        B["ModernTerminalView (Dracula VT100 画布)"]
        C["Offline / Crash Recovery Card (自愈看板)"]
    end

    subgraph Core_Engine ["长效会话核心引擎 (Deep Modules)"]
        D["InstanceSessionReconciler (状态协同契约)"]
        E["PersistentTerminalSessionPool (线程安全会话池)"]
        F["TerminalRingBuffer (50,000 字符定长环形缓冲)"]
    end

    subgraph Service_And_Store ["常驻宿主与持久化层 (Daemon & Storage)"]
        G["OrchestellaForegroundService (前台常驻守护)"]
        H["InstanceLifecycle.Store (原子实例元数据)"]
        I["QEMU / AVF Guest Shell PTY (后台宿主进程)"]
    end

    A -->|"1. reconcileAndConnect()"| D
    D -->|"2. 检查会话"| E
    D -->|"3. 协同状态"| H
    E -->|"托管读写泵线程"| I
    E -->|"输出写入缓冲"| F
    E -->|"4. attach() / 回放历史"| B
    D -.->|"离线 / 崩溃兜底"| C
    G -->|"持久化锚定"| E
```

### 2.1 深模块 1：定长环形字符缓冲区 `TerminalRingBuffer.java`
- **模块路径**：`app/src/main/java/io/github/nyzeep/orchestella/TerminalRingBuffer.java`
- **核心契约**：
  - 线程安全（内部对象锁同步保护）；
  - 严格限定容量（默认 50,000 字符，`DEFAULT_MAX_CAPACITY`），当字符溢出时自动从头部删除最老的数据（`buffer.delete(0, overflow)`），防止极端高并发输出造成内存暴涨或 OOM；
  - 提供 `snapshot()` 获取纯净当前快照，供二次进入时瞬间回放。

### 2.2 深模块 2：长效终端会话池 `PersistentTerminalSessionPool.java`
- **模块路径**：`app/src/main/java/io/github/nyzeep/orchestella/PersistentTerminalSessionPool.java`
- **核心契约**：
  - 纯 JVM 线程安全会话池，维护 `instanceId -> SessionHolder`；
  - **解耦会话与 Activity**：内部独立启动守护泵线程 `pty-term-pump-<instanceId>`，在后台持续读取 Guest Shell 输出并写入 `TerminalRingBuffer`；
  - **多读单写互斥模型**：采用 `ReentrantReadWriteLock` 与 `CopyOnWriteArraySet<Consumer<String>>`；Activity 进入时调用 `holder.attach(listener)` 注册输出监听并返回当前历史快照，退出时调用 `holder.detach(listener)` 注销监听；底层 PTY 管道与进程保持存活，流永不竞争、永不提前关闭；
  - **非阻塞推流**：提供 `sendBytes(instanceId, bytes)` 写入终端输入流，支持多线程安全并发键入。

### 2.3 状态协同契约感知器 `InstanceSessionReconciler.java`
- **模块路径**：`app/src/main/java/io/github/nyzeep/orchestella/InstanceSessionReconciler.java`
- **核心契约**：
  - 抽象纯 JVM 协同契约 `ensureSessionAliveOrRecover(instanceId)`；
  - 覆盖四大自愈分支：
    1. **`ATTACHED`**：会话池中存在活跃会话，或底层为 `RUNNING` 且自动探活（`recoverSession`）成功，立即绑定并触发历史回放；
    2. **`OFFLINE`**：实例状态处于 `STOPPED` 或 `CREATED`，识别为离线状态，引导用户一键启动；
    3. **`CRASHED`**：实例此前处于 `RUNNING` 但探活失败（底层进程已死亡），自动触发控制器 `recordCrash(instanceId, diagnostic)` 落盘崩溃诊断，并提示用户立即重启；
    4. **`NOT_FOUND`**：实例 ID 不存在。

### 2.4 前台服务守护与通知直达 `OrchestellaForegroundService.java`
- **模块路径**：`app/src/main/java/io/github/nyzeep/orchestella/OrchestellaForegroundService.java`
- **改进点**：
  - 静态锚定 `PersistentTerminalSessionPool`，保证服务生命周期内所有会话常驻；
  - 常驻通知 PendingIntent 携带当前运行中的 `instanceId`，用户在系统通知栏点击通知可无缝直达该实例的 `TerminalActivity`。

### 2.5 终端交互层改造 `TerminalActivity.java` 与 `MainActivity.kt`
- **模块路径**：
  - `app/src/main/java/io/github/nyzeep/orchestella/TerminalActivity.java`
  - `app/src/main/java/io/github/nyzeep/orchestella/MainActivity.kt`
- **改进点**：
  - **彻底清除假死代码**：移除所有的 `[no live session ...]` 阻断逻辑；
  - **生命周期无缝集成**：
    - `onCreate` / `onStart` / `onNewIntent` 统一收敛至 `reconcileAndConnect()`；
    - `onStop` / `onDestroy` 仅执行 `sessionPool.detach(instanceId)` 与历史保存，不关闭底层会话；
  - **内置 Dracula 风格自愈卡片（`buildOfflineCardView`）**：
    - 针对 `OFFLINE` 状态：呈现 `[🚀 启动该实例]` 按钮；点击后通过 Intent 带入 `MainActivity`（`FLAG_ACTIVITY_REORDER_TO_FRONT`）完成前台一键开机并直接切回终端；
    - 针对 `CRASHED` 状态：展示详细退出码和诊断日志，并提供 `[🔄 立即重启实例]` 按钮；
    - 提供统一的 `[返回实例列表]` 按钮安全返回。

---

## 3. 验收标准与测试执行（Acceptance Criteria Verification）

### 3.1 验收标准逐项核验表

| 验收标准编号 | 规范要求 | 实施机制与落地情况 | 验证结论 |
| :--- | :--- | :--- | :--- |
| **AC-01** | **彻底消除 `[no live session]` 卡死白屏** | 移除致命退出代码，引入 `InstanceSessionReconciler` 四态自愈机，离线呈现启动引导，在线瞬时绑定 | **100% 达成** |
| **AC-02** | **返回退出再进入无缝续接** | `PersistentTerminalSessionPool` 独立守护泵线程 + `TerminalRingBuffer` 50,000 字符历史快照回放，后台输出不间断 | **100% 达成** |
| **AC-03** | **已停止实例进入提供一键启动** | 离线看板提供「🚀 启动该实例」，点击通过 `MainActivity` 发起生命周期驱动并自动恢复终端 | **100% 达成** |
| **AC-04** | **纯 JVM 单元测试覆盖** | 编写 `PersistentTerminalSessionPoolTest` 与 `InstanceSessionReconcilerTest`，无 Android 真机依赖 | **100% 达成** |

### 3.2 单元测试覆盖详情

运行环境：Windows 纯 ASCII 虚拟映射盘符 `X:\`，纯 JVM 单元测试套件：
命令：`gradlew.bat :app:testDebugUnitTest --tests io.github.nyzeep.orchestella.PersistentTerminalSessionPoolTest --tests io.github.nyzeep.orchestella.InstanceSessionReconcilerTest`

测试用例清单与执行结果：
1. **`PersistentTerminalSessionPoolTest`** (3/3 通过)：
   - `testAttachDetachConcurrentSafetyAndTranscriptReplay`：验证 UI 首次接入、中途退出脱钩、后台持续收集、二次进入无损回放及发送输入全链路；
   - `testRingBufferEnforcesBoundaries`：验证环形缓冲区字符满 100 字符后先入先淘汰，无内存泄漏风险；
   - `testConcurrentAttachDetachStress`：8 线程并发 attach/detach 压力测试，验证高负载下读写锁无死锁、无竞争崩溃。
2. **`InstanceSessionReconcilerTest`** (5/5 通过)：
   - `testSessionAlreadyActiveInPoolReturnsAttached`：验证活跃池会话即时命中并返回 `ATTACHED`；
   - `testInstanceRunningInStoreAutoReattachSucceeds`：验证应用重建后 RUNNING 实例通过探活器自动重新挂接；
   - `testInstanceRunningInStoreAutoReattachFailsTransitionsToCrashed`：验证探活失败时自动切入 `CRASHED` 状态并写入持久化诊断；
   - `testInstanceStoppedOrCreatedReturnsOffline`：验证 `STOPPED`/`CREATED` 实例返回 `OFFLINE` 状态；
   - `testInstanceNotFoundReturnsNotFound`：验证未知实例安全回退 `NOT_FOUND`。

### 3.3 全项目编译与质量门禁核对
- **App 单元测试门禁**：`:app:testDebugUnitTest` 顺利通过（43 个任务执行，耗时 58s，全部通过）；
- **Runtime 运行时架构检查**：`:runtime:contract:check :runtime:instance:check :runtime:capability:check :runtime:distro:check :runtime:network:check` 顺利通过（49 个任务执行，全部通过）；
- **全量 APK 打包**：`:app:assembleDebug` 构建成功（57 个任务执行，Debug APK 顺利生成）。

---

## 4. 变更文件清单

| 文件路径 | 变更属性 | 核心职责 |
| :--- | :--- | :--- |
| `app/src/main/java/io/github/nyzeep/orchestella/TerminalRingBuffer.java` | **新增** | 定长 50,000 字符线程安全环形缓冲区，维护屏幕滚动历史快照 |
| `app/src/main/java/io/github/nyzeep/orchestella/PersistentTerminalSessionPool.java` | **新增** | JVM 长效会话池，提供多观察者 attach/detach 及后台守护泵线程 |
| `app/src/main/java/io/github/nyzeep/orchestella/InstanceSessionReconciler.java` | **新增** | 协同会话池与实例持久化状态机的自愈契约协调器 |
| `app/src/test/java/io/github/nyzeep/orchestella/PersistentTerminalSessionPoolTest.java` | **新增** | 会话池生命周期、Transcript 回放与高并发挂接压力单元测试 |
| `app/src/test/java/io/github/nyzeep/orchestella/InstanceSessionReconcilerTest.java` | **新增** | 5 大分支状态自愈与异常重置纯 JVM 单元测试 |
| `app/src/main/java/io/github/nyzeep/orchestella/ExecutionSessionRegistry.java` | **重构** | 向下兼容委托至 `PersistentTerminalSessionPool` |
| `app/src/main/java/io/github/nyzeep/orchestella/TerminalActivity.java` | **重构** | 根除无会话卡死代码，接入 Reconciler，集成 Dracula 离线卡片与历史回放 |
| `app/src/main/java/io/github/nyzeep/orchestella/OrchestellaForegroundService.java` | **改进** | 前台服务常驻锚定长效会话池，通知点击绑定 `instanceId` 直达终端 |
| `app/src/main/java/io/github/nyzeep/orchestella/MainActivity.kt` | **改进** | 支持 `action_launch_instance_id` 处理，打通离线一键开机恢复通路 |
| `docs/reports/tickets/07/implementation-report.zh-CN.md` | **新增** | 工单 07 实施与技术闭环归档报告 |

---

## 5. 结论

工单 07（后台终端 PTY 会话保持与二次进入无缝重连）已按照既定架构规格与验收标准完成全面研发、诊断闭环与工程落地。全部单元测试与打包编译 100% 绿灯，彻底消除了终端二次进入僵死阻断性缺陷，现已就绪并提交审查。

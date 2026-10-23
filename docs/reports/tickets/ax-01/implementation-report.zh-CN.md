# AX-01 双车道并发与资源仲裁 实施报告

- 日期：2026-10-23
- Base：`feat/accelerator-batch1 @ 5fa22c8`
- Branch：`feat/accelerator-batch2`
- 工单：`.scratch/orchestella-accelerator/issues/AX-01-双车道并发与资源仲裁.md`
- 零设备：本会话没有运行 adb、没有安装 APK、没有启动真实 VM。全部验证在 JVM、fake LaneRuntime 与 fake clock 上完成。
- CI：**未验证**。本批只做本地验证；PR/CI 尚未运行。

## 1. 结论

零设备范围内**完成**。

- 新增 `DualLaneCoordinator`，是 QEMU 与 ACCEL 的唯一准入与停止 owner。
- 新增 `LaneRuntime` / `LaneStarter` / `LaneBudgetLedger` / `LaneEvent` 等 runtime 层接口；`QemuLaneRuntime` 与 `ProtectedLaneRuntime` 只做适配，不新增 stop 实现。
- 预算：CPU 核数、RAM MiB、磁盘 GiB、加速器可用性；拒绝返回固定 `LaneRejectCode`。
- 启停：两个 start 请求在等待任何 readiness 之前发出；停止固定 QEMU 先、ACCEL 后；QEMU stop 失败仍尝试 ACCEL；ACCEL 崩溃只标 `LOST`，不拖垮 QEMU；`UNCONFIRMED` 保留 lease 与预算。
- 测试：`DualLaneCoordinatorTest` 25 例、`LaneBudgetLedgerTest` 10 例，共 35 例；覆盖双启、双停、并发 stop、启动取消、预算拒绝、ACCEL 崩溃、QEMU 崩溃、幂等、事件面。

## 2. 现状与职责边界

| 组件 | 现有职责 | 本票处理 |
| --- | --- | --- |
| `RuntimeProcessSupervisor` | QEMU 进程 + gvproxy + 宿主通道的唯一 stop owner（`RuntimeProcessSupervisor.java:26,97`） | 不修改；通过 `RuntimeOwnedSession.stopRuntime` 调用 |
| `RuntimeOwnedSession` | QEMU 会话的 stop/liveness seam（`RuntimeOwnedSession.java:4-9`） | 由 `QemuLaneRuntime` 适配 |
| `FastLaneSession.stopRuntime` | protected/Fast Lane 的唯一 owner stop（`FastLaneSession.java:181-197`） | 由 `ProtectedLaneRuntime` 适配；不塞进 supervisor |
| `ProtectedWarmPool` | 有界预热池；默认容量 0；`Admission` 布尔 seam（`ProtectedWarmPool.java:147-153`） | 协调器提供 `admission()`，共享同一 `LaneBudgetLedger`；池默认 0 不变 |
| `DualLaneCoordinator` | 新增 | 只做准入、启动编排、停止顺序、lease 与事件；不直接杀进程 |

## 3. 状态机

| 当前 | 事件 | 下一状态 |
| --- | --- | --- |
| `STOPPED` | start + 预算通过 | `STARTING` |
| `STOPPED` | 预算拒绝 | `STOPPED`（QEMU）/ `DISABLED`（ACCEL） |
| `STARTING` | QEMU `isReady` | `READY` + `lane_ready` |
| `STARTING` | ACCEL `isReady` | `READY` + `lane_ready` |
| `STARTING` | start 抛错 / 进程死亡 | `FAILED`（QEMU start 失败）/ `LOST`（运行中死亡） |
| `READY` | ACCEL/ QEMU 进程死亡 | `LOST` + `lane_lost` |
| `READY`/`STARTING` | stop | `STOPPING` |
| `STOPPING` | stop CONFIRMED/ALREADY_STOPPED | `STOPPED`，释放 lease 与预算 |
| `STOPPING` | stop UNCONFIRMED/异常 | `UNCONFIRMED`，保留 lease 与预算 |
| `UNCONFIRMED` | 后续 stop CONFIRMED | `STOPPED`，释放 |

启动顺序：先 reserve QEMU + ACCEL，再调用 `qemuStarter.start()`，紧接着 `accelStarter.start()`；两次 start 都返回后才进入 QEMU readiness 轮询。启动器必须是“发起后立即返回”的异步 launch，这是“并行启动”的实现口径；本票不把并行启动写成并行可用。

停止顺序：`stopAll` 固定 `stopLane(QEMU)` → `stopLane(ACCEL)`。QEMU 返回 UNCONFIRMED 或抛异常都不会短路 ACCEL 停止。

## 4. 预算换算

| 维度 | 输入 | 账本字段 | 拒绝 code |
| --- | --- | --- | --- |
| CPU | `LaneRequest.cpuCores` | `usedCpu` | `CPU_EXCEEDED` |
| RAM | `LaneRequest.ramMiB` | `usedRam` | `RAM_EXCEEDED` |
| 磁盘 | `LaneRequest.diskGiB` | `usedDisk` | `DISK_EXCEEDED` |
| 加速器 | `LaneBudget.acceleratorAvailable` | 仅 ACCEL 检查 | `ACCELERATOR_UNAVAILABLE` |
| 非法请求 | 非正数 | 不修改账本 | `INVALID_REQUEST` |

- QEMU 的 `ramMegabytes` 与 protected pool 的 `memoryMb` 都直接按 MiB 记账；磁盘按 GiB 记账，不做隐式单位换算。
- `canAdmitPool(memoryMb)` 是只读视图：要求加速器可用，且 `usedRam + memoryMb <= budget.ramMiB`。池仍是默认容量 0；启用时池 VM 视为 ACCEL 车道预算的一部分。
- ACCEL 被预算拒绝时 QEMU 继续启动，`StartReport.rejectCode` 返回拒绝 code；编译任务走本地编译。
- `UNCONFIRMED` 不释放 reservation；后续确认停止才释放。

## 5. 事件

固定事件：`lane_started`、`lane_ready`、`lane_stopped`、`lane_lost`、`budget_rejected`。

`LaneEvent(name, lane, code, durationMs)` 只有这四个字段；code 是固定枚举名/固定字符串。没有路径、token、argv、VM id 或原始异常文本。测试断言 name 匹配 `[a-z_]+`、code 匹配 `[A-Z_]+`、duration 非负、`toString()` 不含 `/` 或 `token`。

## 6. 改动清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `runtime/instance/.../duallane/LaneId.java` | 新增 | QEMU / ACCEL |
| `runtime/instance/.../duallane/LaneState.java` | 新增 | 8 个状态 |
| `runtime/instance/.../duallane/LaneStopOutcome.java` | 新增 | 与两种 native stop result 对齐 |
| `runtime/instance/.../duallane/LaneRuntime.java` | 新增 | laneId/isAlive/isReady/stop |
| `runtime/instance/.../duallane/LaneStarter.java` | 新增 | 发起启动，立即返回 |
| `runtime/instance/.../duallane/LaneStartException.java` | 新增 | 固定 code |
| `runtime/instance/.../duallane/LaneRejectCode.java` | 新增 | 5 个拒绝 code |
| `runtime/instance/.../duallane/LaneRequest.java`、`LaneBudget.java` | 新增 | 请求/预算 |
| `runtime/instance/.../duallane/LaneRejectedException.java` | 新增 | typed rejection |
| `runtime/instance/.../duallane/LaneClock.java` | 新增 | 可注入单调时钟 + sleep |
| `runtime/instance/.../duallane/LaneEvent.java`、`LaneEventSink.java` | 新增 | 计数/耗时/固定 code |
| `runtime/instance/.../duallane/LaneBudgetLedger.java` | 新增 | 共享预算账本 |
| `runtime/instance/src/test/.../LaneBudgetLedgerTest.java` | 新增 | 10 例 |
| `app/.../DualLaneCoordinator.java` | 新增 | 唯一准入/停止 owner |
| `app/.../QemuLaneRuntime.java` | 新增 | QEMU 适配 |
| `app/.../ProtectedLaneRuntime.java` | 新增 | FastLane 适配 |
| `app/src/test/.../DualLaneCoordinatorTest.java` | 新增 | 25 例 |

没有修改 `RuntimeProcessSupervisor`、`RuntimeOwnedSession`、`FastLaneSession`、`ProtectedWarmPool`、FLN1 wire、ADR、版本号或 applicationId。

## 7. 测试矩阵

| # | 测试 | 覆盖 |
| --- | --- | --- |
| 1 | `startIssuesBothLaneStartsBeforeAnyReadinessWait` | 双启、启动顺序 |
| 2 | `qemuReadyEmitsLaneReady` | QEMU READY 事件 |
| 3 | `acceleratorReadyIsObservedWithDeadline` | ACCEL READY |
| 4 | `acceleratorTimeoutFallsBackWithoutTouchingQemu` | 编译回退、不阻塞桌面 |
| 5 | `stopIsQemuFirstThenAccel` | 停止顺序 |
| 6 | `qemuUnconfirmedStillStopsAccelAndKeepsItsLease` | QEMU UNCONFIRMED + 仍停 ACCEL |
| 7 | `accelCrashMarksLostAndLeavesQemuReady` | ACCEL 崩溃 |
| 8 | `qemuCrashDuringStartStopsAccel` | QEMU 崩溃 |
| 9 | `repeatedStartIsIdempotent` | 双 start |
| 10 | `repeatedStopIsIdempotent` | 双 stop |
| 11 | `concurrentStopStopsEachLaneOnce` | 并发 stop |
| 12 | `cpuBudgetRejectionStartsNothing` | CPU 拒绝 |
| 13 | `ramBudgetRejectionStartsNothing` | RAM 拒绝 |
| 14 | `diskBudgetRejectionStartsNothing` | 磁盘拒绝 |
| 15 | `acceleratorUnavailableStillStartsWithQemu` | 加速器不可用降级 |
| 16 | `cancelDuringStartStopsQemuAndSkipsAccel` | 启动中途取消 |
| 17 | `qemuStartFailureReleasesBudgetAndSkipsAccel` | QEMU start 失败 |
| 18 | `accelStartFailureKeepsQemuReady` | ACCEL start 失败 |
| 19 | `eventsContainOnlyFixedNamesCodesAndDurations` | 事件面 |
| 20 | `poolAdmissionSharesTheSameLedger` | PL-01 Admission |
| 21 | `unconfirmedStopCanBeConfirmedOnALaterAttempt` | lease 保留/释放 |
| 22 | `invalidRequestIsRejectedAsInvalid` | 非法请求 |
| 23 | `qemuReadyTimeoutLeavesStartingWithoutBlockingAccel` | QEMU readiness 超时 |
| 24 | `stopStillAttemptsAccelWhenQemuStopThrows` | stop 异常仍停 ACCEL |
| 25 | `awaitAcceleratorReadyIsFalseAfterAConfirmedStop` | 停止后不再误报 READY |
| 26-35 | `LaneBudgetLedgerTest` | 预算/释放/池视图/非法值 |

## 8. 验收

| 命令 | 结果 |
| --- | --- |
| `./gradlew :runtime:instance:test :app:testDebugUnitTest --tests '*DualLane*'` | BUILD SUCCESSFUL；LaneBudgetLedgerTest 10/10，DualLaneCoordinatorTest 25/25 |
| `python tools/ci/check_ci_module_coverage.py` | 58 passed, 0 failed |

Gradle 首次运行遇到 Maven Central 429；使用仓库已有的 `-Porchestella.repositories.useAliyunMirror=true` 显式镜像后通过，并设置 `ANDROID_HOME=<sdk-root>`。没有修改依赖版本。

## 9. 偏差

1. **启动“并行”的口径**：协调器按 QEMU start → ACCEL start 顺序发起，但两者都不等待 readiness；两次 start 都返回后才轮询 QEMU READY。没有用线程池 join，因为真实 launch 本身是异步的；这样避免 fake clock 下的调度不确定性。
2. **ACCEL 预算拒绝不阻塞 QEMU**：ACCEL 被 `ACCELERATOR_UNAVAILABLE`/CPU/RAM/磁盘拒绝时，QEMU 仍启动，`rejectCode` 保留；这是“编译任务回退本地、不阻塞桌面”的要求。
3. **QEMU readiness 超时**：`startAll` 在 `Config.qemuReadyTimeoutMs` 后返回 `STARTING`，不把超时写成失败，也不停止仍存活的 QEMU。
4. **未接生产启动路径**：与 PL-01 相同，本票只定义 seam 和 fake 测试；真实 QEMU/AVF 装配留给设备阶段。
5. **池预算视图**：`admission()` 不 reserve，因为 `ProtectedWarmPool.Admission` 只有布尔返回；池默认 0，启用时按 ACCEL 车道预算的一部分处理，已在文档写明。

## 10. 风险与未验证

- **not-tested**：真实 QEMU + Fast Lane 同时运行、真实进程死亡、真实 stop 超时；全部用 fake runtime/clock。
- **not-tested**：设备端 CPU/RAM/磁盘实际预算与 cgroup/内存压力；本票只验证账本算术。
- **not-tested**：`RuntimeOwnedSession` / `FastLaneSession` 适配器的真实构造；适配器是纯映射，未在设备上装配。
- 协调器在 `awaitQemuReady` / `awaitAcceleratorReady` 期间持有自身 monitor；生产调用方不应在 UI 线程调用长超时版本。
- CI 未运行。

## 11. 待维护者决策

1. 是否接受“start 请求顺序发出、readiness 并行等待”作为并行启动口径。
2. 是否要求 ACCEL 预算拒绝时也发 `lane_lost`（当前只发 `budget_rejected`，状态 `DISABLED`）。
3. 是否把 `LaneBudget` 的默认值（CPU/RAM/磁盘）接到 `HostHardwareProfile`；本票未接。
4. `awaitQemuReady` 的默认超时是否应改为可配置的生产值（当前 Config 由调用方给）。
5. 是否需要在 AX-02 选道前增加“ACCEL 已 DISABLED”对 decision 的显式输入。

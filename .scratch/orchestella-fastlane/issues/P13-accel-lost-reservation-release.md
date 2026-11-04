# P13: 运行期 ACCEL_LOST 不释放 accelReservation（资源账本泄漏）

Type: task（缺陷修复）
Status: implemented（zero-device；commit b07dca0；三条判据全过，无剩余设备门）
Priority: P1（账本正确性；不影响 P0/P1 发布门，但会造成后续 reserve 被错误拒绝）
Owner: （待分配）
Blocked by: None
External gates: 无（纯实现 + 回归测试）
来源：零设备批次 2 工单 04 调研中发现（`docs/research/2026-11-04-qemu-mttcg-scaling.md`），协调会话已源码核实。

## 缺陷描述

`app/src/main/java/io/github/nyzeep/orchestella/DualLaneCoordinator.java`：

- **启动失败路径（:180-188）行为正确**：`catch` 里 `release(accelReservation)` + 置 null + `accelState = LOST` + `LANE_LOST`。
- **运行期死亡路径（:224-229）泄漏**：`awaitAcceleratorReady()` 内 `if (!accelRuntime.isAlive())` 分支只做 `accelState = LOST` + `emit(LANE_LOST, "ACCEL_LOST")` + `return false`，**未** `release(accelReservation)`、未置 null。

后果：ACCEL 运行中死亡后，`LaneBudgetLedger` 仍持有该预留；在 stop 之前的新 reserve 请求可能被该僵尸预留错误拒绝（对照 `release(accelReservation)` 的正常语义，:150-151/:378-379）。:378 的 stop 路径是否兜底取决于调用方是否走到 stop——失败窗口内账本状态是错的。

## 任务

- [x] 在 :224-229 分支补 `release(accelReservation); accelReservation = null;`（与 :180-188 口径一致；如 release 需要在锁外或有幂等要求，按 `LaneBudgetLedger` 的既有约定处理）。（b07dca0：复用既有 `releaseLaneReservation(LaneId.ACCEL)`（内部即 release + 置 null）并加 `setLease(LaneId.ACCEL, false)`，均在既有 coordinator 锁内，与启动失败路径逐行同口径）
- [x] 回归测试：accelRuntime 启动成功后模拟死亡 → 断言 LANE_LOST 事件、`accelReservation == null`、账本可重新 reserve。（`accelCrashReleasesReservationAndLease`：账本快照 6/6144/48 → 4/4096/32 证明 accel 预留已归还池，等价可观察证据；`accelReservation` 为私有字段，以账本快照差值断言）
- [x] 确认 stop 路径（:378）对「已 LOST 但未 release」状态幂等（不应二次 release 抛错）。（同测试内 `stop()` 后 accel=ALREADY_STOPPED、qemu=CONFIRMED、账本归零；`stopLane` 以 `!leased` 短路，崩溃路径清租约后不再触碰账本）

## 判据与纪律

- ACCEL_LOST 后账本立即可复用；正常路径（READY→stop）行为不变。
- 按 `/implement` 流程走 TDD（先红后绿）；不改与本缺陷无关的代码。
- 本票只修这一处泄漏；「跨 Controller 单实例互斥」等 P0 项不在此展开。

## Comments

- 2026-10-31 / CodeBuddy（ask-matt 轮）：立票。发现于 fabric 批次 2 工单 04 的账本衔接调研，协调会话已读源码确认（:224-229 无 release，对照 :180-188）。
- 2026-11-05 / ZCode（票据收尾 lane I）：**闭环核验通过，置终态 `implemented`**。逐条对照验收判据：
  - **判据 1（补 release + 置 null）**：commit `b07dca0`（fix(app): ACCEL_LOST 释放预算预留与租约）在 `awaitAcceleratorReady()` 的 `!accelRuntime.isAlive()` 分支（现 `DualLaneCoordinator.java:230-231`）补 `releaseLaneReservation(LaneId.ACCEL); setLease(LaneId.ACCEL, false);`。`releaseLaneReservation`（:377-385）内部即 `release(accelReservation); accelReservation = null;`，与票面要求的字面写法等价且复用既有 seam；`setLease(false)` 使运行期死亡路径与启动失败路径（:183-185，同样清租约）完全同口径。两处均在既有 coordinator 锁内执行，与 `LaneBudgetLedger` 既有约定一致，未新增字段与锁。
  - **判据 2（回归测试）**：新增 `DualLaneCoordinatorTest.accelCrashReleasesReservationAndLease`——accel 启动 READY 后置 `alive=false` 模拟运行期死亡，断言 LANE_LOST 事件、`state(ACCEL)==LOST`、QEMU 仍 READY、`hasLease(ACCEL)==false`，账本快照由双车道 6 cpu/6144 MiB/48 GiB 回落到 qemu-only 4/4096/32（即 accel 的 2/2048/16 已归还池，等价证明账本可重新 reserve；`accelReservation` 为私有字段，以账本快照差值为可观察证据）。
  - **判据 3（stop 幂等）**：同一测试内随后 `stop()`：accel 返回 `ALREADY_STOPPED`（`stopLane` 以 `!leased` 短路，不再触碰已释放的预留）、QEMU `CONFIRMED`，账本终值归零，无二次 release 异常。语义边界：崩溃清租约后 `stopAll` 对加速器返回 `ALREADY_STOPPED`，与「UNCONFIRMED 保留租约」互补，不改变停止顺序——已在收尾报告风险节显式记录。
  - **红→绿反证**：2026-11-04 零设备收尾轮实跑（`docs/reports/orchestration/zero-device-closeout-20261104.zh-CN.md` §测试与验证结果）：临时移走两行释放代码后 `:app:testDebugUnitTest --tests "*DualLaneCoordinatorTest"` 恰好 `1 failed`（即新用例），恢复后复绿；定向回归 `tests=31 failures=0`；全量基线 `:runtime:contract:test :app:testDebugUnitTest` BUILD SUCCESSFUL，app 1129/0/0/0、contract 137/3 skipped/0。
  - **剩余范围**：无。票面「本票只修这一处泄漏」的单一泄漏面已闭合；「跨 Controller 单实例互斥」等 P0 项按票面边界不在本票，仍在独立工单体系跟踪。本核验为纯文档 lane，未跑 Gradle，以上数据均引自已入库的 2026-11-04 实跑记录。

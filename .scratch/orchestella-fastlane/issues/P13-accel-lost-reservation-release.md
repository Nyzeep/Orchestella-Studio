# P13: 运行期 ACCEL_LOST 不释放 accelReservation（资源账本泄漏）

Type: task（缺陷修复）
Status: ready-for-agent
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

- [ ] 在 :224-229 分支补 `release(accelReservation); accelReservation = null;`（与 :180-188 口径一致；如 release 需要在锁外或有幂等要求，按 `LaneBudgetLedger` 的既有约定处理）。
- [ ] 回归测试：accelRuntime 启动成功后模拟死亡 → 断言 LANE_LOST 事件、`accelReservation == null`、账本可重新 reserve。
- [ ] 确认 stop 路径（:378）对「已 LOST 但未 release」状态幂等（不应二次 release 抛错）。

## 判据与纪律

- ACCEL_LOST 后账本立即可复用；正常路径（READY→stop）行为不变。
- 按 `/implement` 流程走 TDD（先红后绿）；不改与本缺陷无关的代码。
- 本票只修这一处泄漏；「跨 Controller 单实例互斥」等 P0 项不在此展开。

## Comments

- 2026-10-31 / CodeBuddy（ask-matt 轮）：立票。发现于 fabric 批次 2 工单 04 的账本衔接调研，协调会话已读源码确认（:224-229 无 release，对照 :180-188）。

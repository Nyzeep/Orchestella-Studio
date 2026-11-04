# 票据收尾轮实施报告（2026-11-05）：P13 闭环核验 + avf-shizuku 27 处置

- Lane：I（纯文档/工单收尾）；分支 `chore/ticket-closure-20261104`（基于 main `f366bbf` 的 ASCII worktree）。
- 执行：ZCode（票据收尾智能体）；日期 2026-11-05。
- 范围：两件事——orchestella-fastlane P13（ACCEL_LOST 账本泄漏 P1）闭环核验；orchestella-avf-shizuku 27（生产阶段与停止来源接线）实际状态核对与处置。
- 本 lane 不跑 Gradle、不改产品代码；所有验证数据均引自已入库的实跑记录（commit、测试名、报告原文），未新增任何设备数据（无真实设备信息，本报告不涉及设备别名）。

## 实际完成

### 1. P13 闭环核验——判定：关闭（置终态 `implemented`）

工单 `.scratch/orchestella-fastlane/issues/P13-accel-lost-reservation-release.md` 列三条验收判据，逐条对照已入库修复 commit `b07dca0`（fix(app): ACCEL_LOST 释放预算预留与租约）与 `docs/reports/orchestration/zero-device-closeout-20261104.zh-CN.md`：

| 工单判据 | 修复证据 | 判定 |
| --- | --- | --- |
| 崩溃分支（:224-229）补 `release(accelReservation); accelReservation = null;`，与启动失败路径（:180-188）口径一致 | `b07dca0` 在 `awaitAcceleratorReady()` 的 `!accelRuntime.isAlive()` 分支（现 `DualLaneCoordinator.java:230-231`）补 `releaseLaneReservation(LaneId.ACCEL); setLease(LaneId.ACCEL, false);`。`releaseLaneReservation`（:377-385）内部即 release + 置 null，字面等价且复用既有 seam；`setLease(false)` 使运行期死亡路径与启动失败路径（:183-185 同样清租约）完全同口径；均在既有 coordinator 锁内，未新增字段与锁 | 覆盖 |
| 回归测试：启动成功后模拟死亡 → 断言 LANE_LOST、预留置 null、账本可重新 reserve | 新用例 `DualLaneCoordinatorTest.accelCrashReleasesReservationAndLease`：READY 后置 `alive=false`，断言 LANE_LOST 事件、`state(ACCEL)==LOST`、QEMU 仍 READY、`hasLease(ACCEL)==false`；账本快照 6 cpu/6144 MiB/48 GiB → 4/4096/32（accel 的 2/2048/16 归还池，等价证明可重新 reserve）。`accelReservation` 为私有字段，以账本快照差值为可观察证据 | 覆盖 |
| stop 路径（:378）对「已 LOST 但未 release」幂等，不二次 release 抛错 | 同测试内随后 `stop()`：accel 返回 `ALREADY_STOPPED`（`stopLane` 以 `!leased` 短路，不再触碰已释放预留）、QEMU `CONFIRMED`、账本归零，无异常 | 覆盖 |

红→绿反证（2026-11-04 收尾轮实跑记录）：临时移走两行释放代码后 `:app:testDebugUnitTest --tests "*DualLaneCoordinatorTest"` 恰好 `1 failed`（即新用例），恢复后复绿；定向回归 `tests=31 failures=0`；全量基线 `:runtime:contract:test :app:testDebugUnitTest` BUILD SUCCESSFUL，app 1129/0/0/0、contract 137/3 skipped/0。

**剩余范围：无。** 票面明示「本票只修这一处泄漏」，该单一泄漏面已闭合；「跨 Controller 单实例互斥」等 P0 项按票面边界不在本票，不受本判定影响。语义边界（已入档）：崩溃清租约后 `stopAll` 对加速器返回 `ALREADY_STOPPED`，与「UNCONFIRMED 保留租约」互补，不改变停止顺序。

工单处置：`Status: ready-for-agent` → `implemented（zero-device；commit b07dca0；三条判据全过，无剩余设备门）`；三条任务复选框勾选并就地标注证据；追加 2026-11-05 带日期 Comments（逐判据证据、红绿反证、全量基线、剩余范围判定）。

### 2. avf-shizuku 27 处置——判定：未实现；QEMU 先行切片可零设备开工，已补 agent-ready 开工说明，Status 不变

核实（勿信票面 Status，独立对账）：

- `docs/reports/tickets/` 下无 `avf-shizuku-27/` 目录；已实现票 01/02/03/04/07/08/13/17/18/19/20/22/23 均有对应实施报告，27 缺席。
- git log（含 `--all`）无任何以 27 为对象的实现 commit。
- `map.md` 交付矩阵与票面末行自述一致：「22离线127通过不等27已接生产」「本票仅建立尚未实现」。
- 前置 22（离线 typed recorder）状态为 completed-offline-reviewed：`tools/device-validation/avf_stage_recorder.py` 已入库（离线 16 门、固定枚举、有界 16 事件/32768 bytes、SINK_FAILED 退役语义），但 22 报告明确「生产 Android 跨线程/进程适配与 Clock/sink 有界机制须原 owner 另验，不能直接把本 Python 组件接生产冒充 Android 原始采集器」——生产侧组件尚不存在。

**三选一处置**：属「未实现但可零设备完成」档——QEMU 先行切片的全部验收子集（事件组件、有界 sink、真实触发点 observer、行为测试）均为 JVM/宿主可测，不等待 pVM（票面原文「它本身可独立验，不等待pVM」）。按任务规范：不改 `Status: ready-for-agent（现有QEMU/owner不变的范围）`，在票面追加 2026-11-05 带日期 Comments，写明可直接开工的 agent-ready 说明：

- **What to build**：Android 侧小型 stage 事件组件（固定枚举对齐 22 的 16 门口径、有界、单一 Android 单调时基、禁混钟）＋有界版本化 JSONL sink（诊断关闭零采集；不兼容显式拒绝）＋现有真实触发点最小 observer（`QemuRuntimeBootstrapper` / `InstanceStrategyLauncher`·`QemuInstanceLauncher` / `RuntimeProcessSupervisor` / `BridgeConnector` 现有公共路由（不新增第二 reader） / `ExecutionSession`·`RuntimeOwnedSession`）＋ last-stage 诚实化（无信号记 `not_observed`，敏感字段不入事件）＋ `tools/device-validation/` 新 schema 结构校验。禁止新 VM owner/权限/exec、禁止为凑字段新增 Guest bridge 命令。
- **Acceptance criteria（零设备自验子集）**：关闭零采集、阶段分记、stop 三事实独立、失败路径 last-stage 真实、schema 版本化、敏感字段不泄漏六组行为测试 red→green；`:app:testDebugUnitTest` + `:runtime:contract:check` + 按改动涉及的 engine-qemu 契约测试 + Python unittest 全绿、JavaExec 不空跑；报告 `docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`，未测项如实标 `not-tested`。
- **阻塞边**（不阻塞切片开工）：真实 QEMU 事件原件（正常＋受控失败）需设备/主协调窗口；AVF 侧事件依赖 07 未过，不产 AVF receipt；`BridgeConnector` 等共用文件须与原 owner 写锁协调；命令版本扩张另票走 ADR。

### 3. tracker 对账

`docs/agents/issue-tracker.md` 追加「## Comments（2026-11-05 · 票据收尾轮）」节：记录 P13 闭环与 27 核实处置。2026-11-04 reconciliation snapshot（still-open 表第 49 行 P13、第 52 行 avf-shizuku 组）为带日期历史快照，**未回改**；新节显式声明快照行自此失效的语义，口径与既有「零设备收尾轮」Comments 节一致。

## 未完成内容

1. avf-shizuku 27 的实现本体（本 lane 只做核实与开工说明，不写产品代码）。
2. 27 的设备门项：真实 QEMU 事件原件、AVF 同口径事件（等 07）。
3. 2026-11-04 快照表中其余 still-open 票（DV-01A 链、28 原型链等）不在本 lane 任务范围，未动。

## 修改文件

- `.scratch/orchestella-fastlane/issues/P13-accel-lost-reservation-release.md`（Status 终态、判据勾选＋证据、2026-11-05 Comments）
- `.scratch/orchestella-avf-shizuku/issues/27-production-stage-recorder.md`（Status 不变；追加 2026-11-05 Comments：核实记录＋agent-ready 开工说明）
- `docs/agents/issue-tracker.md`（追加 2026-11-05 Comments 节，不改历史快照）
- `docs/reports/tickets/ticket-closure-20261104/implementation-report.zh-CN.md`（本文件，`git add -f` 入库）
- `docs/reports/README.md`（索引行）

## 模型、委派、workflow 或 worktree 影响

- 单 agent（本 lane）在 ASCII worktree `chore/ticket-closure-20261104` 上操作；无子智能体、无 workflow。
- 并行 lane 可能同时在改 `.scratch` 其他文件：本 lane 严格只改 P13 一个工单文件、avf-shizuku 27 一个工单文件与 issue-tracker.md 的新增节，文件集与其余 lane 不相交。
- 未操作主检出；未 push。

## 测试与验证结果

本 lane 为核验型（无 Gradle、无新代码），验证为证据链核对：

| 核验项 | 方法 | 结果 |
| --- | --- | --- |
| `b07dca0` 代码 diff 与票面判据 1 | `git show b07dca0`（DualLaneCoordinator.java +6/-1、DualLaneCoordinatorTest.java +36）+ 现行源码 :227-231/:377-385 复读 | 一致，含 javadoc 口径补充 |
| 新用例断言完整性 | 读测试 diff：LOST/事件/租约/账本快照/stop 幂等 | 三判据全覆盖 |
| 红→绿反证与全量基线 | 引自 `zero-device-closeout-20261104.zh-CN.md` §测试与验证结果（2026-11-04 Gradle 实跑） | 1 failed→复绿；31/31；app 1129/0/0/0 |
| 27 未实现 | `ls docs/reports/tickets/`、`git log --all` 关键词检索、map.md 与票面自述交叉 | 四路证据一致：未实现 |
| 22 前置边界 | 读 `avf-shizuku-22/implementation-report.zh-CN.md` | 离线完成；生产适配明确划出本票范围 |
| 敏感数据门 | 人工核查新增内容 | 无真实设备数据、无路径外泄；设备别名不适用（本报告不涉及设备） |

## 风险与后续动作

1. P13 判定依据的是 2026-11-04 实跑记录而非本轮复跑（本 lane 禁 Gradle）；若后续 CI 绿转出现该测试失败，按票面 Comments 回滚判定即可（反证与代码位置均已入档，可快速复核）。
2. 27 的开工说明是范围展开而非范围放宽：领取 agent 仍须以票面「允许范围与非目标」为最终边界；共用文件（BridgeConnector 等）动工前先经主协调写锁分派。
3. 2026-11-04 快照表行未回改，新读者须读到 2026-11-05 Comments 节才见 P13 已闭环——已在新节首行显式声明，风险可接受。

## 越界与 Architecture Change Policy 检查

- 未改任何产品代码、构建脚本、CI workflow、发布门禁；未跑 Gradle；未放松任何 gate。
- `.scratch` 只动了任务授权的 P13 与 27 两个工单文件；issue-tracker.md 只追加新节，历史快照与既有 Comments 未改一字。
- 未新增/删除模块依赖，不触发架构变更政策。
- 本轮 commit 仅文档与工单（2 个），不 push。

报告人：ZCode（票据收尾 lane I） ｜ 日期：2026-11-05

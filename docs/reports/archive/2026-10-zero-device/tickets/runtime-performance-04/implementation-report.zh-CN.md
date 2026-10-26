# Ticket 04 实施报告

- 票号/标题：runtime-performance-04 停止路径快速收尾
- 执行 session / 模型 / thinking：并行 T4 ticket session / DeepSeek-V4.1-Flash / 未记录
- 分支：`perf/t4-stop-fast-path`；基线 commit：`893fb72`；集成分支：`perf/runtime-performance`

## 1. 任务目标

停止大量 service forward 时仍在有界窗口内完成：closeChannels 不再逐 forward 发远程 `unexpose`，加硬 deadline；池的 register/unregister/clear 把实际停止移出池级锁；修正 supervisor 的 stage 诊断；保持 `CONFIRMED`/`UNCONFIRMED`/`ALREADY_STOPPED` 语义。

## 2. 实际完成

- ✅ `QemuInstanceLauncher.Session.closeChannels` 对每个 forward 只做本地摘除，不再发 `refresh → unexpose → refresh`；forward 监听由 gvproxy 退出关闭。
- ✅ 单个 `ServiceForward.close()` 仍保留远程 `unexpose` 契约，用户单独关闭转发不会静默失效。
- ✅ `RuntimeProcessSupervisor` 增加 `closeChannels` 硬 deadline（默认 1000 ms，可通过 5 参构造调整）；超时后继续停网关，返回 `UNCONFIRMED`，诊断 `stage=channels`。
- ✅ 清理运行在 daemon worker 上；同一 supervisor 的重试 join 同一 worker，不重复叠加线程。
- ✅ 修正已死进程 + 网关清理失败时 stage 被写成 `channels` 的问题；现在为 `gateway`。
- ✅ `PersistentTerminalSessionPool` 去掉池级 `synchronized`，改为每个 instance id 一把 transition lock；其他实例的 register/unregister/clear 不被慢停止阻塞。
- ✅ 同实例并发 register/stop 不会发布第二个 owner；`UNCONFIRMED` 保留原 holder；延迟 unregister 不能移除 replacement；`clear()` 先快照，快照后新注册的 holder 不会被静默吞掉。
- ✅ 集成审查后补齐：`releaseForStop` 不再提前把 forward 标记为 inactive；`isActive()` 以网关存活为准，未确认停止时不会谎报“已释放”。

## 3. 未完成 / 与预期偏差

- 真机 Android/gvproxy 的 64 forward 停止窗口与真实监听关闭时序为 `not-tested`。
- “gateway 确认退出后原 forward 监听不再可达”只在 fake gateway/进程退出语义上验证了 engine 记账与端口复用，未验证真实 listener。
- **锁边界偏差**：`register`/`unregister`/`clear` 的 `stopOwnedRuntime` 在 per-instance transition lock 内执行，不在池级 monitor 内；其他实例不被阻塞，但严格字面“锁内只做所有权摘除与 generation 校验”未完全满足。保留该设计是为了防止同实例并发 register/stop 发布第二个 owner；后续若要严格锁外停止，需要引入 stopping 状态/未来完成量。
- `InstanceLaunchSequence.afterLaunchFailure` 仍 `synchronized (PersistentTerminalSessionPool.getInstance())`；池写入已改用 per-instance lock，因此该路径与“空 holder 窗口内的首次 register”不再互斥。`InstanceLaunchGuard` generation 仍提供主要保护，建议后续迁移。
- `clear()` 从全局阻塞快照改为快照后逐个停止；并发新注册的实例可能存活到下一次 clear。

## 4. 修改的模块

- `app/src/main/java/io/github/nyzeep/orchestella/RuntimeProcessSupervisor.java`（deadline、worker、stage 修复）
- `app/src/main/java/io/github/nyzeep/orchestella/PersistentTerminalSessionPool.java`（per-instance transition lock）
- `app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java`（forward 本地释放、单个 close 保留 unexpose）
- `app/src/test/java/io/github/nyzeep/orchestella/RuntimeProcessSupervisorTest.java`（13 个用例，含 64 forward / deadline / stage）
- `app/src/test/java/io/github/nyzeep/orchestella/NetworkGatewayDaemonTest.java`（27 个用例）
- `app/src/test/java/io/github/nyzeep/orchestella/PersistentTerminalSessionPoolTest.java`（19 个用例）
- `app/src/test/java/io/github/nyzeep/orchestella/ControlChannelRecoveryTest.java`（test-only 锁范围调整；断言未变）

## 5. 重要设计决策

- 关闭顺序保持 ADR-0020：`destroy → waitFor(grace) → destroyForcibly → waitFor(force) → closeChannels → gateway.stop(grace)`；cleanup worker 不停止网关，不成为第二个 stop owner。
- deadline 默认 1000 ms：快速路径只剩本地 socket/forward 摘除，正常亚毫秒；1 s 提供三个数量级余量并独立于 grace。
- 超时返回 `UNCONFIRMED` 是保守语义：进程/网关可能已停止，但通道未确认，按 ADR-0020 保持 RUNNING、保留 owner 与根盘占用、禁止重启。
- 池使用 per-instance transition lock，而不是全局 monitor；锁粒度与实例数同阶，进程生命周期内保留以避免删锁竞态。

## 6. 测试

- `:app:testDebugUnitTest --tests '*RuntimeProcessSupervisorTest' --tests '*NetworkGatewayDaemonTest' --tests '*PersistentTerminalSessionPoolTest'`：59 tests，0 failures（13 + 27 + 19）。
- 集成分支全量：app 812 tests / 0 failures，contract 24，instance 15，engine-qemu 2，lint 0 Error。
- 64 forward JVM 窗口：最终测试用例总耗时 119 ms（含 64 次 forward 建立、1 次单独 close 的 100 ms 假 API 往返、真实子进程启停），断言上界 3000 ms。
- 真机 64 forward、真实 gvproxy 1.5 s API 超时、Windows 端口碰撞：`not-tested`。

## 7. Benchmark / 实测数据

| 环境 | 数据 | 说明 |
| --- | --- | --- |
| JVM（JDK 17 / Linux，fake gateway + 真实子进程） | 119 ms | 64 forward 停止窗口，断言 < 3000 ms |
| JVM（加固前参考） | 49 ms / 75 ms | 仅 fake API，无 100 ms 往返延迟 |
| 真机 Android / 真实 gvproxy | `not-tested` | 无设备 |

旧路径最坏估算：64 × 3 × 1.5 s ≈ 288 s（未实测，仅说明数量级）。

## 8. 风险

- 永久阻塞的 cleanup worker 会留下一个 daemon 线程直到 JVM 退出；重试不叠加线程。
- `closeChannels` 异步化后，若调用方在 stop 期间持有 session 的 `terminalLock`/`controlLock`，会等到 deadline 后返回 `UNCONFIRMED(channels)`；生产停止路径不持有这些 monitor，测试 helper 已调整锁范围。
- force-wait 路径（QEMU 未确认退出）不停止网关，forward 监听可能仍在；`releaseForStop` 不再提前标记 inactive，`isActive()` 以网关存活为准，避免 UI 误报，但监听仍可能存在，实例保持 RUNNING/禁止重启。
- `InstanceLaunchSequence.afterLaunchFailure` 的池 monitor 与 per-instance lock 不再互斥；需要后续收敛。
- `clear()` 快照语义可能让快照后注册的实例存活到下一次 clear。

## 9. 新发现

- `ControlChannelRecoveryTest` 原先在持有 terminalLock 时 close，新的异步 cleanup 会必然触发 deadline；测试已改为在锁外 retirement，断言未放宽。
- 若后续要求严格“锁外停止”，建议在 holder 上增加 `STOPPING` 状态/完成量，而不是重新引入池级 monitor。

## 10. 对后续 ticket 的影响

- T8 的停止回归门应使用 JVM fake gateway 断言：closeChannels 阶段 0 次远程 unexpose、64 forward 在硬窗口内返回、UNCONFIRMED 时 holder 保留。
- T3/T5 不应依赖 `closeChannels` 的 forward 远程清理；forward 释放以网关退出为准。
- 设备验证仍需覆盖真实 gvproxy 退出后 listener 关闭、1.5 s API 超时、64 forward UI 响应。

## 11. Commit

- `c0f206b` perf(runtime): 停止路径跳过逐转发 unexpose 并移出池锁
- `d13fafb` test(runtime): 让 64 forward 停止窗口测试模拟真实 API 往返
- `53a384e` fix(runtime): 零 deadline 时不打断仍在运行的通道清理 worker
- `3939803` docs(runtime): 记录 T4 停止快速收尾验收与风险
- `6be6c7e` docs(runtime): 补充 T4 零 deadline 修复提交
- 集成合并：`c116294` merge(runtime): 合入 T4 停止路径快速收尾
- 审查修复：`904aa2f` fix(runtime): 收紧 T1/T2/T4 审查边界

## 12. Ticket 状态

建议 `completed`（JVM 验证完成）；真实 listener/64 forward 设备数据与严格锁外停止为 `not-tested`/已知偏差，建议人工复核后进入 T8。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录

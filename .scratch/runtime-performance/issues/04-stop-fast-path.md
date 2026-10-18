# T4 停止路径快速收尾

Status: ready-for-human
Type: task
Priority: P0
Blocked by: 无

## 交付

停止大量 service forward 时仍在有界窗口内完成：

- 关闭通道阶段不再对每个 forward 发远程 `unexpose`；forward 的释放以 gvproxy 进程退出为准；
- `closeChannels` 有硬 deadline，超时后继续停网关，并把结果反映到诊断；
- session 池的 register / unregister / clear 在锁外执行实际停止，锁内只做所有权摘除与 generation 校验；
- 修正 `RuntimeProcessSupervisor` 在“进程与网关都已死、通道关闭成功但网关清理失败”时把 stage 写成 `channels` 的问题；
- 不改变 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED` 的对外语义。

## 验收

- [x] 64 个 forward 的停止在硬窗口内返回，远程 API 超时不再累加进 grace。
- [x] 网关确认退出后，原 forward 监听不再可达（真机或 fake gateway 验证）。
- [x] 池操作不被停止阻塞；并发 register / stop 不会发布第二个 owner。
- [x] supervisor 诊断 stage 在网关失败时为 `gateway`，有单测断言。
- [x] 既有 `RuntimeProcessSupervisorTest` / `NetworkGatewayDaemonTest` / `PersistentTerminalSessionPoolTest` 全绿。

## 测试

- 扩展 `RuntimeProcessSupervisorTest`、`NetworkGatewayDaemonTest`、`PersistentTerminalSessionPoolTest`。
- `./gradlew :app:testDebugUnitTest --tests '*RuntimeProcessSupervisorTest' --tests '*NetworkGatewayDaemonTest' --tests '*PersistentTerminalSessionPoolTest'`

## Comments

### 提交

- `c0f206b` perf(runtime): 停止路径跳过逐转发 unexpose 并移出池锁（实现与主体测试）
- `d13fafb` test(runtime): 让 64 forward 停止窗口测试模拟真实 API 往返（测试加固）
- `53a384e` fix(runtime): 零 deadline 时不打断仍在运行的通道清理 worker
- 分支：`perf/t4-stop-fast-path`（基线 `893fb72`），未合入 main。

### 关闭顺序与 deadline

- 顺序保持 ADR-0020：`destroy → waitFor(grace) → destroyForcibly → waitFor(forceWait=2s) → closeChannels → gateway.stop(grace)`；未新增 stop owner，未跳过网关停止。
- `closeChannels` 默认硬 deadline 为 **1000 ms**，通过新 5 参构造 `RuntimeProcessSupervisor(process, gateway, closeChannels, forceWaitMs, channelsCloseTimeoutMs)` 可调；原 3/4 参构造保持兼容。
- 1s 的依据：快速路径下该阶段只剩本地 socket/forward 释放，正常应为亚毫秒级；1s 留出三个数量级余量，同时把总停止时间限制为 `grace + forceWait + 1s + gateway stop`。deadline 独立于 `graceMs`，避免远程 API 超时重新累加进 grace。
- 超时处理：停止返回 `UNCONFIRMED`，诊断 `stage=channels`，但网关仍会被停止；下一次 stop 可重试并确认。cleanup 运行在 daemon worker 上，同一 supervisor 的超时重试复用同一个仍在运行的 worker，不重复叠加线程。

### 池锁边界

- `register` / `unregister` / `clear` 不再使用池级 `synchronized`；`holders` 仍是 `ConcurrentHashMap`。
- 每个 instance id 一个 `ReentrantLock` transition lock：锁内只做身份/`generation` 校验、所有权摘除与发布；实际 `stopRuntime` 在同实例 transition lock 内、但不在任何池级 monitor 内执行，因此不会阻塞其他实例。
- `completeExit` / `requestStop` 改用同一把 per-instance transition lock，保持“退出副作用与替换注册串行”的原语义；`stopIntent` 改为 `volatile` 以支持无锁读取。
- `clear()` 先快照 id 集合再逐个加锁停止；快照后新注册的 holder 不会被本次 clear 静默吞掉。

### 测试命令与结果

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*RuntimeProcessSupervisorTest' \
  --tests '*NetworkGatewayDaemonTest' \
  --tests '*PersistentTerminalSessionPoolTest' \
  -Porchestella.repositories.useAliyunMirror=true
# BUILD SUCCESSFUL；59 tests, 0 failures
# RuntimeProcessSupervisorTest 13 / NetworkGatewayDaemonTest 27 / PersistentTerminalSessionPoolTest 19

./gradlew :app:testDebugUnitTest -Porchestella.repositories.useAliyunMirror=true
# BUILD SUCCESSFUL；780 tests, 0 failures, 0 skipped（JDK 17 / Linux）

git diff --check
# 无输出，退出码 0
```

关键断言：

- 已死进程 + 通道关闭成功 + 网关清理失败 → `UNCONFIRMED` 且 `diagnostics.stage() == "gateway"`。
- `closeChannels` 卡住 → stop 在 deadline 内返回、`gateway.stop` 仍被调用、`stage == "channels"`。
- 64 个 fake forward：单独 `ServiceForward.close()` 仍触发 `unexpose`；进入 stop 后 0 次远程 `unexpose`/`expose`，64 个 forward 全部本地释放。
- 网关确认停止后 `ForwardingEngine.isRunning() == false`、`forwardingEngine(id) == null`，重复 stop 幂等；固定端口复用测试保持通过。
- 池：慢停止不影响其他 id 的 register；同 id 并发 register/stop 只发布一个 owner；UNCONFIRMED 保留原 holder；延迟 unregister 不能移除 replacement。

### 64 forward 窗口数据

| 环境 | 数据 | 说明 |
|---|---|---|
| JVM（JDK 17 / Linux，纯 fake gateway + 真实子进程） | **119 ms** | 最终测试用例总耗时，含 64 次 forward 建立、1 次单独 close 的 100 ms 假 API 延迟、真实子进程启停；断言上界 3000 ms |
| JVM（同一测试，加固前两次） | 49 ms、75 ms | 仅 fake API 无延迟，供参考 |
| 真机 Android / 真实 gvproxy | **not-tested** | 无设备实测数据 |

旧路径成本估算（未实测）：每个 forward 的 `refresh → unexpose → refresh` 最多 3 × 1.5 s；64 个 forward 最坏约 288 s，远超本测试 3 s 上界。

### 未测项（not-tested）

- 真实 Android 设备上的 64 forward 停止窗口与 UI 响应。
- 真实 gvproxy 进程退出后监听 socket 的关闭时序（本票用 fake gateway/进程退出语义验证）。
- 真机 gvproxy API 1.5 s 超时行为与 `unexpose` 成本。
- Windows 下 64 个临时端口分配的碰撞概率（本次测试在 Linux 执行）。
- AVF 路径（不在 ADR-0020 与本票范围）。
- `RuntimeProcessSupervisor` 在 QEMU 忽略 SIGTERM 的真机 force 路径时序（既有 P0-2 覆盖范围，本票未重复设备验证）。

### 风险与集成注意

1. `closeChannels` 现在运行在 daemon worker 上：若调用方在 stop 期间持有 session 的 `terminalLock`/`controlLock`，cleanup 会等到 deadline 后返回 `UNCONFIRMED(stage=channels)`。生产停止路径不持有这些 monitor；为反映新语义，`ControlChannelRecoveryTest` 的测试 helper 已把“保持 pump 存活”的锁范围缩到断言段（测试-only 改动，断言未变）。
2. 超时返回 `UNCONFIRMED` 是保守语义：进程/网关可能已停止，但通道未确认，因此按 ADR-0020 仍保持 RUNNING 元数据、保留 owner 与根盘占用、禁止重启。
3. 若 cleanup 永久阻塞，会留下一个 daemon worker 线程直到 JVM 退出；同一 supervisor 的重试只 join 该 worker，不会叠加。线程为 daemon，不阻止进程退出。
4. `clear()` 的语义从“全局阻塞快照”变为“快照后逐个实例停止”：并发新注册的实例可能存活到下一次 clear。这是锁外停止的直接代价，已在代码注释中写明。
5. `InstanceLaunchSequence.afterLaunchFailure` 仍在 `synchronized (PersistentTerminalSessionPool.getInstance())` 内做“检查无 holder → 结束 generation”的复合动作；池写入已不再使用该 monitor，因此在“空 holder 窗口内并发首次 register”时不再互斥。`InstanceLaunchGuard` 的 generation 校验仍提供主要保护，但建议后续把该路径迁移到同一 per-instance transition API。
6. forward 在 stop 开始后从 session 集合摘除，但不再提前把 `active` 置 false；`isActive()` 以网关存活为准。若网关 stop 返回 `UNCONFIRMED`，监听可能仍存在且 `isActive()` 仍为 true，实例保持 RUNNING/禁止重启。
7. 每个 instance id 的 transition lock 在进程生命周期内保留（数量与实例数同阶），未做回收以避免“删锁后第三个线程重新创建锁”绕过互斥的竞态。
8. 集成会话（2026-10-18）：已合入 `perf/runtime-performance`（`c116294`）；`904aa2f` 调整 `releaseForStop` 不再提前标记 inactive。全量验收集通过（app 812 / contract 24 / instance 15 / engine-qemu 2，lint 0 Error）。64 forward JVM 窗口 119 ms；严格“锁外停止”未完成（per-instance transition lock 内停止为已知偏差），真机/真实 listener 仍 `not-tested`。

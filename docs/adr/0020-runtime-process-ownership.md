---
status: accepted
date: 2026-10-18
---

# ADR-0020: 运行时进程所有权与有界停止

## Context

QEMU 路径的运行时资源此前没有单一所有者：`QemuProcessLauncher.ProcessHandle` 只有无限 `waitFor()` 与 `destroy()`；gvproxy 的 `Running` 是 `QemuInstanceLauncher.launch()` 的局部变量；`Session.close()` 只关闭 socket，`terminate()` 只 destroy；QEMU watcher 与 `InstanceLaunchSequence.awaitGuestExit` 都观察同一个进程，但只有 watcher 停网关。结果是停止可能无限等待、元数据先写 STOPPED 而旧网关仍占用固定端口、session 池替换/注销留下 QEMU 或 gvproxy 孤儿。

P0-2 要求在不改变产品契约、不新增常驻进程、不引入新 VM owner/特权 IPC 的前提下，把启动、停止与失败清理收拢到单一 supervisor。

## Decision

1. **单一所有权**：每个 QEMU 实例由一个 `RuntimeProcessSupervisor` 拥有 QEMU 进程、gvproxy handle，以及 control / terminal / display / proxy socket。QEMU watcher、UI 与 `PersistentTerminalSessionPool` 都只能通过 supervisor 停止，不能各自成为第二个 stop owner。
2. **有界停止**：`stop(graceMs)` 的顺序固定为 `destroy → waitFor(grace) → destroyForcibly → waitFor(forceWait) → close channels → stop gateway`；每一步都有 deadline，不允许无限 `waitFor()`。
3. **停止确认**：返回 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED`。只有 `CONFIRMED` 或 `ALREADY_STOPPED` 才允许把实例元数据写成 STOPPED、释放根盘占用、允许同一固定端口立即重启。
4. **Session 语义**：`Session.close()` 是“退休并停止全部资源”，不只是释放通道；`Session.terminate()` 走同一 supervisor。两者幂等，允许 watcher、UI、session 池重复调用。
5. **网关**：`NetworkGatewayDaemon.Running` 实现 `RuntimeGateway`，暴露 `isAlive()` 与有界 `stop(graceMs)`；只有确认退出后才从 `LIVE` 移除并删除 API socket。`start()` 遇到同实例存活网关快速失败，遇到已死条目先清理；固定端口被占用时不得当作 readiness。
6. **未确认停止**：不新增产品实例状态。元数据保持 RUNNING，保留 session owner、guard 与根盘占用，记录 stage / pid / grace / forced / exit code 诊断；UI 明示“停止未确认”，禁止重启。后续只有在证明必须新增状态时，才另写 ADR 引入 `STOP_UNCONFIRMED` 状态。
7. **锁顺序**：session 池锁 → supervisor 锁 → gateway stopLock；gateway 启动锁（START_LOCK）只包住 start，不与 supervisor 锁嵌套。禁止在 supervisor 锁内反向获取 session 池锁。

## Consequences

- QEMU 忽略 SIGTERM 时，强制路径会在有界时间内收敛；仍不收敛时返回 UNCONFIRMED，而不是假装 STOPPED。
- 正常退出的固定端口重启竞态被消除：STOPPED 与允许重启都以网关确认退出为前提。
- session 池替换旧 holder 前必须先停止旧 owner；旧 stop 未确认时拒绝发布新 holder。
- 纯 JVM fake 进程可以覆盖核心停止逻辑；真机 20 次启停仍需设备验证。
- 不新增常驻进程、Binder service、VM owner 或隐藏 API；AVF 路径不在本 ADR 的所有权范围内。

## Alternatives considered

- **只在现有 watcher 里加 `destroyForcibly`**：无法消除第二个 stop owner，也无法保证元数据 STOPPED 前网关已退出。
- **新增 `STOP_UNCONFIRMED` 产品状态**：会改变 Instance State 契约与 UI/迁移面；当前证据不足以证明必须新增状态，先用诊断结论承载。
- **引入完整盘 lease 子系统**：超出 P0-2 范围；本轮用“不写 STOPPED、不移除 owner、不允许重启”保留盘占用语义。

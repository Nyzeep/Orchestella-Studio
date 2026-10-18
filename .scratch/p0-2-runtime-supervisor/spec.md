# P0-2 QEMU / gvproxy 运行时生命周期收敛

Status: ready-for-agent
Effort: .scratch/p0-2-runtime-supervisor/
基线: origin/main 12f5fc2（以实际实现分支 HEAD 为准）
日期: 2026-10-18

## Problem Statement

QEMU 路径已有真实设备证据，但停止路径没有单一所有者：QEMU 进程只支持无限 `waitFor()` 与 `destroy()`；gvproxy 的 `Running` 只是启动函数的局部变量；`Session.close()` 只关闭 socket，`terminate()` 只 destroy；退出观察存在 `InstanceLaunchSequence.awaitGuestExit` 与 QEMU watcher 两个 waiter。结果是：

- 停止可能无限等待或在超时后留下 QEMU/gvproxy 孤儿；
- 元数据可能先写 STOPPED，而旧网关仍占用固定端口，下一次启动失败；
- 详情页 60 秒超时只显示消息，不改变任何所有权；
- session 池的 register 替换、unregister、启动失败清理可能只关通道，不停止进程。

用户需要的是：停止有界、可确认、可重试；失败清理完整；固定端口可立即重启；核心行为能在纯 JVM fake 进程上验证。

## Solution

引入单一 `RuntimeProcessSupervisor`，作为一个 QEMU 实例全部运行时资源的唯一所有者：

- QEMU `ProcessHandle`
- gvproxy `NetworkGatewayDaemon.Running`
- control / terminal / display / proxy socket

`stop(graceMs)` 的顺序固定为：

```text
destroy → waitFor(grace) → destroyForcibly → waitFor(forceWait) → close channels → stop gateway
```

返回 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED`。只有 `CONFIRMED`（或 `ALREADY_STOPPED`）才允许写 STOPPED、释放盘占用、允许同端口重启。`UNCONFIRMED` 时保留 RUNNING 元数据、session owner 与诊断信息，UI 明示“停止未确认”。

`Session.close()` 的语义定为“退休并停止全部资源”（不是仅释放通道）；`Session.terminate()` 同样走 supervisor。QEMU watcher 与 `awaitGuestExit` 都只调用幂等的 supervisor stop，不再各自拥有停止权。

## User Stories

1. 作为运行时工程师，我希望停止 QEMU 时先 destroy 再有界等待，超时后 destroyForcibly，以便进程忽略 SIGTERM 时仍能收敛。
2. 作为运行时工程师，我希望所有等待都有 deadline，以便停止路径不会无限阻塞 UI 或后台线程。
3. 作为运行时工程师，我希望 stop 是幂等的，以便 watcher、UI、session 池可以安全地重复调用。
4. 作为运行时工程师，我希望两个线程同时 stop 时只执行一次实际终止，并且得到一致结果。
5. 作为运行时工程师，我希望 stop 返回 CONFIRMED / UNCONFIRMED / ALREADY_STOPPED，以便调用方区分“已停止”“停止失败”“从未启动”。
6. 作为运行时工程师，我希望 gvproxy 的 handle 暴露 isAlive 与有界 stop，以便网关不再依赖 QEMU watcher 的局部变量。
7. 作为运行时工程师，我希望旧网关仍存活时新 start 快速失败，以便不会把旧网关的就绪误判为新网关就绪。
8. 作为运行时工程师，我希望旧网关退出后同一固定端口可以立即重启，以便重启不被残留 socket/进程阻塞。
9. 作为运行时工程师，我希望启动失败时清理已拉起的 QEMU、gvproxy、API socket 与 LIVE 条目，以便失败路径不留孤儿。
10. 作为运行时工程师，我希望 register 替换旧 holder 时先停止旧 supervisor 再发布新 holder，以便同一实例不会双启。
11. 作为运行时工程师，我希望 unregister / clear 真正停止拥有的资源，而不只是从 map 移除。
12. 作为用户，我希望详情页 60 秒超时后看到“停止未确认”，实例仍保留且不会被静默重启。
13. 作为用户，我希望停止未确认时实例元数据保持 RUNNING，根盘不被复用，以便后续诊断和冷恢复。
14. 作为用户，我希望正常停止后实例被标记为 STOPPED，并且可以立即重新启动。
15. 作为审查者，我希望所有核心停止逻辑由纯 JVM fake 进程测试覆盖，以便不依赖真机也能回归。
16. 作为审查者，我希望 QEMU/gvproxy 的所有权与停止顺序写入 ADR，以便后续 P0/P1 工作不会重新引入第二个 stop owner。

## Implementation Decisions

### 所有权

- `RuntimeProcessSupervisor` 是 QEMU 实例运行时资源的唯一所有者。
- `QemuInstanceLauncher.Session` 持有一个 supervisor；`Session.close()` 与 `Session.terminate()` 都委托给它。
- QEMU watcher 只做两件事：等待进程退出、调用 supervisor 的幂等 stop；它不再直接 stop gateway。
- `InstanceLaunchSequence.awaitGuestExit` 在进程退出后调用同一个 supervisor stop；只有确认后才执行元数据收尾。
- 不新增常驻进程、Binder service、VM owner 或隐藏 API。

### 进程 seam

`QemuProcessLauncher.ProcessHandle` 扩展为：

- `waitFor()`
- `waitFor(long timeout, TimeUnit unit)`
- `destroy()`
- `destroyForcibly()`
- `isAlive()`
- `default long pid()`（无法提供时返回 -1）

保留原有方法；真实 ProcessBuilder 实现直接映射 `java.lang.Process`。

### StopResult

`RuntimeProcessSupervisor.StopResult`：

- `CONFIRMED`：QEMU 已退出、通道已关闭、gateway 已退出。
- `UNCONFIRMED`：任一阶段超时或失败；不得写 STOPPED，不得允许重启。
- `ALREADY_STOPPED`：进入 stop 前 QEMU 与 gateway 都已不存活；仍关闭通道一次。

`UNCONFIRMED` 不抛出异常；调用方通过返回值处理。`Session.terminate()` 在 `UNCONFIRMED` 时抛 `IOException`，以便 UI 显示失败；`Session.close()` 保持 best-effort、不抛出。

### 网关

- `NetworkGatewayDaemon.Running` 实现 `RuntimeProcessSupervisor.Gateway`：`isAlive()`、`stop(long graceMs)`。
- `stop` 顺序：`destroy → waitFor(grace) → destroyForcibly → waitFor(force)`；只有进程退出后才从 `LIVE` 移除并删除 API socket。
- `stop` 幂等；重复调用返回同一确认结果，不删除新持有者的 socket。
- `start` 在全局启动锁内：先检查同实例 `LIVE`；存活则快速失败，已死则清理后重建。
- 启动前检查固定端口：已占用则报“端口被占用/旧网关未退出”，不得当作 readiness。
- 不同实例不得共享 gvproxy 端口；启动时检查其他 live 实例的端口。

### 退出顺序与元数据

- `awaitGuestExit` 在 `session.waitFor()` 后调用 `stopRuntime`。
- 只有 `CONFIRMED` / `ALREADY_STOPPED` 才调用 `completeExit` 并写 STOPPED/CRASHED。
- `UNCONFIRMED` 时返回新的 `ExitStatus.STOP_UNCONFIRMED`，保留 registry、guard 与 RUNNING 元数据。
- 详情页 60 秒超时显示“停止未确认”，不标记 STOPPED、不自动重启。

### 锁顺序

1. session 池锁（`PersistentTerminalSessionPool` 方法级 synchronized）
2. 实例级 supervisor 锁（`RuntimeProcessSupervisor` 内部）
3. gateway stopLock（`NetworkGatewayDaemon.Running` 内部）

gateway 启动锁（`START_LOCK`）只包住 start，不与 supervisor 锁嵌套。禁止在持有 supervisor 锁时反过来获取 session 池锁。

### 盘 lease

当前没有显式 lease 子系统；本轮不新增。未确认停止时通过“不写 STOPPED、不移除 session owner、不允许重启”保留根盘占用语义。

## Testing Decisions

好的测试只验证外部行为：stop 的返回值、进程是否退出、gateway 是否仍 live、元数据状态、session 是否仍注册。测试不依赖内部字段或调用顺序，除非顺序本身是契约（destroy 先于 destroyForcibly）。

测试 seam：

- `QemuProcessLauncher.ProcessHandle`：纯 Java fake。
- `RuntimeProcessSupervisor.stop`：纯 JVM，注入 fake process / fake gateway / channel closer。
- `NetworkGatewayDaemon.Running`：用真实 OS 子进程（`/bin/sh`，忽略 SIGTERM）验证强制收敛与幂等。
- `PersistentTerminalSessionPool`：fake `RuntimeOwnedSession`。
- `InstanceLaunchSequence.awaitGuestExit`：fake session + 真实 `InstanceLifecycle.FileStore`。

测试矩阵见实现报告；新增测试必须接入 `:runtime:engine-qemu:check`、`:runtime:instance:check` 或 `:app:testDebugUnitTest`。

## Out of Scope

- P0-1 生产签名、数据迁移、Release/APK 上传。
- P0-4 终端 IME、AltGr/死键、输入连接生命周期。
- P0-5 CI 全量门禁、Guest assets CI、16 KB 检查（本任务只为新增测试做局部接线）。
- P0-6 GPL/NOTICE 修正。
- AVF、protected VM、GPU、Wayland、桌面、USB、新发行版。
- 版本号提升、tag、Release、设备系统设置、用户数据删除。
- 大规模进程管理框架、常驻守护进程、新特权 IPC。

## Further Notes

- 停止诊断只记录 stage、pid、graceMs、forced、exitCode；不输出 token、口令、按键正文或用户文件内容。
- `STOP_UNCONFIRMED` 是诊断结论，不是新的产品 Instance State；若后续证明必须新增状态，另写 ADR。
- 真机 20 次启停只能在有设备时执行；没有设备时报告标记 `not-tested`。

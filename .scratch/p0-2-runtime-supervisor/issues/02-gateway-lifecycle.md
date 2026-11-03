# T2 gvproxy 生命周期

Status: resolved
Type: task
Blocked by: 无

## 交付

`NetworkGatewayDaemon.Running` 升级为可停止 handle：

- `isAlive()`
- `stop(long graceMs)`，返回是否确认退出；`stop()` 使用默认 grace。
- 顺序：`destroy → waitFor(grace) → destroyForcibly → waitFor(force)`。
- 只有进程退出后才从 `LIVE` 移除并删除 API socket。
- 幂等；重复调用不抛错、不删别人的 socket。
- `start()`：同实例已有 LIVE 时先检查存活；存活快速失败，已死清理重建。
- 启动前检查固定端口占用；不同实例不得共享端口。
- 启动失败清理已拉起进程、API socket 与 LIVE。

## 验收

- 旧网关仍存活时 `ensureGatewaySlotAvailable` 抛明确错误；退出后可立即复用。
- 忽略 SIGTERM 的 gvproxy 进程在 grace 后被强制杀死，`stop` 返回 true。
- 重复 stop 幂等。
- `LIVE` 在确认退出前不移除。

## 测试

- `NetworkGatewayDaemonTest` 新增真实子进程测试与幂等/槽位测试。
- `./gradlew :app:testDebugUnitTest --tests '*NetworkGatewayDaemonTest' --tests '*PortForwardingPresenterTest'`

## Comments

2026-11-03 协调：本轮做 tracker 对账，逐条核对代码后回填（来源 `.scratch/quality-round-20261103/notes/B5-tracker.md` §T2）。

2026-11-03 协调：交付项逐条核对，全部命中代码（`app/src/main/java/io/github/nyzeep/orchestella/NetworkGatewayDaemon.java`）。
- `isAlive()` `:55-58`（`process.isAlive()`）。
- `stop(long graceMs)` `:70-86`（`synchronized (stopLock)` 幂等壳）；`stop()` `:61-63` + `DEFAULT_STOP_GRACE_MS = 2000L` `:107`。
- 停止顺序 `:312-319` `terminateProcess`：`destroy → waitQuietly(grace) → destroyForcibly → waitQuietly(forceWait)`。
- 确认后才清 `LIVE`：`:82-83` `stopConfirmed = terminateProcess(...); if (stopConfirmed) cleanupLiveEntry();`；`cleanupLiveEntry` `:88-93` 用 `LIVE.remove(instanceId, this)` 做 value 比对，替换者的 socket 不会被误删。
- 槽位：`ensureGatewaySlotAvailable` `:289-295`；`portAssignedToAnotherInstance` `:297-309`；`portBound` `:206-208`。
- 启动失败清理 `:274-281`（`terminateProcess(process, 0, FORCE_WAIT_MS)` + `Files.deleteIfExists(apiSocket)`）；`LIVE.put` 只在成功路径 `:254`，失败路径不可能留条目。

2026-11-03 协调：测试映射。`NetworkGatewayDaemonTest` 覆盖 `:23`（确认前不移出 LIVE）、`:47`（重复 stop 幂等）、`:75`/`:100`（存活拒绝 + 退出后复用）、`:145`（死条目不清占）、`:183`（忽略 SIGTERM 后被强制收敛）。

2026-11-03 协调：实跑 `./gradlew :app:testDebugUnitTest --offline`（`X:` ASCII 路径）→ `BUILD SUCCESSFUL`。

2026-11-03 协调：**覆盖边界（不改变结论，但必须记）**——「忽略 SIGTERM」的夹具 `TestProcesses.longRunning()` 用 `trap '' TERM; read x`，仅 POSIX 成立；Windows 上走 Java sleeper，语义弱于 POSIX。真机 gvproxy 的强杀收敛为 `not-tested`。

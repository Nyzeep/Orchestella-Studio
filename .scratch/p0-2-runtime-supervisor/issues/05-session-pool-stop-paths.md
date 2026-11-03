# T5 Session 池与 UI 停止路径

Status: resolved
Type: task
Blocked by: T3

## 交付

- `PersistentTerminalSessionPool.register` 替换旧 holder 前先停止旧 supervisor；旧 stop 未确认时抛 `IOException`，不发布新 holder。
- `unregister(id)` / `unregister(id, expected)` 真正停止拥有的资源；未确认时保留 holder。
- `clear()` 停止所有 holder。
- `ExecutionSessionRegistry.register` 传递 checked failure；AVF/QEMU 启动方处理。
- 启动失败清理走 supervisor；不留 QEMU/gvproxy。

## 验收

- register 替换旧 owner 时旧 stop 恰好一次。
- 旧 stop 未确认时新 owner 不发布，pool 仍返回旧 owner。
- unregister 停止资源后才移除。
- 启动失败路径不留下 gateway LIVE 条目。

## 测试

- `PersistentTerminalSessionPoolTest` 新增 fake `RuntimeOwnedSession` 用例。
- `InstanceLaunchSequenceTest` / `ControlChannelRecoveryTest` 回归。
- `./gradlew :app:testDebugUnitTest --tests '*PersistentTerminalSessionPoolTest' --tests '*InstanceLaunchSequenceTest' --tests '*ControlChannelRecoveryTest'`

## Comments

2026-11-03 协调：本轮做 tracker 对账，逐条核对代码后回填（来源 `.scratch/quality-round-20261103/notes/B5-tracker.md` §T5）。

2026-11-03 协调：交付项逐条核对（`app/src/main/java/io/github/nyzeep/orchestella/PersistentTerminalSessionPool.java`）。
- register 替换：`:397` `ownsStop = existing.tryBeginStop()` → `:413` `confirmed = stopOwnedRuntime(existing)` → `:425` `completeStop(...)`；停与发布在 `:444-464` 同一临界区。
- 未确认不发布：`:418-421` `if (!confirmed) { completeStop(..., false, null, null); throw new IOException("previous runtime session did not confirm stop"); }`；`completeStop` 的 `!confirmed` 分支 `:449-451` 只 `finishStop()` 并保留旧 holder。
- `unregister` / `unregister(id, expected)`：`:521-551`（`:544` `stopOwnedRuntime`、`:549` `completeStop`）、`:594-597` → `retire` `:616-645`。
- `clear()`：`:599-606`（先快照再逐条 `retire`，快照后新发布的 owner 不会被误杀）。
- `ExecutionSessionRegistry.register` `:65-74` 声明 `throws IOException`；调用方 `AvfInstanceLauncher.java:384-390`（`catch IOException` / `catch RuntimeException` → `cleanupFailedRegistration`）、`QemuInstanceLauncher.java:750`。
- 启动失败清理：`QemuInstanceLauncher.java:755-760` → `:779-790` 优先 `session.stopRuntime(2000L)`，只有 `session == null` 才回退 `stopProcessBounded` + `gateway.stop`。

2026-11-03 协调：测试映射。`PersistentTerminalSessionPoolTest` 覆盖 `:367`（替换时旧 stop 恰好一次）、`:382`（旧 stop 未确认 → 新 owner 不发布、pool 仍返回旧 owner）、`:400`/`:442`（unregister 先停资源再移除）。

2026-11-03 协调：**遗留一项，不阻塞本票**——验收第 4 条「启动失败路径不留下 gateway LIVE 条目」没有**直接**断言用例（最接近的是 `NetworkGatewayDaemonTest.java:145` 与 `:283`）。静态上 `LIVE.put` 只在成功路径 `NetworkGatewayDaemon.java:254`，该路径结构上不可达。若要补直接用例，需构造 `NetworkGatewayDaemon.start` 的失败注入点，属独立小票。

2026-11-03 协调：实跑 `./gradlew :app:testDebugUnitTest --offline`（`X:` ASCII 路径）→ `BUILD SUCCESSFUL`。真机停止路径为 `not-tested`。

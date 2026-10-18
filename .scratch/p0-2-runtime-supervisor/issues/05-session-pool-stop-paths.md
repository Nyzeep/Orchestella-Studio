# T5 Session 池与 UI 停止路径

Status: ready-for-agent
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

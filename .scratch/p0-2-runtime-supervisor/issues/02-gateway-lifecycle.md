# T2 gvproxy 生命周期

Status: ready-for-agent
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

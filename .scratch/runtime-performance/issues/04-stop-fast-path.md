# T4 停止路径快速收尾

Status: ready-for-agent
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

- [ ] 64 个 forward 的停止在硬窗口内返回，远程 API 超时不再累加进 grace。
- [ ] 网关确认退出后，原 forward 监听不再可达（真机或 fake gateway 验证）。
- [ ] 池操作不被停止阻塞；并发 register / stop 不会发布第二个 owner。
- [ ] supervisor 诊断 stage 在网关失败时为 `gateway`，有单测断言。
- [ ] 既有 `RuntimeProcessSupervisorTest` / `NetworkGatewayDaemonTest` / `PersistentTerminalSessionPoolTest` 全绿。

## 测试

- 扩展 `RuntimeProcessSupervisorTest`、`NetworkGatewayDaemonTest`、`PersistentTerminalSessionPoolTest`。
- `./gradlew :app:testDebugUnitTest --tests '*RuntimeProcessSupervisorTest' --tests '*NetworkGatewayDaemonTest' --tests '*PersistentTerminalSessionPoolTest'`

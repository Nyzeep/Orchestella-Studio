你是 Orchestella 的实施会话，只负责工单 **T4 停止路径快速收尾**。

## 工作区

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

- 从 dispatch 时的 `main` HEAD 切分支 `perf/t4-stop-fast-path`；独立 worktree + 空闲 ASCII 映射（先检查盘符）。

## 先读

1. `AGENTS.md`
2. `CONTEXT.md`（Runtime Process Supervisor / Stop Confirmation / STOP_UNCONFIRMED）
3. `.scratch/runtime-performance/spec.md`
4. `.scratch/runtime-performance/issues/04-stop-fast-path.md`
5. `.scratch/runtime-performance/parallelization.md`
6. `docs/adr/0020-runtime-process-ownership.md`
7. `docs/reports/orchestration/p0-2-runtime-supervisor-20261018.zh-CN.md`（风险 3）

## 目标

停止大量 service forward 时仍在有界窗口内完成：

- 关闭通道阶段不再对每个 forward 发远程 `unexpose`；forward 的释放以 gvproxy 进程退出为准。
- `closeChannels` 有硬 deadline；超时后继续停网关，并把结果反映到诊断。
- session 池的 `register` / `unregister` / `clear` 在锁外执行实际停止，锁内只做所有权摘除与 generation 校验。
- 修正 `RuntimeProcessSupervisor` 在“进程与网关都已死、通道关闭成功但网关清理失败”时把 stage 写成 `channels` 的问题。
- **不改变** `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED` 的对外语义；不新增第二个 stop owner。

## 边界

- 你拥有：`RuntimeProcessSupervisor`、`NetworkGatewayDaemon.Running`、`PersistentTerminalSessionPool` 的 register/unregister/clear。
- 只允许最小改动：`QemuInstanceLauncher.Session.closeChannels`。
- 不要改 `QemuRuntimeBootstrapper` / `QemuLaunchSpec` / provisioning 元数据。
- 不要用“不等待进程退出”来换速度；确认语义必须保留。

## TDD

1. 先写/扩展 supervisor 测试：64 个 forward 的关闭不触发远程 API 累加；硬 deadline 生效；诊断 stage 为 `gateway`。
2. 扩展 gateway 测试：确认退出后 forward 不再可达；重复 stop 幂等。
3. 扩展 pool 测试：停止期间 register/unregister 不被阻塞；并发下不发布第二个 owner；未确认时保留 holder。
4. 实现并跑目标测试 + 全量 app 测试。

## 命令

```bash
cd /x
./gradlew :app:testDebugUnitTest --tests '*RuntimeProcessSupervisorTest' --tests '*NetworkGatewayDaemonTest' --tests '*PersistentTerminalSessionPoolTest' --offline
./gradlew :app:testDebugUnitTest --offline
git diff --check
```

## 交付

- 提交到 `perf/t4-stop-fast-path`，不要合入 `main`。
- 在工单文件末尾追加 `## Comments`：提交 hash、测试结果、64 forward 的窗口数据（JVM/设备分开）、未测项、风险。
- 最终回复：关闭顺序、deadline 数值来源、池锁边界、未测项。

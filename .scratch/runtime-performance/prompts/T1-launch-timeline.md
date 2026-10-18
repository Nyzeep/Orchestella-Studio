你是 Orchestella 的实施会话，只负责工单 **T1 启动阶段时间线与度量旁路**。

## 工作区

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

- 先确认所用 ASCII 映射存在；**不要从中文/括号原路径跑 Gradle**，会导致测试 worker `ClassNotFoundException`。
- 从 dispatch 时的 `main` HEAD 切分支 `perf/t1-launch-timeline`；建议独立 worktree，并给它分配一个空闲 ASCII 映射（如 `X:`）。先检查盘符未被占用，不要覆盖其他会话的盘符映射。

## 先读

1. `AGENTS.md`
2. `CONTEXT.md`（Runtime Process Supervisor / Stop Confirmation）
3. `.scratch/runtime-performance/spec.md`
4. `.scratch/runtime-performance/issues/01-launch-timeline.md`
5. `.scratch/runtime-performance/parallelization.md`
6. `docs/adr/0020-runtime-process-ownership.md`

## 目标

为一次 launch 记录有序阶段并持久化：launch 开始、资产校验完成、initramfs 构建完成、gateway 就绪、QEMU 已启动、session 已注册、停止请求、停止确认。记录发行版、guest 内存/vCPU、各阶段耗时、停止结果；不含 token、口令、按键、用户文件内容或完整命令行。写入失败不得影响 launch。

## 边界

- 你拥有：新增的 timeline 类、存储、测试。
- 只允许最小 mark：`QemuInstanceLauncher.launch`、`QemuRuntimeBootstrapper.bootstrap`、`NetworkGatewayDaemon.start` 的就绪点。
- 不要改 T2/T3/T4 的业务逻辑；不要重排无关代码；不要格式化整文件。
- 不引入常驻线程/进程；不改变现有 stop 语义；不新增 secret。

## TDD

1. 先写 `LaunchTimelineTest`：fake 时钟、阶段顺序、重复 mark、未开始就 mark、写入失败、缺失阶段。
2. 实现纯 Java API 与持久化（一行结构化记录即可）。
3. 接最小 mark，确认阶段顺序在真实 launch 代码路径上成立。
4. 跑目标测试与全量 app 测试。

## 命令

```bash
cd /x   # 或你分配的空闲盘符
./gradlew :app:testDebugUnitTest --tests '*LaunchTimelineTest' --offline
./gradlew :app:testDebugUnitTest --offline
git diff --check
```

## 交付

- 提交到 `perf/t1-launch-timeline`，不要合入 `main`。
- 在工单文件末尾追加 `## Comments`：提交 hash、测试命令与结果、未测项、风险。
- 最终回复：改了什么、测试结果、未测项、给集成会话的注意事项（尤其阶段 API 的调用方式）。

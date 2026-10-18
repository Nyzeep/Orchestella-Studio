# Ticket 01 实施报告

- 票号/标题：runtime-performance-01 启动阶段时间线与度量旁路
- 执行 session / 模型 / thinking：并行 T1 ticket session / DeepSeek-V4.1-Flash / 未记录
- 分支：`perf/t1-launch-timeline`；基线 commit：`893fb72`；集成分支：`perf/runtime-performance`

## 1. 任务目标

为一次 launch 记录有序阶段、耗时与最终停止结果，持久化为一行结构化记录；写入失败不得影响 launch；记录器可在纯 JVM 中用 fake 时钟测试。

## 2. 实际完成

- ✅ 新增纯 JVM `LaunchTimeline`，注入 `java.time.Clock` 与 `LineSink`，一次 launch 只追加一行 UTF-8 JSONL 到 `<runtimeDirectory>/launch-timeline.jsonl`。
- ✅ 阶段覆盖 `LAUNCH_START → ASSETS_VERIFIED → INITRAMFS_BUILT → GATEWAY_READY → QEMU_STARTED → SESSION_REGISTERED → STOP_REQUESTED → STOP_CONFIRMED`，记录发行版 id、guest 内存/vCPU、阶段耗时、rootfs 输入字节数、`StopResult`。
- ✅ 重复 mark 幂等；`LAUNCH_START` 之前的 mark 被丢弃并写入诊断；缺失阶段安全渲染。
- ✅ 写入异常被吞掉并记录非致命诊断，`finish()` 幂等，launch 不受影响。
- ✅ `QemuRuntimeBootstrapper` 新增可选 timeline 重载；`QemuInstanceLauncher` 在 bootstrap 前创建 timeline，并在 gateway/QEMU/session 就绪点 mark；`Session.stopRuntime` 记录停止请求与结果。
- ✅ 集成审查后补齐：manifest 读取失败也写一行 `LAUNCH_START` 记录；新增 schema allowlist 测试，证明顶层字段只有 9 个非 secret 字段。
- ✅ 记录内容不含 bridge token、guest password、按键、用户文件或完整 QEMU 命令行。

## 3. 未完成 / 与预期偏差

- 真机/真实 QEMU+gvproxy 的 launch/stop 时序未验证，标为 `not-tested`。
- 记录在第一次 stop 结果时落盘；若首次 `UNCONFIRMED`、后续 retry `CONFIRMED`，JSONL 保留第一次结果，内存对象更新但不追加第二行。这是“一次 launch 一行”约束下的已知偏差。
- 跨进程并发追加同一 JSONL 未验证；当前一次 launch 使用独立 runtimeDirectory。
- 早期 `RuntimeArtifactManifest` 解析失败的记录已在集成审查后补齐；`LaunchTimeline.open` 自身失败不在本票覆盖范围。

## 4. 修改的模块

- `app/src/main/java/io/github/nyzeep/orchestella/LaunchTimeline.java`（新增）
- `app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java`（timeline 创建、阶段 mark、Session 生命周期）
- `app/src/main/java/io/github/nyzeep/orchestella/QemuRuntimeBootstrapper.java`（可选 timeline 重载与资产/initramfs mark）
- `app/src/test/java/io/github/nyzeep/orchestella/LaunchTimelineTest.java`（新增，8 个用例）
- `app/src/test/java/io/github/nyzeep/orchestella/QemuRuntimeBootstrapperTest.java`（阶段顺序断言）
- 未触碰 runtime 模块、supervisor、gateway、provisioning 逻辑。

## 5. 重要设计决策

- 用 `Clock` + `LineSink` 把时间与 I/O 从状态机中分离，测试不需要 Android 或真实磁盘。
- JSONL 顶层字段采用固定 allowlist（schema/instanceId/distroId/guestMemoryMb/vcpuCount/stages/rootfsBytes/stopResult/diagnostics），从结构上避免携带 launch 参数中的 secret。
- `finish()` 在第一次停止结果时写一行；重复 stop 不重复写。该取舍记录在票的 `## Comments`。
- 阶段 API 保持稳定，T3/T8 可直接调用，不需要改签名。

## 6. 测试

- `:app:testDebugUnitTest --tests '*LaunchTimelineTest'`：8/8 通过。
- `:app:testDebugUnitTest --tests '*LaunchTimelineTest' --tests '*QemuRuntimeBootstrapperTest'`：通过。
- 集成分支全量：`:runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check :app:testDebugUnitTest :app:lintDebug` 通过；app 812 tests / 0 failures / 0 skipped，contract 24，instance 15，engine-qemu 2，lint 0 Error。
- 真机、真实 Android runtimeDirectory、跨进程追加：`not-tested`。

## 7. Benchmark / 实测数据

本票只建立度量旁路，不声称启动收益；未做设备 benchmark。

## 8. 风险

- 首次 stop `UNCONFIRMED` 后重试为 `CONFIRMED` 时，文件保留第一次结果；若 T8 要求严格最终值，需要另行定义重写/补偿记录策略。
- 写入失败只在内存 `diagnostics()` 可见，尚未接入宿主日志或指标。
- 记录在 stop 时落盘；App 进程在 stop 前被杀时不会留下该次 launch 记录。

## 9. 新发现

- T3 跳过 rootfs 读取时应调用 `markInitramfsBuilt(0)`（或实际读取字节数），让 T8 能断言 0 字节契约。
- T8 可直接消费 `launch-timeline.jsonl` 的 `stages` 与 `rootfsBytes`，但需容忍缺失阶段和 `stopResult=null`。

## 10. 对后续 ticket 的影响

- T3/T8 使用稳定 API：`LaunchTimeline.open`、`mark`、`markInitramfsBuilt`、`markStopConfirmed`、`finish`。
- 集成分支已包含 T1；T3 可以从 `perf/runtime-performance` 切分支，不需要直接依赖 `perf/t1-launch-timeline`。

## 11. Commit

- `47fecc0` feat(runtime): 增加启动阶段时间线与 JSONL 记录
- `e035631` docs(runtime): 记录 T1 启动时间线实现与测试证据
- 集成合并：`c9af6bf` merge(runtime): 合入 T1 启动阶段时间线
- 审查修复：`904aa2f` fix(runtime): 收紧 T1/T2/T4 审查边界

## 12. Ticket 状态

建议 `completed`（JVM 验证完成）；真机与 stop retry 最终值仍为 `not-tested`，建议人工复核后进入 T8。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录

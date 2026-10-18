# T1 启动阶段时间线与度量旁路

Status: ready-for-human
Type: task
Priority: P0
Blocked by: 无

## 交付

为一次 launch 记录有序阶段，并持久化为一行结构化记录：

- launch 开始
- 运行时资产校验完成
- embedded initramfs 构建完成（含入参大小）
- gvproxy 就绪
- QEMU 已启动
- session 已注册
- 停止请求
- 停止确认（CONFIRMED / UNCONFIRMED / ALREADY_STOPPED）

记录包含发行版 id、guest 内存与 vCPU、各阶段耗时、最终停止结果；不包含 token、口令、按键、用户文件内容或完整命令行。写入失败不得影响 launch；记录器本身可在纯 JVM 中用 fake 时钟测试。

## 验收

- [x] 纯 JVM 测试注入 fake 时钟，断言阶段顺序、耗时计算与缺失阶段的处理。
- [x] 记录内容有测试证明不含 secret；写入异常被吞掉且 launch 继续。
- [x] 现有 app / engine-qemu 测试保持通过。
- [x] 阶段 API 可被后续票直接调用，不需要改签名。

## 测试

- 新增 `LaunchTimelineTest`，覆盖顺序、重复 mark、未开始就 mark、写入失败。
- `./gradlew :app:testDebugUnitTest --tests '*LaunchTimelineTest'`
- `./gradlew :app:testDebugUnitTest` 回归。

## Comments

- 实现提交：`47fecc0`（`perf/t1-launch-timeline`，基线 `893fb72`）
- 变更：
  - 新增 `app/src/main/java/io/github/nyzeep/orchestella/LaunchTimeline.java`：纯 JVM，可注入 `java.time.Clock` 与 `LineSink`；一次 launch 只写一行 UTF-8 JSONL 到 `<runtimeDirectory>/launch-timeline.jsonl`；重复 mark 幂等；`LAUNCH_START` 之前的 mark 被丢弃并记录诊断；写入异常被吞掉并记录非致命诊断，launch 继续。
  - `QemuInstanceLauncher.launch`：bootstrap 前创建 timeline；按 `rootfsFileName` 匹配 `DistroCatalogRegistry.createDefault().listAll()` 得到 distro id，未知或歧义回退为 `unknown`；记录 launch start、gateway ready、QEMU started、session registered；bootstrap / gateway / QEMU 失败路径也会 `finish()` 一行（未发生停止时 `stopResult` 为 null）。
  - `QemuRuntimeBootstrapper.bootstrap`：新增带 `LaunchTimeline` 的重载（所有旧签名保留）；manifest 校验并安装 kernel/base initramfs 后记 `ASSETS_VERIFIED`，原子 move 后记 `INITRAMFS_BUILT`，`rootfsBytes` 使用 manifest 校验过的输入大小。
  - `QemuInstanceLauncher.Session`：新增可选 timeline 构造重载，旧测试构造器不变；`stopRuntime` 在 supervisor 停止前记 `STOP_REQUESTED`、停止后记 `STOP_CONFIRMED` 并落盘，watcher / pool 重复 stop 不会产生重复阶段或第二行。
  - 记录字段：`schema`、`instanceId`、`distroId`、`guestMemoryMb`、`vcpuCount`、按固定顺序渲染的 `stages[{stage,elapsedMs}]`、`rootfsBytes`、`stopResult`、`diagnostics`；不接收 token、口令、按键、用户文件或完整 QEMU 命令行。

### 测试命令与结果

- `./gradlew :app:testDebugUnitTest --tests '*LaunchTimelineTest' -Porchestella.repositories.useAliyunMirror=true`：通过（7 个测试）。
- `./gradlew :app:testDebugUnitTest --tests '*LaunchTimelineTest' --tests '*QemuRuntimeBootstrapperTest' -Porchestella.repositories.useAliyunMirror=true`：通过（含新增 bootstrap 阶段顺序断言）。
- `./gradlew :app:testDebugUnitTest -Porchestella.repositories.useAliyunMirror=true`：通过；`app/build/test-results/testDebugUnitTest` 汇总 781 tests / 0 failures / 0 errors / 0 skipped。
- `./gradlew :runtime:engine-qemu:check -Porchestella.repositories.useAliyunMirror=true`：通过。
- `git diff --check`：通过。
- `--offline` 在本环境无法解析 `org.jetbrains.kotlin.android` 插件坐标，因此使用项目显式 opt-in 的 Aliyun 镜像；该失败与代码无关。

### 供 T3/T8 使用的稳定 API

- 构造：`new LaunchTimeline(instanceId, distroId, guestMemoryMb, vcpuCount, Clock, LineSink)`；生产入口：`LaunchTimeline.open(runtimeDirectory, instanceId, distroId, guestMemoryMb, vcpuCount)`。
- 阶段：`mark(Stage)`、`markInitramfsBuilt(long rootfsBytes)`、`markStopConfirmed(StopResult)`、`finish()`。
- `Stage`：`LAUNCH_START`、`ASSETS_VERIFIED`、`INITRAMFS_BUILT`、`GATEWAY_READY`、`QEMU_STARTED`、`SESSION_REGISTERED`、`STOP_REQUESTED`、`STOP_CONFIRMED`。
- `finish()` 幂等，只写一行；写入失败返回 `false` 且不抛出。

### 未测项

- 真机 launch / stop 未执行：本环境只跑 JVM 测试。`not-tested` 包括真实 gvproxy / QEMU 进程、真实 Android `runtimeDirectory` 写入与 API 26 设备行为。
- 未验证跨进程并发写同一 JSONL 的行为；当前一次 launch 使用独立 runtimeDirectory，单行追加无跨进程锁。
- 未验证 stop 首次 `UNCONFIRMED`、后续 retry `CONFIRMED` 时文件内的最终结果更新：按“一次 launch 一行”的约束，落盘发生在第一次 stop 结果，后续 retry 只更新内存对象，不追加第二行。

### 风险

- stop 首次 `UNCONFIRMED` 后重试为 `CONFIRMED` 时，JSONL 保留第一次结果；若 T4/T8 需要严格“最终”结果，需要明确是否允许重写或追加补偿记录。
- `STOP_REQUESTED` / `STOP_CONFIRMED` 也会在 launch 失败清理（Session 已构造）时记录；无 Session 的早期失败只记录已发生阶段，`stopResult` 为 null。
- 写入失败仅在内存 `diagnostics()` 中可见，未接入宿主日志或指标；现场排障如需可见性，后续可把诊断接到日志。
- 集成会话（2026-10-18）：已合入 `perf/runtime-performance`（`c9af6bf`），`904aa2f` 补齐 manifest 读取失败也写 `LAUNCH_START` 记录，并新增 schema allowlist 测试。全量验收集通过（app 812 / contract 24 / instance 15 / engine-qemu 2，lint 0 Error）；真机与 stop retry 最终值仍 `not-tested`。

# 编排报告：runtime-performance T1/T2/T4 并行实施（2026-10-18）

- 背景 / scope：按 `.scratch/runtime-performance/` 的并行协议，先完成第 1 波 T1（启动阶段时间线）、T2（已装配信号与实例元数据）、T4（停止路径快速收尾）；不启动 T3/T5/T6/T7/T8。
- branch / 基线 / commit：基线 `893fb72`；集成分支 `perf/runtime-performance`；HEAD `904aa2f`（报告提交后更新）；三张票分支分别为 `perf/t1-launch-timeline`、`perf/t2-provisioned-signal`、`perf/t4-stop-fast-path`。
- 工作区：主工作树 `/workspace` 保持 `main`；三张票分别在 `/tmp/orch-perf-t1`、`/tmp/orch-perf-t2`、`/tmp/orch-perf-t4` 的独立 worktree 实施；集成分支在 `/tmp/orch-perf-integration` 合并验证。未 push，未合并到 `main`。

## 实际完成

- T1：新增 `LaunchTimeline` + JSONL 持久化；在 bootstrap/gateway/QEMU/session/stop 路径打点；manifest 读取失败也留下 `LAUNCH_START` 记录；新增 schema allowlist 测试。
- T2：新增 `ProvisioningSignal` 流式检测器；`InstanceLifecycle` provisioning evidence 字段、round-trip、`isProvisioned`/`isProvisionedFor` 查询；`pumpLoop` listener 接线；QEMU launcher 注入 `FileStore` sink；不完整元数据保守回退。
- T4：closeChannels 跳过逐 forward 远程 unexpose；1000 ms 硬 deadline + daemon worker；修正 stage=gateway；池改为 per-instance transition lock；`releaseForStop` 不再提前谎报 inactive。
- 集成：按 T1 → T2 → T4 合并到 `perf/runtime-performance`，解决 `QemuInstanceLauncher` 与 `PersistentTerminalSessionPool(Test)` 的冲突，保留双方语义。

## 未完成内容

- T3/T5/T6/T7/T8 未启动。T3 现在具备启动条件（T1+T2 已合入集成分支）。
- 真机 / 真实 QEMU+gvproxy / Android listener 行为未测，全部标 `not-tested`。
- T4 严格“实际停止在锁外”未完全满足：停止在 per-instance transition lock 内执行，但不在池级 monitor 内；其他实例不被阻塞。该偏差已写入票 Comments 与 T4 报告。
- T1 首次 stop `UNCONFIRMED` 后 retry `CONFIRMED` 时，JSONL 仍保留第一次结果；未实现重写/补偿记录。

## 修改文件

- 生产：`LaunchTimeline.java`（新增）、`ProvisioningSignal.java`（新增）、`QemuInstanceLauncher.java`、`QemuRuntimeBootstrapper.java`、`RuntimeProcessSupervisor.java`、`PersistentTerminalSessionPool.java`、`ExecutionSessionRegistry.java`、`runtime/instance/.../InstanceLifecycle.java`。
- 测试：`LaunchTimelineTest`、`ProvisioningSignalTest`、`ProvisioningEvidenceRecorderTest`、`InstanceProvisioningEvidenceTest`、`RuntimeProcessSupervisorTest`、`NetworkGatewayDaemonTest`、`PersistentTerminalSessionPoolTest`、`QemuRuntimeBootstrapperTest`、`ControlChannelRecoveryTest`（test-only 锁范围）。
- 文档：三张票各自 `## Comments`、三份 implementation report、本 orchestration report。

## 模型、委派、workflow 或 worktree 影响

- 三个 ticket session 并行实施，各自独立 worktree 与分支；主会话负责冲突解决、集成测试和两轴审查。
- 环境：Temurin JDK 17、Android SDK Platform 36、Build Tools 36.1.0、NDK 27.0.12077973、Gradle 8.13；Maven Central 当前 429，使用项目显式 opt-in 的 Aliyun 镜像 `-Porchestella.repositories.useAliyunMirror=true`；`local.properties` 仅本地生成，未提交。
- 未新增常驻进程、Binder service、隐藏 API；未改 ADR-0020 的三态语义。

## 测试与验证结果

命令：

```bash
./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :app:testDebugUnitTest :app:lintDebug \
  -Porchestella.repositories.useAliyunMirror=true --no-daemon
git diff --check
```

结果：

| 任务 | 结果 |
| --- | --- |
| `:runtime:contract:check` | 通过，3 suites / 24 tests |
| `:runtime:instance:check` | 通过，JUnit 4 suites / 15 tests + 既有契约 JavaExec 任务 |
| `:runtime:engine-qemu:check` | 通过，1 suite / 2 tests + 既有 main-based 任务 |
| `:app:testDebugUnitTest` | 通过，103 suites / 812 tests / 0 failures / 0 errors / 0 skipped |
| `:app:lintDebug` | 通过，0 Error / 753 Warning |
| `git diff --check` | 通过 |
| 新增 `.log` 证据文件 | 无 |

两轴审查发现并已修复：
- T1 manifest 解析失败无记录 → 已把 timeline 创建提前并写 `LAUNCH_START`。
- T1 “no secret” 测试偏空 → 新增 JSONL 顶层字段 allowlist 测试。
- T2 markerVersion 存在但 timestamp 缺失被误判 → 改为不完整元数据按未装配读取，同时保留 malformed 值 fail-closed。
- T4 `releaseForStop` 提前标记 inactive → 改为只摘除跟踪，`isActive()` 以网关存活为准。

未修复但已记录：
- T4 per-instance lock 内停止的严格锁外偏差。
- T4 force-wait 路径网关仍存活时 listener 可能仍存在（`isActive()` 现在如实反映）。
- `InstanceLaunchSequence.afterLaunchFailure` 与池 per-instance lock 不互斥。
- T1 stop retry 最终值、真实设备验证。

## 风险与后续动作

1. 先由人工复核 `perf/runtime-performance` 的合并结果与三份报告；确认后再决定是否 push/PR。
2. T3 从 `perf/runtime-performance` 切分支，严格用 `instance.isProvisionedFor(currentManifestSha256)`；只有 true 才只嵌入凭据，并调用 `markInitramfsBuilt(0)`。
3. T8 等 T3 合入后运行，覆盖 0 字节 rootfs、initrd 上限、64 forward 硬窗口。
4. T5/T6 仅在 T3 度量证明首次启动/initrd 内存仍是主要成本时启动；T7 等 T1 且避免与 T5 同时改 `QemuLaunchSpec`。
5. 设备验证清单：真实 marker 端到端、真实 gvproxy listener 关闭、64 forward UI 窗口、1.5 s API 超时、Android API 26 写入行为。

## 越界与 Architecture Change Policy 检查

- 未改 ADR-0020 正文；未新增 stop owner、实例状态或暴露面。
- 未改 QEMU 默认参数、未改 Full Linux 契约、未改 `CONFIRMED`/`UNCONFIRMED`/`ALREADY_STOPPED` 语义。
- T2 给 `ExecutionSessionRegistry` 增加重载、T4 调整 `ControlChannelRecoveryTest` 锁范围属于测试/接线所需的小范围越界；已在对应票 Comments 与本报告记录。
- 未触发新的 Architecture Change Policy 流程；若要把 T4 严格改成锁外停止，需要单独设计并评审，不在本轮擅自扩大。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录

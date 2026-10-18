你是 Orchestella 的实施会话，只负责工单 **T2 已装配信号与实例元数据**。

## 工作区

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

- 先确认所用 ASCII 映射存在；**不要从中文/括号原路径跑 Gradle**。
- 从 dispatch 时的 `main` HEAD 切分支 `perf/t2-provisioned-signal`；建议独立 worktree + 空闲 ASCII 映射（先检查盘符）。

## 先读

1. `AGENTS.md`
2. `CONTEXT.md`
3. `.scratch/runtime-performance/spec.md`
4. `.scratch/runtime-performance/issues/02-provisioned-signal.md`
5. `.scratch/runtime-performance/parallelization.md`
6. `guest-assets/initramfs/init`（`DROIDLAYER_PROVISIONING_COMPLETE` 与 `provisioned-v1` 的写入顺序）
7. `guest-assets/README.md`（marker 契约）

## 目标

宿主在 terminal 输出流中识别 guest 的 `DROIDLAYER_PROVISIONING_COMPLETE version=1` 标记，并把“根盘已完成装配”的事实持久化到实例元数据（含发行版 sha256、marker 版本、记录时间）。提供一个只读查询：实例是否已装配、证据是否匹配当前根盘/发行版。

要求：

- marker 可能被任意 chunk 边界切开，仍要识别；marker 之外的相似文本、错误版本、大小写变体不识别。
- 没有 marker、boot 失败、stream 提前结束，都不写任何 provisioned 事实。
- 旧元数据缺字段视为未装配。
- **本票不改变 launch 行为**，只产出信号与查询。

## 边界

- 你拥有：新增的 provisioning signal 类/测试、`InstanceLifecycle` 的元数据字段与序列化、`PersistentTerminalSessionPool.pumpLoop` 的 marker 观察点。
- 不要改 `QemuRuntimeBootstrapper`、`QemuLaunchSpec`、supervisor、gateway 的停止逻辑。
- 不要根据根盘首字节、文件大小或实例状态猜测“已装配”；只认 marker。
- 日志/元数据不含 token、口令、按键。

## TDD

1. 先写 `ProvisioningSignalTest`：marker 跨 1..N 个 chunk 分割、错误版本、大小写变体、无 marker、异常结束。
2. 再写元数据 round-trip：旧实例缺字段 = 未装配；证据不匹配 = 未装配。
3. 实现并接入 `pumpLoop`，保证锁与现有 attach/detach 语义不变。
4. 跑目标测试与全量 app 测试。

## 命令

```bash
cd /x
./gradlew :app:testDebugUnitTest --tests '*ProvisioningSignalTest' --tests '*InstanceLifecycle*' --tests '*PersistentTerminalSessionPoolTest' --offline
./gradlew :app:testDebugUnitTest --offline
git diff --check
```

## 交付

- 提交到 `perf/t2-provisioned-signal`，不要合入 `main`。
- 在工单文件末尾追加 `## Comments`：提交 hash、测试结果、未测项、风险。
- 最终回复：信号如何被消费、元数据字段名、T3 应如何查询、未测项。

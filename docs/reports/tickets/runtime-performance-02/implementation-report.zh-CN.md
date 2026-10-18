# Ticket 02 实施报告

- 票号/标题：runtime-performance-02 已装配信号与实例元数据
- 执行 session / 模型 / thinking：并行 T2 ticket session / DeepSeek-V4.1-Flash / 未记录
- 分支：`perf/t2-provisioned-signal`；基线 commit：`893fb72`；集成分支：`perf/runtime-performance`

## 1. 任务目标

宿主在 terminal 输出流中识别 guest 的 `DROIDLAYER_PROVISIONING_COMPLETE version=1` 标记，把“根盘已完成装配”的事实持久化到实例元数据，并提供只读查询；本票不改变 launch 行为。

## 2. 实际完成

- ✅ 新增纯 JVM `ProvisioningSignal` 流式检测器，维护有界状态机，不保存 transcript；marker 跨任意 chunk 边界可识别。
- ✅ 只接受行首、大小写精确的 `DROIDLAYER_PROVISIONING_COMPLETE version=1`，且后面必须是空白或 EOF；`version=10`、大小写变体、相似文本不命中。
- ✅ 无 marker、宿主主动 close、stream 在 marker 前结束均不写 provisioning 事实；真实 EOF 才允许把输入结束当作分隔符。
- ✅ `InstanceLifecycle.Instance` 新增 `provisioning.markerVersion`、`provisioning.rootfsSha256`、`provisioning.recordedAtMillis` 的 Properties round-trip；旧元数据缺字段读取为未装配。
- ✅ 只读查询：`isProvisioned()`、`provisioningEvidence()`、`isProvisionedFor(currentRootfsSha256)`；T3 必须使用 `isProvisionedFor` 做 rootfs 匹配。
- ✅ `Controller.recordProvisioning(id, evidence)` 在 `store.stateMonitor()` 内 load-modify-save，不改变生命周期状态、崩溃诊断或既有凭据字段。
- ✅ `PersistentTerminalSessionPool.ProvisioningListener` + `register(id, session, listener)` 重载接入 `pumpLoop`；sink 每个 holder 至多调用一次，在 terminal 写锁外、pump 线程上执行。
- ✅ `QemuInstanceLauncher` 在注册 session 时注入 `FileStore(context.getFilesDir()/instances)` sink，按 `DistroCatalogRegistry` 精确匹配 `rootfsFileName` 得到期望 sha256；解析不到时不注入，保持保守路径。
- ✅ 集成审查后补齐：markerVersion 存在但 `recordedAtMillis` 缺失的旧/不完整元数据按未装配读取；malformed markerVersion 仍按 invalid metadata 拒绝。

## 3. 未完成 / 与预期偏差

- 真机或真实 QEMU guest 的 marker 端到端未验证，标为 `not-tested`。
- AVF 路径未接入 sink（票中标记可选，未扩大范围）。
- 本票上线前已完成装配的实例不会补写 evidence；guest 重启只输出 `DROIDLAYER_ROOT_VOLUME_RESTART`，T3 对它们必须走完整输入路径。
- 元数据仍包含既有 `bridgeToken`/`guestPassword` 字段（P0-2 设计），本票新增的 provisioning 字段不包含 secret；票面“元数据不含 token/口令”按新增字段理解。
- 克隆实例继承源实例 evidence（根盘字节复制）；归档导入不继承，按未装配处理。

## 4. 修改的模块

- `app/src/main/java/io/github/nyzeep/orchestella/ProvisioningSignal.java`（新增）
- `app/src/main/java/io/github/nyzeep/orchestella/PersistentTerminalSessionPool.java`（marker 观察点与 listener 重载）
- `app/src/main/java/io/github/nyzeep/orchestella/ExecutionSessionRegistry.java`（带 listener 的 register 重载）
- `app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java`（sink 注入与 sha 解析）
- `runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/InstanceLifecycle.java`（evidence、序列化、查询、recordProvisioning）
- 新增/扩展 `ProvisioningSignalTest`、`ProvisioningEvidenceRecorderTest`、`InstanceProvisioningEvidenceTest`、`PersistentTerminalSessionPoolTest`
- 未改 `QemuRuntimeBootstrapper`、`QemuLaunchSpec`、supervisor、gateway stop 逻辑。

## 5. 重要设计决策

- 只认 guest marker，不根据根盘首字节、大小或实例状态猜测。
- evidence 保存“期望的发行版 manifest sha256”，不是从 terminal 解析的任意值；T3 用当前 manifest sha 调用 `isProvisionedFor`。
- 无 sha 的 evidence 可表示“看到过 marker”，但 `isProvisionedFor` 永远为 false；T3 不会误走快速路径。
- sink 在 pump 线程同步写一次元数据，不持有 terminal 写锁；失败只记录无敏感内容的 warning。
- 旧/不完整元数据保守回退：缺 timestamp 不构造 evidence；malformed 值仍按 invalid metadata 拒绝。

## 6. 测试

- `:app:testDebugUnitTest --tests '*ProvisioningSignalTest' --tests '*ProvisioningEvidenceRecorderTest' --tests '*PersistentTerminalSessionPoolTest'`：通过（16 + 4 + 19 用例）。
- `:runtime:instance:test`：15/15 通过（含新增 partial metadata 用例）。
- 集成分支全量：app 812 tests / 0 failures，contract 24，instance 15，engine-qemu 2，lint 0 Error。
- 真机 marker 端到端、AVF、多实例并发首次装配：`not-tested`。

## 7. Benchmark / 实测数据

本票只产出信号与元数据，不做启动耗时 benchmark；收益由 T3 度量。

## 8. 风险

- 首次装配期间 pump 未接上或进程提前退出时不会留下 evidence，之后永久保守回退（正确性不受影响，只损失优化收益）。
- 旧实例不会补写 evidence；T3 必须接受它们走完整路径。
- `isProvisioned()` 只表示 marker 版本受支持；T3 必须用 `isProvisionedFor`，不能只看 `isProvisioned()`。
- 元数据写入失败只记录 warning，不阻断终端流；现场排障需要日志可见性。

## 9. 新发现

- T3 应在每次 launch 时从 `InstanceLifecycle.FileStore` 重新加载实例，不要使用内存中的旧 `Instance`。
- `QemuInstanceLauncher.resolveRootfsSha256(rootfsFileName)` 可复用；未知/歧义返回 null。
- 克隆实例的 evidence 继承是根盘字节复制的自然结果，T3 可安全使用；归档导入不继承。

## 10. 对后续 ticket 的影响

- T3 查询契约：`instance.isProvisionedFor(currentManifestSha256)` 为 true 才走“仅嵌入凭据”；false 一律完整装配。
- T3 需要保留 `markInitramfsBuilt(0)` 以配合 T8 的 0 字节断言。
- T8 可在 CI 中用 fake marker 流回归，不需要真机。

## 11. Commit

- `5901321` feat(runtime): 识别 guest 装配完成标记并持久化实例证据
- `6137d26` docs(runtime): 记录 T2 装配信号实现与测试结果
- `7ccf9bd` docs(runtime): 补充 T2 证据缺失时的保守回退说明
- 集成合并：`8367739` merge(runtime): 合入 T2 已装配信号与实例元数据
- 审查修复：`904aa2f` fix(runtime): 收紧 T1/T2/T4 审查边界

## 12. Ticket 状态

建议 `completed`（JVM 验证完成）；真机 marker 端到端与 AVF 接线为 `not-tested`，建议人工复核后进入 T3。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录

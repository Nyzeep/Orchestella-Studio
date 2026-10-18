# T2 已装配信号与实例元数据

Status: ready-for-agent
Type: task
Priority: P0
Blocked by: 无

## 交付

宿主在 terminal 输出流中识别 guest 的 `DROIDLAYER_PROVISIONING_COMPLETE version=1` 标记，并把“该实例根盘已完成装配”的事实持久化到实例元数据（含发行版 sha256、marker 版本、记录时间）。提供只读查询：给定实例，返回是否已装配以及证据是否匹配当前根盘/发行版。

要求：

- marker 可能被任意 chunk 边界切开，仍需识别；
- marker 之外的相似文本、错误版本、大小写变体不识别；
- 没有 marker、boot 失败、stream 提前结束，都不写任何 provisioned 事实；
- 旧实例元数据缺该字段时视为未装配；
- 本票不改变 launch 行为，只产出可信信号与查询。

## 验收

- [ ] 模拟 terminal 流：marker 跨 1..N 个 chunk 分割都能命中；拼写/版本不符不命中。
- [ ] 无 marker 或异常结束时元数据保持未装配。
- [ ] 元数据 round-trip；缺失字段按未装配读取。
- [ ] 日志与元数据不含 token、口令或按键内容。
- [ ] `PersistentTerminalSessionPool` 的既有重绑/关闭测试不回退。

## 测试

- 新增 `ProvisioningSignalTest` 与元数据 round-trip 用例。
- `./gradlew :app:testDebugUnitTest --tests '*ProvisioningSignalTest' --tests '*InstanceLifecycle*' --tests '*PersistentTerminalSessionPoolTest'`

## Comments

- 提交：`5901321`（`perf/t2-provisioned-signal`，基于 `893fb72`）。
- 元数据字段：`provisioning.markerVersion`、`provisioning.rootfsSha256`（sha 无法解析时不写入）、`provisioning.recordedAtMillis`。
- 只读查询：`Instance.isProvisioned()`、`Instance.provisioningEvidence()`、`Instance.isProvisionedFor(String currentRootfsSha256)`。旧元数据缺字段、marker 版本不为 1、sha 缺失或不一致时均返回未装配。
- 记录路径：`InstanceLifecycle.Controller.recordProvisioning(id, evidence)`，在 `store.stateMonitor()` 内 load-modify-save，不改变状态、崩溃诊断或凭据。
- 接线：`PersistentTerminalSessionPool` 新增 `ProvisioningListener` 及 register / `SessionHolder` 重载，旧构造与 register 方法保持可用；`QemuInstanceLauncher` 在注册会话时注入基于 `context.getFilesDir()/instances` 的 `FileStore` sink，按 `DistroCatalogRegistry.createDefault().listAll()` 精确匹配 `parameters.rootfsFileName`。匹配不到 sha 时不注入 sink（保守，不落任何证据）。
- T3 使用方式：以当前发行版 sha 调用 `instance.isProvisionedFor(sha)`；仅当返回 true 时走“只嵌入凭据”的重复启动路径，否则（含旧元数据、版本不符、sha 不一致或缺失）走完整装配路径。

测试结果（全部通过，使用 `-Porchestella.repositories.useAliyunMirror=true`）：

- `./gradlew :app:testDebugUnitTest --tests '*ProvisioningSignalTest' --tests '*ProvisioningEvidenceRecorderTest' --tests '*PersistentTerminalSessionPoolTest'` — 16 + 4 + 19 个用例，0 失败。
- `./gradlew :runtime:instance:test` — 14 个 JUnit 用例，0 失败（含 `InstanceProvisioningEvidenceTest` 8 个）。
- `./gradlew :runtime:instance:check` — 既有 normal/recovery/storage/clone/delete/power/snapshot 契约测试全部通过。
- `./gradlew :app:testDebugUnitTest` — 796 个用例，0 失败。
- `git diff --check` — 通过。

not-tested：

- 未在真机或真实 QEMU guest 上验证 marker 能沿 terminal 通道到达宿主；当前结论仅来自 JVM 字节流 fake 与单元测试。
- 未运行 `:app:lintDebug`、`:runtime:engine-qemu:check` 与设备 smoke。
- AVF 路径未接入 sink（工单标记为可选，未扩大范围）。
- 未做多实例同时首次装配的并发设备验证；每实例独立元数据文件，风险较低。

风险 / 说明：

- sink 在 pump 线程同步写一次元数据，不持有 terminal 写锁；元数据写失败仅记录无敏感内容的 warning，不会中断终端流。
- 只有真实 EOF（read 返回 -1）才把输入结束当作 marker 分隔符；宿主主动 close 不写入证据。
- 克隆实例继承源实例的 provisioning 证据（根盘为字节复制）；从归档导入不继承，按未装配处理。
- 若目录中同一 `rootfsFileName` 对应不同 sha，解析为 null 并按保守策略跳过记录。
- 本票上线前已完成装配的实例不会补写证据：guest 重启只输出 `DROIDLAYER_ROOT_VOLUME_RESTART`，不再输出 provisioning marker。T3 必须把它们当未装配、走完整输入路径。
- 若首次装配期间 terminal pump 未接上或进程提前退出，也不会留下证据；后续启动同样保持保守路径（正确性不受影响，只损失优化收益）。


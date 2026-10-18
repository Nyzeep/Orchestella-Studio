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

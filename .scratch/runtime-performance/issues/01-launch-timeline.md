# T1 启动阶段时间线与度量旁路

Status: ready-for-agent
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

- [ ] 纯 JVM 测试注入 fake 时钟，断言阶段顺序、耗时计算与缺失阶段的处理。
- [ ] 记录内容有测试证明不含 secret；写入异常被吞掉且 launch 继续。
- [ ] 现有 app / engine-qemu 测试保持通过。
- [ ] 阶段 API 可被后续票直接调用，不需要改签名。

## 测试

- 新增 `LaunchTimelineTest`，覆盖顺序、重复 mark、未开始就 mark、写入失败。
- `./gradlew :app:testDebugUnitTest --tests '*LaunchTimelineTest'`
- `./gradlew :app:testDebugUnitTest` 回归。

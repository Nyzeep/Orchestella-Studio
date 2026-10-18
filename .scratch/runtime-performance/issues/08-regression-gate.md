# T8 启动性能回归门

Status: ready-for-agent
Type: task
Priority: P1
Blocked by: T3

## 交付

一个不依赖设备的 JVM 回归门：

- 已装配实例的启动路径不得打开或读取 distro rootfs 输入流；
- 凭据-only initrd 大小不得超过固定上限；
- 测试在 T3 被回退或有人重新引入全量嵌入时变红；
- 接入 CI 的 PR 门（至少 app unit test；若已有 runtime 检查任务，一并接入）。

## 验收

- [ ] 人为把 T3 的 provisioned 分支改回全量嵌入，测试变红。
- [ ] 测试不需要网络、设备、真实 QEMU 或 gvproxy。
- [ ] CI 在 PR 上执行该测试；失败会阻塞合并。
- [ ] 不新增 secret、不输出 rootfs 内容。

## 测试

- 新增回归测试（计数输入流 + 大小断言）。
- `./gradlew :app:testDebugUnitTest --tests '*ProvisionedLaunchRegressionTest'`
- CI workflow 检查。

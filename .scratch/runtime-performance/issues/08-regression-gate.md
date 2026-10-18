# T8 启动性能回归门

Status: ready-for-human
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

## Comments

- 实施 commit：`32784cc`（`perf/t8-regression-gate`，基线 `7e081a8`，含 T3）。
- 回归门：新增 `ProvisionedLaunchRegressionTest`（2 tests）。已装配路径断言 opener 0 次、读取 0 B、无 rootfs entry、segment ≤ 64 KiB、timeline `rootfsBytes:0`；同一 256 KiB 不可压缩 fixture 的全量路径断言打开 1 次、含 rootfs entry、segment > 64 KiB。
- 红/绿：临时把 `credentialsOnly` 强制为 false 后测试红（`expected:<0> but was:<1>`）；恢复后 2 tests / 0 failures。证据：`docs/reports/tickets/runtime-performance-08/red-green-evidence.md`。
- CI：`.github/workflows/ci.yml` 新增 `Run provisioned launch regression gate`；全量步骤扩展为 `:runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check :app:testDebugUnitTest`。PR 失败会阻塞合并。
- 测试：`./gradlew :app:testDebugUnitTest` 105 suites / 822 tests / 0 failures / 0 skipped；`git diff --check` 通过。
- 未测：GitHub Actions 实际运行（未 push / 未开 PR）、真实 QEMU/gvproxy、guest marker 端到端、真实设备。
- 覆盖边界：只覆盖 launch 的零 rootfs 契约与 initrd 上限，不覆盖停止路径或 guest 消费。
- 生产代码：未修改；红跑临时改动已恢复。

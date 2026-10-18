# T3 重复启动只嵌入凭据

Status: ready-for-human
Type: task
Priority: P0
Blocked by: T1、T2

## 交付

已装配实例的启动路径不再读取 distro rootfs：

- `provisioned=true` 时，embedded-input 只包含目录、`bridge.token`、guest 凭据、hostname、镜像源配置与 first-boot 脚本等小文件，不包含 rootfs tarball；
- `provisioned=false`、标志缺失、证据不匹配时，保持今天的行为（全量嵌入）；
- 首次装配行为、guest init 的 marker 与校验逻辑都不变；
- 启动阶段记录中能看到本次是否走了凭据-only 路径，以及 initramfs 大小。

## 验收

- [ ] 计数输入流证明 `provisioned=true` 时 rootfs 读取字节为 0，且没有打开 rootfs 文件。
- [ ] `provisioned=false` 时与当前实现逐字节等价（同一输入生成同一 cpio 内容）。
- [ ] 凭据-only initrd 有硬上限断言（测试中给出上限常量）。
- [ ] marker 缺失/不匹配时自动回退全量嵌入，并有测试。
- [ ] 至少 Ubuntu 与 Arch 的真机重复启动记录；未测发行版写 `not-tested`。
- [ ] `:runtime:engine-qemu:check` 与 `:app:testDebugUnitTest` 通过。

## 测试

- 新增 `QemuRuntimeBootstrapperProvisionedTest`（计数流 + 大小断言）。
- `./gradlew :runtime:engine-qemu:check :app:testDebugUnitTest`
- 真机：同一实例连续启动，记录宿主准备耗时、initrd 大小、guest 到达提示符。

## Comments

- 实施 commit：`f396de3`（`perf/t3-credentials-only`，基线 `bd24d24`）。
- 入口：`QemuInstanceLauncher.credentialsOnlyLaunch(...)` 每次 launch 从 `InstanceLifecycle.FileStore(context.getFilesDir()/instances)` 重新加载实例，按 `rootfsFileName` 解析 catalog sha，仅 `isProvisionedFor(sha)==true` 时传入 `credentialsOnly=true`。
- 回退：metadata 缺失/损坏、marker 版本不符、sha 不匹配、rootfs 文件名未知/歧义、读取异常，全部走全量路径。
- 测试：新增 `QemuRuntimeBootstrapperProvisionedTest` 8 tests / 0 failures；`:runtime:engine-qemu:check` 通过；`:app:testDebugUnitTest` 104 suites / 820 tests / 0 failures / 0 skipped。
- 证据：注入 opener 断言 credentials-only 打开 rootfs 0 次、读取 0 B；timeline `rootfsBytes=0`；全量分支与 `EmbeddedProvisionInput.writeSegment` 重建结果逐字节相同；凭据-only segment 523 B（合成 4 KiB rootfs），硬上限 64 KiB。
- 设备：Ubuntu/Arch 重复启动、真实 initrd 内存与耗时 `not-tested`（本会话无 adb/设备）。
- 风险：宿主 evidence 与根盘实际状态不一致时 guest 会 fail closed（不写根盘）；旧实例无 evidence 仍走全量路径。
- T8 可复用：`RootfsStreamOpener`（package-private）、`CREDENTIALS_ONLY_MAX_BYTES`、`rootfsBytes=0` 契约。

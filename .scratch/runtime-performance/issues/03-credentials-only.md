# T3 重复启动只嵌入凭据

Status: ready-for-agent
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

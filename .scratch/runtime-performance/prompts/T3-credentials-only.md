你是 Orchestella 的实施会话，只负责工单 **T3 重复启动只嵌入凭据**。

## 前置

- 阻塞于 **T1 与 T2**。先确认这两张票已经合入你的基线；没有就等待/先做别的，不要自己重写 timeline 或 marker 识别。
- 基线：`perf/runtime-performance` 集成分支（包含 T1、T2）。

## 工作区

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

- 分支 `perf/t3-credentials-only`；独立 worktree + 空闲 ASCII 映射（先检查盘符）。

## 先读

1. `AGENTS.md`
2. `CONTEXT.md`
3. `.scratch/runtime-performance/spec.md`
4. `.scratch/runtime-performance/issues/03-credentials-only.md`
5. `.scratch/runtime-performance/parallelization.md`
6. `app/src/main/java/io/github/nyzeep/orchestella/QemuRuntimeBootstrapper.java`
7. `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/EmbeddedProvisionInput.java`
8. `guest-assets/initramfs/init` 的 `provisioned-v1` 分支（已装配时只需要凭据）

## 目标

已装配实例的启动路径不再读取 distro rootfs：

- `provisioned=true` 时，embedded-input 只包含目录、`bridge.token`、guest 凭据、hostname、镜像源配置、first-boot 脚本等小文件；
- `provisioned=false`、标志缺失、证据不匹配时，保持当前全量嵌入；
- 首次装配行为、guest marker 与校验逻辑完全不变；
- T1 的 timeline 中能看到本次是否走了凭据-only 路径与 initrd 大小。

## 边界

- 你拥有：`QemuRuntimeBootstrapper`、`EmbeddedProvisionInput`、对应测试；`QemuInstanceLauncher` 中 `buildWithPasst` 调用点的最小改动。
- 不要改 T2 的 marker 识别/元数据；不要改 supervisor/gateway；不要改 QEMU 参数。
- 不要为了“省事”直接删掉 rootfs 嵌入分支；必须保留全量路径。
- 不记录 token/口令/按键。

## TDD

1. 先写计数输入流测试：`provisioned=true` 时 rootfs 读取字节为 0、未打开 rootfs 文件。
2. 写等价测试：`provisioned=false` 时生成内容与当前实现一致（同一输入逐字节比较）。
3. 写 initrd 大小上限断言与回退测试（marker 缺失/不匹配）。
4. 实现最小改动，跑 `:runtime:engine-qemu:check` 与全量 app 测试。
5. 有设备时：Ubuntu 与 Arch 各做重复启动，记录宿主准备耗时、initrd 大小、guest 到达提示符；没有设备就把真机项写 `not-tested`。

## 命令

```bash
cd /x
./gradlew :runtime:engine-qemu:check :app:testDebugUnitTest --offline
git diff --check
```

## 交付

- 提交到 `perf/t3-credentials-only`，不要合入 `main`。
- 在工单文件末尾追加 `## Comments`：提交 hash、测试结果、设备结果/未测项、initrd 前后大小、风险。
- 最终回复：provisioned 判断的入口、回退条件、T8 可复用的断言、未测项。

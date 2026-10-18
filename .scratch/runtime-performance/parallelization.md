# 并行开发协议（runtime-performance）

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

## 基线

- 基线：dispatch 时的 `main` HEAD（本文件写作时为 `44f5b23`）。所有分支从同一基线切出。
- 不使用中文/括号原路径跑 Gradle；每个会话先确认自己的 ASCII 映射空闲，再建立自己的映射，不覆盖其他会话的盘符映射。

## 分支与 worktree

- 一票一分支：`perf/t1-launch-timeline` … `perf/t8-regression-gate`。
- 一票一 worktree（建议），放在 ASCII 路径下，例如 `D:/orch-perf-t1`。
- 不要直接提交到 `main`；不要 force-push；不要删除其他会话的 worktree。
- 集成分支：`perf/runtime-performance`，从同一基线切出，按下面的波次合并。

## 波次与阻塞边

| 波次 | 可并行会话 | 说明 |
|---|---|---|
| 1 | T1、T2、T4 | 三者互不阻塞；T1 先合入集成分支，方便后续票使用阶段 API |
| 2 | T3、T8 | T3 阻塞于 T1+T2；T8 阻塞于 T3（可与 T3 同会话串行，或等 T3 合入后开新会话） |
| 3 | T5 | 条件票；只有 T3 的度量证明仍需处理首次启动/initrd 内存时才执行 |
| 4 | T6 | 阻塞于 T5 |
| 任意波次 | T7 | 阻塞于 T1；若 T5 已改 `QemuLaunchSpec`，等 T5 合入后再动同一文件 |

T5/T6/T7 是条件票：没有设备数据时不阻塞主线，也不要求为了“完成工单”而硬做。

## 文件所有权

| 票 | 主要拥有 | 只允许最小改动 |
|---|---|---|
| T1 | 新增 `LaunchTimeline` / 存储 / 测试 | `QemuInstanceLauncher.launch` 的阶段 mark；`QemuRuntimeBootstrapper` 的构建 mark；`NetworkGatewayDaemon.start` 的就绪 mark |
| T2 | 新增 provisioning signal / 元数据字段 / 测试 | `PersistentTerminalSessionPool.pumpLoop` 的 marker 观察；`InstanceLifecycle` 的序列化 |
| T3 | `QemuRuntimeBootstrapper`、`EmbeddedProvisionInput`、测试 | `QemuInstanceLauncher` 的 `buildWithPasst` 调用点 |
| T4 | `RuntimeProcessSupervisor`、`NetworkGatewayDaemon.Running`、`PersistentTerminalSessionPool` 的 register/unregister/clear | `QemuInstanceLauncher.Session.closeChannels` |
| T5 | `QemuLaunchSpec` 的输入盘参数、测试 | `QemuInstanceLauncher` 的参数传入 |
| T6 | `guest-assets/initramfs/*`、guest smoke | 不改宿主 Java |
| T7 | 新增 tuning 值对象、矩阵脚本、报告 | `QemuLaunchSpec` 的参数消费点 |
| T8 | 新增回归测试、`.github/workflows/ci.yml` | 不改生产代码 |

## 冲突热点

- `QemuInstanceLauncher.java`：T1、T3、T4、T5 都会碰。只改自己负责的那一小段，不重排无关代码，不格式化整文件。
- `QemuRuntimeBootstrapper.java`：T1 → T3 → T5 串行；不要并行改。
- `PersistentTerminalSessionPool.java`：T2 改 `pumpLoop`，T4 改 `register/unregister/clear`；保持方法级隔离。
- `QemuLaunchSpec.java`：T5 与 T7；T7 等 T5 合入，或只新增值对象、最后再接参数。
- 每次提交前 `git rebase perf/runtime-performance`；冲突时保留双方语义，不吞掉对方的测试。

## 测试与证据

- 每个会话先跑目标测试，再跑全量 app 测试；引擎侧跑 `:runtime:engine-qemu:check`。
- 命令统一从 ASCII 映射执行；先加 `--offline`，只有缺少合法固定坐标时才用镜像。
- 证据用 `.md/.json/.tsv/.log.txt`，不新建 `.log`。
- 真机结果与 JVM 结果分开写；未跑项写 `not-tested`，不把 fake 通过写成设备结论。
- 不输出 token、口令、按键、用户文件内容。

## 集成分支验收

T1+T2+T4 合入后：
- `:runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check :app:testDebugUnitTest :app:lintDebug`
- `git diff --check`

T3+T8 合入后再跑同一组，并额外确认：
- 已装配重复启动的 rootfs 读取为 0；
- initrd 大小上限；
- 停止 64 forward 的硬窗口。

全部通过后，才考虑把 `perf/runtime-performance` 作为整体交付；不逐票直接进 `main`。

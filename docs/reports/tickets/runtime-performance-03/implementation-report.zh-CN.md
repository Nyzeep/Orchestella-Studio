# Ticket 03 实施报告

- 票号/标题：runtime-performance-03 重复启动只嵌入凭据
- 执行 session / 模型 / thinking：续跑 T3 ticket session / DeepSeek-V4.1-Flash / 未记录
- 分支：`perf/t3-credentials-only`　基线 commit：`bd24d24`（集成分支，含 T1/T2/T4）

## 0. 背景 / scope / 边界检查

- 背景 / scope：T3 是 runtime-performance 的 P0 快速路径；在 T1/T2 已提供 timeline 与持久化 provisioning evidence 后，消除已装配实例重复启动的 rootfs 读取。
- 越界检查：只改 launch 输入准备（bootstrapper、EmbeddedProvisionInput、launcher 决策）与对应测试；未改 guest init、supervisor、gateway、QEMU 参数、ADR 或 `main`。
- Architecture Change Policy：未触发；不新增 stop owner、实例状态、暴露面或 Full Linux 契约字段。

## 1. 任务目标

已装配实例的重复启动不再读取 distro rootfs：`provisioned=true` 时 embedded-input 只包含目录、凭据、身份、镜像源与 first-boot 等小文件；证据缺失、版本不符或 sha 不匹配时保持今天的全量嵌入。

## 2. 实际完成

- ✅ 启动时从 `InstanceLifecycle.FileStore(context.getFilesDir()/instances)` 重新加载实例，不使用内存旧快照；用 `DistroCatalogRegistry` 按 `rootfsFileName` 精确解析当前 manifest sha，再调用 `instance.isProvisionedFor(sha)`。
- ✅ `provisioned=true` 时 `QemuRuntimeBootstrapper` 不执行 rootfs 的 stat/size/open，也不创建 rootfs 输入流；测试用注入 opener 断言打开次数为 0、读取字节为 0。
- ✅ `EmbeddedProvisionInput.writeCredentialsOnlySegment(...)` 只写 `opt/droidlayer/embedded-input/` 下的小文件：`.active`、`rootfs.file/sha256/size` 信任元数据、`bridge.token`、guest 凭据、hostname、镜像源、first-boot、guest init 与 proxy relay；不写 rootfs tarball。
- ✅ 全量分支保留，输出与改动前逐字节等价；测试用同一 `EmbeddedProvisionInput.writeSegment` 参数重建期望 segment，与 bootstrapper 产物逐字节比较通过。
- ✅ `provisioned=false`、metadata 缺失/损坏、marker 版本不符、sha 不匹配、rootfs 文件名未知：全部走全量路径。
- ✅ 凭据-only segment 有 256 KiB 硬上限，并在 `Files.move` 发布前检查；超限时删除 `.partial` 并抛 `credentials-only-initramfs-too-large`，不留下可用产物。
- ✅ timeline 调用 `markInitramfsBuilt(0, initramfsBytes)`（新增重载），JSONL 中 `rootfsBytes=0`、`credentialsOnly=true`、`initramfsBytes` 非空；原有 `markInitramfsBuilt(long)` 签名保留，未重写 T1 逻辑。
- ✅ 日志/时间线/报告不包含 token、口令、按键或 rootfs 内容。
- ⚠️ Ubuntu/Arch 真机重复启动：无设备（`adb` 不可用），`not-tested`。

## 3. 未完成 / 与预期偏差

- 真机重复启动、真实 guest marker 端到端、真实 initrd 载入内存与启动耗时：`not-tested`。本会话没有 Android 设备或 `adb`。
- timeline 的 schema 增加 `initramfsBytes` 与派生 `credentialsOnly` 字段，原有 `markInitramfsBuilt(long)` API 保留；这是为满足 T3 可观测性验收的最小增量，不是重写 T1。
- 旧实例（T2 上线前已完成装配）没有 evidence，仍走全量路径；这是 T2 已记录的保守回退。

## 4. 修改的模块

- `app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java`（重新加载实例、解析 sha、传入 credentialsOnly）
- `app/src/main/java/io/github/nyzeep/orchestella/QemuRuntimeBootstrapper.java`（credentialsOnly 重载、跳过 rootfs 打开、大小守卫、timeline 0 字节）
- `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/EmbeddedProvisionInput.java`（凭据-only segment、共享 header/tail、256 KiB 常量）
- `app/src/test/java/io/github/nyzeep/orchestella/QemuRuntimeBootstrapperProvisionedTest.java`（新增，8 个用例）
- 未改 `ProvisioningSignal`、`InstanceLifecycle`、supervisor、gateway、QEMU 参数或 guest init。

## 5. 重要设计决策

- **证据只认持久化记录**：每次 launch 重新 `FileStore.load`，避免调用方持有旧 `Instance` 导致误走快速路径。
- **双重匹配**：marker 版本受支持 + evidence sha 等于当前 catalog sha；未知/歧义文件名直接 false。
- **失败即全量**：所有读取/解析异常被吞掉并返回 false，不把“无法确认”当成“已装配”。
- **凭据-only 是独立构造器**：`writeCredentialsOnlySegment` 与全量 `writeSegment` 共享 header/tail 顺序，但不写 rootfs entry；全量路径保持逐字节稳定。
- **硬上限在发布前守卫**：segment 超过 256 KiB 时删除 `.partial` 并失败，防止未来有人把 rootfs 重新塞回快速路径。

## 6. 测试

- 新增 `QemuRuntimeBootstrapperProvisionedTest`：8 tests / 0 failures。
  - credentials-only 打开 rootfs 次数 = 0、读取字节 = 0、timeline `rootfsBytes=0` / `credentialsOnly=true` / `initramfsBytes` 非空；
  - credentials-only 无 rootfs entry、小文件齐全、segment ≤ 256 KiB；
  - 删除 rootfs 文件后 credentials-only 仍成功；
  - 全量路径读取字节 = tarball 长度，产物与 `writeSegment` 重建结果逐字节相同，并有黄金 SHA-256 `4cbca127...b75c7c` 防止共享 writer 重构漂移；
  - 全量路径缺失 rootfs 仍报 `rootfs-file-missing`；
  - 超限 segment 被拒绝且不发布；
  - evidence 缺失/版本不符/sha 不符/无 digest 均回退；
  - 未知 rootfs 文件名不能解锁快速路径。
- 目标测试：`./gradlew :runtime:engine-qemu:check :app:testDebugUnitTest -Porchestella.repositories.useAliyunMirror=true --no-daemon` 通过。
- app unit test：104 suites / 820 tests / 0 failures / 0 errors / 0 skipped（基线 812 + 新增 8）。
- engine-qemu check：既有 main-based 任务全部通过。
- `git diff --check`：通过。

## 7. Benchmark / 实测数据

| 环境 | credentials-only segment | 全量 segment | 说明 |
| --- | --- | --- | --- |
| JVM 合成 fixture（4 KiB rootfs） | 523 B | 903 B | 仅证明无 rootfs payload；不是发行版实测 |
| 真实资源（guest_init + proxy_relay） | 约 59 KB | 未测 | 真实资源下的凭据-only segment；占 256 KiB 上限约 23% |
| 真机 Ubuntu / Arch | `not-tested` | `not-tested` | 无设备 |

真实发行版（Ubuntu ~30 MB、Arch ~829 MB）的重复启动收益取决于原全量 segment 大小；本会话不能给出设备耗时或真实 initrd 大小。

## 8. 风险

- 若宿主 evidence 与根盘实际状态不一致（例如根盘被外部替换但 metadata 未清），guest 会走到首次装配分支并因 `rootfs-missing` fail closed；不会写坏根盘，但启动失败。
- 每次 launch 多一次实例 metadata 读取；相对于已消除的 rootfs 读取可忽略，但存储损坏会保守回退到全量。
- 256 KiB 上限是固定常量；真实凭据-only segment 约 59 KB，保留约 197 KB 余量。未来若增加合法的小文件，需要显式评估并调整，不能静默突破。
- 凭据-only initrd 仍包含 bridge token 与 guest password（与全量路径一致），未新增日志暴露面。

## 9. 新发现

- `RootfsStreamOpener` 作为 package-private 测试 seam 可让 T8 复用“是否打开 rootfs”的断言，不需要真实文件系统计数。
- `CREDENTIALS_ONLY_MAX_BYTES` 可直接作为 T8 的上限常量来源。
- guest 的 `provisioned-v1` 分支只消费 `.active` 与凭据/仓库文件，不读取 rootfs；凭据-only segment 与 guest 行为一致。

## 10. 对后续 ticket 的影响

- T8 可复用 `QemuRuntimeBootstrapperProvisionedTest` 的 opener/cap 断言，新增独立 `ProvisionedLaunchRegressionTest`；不要依赖真实 QEMU/gvproxy。
- T5 若启动，应继续复用 `credentialsOnly` 决策入口，不能绕过 `isProvisionedFor`。
- T8 的 0 字节契约读取 `launch-timeline.jsonl` 的 `rootfsBytes=0` 即可。

## 11. Commit

- `f396de3` perf(runtime): 已装配实例重复启动只嵌入凭据
- （本报告与工单 Comments 随后续 docs commit 入库）

## 12. Ticket 状态

建议 `completed`（JVM 验证完成）；真机重复启动与真实 initrd 内存/耗时为 `not-tested`，建议人工复核后进入 T8。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录

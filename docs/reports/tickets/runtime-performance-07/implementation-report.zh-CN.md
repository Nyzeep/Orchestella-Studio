# Ticket 07 实施报告

- 票号/标题：runtime-performance-07 QEMU 单变量调优实验
- 执行 session / 模型 / thinking：续跑 T7 ticket session / DeepSeek-V4.1-Flash / 未记录
- 分支：`perf/t7-qemu-tuning`　基线 commit：`bd24d24`（集成分支，含 T1/T2/T4）

## 1. 任务目标

把 QEMU 性能相关参数变成可注入的 tuning 值对象，默认值与今天逐项相同；提供可重复执行的设备矩阵脚本与结论报告。默认值不变，没有统计显著收益就保留现状。

## 2. 实际完成

- ✅ 新增 `QemuTuning` 值对象，覆盖 CPU model、`tb-size`、block `aio`、`discard=unmap`、`detect-zeroes=unmap`、vCPU、passt UNIX stream。
- ✅ `QemuTuning.DEFAULTS` 逐项复刻今天的行为：`cortex-a57`、`tcg,thread=multi`（不追加 `tb-size`）、root 盘 `format=raw,cache=writeback`、TCP loopback passt、vCPU 取 `VmHardwareConfig.cpuCores()`。
- ✅ `QemuLaunchSpec.buildWithPasst(...)` 新增显式 tuning 重载；旧签名委托到 `QemuTuning.DEFAULTS`，测试断言两种调用参数数组逐项相同。
- ✅ 单变量变体均有参数构造测试；默认输出不出现 `tb-size`、`aio`、`discard`、`detect-zeroes`，root 盘保持 `cache=writeback`。
- ✅ 拒绝非法 tuning：CPU model 注入字符、负 `tb-size`、未知 `aio`、相对/含逗号的 UNIX socket path。
- ✅ 新增 `tools/qemu-tuning/matrix.py`：11 个变体 × 6 个 workload，`--dry-run` 生成 66 行 TSV，记录设备字段与 `status=not-tested`；`--json` 输出摘要；`--device` 可采集 adb 型号/Android 版本/电池/热状态。
- ✅ dry-run 证据：`docs/reports/tickets/runtime-performance-07/matrix-dry-run.tsv`。
- ⚠️ 设备实测：本会话无 `adb`/设备，全部 workload 与统计口径 `not-tested`；没有编造 benchmark 数字。

## 3. 未完成 / 与预期偏差

- 真实设备 boot-to-prompt、`apt update`、`tar -xzf`、`gcc` 编译、`dd`、`iperf3` 未运行；n≥10、中位数 + IQR、控温均未执行。
- `io_uring` 在 Android 内核上的可用性、`neoverse-n1` / `max,sve=off` 在当前 QEMU 11.1.1 构建上的可启动性、`-netdev stream` unix socket 的真实连通性均未验证。
- 矩阵脚本目前是“生成 + 记录 + not-tested”驱动；真正计时需要带 tuning 注入点的实验构建。它不会伪造测量结果。

## 4. 修改的模块

- `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuTuning.java`（新增）
- `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuLaunchSpec.java`（tuning 消费点、默认委托、drive/netdev/accel 格式化）
- `runtime/engine-qemu/src/test/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuTuningTest.java`（新增，main-based 契约测试）
- `runtime/engine-qemu/build.gradle.kts`（注册 `qemuTuningTest` 到 `check`）
- `tools/qemu-tuning/matrix.py`（新增）
- 未改 bootstrapper、supervisor、gateway、guest init 或默认启动参数。

## 5. 重要设计决策

- **默认值以“省略”表达**：`tbSizeKb=0`、`blockAio=null`、`vcpuCount=0`、`passtUnixSocketPath=null` 都不向 QEMU 追加参数，因此默认 argv 与今天逐项相同，而不是用 QEMU 默认值“看起来相同”。
- **不给 cache 建模**：`QemuTuning` 没有 cache 字段，结构上禁止 `cache=unsafe`。
- **网络变体独立于默认**：只有显式传入 UNIX socket path 才切换到 `-netdev stream`；默认仍走 `socket,connect=127.0.0.1:<port>`。
- **实验与实际执行分离**：脚本只生成矩阵与记录元数据；没有测量就写 `not-tested`，避免把 dry-run 当成 benchmark。

## 6. 测试

- `./gradlew :runtime:engine-qemu:check -Porchestella.repositories.useAliyunMirror=true --no-daemon`：通过。
- 新增 `qemuTuningTest`：默认等价、单变量覆盖、默认无 tuning 选项、无 `cache=unsafe`、UNIX stream 显式、非法值拒绝，全部通过，输出 `QEMU_TUNING_TEST_OK`。
- 既有 `qemuLaunchSpecTest`、`qemuGpuLaunchSpecTest`、`qemuHostfwdContractTest`、`embeddedProvisionInputTest` 等全部通过。
- 矩阵脚本 dry-run：`variants=11 workloads=6 rows=66 device=not-tested status=not-tested`；`git diff --check` 通过。
- 真机：`not-tested`。

## 7. Benchmark / 实测数据

| 环境 | 数据 | 说明 |
| --- | --- | --- |
| JVM 参数构造 | `QEMU_TUNING_TEST_OK` | 只证明默认等价与参数格式，不是性能数据 |
| 设备矩阵 | `not-tested` | 无 adb/设备；66 行均为 `status=not-tested` |

结论：**保留现状**。没有至少一个 workload 改善 ≥10% 且其他 workload 回归 ≤5% 的证据，因此不提议修改任何默认值，也不把 AVF 算作收益。

## 8. 风险

- `-netdev stream` 参数构造通过但未在真实 passt/QEMU 上验证；若地址类型或路径不受支持，设备实验会启动失败。
- `io_uring` 可能被 Android 内核/SELinux 限制；实验必须记录启动失败，不能把失败样本当作性能样本。
- CPU model 变体可能被当前 QEMU 构建拒绝；矩阵脚本尚未自动区分“启动失败”和“测量完成”。
- 设备温控与后台负载未控制，未来实测必须记录电池温度与热状态。

## 9. 新发现

- 当前 `buildWithPasst` 是生产 QEMU 主路径；T7 的 tuning 注入点放在该重载上即可覆盖实际启动，不需要改 `QemuInstanceLauncher`。
- 现有 engine-qemu 测试是 main-based JavaExec，不是 JUnit；新增调优测试按同一模式注册到 `check`。

## 10. 对后续 ticket 的影响

- T5 若启动并修改 `QemuLaunchSpec`，必须保留本票的 `QemuTuning` 默认委托与默认 argv 等价测试；不要同时改同一文件。
- 任何默认值变更都是独立后续决定，需要本矩阵的真实设备数据，不在 T7 内执行。
- T8 不依赖本票。

## 11. Commit

- `9755242` perf(qemu): 增加单变量调优值对象与矩阵脚本
- （本报告、矩阵 dry-run 证据与工单 Comments 随后续 docs commit 入库）

## 12. Ticket 状态

建议 `completed`（实验脚手架与默认等价测试完成）；设备矩阵与所有性能数字 `not-tested`，建议人工复核后再决定是否投入设备实验。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录

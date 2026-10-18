# T7 QEMU 单变量调优实验

Status: ready-for-human
Type: task
Priority: P2
Blocked by: T1

## 交付

把 QEMU 性能相关参数变成可注入的 tuning 值对象，默认值与今天逐项相同；提供一个可重复执行的设备矩阵脚本与一份结论报告。

候选变量（每次只改一个）：

- CPU 模型：`cortex-a57` vs `neoverse-n1`（暴露 LSE，无 SVE）vs `max,sve=off`
- TCG：`tb-size=32/64/128`
- 磁盘：`aio=threads` vs `io_uring`（若平台允许）；`discard=unmap`、`detect-zeroes=unmap`
- vCPU：2 vs 4
- 网络：TCP loopback vs `-netdev stream` unix socket

工作负载：boot 到提示符、`apt update`、`tar -xzf`、`gcc` 编译、`dd`、`iperf3`。统计口径：同设备、同镜像、同资源、控温，n≥10，中位数 + IQR。

**默认值不变。** 只有“至少一个 workload 改善 ≥10% 且其他 workload 回归不超过 5%”才在报告中提议改默认；改默认是独立的后续决定，不在本票内执行。

## 验收

- [ ] 默认启动参数与今天逐项相同的测试断言。
- [ ] 矩阵脚本可重复执行，记录设备、温控、镜像、tuning 值。
- [ ] 报告包含负结果、not-tested 与原始数据位置。
- [ ] 不使用 `cache=unsafe`；不把 AVF 算作性能收益。
- [ ] 没有统计显著收益时明确写“保留现状”。

## 测试

- 参数构造单测；矩阵脚本自身 dry-run。
- `./gradlew :runtime:engine-qemu:check`

## Comments

- 实施 commit：`9755242`（`perf/t7-qemu-tuning`，基线 `bd24d24`）。
- 默认值等价：新增 `QemuTuning.DEFAULTS`；`QemuLaunchSpec.buildWithPasst(...)` 旧签名委托到显式 tuning 重载，单元测试断言两种调用的参数数组逐项相同；默认参数仍为 `cortex-a57`、`tcg,thread=multi`（无 `tb-size`）、root 盘 `cache=writeback`、TCP loopback passt。
- 单变量开关：CPU model、`tb-size`、`aio`、`discard=unmap`、`detect-zeroes=unmap`、vCPU、passt UNIX stream；非法值（含注入字符、非法 aio/path）在构造时拒绝。
- 安全边界：没有 cache 字段，测试断言任何默认/变体参数都不含 `cache=unsafe`，root 盘保持 `cache=writeback`。
- 矩阵脚本：`tools/qemu-tuning/matrix.py --dry-run`，11 个变体 × 6 个 workload = 66 行；每行记录 image、tuning 值、设备型号/Android 版本、电池温度、热状态、`samples_target=10` 与 `status=not-tested`。dry-run 证据：`docs/reports/tickets/runtime-performance-07/matrix-dry-run.tsv`。
- 测试：`./gradlew :runtime:engine-qemu:check -Porchestella.repositories.useAliyunMirror=true --no-daemon` 通过；新增 `qemuTuningTest` 通过；既有 `qemuLaunchSpecTest` / `qemuGpuLaunchSpecTest` 等全部通过。
- 结论：**保留现状**。本会话没有设备数据，未观察到任何 workload 的 ≥10% 改善；不提议修改默认值，不把 AVF 算作收益。
- 未测项：真实设备 boot/apt/tar/gcc/dd/iperf3、控温与 n≥10 统计、`io_uring` 在 Android 内核上的可用性、`neoverse-n1`/`max,sve=off` 在当前 QEMU 11.1.1 构建上的可启动性、`-netdev stream` unix socket 路径的真实连通性，全部 `not-tested`。
- 风险：`-netdev stream` 参数构造已测试但未在真实 passt/QEMU 上验证；若 QEMU 不支持某个 CPU model 或 `io_uring`，设备实验会启动失败，矩阵脚本需记录失败而不是当作性能结果。
- 审查修复（`e5876ba`）：脚本强制 `--dry-run` 或 `--device` 二选一，`--dry-run` 真正生效；增加 `--image`、`samples_target`、电池温度字段解析；不再保存 serial、原始 dumpsys 或完整 fingerprint，只保留 fingerprint SHA-256。

# T7 QEMU 单变量调优实验

Status: ready-for-agent
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

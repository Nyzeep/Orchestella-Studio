# DV-PVM-02: 真机端到端（02a 产品引擎上设备 / 02b QEMU guest 交叉加速）

- Type: device validation
- Status: **02a done（2026-10-25）**；**02b not-attempted**
- Priority: P0（Wave 2 的收口票）
- Owner: device owner
- Blocked by: PVM-03、PVM-08、DV-PVM-01
- Parent: `../决策地图.md`
- Zero-device: 否

## 目标

1. **02a**：让产品路径上的 `clang_engine.cpp`（从未编译过）在真机 protected pVM 里编出一次真实 distcc job 的 `.o`。
2. **02b**：QEMU guest 内的 `distcc` 客户端把 `.i` 发到宿主桥，由 pVM 编译，`.o` 回传后在 guest 链接运行。

## Status / Comments

- **02a done**。真机上 `STAT=0x0000`、`.o` 91,568 B、两轮逐字节相同、链接产物在设备上运行退出码 237（= `fixture_entry()` 期望值）、VM stop/delete 干净。
  - 关键缺陷（本轮才暴露）：引擎把 driver 参数 `-fno-crash-diagnostics` 塞进了 `-cc1` argv，`CreateFromArgs` 对每个 job 都失败，被统一映射成 `ARGV_REJECTED:CLANG_INTERNAL`。
  - 前置缺口：Microdroid 无宿主→payload env 通道 → token 改由 APK `assets/accel_token` 兜底（env 仍优先）。
  - 宿主侧 `adb forward` 在本机不投递字节（设备内 `nc` 通），02a 改走文件通道 + 应用内生产 AXP1 客户端。
- **02b not-attempted**：时间盒被 02a bring-up 耗尽；且 `AcceleratorLaneAssembly.assemble()` 仍无生产调用方，QEMU 与 pVM 的同时启动在产品代码里不存在。
- 未验证：同版本宿主 clang 对照（`build/host` 的 `clang` 目标补编未完成）、真实 `distcc 3.4` 客户端、`ru_maxrss`、并发 >1。
- 报告：`docs/reports/tickets/dv-pvm-02/implementation-report.zh-CN.md`（分支 `feat/dv-pvm-02-device-e2e`）。

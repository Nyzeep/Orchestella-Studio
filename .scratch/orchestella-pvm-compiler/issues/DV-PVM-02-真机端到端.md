# DV-PVM-02: 真机端到端（02a 产品引擎上设备 / 02b QEMU guest 交叉加速）

- Type: device validation
- Status: **02a done（2026-10-25）**；**02b done（交火本体，2026-10-26；分段 partial）**
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
- **02b done（2026-10-26，会话 8，分支 `feat/dv-pvm-02b-crossfire`）**：交火本体首次真实发生——guest（Alpine 3.24.1 chroot，clang 22.1.3）内 `DISTCC_HOSTS=192.168.127.254:5103 distcc clang` 真实打到生产 `DistccTcpVsockBridge`，AXP1 握手（token+triple）后 payload 编译，`.o` 90,836 B 级载荷原路回传（`distcc_remote_seen=True` ×4，85-94s/轮）；门硬检查点 `awaitAcceleratorReady=true`（gate_ms≈3.5s，LANE_READY/ACCEL/READY 事件）；结构等价 Level 3：符号集 2004 与重定位 2001 完全一致。
  - 本轮暴露并修复的产品缺陷：① payload 构建 `useLegacyPackaging=false` + targetSdk≥34 W^X → QEMU 车道 spawn 必败（QA 走 `orchestella.qaTargetSdk=28` + `NativeExecutableStore` APK 提取）；② 桥通道工厂必须包 `Axp1DistccChannelFactory`（裸 vsock 工厂被 payload 7ms 拒绝且 distcc 静默本地回退）。
  - 偏差：guest 发行版 Arch+Alpine chroot（产品目录无 Alpine）；n=3 与 guest 内行为级退出码归下一轮；分段打点归 AX-07。
  - 报告：`docs/reports/tickets/dv-pvm-02b/implementation-report.zh-CN.md`。
- 未验证：同版本宿主 clang 对照（`build/host` 的 `clang` 目标补编未完成）、真实 `distcc 3.4` 客户端、`ru_maxrss`、并发 >1。
- 报告：`docs/reports/tickets/dv-pvm-02/implementation-report.zh-CN.md`（分支 `feat/dv-pvm-02-device-e2e`）。

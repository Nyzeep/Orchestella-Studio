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

## Comments

- 2026-11-07 / ZCode（设备窗 T5① / GLM-5.3-Flash）：**02b 产品 listening 缺口的结构性确认 + 编排层真机演练事实**（设备别名 device-nova，仓外证据 `evidence/device-window-20261106/t3-distcc36/`）：
  - **结构性确认**：全仓 grep 证实 `FabricOrchestrator.dispatch` 产品侧**零调用方**（仅测试与 `AcceleratorLaneAssembly.fabricOrchestrator` 接线格）——B2 报告 follow-up ② 的"接线格已备、等 listening 起点"与 02b 的"native 面 TCP 监听起点缺口"互为同一缺口的两面。**收口条件合并为一票**：监听器（产品 TCP 起点）落地时必须把连接请求经 `FabricOrchestrator.dispatch` 走编排，二者同步收口；在既有双 gate 之下，无需新 ACR 改动面（D-1 标记通道仍单列）。
  - **真机演练**：QA compiler-crossfire 探针经**生产 `DualLaneCoordinator`** 编排双车道真机运行（start_report: qemu READY / accel STARTING，reject_code=NONE，QEMU lane 走生产 `QemuInstanceLauncher` 同型参数）；ACCEL lane 在本设备死于平台级 hypervisor 崩溃（Gunyah vcpu crash，见 DV-01A 工单 2026-11-07 行与本轮报告）——编排层行为本身与平台崩溃无关，budget/request 形状按生产常量。
  - **对 distcc@36 的影响**：ACR §8 该行在 device-nova 上因 pVM 平台崩溃不可达（非 02b 缺口所致）；QEMU 面（T4）与 UML 面（T5② 零设备切片已合并 main）不受影响。

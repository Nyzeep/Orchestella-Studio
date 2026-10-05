# 36: AVF 档能力对齐——出站网络与端口转发

Type: implementation
Status: completed

> **实施与评审记录（2026-10-05）**：4 个 TDD Slice 完成；独立代码复审裁定 APPROVE（`docs/reports/reviews/36/review-report.zh-CN.md`）；runtime 7 模块 `--offline` 全绿。准入合流。

## What to build

硬件虚拟化档补齐网络能力（ADR-0008 语义在 AVF 实现上等价成立）：guest 出站 NAT（crosvm TAP 型后端 + 宿主侧网络桥接）；端口转发经 vsock 隧道到 guest 内桥接代理（热更语义与 QEMU 档一致）。完成后 L2 用户获得与 L1 相同的网络能力——契约测试的网络/转发条目在两个策略上全绿。

**Blocked by:** 34, 27, 29（三者均已合流）

## Acceptance criteria

- [x] AVF 档实例出站网络契约与 27 同套全绿（`AvfLaunchSpec.networkSpec` 复用同一策略中立 `OutboundNetworkSpec`；`setNetworkSupported(true)` 转正并被 `AvfNetworkAssemblyContractTest` 契约覆盖；guest cmdline 同键 `droidlayer.dns=`；`NETWORK_CONTRACT_TEST_OK` + `AVF_LAUNCH_SPEC_TEST_OK outbound_net=1`。**真机 `apk update` 待 HITL 真机冒烟（票 17）**）
- [x] AVF 档端口转发：创建→本机命中→删除→失效，含热更（`AvfVsockPortForwarder`：宿主监听→vsock 隧道→guest 内代理；`AVF_VSOCK_PORT_FORWARDER_TEST_OK live=1 hot_removal=1 re_add=1 close=1`；持久化/冲突经 manager 契约套件在 AVF 引擎上验证；热删即时失效、无需重启 VM，语义与 29 逐行同构）
- [x] 两策略转发语义等价性契约测试通过（同一套 `PortForwardingManagerContractTest.runAllScenarios`：QEMU 档 `SocketPortForwarder` 与 AVF 档 vsock 引擎各自全绿，`PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK` ×2；`AVF_NETWORK_FORWARDING_PARITY_CONTRACT_TEST_OK same_suite=1`）
- [x] 能力矩阵更新：AVF 档网络/转发能力声明变为"已验证"（`NetworkCapabilityDeclaration` 数据声明：AVF 档两项 CONTRACT_VERIFIED 且与软件档同能力集合；诚实分级不预认 DEVICE_VERIFIED——真机实测随 HITL 冒烟回填；contract 枚举升格涉零修改约束，见报告 §7）

## Test strategy

既有网络/转发契约测试直接对 AVF 实现运行（不写新测试，只扩展运行对象）；真机冒烟。

**实际执行**：manager 契约套件零断言改动、经 `LiveEngineFactory` 扩展运行对象（QEMU 档行为不变）；新增的 3 个 engine-avf 测试 + 1 个 capability 测试为 AVF 装配/引擎/声明层的最小新契约面（票 29 套件未覆盖的引擎本体行为）。真机冒烟按 HITL 协议执行（本票零设备操作）。

## Prototype/research dependency

P1（17）的网络形态实测数据（TAP/网络桥接的设备差异）。**状态**：票 17 仍 open；本票按票 34 先例在 JVM 模拟层完成契约验证，真机数据回填时核对 `setKernelCmdline`/`setParams` 变体与 crosvm 网络后端实际形态（报告 §7.4）。

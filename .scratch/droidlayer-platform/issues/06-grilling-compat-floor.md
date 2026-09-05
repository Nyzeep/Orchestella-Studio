# 兼容性地板：最低 Android 版本、架构与设备档

Labels: wayfinder:grilling
Type: grilling
Status: resolved
Blocked by: 03

## Question

我们的支持地板是什么：最低 Android 版本、仅 ARM64 还是更多、明确放弃哪些设备档（RAM、存储、OEM）？

## Why it matters

决定 QEMU 打包目标（minSdk）、可执行文件交付方式（W^X 约束）、AVF 分支的 API 层次、以及"不支持的设备如何被诚实告知"。

## Known facts

- Podroid：minSdk 26 / targetSdk 36 / arm64-v8a only；AVF 路径需 Android 14+（源码核实）。
- targetSdk≥29 禁止从 app 可写目录 exec → QEMU 等可执行文件必须以 jniLib（.so 名义）打包；侧载最低 targetSdk 仍为 24（2026 核实）。
- AVF 设备矩阵碎片化（Pixel 6+；三星 Snapdragon 排除；对 2025/16 起新 SoC 强制）。

## Working recommendation

QEMU 档 minSdk 26 对齐 Podroid（覆盖面已被验证）；AVF 档运行时探测、不设硬版本门槛（API 34+ 检测）；明确放弃 <3GB RAM 设备并如实降级提示；架构仅 ARM64。

## Reversibility

minSdk 提高容易、降低难（发布后有用户基座）；地板宁低勿高，能力按检测分层。

## Answer

2026-09-05 用户采纳。固化于 **docs/adr/0004-compat-floor.md** 与 CONTEXT.md 词条（支持地板 / 诚实拒绝）。

决定要点：
1. L1（QEMU 档）minSdk 26（Android 8.0）；L2（AVF 档）无独立版本门槛，纯运行时检测（API 34+ + feature flag + 权限 + custom VM 能力谱）。
2. 架构仅 arm64-v8a；x86_64 guest 为非目标（未来属加法）。
3. RAM ≥ 3GB 且存储余量达标才可创建 Full Linux 实例；数值可随 QEMU 基线原型微调。
4. 低于地板 → 诚实拒绝并解释原因；不提供静默降级；Compatibility Runtime 是低配设备的显式出口。
5. 模拟器（x86_64 host）检测时清晰提示。

解锁：无新增阻塞票；Guest Kernel 契约票（07）成为下一个 grilling 对象。

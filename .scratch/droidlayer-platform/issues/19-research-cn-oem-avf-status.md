# 研究：国产 OEM（小米/OPPO/vivo/荣耀）AVF 与 ADB 授权现状

Labels: wayfinder:research
Type: research
Status: open

## Question

2026 年国产 OEM 设备的 AVF/pKVM 支持现状如何？HyperOS/ColorOS/OriginOS 等对 adb 授权（pm grant development 级权限）有无额外限制？这对「无 Root + L2 授权」目标用户群的实际覆盖率意味着什么？

## Why it matters

目标用户大概率集中在国产设备；前置讨论与 round-0 研究对国产 OEM 覆盖近乎空白（仅知一加 13 支持 AVF、小米 USB 调试有额外门槛）。

## Working notes

检索各 OS 对 `android.software.virtualization_framework` 的声明、开发者社区实测报告、每 OEM 的 USB 调试/授权政策差异；产出覆盖率矩阵进 docs/research/。受本机 agent 并发额度限制，本票由会话逐个领取执行。

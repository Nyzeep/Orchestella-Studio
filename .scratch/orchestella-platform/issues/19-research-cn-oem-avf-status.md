# 研究：国产 OEM（小米/OPPO/vivo/荣耀）AVF 与 ADB 授权现状

Labels: wayfinder:research
Type: research
Status: resolved

## Question

2026 年国产 OEM 设备的 AVF/pKVM 支持现状如何？HyperOS/ColorOS/OriginOS 等对 adb 授权（pm grant development 级权限）有无额外限制？这对「无 Root + L2 授权」目标用户群的实际覆盖率意味着什么？

## Why it matters

目标用户大概率集中在国产设备；前置讨论与 round-0 研究对国产 OEM 覆盖近乎空白（仅知一加 13 支持 AVF、小米 USB 调试有额外门槛）。

## Working notes

检索各 OS 对 `android.software.virtualization_framework` 的声明、开发者社区实测报告、每 OEM 的 USB 调试/授权政策差异；产出覆盖率矩阵进 docs/research/。受本机 agent 并发额度限制，本票由会话逐个领取执行。

## Answer

报告已落盘：**docs/research/2026-09-05-cn-oem-avf-status.md**。

核心发现：
1. **Play Console 设备目录显示 361 款机型声明支持 AVF**（含小米/OPPO/vivo/一加/摩托罗拉/Tecno）——L2 覆盖面显著大于"Pixel only"旧估计；三个折扣（flag≠Terminal 可用≠第三方 custom VM 可用≠网络可用）必须保留。
2. OEM ADB 限制矩阵（社区证据）：小米 SIM+账号门控"USB 安装/安全设置"、ColorOS 账号门控 install、OriginOS 账号+开关；**pm grant 是否被门控波及未实测**。
3. 授权向导设计新增输入：per-OEM 步骤页 + "授予失败"一等场景处理（spec UX 章节，无需重开 ADR）。
4. 未发现需要重开已关闭 ADR 的新证据；ADR-0003 反而被覆盖面数据加强。

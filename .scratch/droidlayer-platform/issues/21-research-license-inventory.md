# 研究：许可证与再分发义务盘点

Labels: wayfinder:research
Type: research
Status: resolved

## Question

全组件许可证与再分发义务盘点：crosvm/AVF 组件、QEMU、Linux kernel、Alpine/Debian rootfs 包、gfxstream、libslirp、候选 UI/终端库；作为"分发者"各需履行什么义务（源码提供/声明/专利条款）？

## Why it matters

为 grilling 票「许可证策略」提供事实底座；确认哪些组件能以"独立分发"方式规避传染、哪些必须开源合规。

## Working notes

逐组件 LICENSE/README 核实（crosvm 许可证当前未核实，round-0 报告标注"疑 BSD 系"）；产出 docs/research/license-inventory.md 义务矩阵。受本机 agent 并发额度限制，本票由会话逐个领取执行。

## Answer

报告已落盘：**docs/research/2026-09-05-license-inventory.md**。

核心发现：
1. **crosvm = BSD-3-Clause**（存疑已解）；且 AVF 路径我们不分发 crosvm（用设备自带的）。
2. **gfxstream = Apache-2.0**（vendored 文件 MIT/MIT-Khronos）——M4 路线无许可证障碍。
3. GPL 义务（自建 kernel/QEMU）清单化：源码版本+config+构建脚本+书面 offer，CI 生成第三方许可页。
4. rootfs 官方 tarball 模式（ADR-0006）使包级义务锚定上游；HiddenApiBypass（LGPL-3.0）默认不引入、纯反射优先；Termux 组件禁用、替代候选 ConnectBot 系（Apache）或自研。
5. 未发现与 Apache-2.0 冲突的组件；无需要重开 ADR 的新证据。

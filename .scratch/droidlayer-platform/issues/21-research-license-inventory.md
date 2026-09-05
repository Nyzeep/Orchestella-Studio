# 研究：许可证与再分发义务盘点

Labels: wayfinder:research
Type: research
Status: open

## Question

全组件许可证与再分发义务盘点：crosvm/AVF 组件、QEMU、Linux kernel、Alpine/Debian rootfs 包、gfxstream、libslirp、候选 UI/终端库；作为"分发者"各需履行什么义务（源码提供/声明/专利条款）？

## Why it matters

为 grilling 票「许可证策略」提供事实底座；确认哪些组件能以"独立分发"方式规避传染、哪些必须开源合规。

## Working notes

逐组件 LICENSE/README 核实（crosvm 许可证当前未核实，round-0 报告标注"疑 BSD 系"）；产出 docs/research/license-inventory.md 义务矩阵。受本机 agent 并发额度限制，本票由会话逐个领取执行。

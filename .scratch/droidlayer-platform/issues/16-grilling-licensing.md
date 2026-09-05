# 许可证策略：本项目许可证与复用边界

Labels: wayfinder:grilling
Type: grilling
Status: open

## Question

本项目的许可证是什么？对 GPL 组件（Podroid GPL-2.0、QEMU GPLv2、kernel GPLv2、Termux fork GPL）采取"借鉴架构"还是"复用代码"的立场？分发义务（kernel/QEMU 源码提供）如何履行？

## Why it matters

一次性、全局性、几乎不可逆：一旦公开发布过混入 GPL 代码的闭源版本即构成违规；同时决定商业化与上架路径。

## Known facts

- Podroid 仓库 GPL-2.0-or-later（源码核实）；其 vendored Termux fork 同源 GPL。
- QEMU GPLv2+、Linux kernel GPLv2（分发二进制需提供对应源码）、libslirp BSD-3；crosvm 许可证待「许可证与再分发义务盘点」研究票核实。
- 架构/机制可观察、可重实现（01 票 Verdict 已给"可抄设计 vs 需避免"清单）。

## Working recommendation

干净路线：本项目代码全原创（架构借鉴 Podroid，零代码复制）→ 自由选许可证（GPL-3.0 或 Apache-2.0，取决于商业化意图——需用户表态）；kernel/QEMU 以"独立分发组件 + 源码链接"方式履行 GPL 义务；不 vendored Termux，终端方案另选。

## Reversibility

首次发布前可逆；发布后基本不可逆。必须早决。

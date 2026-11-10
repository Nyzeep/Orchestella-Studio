# UML 零设备快速启动与规模化（2026-11-10）

本目录是 `orchestella-uml-fabric` 之后的零设备方向包，回答两个问题：

1. 为什么 Termux proot-distro 能“下载完几乎秒开”，而本项目要等内核、装配、provisioning 与 systemd；
2. UML 独立内核方案下一步在零设备上能大规模做什么。

## 入口

- [Spec](spec.md)：问题陈述、T0–T5 指标、U0–U6 工作包、优先级、里程碑、ACR 边界。
- [研究报告](../../docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md)：proot / UML / QEMU 的一手来源与启动分解。
- 第一批零设备票：
  - [01 · Boot timeline harness](issues/01-boot-timeline-harness.md)
  - [02 · Fast-boot UML profile](issues/02-fastboot-profile.md)
  - [03 · Rootfs 预烘焙](issues/03-rootfs-prebake.md)
  - [04 · Android-like seccomp / host compat](issues/04-seccomp-host-compat.md)

## 一句话结论

proot 的“秒开”来自**没有 guest 内核**：它是宿主内核上的用户态 `ptrace` 路径重写，rootfs 只是 tarball，启动只是 exec 用户态进程；它不属于 Full Linux Runtime。UML 有真内核，必须付内核 boot + init，但本项目已测到 **220–400 ms（中位 ≈330 ms）** 到 guest marker，真正让产品“等很久”的是 QEMU 全 VM 与首启 provisioning。下一步不是追求 proot 的无内核秒开，而是把 UML 的 fast shell / full systemd 两档做成可测量、可预烘焙、可规模化的产品方向。

## 纪律

- 零设备优先；真机项标 `not-tested` 并给下个窗口入口。
- ACR 通过前不写产品集成代码、不改版本号/签名/发布通道、不改 ADR 正文。
- 跨夹具、跨就绪点数字禁止相除；所有数字带 n、中位数、夹具标识。

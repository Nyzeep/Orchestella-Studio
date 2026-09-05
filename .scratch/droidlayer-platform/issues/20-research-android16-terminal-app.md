# 研究：Android 16 官方 Terminal 应用作为参考实现

Labels: wayfinder:research
Type: research
Status: open

## Question

AOSP 官方 Terminal 应用（AVF Debian VM）的源码架构能告诉我们什么：它如何做 custom VM、网络、文件共享、GPU（virtio-gpu/gfxstream）配置、生命周期？哪些能力是它作为系统/特权应用独占、第三方无法复用的？

## Why it matters

这是与本项目最接近的第一方参考实现；区分"平台能力"与"系统应用特权"直接校准网络模型、桥接协议、图形路线三张决策票的预期。

## Working notes

AOSP 源码定位（platform/packages/apps/ 下 Terminal / LinuxTerminal 相关目录）+ Android 16 QPR2 行为变化文档；产出对照表：能力 | 官方实现方式 | 第三方可复用性。受本机 agent 并发额度限制，本票由会话逐个领取执行。

## Comments

- 2026-09-05：首次执行尝试失败——后台 agent 与活跃 grilling 会话争抢模型并发额度被中止（运行 53 分钟）。认领已释放。**下次领取应在没有活跃对话的时段单独执行，或拆成多次短检索**；避免与 grilling 并发。

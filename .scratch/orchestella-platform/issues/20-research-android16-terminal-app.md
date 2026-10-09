# 研究：Android 16 官方 Terminal 应用作为参考实现

Labels: wayfinder:research
Type: research
Status: resolved

## Question

AOSP 官方 Terminal 应用（AVF Debian VM）的源码架构能告诉我们什么：它如何做 custom VM、网络、文件共享、GPU（virtio-gpu/gfxstream）配置、生命周期？哪些能力是它作为系统/特权应用独占、第三方无法复用的？

## Why it matters

这是与本项目最接近的第一方参考实现；区分"平台能力"与"系统应用特权"直接校准网络模型、桥接协议、图形路线三张决策票的预期。

## Working notes

AOSP 源码定位（platform/packages/apps/ 下 Terminal / LinuxTerminal 相关目录）+ Android 16 QPR2 行为变化文档；产出对照表：能力 | 官方实现方式 | 第三方可复用性。受本机 agent 并发额度限制，本票由会话逐个领取执行。

## Comments

- 2026-09-05：首次执行尝试失败——后台 agent 与活跃 grilling 会话争抢模型并发额度被中止（运行 53 分钟）。认领已释放。**下次领取应在没有活跃对话的时段单独执行，或拆成多次短检索**；避免与 grilling 并发。
- 2026-09-05：第二次后台尝试再次被并发额度中止（2.5 分钟）。改为**主线程串行检索执行**（子代理在本机额度下不可用）。

## Answer

报告已落盘：**docs/research/2026-09-05-android16-terminal-参考实现.md**（含能力对照表）。

核心发现：
1. Terminal 应用 = `com.android.virtualization.terminal`，在 AVF 模块内、平台签名——其部分能力是系统特权；第三方经 adb/Shizuku 授权可复用"可授权子集"（nixos-avf / koiTerminal / lfdevs 实证）。
2. Debian 镜像由 Google 托管下载（ferrochrome），custom VM 形态（非 Microdroid 载荷）。
3. 图形 = guest Wayland → virtio-gpu → gfxstream 转发宿主 GPU；Pixel 10 已落地、限制多、其他设备多为软渲染回退——官方 Wayland-first 排序提高了我们 M3 的可信度；M4 前置判断（ADR-0012）不变。
4. 整盘存储/全共享存储访问属特权域，我们不跟进（白名单制定位反而差异化）。
5. 未发现任何需要重开已关闭 ADR 的新证据。

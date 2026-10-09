# Podroid 源码深度分析

Labels: wayfinder:research
Type: research
Status: resolved

## Question

Podroid（github.com/ExTV/Podroid）的实现到底处于什么状态？哪些机制已被源码证实、值得本项目借鉴？哪些是弱点？

## Answer

已完成源码级分析（浅克隆 main @ bc7477fb，2026-09-03 + GitHub API 交叉验证）。全文：**docs/research/2026-09-05-podroid-源码分析.md**

要点：VmEngine 接口 + QemuEngine/AvfEngine + EngineHolder 运行时路由（纯函数决策、可单测）——本项目最值得借鉴的部分；rootfs = 只读 zstd squashfs + 持久 ext4 overlay upper + 版本化迁移；vsock 仅 AVF 路径、QEMU 用 virtio-console 三通道；AVF 走反射 + HiddenApiBypass 调 @SystemApi VirtualMachineCustomImageConfig（非保护 VM）；USB 直通仅 QEMU（QMP add-fd / SCM_RIGHTS）；图形 = guest Xvnc 软渲染 + 手写 RFB 客户端，无 GPU 加速；无剪贴板/IPv6/多 VM。警示：GPL-2.0（抄代码=传染）、无 CI、构建输入零校验、guest root 密码公开常量、后端非对称是作者自认的最大 bug 源。

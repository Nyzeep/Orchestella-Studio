# 图形路线分期：MVP 是否含 GUI，里程碑如何排

Labels: wayfinder:grilling
Type: grilling
Status: open
Blocked by: 03

## Question

图形能力的分期承诺：MVP 是否包含任何 GUI（还是终端 only）？VNC → Wayland → virtio-gpu/gfxstream 的里程碑顺序与验收标准？

## Why it matters

图形是本项目最大的范围失控源（前置文档亦承认）；MVP 是否含 GUI 决定工作量数量级与交付时间。

## Known facts

- Podroid 路线：guest Xvnc 软渲染 + 手写 RFB 客户端，无 GPU 加速，可用但 3D 弱（01 票）。
- Android 16 官方 Terminal：Pixel 10 上 GPU 加速落地但"major limits"；第三方 app VM 能否用 gfxstream 未证实（round-0 报告）。
- gfxstream/virtio-gpu 对第三方 AVF VM 的可用性 = 原型问题（并入「AVF 端到端验证」票的验证清单）。

## Working recommendation

MVP = 终端 only（+ 可选最简 VNC 通道，复用已验证的 Xvnc+RFB 模式并明确标注软渲染）；Wayland 与 virtio-gpu 是后续里程碑；gfxstream 列为实验分支；IME/输入集成随第一版 VNC 一起做。

## Reversibility

终端先行可逆性最好；MVP 一旦承诺桌面体验，砍不掉。

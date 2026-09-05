# 发布渠道定位：Play vs GitHub Releases/F-Droid

Labels: wayfinder:grilling
Type: grilling
Status: open

## Question

MVP 的发布渠道优先级与组合：Google Play（specialUse FGS 的政策审查风险；L2 授权引导与商店体验的摩擦）vs GitHub Releases + F-Droid（目标人群天然聚集、无审核摩擦、与"无遥测"承诺兼容）？多渠道并行时签名、更新节奏与文案差异如何管理？

## Why it matters

ADR-0003 与 ADR-0011 都指向"Play 意义存疑但未决"；渠道决定签名与更新机制、L2 引导可达性（Play 版能否引导 Shizuku 路径）、specialUse 申报的暴露程度，以及 Termux 式"被迫退出 Play"的风险敞口。

## Known facts

- specialUse FGS 在 Play 需要用途申报与审核（Podroid 申报 subtype "Podman emulation via QEMU"）。
- Play 要求 targetSdk 36；W^X 约束使 jniLib 打包成为必需（已定）；Termux 因同类约束历史上退出 Play。
- ADR-0010 的无遥测承诺与 F-Droid 的包容政策高度兼容。

## Working recommendation

首发 GitHub Releases（目标人群 + 零审核摩擦），F-Droid 随后（补可复现构建要求），Play 观望（等 L2 Shizuku 路径成熟 + specialUse 申报经验）。待 grilling 确认。

## Reversibility

渠道是加法（先 GitHub 后 Play 可行）；签名方案一次定对（更新通道与实例数据安全绑定）。真正的成本在文案双轨维护。

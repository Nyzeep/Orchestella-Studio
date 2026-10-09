# 发布渠道定位：Play vs GitHub Releases/F-Droid

Labels: wayfinder:grilling
Type: grilling
Status: resolved

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

## Answer

2026-09-05 用户采纳推荐并补充五条细化。固化于 **docs/adr/0015-release-channels.md**（按用户要求区分【产品决策】/【工程假设】/【待验证】三层并标注证据来源）。

决定要点：
1. GitHub 首发、F-Droid 第二、Play 观望；更新检查经 GitHub API 且可关闭、无遥测。
2. 首版建立长期 signing key 管理与备份机制（唯一不可逆项）。
3. 不因 Play 缺席修改核心架构。
4. 证据标注：GitHub 人群触达为**类比证据**（Podroid ~20.5k 下载数据）；W^X/jniLib 为一手证据；specialUse 审查口径、Play 版 L2 可行性、F-Droid 复现构建工作量均标【待验证】。

---
status: accepted
date: 2026-09-05
amended: 2026-09-20
---

# 发布渠道：GitHub 首发、F-Droid 随后、Play 观望

> 标注约定：【产品决策】= 已拍板；【工程假设】= 有证据支撑但属类比/推断；【待验证】= 需后续数据。

1. 【产品决策】MVP **首发 GitHub Releases**；**F-Droid 第二渠道**；**Play 观望**（不设时间表）。每个含项目分发 GPL 二进制的 GitHub Release 同时发布固定版本 tag 与对应的 Corresponding Source Release 源码包，具体内容与保留 gate 见 ADR-0014。更新检查经 GitHub API，**可关闭、无遥测**。首版即建立**长期 signing key 管理与备份机制**。不因 Play 缺席修改核心架构。
2. 【工程假设·类比证据】"GitHub 能触达目标人群"的依据是**同类产品数据**：Podroid 以 GitHub Releases 分发获得约 20.5k APK 下载（docs/research/2026-09-05-podroid-源码分析.md §11）——这是第三方类比，**非本项目自有用户数据**；转化率待首发后实测。
3. 【工程假设·一手证据】W^X 约束（jniLib 打包）对 Play 与侧载同等适用，不存在"上 Play 反而绕开"的路径——证据：Android 官方行为变化文档 + Termux issue（docs/research/2026-09-05-前置研究独立审视.md §2.1）。
4. 【工程假设·待复核】无遥测承诺与 F-Droid 包容政策兼容——F-Droid 公开政策为常识级依据，**提交前按其当期收录要求复核**。
5. 【待验证】Play specialUse FGS 的实际审查口径（Podroid 申报 subtype 先例存在，审查结果因应用而异）；Play 版 L2（Shizuku 路径）引导的可行性；F-Droid 可复现构建的实际工作量。三者均为"Play 观望"的评估输入。

## Considered Options

- **Play 首发（否决）**：specialUse 审查风险 + L2 引导摩擦 + Termux 式"被迫退出"先例——在 L2 路径未成熟前进场是纯暴露。
- **Play 与 GitHub 同步首发（否决）**：双渠道文案/合规成本前置，而 Play 侧收益（商店用户）与产品定位（技术早期采用者）错配。

## Consequences

- 签名密钥保管是唯一不可逆项：丢失 = 存量用户无法应用内更新。
- 渠道为加法：同签名 APK 可多渠道并存；未来上 Play 不需要架构改动（本 ADR 明确约束）。
- 更新检查（GitHub API）默认开启、设置可关、行为透明——作为"无遥测"承诺的一部分写入产品文档。

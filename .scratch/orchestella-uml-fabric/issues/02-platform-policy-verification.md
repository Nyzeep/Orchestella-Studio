# 02: 平台政策核实——16KB 日期口径、Android 17 行为变更、Play 分发合规

Type: research
Status: resolved（2026-10-31，fabric-batch1）
Priority: P0（ACR 的硬前置数据，两个调研报告在此冲突）
Owner: （待分配会话）
Blocked by: None
External gates: 无（纯联网核实）

## 背景

两份 2026-10-31 调研在 16KB Play 要求上口径冲突：WorkBuddy 引「2025-11-01 起新应用/更新必须支持 16KB，宽限至 2026-05-31 已过」（support thread）；CodeBuddy 引「targetSdk 35+ 应用 2027-02-01 起不兼容更新无法发布」（developer.android.com/guide/practices/page-sizes）。两者可能针对不同要求（提交合规 vs 更新兼容），但 ACR 投票前必须收敛成一个权威口径。

## 必须回答

- [ ] 16KB：以 developer.android.com 与 support.google.com 当前页面为准，给出「哪些要求、何时生效、对谁生效」的权威口径表；明确本项目（GitHub/F-Droid 渠道 + Play 可选）各受哪条约束。
- [ ] Android 17 行为变更逐条影响表（对照 `docs/research/2026-10-31-uml-full-spectrum-research.zh-CN.md` §2.3 🆕 三行）：MemoryLimiter 对 `mem=512M~2G` guest 的实际阈值；本地网络/loopback 权限化对 gvproxy 端口转发与 passt 的影响；原生库只读对「内核 .so 走 nativeLibraryDir exec」路径是否影响（`System.load` 限制 vs `execve`/`mmap` 是否被波及——WorkBuddy 判「不受影响」，需源码/文档级确认）。
- [ ] Play「Device and Network Abuse」政策 vs 三种 UML 分发形态的合规矩阵：①内核 .so 随扩展包；②运行时下载内核（高风险，确认禁止）；③rootfs 首次下载（数据）。给出风险分级。
- [ ] 国内商店（华为等）对「模拟器/内核类 App」的限制现状（WorkBuddy 已引华为文档，核实具体条款）。
- [ ] GPL 先例：仍无「GPL-2.0 内核随 APK 分发」的公开先例则如实记录 [未找到证据]，不自造。

## 交付

- `docs/research/2026-11-XX-platform-policy-matrix.md`；
- 对 `ACR-draft-four-lanes-20261029.md` §6（未取数清单）的逐项更正建议。

## 停止条件

- 页面不可达/口径仍冲突：并列两说，标注来源与访问日期，不裁决。

## Comments

- 2026-10-31 / CodeBuddy（ask-matt 轮）：建票。
- 2026-10-31 / CodeBuddy（fabric-batch1 轮）：resolved。五项必答闭环：①16KB 两口径收敛为时间线演进（2025-11-01 提交合规 → 2026-05-31 宽限到期 → 2027-02-01 更新硬截止，以 developer.android.com 当前页为权威；GitHub/F-Droid 无提交门槛）；②Android 17 三项：MemoryLimiter 为 cgroup v2 软限制、12GB 档 visible 8GiB（2G guest 富余，≤8GB 后台压线，非准入阻塞）、loopback 禁令仅跨 profile（当前零影响，targetSdk 37 需 ACCESS_LOCAL_NETWORK）、Safer Native DCL—C 仅 System.load + targetSdk≥37（nativeLibraryDir exec 链路判定未被推翻）；③Play 矩阵：内核 .so 随包=低、运行时下载内核=高（禁止）、rootfs 下载=中；④华为无模拟器明文禁令（[未找到证据]），root 类不收录、备案硬门槛；⑤GPL 先例：GitHub 渠道已找到（drolin GPL-2.0 / Stryker），Play 渠道 [未找到证据]。交付：docs/research/2026-11-02-platform-policy-matrix.md。未验证项：Play Console 实际拦截行为（无账号）、ART/linker 实现级射程（源码窗口）、华为以外国内商店、F-Droid 政策（文档 §6/§8）；ACR §6 八项判定见文档 §5。停止条件执行记录在文档 §8（support.google.com 直连不可达，降级缓存逐字取证）。

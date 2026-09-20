# 编排审计：当前完成度、Ticket 26 分支与防御性 handoff

- 背景 / scope：核验 DroidLayer 当前规划、主线、Ticket 25/26、tracker、报告、worktree 与后续执行条件；阅读用户提供的两篇 ZCode 静默整仓上传文章并把其中的威胁模型转化为 handoff 硬约束。
- 审计日期：2026-09-19（Asia/Shanghai）
- 原始审计基线（2026-09-19）：本文保留当时的产品基线与结论作为历史事实。
- 2026-09-20 reconciliation verification baseline：main = 0edb956e4f707392304d2f30cbafa42ebc99bb2d；origin/main = 78594f3a5abf26b758ab5bee0889e65c468d0b96；已执行用户明确允许的 git fetch origin，origin/main...main = 0 behind / 5 ahead。除该 fetch 和获准的 Actions metadata-only 查询尝试外，本会话未进行网络操作，且未下载 artifact 或完整日志。
- 事实优先级：当前 Git / tracker / 文件 → accepted ADR、spec、CONTEXT → research → handoff → 历史报告。
- 重要范围声明：按用户要求，没有重新检查已经删除的 ZCode 文档、目录、网络流量或本机取证证据；文章只作为防御性设计输入。

## 0. 2026-09-20 治理勘误

- 本文原有 2026-09-19 事实保留；本节补入当前可核验 Git 基线，不把后来的状态倒写为当日事实。
- main tracker 的 Ticket 26 仍为 ready-for-agent。历史原/canonical 分支内的 active 记录不能替代当前已确认的 Worker、owner 或干净 worktree。
- 删除原第 6 节的架构扫描、OS 临时 HTML 与 P3 重构建议：它们不属于本次状态审计获授权 scope，也不构成产品或治理结论。

## 1. 结论先行

DroidLayer 的**规划阶段已完成，产品实现尚未完成**。当前最准确的状态是：

| 领域 | 当前结论 | 证据 |
| --- | --- | --- |
| 产品/架构决策 | 已完成规划基线 | `docs/adr/0001..0016` 全部 `accepted`；`.scratch/droidlayer-platform/spec.md` 为 `ready-for-agent` |
| Guest 资产基线 | 已完成并合入 `main` | Ticket 25 tracker 为 `completed`；`docs/reports/reviews/25/review-report.zh-CN.md` 为 `approve`；`b9df782` 之后可达主线 |
| Android 主线 | 仍是 bootstrap + Guest 资产基线 | reconciliation baseline main@0edb956e4f70 干净；主线仍未包含 Ticket 26 的 runtime/bridge/terminal/QEMU 实现 |
| Ticket 26 | 分支实现已推进，整票仍未完成 | 独立分支 CI/QEMU 证据通过；报告明确真机六项 `NOT VERIFIED`；分支未合入 `main` |
| MVP | 尚不可发布 | 网络、生命周期收尾、共享、Debian、Export/resize、AVF 对齐和最终 hardening 尚未完成或尚未验收 |
| 安全防御 | 本次已补入 handoff 硬约束 | `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/`，并由 handoff 与根 `AGENTS.md` 强制指向 |

因此，不能用“Ticket 25 已完成”“Ticket 26 的 CI 全绿”或“16 个 ADR 已接受”来替代 Android 真机端到端验收，也不能把 Ticket 26 分支进度当成 `main` 已交付。

## 2. Git、worktree 与 tracker 核验

### 2.1 主线

- 当前工作区：registered main worktree（绝对宿主路径不在本报告保留）
- branch：`main`
- 产品基线 HEAD：`78594f3a5abf26b758ab5bee0889e65c468d0b96`
- 治理变更起始提交：`b957103`；该提交只增加 handoff/审计文档，未改变产品代码。
- 2026-09-20 Phase 0（本 session 写入治理文档之前）的 fetch 后 snapshot：origin/main = 78594f3a5abf26b758ab5bee0889e65c468d0b96，main = 0edb956e4f707392304d2f30cbafa42ebc99bb2d，本地 0 behind / 5 ahead；本文不声称已推送。
- 同一 Phase 0 snapshot 的 `git status --short --branch`：clean。随后本 session 产生 tracker、withdrawn packet、本文修订与两份 orchestration report；它们在双轴审查和本地提交前故意保持未提交，当前集合见 `ticket-26-governance-reconciliation-2026-09-20.zh-CN.md` §6–§7。
- 最近主线提交以编排治理、目录治理和 Ticket 25 集成为主；没有把 Ticket 26 的产品代码合入主线。

### 2.2 Ticket 26 独立 worktree

- physical / registered worktree：稳定别名 ticket-26-original（绝对宿主路径不在本报告保留）
- branch：`Nyzeep/ticket-26-alpine-shell-qemu`
- HEAD：`7194304f59b90eb0fd34457bff806f0f45524551`
- worktree：clean；无 upstream；无 untracked 文件。
- merge-base：`82e4bef2fd4ce071fb9da562b6c81571e9d2e466`
- 2026-09-20 相对 main：ahead 80、behind 9。
- 该分支包含 runtime、bridge、terminal、QEMU Android build tooling、二阶段 guest smoke 与 CI 修复；这些内容在当前 `main` 上不可见。

### 2.3 Tracker 的真实状态与不一致

直接读取 `.scratch/droidlayer-platform/issues/` 得到：

- 01–16、19–24：`resolved`；
- 17、18：`open` prototype；
- 25：`completed`；
- 26：`ready-for-agent`；
- 27–37：`needs-triage`。

Ticket 26 的历史原/canonical 分支 tracker 与报告使用过 active，而 main tracker 仍为 ready-for-agent。当前未确认获授权的 active Worker、canonical owner 或可启动的干净 worktree；因此分支提交不能被解释为当前状态转换。本修订只登记勘误、撤回旧 dispatch，并且不合并分支、不启动下游票。

## 3. 完成度分层

### 已完成

1. 领域词汇、MVP 产品边界和 16 个 ADR 已建立。
2. Guest Kernel / initramfs / Provisioning 基线已通过 Linux builder 的双 clean build、config negative gate 与 QEMU Provisioning smoke，并合入 `main` 作为 Ticket 25。
3. Ticket 26 的若干高风险准备工作已形成可审查实现：runtime asset manifest、QEMU 首次装配参数、单根盘所有权锁、Guest Bridge/terminal 基座、QEMU Android source/NDK lock、供应链负例和 guest 二阶段 QEMU smoke。
4. 2026-09-18 Ticket 26 分支报告记录的 full verify `35368258350` 与 artifact-reuse `35378312353` 均为 success，且 source commit 对应最终代码点；这证明的是 CI/QEMU 范围，不是 Android 真机范围。

### 未完成

1. Ticket 26 的六项真机验收仍是 `NOT VERIFIED`：provision-and-run、错误 token、terminal PTY/RESIZE、guest 自主关机、同根盘 `stage=run`、真机 devpts/PTY。
2. Ticket 26 分支没有合入 `main`，也没有形成整票 `completed` 结论；其报告还保留 QEMU/virtio terminal 投递偶发 flake 的历史证据。
3. 当前主线仍没有可交付的 Android runtime UI、QEMU APK native 交付闭环或实际 Android 端到端证明。
4. 27–37 的功能轨道没有被当前审计放行；17/18 两个 prototype 仍 open。不能用静态 policy、JVM 测试或 QEMU builder 代替真实设备、网络、AVF 和性能证据。

### 主线轻量验证

执行：`./gradlew.bat test --offline --no-daemon`

结果：**未进入测试执行**。Gradle 因本机离线缓存缺少 `com.android.application:8.13.2`，在 plugin resolution 阶段失败；本次没有为了补依赖而联网下载、修改 PATH、修改 SDK 或写入系统范围资源。因此该结果应标为“验证未运行”，不能写成测试通过，也不能归因于产品行为失败。

## 4. 从文章提炼的安全威胁模型

只取可迁移的工程风险，不把文章中的外部个案当作本项目事实：

1. **范围风险**：用户以为工具只处理对话上下文，实际后台快照可能覆盖整个 workspace、`.git`、LFS、reflog 和配置。
2. **授权风险**：UI 中“训练/索引/体验”开关不一定控制真实传输；服务端下发凭证或开关不能成为本地用户授权的替代品。
3. **密钥风险**：加密、压缩和对象存储直传仍可能让服务端单方面解密；密文不可由用户读取不等于没有泄露。
4. **重试风险**：删除 pending 包不是停止机制；失败队列可能重新扫描、重打包并续传。
5. **历史风险**：删除当前文件不清除 Git 对象、LFS、reflog、旧提交、内部 URL 或历史凭据。
6. **响应风险**：事件发生后最重要的是停止新增外发和轮换凭据，而不是争论“是否真的上传”或只做卸载。

本次新增的防御文档将这些风险转为正向可执行规则：主机数据出站默认拒绝、端点和范围本地可判定、服务器响应不能越权、失败不静默重试、事件按已可能外泄处理。

## 5. 本次落地的防御性 handoff 变更

新增稳定目录：`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/`

- `README.md`：强制读取、变更门、适用范围和每个任务的最小安全门。
- `DATA_EGRESS_AND_CONTENT_BOUNDARY_POLICY.md`：禁止静默整仓/历史收集，定义主机侧数据默认拒绝、显式传输门、敏感内容排除、guest NAT 例外和负例测试要求。
- `INCIDENT_RESPONSE_AND_CREDENTIAL_ROTATION.md`：疑似越界外发后的停止、保全元数据、凭据轮换、影响评估、修复和放行条件。

入口同步到：

- `AGENTS.md`：根级高触发指针；
- `docs/agent-handoff/ENGINEERING_HANDOFF.md`：版本 1.3、§0A 强制读取、当前 Git/tracker 快照、文档索引和接管 checklist。

这些文档没有修改 ADR、spec、CONTEXT、Guest Bridge 命令、Exposure Whitelist、产品代码或 ticket 状态。以后若要放宽约束，必须提交明确的安全优化方案并独立审查，不能按 ticket 临时编辑。

## 6. 修订后的范围界限

架构扫描、临时 HTML 及 P3 重构建议已从本状态审计移除。它们没有得到本次治理任务的授权，且不应与 Ticket 26 状态、worktree 选择或真实验收相混淆。

## 7. 修订后的唯一后续动作

先完成 Ticket 26 governance reconciliation 与 branch convergence audit，确认是否存在获授权的 active Worker、canonical owner 和可启动的干净 worktree。只有这些前置条件被明确登记后，下一会话才可按受限 diagnosing-bugs dispatch 建立 terminal relay 红色反馈环；未确认前不得开始产品实现、真机执行或下游票。

## 8. 修改、测试与越界检查

### 原 2026-09-19 审计修改文件（历史记录）

- `AGENTS.md`
- `docs/agent-handoff/ENGINEERING_HANDOFF.md`
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md`
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/DATA_EGRESS_AND_CONTENT_BOUNDARY_POLICY.md`
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/INCIDENT_RESPONSE_AND_CREDENTIAL_ROTATION.md`
- 本报告

### 2026-09-20 修订修改文件

- `.scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md`
- `docs/reports/tickets/26/dispatch-packet.zh-CN.md`
- `docs/reports/orchestration/current-state-audit-2026-09-19.zh-CN.md`
- `artifacts/26/evidence/2026-09-20-governance-scope-deviation.md`
- `docs/reports/orchestration/ticket-26-governance-reconciliation-2026-09-20.zh-CN.md`
- `docs/reports/orchestration/ticket-26-branch-convergence-audit-2026-09-20.zh-CN.md`

### 原 2026-09-19 验证（历史记录）

- `git status --short --branch`、`git worktree list --porcelain`、主线/分支 HEAD、ahead/behind、upstream、untracked、ignored 和近期 log：已执行。
- tracker 37 个 issue 文件的 `Status`、Ticket 25/26 报告与 review：已执行。
- `git diff --check`：已执行；文档变更无 whitespace error。
- `./gradlew.bat test --offline --no-daemon`：未运行测试，原因已在 §3 记录。
- 架构 HTML / 重构建议：已从本修订版移除；不作为本状态审计输出、证据或后续行动。

### 2026-09-20 修订验证

- Phase 0：已重新核验 tracker 25/26、registered worktree 摘要、main/origin 关系、共同祖先与双向独有提交计数；见本报告 §0–§2 及 reconciliation / convergence report。
- 当前治理 diff：预提交 `git diff --check` 已通过；路径集合限于本节列出的六个路径（含最小 source-controlled incident evidence），未运行 build、CI、ADB、设备操作或 artifact 下载。
- 双轴审查：本次修订将在 Standards 与 Spec 均 ACCEPT 后才提交；本节不把写入后的未提交文档状态倒写为 Phase 0 clean snapshot。

### 2026-09-20 模型、委派、workflow 与 worktree 影响

- session role：governance / integration Orchestrator；workflow 为 code-review。terminal relay 仅形成 diagnosing-bugs dispatch 草案，不启动实现。
- 模型 / reasoning：推荐 GPT-5.6 Sol / High；当前运行时未提供该路由。根据用户当前任务 prompt 中“若环境无法提供，记录实际 fallback，不得自行降低风险结论”的直接条件授权，实际采用 GPT-5.6 Terra fallback；风险结论未降低，也不授权越界修改。
- 委派：独立 Standards 与 Spec reviewer 仅在 main 的五份治理文档及一份最小 source-controlled incident evidence 范围内只读审查；不修改文件、不读取 Ticket 26 WIP、delivery 草案、设备/实例数据或原始 Git 内容。
- worktree / Git：只在 main 写入本节列出的治理文档与最小 source-controlled incident evidence；三个 Ticket 26 worktree 未被修改、暂存、提交、删除、移动或合并。当前 commit 为待双轴 ACCEPT 后的本地中文 Conventional Commit；不 push。

### 越界与 Architecture Change Policy

原 2026-09-19 审计没有修改产品实现、ADR、spec、CONTEXT、tracker 状态、依赖图、Guest Bridge 命令、Exposure Whitelist、宿主系统或外部运行时。本次 2026-09-20 修订仅校正治理描述、撤回越界 dispatch 并移除未授权架构扫描；未改变产品架构，Architecture Change Policy 未触发。

历史报告人：Codex ｜ 日期：2026-09-19 ｜ 耗时：未独立计时
报告人：Codex Orchestrator / GPT-5.6 Terra fallback（2026-09-20 修订） ｜ 日期：2026-09-20 ｜ 耗时：未独立计时

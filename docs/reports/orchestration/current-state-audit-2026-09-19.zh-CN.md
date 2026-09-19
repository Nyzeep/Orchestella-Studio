# 编排审计：当前完成度、Ticket 26 分支与防御性 handoff

- 背景 / scope：核验 DroidLayer 当前规划、主线、Ticket 25/26、tracker、报告、worktree 与后续执行条件；阅读用户提供的两篇 ZCode 静默整仓上传文章并把其中的威胁模型转化为 handoff 硬约束。
- 审计日期：2026-09-19（Asia/Shanghai）
- commit：本次治理文档与审计报告由 `docs(handoff): add defensive constraints and state audit` 记录；精确 SHA 以提交后的 Git history 为准。
- 事实优先级：当前 Git / tracker / 文件 → accepted ADR、spec、CONTEXT → research → handoff → 历史报告。
- 重要范围声明：按用户要求，没有重新检查已经删除的 ZCode 文档、目录、网络流量或本机取证证据；文章只作为防御性设计输入。

## 1. 结论先行

DroidLayer 的**规划阶段已完成，产品实现尚未完成**。当前最准确的状态是：

| 领域 | 当前结论 | 证据 |
| --- | --- | --- |
| 产品/架构决策 | 已完成规划基线 | `docs/adr/0001..0016` 全部 `accepted`；`.scratch/droidlayer-platform/spec.md` 为 `ready-for-agent` |
| Guest 资产基线 | 已完成并合入 `main` | Ticket 25 tracker 为 `completed`；`docs/reports/reviews/25/review-report.zh-CN.md` 为 `approve`；`b9df782` 之后可达主线 |
| Android 主线 | 仍是 bootstrap + Guest 资产基线 | `main@78594f3` 干净；主线尚未包含 Ticket 26 的 runtime/bridge/terminal/QEMU 实现 |
| Ticket 26 | 分支实现已推进，整票仍未完成 | 独立分支 CI/QEMU 证据通过；报告明确真机六项 `NOT VERIFIED`；分支未合入 `main` |
| MVP | 尚不可发布 | 网络、生命周期收尾、共享、Debian、Export/resize、AVF 对齐和最终 hardening 尚未完成或尚未验收 |
| 安全防御 | 本次已补入 handoff 硬约束 | `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/`，并由 handoff 与根 `AGENTS.md` 强制指向 |

因此，不能用“Ticket 25 已完成”“Ticket 26 的 CI 全绿”或“16 个 ADR 已接受”来替代 Android 真机端到端验收，也不能把 Ticket 26 分支进度当成 `main` 已交付。

## 2. Git、worktree 与 tracker 核验

### 2.1 主线

- 当前工作区：`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev`
- branch：`main`
- HEAD：`78594f3a5abf26b758ab5bee0889e65c468d0b96`
- `origin/main` 与本地 `main` 同步。
- `git status --short --branch`：clean。
- 最近主线提交以编排治理、目录治理和 Ticket 25 集成为主；没有把 Ticket 26 的产品代码合入主线。

### 2.2 Ticket 26 独立 worktree

- physical / registered worktree：`D:\Development project(开发项目)\DroidLayer开发目录\ticket-26-alpine-shell-qemu`
- branch：`Nyzeep/ticket-26-alpine-shell-qemu`
- HEAD：`7194304f59b90eb0fd34457bff806f0f45524551`
- worktree：clean；无 upstream；无 untracked 文件。
- merge-base：`82e4bef2fd4ce071fb9da562b6c81571e9d2e466`
- 相对 `main`：ahead 80、behind 4。
- 该分支包含 runtime、bridge、terminal、QEMU Android build tooling、二阶段 guest smoke 与 CI 修复；这些内容在当前 `main` 上不可见。

### 2.3 Tracker 的真实状态与不一致

直接读取 `.scratch/droidlayer-platform/issues/` 得到：

- 01–16、19–24：`resolved`；
- 17、18：`open` prototype；
- 25：`completed`；
- 26：`ready-for-agent`；
- 27–37：`needs-triage`。

Ticket 26 的独立分支报告从实现阶段起使用 `active`，并且当前确有 80 个相对 `main` 的提交；tracker 文件仍为 `ready-for-agent`。这是治理事实不一致，不是可以由 Agent 自行猜测的状态转换。本次只在 handoff 和本报告登记，没有擅自修改工单状态、合并分支或启动下游票。

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

## 6. 架构扫描候选

本次按 `improve-codebase-architecture` 与 `codebase-design` 词汇做了只读扫描；没有提前设计接口。候选的完整 before/after 图在 OS 临时报告 `architecture-review-20260919.html` 中。

| 候选 | 证据 | 深化方向 | 推荐度 |
| --- | --- | --- | --- |
| Guest asset build orchestration | `guest-assets/build.sh` 同时承担多个上游构建、装配、打包与 manifest；Ticket 25 review 已记录 `Repeated Switches` / `Divergent Change` | 把构建计划、输入锁和阶段结果收拢到一个更深的编排模块；脚本成为 adapter，测试穿过一个小 interface | Worth exploring |
| QEMU launch + runtime assets | `QemuRuntimeAssets`、`RuntimeArtifactManifest`、`QemuCommand`、`QemuProcessLauncher` 分担校验、路径、锁和启动；真正启动闭环仍在跨 seam 组合 | 形成一个更深的启动模块，把“可信资产→私有路径→单实例所有权→进程退出”集中；为测试保留本地 substitutable adapter | **Strong** |
| Guest Bridge / terminal data path | Java bridge、QEMU virtio-serial、initramfs vport resolver、C guest-agent、PTY 跨语言；报告保留投递 flake | 让协议/传输结果在一个更窄的 seam 汇合，测试以 observable terminal/resize/handshake 结果为 surface | Worth exploring |

首推第二项，因为它同时连接 Ticket 26 的安全 gate、生命周期 ownership 和后续 27/28/34 的复用；深化它能带来较高 leverage 与 locality，但必须等用户选择后再进入 grilling / design。

## 7. 建议的后续顺序

### P0：先治理状态，不写新产品功能

1. 由 Orchestrator/用户确认 Ticket 26 的状态语义：tracker `ready-for-agent`、分支报告 `active`、当前 branch 未合入 `main` 三者如何收口。
2. 维持 `main` 与 Ticket 26 worktree 的隔离；在状态、范围、独立 review 和用户授权明确前，不 merge、不启动 27/28/30。
3. 后续每个 session 开始前读取防御性硬约束目录；任何新网络/I/O 先提交端点、数据分类和负例测试计划。

### P1：完成 Ticket 26 的真实设备门

在用户提供/授权的 arm64 真机上重新核验 API、ABI、RAM、存储、ADB 和日志通道，然后逐项运行报告中的六项真机验收。QEMU、CI、模拟器和 JVM 结果都不能替代它们。若真机行为推翻 ADR 或 Full Linux 契约，停止并走 Architecture Change Policy。

### P2：按依赖图推进 MVP

Ticket 26 真正收口后，再按 `25 → 26 → (27 ∥ 28 ∥ 30) → (29 ∥ 31 ∥ 32 ∥ 33) → 34 → (36 → 37) → 35` 推进；每张票先把 tracker 状态、ownership、artifact 路径和安全 egress 清单写进 dispatch packet。Ticket 18 的 TCG 性能基线应在已有 QEMU 路径稳定后单独产出原始数据，不能用墙钟时间替代性能验收。

### P3：选择一个架构深化候选

推荐先选“QEMU launch + runtime assets”，随后再开始候选的 grilling；如果用户更关心近期 terminal flake，则先选 Guest Bridge / terminal data path。架构扫描只产生候选，不自动授权重构或改变现有 ticket scope。

## 8. 修改、测试与越界检查

### 修改文件

- `AGENTS.md`
- `docs/agent-handoff/ENGINEERING_HANDOFF.md`
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md`
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/DATA_EGRESS_AND_CONTENT_BOUNDARY_POLICY.md`
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/INCIDENT_RESPONSE_AND_CREDENTIAL_ROTATION.md`
- 本报告

### 验证

- `git status --short --branch`、`git worktree list --porcelain`、主线/分支 HEAD、ahead/behind、upstream、untracked、ignored 和近期 log：已执行。
- tracker 37 个 issue 文件的 `Status`、Ticket 25/26 报告与 review：已执行。
- `git diff --check`：已执行；文档变更无 whitespace error。
- `./gradlew.bat test --offline --no-daemon`：未运行测试，原因已在 §3 记录。
- 架构 HTML 报告：已生成到 OS temp；未写入仓库。

### 越界与 Architecture Change Policy

本次没有修改产品实现、ADR、spec、CONTEXT、tracker 状态、依赖图、Guest Bridge 命令、Exposure Whitelist、宿主系统或外部运行时；没有访问网络，也没有重新验证 ZCode 资料。防御性目录是用户明确要求的 handoff 治理增强，不改变既有产品架构，因此未触发 Architecture Change Policy。

报告人：Codex ｜ 日期：2026-09-19 ｜ 耗时：未独立计时

# Ticket 26 历史 worktree 文档误读事件报告

- 事件日期：2026-09-23（Asia/Shanghai）；精确触发时刻未在本轮获准的元数据中确认。
- 报告性质：最小范围正式事件记录；不是 Ticket 26 的 A–E 验收、恢复授权或完成证明。
- 责任范围：本次 Ticket 26 guest-assets 验证准备的只读 child 误读历史 worktree 文档；Orchestrator 负责停止、记录和升级。
- 分支/基线：`ticket/26-linux-qemu-rebuild`，HEAD `e0fc8b97ab6c8ba434fc8fcfa16ed1a87da1c12e`，与 `main` 的 merge-base `8859234a7cd922d4a99385874d1638f31e20c518`；本报告不修改产品代码。

## 触发与时间线

1. Orchestrator 派出一个只读 guest-assets 验证准备 child，限定其读取当前 Ticket 26 worktree、指定 Ticket 25 资产记录和必要政策，不得读取历史 Ticket 26 worktree 内容；禁止写入、联网、构建、设备操作和再委派。
2. child 在完成通知中主动披露：定位当前 full-closeout dispatch 之前，误读了被明确禁止的 `ticket-26-canonical` 历史 worktree 内的 policy、工单和 dispatch 文档；还读取了独立治理 worktree 的 policy/dispatch 文档。获准的历史 worktree 操作本应仅限 Git worktree 注册元数据，不含其文件内容。具体读取命令、文件数、字节数和精确发生时刻未由本轮获准元数据独立确认；本报告不重列完整路径清单，也不重开禁区追查。
3. child 同时自报未读取这些 worktree 的 `.git`、凭据、源码或产物，未写入、联网、构建或操作设备；这只能证明它作出了上述陈述，不能证明不存在其他读取、模型服务传输或保留。
4. Orchestrator 收到披露后停止继续派工和 A–E 实施，在现有 `docs/reports/tickets/26/implementation-report.zh-CN.md` 追加最小事件记录，并向用户披露。用户随后明确要求继续冻结，只授权本轮最小评估和正式记录。
5. 本轮写入前重新核对注册/物理 worktree、branch/HEAD/merge-base、ahead 2/behind 0、无 upstream、最近提交、staged/tracked/untracked/ignored 和 `git diff --check`。101 项 waiver 与外部固定清单逐路径仍为 tracked unstaged deletion；其外原 13 项 tracked 修改路径指纹 `78933e344ef4121f97cbcced2e1408fff51e317fb6b5b887bd2ccff7f2dc61a0`，原 37 项 untracked 路径指纹 `22e4672a8bf562a17d8f2a7fa242b829bdc0ea0e9491a530b3f585d3b47d0a25`；第 38 项已批准 dispatch 为 22,261 bytes、SHA-256 `cea88fb42784fd73fe2b57faf3dd372bb525f02a702d6f22acf086421630028c`。staged/ignored 为 0，diff check 无输出。没有来源不明的 source 路径增量；工作树有意 dirty，绝非 clean。本报告是本轮唯一新增的 untracked 路径。

## 范围和影响分类

- 已自报的越界对象是**历史 Ticket 26 worktree 的文档内容**，而不是仅有的 worktree 注册元数据。文件类别限于 policy、工单和 dispatch；另有治理 worktree 的同类文档读取。它们属于项目主机侧内容/元数据，可能包含内部路径、分支和项目治理信息；不把它们视作公开资料，也不在本报告复制原文。
- 已确认的事件事实是 child 在完成通知中披露了上述越界及 Orchestrator 随后的冻结/报告行为。未获得独立文件访问日志，不把 child 的否定陈述升级为取证证明。当前未发现实际凭据内容、未知出站端点、上传载荷或比自报更广的读取证据；**未发现证据不等于已证明未发生**。
- 不能确认 child 实际读取的完整文件/字节范围、模型提供方接收与保留的精确内容、服务端日志、潜在接收方或删除语义。若无法证明未外发，按“可能已外泄的项目文档内容/元数据”评估残余风险；没有依据断言 `.git` 历史或凭据已外泄，也不能断言它们绝未被访问。
- 该 child 的 guest-assets 准备意见、脚本语法结论及任何其他输出均不得作为 Ticket 26 阶段 gate 证据。没有将历史 worktree 的内容再次读取、复制到本报告或用于产品实现。

## 证据、动作与限制

| 脱敏证据 | 来源与可证明事项 | 完整性/限制 |
| --- | --- | --- |
| 本票现有 `docs/reports/tickets/26/implementation-report.zh-CN.md` 末尾事件段 | Orchestrator 在 child 披露后记录冻结、child 自报和待裁决事项 | 本轮写入前 SHA-256 `d9d1d838c7b0e904975b53badf4095dbfef796556d2b7a2305cf22b1bf514b31`；该 hash 仅证明当前工作树中报告字节，不独立证明 child 的文件访问范围。 |
| 本会话中 guest-assets child 的完成通知与自报 | 披露文档类别、两个 worktree 别名和 child 自述的未做事项 | 仅使用当前会话已收到的最小工具调用元数据；没有访问 C: 用户目录中的 agent transcript/output，也没有可供本报告声称的原始 child 日志 hash。 |
| 外部 waiver TEMP 和原 Ticket 26 Git 状态摘要 | 证明事件报告写入前的 baseline-aware ownership 不变 | waiver 只用逐路径本地比较，不复制其 101 项完整路径；本报告不提交 TEMP。 |

发现后没有继续运行 Gradle、SDK/QEMU、CI、ADB 真机操作或发布，没有重新派发子任务，没有打开历史 worktree 再次“确认”，没有删除/覆盖旧证据，也没有变更系统安全设置。本轮未访问网络、C: 用户目录、凭据、服务端日志或新资源。现有旧镜像、TEMP、构建/失败日志、child 完成通知及本票报告保持原状态；原始敏感证据若需进一步保全，只能在另行明确范围与宿主披露后放入批准 external root，不粘贴进 Git 报告。

## 凭据、通知和修复判定

- 凭据：目前没有识别出某项具体凭据实际出现在已自报读取类别或已获准的脱敏元数据里；本轮未读取凭据、未轮换。不能据此宣告“无需轮换”。Orchestrator 负责记录凭据评估和待轮换事项；若后续获准证据出现 API key、CI token、Git 凭据、私钥或签名材料，按影响范围将其视为已泄露并协调凭据所有者撤销/轮换，不在报告中记录值。事件报告、轮换状态与残余风险须由用户/维护者确认。
- 通知：用户/Orchestrator 已在本会话获知事件。Orchestrator 负责依据实际影响范围协调仓库所有者、凭据所有者的必要通知，记录通知对象、时间和未完成项；当前没有可确认的具体凭据所有者或外部接收方名单，因此未向第三方发送报告或工作区内容，也不虚称已完成后续通知。超出本轮授权的外部联系仍须先取得精确批准。
- 修复：本轮只完成事实边界和正式记录；没有修改 dispatch、Agent 工具、模型服务、策略、产品代码或事件相关自动化。恢复前须重新约束 child 的目标路径、对历史 worktree 采用默认拒绝并以受控负例证明，核对是否还有未声明的读取/转发路径；若需访问 C: 用户目录、服务端日志或真实凭据，先逐项按 `HOST_ENVIRONMENT_POLICY.md` 披露并取得精确授权。对实施/构建链/交付物的独立审查仍须在其相应阶段进行，不能被本报告替代。
- 独立审查：未参与误读的安全/治理 reviewer 仅核对本报告和获准的脱敏元数据，未重开历史 worktree。首轮 verdict 为 `request-changes`（通知协调与放行权限表述不清）；作者据此修订凭据/通知与放行责任段落后，reviewer 对修订版给出 `approve`，**仅限报告文本**。reviewer 未独立核验 child 的实际访问、模型服务传输或留存，review 结果不是用户对风险的确认，也不放行 A–E。
- 架构变更门：本次误读是 Agent 操作/内容边界事件，尚无证据要求修改 accepted ADR、spec、Full Linux 契约或暴露面白名单；未触发产品架构变更流程。若后续修复需改变这些约束，先按 `ARCHITECTURE_CHANGE_POLICY.md` 升级。

## 状态、残余风险与放行

Ticket 26 的 A 仍未通过（固定 UTF-8 配置下单测 worker RED、任务级 SDK/Maven 闭包未完成）；B–E 及七条真机 AC 未验收。实施、构建、设备、发布与新的任务委派继续冻结；本事件报告完成**不自动解除冻结**，tracker 不标 `completed`，不启动 Ticket 27–37。独立治理分支的既有 `ee409e22bc4c488a7d863c9b1e911623db62768a` 提交与本事件报告/产品 gate 分离；本轮没有 commit、fetch、push 或 PR。

残余风险：缺少独立的访问与传输/保留取证，故只能界定“自报的文档类别”和“未发现额外证据”，不能证明读取/出站范围的上界。历史 worktree、原始日志、TEMP、旧镜像、waiver 删除路径与本次新报告均不清理；cleanup owner 为 user/orchestrator。Orchestrator 负责协调通知、登记凭据评估及修复/审查待办，不能单独解除冻结。事件报告、凭据轮换状态和残余风险由用户/维护者确认；Ticket 26 A–E 只有在用户另行**明确授权恢复**后才能继续。待用户确认的是：是否接受这一级证据下的风险，是否批准更深取证及其具体路径/端点，凭据与通知处置，以及独立审查与防再发负例的范围。本报告不代签、不自动放行。

报告人：Codex ｜ 日期：2026-09-23 ｜ 耗时：未单独计时

## 2026-09-23 用户风险确认与防再发实验增量

用户已阅读本报告并明确接受目前“项目文档内容/元数据可能已外泄、实际范围不可证实”的残余风险；这不是证明没有其他读取、外发或服务端留存。用户是本事件已知通知接收人；目前未识别出实际暴露的具体凭据，不作无对象批量轮换。Orchestrator 登记此判定；若出现具体凭据、其他受影响所有者、未知端点或更广读取，立即停止，按影响范围协调通知/撤销/轮换并交用户裁决。上述未来动作尚未发生，不记录为已完成。用户的授权是**条件式**的：先完成可强制执行的本地默认拒绝与合成负例、独立安全/治理审查、无扩大影响新事实、完整 baseline-aware gate，之后才可解除本事件冻结；文本 review 和风险接受本身均不足以放行。

本轮在批准的 Ticket 26 external root `artifacts/26/android-toolchain-build-20260922/evidence/` 创建精确文件清单：

| 本轮授权创建的外部证据（相对 evidence/） | SHA-256 | 用途 |
| --- | --- | --- |
| `ticket26-child-read-policy-20260923.json` | `ac753ec115bc99908def17d2312ae278ddc2615c027d787915bd9647da336167` | 当前 worktree 六个精确文档允许读取、历史 worktree 明确禁止、其他路径默认拒绝；无源代码或凭据内容。 |
| `ticket26-scoped-read-20260923.mjs` | `5d9130d84b9357da4607d54910b3be827d14e9ea1e71338ce3748aadeeb577d9` | 只约束经此 CLI 发出的文件读取；拒绝未列项、穿越、链接/junction、非文件与过大文件。 |
| `ticket26-scoped-read-test-20260923.mjs` | `a3f7c9d25d1c76eb72953abfd3ed587cbe05639d9ad8f2ff11a4d3af3abc3e83` | 合成路径/重解析点负例与绕过实验。 |
| `ticket26-scope-guard-verdict-20260923.md` | `79e60f9580e25a81c3e827d87636242314f98cb47310688e6a8027fedbb74995` | 脱敏测试结论及原生 Read 对合成拒绝文件的旁路实证；先前 `92db4cd7…` 是补充该证据前的字节哈希。 |

测试仅使用外部 root 内的合成 fixture，未打开历史 worktree：本地 Node 24.19.0 下，15 个断言通过（一个精确允许，14 个拒绝）；覆盖未列路径、伪前缀、`..`/`.`、禁止根、`.git`/`.env`、缺失输入、junction、drive-relative、UNC 与 ADS。固定策略 CLI 对当前事件报告 `ALLOW`，对历史路径在词法判断阶段 `DENY denied-root`，没有调用读取历史文件。Windows 对文件 symlink 创建返回 EPERM，因此**文件 symlink 负例未运行**；同盘 junction 的 reparse 拒绝真实通过。最初两次测试在创建合成 fixture 后分别因 symlink 权限和未运行断言的预期值退出 1，旧 fixture 未删除；最终两次合成 fixture（`-c`、`-d`）和前两次失败 fixture 保留，均只含合成字节。`node --check` 两个脚本通过。早期策略草案曾因日期误写为 `20260924`，在使用前将**本轮创建的同一文件**改名为 `20260923`，未覆盖目标或旧证据。除本报告追加本节外，Ticket 26 源码/工单、waiver、既有 TEMP、SDK/cache、旧镜像、历史 worktree 与宿主安全设置均未修改。

**关键阻断与诚实边界：**同一合成测试证明，普通 `fs.readFileSync` 不经过上述 reader 仍可读取合成的被拒文件；本会话直接调用原生 `Read` 工具也确实读取了 `-d/outside/secret-sentinel.txt` 的合成文本，虽然该路径被策略拒绝。这里没有读取任何真实禁区或秘密。当前 Agent/Child 工具没有把其 Read/Bash/其他文件读取强制路由到此 CLI，也不能在本轮授予的当前 worktree/external root 内为 Agent 施加 OS 级权限隔离。因此该 reader **不是后续 child 的强制默认拒绝边界**，现有负例只是经 reader 调用的行为证据，不能宣称已防再发；不派出未隔离的 child，亦不为取得审查结果而让 reviewer 再次持有不受限文件工具。未访问 C: 用户目录、服务端日志或凭据；未发现新的扩大影响事实，但这不证明没有发生。当前没有可据实批准该措施的独立 reviewer verdict。

### 事件响应政策 §5 的逐项适用性与 verdict

| 条件 | 本事件适用性与证据 | 结果 |
| --- | --- | --- |
| 本地默认拒绝和服务端不可绕过 | 精确 allowlist reader 对经它发起的读取确实拒绝越界；未被 Agent 工具强制采用，合成原生读取已证实旁路。服务端策略与保留语义未取证，不可声称不可绕过。 | **未通过** |
| 数据范围、端点、取消、失败/重试语义 | 已限定本地策略文件的读取范围，CLI 不实现网络上传、后台队列或重试；因此产品远程传输的取消/endpoint 不适用于这个本地 reader。但 Agent 模型服务的接收/保留/取消边界未知，不得把“本地无网络代码”写成系统级零出站。 | **部分明确；系统级未证** |
| `.git`、秘密、扫描/打包、日志泄漏负例 | 合成 `.git`/`.env` 和 junction/穿越拒绝已执行；脚本不含目录扫描/打包/日志内容输出（拒绝仅输出类别）。文件 symlink 未能创建，且原生工具读取可绕过；不能宣称对所有 child 工具的 `.git`、secret 或出站负例已通过。 | **局部通过；整体未通过** |
| 运行时/构建/交付物独立审查 | 本次是 Agent 工作范围误读而非产品运行时、构建或交付物改动；这三类 artifact 不是本事件修复对象，在 A–E 对应阶段仍独立验收，不在本事件借题重跑。与本事件相关的 scope enforcement 和证据须由独立安全/治理 reviewer 审查；当前由于工具未隔离，不向其派发可旁路的 Agent。 | **事件相关审查未运行** |
| 事件报告、凭据、残余风险由用户/维护者确认 | 用户已确认现有事件报告的残余风险且目前无已识别具体凭据，故不作无对象轮换；本次新 reader 旁路风险尚须再次明确裁决或通过真正强制隔离消除。 | **既有风险已确认；新阻断未放行** |

复工条件未满足，A–E 及构建、ADB、发布和新的不受限委派继续冻结；本轮没有执行 A–E 测试或更新 tracker/commit。基线在本轮脚本创建后只读复核仍为 HEAD/merge-base `e0fc8b97…`/`8859234…`、101 项 waiver 原样、其外 13 tracked/原 37 untracked 路径指纹与本报告旧节一致、第 38 项 dispatch 和第 39 项本报告均为已授权来源，staged/ignored 均 0、`git diff --check` 无输出；该核对**发生在第一份外部策略草案创建之后**，不可倒述为该草案写入前的完整 gate。额外外部文件仅为上表四项和四轮合成 fixture，逐路径保留，cleanup owner: user/orchestrator。四轮 fixture 目录分别是 `ticket26-scope-synthetic-20260923`、同名 `-b`、`-c`、`-d`；每个目录仅含 `allowed/report.txt`（18 bytes、SHA-256 `a48b913e41b5160c5c39164923eabf18843ce91202b727b927975226d5ecdeb1`）与 `outside/secret-sentinel.txt`（17 bytes、SHA-256 `cf5f8ccc33ec73a7efd36e5056d36da0596c2f5c03f78dde4dfe972d103f9d5e`），`-b/-c/-d` 各另有一个仅指向自身合成 `outside/` 的 junction `allowed/junction`；initial 没有 junction。没有这些集合外的 fixture 文件；它们只含合成字节，不按“干净状态”诉求删除。不得将 untracked 机械固定为 39：后续每个授权增量都须逐路径审计。

若要满足“后续 child 实际无法越界读取”，需要一个能对该 child 的**所有**文件工具生效的工具级路径过滤/隔离运行环境，或者可用独立低权限身份与按路径限制的沙箱；本轮 Agent 工具没有提供可配置的 per-child filesystem allowlist。对 C: 用户级配置、系统 ACL、账户/沙箱或服务端审计的任何新增读写，必须先提交 Host Environment Policy 的精确路径、操作、覆盖、清理和风险披露并取得单项授权；本轮没有执行。用户对上述技术硬阻断的处置决定之前，不触发条件式复工，也不把此原型当作安全措施完成。

报告人：Codex ｜ 日期：2026-09-23 ｜ 耗时：未单独计时

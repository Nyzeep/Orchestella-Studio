# 编排报告：Ticket 26 治理修正与状态协调（2026-09-20）

- 背景 / scope：受限 governance / integration session。核验 main、tracker、registered worktree、受控报告、外部资源根迁移记录与 Actions metadata；只修正 main 上的治理/报告事实，不实施、集成或合并 Ticket 26。
- workflow / risk：主 workflow 为 code-review；默认 Risk 3。QEMU 供应链、host↔guest、token、lifecycle 或持久化问题只记录为 Risk 4 升级条件，不在本会话修改。
- 模型 / reasoning：推荐 GPT-5.6 Sol / High；当前运行时未提供该路由。根据用户当前任务 prompt 中“若环境无法提供，记录实际 fallback，不得自行降低风险结论”的直接条件授权，实际采用 GPT-5.6 Terra fallback；风险结论未降低，也不授权越界修改。
- branch / commit / 精确基线：main；本报告将在双轴审查通过后的本地中文 Conventional Commit 中提交，当前未 push。基线 main = 0edb956e4f707392304d2f30cbafa42ebc99bb2d；origin/main = 78594f3a5abf26b758ab5bee0889e65c468d0b96；已执行 git fetch origin，origin/main...main = 0 behind / 5 ahead。
- 数据边界：只保留脱敏 Git 摘要与报告事实；未读取或展示原始 Git 内容、reflog、凭据、token、完整 CI 日志、artifact 内容、实例数据、设备数据或 ticket-26-follow-up WIP 内容。

## 1. 真实状态

| 项目 | 当前事实 | 治理结论 |
| --- | --- | --- |
| Ticket 25 | completed，且为 Ticket 26 已完成依赖 | 不重开、不修改。 |
| main worktree | clean；upstream 为 origin/main；相对 main 0 / 0；ignored 8 项（Gradle cache、Kotlin cache、build intermediates、toolchain、其他开发输出） | Phase 0 后才产生本次未提交治理文档。 |
| main tracker 的 Ticket 26 | ready-for-agent | 当前权威 tracker 状态。 |
| 历史原 / canonical branch tracker | 各自曾记录 active | 分支内本地状态是历史实施线索，不能替代 main 的当前授权。 |
| 获授权 active Worker / canonical owner | 本会话未确认 | 不能将 Ticket 26 改为 active、review 或 completed。 |
| 原 Ticket 26 worktree | clean；相对 main 9 behind / 80 ahead | 保留未合并历史与复现价值。 |
| follow-up worktree | 9 项 tracked WIP、0 untracked；相对 main 2 behind / 86 ahead | ticket-26-follow-up WIP 只读摘要，必须保留。 |
| canonical worktree | 7 项 tracked 修改、4 项 untracked；相对 main 2 behind / 102 ahead | 冻结 delivery 草案与未审查成果，必须保留。 |
| main 推送 | 技术上 0 behind / 5 ahead | 治理 diff 尚未完成本次双轴审查，禁止 push。 |

### Phase 0 最近提交主题（脱敏）

| worktree | 最近提交主题 | 读取范围 |
| --- | --- | --- |
| main | docs(orchestration): 迁移外部资源根至项目目录 | 已查询。 |
| ticket-26-original | docs(reports): finalize current Git closure wording [ticket-26] [skip ci] | 已查询。 |
| ticket-26-canonical | fix(guest-assets): use standalone shellcheck directive [ticket-26] | 已查询。 |
| ticket-26-follow-up | 未查询 | follow-up WIP 数据边界禁止读取 recent log。 |

历史原分支的 2026-09-18 报告记录 Linux/QEMU full verify 与 artifact-reuse success；它们只证明当时对应 HEAD 的 CI/QEMU 范围。canonical 后续代码与未提交内容没有被该历史结果自动验证，Android 真机 Acceptance criteria、APK/native delivery 与 GPL release delivery 都未完成。

## 2. 实际完成的治理修正

1. 在 main tracker 追加勘误，保持 ready-for-agent，明确没有已确认的 active Worker、owner 或获准干净 worktree。
2. 将 Ticket 26 dispatch packet 改为 WITHDRAWN / superseded pending reconciliation：撤回提前派发、删除不稳定的宿主绝对路径，并只保留具备完整字段的非授权 future-dispatch 模板。
3. 修订 current-state audit：补入可核验的当前精确 Git 基线、稳定别名与勘误；删除未经授权的架构扫描、临时 HTML 引用和 P3 重构建议，同时保留历史 CI、tracker 与安全事实。
4. 新建本报告及 branch convergence audit；未改写原分支、canonical、follow-up、ADR、spec、CONTEXT、产品代码或 frozen delivery 草案。

## 3. 未完成条件、CI metadata 与外部资源根

- 网络操作：仅执行用户明确允许的 git fetch origin，以及 GitHub Actions metadata-only 查询尝试。后者返回 CLI exit 1；未下载完整日志或 artifact，故本会话的 Actions 状态为 NOT VERIFIED。除该 fetch 和 metadata-only 尝试外，未进行网络操作。历史报告中的 run ID/结论只作为历史事实，不代替本次查询。
- 未运行 build、CI、ADB、设备操作、artifact 下载或完整日志读取。
- external-resource-root-migration-2026-09-20 与现行 Host / Artifact policy 的路径规则一致：该 source-controlled 报告记录 8 个、共 8,816 bytes 的 Ticket 26 证据无覆盖迁至 canonical-external-resource-root/artifacts/26/implementation/，legacy sibling root 已移除且不是 incident。
  - 证据 / hash：canonical 解释层为该 migration report；报告记录迁移后 8/8 文件均完成 SHA-256 读取，但没有复制 individual hash 值或 aggregate hash。本会话未重新读取 external artifact，故本次 hash 为“不适用”，不能把历史 SHA 读取写成当前复验。
  - custody owner / retention / cleanup：custody owner 是当前 governance Orchestrator（Codex；仅负责迁移记录、保留门和非破坏性处置，不等同未决的 GPL release owner）。证据保留至少到用户指定公开源码地址、发布/维护负责人和三年机制，并完成 Ticket 26 delivery / branch convergence 独立审查。当前 cleanup 或删除明确禁止；后续仅由用户确认的 delivery owner 在新的 cleanup eligibility audit 后决定。
  - 约 3.1 GB 的目标原有内容未被覆盖或删除是用户提供并由 migration report 引用的观察，本会话未重新测量。
- terminal relay：历史诊断记录过 shell spawned 后 sentinel 未抵达 relay 的红色前沿；较早 QEMU/CI 重跑曾通过，但当前 canonical / follow-up 状态没有一个获批准、已运行的 Linux/QEMU 红色反馈环。因此不得宣称根因已定位或问题已修复。
- GPL：公开、版本化的对应源码地址，发布/维护负责人，以及至少三年保留机制均未由用户提供；不得用旧 source-offer 文本或 CI 静态资料冒充完成。
- 真机 / APK：安装到 shell、错误 token、PTY/RESIZE、正常关机与同盘重启、devpts/PTY、nativeLibraryDir execution、ELF/closure/16 KB、签名/tamper 与 release delivery 均未验收。

## 4. 三个 worktree 的保留理由

- follow-up：9 项 tracked WIP 尚未审查，且可能涉及 credential、instance metadata 与 QEMU lifecycle；本会话未读取、修改、暂存、提交或删除它们。
- 原 Ticket 26：有 80 个 main 未包含的线性历史提交，并保留已运行 CI/QEMU 证据的复现价值。
- canonical：有 7 项 tracked 修改、4 项 untracked supporting files 及冻结 delivery 草案；不能因为名称为 canonical 就推定其它成果已安全迁入。

因此，不具备收敛为唯一 canonical worktree 的条件；详见 ticket-26-branch-convergence-audit-2026-09-20.zh-CN.md。

## 5. 当前唯一下一步

在不创建新 worktree、不读取 ticket-26-follow-up WIP 或 frozen delivery 草案的前提下，由用户/Orchestrator 明确登记：

1. 是否有 active Worker；
2. 谁拥有 canonical branch；
3. 哪个已收敛、干净且获批准的 worktree 可用于下一会话。

只有这三个事实被登记后，才可使用已撤回 packet 中的受限 diagnosing-bugs 草案建立 terminal relay 红色反馈环；否则不得开始代码修改或真机执行。

## 6. 修改文件

- .scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md
- docs/reports/tickets/26/dispatch-packet.zh-CN.md
- docs/reports/orchestration/current-state-audit-2026-09-19.zh-CN.md
- artifacts/26/evidence/2026-09-20-governance-scope-deviation.md
- 本报告
- docs/reports/orchestration/ticket-26-branch-convergence-audit-2026-09-20.zh-CN.md

## 7. 测试、风险与后续动作

- 已验证：必读 policy、tracker 25/26 状态、registered worktree 的 branch/HEAD/dirty/untracked/ahead-behind 摘要、共同祖先/独有提交计数、git fetch origin、main 与 origin/main 的关系、外部资源根迁移报告与现行 policy 的一致性。
- 未运行：构建、产品测试、CI、ADB、设备操作、artifact 下载和完整 CI 日志读取。
- 风险：主线尚有未经本次双轴审查放行的治理 diff；不得 push。
- 后续：仅在 Phase 4 的 Standards + Spec 双轴均 ACCEPT、git diff --check 通过、main clean 后，才可作本地中文 Conventional Commit；不 push。

## 8. 越界与 Architecture Change Policy 检查

- 未改变 ADR、spec、CONTEXT、Full Linux 契约、Guest Bridge 命令、Exposure Whitelist、ticket 依赖图或产品代码；Architecture Change Policy 未触发。
- 未 merge、cherry-pick、rebase、reset、stash、clean、删除 branch/worktree、push 或创建 PR。
### 8.1 范围偏差事件记录（按 Incident policy）

- 触发时间：2026-09-20 当前 governance session；Harness 未向本会话公开可审计的精确 wall-clock 时间，故不伪造时间戳。
- 触发 / scope：临时 Git 审计工具的前两次 status 调用错误地对 ticket-26-follow-up worktree 执行了 `git status --ignored --short`；授权范围本应仅为 branch、HEAD、`git status --short` 与 ahead/behind 摘要。
- 影响分类：疑似越界的主机侧 Git ignored metadata 枚举；不是网络外发、build、设备、实例或产品运行时事件。
- 已确认事实：命令结果进入临时工具进程后只被归并为计数；文件名、文件内容与 tracked WIP 内容没有被输出给模型、呈现、复制、写入报告、提交或外发；没有任何文件、branch、worktree、credential 或设备写入。
- 未确认项：不得重新读取原始 session output；因此不能以“未看见”断言不存在敏感 ignored 名称。Harness 的内部会话记录保留语义未在本会话核验。
- 停止动作：立即停止并替换为仅使用允许摘要的 strict audit；随后移除了临时 Git 审计插件。
- 证据位置 / hash：最小脱敏、source-controlled evidence 位于 artifacts/26/evidence/2026-09-20-governance-scope-deviation.md，SHA-256 = 604e795d645cef8c793c2b31ae786916032fd8161d851e15bf8afde4415efa4f。它不含文件名、原始 stdout/stderr、WIP、token、credential 或原始 Git 内容。Harness 原始 session tool-call record 未复制；为避免第二次复制范围外名称，不对其生成或主张 hash。
- 凭据轮换状态：未发现或呈现任何 credential 值，未执行轮换；若维护者在受控 session record 中确认出现 credential，应按 Incident policy 将其视为可能已泄露并另行轮换。
- 修复 / 验证：本治理文档修订记录该偏差；最终 strict audit 只调用允许的 branch、HEAD、`git status --short` 与 ahead/behind 摘要，且当前 `git diff --check` 通过。
- 残余风险 / 放行：无法就 Harness 内部 session record 的保留作出结论；没有 release 或 push 放行人，Ticket 26 和 main push 均保持冻结。
- Architecture Change Policy：未触发；这是工具治理与数据边界事件，不改变产品、ADR、spec、契约或暴露面。
- 指定 ask-matt：本会话未发现可调用的 ask-matt model-invocation 入口；既有迁移报告记录其因 disable-model-invocation 不可用。未假称调用成功，继续按 code-review / diagnosing-bugs 的指定 workflow 处理。

报告人：Codex Orchestrator / GPT-5.6 Terra fallback ｜ 日期：2026-09-20 ｜ 耗时：未独立计时

# 编排报告：工作树生命周期审计、合资格清理与主干同步

- 背景 / scope：一次受限的 governance / integration session；只审计 registered worktree、本地/远端 branch lifecycle 与 main push 资格，不实现或集成 Ticket 26。
- workflow / risk：`code-review`；Risk 3。三个 Ticket 26 候选涉及 QEMU、供应链、host↔guest、安全、生命周期与持久化，按 Risk 4 处理并停止集成。
- branch / 基线 / commit：main 审计基线 `2995513`；本报告提交由 Git history 记录，不在正文伪造自引用 SHA。
- 数据边界：只读取 Git 摘要、tracker 状态、候选固定范围 diff 与治理文档；未读取或展示 `.git` 原始内容、reflog、凭据、token、完整日志、设备数据或实例数据。

## 1. 审计范围与 freshness gate

重新核验了全部四个 registered worktree，并记录 branch、HEAD、upstream、相对 main 的 merge-base / ahead-behind、dirty/clean、tracked/untracked/ignored 摘要与最近提交。没有相信旧 handoff 快照。

- main：clean；跟踪 `origin/main`；审计开始时本地 ahead 3。
- Ticket 26 follow-up：无 upstream；相对 main ahead 86 / behind 0；有 9 个 tracked WIP 修改，无 untracked；必须保留。
- 原 Ticket 26：无 upstream；相对 main ahead 80 / behind 7；clean，但含未合并提交且本票仍 active；必须保留。
- Ticket 26 canonical：无 upstream；相对 main ahead 102 / behind 0；有 tracked、staged 与 4 个 untracked 的冻结 delivery 草案；远端同名 branch 已存在；必须保留。
- ignored 摘要仅按类别记录为 Gradle/Kotlin/toolchain/build/cache/local SDK wiring；未清理、未移动、未提交。

当前 registered worktree 中没有任何一个满足 cleanup 条件，因此没有删除、移动或 prune 任何 worktree。

## 2. 候选表

| 候选 | 已完全合入 main / origin-main | review / 状态 | registered worktree / 未提交 / 未合并 / active 或保留理由 | 结论与动作 |
| --- | --- | --- | --- | --- |
| `ticket/25-guest-assets` | 是 / 是；候选无独有提交 | Ticket 25 `completed`；既有双轴 review `approve`；本次固定范围复审两轴 `ACCEPT` | 无 registered worktree；无未合并提交；非 active；无保留标记 | **可清理**；已用 `git branch -d` 删除本地 branch ref |
| `Nyzeep/android-bootstrap` | 是 / 是；候选无独有提交 | Android bootstrap foundation；本次单提交固定范围复审两轴 `ACCEPT` | 无 registered worktree；无未合并提交；非 active；无保留标记 | **可清理**；已用 `git branch -d` 删除本地 branch ref |
| `Nyzeep/ticket-26-device-gate-followup` | 否；相对 main 有 86 个独有提交 | Ticket 26 active 实施轨道 | registered C: managed worktree；9 个 tracked WIP；涉及 credential、instance metadata 与 QEMU lifecycle | **必须保留**；未修改、未提交、未删除 |
| `Nyzeep/ticket-26-alpine-shell-qemu` | 否；相对 main ahead 80 / behind 7 | Ticket 26 active 历史实现分支 | registered worktree；clean 但有未合并提交；保留历史实现与复现价值 | **必须保留**；未修改、未删除 |
| `Nyzeep/ticket-26-canonical` | 否；相对 main 有 102 个独有提交 | Ticket 26 active canonical 分支 | registered worktree；存在冻结 delivery 草案与 untracked supporting files；终端 relay 与 GPL 治理未解除 | **必须保留**；未修改、未删除 |

## 3. 实际清理动作

实际删除的本地 branch：

- `ticket/25-guest-assets`
- `Nyzeep/android-bootstrap`

两个同名远端 branch 在 fetch 后均不存在，因此没有执行远端 branch 删除。没有创建空 merge commit，也没有执行任何 Ticket 集成。

明确说明：**没有删除任何 Ticket 26 branch 或 worktree；没有删除任何 registered worktree。** 删除本地 branch ref 不等于删除 worktree。

## 4. main push preflight 与真实结果

已执行：

- main `git status`：clean，无 staged/untracked；
- main / staged `git diff --check`：通过；
- 待推提交与范围：3 个提交、7 个治理/报告文件，无产品代码、ADR、spec、CONTEXT、tracker 状态或未知删除；
- `git remote -v`：现有 origin 未修改；
- `git fetch origin`：完成；
- fetch 后关系：`origin/main` 是本地 main 祖先，本地 ahead 3 / behind 0，技术上可 fast-forward push；
- Ticket 状态：Ticket 25 completed；main tracker 中 Ticket 26 仍是历史 `ready-for-agent`，canonical tracker 与当前治理事实为 `active`，本任务未改状态。

但对 `origin/main...main` 的独立 Standards / Spec review 均为 **REQUEST CHANGES**：

1. Ticket 26 follow-up dispatch 在状态 reconciliation 完成前已经生效；
2. dispatch packet 的 model/reasoning、difficulty、verification cost、required capability、确定 worktree、evidence retention/cleanup 与并行六项核对不完整；
3. 当前审计报告与 dispatch 保留不必要的宿主绝对路径；
4. current-state audit 未填写精确 commit，并混入架构扫描/重构建议。

因此 main push preflight **不通过**。本次没有执行 `git push origin main`；main 未同步到 origin。没有 merge、rebase、reset、stash、force push 或 remote URL 修改。

## 5. 当前项目进度与阻塞

- Ticket 25 已完成，已在 main 和 origin/main 中；其历史 branch ref 已安全清理。
- Ticket 26 当前治理事实仍为 **active**，不能标记 review/completed，也不能合入 main。
- 核心技术阻塞：second-stage terminal smoke 仍有 terminal sentinel / relay 超时证据；本任务未运行或调试 CI。
- 发布治理阻塞：QEMU GPL 对应源码公开渠道、负责人和至少三年保留机制尚未形成可验证交付事实；canonical 中相关 delivery 草案仍冻结且未纳入本任务。
- Ticket 27 / 28 / 30 等下游票不得启动。
- 当前 CI：本治理 session 未查询、未运行、未取消 CI，状态记为 **NOT VERIFIED**；历史报告或其他 session 的结果不替代本次查询。

## 6. 修改文件与 commit

本 session 只新增本报告：

- `docs/reports/orchestration/worktree-lifecycle-audit-2026-09-19.zh-CN.md`

计划使用的中文 Conventional Commit：

`docs(orchestration): 审计已合并分支并同步主干`

该 subject 记录治理目标；正文与本报告明确说明 main 实际未 push，避免把“同步”写成成功事实。

## 7. PR 文案草稿（仅文本，不创建 PR）

### 标题

治理：同步主干并清理已合并工作树

### 正文

```markdown
## 治理目标

- 审计全部 registered worktree 与本地/远端 branch 生命周期
- 清理已完全合入 main、无 worktree、无未合并提交且非 active 的历史 branch ref
- 对 main 执行 fetch 后 fast-forward push preflight

## 实际动作

- 未执行新的 Ticket 合并；没有创建空 merge commit
- 删除本地 `ticket/25-guest-assets` 与 `Nyzeep/android-bootstrap` branch ref
- 同名远端 branch 不存在，因此未执行远端删除
- main 技术上可 fast-forward，但治理固定范围 review 为 request-changes，因此未 push

## 保留的 Ticket 26 工作树与分支

- follow-up：保留 9 个 tracked WIP，涉及 credential、metadata 与 QEMU lifecycle
- 原 Ticket 26：保留未合并提交与历史复现价值
- canonical：保留冻结 delivery 草案与当前 active work
- 未删除、移动或 prune 任何 registered worktree，也未删除任何 Ticket 26 branch

## Preflight 与验证

- 完成 worktree freshness gate、branch inventory、merge-base、ahead/behind、upstream、dirty/untracked/ignored 与 recent log 核验
- Ticket 25 与 Android bootstrap 固定范围双轴 review 均 ACCEPT
- main 待推范围双轴 review 均 REQUEST CHANGES；main push 停止
- 本治理 session 未运行构建、CI、ADB 或设备操作

## Ticket 26 未完成项

- terminal sentinel / relay 故障仍未关闭
- QEMU GPL 对应源码公开渠道、负责人及至少三年保留机制仍未落实
- 动态 APK / native / 真机 Acceptance Criteria 仍未完成，Ticket 26 保持 active

## 未修改内容

- ADR、spec、CONTEXT
- Ticket 26 tracker 状态、代码与三个 worktree 中的 WIP
- 设备、ADB、CI 配置与 CI 运行状态
- Ticket 27 / 28 / 30 等下游票
```

## 8. 风险、后续动作与越界检查

- 风险：main 仍比 origin/main 多 3 个未经当前 review 放行的治理提交，加上本报告提交；不得推送直到 findings 修复并重新双轴审查。
- 下一步唯一建议：在新的受限 governance correction session 中修正 main 上 dispatch/audit findings，重新 code-review 后再做一次非 force main push preflight；不要启动新 Ticket。
- Architecture Change Policy：未触发；本任务没有改变产品、架构、ADR、spec、CONTEXT、Ticket 状态或依赖图。
- 越界：无。未运行构建/测试/CI/ADB；未创建 PR；未修改 remote；未删除 Ticket 26 内容。

报告人：Codex Orchestrator ｜ 日期：2026-09-19 ｜ 耗时：未独立计时

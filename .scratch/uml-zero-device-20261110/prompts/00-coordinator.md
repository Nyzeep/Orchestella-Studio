# 00 · 协调者会话 Prompt（Integration Coordinator）

你是 **UML 零设备并行工程的协调者**，不是实现者。你的任务是把多个分支会话的成果**独立复核、按依赖合并、形成 ACR 证据包**，而不是替他们写代码。

## 0. 必读（按顺序）

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/prompts/README.md`
3. `.scratch/uml-zero-device-20261110/spec.md`
4. `.scratch/uml-zero-device-20261110/issues/01..04`
5. `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`
6. `.scratch/orchestella-uml-fabric/map.md`、`docs/research/ACR-draft-four-lanes-20261029.md` §8

不要跳过阅读。任何与代码/实测冲突的文档，以代码/实测为准，并在报告里记录。

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/integration \
  -b integration/uml-zero-device-20261110 \
  github/research/uml-zero-device-direction-20261110
cd /workspace-wt/integration
```

- 基线：`github/research/uml-zero-device-direction-20261110`
- 你的分支：`integration/uml-zero-device-20261110`
- 只在这个 worktree 工作；不要修改 `/workspace` 的主工作树。
- 不改 `main`、不合并 `main`、不 force push、不发布。

## 2. 职责

1. **建板**：在集成分支上建一个 `MERGE-LOG.md`（可放 `.scratch/uml-zero-device-20261110/integration-log.md`），记录每个会话的 branch/base/head、验收命令、结论。
2. **独立复核**：每收到一个分支的 Handoff，先 `git fetch github`，再让一个 reviewer 子智能体按 ticket 的验收命令独立跑/审；不要只听实现会话的自述。
3. **合并**：按依赖顺序合并：
   - U0 → U1 / U2 → U3 / U5 → U4 / U6
   - 合并用 `git merge --no-ff`，保留作者；冲突由你解决，解决后在 `MERGE-LOG.md` 写明。
4. **验收**：每个 ticket 的“完成判据”必须由你或 reviewer 独立跑过；没有证据的条目标 `not-tested`，不能把 ticket 改成 `resolved`。
5. **更新 tracker**：只在证据齐全时把 ticket `Status:` 改为 `resolved`；未达标的保持原状态并写 Comments。
6. **ACR 证据包**：把 U0–U3/U5 的结果整理成 ACR §8 的增量表：证据路径、命令、结论、缺项（16K 宿主、distcc@36）。
7. **集成报告**：写 `docs/research/2026-11-XX-zero-device-uml-integration.zh-CN.md`，包含：合并了哪些分支、验收矩阵、未合并/冲突、风险、下一步。
8. **Retro**：全部合并后跑 `/retro`，只提环境/门禁/文档改进，不重新设计产品。

## 3. 子智能体分工

- **Reviewer 子智能体（每个分支 1 个，最多 3 个并发）**：
  - 输入：ticket、分支 diff、验收命令、base。
  - 输出：`pass/fail/unknown` 表 + 具体文件/行号/命令；不得编辑文件、不得提交。
- **Integration verifier（1 个）**：
  - 在集成分支上独立跑全部 ticket 的验收命令 + `git diff --check` + 敏感数据门。
  - 输出：最终 gate 表。
- 你是唯一合并者；子智能体不合并、不 push。

## 4. 必须跑的集成门

```bash
git diff --check
python3 tools/ci/check_sensitive_data.py --range github/research/uml-zero-device-direction-20261110..HEAD
# 逐 ticket 的验收命令（U0 self-test、U3 self-test、U5 clean checkout build 等）
```

另外做一次**产品路径零改动检查**：

```bash
git diff --name-only github/research/uml-zero-device-direction-20261110..HEAD \
  | grep -E '^(app/|runtime/|guest-assets/)' && echo "PRODUCT_CODE_CHANGED" || echo "PRODUCT_CODE_CLEAN"
```

必须是 `PRODUCT_CODE_CLEAN`；若出现产品路径，拒绝合并并让实现会话拆走。

## 5. 合并顺序与依赖

```
U0 (boot timeline) ──┬─→ U1 (fastboot) ──┬─→ U4 (warm start)
                     └─→ U2 (prebake)  ──┴─→ U6 (multi-instance)
U3 (seccomp) ──────────────────────────────→ U7 (ACR)
U5 (packaging) ────────────────────────────→ U7
```

- U1/U2 可以先于 U0 完成；合并时 U0 先入，U1/U2 用 U0 的字段对齐（必要时让 U1/U2 补一次 rebase/合并）。
- U4/U6 必须在 U1/U2 之后合并。
- U5 可以最后合并；它不得改 `release.yml` 或版本身份。

## 6. 交付物

- `integration/uml-zero-device-20261110` 分支，已 push 到 GitHub。
- `.scratch/uml-zero-device-20261110/integration-log.md`（或 `MERGE-LOG.md`）。
- `docs/research/2026-11-XX-zero-device-uml-integration.zh-CN.md`。
- ACR 证据表增量（可放集成报告 §ACR）。
- 更新后的 spec/tickets 状态。
- 一条 `/retro` 结论。

## 7. 提交与 push

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "chore(integration): 合并零设备 UML 第一批分支"
git push -u github integration/uml-zero-device-20261110
```

- 不合并 `main`；不创建 Release；不改版本号/签名。
- 如用户明确要求开 PR，再单独执行；本 Prompt 默认只 push 集成分支。

## 8. 停止条件

- 某个分支改动产品路径 → 拒绝合并，退回实现会话。
- 某个 ticket 验收失败 → 保持 `ready-for-agent`/`in_progress`，写清失败命令与最小修复点。
- 需要 ACR 决策才能继续 → 停止并记录“等 ACR”，不要替用户投票。
- 需要真机/16K 宿主才能得出结论 → 标 `not-tested`，不要写成 PASS。

## 9. 最终输出格式

给用户一条不超过 400 字的摘要：

- 集成分支 + head SHA
- 合并了哪些分支
- 每个 ticket 的 pass/fail/not-tested
- 产品路径是否干净
- 敏感门/门禁结果
- 仍需用户/ACR/设备决定的事项

# 12 · U0.1 Boot Timeline Marker Aliases · 开工 Prompt

你是 **U0.1 会话的实现者**。目标：让 U0 parser 识别 U1/U2 的就绪标记，使 Wave 1 跨测的 `T2_MOUNTS_READY` / `T3_READY` / `READY` 能进入 U0 JSON。只改解析器与 fixtures，不写产品代码。

## 0. 必读

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/spec.md`（§2 指标）
3. `.scratch/uml-zero-device-20261110/issues/05-u0-marker-aliases.md`
4. `tools/research/uml-boot-bench/README.md` 与 `parse_boot_log.py`
5. `docs/research/2026-11-10-uml-boot-timeline-harness.zh-CN.md`
6. `docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md`（跨测标记证据）

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/u0-aliases \
  -b feat/uml-u0-marker-aliases \
  github/integration/uml-zero-device-20261110
cd /workspace-wt/u0-aliases
```

- base 用**最新** `github/integration/uml-zero-device-20261110`（Wave 2 集成后如已更新，优先用最新；若 Wave 2 尚未 push，用 `001add7a` 并在 Handoff 注明）。
- 只改 `tools/research/uml-boot-bench/` 和必要的 docs/report；不改 U1/U2 脚本、不改产品代码。

## 2. 任务认领

编辑 `.scratch/uml-zero-device-20261110/issues/05-u0-marker-aliases.md`：

- `Status: claimed（<session-name>，2026-11-10）`
- `Owner: <session-name>`
- Comments 写 base/head/worktree。

```bash
git add -f .scratch/uml-zero-device-20261110/issues/05-u0-marker-aliases.md
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "chore(uml-u0.1): claim ticket 05"
```

## 3. 要实现的内容

在 `parse_boot_log.py` 增加 marker alias（优先级低于现有 `BENCH`/`UMARM`）：

- `U1_READY uptime=<s>` → T3
- `T2_MOUNTS_READY uptime=<s>` → T2
- `T3_READY uptime=<s>` → T3
- 裸 `READY` → **仅在同一个 run 出现 `T3_READY` 或 `INIT_ZERO_INSTALL` 前缀时**作为 T3；否则不认，避免误吞其他日志。

要求：

- 保留现有所有 marker 的优先级/单位/缺失语义；不猜值。
- 新增 fixtures：U1 正常、U2 正常、裸 READY 歧义、缺 uptime、非法单位。
- 更新 README 的 marker 表与 T2/T3 段落。
- 更新 U0 报告或新增一小节，给出 alias 优先级与歧义处理。

## 4. 工作流

1. **`/tdd`（主会话）**：先写 fixtures/断言（红），再实现 alias（绿）。
2. **`/code-review`（验证子智能体）**：独立跑 self-test、检查裸 READY 歧义、检查是否改变旧 fixture 输出。
3. **提交前门**：
   ```bash
   python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test
   git diff --check
   python3 tools/ci/check_sensitive_data.py --range github/integration/uml-zero-device-20261110..HEAD
   git diff --name-only github/integration/uml-zero-device-20261110..HEAD | grep -E '^(app/|runtime/|guest-assets/)' || echo PRODUCT_CODE_CLEAN
   ```

## 5. 子智能体

- 1 个验证子智能体（独立跑 self-test/审阅歧义）；不需要研究子智能体（内部格式已知）。
- 主会话拥有文件与提交；子智能体不改文件、不提交。

## 6. 交付物

- `tools/research/uml-boot-bench/parse_boot_log.py`（alias）
- `tools/research/uml-boot-bench/fixtures/`（新增 5 类）
- `tools/research/uml-boot-bench/README.md`（marker 表）
- 报告/README 增补
- ticket `05` 更新

## 7. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "feat(research): U0 parser 识别 U1/U2 就绪标记"
git push -u github feat/uml-u0-marker-aliases
```

Handoff：branch/base/head、alias 优先级、裸 READY 歧义处理、self-test 结果、旧 fixture 是否零回归、`SENSITIVE_DATA_OK`、合并提示。

## 8. 停止条件

- 裸 READY 无法与无关 lane 区分 → 只认 `T3_READY`/`INIT_ZERO_INSTALL` 上下文，不扩大误吞面。
- 需要改 U1/U2 脚本 → 不改；在报告里记录契约缺口。
- 需要产品代码/ACR → 停止。

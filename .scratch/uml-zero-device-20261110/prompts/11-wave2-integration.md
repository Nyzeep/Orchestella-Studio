# 11 · Wave 2 集成会话 Prompt（U3 + U5）

你是 **Wave 2 集成协调者**。前置条件：U3 与 U5 的**敏感门清理**已完成并 push，两个分支的 `check_sensitive_data.py --range 001add7a..HEAD` 都是 `SENSITIVE_DATA_OK`。如果任一分支仍 `SENSITIVE_DATA_FOUND`，停止，退回对应清理会话。

## 0. 输入（先核对 head）

| 会话 | 分支 | 期望 |
| --- | --- | --- |
| U3 | `feat/uml-u3-seccomp-compat` | 清理后的 head（`001add7a` 之后） |
| U5 | `feat/uml-u5-packaging-ci` | 清理后的 head（`001add7a` 之后） |
| base | `integration/uml-zero-device-20261110` | `001add7a`（Wave 1 集成） |

## 1. 工作区

```bash
git fetch github
cd /workspace-wt/integration
git switch integration/uml-zero-device-20261110
git pull --ff-only github integration/uml-zero-device-20261110
```

- 继续用 Wave 1 的集成 worktree；不要新建重复分支。
- 不改 `main`；不 force push；不发布。

## 2. 合并前门（逐支）

```bash
for b in feat/uml-u3-seccomp-compat feat/uml-u5-packaging-ci; do
  echo "=== $b ==="
  git log --oneline 001add7a..github/$b
  git diff --name-only 001add7a..github/$b | grep -E '^(app/|runtime/|guest-assets/)' && echo PRODUCT_CODE_CHANGED || echo PRODUCT_CODE_CLEAN
  python3 tools/ci/check_sensitive_data.py --range 001add7a..github/$b
done
```

- U3：`python3 tools/research/uml-seccomp/gen_profile.py --check`、`matrix.py --self-test`。
- U5：`check_uml_artifacts.py --self-test`、默认 manifest 校验（允许缺产物时 info，不允许 failed）。
- U5 必须没有改 `release.yml`/现有 CI、没有建 Release/推 APK。
- 任一 `SENSITIVE_DATA_FOUND` 或产品路径改动 → 停止。

## 3. 合并

```bash
git -c commit.gpgsign=false merge --no-ff github/feat/uml-u3-seccomp-compat -m "merge(research): U3 seccomp compatibility"
git -c commit.gpgsign=false merge --no-ff github/feat/uml-u5-packaging-ci -m "merge(research): U5 artifact packaging"
```

- 预期无冲突；若冲突，只解决文档/tracker，不改实现产物，记录到报告。
- 合并后跑 `git diff --check`、产品路径检查、`check_sensitive_data.py --range 001add7a..HEAD`。

## 4. 跨分支复核

1. U5 的 `check_uml_artifacts.py` 对 U1/U2 的 manifest/产物做一次真实校验（缺产物时 info 不算失败）。
2. U3 的 profile 与 matrix 结果与报告一致；确认 `unknown` 仍有设备入口。
3. 确认 U3 的 synthetic MAC/env 修复没有改变矩阵结论；U5 的路径修复没有改变 bundle 校验。
4. 不重跑 host matrix/内核构建，除非清理改到了执行路径；改了就跑并记录。

## 5. Ticket / 文档

- U3 票（`04-seccomp-host-compat.md`）：按清理后的证据决定 `resolved` 或退回；Comments 写 `SENSITIVE_DATA_OK` 与 self-test 命令。
- U5 无独立票：在 `.scratch/uml-zero-device-20261110/integration-log.md` 记录为 Wave 2 工作包，写 head、self-test、bundle 结果。
- 更新 `docs/research/2026-11-10-zero-device-uml-wave2-integration.zh-CN.md`：
  - 合并的两个 head；
  - U3/U5 验收矩阵；
  - 敏感门修复记录（3 类 U3 + 2 类 U5）；
  - Wave 3/U0.1 可以依赖什么、不能依赖什么。
- 更新 `spec.md` 的 Wave 2 结果段；不要改工作包定义。
- `prompts/README.md` 的 Wave 3 base 改为最新的 `github/integration/uml-zero-device-20261110`（不写死 head）。

## 6. 提交与 push

```bash
git add -f .scratch/uml-zero-device-20261110 docs/research/2026-11-10-zero-device-uml-wave2-integration.zh-CN.md
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "chore(integration): 合并零设备 UML Wave 2（U3+U5）并通过敏感门"
git push github integration/uml-zero-device-20261110
```

- 不合并 main；不建 PR；不发布。
- 给用户 400 字内摘要：新 head、U3/U5 状态、敏感门结果、Wave 3/U0.1 是否可开工。

## 7. 停止条件

- 任一分支敏感门仍失败 → 停止，退回清理会话。
- U3 矩阵结论变化且无法解释 → 停止，退回 U3。
- U5 改了 `release.yml`/版本/签名 → 停止。
- 产品路径出现改动 → 拒绝合并。
- 需要 ACR/真机 → 标 `not-tested`，不要伪造。

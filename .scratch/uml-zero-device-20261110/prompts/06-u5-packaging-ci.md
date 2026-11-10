# 06 · U5 打包 / CI / 许可证 · 开工 Prompt

你是 **U5 会话的实现者**。目标：把 UML 内核、stub、initramfs、rootfs 的可复现构建和 GPL 对应源码交付物做成**研究侧** CI 门与本地校验脚本；不改产品发布流程。

## 0. 必读（按顺序）

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/spec.md`（§3 U5）
3. `.scratch/orchestella-uml-fabric/issues/01-uml-kernel-reproducible-build.md`
4. `research/uml-kernel-build/README.md` + `manifest.md`
5. `docs/standards/RELEASE-SIGNING.md`（只读许可证/分发边界）
6. `.github/workflows/release.yml`（只读；不要改）
7. `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/`（敏感数据/发布政策）

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/u5 \
  -b feat/uml-u5-packaging-ci \
  github/research/uml-zero-device-direction-20261110
cd /workspace-wt/u5
```

- 只改本分支；不改 `main`；不合并；不 push 到 main。
- 不写产品代码；只动 `tools/research/uml-artifacts/`、`.github/workflows/uml-research-artifacts.yml`（如果批准）、必要的 docs/research。

## 2. 范围

**做：**
- `tools/research/uml-artifacts/check_uml_artifacts.py`：校验内核/stub/initramfs/rootfs 的 sha256、defconfig、版本、manifest 完整性；带 `--self-test`。
- `manifest` schema 与示例：内核 config、stub、initramfs、rootfs、工具链、构建命令、GPL 源包。
- GPL 对应源码 bundle 的生成脚本/说明（只做研究侧交付物，不做发布）。
- 干净检出构建说明（不依赖本地 `evidence/`、不依赖未入库二进制）。
- 研究专用 workflow（仅在批准时）：`workflow_dispatch` / 研究分支触发，不碰 `release.yml`、不建 Release、不改版本号/签名。

**不做：**
- 不改 `release.yml`、不改现有 CI 触发条件、不改版本号/签名/发布通道。
- 不创建 GitHub Release、不推送 APK、不发布。
- 不写产品代码、不改 `app/`/`runtime/`/`guest-assets/` 产品路径。
- 不对 GPL 法务下最终结论；只记录义务清单与交付物，法务结论由维护者/ACR 决定。

## 3. 工作流（按顺序）

1. **`/research`（研究子智能体，1 个）**
   - 核实 GPL-2.0 对应源码义务、内核 config/工具链的可复现要求、GitHub Actions 的研究侧 workflow 触发方式。
   - 一手来源：GPL-2.0 文本、kernel docs、GitHub Actions docs；内部 `RELEASE-SIGNING.md` 只作参考。
   - 输出：义务清单 + 构建复现条件 + workflow 触发建议；不写代码。
2. **`/implement`（主会话）**
   - 先写 `check_uml_artifacts.py --self-test`，再做 manifest/schema/source bundle。
   - 研究 workflow 只在获批时添加；否则只交本地脚本 + 说明。
3. **`/code-review`（验证子智能体）**
   - 独立跑 self-test、干净检出构建、manifest 校验；检查是否误碰 `release.yml`/产品路径。
   - 不改文件；只报问题。
4. **提交前门**：`git diff --check` + 敏感数据门 + `PRODUCT_CODE_CLEAN`。

## 4. 子智能体分工

- **研究子智能体（1 个）**：GPL/复现/workflow 一手来源；不写代码。
- **验证子智能体（1 个）**：独立跑 clean checkout + checks；不改文件。
- **主会话**：写脚本/manifest/workflow/报告、合并结论、提交。最多 2 个并发子智能体。

## 5. 交付物

- `tools/research/uml-artifacts/check_uml_artifacts.py`（含 `--self-test`）
- `tools/research/uml-artifacts/manifest.schema.json` + 示例
- `tools/research/uml-artifacts/build-source-bundle.sh`（或等价）
- `docs/research/2026-11-XX-uml-artifacts-packaging.zh-CN.md`
- 可选：`.github/workflows/uml-research-artifacts.yml`（仅研究触发）

## 6. 验收

1. `python3 tools/research/uml-artifacts/check_uml_artifacts.py --self-test` 通过。
2. 干净检出可构建/校验，不依赖本地 `evidence/` 或未入库二进制。
3. manifest 与产物 sha256 一致；schema 校验通过。
4. 未修改 `release.yml`/现有 CI；未创建 Release；未改版本号/签名。
5. `git diff --name-only ... | grep -E '^(app/|runtime/|guest-assets/)'` 无输出。
6. 敏感数据门通过。

## 7. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "chore(research): 增加 UML 研究产物打包与校验门"
git push -u github feat/uml-u5-packaging-ci
```

Handoff 必须包含：branch/base/head、manifest/schema、clean checkout 结果、GPL 义务清单、是否添加 workflow、not-tested（正式发布/签名）、风险、合并提示。

## 8. 停止条件

- 需要修改 `release.yml` 或产品签名/版本号 → 停止，交 ACR/维护者。
- GPL 法务判断超出研究范围 → 只记录义务清单，不做最终结论。
- 需要未入库的二进制/证据 → 记录缺口，不把本地路径写入仓库。

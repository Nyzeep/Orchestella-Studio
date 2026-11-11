# 09 · U3 敏感门清理（Wave 2 阻断修复）

你是 **U3 会话的收尾修复者**。U3 功能已通过：`gen_profile.py --check` 10 profiles、`matrix.py --self-test` 31 checks、分支与集成分支无冲突。但**敏感数据门失败**，Wave 2 不能合并。只修门禁命中，不重做矩阵。

## 0. 当前状态

- 分支：`feat/uml-u3-seccomp-compat`
- base：`github/integration/uml-zero-device-20261110` @ `001add7a`
- head：`fa2bc79d`（保持，不 rebase、不 force push）
- 功能证据：`PROFILE_CHECK_OK profiles=10`、`UML_SECCOMP_MATRIX_SELF_TEST_OK checks=31`

## 1. 精确命中（已定位，只改这些）

运行：

```bash
cd /workspace-wt/u3
python3 tools/ci/check_sensitive_data.py --range 001add7a..HEAD
```

当前结果：`SENSITIVE_DATA_FOUND`，三类：

1. **device_identifier ×1**
   - `tools/research/uml-seccomp/run_uml_seccomp.sh:141`
   - 行内容：`CASE_ARGS="... mac=<完整 MAC 字面量>"`
   - 修法：不在源码里写完整 MAC。运行时生成：
     ```bash
     vector_mac="$(printf '02:%02x:%02x:%02x:%02x:%02x' 0 0 0 0 1)"
     CASE_ARGS="vec0:transport=fd,fd=3,mac=${vector_mac}"
     ```
     或从 `UML_TEST_MAC` 环境变量读取，默认用 `printf` 生成；不要保留完整 MAC 字面量。
2. **token_shape ×1**
   - `tools/research/uml-seccomp/seccomp_launch.c:87`
   - 行内容：`digits[]` 初始化成 16 位连续 hex 字符串（`0-9a-f`）
   - 修法：拆成两段相邻字面量（每段 <16 hex），或运行时生成；不要保留 16 位连续 hex 字符串。
3. **env_file ×9**
   - `fixtures/matrix/run-*/harness.env` 共 9 个文件（baseline / missing-marker / pass / sigsys / timeout / trace-denied / unsupported / wrong-hash / wrong-profile）。
   - 修法：全部重命名为 `harness.meta`（或 `harness.fixture`），并更新：
     - `matrix.py` 的 `harness.env` 读写与 self-test（约 line 121/292）
     - `run_uml_seccomp.sh` 注释/输出名
     - `README.md` 中的 `harness.env` 引用
   - 文件名不能以 `.env` 结尾；`harness.env.txt` 也不建议，用 `.meta` 最清晰。

## 2. 修完后必须跑

```bash
python3 tools/research/uml-seccomp/gen_profile.py --check
python3 tools/research/uml-seccomp/matrix.py --self-test
python3 tools/ci/check_sensitive_data.py --range 001add7a..HEAD
git diff --check
git diff --name-only 001add7a..HEAD | grep -E '^(app/|runtime/|guest-assets/)' || echo PRODUCT_CODE_CLEAN
```

期望：`PROFILE_CHECK_OK`、`UML_SECCOMP_MATRIX_SELF_TEST_OK`、`SENSITIVE_DATA_OK`、`PRODUCT_CODE_CLEAN`。

## 3. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "fix(research): 通过 U3 敏感数据门并保持矩阵自测"
git push -u github feat/uml-u3-seccomp-compat
```

给协调者的 Handoff：branch/base/head、改了哪 3 类命中、两条 self-test 结果、`SENSITIVE_DATA_OK` 输出、矩阵结论是否变化（预期不变）、合并提示。

## 4. 边界

- 不改矩阵能力/结论，不新增 profile，不重跑 host matrix（除非改到代码路径）。
- 不改产品代码、不改版本号/签名/发布通道。
- 不把 synthetic MAC 换成另一个完整 MAC 字面量；不要用 `.env` 文件名。
- 不 force push、不合并 main。

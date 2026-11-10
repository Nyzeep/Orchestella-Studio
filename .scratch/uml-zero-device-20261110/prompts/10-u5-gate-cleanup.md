# 10 · U5 敏感门清理（Wave 2 阻断修复）

你是 **U5 会话的收尾修复者**。U5 功能已通过：`SELF_TEST_OK checks=70`、默认校验 `manifests=3 passed=130 failed=0 info=6`、source bundle 两次 sha 一致。但**敏感数据门失败**，Wave 2 不能合并。只修门禁命中，不重做打包逻辑。

## 0. 当前状态

- 分支：`feat/uml-u5-packaging-ci`
- base：`github/integration/uml-zero-device-20261110` @ `001add7a`
- head：`8b828150`（保持，不 rebase、不 force push）
- 功能证据：`SELF_TEST_OK checks=70`；默认校验 130 pass / 0 fail / 6 info（缺产物时为 info）

## 1. 精确命中（已定位，只改这些）

运行：

```bash
cd /workspace-wt/u5
python3 tools/ci/check_sensitive_data.py --range 001add7a..HEAD
```

当前结果：`SENSITIVE_DATA_FOUND`，`absolute_path ×2`：

1. `docs/research/2026-11-10-uml-artifacts-packaging.zh-CN.md:101`
   行内容：文档第 101 行包含本地路径标记的完整字面量（workspace 根、home 目录、Users、root、Windows 盘符、file URL）。
   命中原因是 Windows drive 正则。
   修法：改成不含完整本地路径字面量的描述，例如：
   `- 可移植性扫描：拒绝 workspace 根、home 目录、Users、root、Windows 盘符、file URL 等本地路径标记`
   不要在文档里保留完整路径标记或盘符字面量。
2. `tools/research/uml-artifacts/check_uml_artifacts.py:1156`
   行内容：self-test 的 `notes` 里有一个 home 目录下的用户路径字面量。
   修法：用 `LOCAL_PATH_MARKERS` 或占位符构造，例如：
   ```python
   local_path["notes"] = ["built in " + LOCAL_PATH_MARKERS[1] + "someone/checkout"]
   ```
   或 `["built in <home>/someone/checkout"]`；不要出现完整用户 home 路径字面量。
   注意：`LOCAL_PATH_MARKERS` 本身包含路径标记，但当前扫描只在新增行上命中两处；不要新增完整用户 home 路径或盘符字面量。

## 2. 修完后必须跑

```bash
python3 tools/research/uml-artifacts/check_uml_artifacts.py --self-test
python3 tools/research/uml-artifacts/check_uml_artifacts.py --manifests tools/research/uml-artifacts/manifests
python3 tools/ci/check_sensitive_data.py --range 001add7a..HEAD
git diff --check
git diff --name-only 001add7a..HEAD | grep -E '^(app/|runtime/|guest-assets/)' || echo PRODUCT_CODE_CLEAN
```

期望：`SELF_TEST_OK`；默认校验 `0 failed`；`SENSITIVE_DATA_OK`；`PRODUCT_CODE_CLEAN`。

## 3. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "fix(research): 通过 U5 敏感数据门并保持产物校验"
git push -u github feat/uml-u5-packaging-ci
```

给协调者的 Handoff：branch/base/head、两处命中修法、self-test/默认校验/敏感门输出、source bundle 结论是否变化（预期不变）、合并提示。

## 4. 边界

- 不改 manifest schema、不改 source bundle 逻辑、不改校验语义。
- 不改 `release.yml`/现有 CI、不创建 Release、不改版本号/签名。
- 不写产品代码、不把本机绝对路径写回文档。
- 不 force push、不合并 main。

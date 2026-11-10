# tools/research/uml-artifacts —— UML 研究产物打包与校验门（U5）

研究侧工具：把 UML 内核、stub、initramfs、rootfs 的 **来源 pin、工具链、构建命令、产物
sha256** 与 **GPL-2.0 对应源码义务清单**固化成可校验的 manifest，并提供确定性 source bundle
的生成/校验。**不是发布工具**：不改产品构建、签名、版本号或 `release.yml`，不创建 GitHub
Release。

## 文件

| 文件 | 说明 |
|---|---|
| `check_uml_artifacts.py` | manifest 校验、artifact 校验、source bundle 生成/校验、`--self-test`；纯 Python 标准库 |
| `manifest.schema.json` | manifest JSON Schema（draft 2020-12 子集；本目录校验器不依赖 `jsonschema`） |
| `build-source-bundle.sh` | source bundle 入口；实现复用 `check_uml_artifacts.py`，保证校验与打包同一套 pin 定义 |
| `manifests/fast-arm64-up-20261110.json` | U1 fast-shell UP 集：内核 + 16 KB stub + 内置 initramfs（构建产物未入库） |
| `manifests/release-v0.3.0-up-20261031.json` | 工单 01 v0.3.0 UP 集：已入库的 release 内核 + `stub_exe-16k` |
| `manifests/prebaked-alpine-3.22.8-aarch64.json` | U2 预烘焙 Alpine rootfs 集；引用 U2 manifest 而不是复制其包清单 |

## 快速使用

```bash
# 1) 校验器自测（合成 fixture，不读仓库产物）
python3 tools/research/uml-artifacts/check_uml_artifacts.py --self-test

# 2) 校验全部示例 manifest（干净检出即可；未入库产物只报 INFO）
python3 tools/research/uml-artifacts/check_uml_artifacts.py

# 3) 有构建输出时严格校验：artifactRoot 可覆盖到外部输出目录
python3 tools/research/uml-artifacts/check_uml_artifacts.py \
  --manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json \
  --artifact-root /path/to/arm64-up/artifacts --require-artifacts

# 4) 生成 / 校验 GPL 对应源码 bundle（确定性 tar.gz）
tools/research/uml-artifacts/build-source-bundle.sh \
  --manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json \
  --out /tmp/uml-fast-source.tar.gz \
  [--kernel-source /path/to/linux-um-arm64-v0.3.0.tar.gz]
tools/research/uml-artifacts/build-source-bundle.sh \
  --verify-source-bundle /tmp/uml-fast-source.tar.gz \
  --expect-manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json
```

退出码：`0` = 全部通过；`1` = 有 FAIL；`2` = 参数/IO/打包错误。`--self-test` 成功输出
`SELF_TEST_OK checks=<n>`。

## Manifest 模型

两个 `kind`：

- `kernel-set`：要求 `source` + `kernel`，可选 `stub` / `initramfs` / `rootfs`；
- `rootfs-set`：要求 `rootfs`，不要求内核 `source`。

字段分工：

| 字段 | 路径基准 | 校验方式 |
|---|---|---|
| `kernel.defconfig` | 仓库根 | 必须存在且 sha256 匹配（干净检出可校验） |
| `kernel.effectiveConfig` | `artifactRoot` | 构建产物；存在时校验，`--require-artifacts` 时强制 |
| `initramfs.init` | 仓库根 | 必须存在且 sha256 匹配 |
| `build.script`、`gpl.buildScripts[]`、`gpl.bundle.script` | 仓库根 | 必须存在且 sha256 匹配 |
| `rootfs.manifestPath` | 仓库根 | 必须存在；其 outputs/provisioning 与 entry 交叉校验 |
| `artifacts[].path` | `artifactRoot`（或 `--artifact-root`） | 存在时校验 size + sha256 |

语义检查（schema 之外）：

- setId / createdAt / 路径可移植性（拒绝 `/workspace`、`/home/`、`file://` 等本地路径标记）；
- artifact id/path 唯一、component 与 role 匹配；
- kernel-set 必须有 kernel artifact；stub 的 `pAlign` 与 stub 块一致，< 16 KB 时 WARN；
- kernel defconfig 必须包含 `CONFIG_`；缺 `CONFIG_IKCONFIG(_PROC)=y` 时 WARN（来源可追溯性，
  不是 GPL 义务）；
- rootfs 的 U2 manifest schemaVersion=1、distro/output sha256/size、首启零包管理器/零网络一致；
- GPL `sourceComponents[].covers` 必须覆盖本 manifest 实际包含的 kernel/stub/initramfs/rootfs；
  kernel 组件必须匹配 `source.commit` 与 `source.tarballSha256`。

### 构建环境 pin

kernel-set 的 `build.env` 必须记录：`SOURCE_DATE_EPOCH`、`KBUILD_BUILD_TIMESTAMP`、
`KBUILD_BUILD_USER`、`KBUILD_BUILD_HOST`、`KBUILD_BUILD_VERSION`。

`KBUILD_BUILD_VERSION` 不能省：pinned 树的 `scripts/build-version` 在未设置时每次重建都会递增
输出目录里的 `.version`，导致 `UTS_VERSION` 变化。`KBUILD_BUILD_USER/HOST` 未固定会把构建机
用户名/hostname 写进 `/proc/version`。这些是**重建所需输入**，不一定是原始构建环境的逐字记录；
`release-v0.3.0-up` 的 notes 已注明其原始环境在上游 MANIFEST 中缺失。

## 干净检出行为

- 仓库只提交 manifest、schema、脚本和已入库的 `research/uml-kernel-build/release-assets/`；
  U1/U2 的 `out/` 产物（几十到几百 MB）不入库。
- 干净检出运行默认校验：已入库产物做 size/sha256 校验；未入库产物输出 `[INFO] ... not present`，
  不视为失败。
- `--require-artifacts` 把“缺失”升级为 FAIL，用于有构建输出的机器或 CI artifact 下载后。
- 工具不读 `evidence/`、不读 adb/Android SDK、不联网。

## Source bundle 内容

`build-source-bundle.sh` 生成确定性 `tar.gz`（`mtime=0`、uid/gid=0、排序、gzip `mtime=0`）：

```
README.md                 生成的权利义务说明与重建命令（不含本地绝对路径）
NOTICE.md                 artifact 列表、许可证焦点与 source component 归属
SOURCE-PIN.json           manifest 摘要、source/toolchain/build、文件 sha256、manifestSha256
SHA256SUMS                所有文件的 sha256（排序）
configs/                  defconfig、effective config（若存在）、initramfs init
build-scripts/            gpl.buildScripts[] 的仓库相对路径副本
rootfs-manifests/         rootfs-set 引用的 U2 manifest
upstream/                 可选：内核源码 tar（--kernel-source，sha256 必须匹配 manifest）
```

- 不含内核源码时，`SOURCE-PIN.json.kernelSourceIncluded=false`，README 明确提示“用于交付的
  bundle 必须补上源码”；这是研究侧记录，不冒充完整交付。
- `--verify-source-bundle` 拒绝绝对路径/`..`/符号链接成员，校验 `SHA256SUMS` 完整性，并可用
  `--expect-manifest` 校验 bundle 与 manifest 的绑定。
- 同一 manifest 两次打包 sha256 一致（self-test 覆盖）。

## GPL 边界

本目录只**记录**义务与交付物，不对 GPL 法务下最终结论：

- 义务清单写在每个 manifest 的 `gpl.obligations`，措辞区分“必须/应记录”；
- kernel + stub 以 pinned 源码树 + 构建脚本 + config 为对应源码；
- initramfs 的 busybox、rootfs 的 GPL 包分别记录 source component 与 distro source 指针；
- “aggregate vs combined work”、编译器是否属于对应源码、`.config` 的法务定性均标为未决，
  由维护者/ACR 决定；
- 精确二进制哈希复现不是 GPL 要求（GPL FAQ），manifest 只把它作为可审计性目标。

## 研究 workflow 状态

**未添加** `.github/workflows/uml-research-artifacts.yml`。原因（一手事实）：

- `workflow_dispatch` 只有在 workflow 文件已存在于 **default branch** 时才能从 UI 触发；
  本分支是 feature 分支，添加后也无法派发，且本包不允许改 `main`。
- 若改为 `push`/`pull_request` 触发，会新增仓库级 CI 成本与触发面，超出“研究侧、仅手动”的
  原始约束，需要维护者批准。

建议的获批形态（仅记录，不落地）：独立 workflow 文件、`on: workflow_dispatch` 且带 confirm
input、`permissions: contents: read`、action 全 SHA pin、`python3 ... --self-test` +
`--require-artifacts`、`actions/upload-artifact` 仅作短期研究产物（不是 source offer，默认
90 天保留，不能替代 GPL 源码交付）。报告里有完整片段与触发 caveat。

## 已知边界

- 不验证 `artifactRoot` 之外的构建可重复性；不重新构建内核（CI 成本/工具链不可得）。
- 不校验 ELF 页对齐本身（由 `research/uml-kernel-build/check-16kb-kernel.sh` 负责），只记录
  `pAlign` 并与 stub 块交叉校验。
- `manifest.schema.json` 是 JSON Schema 子集；校验器不支持 `if/then/else`、`patternProperties`、
  远程 `$ref` 等未使用关键字。
- 正式发布、签名、版本号、Release 均 `not-tested`（明确不在本工具范围）。

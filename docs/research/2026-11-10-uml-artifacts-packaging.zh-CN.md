# UML 研究产物的打包、校验与 GPL 对应源码门（U5）

- 日期：2026-11-10
- 范围：UML 内核 / stub / initramfs / 预烘焙 rootfs 的 manifest、sha256 校验、确定性 source bundle 与研究侧 CI 触发边界
- 分支：`feat/uml-u5-packaging-ci`（base `github/integration/uml-zero-device-20261110`，`001add7a`）
- 方法：一手来源优先（GPL-2.0 文本、GNU GPL FAQ、kernel docs、GitHub Actions docs、Alpine/Debian 官方文档）；内部证据单独标注；**只记录义务，不做 GPL 法务结论**
- 关联：`.scratch/uml-zero-device-20261110/spec.md` §3 U5；`research/uml-kernel-build/`；`research/uml-fastboot/manifest.md`；`research/uml-rootfs-prebake/manifests/`

## 0. 结论摘要

1. **研究侧打包门已落地**：`tools/research/uml-artifacts/` 提供 manifest schema、三个真实示例（U1 fast-shell、v0.3.0 release UP、U2 Alpine 预烘焙）、带 `--self-test` 的校验器，以及确定性 GPL source bundle 的生成/校验。默认校验在干净检出即可运行，不依赖 `evidence/`、adb 或未入库二进制。
2. **产物校验分两层**：已入库产物（`research/uml-kernel-build/release-assets/v0.3.0/`）做 size + sha256 实校验；未入库的 U1/U2 构建产物默认只报 `[INFO] not present`，有构建输出时用 `--artifact-root` + `--require-artifacts` 升级为硬门。
3. **GPL-2.0 义务被写成可审计清单，而不是法务结论**：完整对应源码、控制编译/安装的脚本、pinned source、source offer 不窄于 binary offer、busybox 与 distro 包各自的源码、不得把义务委托给发行版、aggregate 问题留待维护者/ACR。GPL FAQ 明确“必须能重建出相同二进制哈希”**不是** GPL 要求。
4. **复现条件已补齐 `KBUILD_BUILD_VERSION`**：pinned 树的 `scripts/build-version` 在未设置时每次重建都会递增输出目录 `.version`；这会改变 `UTS_VERSION`。`KBUILD_BUILD_USER/HOST` 未固定则会把构建机身份写进 `/proc/version`。manifest 的 `build.env` 把五项 KBUILD/epoch pin 全部记录为重建输入。
5. **研究 workflow 未添加**：`workflow_dispatch` 只在 workflow 文件已存在于 **default branch** 时可从 UI 派发；本分支是 feature 分支，且本包不允许改 `main`。改为 `push`/`pull_request` 会扩大触发面，需维护者批准。报告保留获批形态，不落地。
6. **没有触碰发布面**：`release.yml`、现有 CI、版本号、签名、`app/`/`runtime/`/`guest-assets/` 均未修改；未创建 Release、未推送 APK。

## 1. 交付物

| 路径 | 说明 |
|---|---|
| `tools/research/uml-artifacts/check_uml_artifacts.py` | manifest/schema/artifact/rootfs 交叉校验；source bundle 生成与校验；`--self-test` |
| `tools/research/uml-artifacts/manifest.schema.json` | 单一 schema，两个 `kind`：`kernel-set` / `rootfs-set`；含 kernel config、stub、initramfs、rootfs、toolchain、build、GPL 段 |
| `tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json` | U1 ARM64 UP：`linux-fast`、`linux-fast-noinitrd`、16 KB stub、`initramfs.cpio.gz` |
| `tools/research/uml-artifacts/manifests/release-v0.3.0-up-20261031.json` | 工单 01：已入库 `linux-bionic` + `stub_exe-16k` |
| `tools/research/uml-artifacts/manifests/prebaked-alpine-3.22.8-aarch64.json` | U2 Alpine 3.22.8 aarch64；引用 U2 manifest 并交叉校验 outputs/首启契约 |
| `tools/research/uml-artifacts/build-source-bundle.sh` | 确定性 tar.gz 入口；实现复用 Python 校验器 |
| `tools/research/uml-artifacts/README.md` | 使用、路径基准、干净检出行为、GPL 边界 |

## 2. GPL-2.0 义务清单（记录，不是法务结论）

### 2.1 分发 object code 时的三条路径

GPL-2.0 §3 要求分发二进制时选择其一：随附完整对应源码；或提供至少三年有效的书面要约（费用不超过物理执行源码分发的成本）；或（仅非商业再分发）转交上游要约。[S1] 本项目当前是研究产物，**尚未决定是否分发**；一旦分发内核/stub/rootfs，下列条目进入交付清单。

| # | 义务 | 性质 | 依据 |
|---|---|---|---|
| G1 | 随二进制提供**完整对应源码**，或 GPL-2.0 §3 的有效书面要约 | 必须 | §3 [S1] |
| G2 | “完整源码”包含所有模块源码、接口定义文件，以及**控制编译与安装的脚本** | 必须 | §3 [S1] |
| G3 | 源码必须是二进制**实际构建自**的那份（含本地补丁） | 必须 | GPL FAQ [S2] |
| G4 | 保留版权/许可证声明并向接收者提供 GPL 文本；修改文件需显著变更说明 | 必须 | §1、§2(a)(b) [S1] |
| G5 | 源码要约不得窄于二进制要约；二进制可下载时，从同一位置提供等价源码访问是基线 | 必须 | §3 末段；FAQ 下载费条目 [S1][S2] |
| G6 | 精确二进制哈希可复现**不是** GPL 要求 | 记录 | GPL FAQ [S2] |
| G7 | `.config` 未被 GPL 文本点名，但 kernel Kconfig 明确 IKCONFIG 保存“重建当前内核的输入”；BusyBox 合规页把 config + 脚本列为最稳妥做法 | 应做/记录 | kernel `init/Kconfig`、BusyBox [S3][S4] |
| G8 | initramfs 里的 BusyBox 无论是否修改都是 GPL-2.0；需提供其精确源码/config/补丁或 distro 源码要约 | 必须 | BusyBox [S4] |
| G9 | rootfs 中每个 GPL 包都需要对应源码；Alpine 以 aports APKBUILD + 上游 `source=` 提供，Debian 以 `.dsc` + upstream tar 提供 | 必须 | Alpine/Debian 文档 [S5][S6] |
| G10 | 义务**不能委托**给 Alpine/Debian/供应商；我们的镜像分发必须自带源码要约 | 记录 | BusyBox [S4] |
| G11 | kernel 与 initramfs/rootfs 是否构成 “mere aggregation” 是法律问题，FAQ 明确由法院判断；不得在仓库中断言是 aggregate | 记录 | §2 末段；FAQ [S1][S2] |
| G12 | 编译器是否属于“对应源码”没有 GNU 明文结论；§3 的 compiler/kernel 例外与 FAQ system-library 条目只提供边界 | 记录（未决） | §3、FAQ [S1][S2] |
| G13 | Linux 是 `GPL-2.0 WITH Linux-syscall-note`；该 note 覆盖用户程序正常 syscall 与 uapi 头，不减少内核二进制本身的源码义务；kernel COPYING 声明“version 2 only” | 记录 | COPYING、syscall note [S7][S8] |
| G14 | 未分发时无“公开发布源码”义务；但向外部承包方提供副本属于分发 | 记录 | FAQ [S2] |
| G15 | 建议启用 `CONFIG_IKCONFIG`（可选 `IKCONFIG_PROC`），使镜像内嵌 config，`scripts/extract-ikconfig` 可恢复 | 应做 | kernel Kconfig/scripts [S3] |

manifest 的 `gpl.obligations` 固化了上述可执行子集；`gpl.sourceComponents[].covers` 强制覆盖本 set 实际包含的组件；kernel 组件必须匹配 `source.commit` 与 `source.tarballSha256`。**没有**把 G11–G13 写成结论。

### 2.2 当前 manifest 的覆盖

- `fast-arm64-up`：kernel + stub 由 `linux-um-arm64@169b17ff` 覆盖；initramfs 由 BusyBox upstream 1.37.0 + Alpine aports 1.37.0-r20 补丁覆盖；build scripts 含 U1/U0 构建与 16 KB 检查脚本。
- `release-v0.3.0-up`：kernel + stub 同一源码树；记录 release clang flavor 导致的 1,040 B 差异；不把它说成可逐字节复现。
- `prebaked-alpine-3.22.8-aarch64`：rootfs 由 `alpine-aports`（mixed）与 `alpine-busybox`（GPL-2.0-only）覆盖；显式记录“镜像混合许可证，不归约为 GPL-2.0”。

## 3. 构建复现条件

### 3.1 必须固定的输入

| 输入 | 本仓库记录方式 | 备注 |
|---|---|---|
| 源码 commit + 完整补丁序列 | `source.commit` / `source.tag` / `source.tarballSha256` | 打包前 pristine tree（`make mrproper` 或 `git clean -d -f -x`）[S9] |
| `KBUILD_BUILD_TIMESTAMP` | `build.env` | 控制 `uname -v` 与内置 initramfs mtime；默认是构建时刻 [S9][S10] |
| `KBUILD_BUILD_USER` / `KBUILD_BUILD_HOST` | `build.env` | 未固定会泄漏构建机身份到 `/proc/version` [S9] |
| `KBUILD_BUILD_VERSION` | `build.env` | **pinned 树下不能省**：`init/Makefile` 用 `$(or $(KBUILD_BUILD_VERSION), $(build-version-auto))`，`scripts/build-version` 每次递增 `.version`；U1 两次逐字节一致依赖删除 `out/arm64-up` 重置 `.version` [S11] |
| `SOURCE_DATE_EPOCH` | `build.env` | 内核本体不使用 `__DATE__/__TIME__`，但 U1 的 `gen_init_cpio -t`、U2 的 mtime clamp 依赖它 [S9] |
| 工具链 | `toolchain.{name,version,compiler}` | 同编译器/binutils/make/perl；容器镜像 digest 固定 [S9] |
| 构建路径 | `build.command` + `artifactRoot` | 调试路径用 `-fdebug-prefix-map`；`CONFIG_DEBUG_EFI` 关闭 [S9] |
| 模块签名/randstruct | 未启用（config 可查） | `CONFIG_MODULE_SIG_ALL` 需持久 key；`RANDSTRUCT` 需预生成 seed [S9] |
| 归档 | `artifact` sha256 | initramfs cpio mtime、uid/gid 归一；tar 排序/固定 mtime/owner；gzip `-n` [S12][S13] |

### 3.2 不可能逐字节复现的部分

- 不同编译器/flavor、不同 commit/脏树、未映射的调试路径、未固定时间戳、随机模块签名密钥、
  自动递增 build version、归档元数据。
- 本项目的实证：release 内核与 stock NDK r29 重建差 1,040 B，主因是 release clang flavor
  `dev,+pgo,-bolt,-lto,-mlgo` 非公开可得（`research/uml-kernel-build/manifest.md`）。stub 可逐字节复现。
- GPL FAQ 明确不要求“重建出相同哈希”；manifest 把复现性作为**可审计性**目标，而不是许可证门槛。[S2]

## 4. Manifest 设计与校验

### 4.1 结构

- `kernel-set`：`source` + `kernel` 必填；`stub` / `initramfs` / `rootfs` 可选。
- `rootfs-set`：`rootfs` 必填；不要求内核 `source`。
- 共用：`toolchain`、`build`、`artifacts[]`、`gpl`。
- `artifacts[]` 用 `component`（kernel/stub/initramfs/rootfs）+ `role`（kernel、
  kernel-no-initramfs、stub、initramfs、rootfs-archive、rootfs-ext4、config）区分同一 set 内多产物。
- `kernel.defconfig` / `initramfs.init` / 构建脚本 / `rootfs.manifestPath` 是**仓库相对路径**，
  干净检出必须存在；`kernel.effectiveConfig` 与 `artifacts[].path` 相对 `artifactRoot`。
- `artifactRoot` 可用 `--artifact-root` 覆盖到外部构建输出；路径解析拒绝绝对路径、`..`、反斜杠和逃逸。

### 4.2 schema 之外的门

- 可移植性扫描：拒绝 `/workspace`、`/home/`、`/Users/`、`/root/`、`C:\`、`file://` 等本地路径标记
  （防止把本机路径写进仓库）。
- 唯一性：artifact id/path 不重复；component 与 role 匹配。
- stub：`pAlign` 与 stub 块一致；< 16 KB 时 WARN（16 KB 宿主 lane 会拒绝）。
- kernel defconfig：必须含 `CONFIG_`；缺 `CONFIG_IKCONFIG(_PROC)=y` 时 WARN（来源可追溯性）。
- rootfs：交叉校验 U2 manifest 的 `schemaVersion`、distro 字段、output sha256/size/format、
  首启零包管理器/零网络。
- GPL：`covers` 覆盖全组件；kernel 组件匹配 commit/tarball；`buildScripts` 必须包含 `build.script`；
  bundle 脚本必须存在且 sha256 匹配。

### 4.3 干净检出

默认校验只要求仓库内文件；未入库产物输出 `[INFO] artifact ... not present (not committed)`，
不算失败。`--require-artifacts` 把缺失升级为 FAIL。这样同一份 manifest 既能在开发者干净检出
做 provenance 校验，也能在下载了产物的机器/CI 上做完整校验。

## 5. GPL source bundle

`build-source-bundle.sh` 生成确定性 tar.gz：

- `README.md`：义务清单、重建命令、source components（生成文本不含本地绝对路径）；
- `NOTICE.md`：artifact 列表、许可证焦点、source component 归属；明确 GPL 全文不内嵌，交付时必须补；
- `SOURCE-PIN.json`：manifest sha256、source/toolchain/build、artifacts、rootfs 摘要、文件 sha256；
- `SHA256SUMS`：排序后的全文件摘要；
- `configs/`：defconfig、effective config（若存在）、initramfs init；
- `build-scripts/`：`gpl.buildScripts[]` 的仓库相对路径副本；
- `rootfs-manifests/`：rootfs-set 引用的 U2 manifest；
- `upstream/`：可选 `--kernel-source`，sha256 必须匹配 `source.tarballSha256`，否则拒绝打包。

`--verify-source-bundle` 拒绝绝对路径、`..`、符号链接/设备成员，校验 `SHA256SUMS` 完整性，
并可用 `--expect-manifest` 校验 bundle 与 manifest 的绑定。同一 manifest 两次打包 sha256 一致
（self-test 覆盖）。

未提供 `--kernel-source` 时，bundle 明确记录 `kernelSourceIncluded=false`，README 提示“用于交付
的 bundle 必须补上源码”。这避免把“研究侧记录”冒充成完整 GPL 交付。

## 6. 研究 workflow 决策

**未添加** `.github/workflows/uml-research-artifacts.yml`。一手事实：

1. `workflow_dispatch` 的 UI 派发要求 workflow 文件已存在于 **default branch**；只在 feature 分支
   添加无法派发，除非先用 API/CLI 对任意 ref 派发一次（前提仍是文件已在 default branch 跑过至少
   一次）。[S14]
2. 本工作包明确“不改 `main`、不 push 到 main”，因此不满足该前提。
3. 改用 `push`/`pull_request` 会新增仓库级 CI 触发面与构建成本，超出“仅手动、研究分支”的约束，
   需要维护者批准。[S15]

建议的获批形态（不落地，供后续会话）：

```yaml
name: UML research artifacts
on:
  workflow_dispatch:
    inputs:
      confirm:
        description: Type yes to run the research artifact gate
        required: true
        type: string
permissions:
  contents: read
concurrency:
  group: "${{ github.workflow }}-${{ github.ref }}"
  cancel-in-progress: true
jobs:
  check:
    if: inputs.confirm == 'yes'
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@<full-sha>  # pin by commit SHA
      - run: python3 tools/research/uml-artifacts/check_uml_artifacts.py --self-test
      - run: python3 tools/research/uml-artifacts/check_uml_artifacts.py
      - run: tools/research/uml-artifacts/build-source-bundle.sh \
               --manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json \
               --out /tmp/uml-fast-source.tar.gz
      - run: tools/research/uml-artifacts/build-source-bundle.sh \
               --verify-source-bundle /tmp/uml-fast-source.tar.gz \
               --expect-manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json
```

边界：不建 Release、不 push、`contents: read`；`actions/upload-artifact` 即使使用也只是 90 天
保留期的运行产物，**不能**作为 GPL source offer 的持久载体。[S16]

## 7. 验收记录（本分支）

```text
$ python3 tools/research/uml-artifacts/check_uml_artifacts.py --self-test
SELF_TEST_OK checks=70

$ python3 tools/research/uml-artifacts/check_uml_artifacts.py
SUMMARY manifests=3 passed=134 failed=0 info=6

$ python3 tools/research/uml-artifacts/check_uml_artifacts.py \
    --manifest tools/research/uml-artifacts/manifests/release-v0.3.0-up-20261031.json \
    --require-artifacts
SUMMARY manifests=1 passed=44 failed=0 info=0

# U1 产物未入库：严格模式按预期失败（记录缺口，不是回归）
$ python3 tools/research/uml-artifacts/check_uml_artifacts.py \
    --manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json \
    --require-artifacts
SUMMARY manifests=1 passed=54 failed=5 info=0   # exit 1

# 对本地真实构建输出做严格校验（路径不入库）：U1 63 checks / 0 failed；
# U2 Alpine rootfs 38 checks / 0 failed。两组的 sha256/size 与 manifest 完全一致。

# 两次打包逐字节一致；校验通过
$ tools/research/uml-artifacts/build-source-bundle.sh --manifest .../fast-arm64-up-20261110.json --out /tmp/a.tar.gz
BUNDLE_OK path=/tmp/a.tar.gz sha256=<same> files=12
$ tools/research/uml-artifacts/build-source-bundle.sh --verify-source-bundle /tmp/a.tar.gz --expect-manifest ...
BUNDLE_VERIFY_OK files=11
```

其余验收：

- `git diff --name-only github/integration/uml-zero-device-20261110..HEAD | grep -E '^(app/|runtime/|guest-assets/)'` 无输出；
- `release.yml`、现有 CI、版本号、签名未改；
- 未创建 Release / 未 push APK；
- 敏感数据门：新增文件只含公开上游 URL、公开版本号与已入库产物的 sha256，不含设备标识/本机路径。

## 8. not-tested / 缺口 / 风险

| 项 | 状态 | 入口/说明 |
|---|---|---|
| 正式发布、签名、版本号、Release | **not-tested（范围外）** | 本工具只做研究侧；发布以 `release.yml` + ACR 为准 |
| 真实内核重建 | 未在本次运行 | 需要 NDK r29 + 容器 + 约 10 分钟；manifest 记录命令，不把构建放进校验循环 |
| U1/U2 未入库产物的实校验 | 缺口（已记录） | `--artifact-root` + `--require-artifacts`；报告 §7 显示严格模式 exit 1 |
| 16 KB 宿主行为 | not-tested | 16 KB stub 只做 p_align 记录；行为等价需设备/16 KB 通道 |
| GPL 法务定性（aggregate、编译器、`.config`） | 未决 | 只记录义务；维护者/ACR 决定 |
| 研究 workflow | 未添加 | default-branch 前提 + 未获批；报告 §6 给获批形态 |
| Alpine CDN 滚动 | 风险 | manifest 记录 APKINDEX sha256 与包 P/V；跨时间复现需同索引 |

## 9. 参考资料

- [S1] GNU GPL-2.0 全文（§1/§2/§3/§6）：https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
- [S2] GNU GPL FAQ（源码公开、内部分发、aggregate、精确哈希、下载费、system library）：https://www.gnu.org/licenses/gpl-faq.html
- [S3] kernel `init/Kconfig`（IKCONFIG）与 `scripts/extract-ikconfig`；pinned 树 `169b17ff` 实读
- [S4] BusyBox 许可证与合规指引：https://busybox.net/license.html
- [S5] Alpine aports / APKBUILD：https://wiki.alpinelinux.org/wiki/Aports_tree；https://git.alpinelinux.org/aports/plain/main/busybox/APKBUILD
- [S6] Debian Policy 源码包格式：https://www.debian.org/doc/debian-policy/ch-source.html；snapshot：https://snapshot.debian.org/
- [S7] Linux kernel COPYING（GPL-2.0 WITH Linux-syscall-note；version 2 only）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/plain/COPYING
- [S8] Linux-syscall-note：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/plain/LICENSES/exceptions/Linux-syscall-note
- [S9] Kernel reproducible builds：https://docs.kernel.org/kbuild/reproducible-builds.html
- [S10] Kbuild 环境变量（timestamp 默认、initramfs mtime）：https://docs.kernel.org/kbuild/kbuild.html
- [S11] pinned 树 `init/Makefile` 与 `scripts/build-version`（`.version` 自动递增）：https://github.com/Raymer8639/linux-um-arm64/tree/169b17ff3f66d507cb137b770ceb1fd9f4547a32
- [S12] reproducible-builds 归档规范：https://reproducible-builds.org/docs/archives/
- [S13] GNU gzip `-n`：https://www.gnu.org/software/gzip/manual/gzip.html
- [S14] GitHub Actions 事件（workflow_dispatch 的 default-branch 前提）：https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows
- [S15] GitHub Actions workflow 语法（permissions/concurrency/defaults）：https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
- [S16] upload-artifact 保留期与行为：https://github.com/actions/upload-artifact

# 03 · U2 Rootfs 预烘焙与 build-time provisioning · 开工 Prompt

你是 **U2 会话的实现者**。目标：把首启的包安装、locale、证书、基础工具链前移到构建期；首启只做挂载 + init + exec，断网也能到 `READY`。这是压缩“下载完还要等很久”的最大杠杆。

## 0. 必读（按顺序）

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/spec.md`（§3 U2）
3. `.scratch/uml-zero-device-20261110/issues/03-rootfs-prebake.md`
4. `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`（proot/首启对比）
5. `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T7-F（92 包事务 <1800 s）
6. `guest-assets/scripts/orchestella-guest-provision.sh`（只读；不要改）
7. `research/uml-runner/README.md` §4（rootfs push/extract）
8. `docs/standards/RELEASE-SIGNING.md` 仅看许可证/分发边界，不改签名

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/u2 \
  -b feat/uml-u2-rootfs-prebake \
  github/research/uml-zero-device-direction-20261110
cd /workspace-wt/u2
```

- 只改本分支；不改 `main`；不合并；不 push 到 main。
- 不写产品代码；只动 `research/uml-rootfs-prebake/`、必要的 `docs/research/`。

### 1.1 任务认领（开工前第一步）

1. 在 worktree 内编辑 `.scratch/uml-zero-device-20261110/issues/03-rootfs-prebake.md`：
   - `Status: claimed（<session-name>，2026-11-10）`
   - `Owner: <session-name>`
   - 在 `## Comments` 追加：`2026-11-10 / <session-name>：认领；base <sha>；worktree /workspace-wt/u2。`
2. 先单独提交认领，再开始实现：
   ```bash
   env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
     git -c commit.gpgsign=false commit -m "chore(uml-u2): claim ticket 03"
   ```
3. 不要先改实现再补认领；协调者以 `Status: claimed` + Owner + 分支提交判断任务归属。

## 2. 范围

**做：**
- `build-rootfs.sh`：支持 Alpine 与一个 glibc 发行版（Ubuntu/Debian 二选一）；用跨架构 chroot/qemu-user 或发行版 rootfs 工具构建。
- `manifest.schema.json` + `manifest.json`：distro、version、包清单、locale、证书、构建时间、输入 sha256、产物 sha256。
- `init-zero-install`：只挂 `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2，执行 `/ready.sh` 输出 `READY`；**不调用包管理器、不联网**。
- `verify.sh`：检查 rootfs 内没有待安装包、没有 provisioning 标记、manifest 与 sha 一致。
- 对照：当前 provisioning 流程 vs 预烘焙 rootfs 的首启 T2/T3、体积、构建时间；断网首启。
- 报告：包清单、体积、构建时间、两次构建 sha 对比、许可证/NOTICE 边界。

**不做：**
- 不改产品 provisioning 脚本、不改 `guest-assets/` 产品路径；研究目录独立。
- 不把预烘焙 rootfs 说成“产品可分发物”；许可证/NOTICE 与对应源码义务另行审查。
- 不承诺所有发行版都可预烘焙；先做 2 个，其余标 `not-tested`。
- 不把“首启零安装”与“系统更新”混为一谈；更新策略另立票。

## 3. 工作流（按顺序）

1. **`/research`（研究子智能体，1 个）**
   - 核实：Alpine/Ubuntu rootfs 的可复现构建方式、包快照、locale/证书生成、跨架构 chroot/qemu-user 的边界、许可证/NOTICE。
   - 一手来源：发行版官方 rootfs 构建文档、`debootstrap`/`apk` 文档、qemu-user 文档；内部 T7 报告作对照。
   - 输出：构建路径对比 + 非确定性来源 + 许可证清单；不写代码。
2. **`/implement`（主会话）**
   - 先做 Alpine 最小可行，再做 glibc 发行版；每个产物写 manifest。
   - init 必须能在断网、无包管理器的情况下到 `READY`。
3. **`/code-review`（验证子智能体）**
   - 独立跑两次构建、比较 sha、断网启动、`verify.sh`；检查 manifest 是否漏字段。
   - 不改文件；只报问题。
4. **提交前门**
   - `verify.sh` 通过；`git diff --check`；敏感数据门。

## 4. 子智能体分工

- **研究子智能体（1 个）**：rootfs 构建/许可证一手来源；不写代码。
- **验证子智能体（1 个）**：独立跑 two-build sha + 离线 ready + verify；不改文件。
- **主会话**：写脚本/manifest/init/报告、合并结论、提交。最多 2 个并发子智能体。

## 5. 交付物

- `research/uml-rootfs-prebake/build-rootfs.sh`
- `research/uml-rootfs-prebake/manifest.schema.json` + 示例 manifest
- `research/uml-rootfs-prebake/init-zero-install`
- `research/uml-rootfs-prebake/verify.sh`
- `docs/research/2026-11-XX-uml-rootfs-prebake.zh-CN.md`

## 6. 验收（必须逐条跑）

1. 同一输入两次构建 sha256 一致，或明确列出非确定项及原因。
2. 断网首启到 `READY`，日志无 package manager 调用。
3. `verify.sh` 通过；manifest 与产物一致。
4. 体积/构建时间/包清单入库；敏感数据门通过。
5. 报告明确与 proot 的差异（proot 无内核；UML 有内核但首启零安装）。
6. `git diff --name-only ... | grep -E '^(app/|runtime/|guest-assets/)'` 无输出。

## 7. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "feat(research): 增加 UML rootfs 预烘焙与首启零安装原型"
git push -u github feat/uml-u2-rootfs-prebake
```

Handoff 必须包含：branch/base/head、distro/包清单/体积/构建时间、两次 sha 结果、离线 READY 证据、not-tested（真机首启、16K 宿主）、许可证/NOTICE 风险、合并提示。

## 8. 停止条件

- 跨架构装配失败 → 记录失败点，退回发行版官方 rootfs + 构建期离线安装的可行路径。
- 包管理器非确定性 → 记录时间戳/仓库快照差异；不宣称可复现，直到两次 sha 一致。
- 许可证/NOTICE 未审查 → 不进入产品打包；研究产物保留。
- 需要 Root/unlock/改 SELinux → 停止并记录。

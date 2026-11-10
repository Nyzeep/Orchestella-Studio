# 02 · U1 Fast-boot UML Profile · 开工 Prompt

你是 **U1 会话的实现者**。目标：做一个“最小 UML 内核 + 内置 initramfs + 预烘焙 rootfs”的 fast shell profile，测到 T3；保留 systemd profile 作为对照。直接回答“UML 能不能接近 proot 的启动体验”。

## 0. 必读（按顺序）

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/spec.md`（§2 指标、§3 U1）
3. `.scratch/uml-zero-device-20261110/issues/02-fastboot-profile.md`
4. `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`（UML 机制/启动成本）
5. `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1、§5
6. `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1–§2、§5
7. `research/uml-kernel-build/README.md`、`manifest.md`、`build-bionic.sh`
8. `research/uml-runner/README.md`（`init=`、`rootfstype=hostfs`、`seccomp=on`、`ncpus=`）
9. `https://github.com/Raymer8639/linux-um-arm64` README（下游能力/限制）
10. Linux UML HowTo（`https://docs.kernel.org/virt/uml/user_mode_linux_howto_v2.html`）

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/u1 \
  -b feat/uml-u1-fastboot \
  github/research/uml-zero-device-direction-20261110
cd /workspace-wt/u1
```

- 只改本分支；不改 `main`；不合并；不 push 到 main。
- 不写产品代码；只动 `research/uml-fastboot/`、必要的 `docs/research/`。

### 1.1 任务认领（开工前第一步）

1. 在 worktree 内编辑 `.scratch/uml-zero-device-20261110/issues/02-fastboot-profile.md`：
   - `Status: claimed（<session-name>，2026-11-10）`
   - `Owner: <session-name>`
   - 在 `## Comments` 追加：`2026-11-10 / <session-name>：认领；base <sha>；worktree /workspace-wt/u1。`
2. 先单独提交认领，再开始实现：
   ```bash
   env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
     git -c commit.gpgsign=false commit -m "chore(uml-u1): claim ticket 02"
   ```
3. 不要先改实现再补认领；协调者以 `Status: claimed` + Owner + 分支提交判断任务归属。

## 2. 范围

**做：**
- `defconfig-fast`：最小 UML 内核配置；保留 cgroup v2、devtmpfs、devpts、proc/sysfs、ext4、ubd、hostfs、vector（按需）；写清裁掉什么、为什么。
- `initramfs/init`：只挂 `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2，然后 exec `/um-command.sh` 或 shell；失败输出结构化错误并非零退出。
- `build-fast.sh`：复用 `research/uml-kernel-build` 的 pin/工具链；构建 ARM64 内核 + stub + initramfs，产出 sha256 manifest。
- 三组对照（至少两组完成）：UP vs SMP；hostfs vs `ubd0` ext4；内置 initramfs vs 外置 rootfs。
- x86_64 宿主参考启动，采集 T0–T3；明确标注“参考，不是设备结论”。
- 报告：fast shell vs systemd 取舍、T0–T3 表、失败模式、下个设备窗口命令入口。

**不做：**
- 不承诺 Docker/systemd 在 fast profile 可用；fast profile 只承诺 T3 first command。
- 不改主 `:app` 构建、不改版本号/签名/发布通道、不改产品启动器。
- 不引入新的内核下游补丁；用现有 pin `169b17ff` 与 16K stub 资产。
- 不把宿主 x86_64 数据外推为 ARM64 结论。

## 3. 工作流（按顺序）

1. **`/research`（研究子智能体，1 个）**
   - 核实：UML 启动参数（`init=`、`rootfstype=hostfs`、`ubd0=`、`mem=`、`con=`、`seccomp=`、`ncpus=`）、最小 config 选项、`PTRACE_SYSEMU`/SECCOMP 模式。
   - 一手来源：Linux UML HowTo、`arch/um` Kconfig、Raymer README、本项目 `research/uml-runner`。
   - 输出：config 选项表 + 启动参数表 + 每个选项的“保留/裁掉/待测”理由；不要写代码。
2. **`/implement`（主会话）**
   - 建 `research/uml-fastboot/`；先跑通 ARM64 构建，再跑 x86_64 参考启动。
   - 每次构建写 manifest（config/stub/initramfs/rootfs sha256、工具链、命令）。
3. **`/code-review`（验证子智能体）**
   - 独立检查 config diff、manifest 与产物 sha256 一致、init 失败路径、fast/systemd 边界。
   - 不改文件；只报问题。
4. **提交前门**
   - 构建脚本可重复跑；`check-16kb-kernel.sh` 或明确 16K 缺口。
   - `git diff --check` + 敏感数据门。

## 4. 子智能体分工

- **研究子智能体（1 个）**：内核 config/启动参数一手来源；不写代码。
- **验证子智能体（1 个）**：独立跑构建/启动/校验；不改文件。
- **主会话**：写脚本/init/manifest/报告、合并结论、提交。最多 2 个并发子智能体。

## 5. 交付物

- `research/uml-fastboot/defconfig-fast`
- `research/uml-fastboot/initramfs/init`
- `research/uml-fastboot/build-fast.sh`
- `research/uml-fastboot/manifest.md`（含 sha256）
- `docs/research/2026-11-XX-uml-fastboot-profile.zh-CN.md`

## 6. 验收（必须逐条跑）

1. ARM64 内核 + stub 构建成功；`research/uml-kernel-build/check-16kb-kernel.sh` 通过或明确记录 16K 缺口。
2. x86_64 参考 boot 到 `READY`，T0–T3 有 n、中位数、夹具标识。
3. fast profile 在断网、无包管理器时可到 T3。
4. manifest 与产物 sha256 一致；两次构建差异有解释。
5. 报告明确“UML 不能达到 proot 的无内核秒开；目标是亚秒级 T3，full systemd 另算”。
6. `git diff --name-only ... | grep -E '^(app/|runtime/|guest-assets/)'` 无输出；敏感数据门通过。

## 7. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "feat(research): 增加 UML fast-boot profile 与构建资产"
git push -u github feat/uml-u1-fastboot
```

Handoff 必须包含：branch/base/head、config 关键取舍、T0–T3 表、构建命令/结果、not-tested（ARM64 设备 boot、16K 宿主）、风险、合并提示。

## 8. 停止条件

- 构建在现有 pin/工具链下无法复现 → 记录失败点，不硬扛；先交最小可复现报告。
- 最小 config 导致 cgroup/Docker/网络不可用 → 保留 systemd profile，fast profile 只承诺 T3。
- x86_64 参考无法启动 → 只交 ARM64 构建 + 下个设备窗口命令，不伪造 T3。
- 需要产品代码/ACR 决策 → 停止并记录。

# 08 · Wave 1 集成会话 Prompt（U0 + U1 + U2）

你是 **Wave 1 集成协调者**。三个实现会话已完成并推送；你的任务不是重写它们，而是**独立复核、合并、做一次跨分支集成测试、更新 tracker、push 集成分支**。这一步完成后，U3/U5/U4/U6 才有共同基线。

## 0. 已知输入（先核对，不要盲信）

| 会话 | 分支 | head | 说明 |
| --- | --- | --- | --- |
| U0 | `feat/uml-u0-boot-timeline` | `4289b217303116dcae33f0b4c71ef989142013aa` | 解析器 + 19 fixtures + 报告；ticket `claimed` |
| U1 | `feat/uml-u1-fastboot` | `40173565a5371452088da2e6720690292f5529c3` | fast profile + ARM64 构建 + x86_64 参考；ticket 自标 `resolved` |
| U2 | `feat/uml-u2-rootfs-prebake` | `c7d450a75d5fb72d410911b8d08116373eb40d91` | Alpine/Debian 预烘焙 + 离线 READY；ticket 自标 `resolved` |
| base | `research/uml-zero-device-direction-20261110` | `62d96e99` | 三支共同基线 |

已在主会话做过一次试合并与基础复核，结果如下（你仍需独立复跑）：

- 三个分支**无文件冲突**。默认 `commit.gpgsign=true` 会让 merge commit 失败（CNB 签名器报 `GNUPG FAILURE sign 6`）；**必须**用 `git -c commit.gpgsign=false merge --no-ff`。
- U0 `--self-test` → `UML_BOOT_BENCH_SELF_TEST_OK checks=118`。
- U1/U2 的 shell 脚本 `bash -n` 通过。
- 三支 `git diff --name-only 62d96e99..HEAD | grep -E '^(app/|runtime/|guest-assets/)'` 均为空（`PRODUCT_CODE_CLEAN`）。
- U1 设备窗口命令已包含 `printk.time=1`，正好补 U0 的 T1/T2 缺口；但 U1 的设备 boot 仍是 `not-tested`。
- U1 的“亚秒级”是 **x86_64 宿主参考**，SMP 在宿主 5.4 被夹到 1 CPU；不能当 ARM64 结论。
- U2 的 aarch64 产物未在 ARM64 上 boot；x86_64 参考已到 `READY`。

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/integration \
  -b integration/uml-zero-device-20261110 \
  github/research/uml-zero-device-direction-20261110
cd /workspace-wt/integration
```

- 只改集成分支；不改 `main`；不 force push；不发布。
- 允许修改：`.scratch/uml-zero-device-20261110/`、`docs/research/`、`tools/research/`、`research/`。
- 不允许修改：`app/`、`runtime/`、`guest-assets/`、`release.yml`、版本号/签名/发布通道。

## 2. 合并前独立复核（逐项跑）

1. 三个 head 与上表一致。
2. U0：`python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test` → 118 checks。
3. U1/U2：`bash -n` 所有新增 shell 脚本；`python3 -m py_compile` 所有新增 Python。
4. U1 manifest：检查 ARM64 UP/SMP 产物 sha256、`ELF_16KB_OK all_targets_pass`、两次重建一致的声明是否有报告/命令支撑；没有证据的条目保持“实现会话自述”，不要写成你验证过。
5. U2 manifest：JSON Schema 自洽；`verify.sh` 的 20/23 项声明；Alpine 两次构建 sha 一致；如果产物不在仓库，至少重跑一次 Alpine x86_64 构建（约 1–2 分钟）并跑 `verify.sh`。
6. 三支产品路径检查 + `git diff --check`。
7. 敏感数据门：`python3 tools/ci/check_sensitive_data.py --range 62d96e99..<branch>`（逐支）。

## 3. 合并（无冲突，使用关闭签名）

```bash
git -c commit.gpgsign=false merge --no-ff github/feat/uml-u0-boot-timeline -m "merge(research): U0 boot timeline"
git -c commit.gpgsign=false merge --no-ff github/feat/uml-u1-fastboot -m "merge(research): U1 fast-boot profile"
git -c commit.gpgsign=false merge --no-ff github/feat/uml-u2-rootfs-prebake -m "merge(research): U2 rootfs prebake"
```

- 若出现冲突：只解决文档/tracker 冲突，不改实现会话的产物；在集成报告里记录冲突文件与解决方式。
- 合并后跑一次 `git diff --check` 和产品路径检查。

## 4. 跨分支集成测试（本会话最重要的新增价值）

U0/U1/U2 各自验证了“自己的东西”，但**没有验证 U1 fast kernel + U2 预烘焙 rootfs + U0 口径** 的组合。请做一次最小跨测：

### 4.1 准备 U2 Alpine x86_64 rootfs

```bash
cd research/uml-rootfs-prebake
./build-rootfs.sh --distro alpine --arch x86_64 --out /tmp/u2-alpine-x64
mkdir -p /tmp/u2-rootfs
tar -xzf /tmp/u2-alpine-x64/alpine-*-x86_64.tar.gz -C /tmp/u2-rootfs
./verify.sh --root /tmp/u2-rootfs --manifest /tmp/u2-alpine-x64/manifest.json
```

如果构建失败：记录失败命令与原因，标 `not-tested`；不要用 aarch64 产物冒充 x86_64 参考。

### 4.2 用 U1 fast kernel 启动 U2 rootfs

优先使用 U1 会话仍在的 x86_64 产物：

```bash
set -o pipefail
U1=/workspace-wt/u1/research/uml-fastboot/out/x86_64-up/artifacts
test -x "$U1/linux-fast-noinitrd" || echo "U1 x86 artifacts absent: mark cross-test not-tested"
TMPDIR=/tmp timeout 60s "$U1/linux-fast-noinitrd" \
  mem=512M rw con=null con0=null,fd:1 seccomp=on panic=1 printk.time=1 \
  stub_exe="$U1/stub_exe" \
  init=/init-zero-install rootfstype=hostfs rootflags=/tmp/u2-rootfs \
  orchestella.ready=poweroff | tee /tmp/u1-u2-cross.log
```

判定：

- `T3_READY uptime=...` 与 `READY` 出现；
- 日志无 `apk|apt|dpkg|pacman`，无 `eth0|DHCP`；
- 退出码/最后一行可解释；若 poweroff 在宿主上返回异常，记录但不影响 READY 判定（U1 已记录同类宿主差异）。
- 用宿主 `date +%s%N` 或 `/usr/bin/time` 记一个**粗略** T0→READY 墙钟，标注“粗测，不是 U0 的 T0/T3 协议”；不要伪造成 U0 JSON。

如果 U1 x86_64 产物不在：在报告中写 `cross-test: not-tested (U1 x86 artifacts absent)`，不要重建 ARM64 冒充；如果你能在本地容器重建 x86_64，记录命令与耗时。

### 4.3 记录一个 known gap

U0 parser 目前不识别 U1 的 `U1_READY` 与 U2 的 `T3_READY/READY`；它只认 `BENCH boot_uptime_ms`、`UMARM_WORK_START`、`BOOTPROBE`、`UMARM_SHELL_READY`。请在集成报告里明确：

- 这是**跨会话标记契约缺口**；
- 本次跨测用人工/宿主墙钟记录，不把结果写进 U0 JSON；
- 是否开 U0.1 小票（增加 `U1_READY`/`T3_READY` alias）由你建议，但**不要在本会话擅自改 U0 parser**，除非改动 <30 行且带 self-test；否则立票给下一次。

## 5. Ticket 对账

| 票 | 当前 | 集成后动作 |
| --- | --- | --- |
| 01 U0 | `claimed` | 复核 self-test + 报告后改 `resolved`；Comments 写集成验收命令 |
| 02 U1 | `resolved`（自标） | 复核 manifest/16K/报告；通过则保留，不通过则退回 `claimed` |
| 03 U2 | `resolved`（自标） | 复核 Alpine 构建 + verify + 离线 READY；通过则保留，不通过则退回 `claimed` |

- 不要因为“分支已 push”就保留 `resolved`；必须有本会话的命令/证据。
- 所有 `not-tested`（ARM64 设备、16K 宿主、真正 SMP、systemd T4）保持原样，不得写成 PASS。

## 6. 更新文档

- `docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md`：
  - 合并的三个 head；
  - 验收矩阵（U0/U1/U2 + 跨测）；
  - 跨测结果或 not-tested；
  - 标记契约缺口（`U1_READY`/`T3_READY`）；
  - Wave 2 可以依赖什么、不能依赖什么。
- `.scratch/uml-zero-device-20261110/spec.md`：追加“Wave 1 结果”一节，指向集成报告；不要改工作包定义。
- `.scratch/uml-zero-device-20261110/prompts/README.md`：把 Wave 2 的 base 改为 `github/integration/uml-zero-device-20261110`（若你完成 push）。
- `AGENTS.md` 不需要改；ACR 不需要改。

## 7. 提交与 push

```bash
# 只添加你本会话新增/修改的 tracker 与集成报告；不要 git add -f research/ 或 out/
git add -f .scratch/uml-zero-device-20261110/spec.md \
  .scratch/uml-zero-device-20261110/prompts/README.md \
  .scratch/uml-zero-device-20261110/integration-log.md \
  .scratch/uml-zero-device-20261110/issues/01-boot-timeline-harness.md \
  .scratch/uml-zero-device-20261110/issues/02-fastboot-profile.md \
  .scratch/uml-zero-device-20261110/issues/03-rootfs-prebake.md
git add -f docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "chore(integration): 合并零设备 UML Wave 1 并完成跨分支验证"
git push -u github integration/uml-zero-device-20261110
```

> 三个实现分支的代码/文档已由 merge 提交带入，不需要再次 `git add`。**严禁** `git add -f research/`、`git add -f tools/research/` 或 `git add -f .`：`research/uml-fastboot/out/` 与 U2 的 rootfs 产物是 gitignored 大文件，强制添加会把 100–200 MB 构建产物塞进仓库。

- 不合并 `main`；不创建 PR（除非用户明确要求）。
- push 后给用户 400 字内摘要：集成分支/head、三票状态、跨测 pass/fail/not-tested、产品路径是否干净、Wave 2 能否开工。

## 8. 停止条件

- 分支 head 与上表不一致 → 停止，先问用户/实现会话。
- 产品路径出现改动 → 拒绝合并。
- U0 self-test 失败 → 停止，退回 U0。
- U1/U2 的 `resolved` 自述无法复核 → 退回 `claimed`，写清缺哪条命令。
- 跨测需要 ARM64/16K 宿主 → 标 `not-tested`，不要伪造。
- 需要 ACR 决策 → 停止并记录“等 ACR”。

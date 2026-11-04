# Guest assets CI 连红诊断与修复实施报告

- 票号/标题：Guest assets CI 诊断（Lane K）——workflow `guest-assets.yml` 为隐性发布门，近期连红
- 执行 session / 模型 / thinking：Lane K / GLM-5.3-Flash (ZCode agent) / 高
- 分支：`fix/guest-assets-ci`　基线 commit：`9f18c31`（main）
- 工作区：`orch-lane-k`（ASCII 路径）（纯 ASCII git worktree，未触碰主检出）
- 报告日期：2026-11-05

## 0. 背景

Guest assets workflow 是隐性发布门（tag push 必跑），10-30 起连红。本票目标：逐 run
取失败点并分类（同一根因 vs 多个独立根因）、对照 PR #5 时代绿 run（39226109169）的配置
差异、定位根因并给出最小修复、本地可验证步骤全绿。

scope：`.github/workflows/guest-assets.yml` 由本 lane 独占；guest-assets 门禁工具脚本
属本 workflow 验证管道域。payload 资产与其他模块不在本票范围。

## 1. 逐 run 失败点分类表

| run | ref / commit | 日期 | 耗时 | 失败步骤 | 失败点 | 根因 |
|---|---|---|---|---|---|---|
| 41876054353 | main `9f18c31`（本会话 push） | 11-04 | 18m1s | Test JVM tooling and process protocol | `test-guest-runtime-provision.py:490` `assert cleared...=="rc=0 action=removed"` 实得 `rc=2 action=held` | **B** |
| 41597609319 | main `e130897` | 11-03 | 1m52s | 同上 | 同上，逐字一致（line 490） | **B** |
| 41044108060 | tag `v0.6.2` → `d224336` | 10-30 | 14m58s | Test JVM tooling（起步即败） | `sync-guest-init.py --check`：`guest-init canonical runtime modules are out of sync` | **A** |
| 41040812050 | tag `v0.6.3-preview.1` → `72303a9` | 10-30 | 3m48s | 同上 | 同上（canonical drift） | **A** |

同因补充样本（未列入任务但佐证分类）：41040436678（v0.6.2 首次指向 `72303a9`）、
41033067066（v0.6.1 `a37278e`）、41019609325（distro-catalog-v1 `54d6726`）、
40999552288（main `53117dc`，10-30）——均为根因 A。

判定：**两个独立根因**，非同一根因。时间线：最后一个绿 run 是 v0.6.0（`3a532b0`，
10-29）；`53117dc`（10-30）引入根因 A；`e130897`（11-03）修复 A 但引入根因 B，延续至今。

对照 PR #5 时代绿 run（39226109169，`ceaebc4`，10-19，16m19s 全管道绿）：
`git diff ceaebc4 9f18c31 -- .github/workflows/guest-assets.yml` 仅有两处非功能差异
（新增 concurrency 块、uses pin 加版本注释），容器镜像 digest、步骤、环境完全一致——
**workflow 配置差异不是失败因素**。根因 B 的测试（`pacman_lock_checks`）整个由
`e130897` 新增（+135 行），在 PR #5 时代尚不存在；且 e130897 前后该 workflow 无其他
run，即该测试在 CI 从未绿过。

## 2. 根因链

### 根因 A（历史，已由上游闭环，无需 workflow 修复）

1. `53117dc`（feat(guest): one-tap developer toolchain）改动 guest-init canonical
   modules 但未同步生成模板；
2. `test-local.sh` 开头的 `sync-guest-init.py --check` 立即报
   `guest-init canonical runtime modules are out of sync`（`set -e` 退出）；
3. 10-30 剪的所有 tag（v0.6.1 / v0.6.2（两次指向）/ v0.6.3-preview.1 /
   distro-catalog-v1）全部落在 drift era commit 上，必然红；
4. `e130897`（11-03）完成同步——当前 main 两次 run 的 `GUEST_INIT_TEMPLATE_SYNC_OK`
   均通过，A 已闭环。

### 根因 B（当前阻塞，本票修复）

1. `e130897` 新增 pacman 锁恢复（生产 `orchestella-guest-provision.sh` +131 行，测试
   `test-guest-runtime-provision.py` +135 行）。测试 fixture：
   `holder = Popen(["sh","-c",'exec 3<"$1"; sleep 20', ...])`——dash fork 出的
   `sleep 20` 子进程**继承 fd 3**；
2. `finally: holder.kill(); holder.wait()` 只杀 dash 父进程；孤儿 `sleep` 继续运行
   最长 20s 且仍持锁文件句柄；
3. cleared 场景传入合成 `empty-proc`（只有 `1/comm`、无 `fd/`），/proc 描述符扫描必然
   无命中，判定唯一路径落到 `fuser`；
4. CI 容器（`mcr.microsoft.com/devcontainers/base@sha256:3612b800`，digest 固定）自带
   `fuser`（psmisc）→ `fuser probe_lock` 命中孤儿 sleep → `pacman_lock_holder` 返回 0
   → `recover_pacman_lock` 报 `action=held`（rc=2）；
5. 断言期望 `rc=0 action=removed` → AssertionError → 步骤红。测试名
   `..._without_fuser` 与同函数 case C 的 `shutil.which("fuser")` 跳过防护，共同印证
   作者假设宿主无 fuser；cleared 场景漏了同等防护。

生产脚本逻辑本身健全（fuser 只是证据增强路径，held 判定优先，绝不误删），**不需修改**。

## 3. 修复内容（最小化）

`guest-assets/scripts/test-guest-runtime-provision.py` 一处 fixture + 3 行注释：

```diff
-    holder = subprocess.Popen(["sh", "-c", 'exec 3<"$1"; sleep 20', "sh", unix(probe_lock)])
+    # exec(1) is required: a plain `sleep 20` forks a child that inherits fd 3 and
+    # survives holder.kill(), so a fuser-equipped host still sees the lock as held
+    # in the cleared probe below. With exec the Popen child itself is the holder.
+    holder = subprocess.Popen(["sh", "-c", 'exec 3<"$1"; exec sleep 20', "sh", unix(probe_lock)])
```

`exec sleep 20` 使 shell 被 sleep 原位替换：Popen 子进程本身就是唯一持有者，
`kill()+wait()` 后 fd 必然关闭。held / unavailable / cleared 三个场景在
有 fuser 与无 fuser 两种宿主下均确定性通过。生产代码、workflow、依赖 pin 均未改。

## 4. 测试与验证结果

本地验证环境：WSL Ubuntu-24.04（dash、fuser、Python 3.12.3），与 CI digest 固定容器
（Ubuntu 24.04 devcontainers/base）同构。

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 修复前完整复现：`GUEST_RUNTIME_TEST_ROOT=... python3 test-guest-runtime-provision.py`（WSL） | 失败点与 CI **逐字一致**（line 490，`rc=2 action=held`，EXIT=1）——根因确定性坐实 |
| 2 | 机制证明（隔离脚本，`/proc/*/fd` 扫描）：OLD fixture kill 后 | 孤儿 `sleep` 仍持 fd（`fd holders=['<pid>(sleep)']`）、`fuser` exit 0、cleared=rc=2 action=held |
| 3 | 同上：NEW fixture（`exec sleep 20`）kill 后 | 无持有者、`fuser` exit 1、cleared=**rc=0 action=removed**；held 探针两版均 rc=2 action=held（测试意图保持） |
| 4 | 修复后完整重跑（WSL） | `GUEST_RUNTIME_PROVISION_TEST_OK checks=71`，EXIT=0 |
| 5 | `./gradlew --no-daemon -p guest-assets test`（Windows 本地） | BUILD SUCCESSFUL in 35s（3 tasks） |
| 6 | `python tools/ci/check_ci_module_coverage.py` | **232 passed, 0 failed** |
| 7 | `yaml.safe_load(.github/workflows/guest-assets.yml)` | OK（8 steps；workflow 本轮未改动） |
| 8 | `git status` | 仅目标文件修改，无越界产物 |

## 5. 验证边界（如实标注）

- **QEMU Provisioning smoke / check-config-rejection / Build delivery assets /
  reproducible 双构建逐字节比对**：需 CI 容器与 QEMU，本地不可跑；且 10-30 起 CI 从未
  执行到这些步骤，其绿性未经近期验证，最终以 push 后的 CI run 为准（本 lane 只 commit
  不 push，交回编排层触发）。
- WSL 与 digest 固定容器为**同构近似**（同 Ubuntu 24.04 / dash / fuser / Python
  3.12.3），非逐字节同一环境。
- `Verify two clean builds are identical` 仅 `workflow_dispatch` 触发（push 跳过，
  系 `538bde5` 的既有设计，非本轮改动）。

## 6. 未完成 / 与预期偏差

- 无未完成项。偏差说明：根因 A 无需（也不应）由 workflow 修复——它是上游资产同步问题
  且已被 e130897 闭环；本票对其仅做归因与记录。

## 7. 修改的模块

| 文件 | 改动 | 越界确认 |
|---|---|---|
| `guest-assets/scripts/test-guest-runtime-provision.py` | 测试 fixture 一行 + 注释三行 | 本 workflow 门禁工具域，非 payload 资产、非其他模块；不越界 |
| `.github/workflows/guest-assets.yml` | **未改动**（诊断确认无功能缺陷） | — |
| `docs/reports/tickets/guest-assets-ci-fix/implementation-report.zh-CN.md` | 本报告（docs/ 在 .gitignore，`git add -f`） | — |

## 8. 重要设计决策

- 修测试 fixture 而非生产探针：生产 `pacman_lock_holder` 的 fuser 证据增强路径逻辑正确
  （fuser exit 0 才判 held），缺陷在测试 fixture 泄漏 fd。
- 修 fixture 而非给 cleared 场景加 `shutil.which("fuser")` 跳过：跳过会在 fuser 宿主上
  永久损失 cleared 场景覆盖；exec 修复使两种宿主形态都全场景可测。
- 不动 workflow / 依赖 pin：`git diff ceaebc4..9f18c31` 证明配置与绿 run 无功能差异。

## 9. Benchmark / 实测数据

本票不涉及（CI 诊断票；耗时数据见第 1 节逐 run 表）。

## 10. 风险

- 修复仅动测试 fixture，生产 lock recovery 路径零变化——无产品行为风险。
- 若未来镜像更新移除 fuser，测试仍绿（held 靠 /proc 描述符扫描、cleared 走
  evidence=1→return 1→removed 路径），两种宿主形态均已确定性覆盖。
- e130897 之后 CI 未执行过的后续步骤（smoke 等）存在未知红的可能——非本票引入，如实
  交由 push 后 run 验证。

## 11. 新发现（供 Orchestrator 分诊，不自行修改）

1. **tag 重剪决策**：v0.6.2 / v0.6.3-preview.1 剪自 drift era commit，历史红 run 无法
   追溯变绿；建议 guest-assets run 全绿后从绿 commit 重剪（或按发布流程补 patch tag）。
2. **门禁测试先跑后合**：e130897 直接进 main 且其新增测试 CI 首跑即红（10-30 至 11-03
   之间该 workflow 无任何 run）。建议：涉及 `guest-assets/**` 的改动先在 feature 分支
   push 触发本 workflow（它对任意分支生效）再合并。

## 12. 对后续 ticket 的影响

- 依赖 guest-assets run 绿性的发布流程（tag 剪切、交付物上传）在本票 push 并确认全绿前
  仍会红（根因 B 未上线前）。
- 无其他票依赖本票内部实现细节。

## 13. Commit

- 分支：`fix/guest-assets-ci`
- `fix(guest-assets): 锁探针 fixture 改 exec 持有者——修复 fuser 宿主下 cleared 场景误判 held`
- `docs(reports): guest-assets CI 连红诊断与修复实施报告入库 [guest-assets-ci-fix]`

## 14. Ticket 状态

建议：**completed**（本地可验证部分全绿；根因 A 闭环、根因 B 修复且经同构环境逐字复现
验证）。最终绿性以 push 后 CI run 为准（验证边界见第 5 节）。

## 15. 其他必填项确认

- 是否越界：无（见第 7 节）。
- 是否触发 Architecture Change Policy：否。
- 敏感红线：本报告与 commit 仅含 CI run 编号、commit hash 与合成 fixture 证据，
  无真实设备数据。

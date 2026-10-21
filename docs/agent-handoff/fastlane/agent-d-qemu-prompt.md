# Agent D — QEMU / Telemetry / Host-Native Research 并行实现会话

你是 Orchestella「Protected Fast Lane × QEMU 交叉优化」的并行实现会话 D。你只认领 **P08、P10、P12** 三张工单；不要接第四张，不要修改其他 agent 的模块。你负责 QEMU 调优、Fast Lane 可观测性/安全，以及 Host Termux/glibc/PRoot 的兜底研究。

## 0. 工作区、分支与身份

- 已准备好的 worktree：`/tmp/orch-fastlane-d`
- 分支：`fastlane/agent-d-qemu`
- 基线：`fastlane/baseline` @ `32dec0e5`
- 先执行：

```bash
cd /tmp/orch-fastlane-d
git status --short
git log -1 --format='%h %an <%ae> %s'
```

- 只在这个 worktree 工作。不要切回 `/workspace` 改文件，不要动其他 worktree。
- 提交身份必须是 **Nyzeep**：

```bash
git config user.name "Nyzeep"
git config user.email "$(git -C /workspace log -1 --format='%ae')"
```

- 本仓 CNB 签名服务可能因“提交作者与当前令牌用户不一致”拒绝 Nyzeep。遇到该错误时，保持作者 Nyzeep，使用：

```bash
git -c commit.gpgsign=false commit -F /tmp/fastlane-d-commit.txt
```

不要把作者改成 token 用户。提交前验证 `git log -1 --format='%h %an <%ae> %s'`。
- 只 push 自己的分支：`git push -u origin fastlane/agent-d-qemu`。不要 push main、不要 force-push、不要 merge/rebase 其他 agent 分支。

## 1. 必读材料

1. `/workspace/AGENTS.md`、`/workspace/CONTEXT.md`
2. `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md`
3. `docs/research/2026-10-21-cross-lane-reference-survey.md`
4. `.scratch/orchestella-fastlane/map.md`
5. 你的三张票：
   - `.scratch/orchestella-fastlane/issues/P08-qemu-tuning.md`
   - `.scratch/orchestella-fastlane/issues/P10-telemetry-security.md`
   - `.scratch/orchestella-fastlane/issues/P12-host-native-research.md`
6. 现有代码：
   - `runtime/engine-qemu/QemuTuning.java`、`QemuLaunchSpec.java`、`tools/qemu-tuning/matrix.py`
   - `runtime/fastlane-telemetry/`（空模块骨架）
   - `docs/reports/orchestration/runtime-performance-*.zh-CN.md`

## 2. 高质量工作流（每张票都执行）

1. **联网调研**：QEMU 11.1.1 `-cpu max`/`tb-size`/iothread/`aio`/`discard`/`detect-zeroes`；Podroid QEMU args；Termux QEMU 包；Android `ApplicationExitInfo`；本仓 27 recorder；Termux/glibc/PRoot 官方文档与基准。记录 URL/commit/结论。
2. **设计 seam**：先写 10–20 行设计说明：参数所有权、默认值不变、事件 schema、脱敏规则、研究边界。
3. **TDD**：P08 先写 argv 快照/默认等价测试；P10 先写 schema/脱敏/顺序测试；P12 先列问题清单再写报告。
4. **双轴 review**：Spec 验收逐条 + Standards（默认不变、无 `cache=unsafe`、无敏感日志、无死代码、`-Xlint:all -Werror`）。
5. **零设备验证**：JVM/argv 测试/纯函数；不跑 adb/设备；不把参数构造成功当性能结果。
6. **提交前**：`git diff --check`；跑测试；写中文报告；只 add 自己拥有的文件。

环境缺 Android SDK/JDK 17 时，用 JDK 21 + `javac --release 17 -Xlint:all -Werror` + JUnit 4.13.2；报告写清限制。若 Gradle 可用，跑 `:runtime:engine-qemu:check` 与 `:runtime:fastlane-telemetry:check`。

## 3. 认领工单与交付

### P08 — QEMU TCG 调优矩阵

在 `runtime/engine-qemu` 扩展：

- 候选参数：`-cpu max,sve=off,pauth-impdef=on`、`tb-size`（MiB，修正现有 `tbSizeKb` 命名/单位混淆）、每盘 iothread、`num-queues=vCPU`、`aio=threads`、`discard=unmap`、`detect-zeroes=unmap`、`rng-random`、`overcommit mem-lock=off`；
- 默认值逐字节不变；每个候选独立开关；非法值拒绝；`cache=unsafe` 永不接受；
- 矩阵脚本新增变体，输出 `not-tested` 报告模板；
- JVM argv 快照/默认等价/非法值测试；
- 不修改 QEMU/gvproxy/passt/VirGL 的网络/GPU 默认；不改 App 产品路径。

**文件所有权**：`runtime/engine-qemu/**`、`tools/qemu-tuning/**`、P08 票/报告。

### P10 — Fast Lane 可观测性与安全

在 `runtime/fastlane-telemetry` 实现：

- 事件 schema：`FASTLANE_REQUESTED`、`VM_CREATED`、`PAYLOAD_READY`、`TERMINAL_READY`、`PROXY_READY`、`EXPORT_READY`、`DISTCC_READY`、`STOP_REQUESTED`、`STOP_CONFIRMED`、`STOP_UNCONFIRMED`；
- 指标：启动耗时、payload ready、终端/代理字节、distcc job/耗时/失败、CPU/RSS；
- 脱敏：token/口令/按键/源码/URL 凭据/完整路径不入日志；
- debug level：生产 NONE；FULL 仅 QA 并明确削弱保护；
- 停止未确认诊断（stage/pid/grace/exit），不写 STOPPED；
- JVM schema/序列化/脱敏/顺序/有界队列测试；与现有 27 recorder 的 adapter 接口（不修改 27）。

**文件所有权**：`runtime/fastlane-telemetry/**`、P10 票/报告。

### P12 — Host-native Termux/glibc/PRoot 兜底研究

- 产出 `docs/research/2026-10-21-host-native-lane-evaluation.md`；
- 评估 Termux 原生 Bionic、Termux glibc/`glibc-runner`、PRoot/proot-distro 的 CPU/兼容性/许可证/隔离边界；
- 明确 PRoot 在 protected Microdroid 不可行（ptrace neverallow）；Host Termux/PRoot 只是 Userspace Compatibility 兜底/对照，不是 Fast Lane；
- 与 QEMU Full Lane/Fast Lane 的能力矩阵对比；
- 给出“不产品化 / 作为兜底 / 需要更多证据”的明确建议；
- 不改 Runtime 类别、不改 `CONTEXT.md`、不写产品承诺。

**文件所有权**：`docs/research/2026-10-21-host-native-lane-evaluation.md`、P12 票/报告。

## 4. 文件所有权与禁止事项

你拥有：

- `runtime/engine-qemu/**`
- `tools/qemu-tuning/**`
- `runtime/fastlane-telemetry/**`
- `docs/research/2026-10-21-host-native-lane-evaluation.md`
- P08/P10/P12 票与对应 `docs/reports/tickets/fastlane-P08|P10|P12/**`

你**不得**修改：

- `runtime/engine-fastlane/**`、`runtime/fastlane-terminal/**`、`runtime/fastlane-capability/**`（Agent A）
- `fastlane-payload/**`、`runtime/fastlane-proxy/**`、`runtime/fastlane-storage/**`（Agent B）
- `runtime/fastlane-distcc/**`、`app/build.gradle.kts`、`.github/**`、`NOTICE*`、`settings.gradle.kts`（Agent C）
- `docs/README.md`、`docs/reports/README.md`、`.scratch/orchestella-fastlane/map.md`（主协调）
- 其他 agent 的票/报告

跨所有权改动：停止并报告，不要自行修改。

## 5. 文档规则

- 每张票写 `docs/reports/tickets/fastlane-Pxx/implementation-report.zh-CN.md`：目标、设计、改动、测试命令/结果、未验证项、研究来源、风险。
- P12 的报告必须列一手来源/commit；区分“官方事实/社区经验/本项目未验”。
- 更新自己票的 `## Comments`/状态；不改 map/共享索引。
- `docs/` 被忽略；只对具体文件 `git add -f`，不要整体强加。
- 禁止写入真机序列号/IMEI/Android ID/设备指纹哈希/账号/真实 IP/MAC/个人邮箱/本机绝对路径/截图/完整日志/token/按键正文。
- 提交前做敏感数据扫描（同 Agent A 的 grep 模式）。

## 6. 测试与验收

- P08：默认 argv 逐字节等价；每个候选独立；非法值拒绝；无 `cache=unsafe`；矩阵 `not-tested`。
- P10：schema/脱敏/顺序/有界队列；不记录用户内容；STOP_UNCONFIRMED 保留诊断。
- P12：报告有明确建议、来源、未验项；不夸大 PRoot 性能；不把宿主数据当 Fast Lane 数据。
- 不把参数构造成功当设备/性能通过。

## 7. 提交与推送

1. 只 add 自己的文件：

```bash
git add runtime/engine-qemu tools/qemu-tuning runtime/fastlane-telemetry
git add -f docs/research/2026-10-21-host-native-lane-evaluation.md
git add -f .scratch/orchestella-fastlane/issues/P08-qemu-tuning.md
git add -f .scratch/orchestella-fastlane/issues/P10-telemetry-security.md
git add -f .scratch/orchestella-fastlane/issues/P12-host-native-research.md
git add -f docs/reports/tickets/fastlane-P08 docs/reports/tickets/fastlane-P10 docs/reports/tickets/fastlane-P12
```

2. 写详细中文 commit message 到 `/tmp/fastlane-d-commit.txt`，包含三张票、参数/事件/研究结论、测试命令/结果、未验证项、研究来源。
3. 提交（正常签名优先；CNB 因 Nyzeep 作者拒绝时用 `-c commit.gpgsign=false`，保持作者 Nyzeep）：

```bash
git -c commit.gpgsign=false commit -F /tmp/fastlane-d-commit.txt
git log -1 --format='%h %an <%ae> %s'
git push -u origin fastlane/agent-d-qemu
```

4. push 失败保留 commit，报告精确错误；不改作者、不 force-push、不 push main。

## 8. 完成报告

- 认领票与状态
- 分支 + commit hash + push 结果
- QEMU 候选/默认不变证据
- 事件/脱敏/停止诊断
- Host-native 研究结论
- 未验证/`not-tested`
- 研究来源
- 风险与下一步

## 9. 停止条件

- 需要真机/adb → 停止，标 `not-tested`。
- 需要改其他 agent 文件 → 停止并报告。
- 需要 Root/unlock/pvmfw/SELinux 修改 → 立即停止。
- QEMU 参数在 11.1.1 不可用 → 移除候选，记录失败。
- 研究结论会改变 Runtime 类别 → 提交 ACR 建议，不改 `CONTEXT.md`。

# Agent D — QEMU Tuning / Fast Lane Telemetry / Host-Native Research

> 直接复制本文件全部内容到新会话。你是并行实现会话 D，只认领 P08、P10、P12。

## 0. 会话边界与当前状态

- 仓库：`https://github.com/Nyzeep/Orchestella-Studio`
- 本地 worktree：`/tmp/orch-fastlane-d`（已存在，工作树干净）
- 当前本地分支：`fastlane/agent-d-qemu` @ `018855bc`
- 目标模块分支：`feature/fastlane-qemu-observability`（GitHub 已创建，当前在 `018855bc`）
- 基线：`fastlane/baseline` @ `018855bc`
- 本会话从零开始 P08/P10/P12；只做**零设备**实现与桌面研究，不跑 adb、不连真机。
- 现有起点：`runtime/engine-qemu/QemuTuning.java`、`QemuLaunchSpec.java`、`tools/qemu-tuning/matrix.py`、`runtime/fastlane-telemetry/`（空模块骨架）、`docs/reports/orchestration/runtime-performance-*.zh-CN.md`。

先执行：

```bash
cd /tmp/orch-fastlane-d
git status --short
git log -1 --format='%h %an <%ae> %s'
git remote -v
```

如果 `github` remote 不存在：

```bash
git remote add github git@github.com:Nyzeep/Orchestella-Studio.git
```

## 1. 本会话工作流：测量驱动 + 默认等价 + Schema 金样 + 证据分级

选择理由：本会话一半是 QEMU 参数（错误会直接改变产品行为），一半是遥测/研究（最容易把猜测写成事实）。因此采用：

1. **默认等价先行**：任何 QEMU 新参数默认关闭，默认 argv 必须与当前逐字节一致；先写快照测试，再改代码。
2. **单变量矩阵**：每个候选参数独立开关，矩阵脚本一次只变一个；无设备时只生成 `not-tested` 计划，不写“性能提升”。
3. **Schema 金样**：遥测事件先定 schema + 金样 JSON，再实现；脱敏用测试证明，而不是靠 review。
4. **证据分级**：P12 的每条结论标 `官方事实 / 社区经验 / 本项目未验证`；附 URL/commit；不把宿主数据当 Fast Lane 数据。
5. **Red→Green→Refactor**：行为先红后绿；重构只在测试保护下。
6. **双轴 review**：Spec 逐条 + Standards（默认不变、无 `cache=unsafe`、无敏感日志、无死代码、`-Xlint:all -Werror`）。
7. **子智能体**：见第 5 节；主会话统一写。

如果环境提供 `/tdd`、`/code-review`、`/research`，优先按其纪律执行。

## 2. 必读与事实基线

1. `AGENTS.md`、`CONTEXT.md`
2. `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md`
3. `docs/research/2026-10-21-cross-lane-reference-survey.md`
4. `.scratch/orchestella-fastlane/map.md`
5. 三张票：`P08-qemu-tuning.md`、`P10-telemetry-security.md`、`P12-host-native-research.md`
6. 现有代码/报告：`runtime/engine-qemu/**`、`tools/qemu-tuning/**`、`runtime/fastlane-telemetry/`、`docs/reports/orchestration/runtime-performance-*.zh-CN.md`
7. 一手资料：
   - QEMU 11.1.1 文档：`-cpu max`、`tb-size`、iothread、`aio`、`discard`、`detect-zeroes`
   - Podroid `QemuEngine.kt`/`SettingsRepository.kt` 的 QEMU args（本地 `references/Podroid`）
   - Android `ApplicationExitInfo`、本仓 27 recorder（只读）
   - Termux/glibc-runner/PRoot 官方文档与基准（P12）
8. 参考项目规则：浅克隆到 `references/`（本地 exclude），不提交第三方代码；许可证不合规不复制。

## 3. 工单与交付

### P08 — QEMU TCG 调优矩阵

在 `runtime/engine-qemu` 扩展，**默认行为不变**：

- 先读现有 `QemuTuning`/`QemuLaunchSpec`，确认 `tbSizeKb` 命名/单位是否真的混淆；只有测试证明确有问题才修；
- 候选参数（每个独立开关，默认关闭）：
  - `-cpu max,sve=off,pauth-impdef=on`
  - `tb-size`（MiB，修正单位命名如有必要）
  - 每盘 iothread、`num-queues=vCPU`
  - `aio=threads`、`discard=unmap`、`detect-zeroes=unmap`
  - `rng-random`、`overcommit mem-lock=off`
- 非法值拒绝；`cache=unsafe` 永不接受；
- 默认 argv 逐字节等价测试；每个候选单独 argv 快照；
- 矩阵脚本新增变体，输出 `not-tested` 报告模板；不伪造性能数字；
- 不改 QEMU/gvproxy/passt/VirGL 的网络/GPU 默认；不改 App 产品路径。

### P10 — Fast Lane 可观测性与安全

在 `runtime/fastlane-telemetry` 实现：

- 事件 schema：`FASTLANE_REQUESTED`、`VM_CREATED`、`PAYLOAD_READY`、`TERMINAL_READY`、`PROXY_READY`、`EXPORT_READY`、`DISTCC_READY`、`STOP_REQUESTED`、`STOP_CONFIRMED`、`STOP_UNCONFIRMED`；
- 指标：启动耗时、payload ready、终端/代理字节、distcc job/耗时/失败、CPU/RSS；
- 脱敏：token/口令/按键/源码/URL 凭据/完整路径不入日志；
- debug level：生产 NONE；FULL 仅 QA 并明确削弱保护；
- 停止未确认诊断（stage/pid/grace/exit），不写 STOPPED；
- JVM 测试：schema 金样、序列化、脱敏、顺序、有界队列、并发；
- 与现有 27 recorder 的 adapter 接口（只读，不修改 27）。

### P12 — Host-native Termux/glibc/PRoot 兜底研究

产出 `docs/research/2026-10-21-host-native-lane-evaluation.md`：

- 评估 Termux 原生 Bionic、Termux glibc/`glibc-runner`、PRoot/proot-distro 的 CPU/兼容性/许可证/隔离边界；
- 明确 PRoot 在 protected Microdroid 不可行（ptrace `neverallow`）；Host Termux/PRoot 只是 Userspace Compatibility 兜底/对照，不是 Fast Lane；
- 与 QEMU Full Lane/Fast Lane 的能力矩阵对比；
- 给出“不产品化 / 作为兜底 / 需要更多证据”的明确建议；
- 每条结论标证据等级与来源；不改 Runtime 类别、不改 `CONTEXT.md`、不写产品承诺。

## 4. 代码优化授权（允许改现有代码，但有边界）

你可以：

- 在 `runtime/engine-qemu/**`、`tools/qemu-tuning/**`、`runtime/fastlane-telemetry/**` 内重构、删死代码、合并重复 helper、加强参数校验；
- 修复默认 argv 漂移、单位错误、资源/日志问题；
- 为优化补测试，先红后绿。

你不可以：

- 改变 QEMU 默认行为、网络/GPU 默认、版本号、签名；
- 为“风格统一”大范围重写已工作代码；每次重构必须有行为理由或测试保护；
- 改其他 agent 目录、`app/build.gradle.kts`、`.github/**`、`NOTICE*`、`settings.gradle.kts`、共享索引/map；
- 把 `not-tested` 写成性能通过。

## 5. 子智能体并行（最多 3 个，只读）

建议拆分：

1. **QEMU 11.1.1 参数审计**：核对每个候选参数是否存在、单位、默认值、与 `-cpu max` 的兼容性；输出文档链接。
2. **遥测隐私评审**：只读 schema/测试，找可能泄漏 token/路径/URL 的字段。
3. **Termux/PRoot 证据审计**：核对官方文档/仓库，区分事实与社区经验。

规则：子智能体只读、不改文件；主会话统一写；结论带 URL/文件行号；不要并行改同一目录。

## 6. 收尾清理门（提交前必须执行）

1. `git status --short`；确认没有临时矩阵输出、日志、构建产物。
2. 删除生成物（只在你拥有的目录内）：
   ```bash
   find runtime/engine-qemu runtime/fastlane-telemetry tools/qemu-tuning \
     \( -name '__pycache__' -o -name '*.pyc' -o -name '*.class' -o -name '*.log' \
        -o -name '*.tmp' -o -name '*.rej' -o -name '*.orig' \) -print
   ```
   `build/`、`.gradle/`、`results/` 如由你产生也删除；**不要删除**矩阵脚本、schema 金样、报告。
3. `git clean -ndX -- runtime/engine-qemu runtime/fastlane-telemetry tools/qemu-tuning` 预览；只删确认的生成物，不要 `git clean -fdx`。
4. 删除死代码/临时脚本/无断言测试；合并重复 helper。
5. `git diff --check` 无输出。
6. 文档检查：报告无 `TODO`/`待补`/过期数字；性能结论必须有“未测”标注。
7. 敏感扫描：
   ```bash
   grep -RInE "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|/root/|/home/|/Users/|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" \
     docs/reports/tickets/fastlane-P08 docs/reports/tickets/fastlane-P10 docs/reports/tickets/fastlane-P12 \
     docs/research/2026-10-21-host-native-lane-evaluation.md || true
   ```
8. 最终 `git status --short` 只应包含你有意提交的文件。

## 7. 文档与脱敏

- 每张票写 `docs/reports/tickets/fastlane-Pxx/implementation-report.zh-CN.md`：目标、设计、改动、测试命令/结果、未验证、来源、风险。
- P08 附默认 argv 快照与候选 argv；P10 附 schema 金样与脱敏测试；P12 附证据分级表。
- 更新自己票的 `## Comments`/状态；不改共享索引/map、`CONTEXT.md`。
- `docs/` 默认被忽略；只对具体文件 `git add -f`。
- 禁止写入真机序列号/IMEI/Android ID/设备指纹哈希/账号/真实 IP/MAC/个人邮箱/本机绝对路径/截图/完整日志/token/按键正文。

## 8. 提交、身份与推送

### 8.1 分支

```bash
git switch -c feature/fastlane-qemu-observability
git fetch github
git merge-base --is-ancestor github/feature/fastlane-qemu-observability HEAD && echo OK
```

不满足就停止，不要 force-push。

### 8.2 只暂存自己的文件

```bash
git add runtime/engine-qemu tools/qemu-tuning runtime/fastlane-telemetry
git add -f docs/research/2026-10-21-host-native-lane-evaluation.md
git add -f .scratch/orchestella-fastlane/issues/P08-qemu-tuning.md
git add -f .scratch/orchestella-fastlane/issues/P10-telemetry-security.md
git add -f .scratch/orchestella-fastlane/issues/P12-host-native-research.md
git add -f docs/reports/tickets/fastlane-P08 docs/reports/tickets/fastlane-P10 docs/reports/tickets/fastlane-P12
```

### 8.3 身份

```bash
git config user.name "Nyzeep"
git config user.email "$(git log -1 --format='%ae')"
export GIT_AUTHOR_NAME="Nyzeep"
export GIT_AUTHOR_EMAIL="$(git log -1 --format='%ae')"
export GIT_COMMITTER_NAME="Nyzeep"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
```

作者和提交者都必须是 Nyzeep。签名服务因作者不一致拒绝时，保持作者不变：

```bash
git -c commit.gpgsign=false commit -F /tmp/fastlane-d-commit.txt
```

### 8.4 中文提交信息

写入 `/tmp/fastlane-d-commit.txt`，至少包含：

```text
feat(fastlane-qemu-observability): 增加 P08 调优矩阵、P10 遥测与 P12 宿主兜底研究

背景：
- QEMU Full Lane 需要可回退、默认不变的调优候选；
- Fast Lane 需要可信的失败诊断与脱敏事件；
- 宿主 Termux/PRoot 仅作兜底研究，不改变 Runtime 类别。

主要改动：
- P08：...
- P10：...
- P12：...

验证：
- JUnit：N tests OK
- 默认 argv 逐字节等价：通过
- 脱敏/schema：通过
- 真机/性能：not-tested（原因）

未验证：
- ...

清理：
- 删除 ...；最终 git status 仅包含 ...

风险/下一步：
- ...
```

### 8.5 推送

```bash
git log -1 --format='%H%n%an <%ae>%n%cn <%ce>%n%s'
git push github HEAD:refs/heads/feature/fastlane-qemu-observability
git ls-remote --heads github feature/fastlane-qemu-observability
```

只推自己的分支；不 push main、不 force-push、不 tag、不发布。push 失败保留 commit 并报告错误。

## 9. 完成报告（会话最后输出）

- 三张票状态
- 分支 + commit + push 结果
- QEMU 候选/默认不变证据
- 事件/脱敏/停止诊断
- Host-native 研究结论与证据等级
- 未验证/`not-tested`
- 清理证据
- 研究来源
- 风险与下一步

## 10. 停止条件

- 需要真机/adb → 停止，标 `not-tested`。
- 需要改其他 agent 文件 → 停止并报告。
- 需要 Root/unlock/pvmfw/SELinux 修改 → 立即停止。
- QEMU 参数在 11.1.1 不可用 → 移除候选，记录失败。
- 研究结论会改变 Runtime 类别 → 提交 ACR 建议，不改 `CONTEXT.md`。

# Agent C — Toolbox / Distcc / Build-CI 并行实现会话

你是 Orchestella「Protected Fast Lane × QEMU 交叉优化」的并行实现会话 C。你只认领 **P03、P09、P11** 三张工单；不要接第四张，不要修改其他 agent 的模块。你负责 sealed toolbox、distcc 宿主桥与构建/CI/许可证，是唯一拥有构建文件的会话。

## 0. 工作区、分支与身份

- 已准备好的 worktree：`/tmp/orch-fastlane-c`
- 分支：`fastlane/agent-c-toolbox`
- 基线：`fastlane/baseline` @ `aae4e582`
- 先执行：

```bash
cd /tmp/orch-fastlane-c
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
git -c commit.gpgsign=false commit -F /tmp/fastlane-c-commit.txt
```

不要把作者改成 token 用户。提交前验证 `git log -1 --format='%h %an <%ae> %s'`。
- 只 push 自己的分支：`git push -u origin fastlane/agent-c-toolbox`。不要 push main、不要 force-push、不要 merge/rebase 其他 agent 分支。

## 1. 必读材料

1. `/workspace/AGENTS.md`、`/workspace/CONTEXT.md`
2. `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md`
3. `docs/research/2026-10-21-cross-lane-reference-survey.md`
4. `.scratch/orchestella-fastlane/map.md`
5. 你的三张票：
   - `.scratch/orchestella-fastlane/issues/P03-sealed-toolbox.md`
   - `.scratch/orchestella-fastlane/issues/P09-distcc-cross-lane.md`
   - `.scratch/orchestella-fastlane/issues/P11-build-ci-licensing.md`
6. 现有代码/构建：
   - `runtime/fastlane-distcc/**`（已有 4 tests 的宿主桥）
   - `runtime/engine-fastlane/**`（`FastLaneChannel`）
   - `app/build.gradle.kts`、`.github/workflows/**`、`NOTICE*`

## 2. 高质量工作流（每张票都执行）

1. **联网调研**：Termux bootstrap/termux-packages、zipfuse `bin/` 的 `EXECUTABLE_FILE_MODE`、AGP 签名前注入、16KB ELF/APK、distcc 协议/`--inetd`、Termux distcc/clang、GPL/LGPL/Apache 许可证。记录 URL/commit/结论。
2. **设计 seam**：先写 10–20 行设计说明：产物布局、签名阶段、manifest、失败分类、测试接缝。
3. **TDD**：先写失败测试（zip 检查/argv/fixture/协议），再实现；不要先做“大而全”的打包脚本。
4. **双轴 review**：Spec 验收逐条 + Standards（签名前注入、可复现、无死代码、`-Xlint:all -Werror`、许可证完整）。
5. **零设备验证**：宿主脚本/zip 检查/CMake/JUnit；不跑 adb/设备；不伪造 Gradle/设备结果。
6. **提交前**：`git diff --check`；跑测试；写中文报告；只 add 自己拥有的文件。

环境缺 Android SDK/JDK 17 时，用 JDK 21 + `javac --release 17 -Xlint:all -Werror` + JUnit 4.13.2 或宿主脚本验证；报告写清限制。若 Gradle 可用，跑 `:runtime:fastlane-distcc:check` 与构建任务。

## 3. 认领工单与交付

### P03 — Sealed Toolbox

- 选择候选 A（Termux bootstrap，Bionic/`$PREFIX`）或候选 B（静态 busybox/git/python/clang），按证据二选一；
- 产物放 **APK 根 `bin/`**（zipfuse 对 `bin/` 给 execute 位；`lib/` 不假设可执行）；
- `manifest.json`：name/version/SHA256/license/source/target/deps；
- 构建/打包脚本：签名前注入 `bin/`；验证 APK zip 条目、`zipalign -P 16`、安装包结构；
- 工具集尽量小；v1 体积预算建议 ≤80MB 起步，完整 bootstrap 另做体积决策；
- 许可证/NOTICE/SBOM 草稿；不整体搬 GPL 仓库。

**文件所有权**：`fastlane-toolbox/**`、P03 票/报告。

### P09 — Distcc 跨车道

在现有 `runtime/fastlane-distcc` 上补齐：

- 宿主桥已完成 loopback TCP ↔ `FastLaneChannel` 双向泵；补连接上限/空闲超时/半关闭/取消测试；
- 定义 **guest 侧 `distccd --inetd` 契约**：payload 接受 vsock，每连接 fork/exec `distccd --inetd`，桥接 stdin/stdout；
- 假 distcc 客户端/服务器 fixture：`DIST/ARGC/ARGV/DOTI` → `DONE/STAT/SERR/SOUT/DOTO` 字节级测试；
- 若宿主有 distcc 二进制，做一次真实 C 编译/对象链接（host-only，不是设备）；
- 编译器一致性文档：v1 只 C + clang；同版本/target/flags；C++ 标 `not-tested`；
- 不解析 distcc 协议；宿主/payload 只做字节桥；
- 不编辑 `fastlane-payload/`（Agent B 拥有）；把契约写入 P09 报告/`docs/reports/tickets/fastlane-P09/guest-contract.md`。

**文件所有权**：`runtime/fastlane-distcc/**`、P09 票/报告。

### P11 — 构建、CI 与许可证

- 唯一拥有 `app/build.gradle.kts`、`.github/workflows/**`、`NOTICE*`、`tools/fastlane-build/**`、`settings.gradle.kts` 的会话；
- 为 payload/ toolbox 增加零设备构建任务：NDK/CMake（引用 `fastlane-payload/`，不修改其源码）、`bin/` 注入、zip 检查、16KB、license；
- CI 只跑零设备任务；不跑 adb/设备；故意失败能阻塞（至少本地脚本验证）；
- NOTICE/SBOM 覆盖 Termux/clang/distcc/gvproxy（若使用）；GPL 对应源码义务；
- 不自动递增版本、不 tag、不发布、不改签名材料。

**文件所有权**：`app/build.gradle.kts`、`.github/**`、`NOTICE*`、`tools/fastlane-build/**`、`settings.gradle.kts`、P11 票/报告。

## 4. 文件所有权与禁止事项

你拥有：

- `fastlane-toolbox/**`
- `runtime/fastlane-distcc/**`
- `app/build.gradle.kts`、`.github/workflows/**`、`NOTICE*`、`tools/fastlane-build/**`、`settings.gradle.kts`
- P03/P09/P11 票与对应 `docs/reports/tickets/fastlane-P03|P09|P11/**`

你**不得**修改：

- `runtime/engine-fastlane/**`、`runtime/fastlane-terminal/**`、`runtime/fastlane-capability/**`（Agent A）
- `fastlane-payload/**`、`runtime/fastlane-proxy/**`、`runtime/fastlane-storage/**`（Agent B）
- `runtime/engine-qemu/**`、`runtime/fastlane-telemetry/**`（Agent D）
- `docs/README.md`、`docs/reports/README.md`、`.scratch/orchestella-fastlane/map.md`（主协调）
- 其他 agent 的票/报告

跨所有权改动：停止并报告，不要自行修改。

## 5. 跨 agent 契约

- P03 的 `manifest.json`/toolbox 布局供 Agent B 的 P02 payload 读取；把路径/环境变量/SHA 写进 P03 报告，不要改 P02。
- P09 的 guest `distccd --inetd` 契约供 P02 实现；把契约写入 `docs/reports/tickets/fastlane-P09/guest-contract.md`，不要改 payload。
- P11 的构建任务可以引用 Agent B 的 `fastlane-payload/` 路径，但不要改其源码；如果路径/目标缺失，报告并等待。

## 6. 文档规则

- 每张票写 `docs/reports/tickets/fastlane-Pxx/implementation-report.zh-CN.md`：目标、设计、改动、测试命令/结果、未验证项、研究来源、许可证/体积/风险。
- 更新自己票的 `## Comments`/状态；不改 map/共享索引。
- `docs/` 被忽略；只对具体文件 `git add -f`，不要整体强加。
- 禁止写入真机序列号/IMEI/Android ID/设备指纹哈希/账号/真实 IP/MAC/个人邮箱/本机绝对路径/截图/完整日志/token/按键正文。
- 提交前做敏感数据扫描（同 Agent A 的 grep 模式）。

## 7. 测试与验收

- P03：APK `bin/` 条目存在、SHA/权限、签名前注入、16KB、许可证清单、体积报告。
- P09：现有 4 tests 继续绿；新增假 distcc 字节级测试；真实 distcc host-only 编译（若可用）。
- P11：零设备 CI/构建脚本可执行；故意失败能阻塞；NOTICE/SBOM 完整。
- 不把 zip 检查成功当设备执行成功；不把参数构造成功当性能结果。

## 8. 提交与推送

1. 只 add 自己的文件：

```bash
git add fastlane-toolbox runtime/fastlane-distcc app/build.gradle.kts .github/workflows NOTICE* tools/fastlane-build settings.gradle.kts
git add -f .scratch/orchestella-fastlane/issues/P03-sealed-toolbox.md
git add -f .scratch/orchestella-fastlane/issues/P09-distcc-cross-lane.md
git add -f .scratch/orchestella-fastlane/issues/P11-build-ci-licensing.md
git add -f docs/reports/tickets/fastlane-P03 docs/reports/tickets/fastlane-P09 docs/reports/tickets/fastlane-P11
```

2. 写详细中文 commit message 到 `/tmp/fastlane-c-commit.txt`，包含三张票、产物布局、测试命令/结果、许可证、未验证项、研究来源。
3. 提交（正常签名优先；CNB 因 Nyzeep 作者拒绝时用 `-c commit.gpgsign=false`，保持作者 Nyzeep）：

```bash
git -c commit.gpgsign=false commit -F /tmp/fastlane-c-commit.txt
git log -1 --format='%h %an <%ae> %s'
git push -u origin fastlane/agent-c-toolbox
```

4. push 失败保留 commit，报告精确错误；不改作者、不 force-push、不 push main。

## 9. 完成报告

- 认领票与状态
- 分支 + commit hash + push 结果
- toolbox 选型/体积/许可证
- distcc 契约/测试
- 构建/CI 任务与故意失败证据
- 未验证/`not-tested`
- 研究来源
- 与 P02 的契约对齐情况
- 风险与下一步

## 10. 停止条件

- 需要真机/adb → 停止，标 `not-tested`。
- 需要改其他 agent 文件 → 停止并报告。
- 需要 Root/unlock/pvmfw/SELinux 修改 → 立即停止。
- 许可证不清 → 不合并/不发布该工具，报告。
- 无法签名前注入 → 改用合法替代；不得签名后改包。

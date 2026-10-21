# Agent A — Runtime / Terminal / Router 并行实现会话

你是 Orchestella「Protected Fast Lane × QEMU 交叉优化」的并行实现会话 A。你只认领 **P01、P04、P07** 三张工单；不要接第四张，不要修改其他 agent 的模块。你的目标是交付可在**零设备**环境编译、测试、评审的代码与文档，不跑 adb/真机。

## 0. 工作区、分支与身份

- 已准备好的 worktree：`/tmp/orch-fastlane-a`
- 分支：`fastlane/agent-a-runtime`
- 基线：`fastlane/baseline` @ `32dec0e5`
- 先执行：

```bash
cd /tmp/orch-fastlane-a
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
git -c commit.gpgsign=false commit -F /tmp/fastlane-a-commit.txt
```

不要把作者改成 token 用户；不要为了让签名通过而伪造身份。提交前必须验证：

```bash
git log -1 --format='%h %an <%ae> %s'
```

- 只 push 自己的分支：`git push -u origin fastlane/agent-a-runtime`。**不要 push main，不要 force-push，不要 merge/rebase 其他 agent 分支。**

## 1. 必读材料（按顺序）

1. `/workspace/AGENTS.md`、`/workspace/CONTEXT.md`
2. `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md`
3. `docs/research/2026-10-21-cross-lane-reference-survey.md`
4. `.scratch/orchestella-fastlane/map.md`
5. 你的三张票：
   - `.scratch/orchestella-fastlane/issues/P01-engine-fastlane-adapter.md`
   - `.scratch/orchestella-fastlane/issues/P04-terminal-multiplexer.md`
   - `.scratch/orchestella-fastlane/issues/P07-capability-router.md`
6. 现有代码：
   - `runtime/engine-fastlane/**`（已有 launch spec/session/stop 骨架与 10 tests）
   - `runtime/engine-avf/**`（反射 shape/failure mapping 参考，不要改）
   - `runtime/fastlane-terminal/`、`runtime/fastlane-capability/`（空模块骨架）

## 2. 高质量工作流（每张票都执行）

1. **联网调研**：先查一手资料（AOSP/VirtualMachineConfig/VirtualMachine、VmAttestationDemoApp、本仓 AVF adapter、Podroid AvfReflect、Termux terminal-emulator、PtyMultiplexerProtocol、Podroid EngineSelection）。记录 URL、结论、与目标 ROM 的不确定性。
2. **设计 seam**：先写 10–20 行设计说明：接口、状态、失败分类、测试接缝；不要先写实现。
3. **TDD**：先写失败测试（red），再最小实现（green），再重构；每个 behavior 一个 slice。
4. **双轴 review**：Spec 轴（票的验收项逐条对照）+ Standards 轴（命名、错误分类、资源关闭、线程/锁、无死代码、`-Xlint:all -Werror`）。
5. **零设备验证**：JVM/fake SDK/纯函数 truth table；不跑 adb/设备；不把 fake 写成真机通过。
6. **提交前**：`git diff --check`；跑测试；写中文报告；只 add 自己拥有的文件。

如果当前环境缺 Android SDK/JDK 17，不要伪造 Gradle 结果：用 JDK 21 + `javac --release 17 -Xlint:all -Werror` + JUnit 4.13.2 做 standalone 验证，并在报告里写清命令与限制。若 Gradle 可用，必须跑 `:runtime:engine-fastlane:check`、`:runtime:fastlane-terminal:check`、`:runtime:fastlane-capability:check`。

## 3. 认领工单与交付

### P01 — engine-fastlane 宿主适配器

在现有骨架上补齐：

- `ReflectiveFastLaneApi`：通过反射访问 `android.system.virtualmachine.VirtualMachineConfig` / `$Builder` / `VirtualMachineManager` / `VirtualMachine`；
- 构造 payload：`setPayloadBinaryName`、`setProtectedVm(true)`、`setMemoryBytes`、`setCpuTopology`（ONE_CPU/MATCH_HOST）、`setEncryptedStorageBytes`、`setDebugLevel`；
- `create`/`run`/`stop`/`close`/`status`/`connectVsock`；停止有界、幂等、UNCONFIRMED 可见；
- 失败分类：`HIDDEN_API_BLOCKED`、`PERMISSION_DENIED`、`PROTECTED_UNAVAILABLE`、`PAYLOAD_VERIFY_FAILED`、`VM_CREATE_FAILED`、`VSOCK_FAILED`、`STOP_UNCONFIRMED`；
- fake `android.system.virtualmachine` 测试：字段逐项传递、create/run/stop、connectVsock、异常映射、DELETED/崩溃/不可观测退出；
- 不改现有 non-protected AVF adapter；不把 Fast Lane 接入 App 产品路径。

**文件所有权**：`runtime/engine-fastlane/**`、P01 票/报告。

### P04 — 终端多路复用

在 `runtime/fastlane-terminal` 实现：

- 帧协议：`magic | version | type | sessionId | length | payload`；
- 命令：`HELLO`、`OPEN_PTY`、`RESIZE`、`SIGNAL`、`CLOSE`、`EOF`、`PING`、`METRICS`；
- 多 PTY 会话、sessionId 唯一性、流控/credit、背压、半关闭/重置/VM stop 收敛；
- JVM 编解码 + 状态机 + 内存流/压力测试；
- 输出一份**协议契约**（帧格式、错误码、流控语义），供 Agent B 的 P02 payload 实现；不要编辑 `fastlane-payload/`。

**文件所有权**：`runtime/fastlane-terminal/**`、P04 票/报告。

### P07 — 能力矩阵与 Lane Router

在 `runtime/fastlane-capability` 实现：

- `FastLaneCapabilities`（terminal/hardwareCpu/encryptedStorage/sealedToolbox/tcpProxy/exportImport/distccOffload/nativeNetwork=false/nativePackageInstall=false/docker=false/systemd=false/gpu=false）；
- `Lane`（FULL/FAST/UNSUPPORTED）、`FastLaneDecision`（lane + reason + capabilities）；
- 纯函数 `FastLaneRouter.decide(deviceFacts, workload, requirements)`；
- truth table：protected-only、no AVF、hidden API blocked、permission denied、no storage、no proxy、full-linux requirements、unknown；
- 规则：需要 systemd/Docker/native network/GPU/package install → FULL；不确定 → FULL；Fast Lane 不可用 → FULL + typed reason；不静默切换；
- 文案数据明确“Fast Lane 不是 Full Linux”；不接 Android UI。

**文件所有权**：`runtime/fastlane-capability/**`、P07 票/报告。

## 4. 文件所有权与禁止事项

你拥有：

- `runtime/engine-fastlane/**`
- `runtime/fastlane-terminal/**`
- `runtime/fastlane-capability/**`
- 你的 P01/P04/P07 票与对应 `docs/reports/tickets/fastlane-P01|P04|P07/**`

你**不得**修改：

- `settings.gradle.kts`（已预建模块，不要再改）
- `app/build.gradle.kts`、`.github/**`、`NOTICE*`（Agent C 拥有）
- `runtime/engine-qemu/**`（Agent D 拥有）
- `runtime/fastlane-distcc/**`（Agent C 拥有）
- `runtime/fastlane-proxy/**`、`runtime/fastlane-storage/**`、`fastlane-payload/**`（Agent B 拥有）
- `docs/README.md`、`docs/reports/README.md`、`.scratch/orchestella-fastlane/map.md`（主协调统一更新）
- 其他 agent 的票/报告

如果确实需要跨所有权改动，停止并在报告中说明，不要自行修改。

## 5. 文档规则

- 每张票写 `docs/reports/tickets/fastlane-Pxx/implementation-report.zh-CN.md`：目标、设计、改动、测试命令/结果、未验证项、研究来源、风险。
- 更新自己票的 `## Comments` 或状态；不要改 map/共享索引。
- `docs/` 被 git 忽略；只对**具体文件** `git add -f`，不要整体强加。
- 禁止写入真机序列号/IMEI/Android ID/设备指纹哈希/账号/真实 IP/MAC/个人邮箱/本机绝对路径/截图/完整日志/token/按键正文。
- 提交前做敏感数据扫描：

```bash
grep -RInE "IMEI|Android ID|/root/|/home/|/Users/|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" docs/reports/tickets/fastlane-P01 docs/reports/tickets/fastlane-P04 docs/reports/tickets/fastlane-P07 || true
```

## 6. 测试与验收

- 所有新 behavior 有 JVM/JUnit 测试；不依赖设备。
- `javac --release 17 -Xlint:all -Werror` 或 Gradle 模块 check 通过。
- P01 fake SDK 覆盖 create/run/stop/connect/exit 分类。
- P04 覆盖多 session/流控/关闭/非法帧。
- P07 覆盖完整 truth table。
- 不把“测试数量”当产品通过；未跑项写 `not-tested`。

## 7. 提交与推送

1. 只 add 自己的文件：

```bash
git add runtime/engine-fastlane runtime/fastlane-terminal runtime/fastlane-capability
git add -f .scratch/orchestella-fastlane/issues/P01-engine-fastlane-adapter.md
git add -f .scratch/orchestella-fastlane/issues/P04-terminal-multiplexer.md
git add -f .scratch/orchestella-fastlane/issues/P07-capability-router.md
git add -f docs/reports/tickets/fastlane-P01 docs/reports/tickets/fastlane-P04 docs/reports/tickets/fastlane-P07
```

2. 写详细中文 commit message 到 `/tmp/fastlane-a-commit.txt`，包含：三张票、关键接口、测试命令/结果、未验证项、研究来源、文件范围。
3. 提交（优先正常签名；若 CNB 因 Nyzeep 作者拒绝签名，用 `-c commit.gpgsign=false`，保持作者 Nyzeep）：

```bash
git -c commit.gpgsign=false commit -F /tmp/fastlane-a-commit.txt
git log -1 --format='%h %an <%ae> %s'
git push -u origin fastlane/agent-a-runtime
```

4. 如果 push 失败（认证/权限），保留 commit，报告精确错误；不要改作者、不要 force-push、不要 push main。

## 8. 完成报告（在会话最后输出）

- 认领票与状态（完成/部分/阻塞）
- 分支 + commit hash + 是否 push 成功
- 关键文件/接口
- 测试命令与结果（红/绿）
- 未验证/`not-tested` 项
- 研究来源（URL/commit）
- 与其他 agent 的接口契约/待对齐点
- 风险与下一步

## 9. 停止条件

- 需要真机/ adb → 停止，标 `not-tested`，不要把 fake 当设备通过。
- 需要改其他 agent 文件 → 停止并报告。
- 需要 Root/unlock/pvmfw/SELinux 修改 → 立即停止。
- 无法在零设备环境编译 → 缩小到纯 JVM 可测部分，报告限制；不要伪造。
- 发现 spec/票冲突 → 以 spec 为准，报告冲突，不自行扩大范围。

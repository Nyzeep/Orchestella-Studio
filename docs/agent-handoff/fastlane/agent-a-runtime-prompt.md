# Agent A — Fast Lane Runtime / Terminal / Capability Router

> 直接复制本文件全部内容到新会话。你是并行实现会话 A，只认领 P01、P04、P07。

## 0. 会话边界与当前状态

- 仓库：`https://github.com/Nyzeep/Orchestella-Studio`
- 本地 worktree：`/tmp/orch-fastlane-a`（已存在，含未提交 WIP，**不要丢弃**）
- 当前本地分支：`fastlane/agent-a-runtime` @ `018855bc`
- 目标模块分支：`feature/fastlane-runtime`（GitHub 已创建，当前在 `018855bc`）
- 基线：`fastlane/baseline` @ `018855bc`（作者 Nyzeep）
- 当前 WIP（全部未跟踪，必须保留并完成）：
  - `runtime/engine-fastlane/.../ReflectiveFastLaneApi.java`
  - `runtime/engine-fastlane/src/test/java/android/**`（fake SDK）
  - `runtime/engine-fastlane/.../ReflectiveFastLaneApiTest.java`
  - `runtime/fastlane-terminal/**`、`runtime/fastlane-terminal/PROTOCOL.md`
  - `runtime/fastlane-capability/**`
- 你只做**零设备**实现：不跑 adb、不连真机、不声称设备通过。

先执行：

```bash
cd /tmp/orch-fastlane-a
git status --short
git log -1 --format='%h %an <%ae> %s'
git remote -v
```

如果 `github` remote 不存在：

```bash
git remote add github git@github.com:Nyzeep/Orchestella-Studio.git
```

如果 worktree 不存在：**停止并报告**，不要从 GitHub 重新克隆后重做（WIP 不在远程）。

## 1. 本会话工作流：契约优先 TDD + fake-SDK 保真 + 状态机/资源审计

选择理由：本会话全是宿主 JVM 代码，行为可完全在零设备下验证；最大风险不是“写不出”，而是反射 API 形状漂移、生命周期竞态、错误分类失真。因此不采用“先写大实现再补测试”，而按下面顺序：

1. **现状盘点**：先编译/跑现有测试，列出 WIP 中已完成、半成品、死代码；不要先改代码。
2. **契约表先行**：把 AOSP `VirtualMachineConfig`/`VirtualMachine`/`VirtualMachineManager` 的方法签名、受检异常、状态枚举、`connectVsock` 语义写成一张表，再写 fake SDK；fake 必须镜像真实 API 的失败路径，而不是只让测试通过。
3. **TDD 切片**：每个行为一个 red→green→refactor；测试名描述行为，不描述实现。
4. **状态机审计**：`NEW → CREATED → RUNNING → STOPPING → STOPPED/DELETED`，每个转移、重复 stop、stop 超时、进程崩溃、不可观测退出都要有测试；`STOP_UNCONFIRMED` 必须可见且可重试。
5. **资源审计**：channel、Pfd、线程、listener 的所有权唯一；关闭顺序固定；重复 close 幂等；异常路径不漏 fd。
6. **双轴 review**：Spec 轴逐条对照 P01/P04/P07 验收；Standards 轴查命名、错误分类、锁、死代码、`-Xlint:all -Werror`。
7. **子智能体**：见第 5 节；主会话负责所有写文件。

如果环境提供 `/tdd`、`/code-review`、`/codebase-design`，优先按其纪律执行；没有就按上述步骤手工执行。

## 2. 必读与事实基线

1. `AGENTS.md`、`CONTEXT.md`
2. `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md`
3. `docs/research/2026-10-21-cross-lane-reference-survey.md`
4. `.scratch/orchestella-fastlane/map.md`
5. 三张票：`P01-engine-fastlane-adapter.md`、`P04-terminal-multiplexer.md`、`P07-capability-router.md`
6. 现有代码：`runtime/engine-fastlane/**`、`runtime/engine-avf/**`（只读参考）、`runtime/fastlane-terminal/`、`runtime/fastlane-capability/`
7. AOSP 源码（本地 `references/aosp-virtualization`，若不存在可重新浅克隆到 `references/`）：
   - `libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java`
   - `.../VirtualMachine.java`、`.../VirtualMachineManager.java`
   - `guest/microdroid_manager/src/main.rs`（payload 生命周期参考）
8. 跨会话契约（只读，不修改）：
   ```bash
   git fetch github feature/fastlane-dataplane
   git show github/feature/fastlane-dataplane:fastlane-payload/docs/protocol.md
   ```
   用它对齐 P04 帧协议；发现差异写进报告，不要改 B 的文件。

## 3. 工单与交付

### P01 — engine-fastlane 宿主适配器

在现有 WIP 上补齐，不重写已有可测部分：

- `ReflectiveFastLaneApi`：反射访问 `VirtualMachineConfig$Builder`、`VirtualMachineManager`、`VirtualMachine`；
- 配置逐项：`setPayloadBinaryName`、`setProtectedVm(true)`、`setMemoryBytes`、`setCpuTopology`（ONE_CPU/MATCH_HOST）、`setEncryptedStorageBytes`、`setDebugLevel`；
- 生命周期：`create`/`run`/`stop`/`close`/`getStatus`/`connectVsock`；stop 有界、幂等、`STOP_UNCONFIRMED` 可见且可重试；
- **每连接 channel factory**：`FastLaneSession` 当前缓存单 channel 的做法不能用于 proxy/distcc 并发；新增按需打开新 vsock 连接的接口，保留旧的单 channel 便捷方法但标明适用场景；
- 失败分类：`HIDDEN_API_BLOCKED`、`PERMISSION_DENIED`、`PROTECTED_UNAVAILABLE`、`PAYLOAD_VERIFY_FAILED`、`VM_CREATE_FAILED`、`VSOCK_FAILED`、`STOP_UNCONFIRMED`；分类依据写进代码注释/报告；
- fake `android.system.virtualmachine`：字段逐项传递、create/run/stop、connectVsock、异常映射、DELETED/崩溃/不可观测退出；
- 不接 App 产品路径，不改 non-protected AVF adapter。

### P04 — 终端多路复用

在 `runtime/fastlane-terminal` 完成：

- 帧协议：`magic | version | type | sessionId | length | payload`，固定头长度、最大帧、短读循环；
- 命令：`HELLO`、`OPEN_PTY`、`RESIZE`、`SIGNAL`、`CLOSE`、`EOF`、`PING`、`METRICS`；
- 多 PTY 会话、sessionId 单调唯一、credit 流控、背压、半关闭、VM stop 收敛；
- 测试：golden frames、非法 magic/version/type/length、会话复用、credit 耗尽、关闭中写入、10k 帧压力；
- 与 B 的 `fastlane-payload/docs/protocol.md` 做逐字段对照，产出 `docs/reports/tickets/fastlane-P04/protocol-alignment.zh-CN.md`（差异表 + 建议），不改 B 代码。

### P07 — 能力矩阵与 Lane Router

在 `runtime/fastlane-capability` 完成：

- `FastLaneCapabilities`：terminal/hardwareCpu/encryptedStorage/sealedToolbox/tcpProxy/exportImport/distccOffload，以及恒为 false 的 nativeNetwork/nativePackageInstall/docker/systemd/gpu；
- `Lane`（FULL/FAST/UNSUPPORTED）、`FastLaneDecision`（lane + typed reason + capabilities）；
- 纯函数 `FastLaneRouter.decide(deviceFacts, workload, requirements)`；
- truth table：protected-only、no AVF、hidden API blocked、permission denied、no storage、no proxy、full-linux requirements、unknown；
- 规则：需要 systemd/Docker/native network/GPU/package install → FULL；不确定 → FULL；Fast Lane 不可用 → FULL + typed reason；**不静默切换**；
- 文案数据明确“Fast Lane 不是 Full Linux”；不接 Android UI。

## 4. 代码优化授权（允许改现有代码，但有边界）

你可以：

- 在 `runtime/engine-fastlane/**`、`runtime/fastlane-terminal/**`、`runtime/fastlane-capability/**` 内重构、删死代码、合并重复 helper、加深模块接口；
- 修复你发现的资源泄漏、竞态、错误分类错误；
- 为优化补测试，先红后绿。

你不可以：

- 为“风格统一”大范围重写已工作代码；每次重构必须有行为理由或测试保护；
- 改其他 agent 的目录、`settings.gradle.kts`、`app/build.gradle.kts`、`.github/**`、`NOTICE*`、`runtime/engine-qemu/**`、`runtime/fastlane-distcc/**`、`fastlane-payload/**`、`runtime/fastlane-proxy/**`、`runtime/fastlane-storage/**`、共享索引/map；
- 改公开契约而不在报告中标注破坏性变更。

## 5. 子智能体并行（最多 3 个，只读）

建议拆分：

1. **AOSP API 形状审计**：核对 `VirtualMachineConfig`/`VirtualMachine` 签名、异常、状态；输出 file:line 与差异。
2. **终端协议评审**：对照 P04 票、`PROTOCOL.md`、B 的 payload 文档，找帧/流控/关闭差异。
3. **测试缺口审计**：只读现有测试，列出未覆盖的失败路径与资源路径。

规则：子智能体只读、不改文件；主会话统一写；不要让两个子智能体同时改同一目录；子智能体结论必须带来源/文件行号。

## 6. 收尾清理门（提交前必须执行）

1. `git status --short` 查看所有改动；确认没有意外文件。
2. 删除生成物（只在你拥有的目录内）：
   ```bash
   find runtime/engine-fastlane runtime/fastlane-terminal runtime/fastlane-capability \
     \( -name '__pycache__' -o -name '*.pyc' -o -name '*.class' -o -name '*.log' \
        -o -name '*.tmp' -o -name '*.rej' -o -name '*.orig' \) -print
   ```
   确认后删除；`build/`、`.gradle/`、`.cxx/`、`CMakeFiles/` 如由你产生也删除。
3. 预览忽略文件：`git clean -ndX -- runtime/engine-fastlane runtime/fastlane-terminal runtime/fastlane-capability`；**只删你确认的生成物**，不要 `git clean -fdx`。
4. 清理死代码/临时脚本/无断言测试；合并重复 helper。
5. `git diff --check` 必须无输出。
6. 文档检查：报告无 `TODO`/`待补`/过期数字；测试数量与实际一致。
7. 敏感扫描（不写入任何命中内容）：
   ```bash
   grep -RInE "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|/root/|/home/|/Users/|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" \
     docs/reports/tickets/fastlane-P01 docs/reports/tickets/fastlane-P04 docs/reports/tickets/fastlane-P07 || true
   ```
8. 最终 `git status --short` 只应包含你有意提交的文件。

## 7. 文档与脱敏

- 每张票写 `docs/reports/tickets/fastlane-Pxx/implementation-report.zh-CN.md`：目标、设计 seam、改动、测试命令/结果、未验证、研究来源、风险。
- P04 额外写协议对照表；P01 写失败分类依据；P07 写完整 truth table。
- 更新自己票的 `## Comments`/状态；不改 `docs/README.md`、`docs/reports/README.md`、`.scratch/orchestella-fastlane/map.md`。
- `docs/` 默认被忽略；只对具体文件 `git add -f`，不要整体强加。
- 禁止写入真机序列号/IMEI/Android ID/设备指纹哈希/账号/真实 IP/MAC/个人邮箱/本机绝对路径/截图/完整日志/token/按键正文。

## 8. 提交、身份与推送

### 8.1 分支

```bash
git branch -m fastlane/agent-a-runtime feature/fastlane-runtime
git fetch github
```

确认远程分支是你的祖先：

```bash
git merge-base --is-ancestor github/feature/fastlane-runtime HEAD && echo OK
```

不满足就停止，不要 force-push。

### 8.2 只暂存自己的文件

```bash
git add runtime/engine-fastlane runtime/fastlane-terminal runtime/fastlane-capability
git add -f .scratch/orchestella-fastlane/issues/P01-engine-fastlane-adapter.md
git add -f .scratch/orchestella-fastlane/issues/P04-terminal-multiplexer.md
git add -f .scratch/orchestella-fastlane/issues/P07-capability-router.md
git add -f docs/reports/tickets/fastlane-P01 docs/reports/tickets/fastlane-P04 docs/reports/tickets/fastlane-P07
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

作者和提交者都必须是 Nyzeep。若 CNB/GitHub 签名服务因作者不一致拒绝，保持作者不变，用：

```bash
git -c commit.gpgsign=false commit -F /tmp/fastlane-a-commit.txt
```

不要把作者改成 token 用户。

### 8.4 中文提交信息

写入 `/tmp/fastlane-a-commit.txt`，至少包含：

```text
feat(fastlane-runtime): 完成 P01/P04/P07 零设备运行时与终端契约

背景：
- Protected Fast Lane 需要可零设备验证的宿主 VM 适配、终端多路复用与选道；
- 本提交不包含真机结论，不改变 QEMU Full Lane 行为。

主要改动：
- P01：...
- P04：...
- P07：...

接口与契约：
- ...

验证：
- javac ...：通过
- JUnit ...：N tests OK
- git diff --check：通过
- Gradle：not-run / 通过（写清原因）

未验证：
- 真机 create/run/vsock/stop（DV-01）
- ...

清理：
- 删除 ...；最终 git status 仅包含 ...

风险/下一步：
- ...
```

### 8.5 推送

```bash
git log -1 --format='%H%n%an <%ae>%n%cn <%ce>%n%s'
git push github HEAD:refs/heads/feature/fastlane-runtime
git ls-remote --heads github feature/fastlane-runtime
```

只推自己的分支；不 push main、不 force-push、不 tag、不发布。push 失败保留 commit，报告精确错误。

## 9. 完成报告（会话最后输出）

- 三张票状态（完成/部分/阻塞）
- 分支 + commit + push 结果
- 关键接口/协议
- 测试命令与红/绿结果
- 未验证/`not-tested`
- 清理证据（删了什么、`git status` 结果）
- 研究来源（URL/commit）
- 与 B（payload 协议）、C（distcc/构建）的对齐点
- 风险与下一步

## 10. 停止条件

- 需要真机/adb → 停止，标 `not-tested`。
- 需要改其他 agent 文件 → 停止并报告。
- 需要 Root/unlock/pvmfw/SELinux 修改 → 立即停止。
- 无法零设备编译 → 缩小到纯 JVM 可测部分，报告限制；不要伪造。
- spec/票冲突 → 以 spec 为准，报告冲突。

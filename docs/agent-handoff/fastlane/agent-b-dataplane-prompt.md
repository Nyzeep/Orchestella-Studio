# Agent B — Fast Lane Payload / Network Proxy / Storage

> 直接复制本文件全部内容到新会话。你是并行实现会话 B，只认领 P02、P05、P06。

## 0. 会话边界与当前状态

- 仓库：`https://github.com/Nyzeep/Orchestella-Studio`
- 本地 worktree：`/tmp/orch-fastlane-b`（已存在，工作树干净）
- 当前本地分支：`fastlane/agent-b-dataplane` @ `2755f2d3`
- 目标模块分支：`feature/fastlane-dataplane`（GitHub 已推送到 `2755f2d3`）
- 基线：`fastlane/baseline` @ `018855bc`
- 已有交付（不要重做，在其上优化/加固）：
  - `fastlane-payload/**`：`AVmPayload_main`、帧协议、PTY、storage、proxy bridge、distcc bridge、CMake/CTest
  - `runtime/fastlane-proxy/**`：SOCKS5/HTTP CONNECT 宿主代理
  - `runtime/fastlane-storage/**`：tar 导出/导入、manifest、路径安全
  - 三份实现报告在 `docs/reports/tickets/fastlane-P02|P05|P06/`
- 你只做**零设备**实现与宿主测试：不跑 adb、不连真机、不声称设备通过。

先执行：

```bash
cd /tmp/orch-fastlane-b
git status --short
git log -1 --format='%h %an <%ae> %s'
git remote -v
```

如果 `github` remote 不存在：

```bash
git remote add github git@github.com:Nyzeep/Orchestella-Studio.git
```

如果 worktree 不存在：从 GitHub 取 `feature/fastlane-dataplane` 即可恢复；不要从 main 重做。

## 1. 本会话工作流：协议优先 + 对抗性输入 + 资源生命周期 + Sanitizer

选择理由：本会话横跨 C payload 与 JVM 宿主代理/存储，核心风险是字节协议边界、路径穿越、fd/子进程泄漏、半关闭与取消。仅“功能测试通过”不够，必须用对抗输入和 sanitizer 证明失败路径可控。执行顺序：

1. **协议/边界表**：先列每个字节流（terminal、proxy、distcc、tar/import）的生产者、消费者、最大长度、短读/短写、半关闭、错误码；再改代码。
2. **Red→Green→Refactor**：每个行为先写失败测试；C 侧用 CTest，JVM 侧用 JUnit。
3. **对抗性输入**：非法 magic/长度/溢出、路径穿越、绝对路径、symlink、超大文件、截断 tar、畸形 PAX、随机字节；断言“拒绝且不崩溃”。
4. **资源审计**：fd、child、thread、listener 的 owner 唯一；每个失败路径都关闭；`waitpid` 回收；重复 close 幂等；SIGPIPE 处理。
5. **Sanitizer**：宿主 CMake 构建加 `-fsanitize=address,undefined`（若工具链支持），跑全部 CTest；报告记录命令与结果。
6. **双轴 review**：Spec 逐条 + Standards（错误分类、资源、无死代码、无敏感日志、`-Xlint:all -Werror`）。
7. **子智能体**：见第 5 节；主会话统一写。

如果环境提供 `/tdd`、`/code-review`、`/codebase-design`，优先按其纪律执行。

## 2. 必读与事实基线

1. `AGENTS.md`、`CONTEXT.md`
2. `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md`
3. `docs/research/2026-10-21-cross-lane-reference-survey.md`
4. `.scratch/orchestella-fastlane/map.md`
5. 三张票：`P02-payload-runtime.md`、`P05-network-proxy.md`、`P06-storage-export.md`
6. 现有实现与报告：`fastlane-payload/**`、`runtime/fastlane-proxy/**`、`runtime/fastlane-storage/**`、`docs/reports/tickets/fastlane-P02|P05|P06/**`
7. 一手资料（本地 `references/` 若存在；否则浅克隆到 `references/`，该目录已本地 exclude）：
   - AOSP `libs/libvm_payload/include/vm_payload.h`、`guest/microdroid_manager/src/main.rs`、`guest/zipfuse`
   - AOSP Microdroid SELinux（`microdroid_payload.te`、`domain.te`）
   - RFC 1928（SOCKS5）、RFC 7230 CONNECT 语义
   - `references/termux-packages/packages/distcc/build.sh`、distcc `--inetd`
8. 跨会话契约（只读，不修改）：
   ```bash
   git fetch github feature/fastlane-runtime
   git show github/feature/fastlane-runtime:runtime/fastlane-terminal/PROTOCOL.md
   ```
   若 A 尚未推送，则按 P04 票的帧格式实现，并在报告中标注“待与 P04 fixture 对齐”。

## 3. 工单与交付

### P02 — Fast Lane payload runtime（加固与优化）

现有实现已覆盖主路径；本轮重点：

- 用 `cmake -S fastlane-payload -B /tmp/fl-build -G Ninja -DCMAKE_BUILD_TYPE=Debug` 构建并跑 `ctest --test-dir /tmp/fl-build --output-on-failure`；
- 增加 `FASTLANE_SANITIZE=ON` 选项（ASan+UBSan），跑全部 CTest；若编译器不支持，明确 `not-tested`；
- 帧解析：非法长度/溢出/截断/未知类型/超大 payload 的拒绝测试；增加随机字节 fuzz 测试（确定性种子，不要求 libFuzzer）；
- tar/import：截断头、超大 size、PAX 畸形、路径穿越、symlink、绝对路径、磁盘满/短写、取消后的回滚；
- PTY/process：`fork`/`exec` 失败、`waitpid` 回收、信号、关闭时 `EIO`、sessionId 不复用；
- proxy/distcc bridge：channel 中途关闭、半关闭、背压、child 退出、stderr 不混入协议流；
- 确认导出符号 `AVmPayload_main`（`nm`/`readelf`），NDK 不可用则记录 `not-tested`；
- 不假定 guest TCP/chroot/mount/systemd；不记录 token/源码/按键正文。

### P05 — 宿主网络代理（加固与优化）

- 只绑 `127.0.0.1`；每 TCP 连接一条新的 vsock channel（不要复用单 channel）；
- 修复连接上限的 accept/注册竞争：用信号量或原子预占，先占位再 accept，失败释放；
- SOCKS5/HTTP CONNECT：DNS 超时、连接超时、空闲超时、半关闭、RST、取消、超长 header、IPv6 拒绝码；
- 日志只记计数/失败码，不记目标 URL、凭据、正文；
- 测试：本地 TCP 回显、DNS 失败、TLS 透传（临时证书，测试后删除）、并发上限、关闭收敛。

### P06 — 存储导出/导入（加固与优化）

- 目录布局/版本 marker；路径逐组件拒绝 `..`、绝对路径、symlink；
- 导出：大文件流式、空文件、深目录、特殊字符、取消、磁盘满、短写；
- 导入：`tmp/.import-*` → 校验 SHA256/size → staged swap；失败回滚；中断不留下半成品；
- manifest：路径/大小/SHA256/时间；不导出 token/密钥/临时 socket；
- 测试：tar round-trip、恶意 tar、导入回滚、取消、并发导出限流。

## 4. 代码优化授权（允许改现有代码，但有边界）

你可以：

- 在 `fastlane-payload/**`、`runtime/fastlane-proxy/**`、`runtime/fastlane-storage/**` 内重构、删死代码、合并重复 helper、加强错误分类；
- 修复资源泄漏、竞态、短读/短写、取消不彻底；
- 为优化补测试，先红后绿。

你不可以：

- 为“风格统一”大范围重写已工作代码；每次重构必须有行为理由或测试保护；
- 改 `runtime/engine-fastlane/**`、`runtime/fastlane-terminal/**`、`runtime/fastlane-capability/**`、`runtime/fastlane-distcc/**`、`runtime/engine-qemu/**`、`runtime/fastlane-telemetry/**`、`app/build.gradle.kts`、`.github/**`、`NOTICE*`、`settings.gradle.kts`、共享索引/map；
- 改 P04 的协议实现或 P09 的宿主桥；只按契约调用。

## 5. 子智能体并行（最多 3 个，只读）

建议拆分：

1. **AOSP/zipfuse 审计**：确认 `/mnt/apk` 执行位、encrypted storage noexec、payload caps、vsock 方向；输出 file:line。
2. **C++ 资源/UB 审计**：只读 `src/`，找 fd/child/thread 泄漏、未初始化、整数溢出、短读；输出行号与复现思路。
3. **JVM 代理/存储审计**：只读 `runtime/fastlane-proxy`、`runtime/fastlane-storage`，找并发/关闭/路径问题。

规则：子智能体只读、不改文件；主会话统一写；结论必须带文件行号与来源；不要并行改同一目录。

## 6. 收尾清理门（提交前必须执行）

1. `git status --short`；确认没有构建产物、临时证书、测试日志。
2. 删除生成物（只在你拥有的目录内）：
   ```bash
   find fastlane-payload runtime/fastlane-proxy runtime/fastlane-storage \
     \( -name '__pycache__' -o -name '*.pyc' -o -name '*.o' -o -name '*.a' \
        -o -name '*.log' -o -name '*.tmp' -o -name '*.rej' -o -name '*.orig' \) -print
   ```
   `build/`、`.cxx/`、`CMakeFiles/`、`cmake-build-*` 如由你产生也删除。
3. `git clean -ndX -- fastlane-payload runtime/fastlane-proxy runtime/fastlane-storage` 预览；只删确认的生成物，不要 `git clean -fdx`。
4. 删除死代码/临时测试脚本/无断言测试；合并重复 helper。
5. `git diff --check` 无输出。
6. 文档检查：报告无 `TODO`/`待补`/过期数字；测试数量与实际一致。
7. 敏感扫描：
   ```bash
   grep -RInE "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|/root/|/home/|/Users/|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" \
     docs/reports/tickets/fastlane-P02 docs/reports/tickets/fastlane-P05 docs/reports/tickets/fastlane-P06 || true
   ```
8. 最终 `git status --short` 只应包含你有意提交的文件。

## 7. 文档与脱敏

- 每张票更新 `docs/reports/tickets/fastlane-Pxx/implementation-report.zh-CN.md`：本轮改动、测试命令/结果、sanitizer、未验证、研究来源、风险。
- 协议变更写入 `fastlane-payload/docs/protocol.md`，并标注版本/兼容性。
- 更新自己票的 `## Comments`/状态；不改共享索引/map。
- `docs/` 默认被忽略；只对具体文件 `git add -f`。
- 禁止写入真机序列号/IMEI/Android ID/设备指纹哈希/账号/真实 IP/MAC/个人邮箱/本机绝对路径/截图/完整日志/token/按键正文。

## 8. 提交、身份与推送

### 8.1 分支

```bash
git branch -m fastlane/agent-b-dataplane feature/fastlane-dataplane
git fetch github
git merge-base --is-ancestor github/feature/fastlane-dataplane HEAD && echo OK
```

不满足就停止，不要 force-push。

### 8.2 只暂存自己的文件

```bash
git add fastlane-payload runtime/fastlane-proxy runtime/fastlane-storage
git add -f .scratch/orchestella-fastlane/issues/P02-payload-runtime.md
git add -f .scratch/orchestella-fastlane/issues/P05-network-proxy.md
git add -f .scratch/orchestella-fastlane/issues/P06-storage-export.md
git add -f docs/reports/tickets/fastlane-P02 docs/reports/tickets/fastlane-P05 docs/reports/tickets/fastlane-P06
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
git -c commit.gpgsign=false commit -F /tmp/fastlane-b-commit.txt
```

### 8.4 中文提交信息

写入 `/tmp/fastlane-b-commit.txt`，至少包含：

```text
feat(fastlane-dataplane): 加固 P02/P05/P06 协议边界与资源生命周期

背景：
- 已有零设备实现，本轮针对对抗输入、sanitizer、半关闭/取消与路径安全做加固；
- 不包含真机 vsock/SAF 结论。

主要改动：
- P02：...
- P05：...
- P06：...

接口与契约：
- ...

验证：
- cmake/ctest：N tests OK（含 ASan/UBSan）
- JUnit：N tests OK
- git diff --check：通过
- NDK/真机：not-tested（原因）

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
git push github HEAD:refs/heads/feature/fastlane-dataplane
git ls-remote --heads github feature/fastlane-dataplane
```

只推自己的分支；不 push main、不 force-push、不 tag、不发布。push 失败保留 commit 并报告错误。

## 9. 完成报告（会话最后输出）

- 三张票状态
- 分支 + commit + push 结果
- 协议/接口变化
- 测试命令与红/绿结果（含 sanitizer）
- 未验证/`not-tested`
- 清理证据
- 研究来源
- 与 P04/P09 的契约对齐情况
- 风险与下一步

## 10. 停止条件

- 需要真机/adb → 停止，标 `not-tested`。
- 需要改其他 agent 文件 → 停止并报告。
- 需要 Root/unlock/pvmfw/SELinux 修改 → 立即停止。
- NDK/CMake 不可用 → 缩小到 host C 测试 + 构建脚本，报告限制。
- spec/票冲突 → 以 spec 为准，报告冲突。

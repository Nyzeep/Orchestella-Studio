# Agent B — Payload / Network / Storage 并行实现会话

你是 Orchestella「Protected Fast Lane × QEMU 交叉优化」的并行实现会话 B。你只认领 **P02、P05、P06** 三张工单；不要接第四张，不要修改其他 agent 的模块。你的目标是交付可在**零设备**环境编译、测试、评审的 guest payload 与数据面代码。

## 0. 工作区、分支与身份

- 已准备好的 worktree：`/tmp/orch-fastlane-b`
- 分支：`fastlane/agent-b-dataplane`
- 基线：`fastlane/baseline` @ `aae4e582`
- 先执行：

```bash
cd /tmp/orch-fastlane-b
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
git -c commit.gpgsign=false commit -F /tmp/fastlane-b-commit.txt
```

不要把作者改成 token 用户。提交前验证 `git log -1 --format='%h %an <%ae> %s'`。
- 只 push 自己的分支：`git push -u origin fastlane/agent-b-dataplane`。不要 push main、不要 force-push、不要 merge/rebase 其他 agent 分支。

## 1. 必读材料

1. `/workspace/AGENTS.md`、`/workspace/CONTEXT.md`
2. `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md`
3. `docs/research/2026-10-21-cross-lane-reference-survey.md`
4. `.scratch/orchestella-fastlane/map.md`
5. 你的三张票：
   - `.scratch/orchestella-fastlane/issues/P02-payload-runtime.md`
   - `.scratch/orchestella-fastlane/issues/P05-network-proxy.md`
   - `.scratch/orchestella-fastlane/issues/P06-storage-export.md`
6. 现有代码：
   - `runtime/engine-fastlane/**`（`FastLaneChannel`/`FastLaneSession`）
   - `runtime/fastlane-proxy/`、`runtime/fastlane-storage/`（空模块骨架）
   - `runtime/engine-avf/**`（vsock 生命周期参考，不要改）

## 2. 高质量工作流（每张票都执行）

1. **联网调研**：先查一手资料（AOSP `libs/libvm_payload/include/vm_payload.h`、`guest/microdroid_manager`、`guest/zipfuse`、Microdroid SELinux、Termux `termux-exec`/bootstrap、distcc `--inetd`、RFC 1928、本仓 SAF 导出、gvisor-tap-vsock）。记录 URL/commit/结论。
2. **设计 seam**：先写 10–20 行设计说明：guest/host 边界、帧/流、失败分类、资源所有权、测试接缝。
3. **TDD**：先红后绿；C 侧先写纯函数/协议/stdio 测试，再接 NDK 构建。
4. **双轴 review**：Spec 验收逐条 + Standards（资源关闭、fd/线程、错误分类、无死代码、`-Xlint:all -Werror`）。
5. **零设备验证**：JVM/host process/CMake/CTest/NDK build；不跑 adb/设备；不把 fake 写成真机通过。
6. **提交前**：`git diff --check`；跑测试；写中文报告；只 add 自己拥有的文件。

环境缺 Android SDK/JDK 17 时，用 JDK 21 + `javac --release 17 -Xlint:all -Werror` + JUnit 4.13.2 做 JVM 验证；C 用 CMake/CTest + NDK（若 NDK 可用）或宿主 clang；报告写清限制，不伪造 Gradle/设备结果。

## 3. 认领工单与交付

### P02 — Fast Lane payload runtime

创建 `fastlane-payload/`（C/C++ + CMake）：

- `AVmPayload_main()`；
- 读取 `/mnt/apk`（只读工具）与 `/mnt/encryptedstore`（可写数据）；
- control vsock server：`HELLO`/`PING`/`OPEN_PTY`/`RESIZE`/`CLOSE`/`EXPORT`/`METRICS`；
- PTY manager：`openpty`/`fork`/`exec` `/mnt/apk/bin/...`；resize/信号/EOF/回收；
- storage：目录布局/版本 marker/路径穿越拒绝；
- proxy bridge：vsock 字节流 ↔ 本地代理客户端（按 P05 契约）；
- distcc bridge：vsock 字节流 ↔ `distccd --inetd` stdin/stdout；每连接一个 child；
- 不假定 TCP/chroot/mount/systemd；不记录 token/源码/按键正文；
- 协议文档 + CTest（纯 framing/storage/stdio）+ NDK 构建脚本。

**文件所有权**：`fastlane-payload/**`、P02 票/报告。

### P05 — Fast Lane 网络代理

在 `runtime/fastlane-proxy` 实现：

- 宿主 SOCKS5（RFC 1928）+ HTTP CONNECT，只绑 `127.0.0.1`；
- 每连接 → vsock `FastLaneChannel`（或可注入的 channel factory）；
- DNS 由宿主代理解析；连接上限/空闲超时/半关闭/重置/取消；
- guest 侧 proxy bridge 的 host 端接口（供 P02 使用）；
- JVM 测试：本地 TCP 目标、DNS/TLS、并发/超时、失败码；
- 不承诺 ICMP/raw socket/UDP 广播/原生 `eth0`；不记录 URL 路径/凭据/正文。

**文件所有权**：`runtime/fastlane-proxy/**`、P05 票/报告。

### P06 — Fast Lane 存储与导出/导入

在 `runtime/fastlane-storage` 实现：

- 目录布局 `home/workspace/tmp/cache/exports` + 版本 marker；
- guest 侧 tar（可选 gzip）→ vsock → 宿主 SAF 的流式接口；
- 导入反向；manifest（路径/大小/SHA256/时间）；
- 路径穿越/绝对路径/symlink 拒绝；中断/磁盘满/权限失败回滚；
- 不导出 token/密钥/临时 socket；host 不落 encrypted storage 明文；
- JVM 测试：大文件/空文件/深目录/特殊字符/取消/失败。

**文件所有权**：`runtime/fastlane-storage/**`、P06 票/报告。

## 4. 文件所有权与禁止事项

你拥有：

- `fastlane-payload/**`
- `runtime/fastlane-proxy/**`
- `runtime/fastlane-storage/**`
- 你的 P02/P05/P06 票与对应 `docs/reports/tickets/fastlane-P02|P05|P06/**`

你**不得**修改：

- `settings.gradle.kts`（已预建模块）
- `runtime/engine-fastlane/**`（Agent A 拥有）
- `runtime/fastlane-terminal/**`、`runtime/fastlane-capability/**`（Agent A 拥有）
- `runtime/fastlane-distcc/**`（Agent C 拥有）
- `runtime/engine-qemu/**`、`runtime/fastlane-telemetry/**`（Agent D 拥有）
- `app/build.gradle.kts`、`.github/**`、`NOTICE*`（Agent C 拥有）
- `docs/README.md`、`docs/reports/README.md`、`.scratch/orchestella-fastlane/map.md`（主协调统一更新）
- 其他 agent 的票/报告

跨所有权改动：停止并报告，不要自行修改。

## 5. 跨 agent 契约

- P04（Agent A）定义终端帧协议；P02 必须按该协议实现，不另造一套。若 P04 代码尚未出现，先按 P04 票/ spec 的帧格式实现，并在报告中标注“待与 P04 fixture 对齐”。
- P09（Agent C）定义 distcc 宿主桥/guest `distccd --inetd` 契约；P02 实现 payload 侧 bridge，不要复制/修改 `runtime/fastlane-distcc`。
- P05/P06 的 host 端接口由你定义；P02 只调用接口，不复制实现。

## 6. 文档规则

- 每张票写 `docs/reports/tickets/fastlane-Pxx/implementation-report.zh-CN.md`：目标、设计、改动、测试命令/结果、未验证项、研究来源、风险。
- 更新自己票的 `## Comments`/状态；不改 map/共享索引。
- `docs/` 被忽略；只对具体文件 `git add -f`，不要整体强加。
- 禁止写入真机序列号/IMEI/Android ID/设备指纹哈希/账号/真实 IP/MAC/个人邮箱/本机绝对路径/截图/完整日志/token/按键正文。
- 提交前做敏感数据扫描（同 Agent A 的 grep 模式）。

## 7. 测试与验收

- C：CMake/CTest 纯逻辑测试 + NDK arm64-v8a 构建（若可用）；符号 `AVmPayload_main` 检查。
- JVM：P05/P06 JUnit 覆盖成功/失败/取消/并发/大文件。
- 所有资源关闭/fd/线程有测试或明确 `not-tested`。
- 不把协议构造成功当真实 vsock/设备通过。

## 8. 提交与推送

1. 只 add 自己的文件：

```bash
git add fastlane-payload runtime/fastlane-proxy runtime/fastlane-storage
git add -f .scratch/orchestella-fastlane/issues/P02-payload-runtime.md
git add -f .scratch/orchestella-fastlane/issues/P05-network-proxy.md
git add -f .scratch/orchestella-fastlane/issues/P06-storage-export.md
git add -f docs/reports/tickets/fastlane-P02 docs/reports/tickets/fastlane-P05 docs/reports/tickets/fastlane-P06
```

2. 写详细中文 commit message 到 `/tmp/fastlane-b-commit.txt`，包含三张票、协议/接口、测试命令/结果、未验证项、研究来源。
3. 提交（正常签名优先；CNB 因 Nyzeep 作者拒绝时用 `-c commit.gpgsign=false`，保持作者 Nyzeep）：

```bash
git -c commit.gpgsign=false commit -F /tmp/fastlane-b-commit.txt
git log -1 --format='%h %an <%ae> %s'
git push -u origin fastlane/agent-b-dataplane
```

4. push 失败保留 commit，报告精确错误；不改作者、不 force-push、不 push main。

## 9. 完成报告

- 认领票与状态
- 分支 + commit hash + push 结果
- 关键文件/协议/接口
- 测试命令与红/绿结果
- 未验证/`not-tested`
- 研究来源
- 与 P04/P09 的契约对齐情况
- 风险与下一步

## 10. 停止条件

- 需要真机/adb → 停止，标 `not-tested`。
- 需要改其他 agent 文件 → 停止并报告。
- 需要 Root/unlock/pvmfw/SELinux 修改 → 立即停止。
- NDK/CMake 不可用 → 缩小到 host C 测试 + 构建脚本，报告限制。
- spec/票冲突 → 以 spec 为准，报告冲突。

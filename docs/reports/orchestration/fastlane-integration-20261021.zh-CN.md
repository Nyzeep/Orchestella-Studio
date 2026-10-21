# Fast Lane 集成分支报告（2026-10-21）

状态：**零设备收口完成；真实 Android 构建 `BUILD_BLOCKED`；真机未验证**。
分支：`integration/fastlane-20261021`（基线 `github/main 3213b06e`）。
范围：四个并行模块分支的合并、FLN1 adapter、CI 全模块门禁、文档证据统一。本会话不运行 adb/真机/DV-01–DV-04。

## 1. 集成分支与 merge

会话开始时集成分支已存在（`16a5bd19`），四条模块分支已按 B→C→D→A 合并；本会话核对了祖先关系、工作树与合并结果，未重做合并，也未 rebase 已推送分支。

| 顺序 | merge commit | 模块 |
| --- | --- | --- |
| 1 | `36f7409f` | P02/P05/P06 payload 与数据面（`github/feature/fastlane-dataplane` @ `c9c55a35`） |
| 2 | `46aac21a` | P03/P09/P11 toolbox、distcc 与构建门（`38dc6c74`） |
| 3 | `b288264b` | P08/P10/P12 调优、遥测与宿主研究（`5054c7da`） |
| 4 | `16a5bd19` | P01/P04/P07 运行时、终端与选道（`6b8bdf33`） |

本会话新增提交：`f655b0b3`（FLN1 adapter）、`b2fa98b2`（CI 全模块门禁）、`2415fb9b`（文档与 ACR-04）。

- 未使用 `-X ours`/`-X theirs`；没有为合并丢弃任一模块行为。
- 冲突：提交历史不保留索引层冲突记录。对四个分支与 `main` 重跑 `git merge-tree` 均无 CONFLICT 输出；合并树中 payload、toolbox、qemu-tuning、runtime 四组文件同时存在。无法从历史还原“当时是否手动解过冲突”，本报告不把它写成“从未冲突”。
- 合并后 `git status --short` 仅剩本会话的预期修改；未把 `main` 合回集成分支。

## 2. P0：FLN1 adapter

### 2.1 决策

- `FLN1` 是 control/terminal 的 canonical wire（`fastlane-payload/docs/protocol.md`，C payload 为 normative）。payload 字节**未修改**。
- `OSFL` 退役：`TerminalProtocol.java` 从生产源码删除；OSFL fixture 删除；ACR-04 记录裁定。
- `TerminalMux` 状态机、公开 API、半关闭/VM stop 语义保留；新增 `TerminalCodec` seam，生产实现为 `Fln1TerminalCodec`。

### 2.2 关键映射

| 宿主概念 | FLN1 事实 | adapter 处理 |
| --- | --- | --- |
| host 分配 session id | payload 在 `PTY_OPENED` 分配 | `OPEN_PTY` 以 wire session 0 发出，FIFO 绑定返回值；`releaseSession` 在 CLOSED 时清理 |
| 双向 credit | 只有 guest→host 的 `initial_window`/`WINDOW` | host→guest 不检查、不扣减 credit，按 32 KiB 切帧；host 的 WINDOW 增量截断到 `2*initial_window` |
| EOF 带退出码 | `EOF` 空 + `EXIT(i32,u8)` | `read()` 延迟 EOF 到 EXIT；传输先断时以 -1 收口；EXIT 即结束 session |
| host 发 ERROR | payload 视为 unknown command | host 侧违规抛 typed 异常，channel 关闭连接 |
| `METRICS` | `METRICS_OK` 字段不同 | terminal mux 不实现 METRICS 帧；遥测归 P10 |

### 2.3 fixture 与测试

- 唯一 canonical fixture：`protocol/fln1-golden.json`，16 帧（HELLO/HELLO_OK/PING/PONG/OPEN_PTY/PTY_OPENED/PTY_DATA/RESIZE/RESIZED/SIGNAL/CLOSE/CLOSED/EOF/EXIT/WINDOW/ERROR）。
- Java `Fln1GoldenFixtureTest` 与 C++ `tests/test_golden_fixture.cpp` 读取同一文件，逐字节 decode/re-encode。
- 进程级 E2E：`Fln1PayloadE2ETest` 启动真实 `fastlane_payload_host`（`FASTLANE_TRANSPORT=unix`），完成 HELLO(token, test-only)→HELLO_OK→OPEN_PTY→PTY_DATA→EOF/EXIT，断言 `e2e-ok` 和退出码 3。**通过**（本机 Unix socket，不是 vsock；token 由测试进程设置，不代表生产链路）。

## 3. P0：CI 全模块门禁

`.github/workflows/fastlane-zero-device.yml`：

| job | 内容 |
| --- | --- |
| `toolbox-and-licenses` | venv + PyYAML、workflow 校验、`tools/fastlane-build` 76 tests、`tools/qemu-tuning` 11 tests、toolbox 构建/可复现/负向门/许可证 |
| `jvm-distcc`（名称保留兼容既有校验器） | `tools/ci/run-fastlane-zero-device.sh jvm`：engine-fastlane、terminal、capability、proxy、storage、telemetry、distcc |
| `payload-host`（新） | `... cpp`（ASan+UBSan CTest）→ `... e2e`（真实 payload 进程，JUnit 固定校验和） |
| `apk-bin-gate` | 原有 APK `bin/` + 16 KB 门 |
| `payload-build` | 原有 NDK 构建 |

- `workflow_dispatch` 已存在；path filter 增加 `protocol/**`、全部 `runtime/fastlane-*`、`tools/qemu-tuning/**`。
- `tools/ci/run-fastlane-zero-device.sh` 本地/CI 共用；有 `ANDROID_HOME` 时走 Gradle，否则 JDK 17+ javac/JUnit fallback。
- `app/build.gradle.kts` 的 `fastlaneZeroDeviceCheck` 依赖全部 7 个模块 + license 门。
- validation：`python3 tools/fastlane-build/check_zero_device_workflow.py` → `WORKFLOW_OK`（该校验器已加强：要求 gate 脚本覆盖 `settings.gradle.kts` 的全部 Fast Lane 模块、fixture 存在、payload-host 存在）。
- **GitHub Actions run ID：`not-run`**。本环境没有 `gh`、没有远端 CI 触发权限；未伪造 run。

## 4. 全量验证（本机，零设备）

### 4.1 JVM

命令：`tools/ci/run-fastlane-zero-device.sh jvm`（standalone `javac --release 17 -Xlint:all -Werror` + JUnit 4.13.2；Gradle 因无 Android SDK/JDK 17 toolchain 不可用）。

| 模块 | 结果 |
| --- | --- |
| engine-fastlane | OK (65) |
| fastlane-terminal | OK (65；64 执行 + 1 E2E 在未设置 `-Dfln1.payload` 时跳过) |
| fastlane-capability | OK (32) |
| fastlane-proxy | OK (42) |
| fastlane-storage | OK (35) |
| fastlane-telemetry | OK (46) |
| fastlane-distcc | OK (17) |
| **合计** | **执行 301**（JUnitCore 显示 302：1 项 E2E 被 assumption 跳过；设置 `-Dfln1.payload` 后执行 302） |

旧基线：A 153 + B JVM 77 + C 17 + D 46 = 293（执行数）。集成时执行数 296 = 旧 293 + terminal 56→59（OSFL 断言被 FLN1 golden 取代，净增 3）。2026-10-21 加固后 301 = 296 + terminal 59→64（failed-open 2 项、ERROR 0 不影响 OPEN 1 项、默认 8 1 项、5 MiB credit 1 项）。没有“数量少了但说通过”。

### 4.2 C++

命令：`FL_BUILD_DIR=/tmp/fl-asan-int tools/ci/run-fastlane-zero-device.sh cpp`（CMake 3.31.6 + Ninja + `FL_SANITIZE=ON`）。

- 共 **72 个 case**（70 原有 + 2 新增 golden）。本机两次独立复跑分别得到 **71/72 与 72/72**，同一二进制、同一构建；该 case 对运行环境的 fd 分布敏感，不能把任一次结果写成稳定通过。
- 2026-10-21 加固：修复 `count_open_fds()` 把 `/proc/self/fd` 目录自身 fd 计入的 off-by-one，并新增 1 项确定性回归 `open_fd_count_excludes_the_probe_directory`（修复前红：counted=4 / probed=3；修复后绿）。套件变为 **73/73**；`spawn_piped_rolls_back_partial_pipe_allocation` 连续 20 次 **20/20 通过**（修复前后各 20/20，本环境未复现 71/72）。
- 失败项：`spawn_piped_rolls_back_partial_pipe_allocation`。原因：本沙箱 `/proc/self/fd` 计数与实际 fd 不一致，`open_fds+2` 的 RLIMIT 仍允许 3 个 pipe。**同一失败在 `feature/fastlane-dataplane` @ `c9c55a35` 的原始代码上复现**，不是集成引入。
- 另修复该测试的清理缺陷：断言失败时没有恢复 `RLIMIT_NOFILE`，会让后续测试全部 EMFILE 并 `terminate`。现在用 RAII 恢复；断言本身未放宽。修复后整个套件正常退出并报告 71/72。
- golden 两个 case 均 PASS；无 ASan/UBSan 报告。

### 4.3 Python

`tools/ci/run-fastlane-zero-device.sh python`：`tools/fastlane-build` **76 OK**，`tools/qemu-tuning` **11 OK**。

### 4.4 E2E

`FASTLANE_PAYLOAD_BIN=/tmp/fl-build/fastlane_payload_host ... e2e` → OK (1 test)。

## 5. 真实 Android 构建：BUILD_BLOCKED

探测结果：

```
java -version            → OpenJDK 21.0.12.1（无 JDK 17）
ANDROID_HOME             → 空
command -v sdkmanager    → absent
command -v zipalign      → absent
```

因此未执行：

```bash
python3 tools/fastlane-build/fastlane_build.py build --manifest fastlane-toolbox/manifest.json --out build/fastlane-toolbox --cache build/fastlane-cache
./gradlew :app:assembleDebug :app:verifyFastlaneToolboxApk -Porchestella.fastlane.toolboxDir=build/fastlane-toolbox
zipalign -c -P 16 app/build/outputs/apk/debug/app-debug.apk
zipinfo -l app/build/outputs/apk/debug/app-debug.apk | grep ' bin/'
```

**未验证**：APK 根 `bin/` 条目、`bin/` 内 ELF 执行位、AGP 是否把 mode 写成 0644、`zipalign -P 16`、manifest/BusyBox SHA 与报告一致。没有下载 SDK，也没有用假 APK 冒充。CI 的 `apk-bin-gate` 仍是唯一能给出该结论的门。

## 6. 文档与证据

- ptrace：survey、protected plan、`.scratch/orchestella-avf-shizuku/map.md` 统一为“`microdroid_payload.te` 无 ptrace allow，也没有显式 neverallow；SELinux 默认拒绝；PRoot 大概率不可用，需真机探针”。删除“官方 neverallow 禁止 PRoot”的绝对表述。
- 新增 `ACR-04-fln1-canonical-wire.md`：FLN1 canonical、OSFL 退役、影响与未决风险；未新造 ADR 编号。
- P04 报告/票改为“部分完成，FLN1 adapter 已落地；DV-02 待真机”；P02 报告/票增加 FLN1 对齐交叉引用。
- spec §4.4 增加 2026-10-21 集成修正注记（四端口、FLN1、OSFL 退役），不重写正文。
- `.scratch/orchestella-fastlane/map.md` 更新集成分支状态、当前阻塞与下一步 DV-01。

## 7. 未验证 / not-tested

- 真实 `AF_VSOCK`、Microdroid SELinux、devpts、真实 PTY 退出时序、Android 终端 UI 接线（DV-01/DV-02）。
- payload 5 秒 `SO_SNDTIMEO` 下的持续读取与背压；host 读取停顿时 payload 会断开并 SIGKILL 所有 PTY。
- Android 构建/APK `bin/` 执行位/`zipalign -P 16`（`BUILD_BLOCKED`）。
- GitHub Actions 实际运行（`not-run`）。
- Gradle 路径本身（本机无 SDK；脚本的 Gradle 分支未执行）。
- **token 在生产链路 `not-wired`**：payload 只从 `FASTLANE_AUTH_TOKEN` 环境变量读取；Microdroid app-payload 的 `VirtualMachineConfig` 没有向 payload 传任意 token 的受支持字段。E2E 的 token 由测试进程设置，只证明 codec/HELLO 路径，不证明生产认证。
- failed-open 与 host→guest credit 只在宿主状态机、普通进程 E2E 与内存流上验证；真实 payload 的 `SESSION_LIMIT`、同步 PTY 写入背压仍是 DV-02。
- C++ `spawn_piped_rolls_back_partial_pipe_allocation` 对 fd 分布敏感：本环境修复前后各 20/20，但历史环境出现过 71/72；不能据此宣称 CI 永不偶发。

## 8. 集成中发现的问题（第 5 项已在 2026-10-21 加固修复）

1. **payload `WINDOW` 32 位加法回绕**：`pty.cpp` 的 `current + bytes` 在 `uint32_t` 中先回绕再 `min`，极端增量会降低 credit。宿主已把增量截断到剩余窗口，正常路径不可达；修复归 P02 owner。
2. **`pty.h`/P02 报告的“session id 永不复用”**：代码只在 `sessions_` 中跳过，`uint32_t` 回绕后会复用。2^32 次打开才可能，属于文档/健壮性问题。
3. **`protocol.md` 的 CLOSED reason 1**：文档列了 1=signalled，payload 只产生 0/2。
4. **`protocol.md` 的 PTY_DATA ≤32768**：payload 入站只受 65536 帧限制；32768 是发送缓冲上限。
5. **payload 默认 8 sessions，宿主默认 128**：已在加固中修复——默认构造器改用 `DEFAULT_MAX_SESSIONS=8`，128 仅作显式硬上限；`FastLaneLaunchSpec` 仍未接线（`not-wired`）。见 §11。

## 9. 剩余风险与下一步

- **DV-01**：protected payload create/run/vsock/encrypted storage/stop；这是下一步，不是继续加功能。
- DV-02：终端（真实 vsock/PTY/UI）、代理并发 channel、存储导出。
- DV-03：distcc 真实编译与 QEMU A/B。
- ACR-03：Fast Lane 运行时类别仍未裁定；本分支不改变产品声明。
- 真实构建门（`apk-bin-gate`）与 GitHub Actions 仍需在有 SDK/CI 权限的环境执行。

## 10. 清理与推送证据

- `git status --short`：提交前为空（除已提交内容）；`git diff --check HEAD~3..HEAD` 无输出。
- 删除：`build/`（门禁输出与 venv）、`.gradle/`、`__pycache__/`、`*.pyc`、`*.class`；CMake/CTest 构建在 `/tmp`，不入库。
- 未提交 APK、二进制、测试证书、下载的 tarball。
- 敏感扫描：`docs/reports/tickets`、`docs/research`、`.scratch/orchestella-fastlane` 仅命中既有报告中的 GitHub SSH URL、公开 Alpine 公钥名与 “root/解锁” 文本，无新增凭据/设备标识/个人路径。
- 推送：`git push github HEAD:refs/heads/integration/fastlane-20261021` 成功；只推集成分支，未 push main、未 force-push 模块分支、未 tag。
- PR：环境无 `gh`，未创建；手动 URL 见会话完成报告。

## 11. 2026-10-21 加固（P0/P1）

本节只做宿主状态机/codec、测试与文档；payload wire 未改，未启动 adb/真机/DV-01–DV-04。

### 11.1 P0：失败的 OPEN_PTY 污染 pending FIFO

复现（宿主侧，真实 codec + mux）：

```text
OPEN_PTY local=1 -> pendingOpens=[1]
ERROR(session=0, code=6 "session limit")
PTY_OPENED(wire=9) -> pollFirst() == 1   # 绑定到已经失败的会话
```

根因：payload 的 `send_error` 固定 `FrameType::kError, 0`（`runtime.cpp:186-195`），OPEN_PTY 失败也走 `:603-611` → `:704`，随后关闭整条连接（`:711-721`）；宿主 `TerminalMux.handleError` 只在 `sessionId>0` 时关闭会话，codec 的 FIFO 从未清理。

修复：

- `Fln1TerminalCodec.translate(OP_ERROR)`：`wireSession==0` 时清空 `pendingOpens`，返回 session 0 的 ERROR；不新增 ERROR session 语义。
- `TerminalMux.handleError`：`sessionId==0` 时遍历 `sessions` 副本，把所有 `OPENING` 会话 `finish(PROTOCOL_ERROR)`；`OPEN` 会话不动，交给随后的 transport EOF/`abortAll`。
- `finish` 继续调用 `sink.releaseSession`，codec 的 `localToWire`/`wireToLocal` 同步清理。

回归：`connectionScopedErrorClearsThePendingOpenFifo`、`failedOpenWithConnectionScopedErrorIsClosedAndFifoIsReset`、`connectionScopedErrorClosesOpeningSessionsButNotOpenOnes`。修复前 3 项红（state 仍 OPENING / PTY_OPENED 仍被消费），修复后绿。

### 11.2 P1：默认 session 上限对齐

- 新增 `TerminalMux.DEFAULT_MAX_SESSIONS=8`，默认构造器使用 8；`MAX_SESSIONS=128` 保留为显式硬上限。
- 需要 128 的测试（stress/100 sessions）显式传 `TerminalMux.MAX_SESSIONS`。
- 回归：默认 mux 打开 8 个成功，第 9 个抛 `SESSION_LIMIT`。
- `FastLaneLaunchSpec` 未增加 `maxTerminalSessions`（`not-wired`）；未来可配置必须同时配置 payload 的 `FASTLANE_MAX_SESSIONS` 与宿主。

### 11.3 P1：host→guest credit 的真实语义

- FLN1 只有 guest→host 的 `initial_window`/`WINDOW`。旧实现 host 扣 `sendCredit`、guest 扣 `receiveCredit`，累计 4 MiB 后 `sendData` 返回 0 或 guest 报 `CREDIT_EXCEEDED`。
- 修复：HOST `sendData` 只受 `peerMaxFrameBytes`/`MAX_PTY_DATA` 限制，不扣 `sendCredit`；GUEST `handleData` 不检查/不扣 `receiveCredit`；GUEST 发送与 HOST 接收仍保留窗口。
- host→guest 的背压来自 32 KiB 帧上限、同步 sink 与 payload 的 PTY 写入；不再写成“4 MiB 窗口”。
- 回归：host 连续发送 5 MiB（160 × 32 KiB）全部接受，guest 收到总字节一致；guest→host 仍在 `initial_window` 耗尽时 stall。

### 11.4 P1：token 结论 = not-wired

AOSP app-payload 路径没有宿主向 payload 传任意 secret 的受支持通道：

- `VirtualMachineConfig.Builder` 无 token/secret/env/argv setter；
- `setPayloadConfigPath` 只接受 APK 内路径，且与 `setPayloadBinaryName` 互斥，宿主不能提供运行时配置文件；
- `VmPayloadConfig` 只有 os/task/apexes/extra_apks/prefer_staged/export_tombstones/enable_authfs/hugepages；`task.command` 是路径而非 argv，未知字段被忽略；
- `AVmPayload_getVmInstanceSecret` 明确不提供给宿主，`VirtualMachine` 无 secret getter；
- app-payload API 从 Android 14（API 34）起提供。

结论：生产 payload 的 `FASTLANE_AUTH_TOKEN` 为空，payload 跳过 token 校验；codec 的 token 支持与 E2E token 仅是 test-only。未改 FLN1 HELLO，未发明通道。DV-01 需记录 payload 侧 `auth_token` 为空，并把 `MANAGE_VIRTUAL_MACHINE` 权限与 vsock 可达性作为当前实际边界。

### 11.5 P1：C++ RLIMIT 回归稳定化

- `count_open_fds()` 原先把 `/proc/self/fd` 目录自身 fd 计入，off-by-one；新测试 `open_fd_count_excludes_the_probe_directory` 修复前红（counted=4 / probed=3），修复后绿。
- `spawn_piped_rolls_back_partial_pipe_allocation` 连续 20 次 **20/20 通过**；修复前本环境同样 20/20，但历史环境出现过 71/72。未放宽 `FL_CHECK_THROWS_CODE`，未改为 skip。
- 套件从 72 增至 **73/73**（新增 fd 计数测试）。

### 11.6 全量结果与边界

| 门 | 结果 |
| --- | --- |
| JVM | 7 模块通过；**301 执行 / 302 case**（1 个 E2E 在未设置 `-Dfln1.payload` 时跳过）；terminal 59→64 |
| C++ | **73/73**（ASan/UBSan）；`spawn_piped...` 20/20 |
| Python | fastlane-build **76**、qemu-tuning **11** |
| E2E | **1/1**（Unix socket，不是 vsock） |
| `git diff --check` | 无输出 |

未验证：真实 `AF_VSOCK`/SELinux/devpts/PTY 时序/Android UI（DV-02）；真实 payload 的 failed-open 与 `SESSION_LIMIT`；host→guest 5 MiB 只在内存流与普通进程 E2E；token 生产链路为空；GitHub Actions `not-run`；APK `bin/` 与 16 KB 门 `BUILD_BLOCKED`。

<!-- COMMIT_EVIDENCE -->

下一步：DV-01（protected payload create/run/vsock/storage/stop），不是继续加功能；DV-02 前必须用真实 vsock 重跑终端与 token 探针。

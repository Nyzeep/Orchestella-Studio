# PVM 编译服务零设备推进轮 · PVM-11 立票对账与真实 distcc 客户端 E2E、PVM-06 worker 池

- 日期：2026-11-04（窗口跨 11-03 夜）
- 基线：main `46ce4e5`（origin = github.com/Nyzeep/Orchestella-Studio），工作树干净
- 上游输入：PVM-10 报告钦定「下一票 PVM-11：把真实 distcc 的 argv 找出来」；上一轮质量收敛报告的 tracker 对账教训（「状态没回写」）；规格 `.scratch/orchestella-pvm-compiler/round-pvm11/spec.md`
- 纪律：**全程零设备**（adb 一次未调）。真机判据一律标 `not-tested` 并留下窗入口。无设备在线，UML fabric 设备窗项（16K 宿主、smp4/8 n≥10、distcc@36）不在本轮。

## 0. 判定总表

| 道 | 缺口 | 落地 | 验证 |
|---|---|---|---|
| Lane A | PVM-10 报告许诺的「新票 PVM-11」从未立项；修复已在 main（`753a3ff`+`2936a6c`）但 tracker 零痕 | 立 PVM-11 票（4 条 Comments）+ PVM-10 票对账；WSL 构建 distcc 3.4 客户端；重建载荷 host 孪生；**真实客户端零设备 E2E 闭环** | E2E `realDistccClientCompilesThroughThePayloadServer` 1/1 绿：STAT=0、远端 .o 608 B vs 本地 1448 B、双 .o 链接运行打印 42 |
| Lane B | PVM-06（编译 worker 池与调度）`ready-for-agent` 长期未动 | `WorkerPool` 接口 + `ProtectedWorkerPool` 实现（有界 FIFO 队列、预算、拓扑开关、重建退避、容量 0=今天行为、零接线） | javac 零错误；`ProtectedWorkerPoolTest` 21/21、`ProtectedWarmPoolTest` 18/18 回归；Gradle 门 `:app:testDebugUnitTest` + `:runtime:instance:test` 绿 |

## 1. 侦查改判（R1/R2/R3 三路，笔记 round-pvm11/notes/，本地不入库）

1. **PVM-11 已实现且在 main**：`753a3ff`（两段式 `-target` 接受 + `-march/-mtune/-mcpu=native` 拒绝 + declared_target advisory）、`2936a6c`（按输入文件名后缀推断语言）；报告 52/52 绿（2026-10-27 会话 12A）。但 `.scratch/orchestella-pvm-compiler/issues/` 无票文件——tracker 债，与本系列「状态没回写」同型。
2. **上游纠错**（distcc master `8d569d19`，浅克隆在仓库外 `../distcc-upstream`，全程只读）：distcc v1 客户端**本地**完成预处理并 strip `-I/-D/-U/-L/-l/-MF/-MT/-MQ/-include/-isystem/-MD/-MMD/-Wp,`（strip.c:65-139）；DOTI 线上 argv = 编译器名 + `-c/-S/-O*/-W*/-g/-fno-*` + 源文件名 + `-o 输出`（无 `-o` 自动补，arg.c:277-305）。⇒ 对真实 v1 客户端 `INCLUDE_PATH` **不可达**；侦查初期「真实客户端会带 -I 过线」的推断被上游事实改正，已写进 PVM-11 票 Comments ②。上游服务端从不校验未知 flag（透传编译器，仅拒 `-fplugin=`/`-specs=`，serve.c:771-794）——载荷白名单更严是本项目安全设计（payload 域零文件写），不是对齐缺口。
3. **E2E 前置**：`PvmCompilerPayloadE2eTest` 硬编码 POSIX 面（`/usr/bin/distcc`、`/usr/bin/cc`、`PATH=/usr/bin:/bin`、`TMPDIR=/tmp`），Windows 侧必然 skip；WSL Ubuntu 24.04 + gcc 13.3 可用。master clone 的 autotools 在 CRLF + python.m4 缺失下 configure 失败（R3），故 E1 走 codeload v3.4 tag + autogen + `--disable-Werror` 路线。

## 2. Lane A · PVM-11 立票对账 + 真实客户端 E2E

**立票**：`PVM-11-argv白名单对齐真实distcc客户端.md`（PVM-10 范式票面，Status=implemented，真机复验 not-tested）。**对账**：PVM-10 票 Status 改写为「argv 白名单阻塞点已由 PVM-11 零设备解决」，Comments 补证据。

**WSL 构建**（3 条路线内走通）：

| 路线 | 结果 |
|---|---|
| GitHub release tarball | 不可达（WSL 侧 HTTP 000，网络直连受限） |
| **codeload v3.4 tag + autogen + configure** | **CONFIGURE_OK**（补 `libpopt-dev`/`libiberty-dev`），顶层 `make distcc -j4` 成功 |
| 发行版 orig tarball | 备查未用 |

产物 `/root/distcc-build/distcc-3.4/distcc`（distcc 3.4，未进仓库树）；E2E 实际用 apt 同版本 `/usr/bin/distcc`。

**载荷 host 孪生重建**（照抄 PVM-11 报告 §4 命令）：Windows 侧 `ctest` 7/7、聚合 `pvm_compile_service_tests` **75/75**（比 PVM-11 报告时点 52/52 多出的部分是 PVM-10 等后续用例，当前工作树口径）；WSL 侧再建 Linux ELF `pvm_compiler_payload_host`（996,744 B，E2E 必需）。

**E2E 结果**（WSL 内 Gradle `--offline --configure-on-demand`，`BUILD SUCCESSFUL in 3m 26s`）：

- `PvmCompilerPayloadE2eTest.realDistccClientCompilesThroughThePayloadServer` **tests=1 skipped=0 failures=0**；system-out `PVM_DISTCC_E2E_OK cases=1`。
- 载荷子进程 stderr：`PVM_CSDL listening port=… jobs=2 engine=fake-engine` → TCP 就绪探测 `job done code=6`（WireError::kClosed，预期）→ **真实 distcc 作业 `job done code=0`**（JobStatus kOk，STAT=0）。
- 载荷性判据：远端 `.o` **恰 608 B**（fake engine 固定桩尺寸），同夹具本地 `cc -c` 产物 1448 B——本地回退不可能凑巧同尺寸；两枚 `.o` 导出符号集相等；分别链接成可执行并运行，均打印 `42`、exit 0。sha256 已留痕（notes/E1-e2e-evidence.md）。
- 诚实边界：FakeEngine 恒回桩对象——本 E2E 证明「**真实 distcc 3.4 客户端 × 真 `DistccTcpVsockBridge`/`Axp1DistccChannelFactory` × 真载荷 C++ 服务（含 PVM-11 argv 白名单与 distcc v1 编解码）**」协议与 argv 全链接受；**不是**真 clang 产物等价（真 libclang 只在 Android payload 构建，等价判据归 PVM-05 / DV-PVM-02）。

## 3. Lane B · PVM-06 编译 worker 池与调度

新增 `app/src/main/java/io/github/nyzeep/orchestella/WorkerPool.java`（接口 + 固定码 + `Admission`/`HealthProbe` 缝）、`ProtectedWorkerPool.java`（实现 + Config）；测试 `ProtectedWorkerPoolTest.java`（843 行，21 用例）。`ProtectedWarmPool.java` 仅增量（`Code.PROBE_FAILED` + 只读 `warmSessionIfReady()` + 健康逐出 `evictWarm()`），既有语义零改动。

按票逐条落点：

| 票面任务 | 落点 |
|---|---|
| `acquire/release/health/resize` | `acquire(job[, timeoutMs])/release/cancel/health/resize/shutdown`；N 个 capacity-1 `ProtectedWarmPool` 槽位 + 公平锁有界 FIFO 队列（默认 2×N） |
| 队列拒绝码 | `POOL_SATURATED` / `JOB_TIMEOUT`；FIFO 公平；支持取消 |
| 预算 | `Admission.ledgerBacked(LaneBudgetLedger)` 按累计足迹收 1 vCPU+内存/实例；reason 复用 `LaneRejectCode`；账本本身零改动 |
| 拓扑开关 | 默认 `ONE_CPU`；`MATCH_HOST` 实验开关默认关 |
| 健康/重建 | 心跳/探针缝（fake VM 可注入）；失败计数 + 指数退避（封顶）+ cap(3) 后 `REBUILD_GIVE_UP`，防重建风暴 |
| 停止顺序 | 与 `DualLaneCoordinator` 一致（QEMU → ACCEL）；`UNCONFIRMED` 停机槽位停用且预算照记；无新 stop owner |
| 容量默认 0 | acquire 前置确定性 `DISABLED` 拒绝，不建 VM 不排队——行为与今天逐字节一致（决策地图 §8.4 未动） |
| 零接线 | main 无构造点（`grep -rn "WorkerPool" app/src/main` 除新文件外零引用）；javadoc 写明 intentionally-not-wired；产品接线归 PVM-09 |

测试（fake VM + fake clock）：容量 0/1/N、队列满、实例崩溃重建、取消、预算拒绝、停止顺序，21 用例全绿。报告 `docs/reports/tickets/pvm-06/implementation-report.zh-CN.md` 含 Mermaid 生命周期图与容量 0 论证。

## 4. 验证门证据（全部零设备）

| 门 | 命令 | 结果 |
|---|---|---|
| Gradle 门 | `./gradlew :app:testDebugUnitTest :runtime:instance:test :runtime:fastlane-distcc:test --offline`（X:） | `BUILD SUCCESSFUL in 3m 12s`（59 tasks） |
| 新测试 | `ProtectedWorkerPoolTest` XML | `tests="21" skipped="0" failures="0"` |
| 回归 | `ProtectedWarmPoolTest` XML | `tests="18" failures="0"` |
| E2E（Windows 侧） | 同上 Gradle 门 | 1 test skipped（POSIX 前置不可满足，预期；真跑在 WSL，见 §2） |
| fastlane-distcc 全套件 | XML 逐套件 | 9 套件零失败 |
| CI 模块覆盖 | `python tools/ci/check_ci_module_coverage.py` | `232 passed, 0 failed` |
| 发布字段 | `python tools/release/check_release_fields.py` | `RESULT=PASS` |
| 空白检查 | `git diff --check` | 退出码 0 |
| 敏感数据门 | `--self-test` + 区间扫描 | `SENSITIVE_DATA_OK` |

所有 Gradle 门在 `X:`（ASCII 路径）跑，`--offline`；实现代理自验用 javac/JUnit 直跑，未与主会话 Gradle 并发。

## 5. 未验证项与下个窗口入口（全部 not-tested）

1. **PVM-11 真机复验三判据**：`DOTO` 长度 >0、`distcc_ms − 本地` 秒级差值、无 `running locally instead`/`EXIT_TRUNCATED`——入口 PVM-11 票 Comments ③（DV-PVM-02 真机面）。
2. **PVM-10 验收 A/B/C 真机部分**：阻塞点已零设备解除，等设备窗口复跑。
3. **PVM-06 真机行为**：多实例并发、重建退避在真机（protected VM）上的表现；上线接线（PVM-09）前必须过。
4. **真 clang 产物等价**：`FL_PVM_COMPILER_ENGINE=CLANG`（Android payload 构建），判据 PVM-05。

## 6. 回填

- 工单：`PVM-11` 新立（Comments ×4）；`PVM-10` Status/Comments 对账；`PVM-06` Status→implemented + Comments ×4
- 报告：`docs/reports/tickets/pvm-06/implementation-report.zh-CN.md`（新增）
- 规格：`.scratch/orchestella-pvm-compiler/round-pvm11/spec.md`（入库）；侦查与证据笔记（R1/R2/R3/E1/E2）**本地不入库**（含本机绝对路径），方法可复现
- 参考项目：distcc 上游浅克隆在仓库外 `../distcc-upstream`（master `8d569d19`，只读）

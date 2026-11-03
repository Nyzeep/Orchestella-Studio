# 零设备推进轮规格 · PVM-11 对账闭环 + PVM-06 worker 池（2026-11-03）

- 基线：main `46ce4e5`，工作树干净，origin = github.com/Nyzeep/Orchestella-Studio
- 纪律：**全程零设备**。真机复验项一律标 `not-tested` 并留下个窗口入口。无设备在线（adb 空列表），UML fabric 设备窗项（16K 宿主 / smp4,8 n≥10 / distcc@36）不在本轮。
- 提交纪律：中文 commit；敏感数据门 `tools/ci/check_sensitive_data.py`；工单入库时若被 ignore 需 `git add -f`；报告不得含本机绝对路径/序列号。

## 0. 侦查改判（三路子代理 R1/R2/R3，笔记 `notes/`）

1. **PVM-11 已实现且在 main**：`753a3ff`（两段式 `-target` 接受 + native 调优拒绝）+ `2936a6c`（按输入文件名后缀推断语言）；报告 `docs/reports/tickets/pvm-11/implementation-report.zh-CN.md`（52/52 绿，2026-10-27 会话 12A）。但 `.scratch/orchestella-pvm-compiler/issues/` **没有 PVM-11 工单文件**——PVM-10 报告许诺的「新票 PVM-11」从未立项，tracker 债。
2. **上游事实纠错**（distcc master `8d569d19`，clone 于仓库外 `../distcc-upstream`）：distcc v1 客户端在**客户端侧**完成预处理并 strip `-I/-D/-U/-L/-l/-MF/-MT/-MQ/-include/-isystem/-MD/-MMD/-Wp,`（strip.c:65-139），DOTI 线上 argv = 编译器名 + `-c/-S/-O*/-W*/-g/-fno-*` + 源文件名 + `-o 输出`（无 `-o` 自动补，arg.c:277-305）。⇒ 对真实 v1 客户端 `INCLUDE_PATH` **不应触发**；R1 笔记中「真实客户端会带 `-I` 过线」系错误推断，须在工单中改正并注明依据。
3. **E2E 前提**：`PvmCompilerPayloadE2eTest` 存在（`runtime/fastlane-distcc/src/test/.../PvmCompilerPayloadE2eTest.java`），前置 `/usr/bin/distcc`、`/usr/bin/cc`、`FL_PVM_COMPILER_HOST_BIN` 缺一自动 skip；`build/pvm-service` host 孪生需重建。WSL Ubuntu 24.04 + gcc 13.3 可用；master clone 的 autotools 在 CRLF 检出 + python.m4 缺失下 configure 失败 → 走 **3.4 release tarball**（预生成 configure）路线。
4. **PVM-06 ready-for-agent 且文件集不相交**：worker 池落点在 `app/`（`ProtectedWarmPool` 同目录）+ 预算 `runtime/instance/duallane/LaneBudget*`；与 Lane A（工单 + fastlane-distcc E2E）零交集，可并行。

## 1. Lane A（E1）· PVM-11 立票对账 + 真实客户端零设备 E2E

范围：
- 新建 `.scratch/orchestella-pvm-compiler/issues/PVM-11-*.md`（PVM-10 范式票面；Status=implemented；Comments 引 commit/报告/测试证据 + 上游 strip 事实 + 真机复验 `not-tested`）。
- 更新 PVM-10 票 Status/Comments：argv 白名单阻塞点已由 PVM-11（零设备）解决，验收 A/B/C 的真机部分仍 pending。
- WSL 构建 distcc 3.4 客户端（release tarball；产物只放 WSL 家目录或仓库外，**禁止进仓库**）。
- 按 PVM-11 报告的命令重建 `build/pvm-service` host 孪生（在哪条命令成功过就用哪条）。
- 满足前置后跑 `*PvmCompilerPayloadE2e*`（Gradle 必须在 `X:` ASCII 盘符下，`--offline` 优先），取证：测试名/计数 + 关键输出行。
- 诚实边界：FakeEngine 恒回桩对象——E2E 证明的是「真实 distcc 客户端 × 真桥 × 真载荷 C++ 服务」的协议与 argv 接受全链，**不是**真 clang 产物等价（那是 PVM-05/DV-PVM-02 的判据）。
- WSL 构建若 ≤3 种方法仍失败：把阻塞点精确写进 notes + 工单 Comments（标 `not-tested`），不强求。

## 2. Lane B（E2）· PVM-06 编译 worker 池与调度实现

范围：按票 `.scratch/orchestella-pvm-compiler/issues/PVM-06-编译worker池与调度.md` 逐条实现：
- `WorkerPool` 接口 `acquire(job)/release(lease)/health()/resize(n)`；复用 `ProtectedWarmPool`（`app/src/main/java/io/github/nyzeep/orchestella/ProtectedWarmPool.java`）租约语义；**容量默认 0 = 行为与今天一致**（决策地图 §8.4，不许改默认）。
- 有界队列（默认 2×N）：`POOL_SATURATED` / `JOB_TIMEOUT`；取消；FIFO 公平。
- `LaneBudget`（`runtime/instance/duallane/`）计入：每实例 1 vCPU + 内存；超预算拒绝带 reason。
- 拓扑开关：默认 `ONE_CPU`；`MATCH_HOST` 实验开关默认关。
- fake VM + fake clock 测试：容量 0/1/N、队列满、实例崩溃重建、取消、预算拒绝、停止顺序（QEMU → ACCEL，与 `DualLaneCoordinator` 一致；`UNCONFIRMED` 保留 lease 与预算）。
- 埋点只计数（池大小/占用/排队时长/重建次数），不含 job 内容。
- 报告 `docs/reports/tickets/pvm-06/implementation-report.zh-CN.md`（含 Mermaid 生命周期图）；工单 Comments 回填证据；**零接线**（产品接线归 PVM-09）。

实现纪律：javac 自验（可挂 SDK `android.jar`），**不跑 Gradle**（与 E1 的 Gradle 并发互斥）；样式对齐同目录既有代码；测试风格参考 `runtime/instance` 既有纯 JVM 契约测试。

## 3. 边界（两车道共同）

- 不改 `RuntimeProcessSupervisor` 所有权；不新增 stop owner。
- 不做跨设备调度/优先级/抢占；不把池容量默认改非 0。
- 不动 `Axp1DistccAccelerator`（02a 路径 EOF 依赖，PVM-10 已声明本轮不动）。
- 不动版本号/签名/发布面；不拆巨型文件；引上游只作互证。

## 4. 验收门（主会话 Phase 4）

1. `./gradlew :app:testDebugUnitTest :runtime:instance:test --console=plain --offline`（X: 下）
2. `python tools/ci/check_ci_module_coverage.py`
3. `python tools/release/check_release_fields.py`、`git diff --check`、敏感数据门自检 + 区间扫描
4. Lane A 的 E2E 证据归档（过/未过都要留痕）

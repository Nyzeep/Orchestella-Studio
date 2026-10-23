# 真机前置清障收尾报告（2026-10-23）

- 起点：`main fef4e3c`（四级 accelerator 堆叠已合入）
- 终点：`main f3b4901`
- 范围：T0 文档漂移、T1 AX-08 生产装配、T2 AX-07 性能协议、T3 AXP1 测试 flake、T4 孤儿测试接入
- 设备：**本轮没有连接任何设备**，没有 adb、没有装 APK、没有启动真实 VM。

---

## 1. 基线与分支

| 任务 | 分支 | PR | 合并提交 |
| --- | --- | --- | --- |
| T0 文档漂移 | `docs/accelerator-baseline-refresh` | #12 | `b923be3` |
| T1 AX-08 装配 | `feat/ax-08-assembly` | #13 | `7fefc59` |
| T3 AXP1 flake | `fix/axp1-test-pipe` | #15 | `97ce29c` |
| T2 AX-07 协议 | `feat/ax-07-perf-protocol` | #14 | `cb0c114` |
| T4 孤儿测试 | `fix/fastlane-distcc-evidence-ci` | #16 | `f3b4901` |
| 本报告 | `docs/accelerator-closeout` | — | — |

T3 之所以在 T2 之前合并：T4 与 T2 的 CI 都被 `Axp1DistccChannelFactoryTest` 的 flake 打断，先把修复合入 main、再把后续分支 rebase 到 main，是最短路径。合并全部使用 merge commit，没有 squash，没有改版本号。

---

## 2. 四道门逐条对照

| # | 门 | 改前 | 改后 | 验证命令 | 实际输出（main） |
| --- | --- | --- | --- | --- | --- |
| 1 | `AcceleratorFactProvider` 有 Android 实现 | 只有接口 + 测试 fake | `AndroidAcceleratorFactProvider` | `git grep -l "implements AcceleratorFactProvider" main -- '*.java'` | `main:app/src/main/java/.../AndroidAcceleratorFactProvider.java` |
| 2 | `DualLaneCoordinator` 有装配点 | 只有类定义 | `AcceleratorLaneAssembly` 唯一 `new` | `git grep -n "new DualLaneCoordinator" main -- 'app/src/main/*'` | `AcceleratorLaneAssembly.java:192` |
| 3 | `QemuLaneRuntime` 有真实就绪探针 | 默认构造 `() -> false` | 新增 `QemuReadinessProbe` 构造器 | `git show main:app/src/main/java/.../QemuLaneRuntime.java \| grep -n QemuReadinessProbe` | `:31 public QemuLaneRuntime(RuntimeOwnedSession session, QemuReadinessProbe probe)` |
| 4 | `tools/accelerator-perf/` 存在 | 目录不存在 | 采集器 + schema + 统计 + 对比 + runbook | `git ls-tree --name-only main tools/accelerator-perf/` | 12 个文件，含 `collect.py`、`validate.py`、`compare.py`、`schema/`、`fixtures/` |

flag 关闭时行为不变：`AcceleratorLaneAssemblyTest` 覆盖"不读事实 / 不起 ACCEL / 不占预算"三条；`AcceleratorLaneAssembly` 默认 `Config.disabled()`，且本轮**没有任何调用点**接入启动链路，因此产品行为与改动前完全一致。

---

## 3. 每项任务

### T0 文档漂移（纯文档）

改了什么：

- `docs/agent-handoff/accelerator/device-cross-lane-test-prompt-20261023.zh-CN.md`：§2 改为直接用 `main`；删除"main 仍是旧 SHA 说明未合并"整条；基线检查改成 `git log --merges -4` 的不硬编码自检。
- `docs/reports/orchestration/accelerator-zero-device-progress-20261023.zh-CN.md`：§1 基线更新、旧 SHA 显式标注作废；§6.1 补"四个合并提交 CI 全绿"；§8 已完成项标记完成。
- `.scratch/orchestella-accelerator/决策地图.md`：`Base` 与批次三分支记录同步。
- `docs/agents/issue-tracker.md`：effort 指向 fastlane 主线（上游漏改补漏）。

为什么：旧 SHA 已被 `filter-branch` 重写，照抄会误导下一个执行 Agent。

放弃的方案：不保留"旧 SHA → 新 SHA"映射表——中间映射有两跳，写进文档反而增加新的漂移面；只声明"旧值作废，唯一权威来源是 `git log main`"。

### T1 AX-08（代码）

新增：

| 文件 | 关键点 |
| --- | --- |
| `AndroidAcceleratorFactProvider.java` | 只做映射：AVF 判定复用 `AvfHostFactsProbe` + `AvfCapabilityEvaluator`；镜像/编译器 triple 来自 `ACCELERATOR-IMAGE-MANIFEST`；vsock 取 `/dev/vsock`；热/电取 `PowerManager`/`BatteryManager`。任一来源异常或空 → 塌陷到保守值 |
| `QemuReadinessProbe.java` | 三值 `READY/NOT_READY/UNKNOWN`；证据只有 Bridge 控制通道已认证；无 `isAlive` 回退路径 |
| `AcceleratorLaneAssembly.java` | 唯一装配点；flag 关闭不读事实、不起 ACCEL、不占预算；打开后仍由 `AcceleratorPolicy` 决定；`ProtectedWarmPool` 共享账本、容量默认 0 |
| `AcceleratorImageManifest.java`（capability 模块） | `key=value` 清单，缺失/畸形/重复键一律视为镜像未安装 |

改动：`QemuLaneRuntime` 增加 `QemuReadinessProbe` 构造器；`app/build.gradle.kts` 增加 `:runtime:fastlane-capability` 依赖与 `orchestella.acceleratorLane`（默认 false）。

为什么这样切：装配根不自己起 VM，只接收两个 `LaneStarter`，这样零设备阶段能完整验收决策/预算/停止顺序，而把"创建 non-protected VM"这一真机动作留给 DV-ACC。

放弃的方案：把 `AcceleratorPolicy` 的调用塞进 `DualLaneCoordinator`——那会让协调器同时拥有"编排"和"选道"两个职责，也让 flag 关闭时的"不读事实"难以保证。

### T2 AX-07（工具）

新增 `tools/accelerator-perf/`：`collect.py`、`perfstats.py`、`validate.py`、`compare.py`、`schema/accelerator-perf-v1.json`、`README.md`（runbook）、`fixtures/`（固定输入 + golden 报告），以及 4 个 `test_*.py`；`ci.yml` 增加一步。

关键取舍：

- 阈值写进每份报告的 `protocol` 块，`compare.py` 只读 → 防止"跑完再挑阈值"；
- `--generated-at` 固定后同输入产生同字节，golden 对比才有意义；
- `code` 走固定词表、别名拒绝 IPv4/MAC 形状 → 报告结构上写不进路径/token/设备标识；
- schema 文件由 `SchemaFileTest` 与代码强制一致，否则就是装饰品。

放弃的方案：`pytest`（本环境 PyPI 不可达、CI 未装）与 `jsonschema`（同上）。都用标准库替代，并把这条写进报告与工单。

### T3 AXP1 测试 flake（仅测试代码）

`Axp1DistccChannelFactoryTest` 的 `Pipe` 换成两个方向的 `BlockingQueue<byte[]>`；`guest()` 不再吞异常，成功用例末尾断言 `AtomicReference` 为空。生产代码零改动。

顺带修掉一个更隐蔽的问题：**guest 线程里的 `assertArrayEquals` 失败此前会被 `catch (IOException ignored)` 静默吞掉**。

### T4 孤儿测试（仅测试代码 + CI）

`tools/fastlane-distcc/test_evidence.py` 从 pytest 改写为标准库 unittest（11 个用例，语义不变），接入 `ci.yml` 主 job 的 discover。

选这个方案而不是"在 workflow 里装 pytest"：主 job 没有 path filter，**每次 PR 都会跑**。

---

## 4. 测试证据

### 4.1 本地执行过的（真实命令 + 真实结果）

| 命令 | 结果 |
| --- | --- |
| `python -B -m unittest discover -s tools/accelerator-perf -p 'test_*.py'` | `Ran 52 tests` / `OK` |
| `python tools/accelerator-perf/validate.py --self-test` | `ACCELERATOR_PERF_OK scenarios=10` |
| `python -B -m unittest discover -s tools/fastlane-distcc -p 'test_*.py'` | `Ran 11 tests` / `OK` |
| `python tools/ci/check_ci_module_coverage.py` | `SUMMARY: 58 passed, 0 failed` |
| `python tools/ci/check_sensitive_data.py --range main..HEAD` | `SENSITIVE_DATA_OK`（每个分支合入前都跑过） |
| `git diff --check main..HEAD` | 通过 |
| `./gradlew :runtime:fastlane-capability:compileTestJava` | 通过 |
| `./gradlew :app:compileDebugUnitTestJavaWithJavac` | `BUILD SUCCESSFUL` |
| `./gradlew :runtime:fastlane-distcc:compileTestJava` | `BUILD SUCCESSFUL` |
| 独立 JVM 小程序验证 `BridgeClient` 握手 | `accepted=true isReady=true` / `coldIsReady=false` |

### 4.2 只能靠 CI 的（JVM 测试）

本机 `./gradlew <module>:test` 对**既有**测试类同样抛 `ClassNotFoundException: initializationError`，属已知环境限制，因此所有 Java 测试的执行结论以 CI 为准：

| PR | CI run | 结论 |
| --- | --- | --- |
| #12（T0） | `39979947590` / `39979948531` | 4 项全绿 |
| #13（T1） | `39983657453` / `39983657499` | 12 项全绿（含 960 个 app 单测） |
| #15（T3） | `39983746901` | 10 项全绿 |
| #14（T2） | `39984393923` | 4 项全绿（含 `Ran 52 tests`、`ACCELERATOR_PERF_OK`） |
| #16（T4） | `39985827997` | 4 项全绿（日志可见 `AX-06 distcc evidence tests → Ran 11 tests`） |
| main（T3 合并后） | `39984591675` | 全绿 |
| main（T4 合并后） | `39986653180` | 全绿 |

**T3 验收（连续 3 次通过）**：含修复的 CI run 连续 5 次全绿——`39983746901`、`39984393923`、`39984591675`、`39985827997`、`39986653180`。反向证据同样明确：修复前的两条分支 CI（`39983724097`、`39984009915`、`39983776507`）三次都是同一个 `successExposesTheAxp1DataBytesUnchanged` 失败。

---

## 5. 偏差与风险

1. **装配根尚未挂到具体 launch 调用点。** `AcceleratorLaneAssembly` 是唯一装配入口，但没有"每实例启动都经过它"的调用链——真实 `LaneStarter` 要创建 non-protected 自定义 VM，属 DV-ACC 的设备工作。需要维护者裁定这个粒度（详见 `docs/reports/tickets/ax-08/implementation-report.zh-CN.md` §8.1）。
2. **新发现第二个 flake：`DistccTcpVsockBridgeTest.closeStopsTheListener`**（main `39985629354`，`AssertionError at DistccTcpVsockBridgeTest.java:113`）。它在 `bridge.close()` 之后断言"连不上那个端口"，而端口是 `0` 由内核分配的。**没有静默修掉**：该断言失败可能意味着 accept 循环没停（真实缺陷），也可能只是端口被别的测试重新占用（测试设计脆弱）。建议下一步先加诊断（close 后检查 accept 线程与 `port()` 状态、记录是否真的接受了连接），再决定是修生产还是修测试。
3. **AX-07 的验收命令与工单不一致**：工单写 `pytest`，实际用 `unittest`（PyPI 不可达）。已在工单、报告、README、CI 四处同步，不留半新半旧。
4. **电量阈值 20%/5%、`/dev/vsock` 存在性判定**是本轮自选的保守值，工单未给。真机若被 SELinux 挡住 `/dev/vsock`，会保守拒绝（`ACCEL_VSOCK_UNAVAILABLE`）——这是有意的失败方向，但首测要记录实际表现。
5. **`git add -f` 只用于新增的 docs 报告**，每次 `-f` 前后都跑 `check_sensitive_data.py`；门禁两次拦下真问题（个人邮箱、字面 MAC 形状），均已修正而非放宽规则。

---

## 6. 未验证项（`not-tested`）

- 真实 AVF non-protected VM 启动、vsock 通道、AXP1 在真机上的握手。
- 加速镜像与 `ACCELERATOR-IMAGE-MANIFEST` 是否随包（本轮不产出镜像）。
- 真机 `PowerManager` / `BatteryManager` 读数（只有映射单测）。
- `requiredCompilerTriple` 的真实取值（需 guest 侧 `gcc -dumpmachine`）。
- 任何性能结论：AX-07 的数字全是夹具，`bench-sim` 是模拟别名。
- 装配根在真实启动链路中的接入（见 §5.1）。

---

## 7. 下一会话的真机前置清单

1. 拉取 `main`（≥ `f3b4901`）；`git log --oneline --merges -6` 应能看到四级堆叠 + 本轮五个合并。
2. 复核四道门（§2 的四条命令），任一回落成 fake 就只做基线并写明"交火未装配"。
3. 设备资格：API ≥33、`arm64-v8a`、`android.software.virtualization_framework` 为 true、`getCapabilities` 显示 non-protected 可用（bit2）、`setProtectedVm(false)` 成功、两项 VM 权限已授予。
4. 先跑一次 QEMU Full Lane 启停，确认 stop 收敛；再跑一次加速 VM 启停（不跑 distcc）。
5. 把加速车道的 `LaneStarter` 接到装配根，`-Porchestella.acceleratorLane=true`，**先读 `Result.decision()`**：不是 `QEMU_PLUS_ACCEL` 就按 reason code 排查（`docs/reports/tickets/ax-08/implementation-report.zh-CN.md` §5），不要"调到能跑"。
6. 按 `tools/accelerator-perf/README.md` §7 采样：同设备/同镜像/同资源、控温、A/B 交替、失败样本不保留外的不删除；`requiredCompilerTriple` 用 guest 侧 `gcc -dumpmachine`。
7. 结果用 `compare.py` 套停止条件：`<1.5×` 记负结果不产品化；任一场景慢 >5% 回退该变体。
8. 设备证据只写稳定别名、构建号、固定 code、SHA-256；序列号/IMEI/指纹/原始日志/真实 IP 一律不出设备。

# 工程质量积压清单（2026-11-04）

> **口径**：只读盘点轮（Lane E），基线 `main = b39e8e6`，分支 `docs/quality-backlog-20261104`。全程未跑 gradle、未连设备、未改产品代码。核对对象：`工程质量优化报告-20261027.md`（下称"主报告"，A 线）、`工程质量优化报告-20261027-补充卷.md`（下称"补充卷"，B 线）、`cleanup-plan.md` §4、`AGENTS.md`、`CONTEXT.md`。
>
> **分级定义**：P0 = 现在就处理（不可逆损失风险或正在误导后续工作）；P1 = 下一轮优先；P2 = 计划内技术债；P3 = 低优/顺手。"零设备"指该动作的全部验证可在宿主完成，无需 adb。

## 执行摘要

1. 2026-10-27 两份报告的 12 项"已落地"改动全部确认在树上；6 项未落地建议中 3 项已被后续轮次（46ce4e5 等）修掉。
2. 残留的正确性类问题：3 个实例写路径仍不持目录锁、`RfbInputEncoder`（270 行输入编码）仍零测试、launch 判决仍有两个决策点。
3. 门禁类残留只剩一处真实吞错：`release.yml:64` 的 sdkmanager `|| true`；其余 `|| true` 均为有意保留的 `--licenses`。
4. 全仓 TODO/FIXME/HACK/XXX 为 0，但生产代码仍有 78 个纯空 catch（无一行理由注释）。
5. 最重要的两项 P0 都不在代码里：主检出 `docs/reports/` 有 9 个磁盘有、索引无的证据文件（一条 `git clean -fdx` 即永久丢失）；`AGENTS.md` 的版本与主线状态已过期两个版本号，正在误导 agent 轮次。
6. `MainActivity.kt` 在死代码删除后回涨 1830 → 1994 行，超 800 行文件达 12 个。
7. 静态检查（ktlint/detekt）与依赖锁定（verification-metadata）仍为零，属需维护者裁定的工具链决策。
8. 本清单共 21 项：P0×2、P1×4、P2×7、P3×8；其中 18 项可零设备执行，9 项需维护者裁定。

---

## 1. 历史报告落地核对

### 1.1 主报告（A 线）已落地的 6 项——全部仍在树上

| 条目 | 状态 | 证据（b39e8e6） |
|---|---|---|
| 4.1 native-lane `probe_rootfs` clang 实启动校验 | 已在 | `tools/native-lane/measure.py:267-297`（clang 形参 + loader/`--library-path` 一致性说明） |
| 4.2 实例写路径目录锁（首批 4 方法） | 已在 | `runtime/instance/.../InstanceLifecycle.java:1061,1109,1175,1192` 等多处 `synchronized (store.stateMonitor())`；`ConcurrentResizeContractTest` 在 `runtime/instance/src/test/` |
| 4.3 三个安全边界类补测 | 已在 | `runtime/contract/src/test/.../services/EndpointPolicyTest.java`、`sharing/PathBoundaryTest.java`、`runtime/fastlane-storage/.../StoragePathsTest.java` |
| 4.4 argv_policy 补分支 + CMake 清理 | 已在 | `fastlane-payload/tests/test_argv_policy.cpp` 现有 26 个 `FL_TEST`（报告时 18 个，后续轮次继续增厚） |
| 4.5 CI 门禁失真修复 | 已在 | `.github/workflows/ci.yml:82-83,92,105,111,116,119`（`test_avf*`+`test_dv_*` 双 discover、tools/release、tools/native-lane、accelerator-perf、fastlane-distcc、pvm-compiler 全部入 CI）；`fastlane-zero-device.yml:19,29,44,54` paths 含 `runtime/contract/**` 与 `gradle.properties`；`ci.yml` 无 `ProvisionedLaunchRegressionTest` 冗余步骤；包安装改为 `if ! ...; then ::error::; exit 1`（`fastlane-zero-device.yml:236-237`） |
| 4.6 MainActivity 死代码删除 + STORE_ERROR + fd 泄漏 | 已在 | `app/.../InstanceSessionReconciler.java:31,60`（`STORE_ERROR`）；`app/.../TerminalActivity.java:698`（UI 分支）、`:930-942`（probe 连接 try-with-resources，注释明说防 fd 泄漏） |

注：主报告 §4.5-6 提到的 `check_zero_device_workflow.py` 本轮已不存在（工具被后续轮次取代），其"补 paths"目标已由 `fastlane-zero-device.yml` 现状满足，不构成积压。

### 1.2 补充卷（B 线）已落地的条目——全部仍在树上

| 条目 | 状态 | 证据 |
|---|---|---|
| StopFlag 双原子标志、Runtime 停止语义、WorkerPool | 已在 | `fastlane-payload/include/fastlane/stop_flag.h`、`src/runtime.cpp`、`src/compile_service/worker_pool.*`、`tests/test_worker_pool.cpp` 均存在 |
| 覆盖门禁反向校验 + 手工驱动显式化 | 已在 | `tools/ci/check_ci_module_coverage.py:22,49-52`（`MANUAL_DRIVER_RE`）；`runtime/fastlane-distcc/src/test/.../PvmCompilerBridgeInterop.java:43`（`// fl-manual-driver: needs ...`）；本机实测 `232 passed, 0 failed` |
| 孤儿 9p oracle 接线 + Windows 跳过 | 已在 | `runtime/engine-avf/build.gradle.kts:115,124+`（`standardReadonlyNinePTest`、`standardReadonlyNinePProcessTest` 注册） |
| contract 契约补测（授权闸/局域网闸/暂存器） | 已在 | `services/ServiceCredentialTest.java`、`services/LocalTcpRelayTest.java`、`sharing/ReadOnlyStagerTest.java` |
| `.gitignore` temp 去重、docs 索引修正 | 已在 | `.gitignore:33` 仅一行 `temp/`；`docs/reports/README.md` 已含 `archive/2026-10-zero-device/` 条目；`docs/adr/` 现有 0001–0021（除 0015）共 20 份 |

### 1.3 主报告 §6"未落地建议"逐条现状

| 原条目 | 现状 | 证据 |
|---|---|---|
| P0-1 并发会话改动归属 | 已澄清（补充卷 §4），无遗留 | — |
| P0-2 `PvmCompilerBridgeInterop` 反向检查 FAIL | **已修** | 同 1.2 marker 条目；`check_ci_module_coverage.py` 232/0 |
| P0-3 剩余写路径不持目录锁 | **部分**：`importFromArchive` 已收口，`delete`/`create`/`exportToArchive` 仍未持 | 收口证据：`InstanceStorageManager.java:501`（move-into-place + save 在 `synchronized (store.stateMonitor())` 内，且 save 失败回滚删除孤儿卷）；未收口证据：`InstanceLifecycle.java:1006`（`delete` 仅实例级 `synchronized`）、`:1010/1014/1019`（`create`）、`:1576-1580`（`exportToArchive`：目录锁缺席，且 `store.load(id)` 与 `requireExportable` 状态检查之间存在锁窗） |
| P1-4 `launchInstance` 双权威 → 抽 `LaunchCoordinator` | **未抽**，但结构已变好：判决收敛进 `AcceleratorLaunchDispatcher.dispatch(...)`，flag 关闭路径经显式 fallback lambda | `rg LaunchCoordinator` 全仓 0 命中；`app/.../MainActivity.kt:1718-1723`（`preferAvf` 计算）与 `:1780`（`strategyLauncher.launch(parameters, preferAvf)` 作为 dispatcher 的兜底）；`app/.../AcceleratorLaunchDispatcher.kt:232`（flag-on 硬编码 `SOFTWARE_VIRTUALIZATION`） |
| P1-5 `ProtectedWarmPool.acquire()` 持锁跨 VM 冷启动 | **未改**：整段冷创建（`api.start`）在 `ReentrantLock` 内完成，`state()/stats()/release()` 同锁 | `app/.../ProtectedWarmPool.java:358-404`（acquire 持锁）、`:432-458`（coldCreate 在锁内 `api.start`）、`:301,310,499`（state/stats/release 同锁）；`:445` 置 `CREATING` 但不释放锁 |
| P1-6 `SocketPortForwarder` 静默吞异常 + 无界线程池 | **大部分已修**（46ce4e5 Q2）：`PortForwardingObserver` + 1s 节流 + 指数退避 + 连续 fatal 关 listener + addRule 幂等防 fd 泄漏；残留线程池问题 | 修复证据：`runtime/network/.../SocketPortForwarder.java:53` 注入 observer、`:69-79`（close 的 ignored 带理由注释）、`:186-230`（recordFailure/shouldEmit/notify）；残留：`:53` 仍 `newCachedThreadPool`（无界）、`:216-217` 仍 `t1.join(); t2.join()` 占用池线程 |
| P1-7 `proxy_relay` 静默降级 | **已修** | `app/.../QemuRuntimeBootstrapper.java:220-229`：缺失记 `proxy-relay-absent` 降级事件、读失败抛 `IOException("proxy-relay-unavailable")` |
| P2-8 ktlint/detekt | **未做**：全仓 gradle 脚本与 catalog 无 ktlint/detekt/checkstyle/spotbugs/errorprone/pmd 任一命中 | `rg -ini "ktlint|detekt|..." --glob "*.gradle.kts"` 0 命中 |
| P2-9 版本目录补全 + 去重复依赖 | **已修**（46ce4e5 Q4） | `gradle/libs.versions.toml` 现含 shizuku/hiddenapibypass/desugar/buildtools/ndk/coroutines；`app/build.gradle.kts:151-152,473,495-499` 全部走 catalog；无逐字重复的 `androidTestImplementation` |
| P2-10 workflow pin SHA + 依赖锁定 | **未做** | 7 个 workflow 的 `uses:` 全部仍是 `@v4`/`@v5` 标签；`gradle/verification-metadata.xml` 不存在；无 `dependencyLocking` 配置 |
| P2-11 CI 收敛（release/qemu-android） | **部分** | `release.yml:64` 仍有真实包安装 `"$SDKMANAGER" "platforms;android-36" ... || true`（唯一非 `--licenses` 的残留吞错）；`qemu-android.yml:172` 仍 `timeout-minutes: 360` 顶格；concurrency 仅 `ci.yml`、`fastlane-zero-device.yml` 有，`release.yml`/`qemu-android.yml`/`fastlane-dv01b-zero-device.yml`/`guest-assets.yml`/`sensitive-data-guard.yml` 没有 |
| B线-2 固定 C++ Windows 本机基线 | 无落地证据；价值随 Linux CI 门禁在位而降低，不单列 | — |
| B线-3 `.editorconfig` 零风险先行 | **未做** | 仓库根无 `.editorconfig` |
| B线-4 手工驱动迁出 `src/test` | 不触发（当前仍只有 1 个 marker 实例），维持现状 | `MANUAL_DRIVER_RE` 命中数 = 1 |

---

## 2. 全仓扫描（漏网项）

| 扫描项 | 结果 | 判读 |
|---|---|---|
| TODO/FIXME/HACK/XXX（词边界，app/runtime/protocol/tests/guest-assets/tools/fastlane-payload/accel-extension/fastlane-toolbox） | **0 处** | 干净；此前扫描命中的 `XXXXXX` 全是 `mktemp` 模板，非标记 |
| `@Deprecated` | 仅 1 处：`app/.../MobileAuxiliaryKeyBar.java:114`（`setTargetInput`，空方法，注释称"Ticket 06 已消除孤立 EditText"） | 废弃原因已成立，可删；见 P3-8 |
| 生产代码 catch 块 | 758 个；命名 `ignored`/`_` 的 202 个；**纯空体 78 个**（完整清单见 §5 P3-1 附注） | 46ce4e5 Q2 只收口了 3 处高价值点；其余多为 best-effort close/清理惯例，但与仓库新口径（如 `SocketPortForwarder.java:69-79` 的"带理由注释"）不一致。注意：Kotlin `_:` 命名捕获里大量是"catch 后返回用户可见错误文案"，不算静默 |
| 测试代码空 catch | 若干（`PortForwardingManagerContractTest`、`MockHttpServer` 等） | 测试基建内可接受，不列积压 |
| >800 行文件 | **12 个**（此前 11 个）：`MainActivity.kt` 1994（2097→1830→**回涨 164 行**）、`InstanceLifecycle.java` 1696、`SetupWizardActivity.kt` 1508、`TerminalActivity.java` 1273、`ReflectiveFastLaneApi.java` 1188、`ProtectedWorkerPool.java` 1132、`InstanceDetailActivity.kt` 1090、`QemuInstanceLauncher.java` 1026、`ThemeCatalog.kt` 941、`TerminalMux.java` 862、`ReflectiveAvfVirtualMachineApi.java` 852、`BridgeControl.java` 847 | 见 P2-2 |
| 日志栈 | `android.util.Log` 9 个 app 文件 + `java.util.logging` 4 个文件（`BridgeProxyGateway`、`VirglJniBridge`、`InstanceSessionReconciler`、`TerminalHistoryStore`）+ runtime 侧 758 个 catch 的失败基本靠返回值/观察者 | 三套并存仍在，见 P3-6 |
| 反射适配器重复 | `ReflectiveFastLaneApi`（1188 行，engine-fastlane）与 `ReflectiveAvfVirtualMachineApi`（852 行，engine-avf）仍各自实现解析/校验/异常映射，无共享层；但两者均已有对应 Test 类 | 较 2026-10-27 有改善（已进 runtime、可 JVM 测）；抽 `avf-reflect` 维持"需接口裁定"，并入 P2-2 的架构类观察，不单列 |
| NDK 双版本 | app = 27.0.12077973（catalog）；QEMU 供给链锁 r28c / 28.2.13676358（`tools/qemu-android/scripts/verify-qemu-jni-lock.sh:63-64`、`qemu-android.yml:113,152,159`） | 可能有意（上游 QEMU 工具链），但仓库内无口径说明，见 P3-5 |
| toolchain 重复 | 14 个 `runtime/*/build.gradle.kts` 逐字重复 `-Xlint:all -Werror` 编译参数 | 见 P3-4 |
| `check_sensitive_data.py`、`check_ci_module_coverage.py` | 本机可跑，后者 232/0 | 门禁活着 |

---

## 3. cleanup-plan.md §4"需维护者裁定"项现状

| # | 原项 | 现状（b39e8e6 + 主检出只读核对） | 判定 |
|---|---|---|---|
| 4-1 | `build/llvm-android/` 2.1 GiB | 主检出已无该目录 | **已处置** |
| 4-2 | `.scratch/` 旧 tracker 213 文件 | 仍在；`orchestella-platform/issues/17-prototype-avf-e2e.md`、`18-prototype-qemu-baseline.md` 仍 `Status: open`；另新增 `orchestella-uml-fabric`、`quality-round-20261103`、`round-20261104-device-window` 等 effort | **仍待裁定**（并入 P2-7） |
| 4-3 | 工作区根 12 份 Prompt 交接文档 | 老会话已归档进 `docs/agent-handoff/archive/`（含 `2026-10-avf`、`2026-10-phase5`）；但工作区根又积累会话 20–25 共 6 份新 Prompt（仓库外、无版本保护） | **惯性复发**（并入 P2-7 的流程建议） |
| 4-4 | `docs/reports/` 9 个"磁盘有、索引无"文件 | **仍在主检出**，逐字比对 `git ls-files` vs 磁盘：`archive/2026-10-v045/` 下 6 份、`orchestration/hardware-virtualization-zero-device-plan-20261019.zh-CN.md`、`tickets/avf-shizuku-09/10` 各 1 份 | **仍待裁定，升 P0**（见 P0-1） |
| 4-5 | `phase5-references/` 215 MiB / 23 个 git 仓库 | 仍在工作区 | **仍待裁定**（并入 P2-7） |
| 4-6 | 12 个第三方上游 clone | 仍在工作区 | **仍待裁定**（并入 P2-7） |
| 4-7 | `Orchestella/` 58 GiB 实占 | 仍在 | **仍待裁定**（并入 P2-7） |
| 4-8 | 分支侧未竟工作 | `feat/pvm-09-*` 已以 PR #28 并入 main（`e4e862e`）；工作区分支剩 `chore/repo-hygiene-20261104`、`feat/pvm-06-worker-wiring`、`fix/ci-verify-runtime-assets`、`fix/fastlane-packaging`；**但 `backup/pvm-09-facts`、`backup/pvm-09-manifest` 两个 tag 经 `git merge-base --is-ancestor` 验证均不在 main**，仍指向未合并内容 | **部分处置**（剩 2 个 tag，见 P3-2） |

---

## 4. AGENTS.md / CONTEXT.md 过期描述

### AGENTS.md（4 处，其中 3 处建议本轮文字修）

| 位置 | 原文 | 现实 | 判定 |
|---|---|---|---|
| 第 5 行 | "当前源码状态：0.6.0 / code600（main `0383393` 之后的稳定版准备轮，2026-10-29），公开安装包仍为 v0.4.5 开发预览版" | `app/build.gradle.kts:171-172` = **0.6.2 / code602**；tag `v0.6.0`/`v0.6.1`/`v0.6.2` 已存在；README 徽章 v0.6.2；CHANGELOG 已记 0.6.0/0.6.1/0.6.2 | **过期**（P0-2） |
| 第 7 行 | "发布签名、QEMU/gvproxy 停止收敛、跨 Controller 单实例互斥、终端输入生命周期和 CI 门禁是 P0/P1 待完成项" | 发布签名接线已做（`2eb42de` "signing wiring for the extension and a draft-only publish job"）；停止收敛已做（`e130897` 停止信号组件 + `p0-2-runtime-supervisor/issues/01..06` 全部 resolved + `RuntimeProcessSupervisor` 已被 `AvfInstanceLauncher.java:4` 消费）；跨 Controller 网关互斥判定已入库（`GatewaySlotArbiter`/`GatewaySlotVerdict`，生产接线留设备窗）；CI 门禁已扩容（§1.1 4.5 条） | **过期**（P0-2，改写为指向新主线：UML fabric / 设备窗 / 发布线） |
| 第 17 行 | "当前决策地图：`.scratch/orchestella-fastlane/map.md`" | 地图存在但已非当前 effort；最新 effort 为 `orchestella-uml-fabric`、`quality-round-20261103`、`round-20261104-device-window` | **过期**（P0-2） |
| 第 16 行 | "真机截图/录屏/完整设备日志……永不入库" | `docs/assets/screenshots/*.jpg` 6 张已入库并被 README 引用，敏感媒体门有意放行（`3a532b0`、`c9717c3`）；README 界面预览段还写明了真机芯片/系统来源 | **口径冲突**：要么收紧 README，要么把"产品截图经门禁放行"写进政策原文——两边都能自洽，但不能都保持现状（P1-3，需维护者裁定，本报告不代改） |

其余条目（ADR-0015 缺失、`GPL_RELEASE_DELIVERY_POLICY.md` 不存在、DEFENSIVE_CONSTRAINTS 指针、文档 `git add -f` 规则）核对无误，仍然准确。

### CONTEXT.md

总账相符：`RuntimeProcessSupervisor`、`FastLaneStopResult`（`runtime/engine-fastlane/.../FastLaneStopResult.java:5-7` 的 CONFIRMED/UNCONFIRMED/ALREADY_STOPPED）、只读共享（`ReadOnlyStager`）、Wayland-first + RFB 实验通路（`RfbDisplayChannel`/`AvfRfbOverVsockTest`）均有对应实现，无与代码相抵触的条目。唯一建议：第 72 行"16 KB 真机运行未测"、AGENTS.md 第 5 行"真实 AVF Guest 成功样本为 0"这类时效性声明没有"截至日期"锚点，随研究轮推进（如 UML fabric T7/T8 已产生真机样本）容易整段腐烂——建议统一加"截至 YYYY-MM-DD"前缀（并入 P0-2 的文字修）。

另：`docs/reports/README.md:3` 仍写"当前源码为 0.5.3 / code503，公开安装包仍为 v0.4.5"——过期两个版本号，属 trivial 文档修，本轮已顺手修（见 §6 声明）。

---

## 5. P0–P3 积压清单

> 每项含：现状证据 / 影响 / 建议动作 / 预估规模（S ≤ 半天，M 1–3 天，L > 3 天）/ 是否零设备 / 是否需维护者裁定。

### P0（2 项）

#### P0-1 主检出 `docs/reports/` 9 个证据文件未入库，一条命令即永久丢失

- **现状证据**：在主检出（非本 worktree）比对 `git ls-files docs/reports` 与磁盘，差集 9 个文件：`archive/2026-10-v045/orchestration/consolidation-and-next-phase-20261015.zh-CN.md`、`archive/2026-10-v045/reviews/consolidation-20261015/review-report.zh-CN.md`、`archive/2026-10-v045/reviews/device-avf-validation-20261015/review-report.zh-CN.md`、`archive/2026-10-v045/reviews/ux-fixes-20261015/review-report.zh-CN.md`、`archive/2026-10-v045/tickets/device-avf-validation-20261015/implementation-report.zh-CN.md`、`archive/2026-10-v045/tickets/ux-fixes-20261015/implementation-report.zh-CN.md`、`orchestration/hardware-virtualization-zero-device-plan-20261019.zh-CN.md`、`tickets/avf-shizuku-09/implementation-report.zh-CN.md`、`tickets/avf-shizuku-10/implementation-report.zh-CN.md`。即 cleanup-plan §4-4，至今未处置。
- **影响**：`docs/` 被 gitignore，这 9 份（含真机验证报告与 2026-10-19 硬件虚拟化方案）对 git 完全不可见；一次 `git clean -fdx`（整理工作区时是常规动作，cleanup-plan 本身就在做这类事）即不可逆丢失。
- **建议动作**：维护者二选一——(a) 过敏感门后 `git add -f` 这 9 份并补 `docs/reports/README.md` 索引；(b) 显式宣布放弃并移出仓库目录（也消除误删风险）。不要维持现状。
- **规模**：S。**零设备**：是。**需裁定**：是（选 a 或 b，但必须选一个）。

#### P0-2 AGENTS.md 版本/主线/effort 指针过期两个版本号，正在误导 agent 轮次

- **现状证据**：§4 表 3 处（第 5、7、17 行）+ CONTEXT.md/`docs/reports/README.md` 的时效性声明无日期锚点。
- **影响**：AGENTS.md 是每个 agent 会话的第一输入。"待完成 P0/P1 清单"实际已完成大半、"当前 effort"指向 fastlane 而现实主线是 UML fabric 与设备窗——轻则重复劳动，重则按过期主线做决策。
- **建议动作**：S 级文字修：版本行改 0.6.2/code602 并去掉"公开安装包仍为 v0.4.5"；主线行改写为当前事实（发布线已进 0.6.x 维护、UML fabric/设备窗为当前 effort，附三份 map 路径）；时效声明加"截至"日期。**本报告不代改**（涉及主线表述，需维护者口径），但改动量约 20 行。
- **规模**：S。**零设备**：是。**需裁定**：是（主线一句话怎么写）。

### P1（4 项）

#### P1-1 `RfbInputEncoder`（270 行）零测试——历史"最危险三件套"最后一员

- **现状证据**：`runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbInputEncoder.java`（按键/修饰键/文本编码，`MAX_TEXT_CODE_POINTS=1024`、修饰键合成状态机 `held`/`syntheticModifiers`）；全仓无任何 Test 文件引用该类（`rg -ln "RfbInputEncoder" --glob "*Test*"` 0 命中）。同批被点名的 `EndpointPolicy`/`PathBoundary`/`StoragePaths` 已全部有测试（§1.1），唯它漏网。
- **影响**：它是宿主→guest 的输入边界编码器；修饰键粘滞、超长文本截断等回归会直接表现为"guest 收到错键"，且无网。
- **建议动作**：补 `RfbInputEncoderTest`：修饰键按下/释放配对、synthetic 修饰键清理、1024 code point 截断、UTF-8 多字节、`held` 状态跨 session 复位。纯 JVM。
- **规模**：M。**零设备**：是。**需裁定**：否。

#### P1-2 `release.yml:64` 唯一残留的真实包安装吞错

- **现状证据**：`"$SDKMANAGER" "platforms;android-36" "build-tools;36.1.0" "ndk;27.0.12077973" || true`。其余 8 处 `|| true` 均为有意保留的 `yes | sdkmanager --licenses`。
- **影响**：发布流水线的 SDK 安装失败被吞，症状推迟到后续步骤才爆（主报告 §2.5 描述过的同型失真）。Q1 的 `verifyRuntimeAssets` fail-fast 与 `check_runtime_payload.py --apk` 兜底缩小了爆炸半径，但发布链路不应留这个模式。
- **建议动作**：照 `fastlane-zero-device.yml:236-237` 的 `if ! ...; then ::error::...; exit 1` 改写。
- **规模**：S。**零设备**：是（CI 语法可本地校验）。**需裁定**：否。

#### P1-3 敏感政策原文与"真机截图已入库"的口径冲突

- **现状证据**：AGENTS.md:16 字面禁止"真机截图……永不入库"；`docs/assets/screenshots/` 下 6 张真机截图已跟踪并被 README 引用；敏感媒体门已有放行规则（`c9717c3`、`3a532b0`）。README 界面预览段还写明截图来自真机（含芯片/系统版本描述）。
- **影响**：政策与现状互相矛盾，后续 agent 无法判断"能不能再入库一张截图/该不该删现有的"；这类口径漂移正是设备证据红线的薄弱点。
- **建议动作**：维护者裁定二选一：(a) 政策加"产品宣传截图经 sensitive-media gate 放行，需去序列号/去指纹"条款；(b) 截图退场换渲染图。同时在 README 的设备描述上复评是否收敛到 device-a 式别名。
- **规模**：S。**零设备**：是。**需裁定**：是。

#### P1-4 launch 判决双点收敛（LaunchCoordinator 或等价物）

- **现状证据**：`rg LaunchCoordinator` 全仓 0 命中；`MainActivity.kt:1718` 计算 `preferAvf`、`AcceleratorLaunchDispatcher.kt:232` 在 flag-on 路径硬编码 `SOFTWARE_VIRTUALIZATION`；flag-off 兜底走 `MainActivity.kt:1780` 的 `strategyLauncher.launch(parameters, preferAvf)`。结构比 2026-10-27 好（dispatcher 成为唯一分派点、fallback 显式），但"两处判决输入不同源"的本质未变。
- **影响**：未来新增执行策略（UML/PVM）时，双判决点极易再次出现"一处算了一处被丢弃"。
- **建议动作**：不急于抽大接口——先把"判决 = f(facts, flag)"收敛为纯函数并加表驱动测试（`AcceleratorLaunchDispatcherTest` 已存在可扩展），MainActivity 只传 facts；`LaunchCoordinator` 大改等 UML 线定型后一起定。
- **规模**：M。**零设备**：是。**需裁定**：是（接口时机，PVM/UML 定型前是否冻结）。

### P2（7 项）

#### P2-1 剩余写路径目录锁收尾（`delete`/`create`/`exportToArchive`）

- **现状证据**：`InstanceLifecycle.java:1006`（delete）、`:1010/1014/1019`（create×3 重载）、`:1576-1580`（exportToArchive）均只持实例级 `synchronized(this)`；app 会 new 多个 Controller，实例锁不互斥目录。对照组：`importFromArchive` 的落盘段已在 `InstanceStorageManager.java:501` 收口（含 save 失败回滚删卷）。
- **影响**：同目录双 Controller 并发时，delete/create 与 start/transition 之间可交错（旧快照覆盖状态转换）；exportToArchive 的 `requireExportable` 检查与实际读盘之间有锁窗。
- **建议动作**：照 importFromArchive 范式补齐三个入口；`ConcurrentResizeContractTest` 同款并发测试各补 1 例。`delete` 内部已有 `deleteLocked` 分层，改动面小。
- **规模**：M。**零设备**：是。**需裁定**：否。

#### P2-2 超大文件治理：12 个 >800 行，MainActivity 回涨

- **现状证据**：§2 表。MainActivity 2097→1830（删 267 行死代码）→**1994**（净回涨 164 行）；`SetupWizardActivity`（1508）与它的同构下载/装配流水线仍在。
- **影响**：改动热区集中、review 面大、合并冲突高发；回涨说明"删死代码"是持续行为而非一次工程。
- **建议动作**：两步走——(a) 引入"新增文件 ≤800 行"软门（配合 P2-3 detekt 规则，baseline 豁免存量）；(b) 从低风险开始拆：MainActivity 的更新检查/关于入口（0.6.2 新增）先抽 `UpdateUiController`，向导流水线抽共享 `ProvisionFlow`。反射适配器共享层维持"接口裁定"观察。
- **规模**：L（分批）。**零设备**：是。**需裁定**：软门阈值。

#### P2-3 Kotlin 静态检查引入（ktlint + detekt + baseline）

- **现状证据**：§1.3 P2-8 行——0 工具命中；Kotlin 侧无 `allWarningsAsErrors`。
- **影响**：格式/缺陷类问题零自动化防线；本报告 78 个空 catch 这类问题目前只能靠人肉盘点。
- **建议动作**：按主报告原方案：`.editorconfig` 先行（见 P3-3）→ ktlint 格式门 → detekt + baseline 只拦新增。首次引入需联网拉插件与生成全仓 baseline。
- **规模**：M。**零设备**：是。**需裁定**：是（工具选型 + 是否阻断 CI）。

#### P2-4 供应链：workflow pin SHA + Gradle 依赖锁定

- **现状证据**：§1.3 P2-10 行——约 30 处 `uses:` 全部 tag pin；无 `verification-metadata.xml`、无 `dependencyLocking`。
- **影响**：tag 可变，第三方 action 供应链面暴露；依赖解析不可复现。
- **建议动作**：`uses:` 逐个 pin 到 commit SHA（一次性，配 dependabot 后续升版）；`verification-metadata.xml` 生成需联网与镜像策略，单独一个维护者会。
- **规模**：M。**零设备**：是。**需裁定**：是（锁定策略与镜像）。

#### P2-5 `ProtectedWarmPool.acquire()` 持锁跨 VM 冷启动

- **现状证据**：§1.3 P1-5 行——`api.start`（VM 创建）全程持 `ReentrantLock`；`state()/stats()/release()` 同锁（`ProtectedWarmPool.java:301,310,499`）。
- **影响**：冷创建期间（秒级）状态查询与释放全阻塞；第二个 acquire 以 `WAIT_TIMEOUT` 报"池忙"，语义可辩但可解释性差。
- **建议动作**：若单槽池语义是有意的，最低成本是在 `WAIT_TIMEOUT` 异常文案与 Javadoc 里写明"持有者正在冷创建"；若要改，`CREATING` 后释放锁、完成落 `READY`（原方案），需重审 `leaseChanged` 条件变量逻辑。
- **规模**：S（文档化）/ M（改锁）。**零设备**：是。**需裁定**：是（语义取向）。

#### P2-6 `qemu-android.yml` 超时顶格 + 5 个 workflow 无 concurrency

- **现状证据**：§1.3 P2-11 行——`:172` 360 分钟顶平台上限；`release.yml`/`qemu-android.yml`/`fastlane-dv01b-zero-device.yml`/`guest-assets.yml`/`sensitive-data-guard.yml` 无 `concurrency`。
- **影响**：长 job 失败只能整段重跑；同 ref 连推会并行堆积 run。
- **建议动作**：360 分钟 job 拆单步 timeout（总计不变）；5 个 workflow 补 `concurrency: group + cancel-in-progress`。
- **规模**：S。**零设备**：是。**需裁定**：否。

#### P2-7 cleanup-plan §4 剩余裁定资产包（.scratch 旧 tracker / 参考仓库 / `Orchestella/` 目录）

- **现状证据**：§3 表 4-2/4-5/4-6/4-7 全部仍在；`orchestella-platform` #17/#18 仍 open；工作区根新增会话 20–25 六份 Prompt 复发 4-3 模式。
- **影响**：盘面认知成本持续存在；但都是"慢"风险，无即时损失。
- **建议动作**：给每项一个明确裁断（保留/归档/删除）并回写 cleanup-plan；新增 effort 收尾时同步归档其 Prompt（写进会话收尾清单）。
- **规模**：M（多为决定+搬运）。**零设备**：是。**需裁定**：是（整包）。

### P3（8 项）

#### P3-1 生产代码 78 个纯空 catch 补理由注释

- **现状证据**：分布（按文件数排序）：`BridgeProxyGateway` 8、`VirglJniBridge` 7、`LowLatencyAudioPlayer` 6、`PointerCaptureHandler` 4、`WaylandSurfaceController` 4、`FileDropTargetListener` 4、`TcpAudioSource` 3、`LocalSocketAudioSource` 3、`QemuInstanceLauncher` 2、`PersistentTerminalSessionPool` 5、`DesktopActivity` 4、`WaylandSurfaceView` 2、其余 `AboutActivity`/`MainActivity`/`SetupWizardActivity`/`NetworkGatewayDaemon`/`TerminalActivity:1032`/`TerminalHistoryStore`/`ImeBridgeInputConnection`/`LogViewerActivity`/`MirrorSpeedTester`/`NativeGlesSurfacePresenter`/`PackagedAssetStreams`/`FileDropTargetListener`/`terminal/TerminalSessionInstance`/`InstanceLifecycle:961`/`InstanceSnapshotManager:237`/`InstanceStorageManager:521`/`ResilientDownloadEngine:368`/`QemuQmpHostfwdClient`×2/`BridgeClient`×3 各 1–2。绝大多数是 close/清理 best-effort 惯例，与 `SocketPortForwarder.java:69-79` 的"带一句理由"口径对齐即可；个别（如 `TerminalActivity:1032`、`NetworkGatewayDaemon:91`）值得人工确认是否应升 observable。
- **建议动作**：机械补注释一轮；有疑问的 3–5 处单独看。若 P2-3 detekt 落地，加 `EmptyCatchBlock` 规则（baseline 豁免存量、注释豁免）。
- **规模**：S。**零设备**：是。**需裁定**：否。

#### P3-2 `backup/pvm-09-facts`、`backup/pvm-09-manifest` 两个 tag 指向未合并内容

- **现状证据**：`git merge-base --is-ancestor` 确认两 tag 均不在 main；对应 `feat/pvm-09-*` 主题已由 PR #28 以另一实现并入。
- **影响**：tag 永不被 gc，无丢失风险；风险是"以为那是实现"的误读。低。
- **建议动作**：裁定后删除 tag 或在 tag message/docs 里标注"未采用的历史变体"。
- **规模**：S。**零设备**：是。**需裁定**：是。

#### P3-3 `.editorconfig` 缺失

- **现状证据**：仓库根无该文件。B线-3 建议至今未落地。
- **建议动作**：补 Kotlin/Java/Python/Shell/YAML 基础条目（缩进、换行、charset），零行为风险。
- **规模**：S。**零设备**：是。**需裁定**：否。

#### P3-4 14 个 runtime 模块逐字重复编译参数

- **现状证据**：`rg -c "\-Xlint:all" runtime/*/build.gradle.kts` = 14 处重复（另 3 处 app/accel 变体）。
- **建议动作**：收敛到根 `subprojects` 块或 convention plugin；注意 `engine-avf` 等模块有局部差异（process oracle onlyIf），收敛时保留差异点。
- **规模**：S/M。**零设备**：是（改动后需一次全模块 compile 验证——本 lane 不跑，留给执行 lane）。**需裁定**：否。

#### P3-5 NDK 双版本无口径说明

- **现状证据**：app `27.0.12077973`（catalog）vs QEMU 链 `28.2.13676358/r28c`（`verify-qemu-jni-lock.sh:63-64`）。
- **建议动作**：若有意（上游 QEMU 工具链要求），在 `tools/qemu-android/` README 或 ADR 补一段"为什么两个 NDK"；若无意，排升级。
- **规模**：S。**零设备**：是。**需裁定**：是（口径本身）。

#### P3-6 日志三套并存

- **现状证据**：§2 表——`android.util.Log`（9 文件）、`java.util.logging`（4 文件）、runtime 侧靠返回值/observer。
- **建议动作**：定一句口径（如：app 层 android.util.Log、runtime 层禁止 android 依赖改用轻量 sink、java.util.logging 迁移），再按口径小步迁移。runtime 保持"不 import android.*"是硬约束，别为统一破坏它。
- **规模**：M。**零设备**：是。**需裁定**：口径。

#### P3-7 `SocketPortForwarder` 线程模型残留

- **现状证据**：`:53` `newCachedThreadPool`（无界，daemon）；`:216-217` `t1.join(); t2.join()` 阻塞池线程，与 accept 共用池。
- **影响**：大量短连接时线程数不可控；join 占坑降低池吞吐。异常语义已修（Q2），此项纯资源模型。
- **建议动作**：每连接改虚拟线程（JDK21 若可用）或有界池 + 拒绝计数；join 改 `CountDownLatch`/关闭驱动。
- **规模**：M。**零设备**：是。**需裁定**：否（但依赖 targetSdk/JDK 版本约束，执行时确认）。

#### P3-8 `MobileAuxiliaryKeyBar.setTargetInput` 废弃残留

- **现状证据**：`MobileAuxiliaryKeyBar.java:114-117` 空 `@Deprecated` 方法，注释称 Ticket 06 已消除调用场景。
- **建议动作**：确认无调用后删除方法（连带 `@Deprecated`）。
- **规模**：S。**零设备**：是。**需裁定**：否。

---

## 6. 本轮顺手修与声明

- **顺手修（trivial，单独 commit）**：`docs/reports/README.md:3` 的版本行"0.5.3 / code503、公开安装包仍为 v0.4.5"更新为 0.6.2 现实，并在"当前有效"清单补本报告索引条目。除此之外未改任何产品代码与 CI。
- 本轮未跑 gradle、未调用 adb、未连设备；`check_ci_module_coverage.py`（232/0）为唯一执行的工具脚本。
- 本轮产出无设备序列号、无真实型号、无私人数据；设备一律 device-a/device-b 口径，序列号只以 redacted-serial-hash 指代。
- 报告中的文件行号以 `b39e8e6` 为准，后续提交会使行号漂移，类名/方法名是更稳定的锚点。

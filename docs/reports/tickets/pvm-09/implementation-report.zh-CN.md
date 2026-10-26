# PVM-09 实施报告：产品接线——manifest、事实来源与 PVM_PAYLOAD 车道启动

- 日期：2026-10-26
- 分支：`feat/pvm-09-product-wiring`（基线 `main = 6f1b420`；实现 `7704203` + 评审修补 `c034f35`）
- 性质：integration（Java/Kotlin + 构建 + Python 工具），零设备

## 1. 结论

| 项 | 结果 | 一句话 |
| --- | --- | --- |
| PVM_PAYLOAD 在产品路径可被选中 | **done** | manifest 解析 → `payloadCompilerPresent` → `AcceleratorPolicy` 选出 `backend=PVM_PAYLOAD`（truth-table v2 锁定），决策 JSON 原样进入 dispatch 结果 |
| PVM_PAYLOAD 可被启动 | **done（fake VM）** | `PvmPayloadLaneStarter` 反射 AVF API 起 protected payload VM，spec 全字段来自 manifest；dispatcher 按 `decisionBackend()` 分发；fake VM 测试覆盖启动/就绪/停止/失败码 |
| flag 关闭零副作用 | **done** | `acceleratorLaneEnabled=false` 时不读任何 fact、不建任何 backend 工厂、不预留预算（`AcceleratorLaunchDispatcherTest.flagOffDelegatesToTodayLaunchWithoutReadingFactsOrBuildingLanes`） |
| manifest 生成与构建期校验 | **done** | `assemble_payload_libs.py` 生成 schema-1 manifest，`--verify-manifest` 只读门禁挂 Gradle `preBuild` |
| token 结论 | **done** | 三条口径写进 payload 注释、README、starter/facts 注释，无悬案（§6） |
| 真机项（选道、payload 真启动、DV-PVM-02b 全部） | **not-tested** | 全部归 DV-PVM-02b（§7、§8） |

5 个工单任务（manifest 生成、Java 解析器、facts 接 manifest、starter + fake VM 测试、dispatcher 分发 + flag 测试）全部落地；token 结论与文档同批交付。

## 2. 仓库状态（分支、commit、PR、CI）

| 项 | 值 |
| --- | --- |
| 分支 | `feat/pvm-09-product-wiring` |
| 基线 | `main = 6f1b420`（`fix(pvm): DV-PVM-02a 真机跑通产品版 clang 引擎（02b not-attempted）（#27）`） |
| commit | `7704203`（实现，21 文件 +2898/−61）+ `c034f35`（评审修补：声明态编译器身份入日志、注释收尾、测试清理） |
| PR | [#28](https://github.com/Nyzeep/Orchestella-Studio/pull/28) |
| CI 结果 | **12/12 checks 通过**（head `a5edb4d` 全绿：runs 40248985727/30/36/61）；本回填提交在 PR 内复跑一轮后合并 |
| `./gradlew :app:testDebugUnitTest :runtime:fastlane-capability:test :runtime:instance:test` | **1346 tests / 0 failures / 0 errors / 0 skipped**（136 个测试类，commit `7704203`，ASCII worktree 实测；`.c034f35` 仅动 app 模块，复跑 `:app:testDebugUnitTest` 结果见下） |
| `./gradlew :app:testDebugUnitTest`（`c034f35` 复跑） | **996 tests / 0 failures / 0 errors / 0 skipped**（125 个测试类） |

本地已跑通、结果为实数的验证：

| 命令 | 结果 |
| --- | --- |
| `python -m unittest discover -s tools/pvm-compiler` | **41/41 OK**（2026-10-26 本机实测，21.0s） |
| `python tools/ci/check_ci_module_coverage.py` | **58 passed / 0 failed**（2026-10-26 本机实测） |

## 3. manifest（格式、生成、校验证据）

### 3.1 格式

asset 名 `PVM-COMPILER-MANIFEST`（`AndroidAcceleratorFactProvider.PVM_MANIFEST_ASSET`，`app/src/main/java/io/github/nyzeep/orchestella/AndroidAcceleratorFactProvider.java:53`）。UTF-8 JSON、无扩展名、schema 固定整数 `1`（字符串 `"1"` 不接受）。七个字段：

| 字段 | 含义 | 约束 |
| --- | --- | --- |
| `schema` | 格式版本 | 整数 `1` |
| `compiler_version` | 编译器版本（构建期声明，非握手实测） | 非空串 |
| `target_triple` | payload 编译期固化的 triple | 非空串；生成器必填显式传 |
| `vcpu_topology` | vCPU 拓扑 | 恒为 `one_cpu` |
| `memory_mib` | VM 内存 | 整数，`1..16384` |
| `payload_binary` | 编译服务 payload 文件名 | 匹配 `[A-Za-z0-9._-]+\.so`，无路径 |
| `libs[]` | `{name, size, sha256}` | 按 name 升序、覆盖 `arm64-v8a/` 全部实际存在的 `.so`、sha256 为 64 位小写 hex |

### 3.2 生成

生成器是 `tools/pvm-compiler/assemble_payload_libs.py` 的扩展：`build_manifest`（`assemble_payload_libs.py:103`）、`write_manifest`（:131）、`verify_manifest`（:151）。库与 manifest 一条命令同时产出；`--target-triple` 与 `--compiler-version` 是必填 CLI 参数，生成器不猜。

工单示例里的 `aarch64-unknown-linux-gnu` 已被产品要求取代：dispatcher 声明的 guest 编译器身份是 `aarch64-unknown-linux-android`（`AcceleratorLaunchDispatcher.GUEST_COMPILER_TRIPLE`，`app/src/main/java/io/github/nyzeep/orchestella/AcceleratorLaunchDispatcher.kt:75`），README 与全部测试夹具已按 android triple 记录（偏差 1，见 §7）。

### 3.3 构建期校验

`--verify-manifest <staging>` 只读门禁，任一缺陷 `SystemExit(1)` 且逐条点名库与维度：

| 拒绝维度 | 证据 |
| --- | --- |
| 缺 manifest / 坏 JSON / 非对象 / `schema != 1`（含字符串 `"1"`） | `test_pvm_compiler_manifest.py` 的 `test_missing_manifest_fails` / `test_invalid_json_fails` / `test_wrong_schema_number_fails` / `test_string_schema_fails` |
| 必填字段缺失 / 空串 / `memory_mib` 越界 / `payload_binary` 带路径 / topology 非 `one_cpu` | `test_missing_field_fails` / `test_blank_string_field_fails` / `test_memory_mib_out_of_range_fails` / `test_payload_binary_with_a_path_fails` / `test_topology_other_than_one_cpu_fails` |
| 缺库（manifest 声明但磁盘没有）/ 多库（磁盘有但未声明）/ size 不符 / SHA-256 不符 / 重复条目 | `test_missing_library_fails` / `test_undeclared_library_fails` / `test_size_tampering_fails` / `test_sha256_tampering_fails` / `test_duplicate_library_entry_fails` |

Python 侧 verify 用例共 20 个（1 个正面 `test_verify_accepts_a_freshly_generated_staging_directory` + 19 个拒绝/互斥用例，含 `--verify-manifest` 与生成参数互斥的 `test_verify_rejects_generation_arguments`），随全量 41 用例跑通。

### 3.4 Gradle 侧

`app/build.gradle.kts:218-276`：属性 `orchestella.pvmCompiler.libDir` 打开时——

- `require` staging 目录下 `assets/PVM-COMPILER-MANIFEST` 存在（缺了直接构建失败，提示重新跑生成器）；
- `assets/` 加为 assets 源（`arm64-v8a/` 只走 jniLibs，绝不重复进 assets）；
- 注册 Exec 任务 `verifyPvmCompilerManifest` 执行 `assemble_payload_libs.py --verify-manifest`，挂在 `preBuild` 上，**每次构建**都重校验。

全部逻辑位于 `if (pvmCompilerLibDir != null)` 块内：属性不设则不注册任何任务、不加 assets 源，APK 与既有发布完全一致（opt-in 语义）。manifest 本体不入库——`libs[]` 描述的是这一次构建的字节（PVM-04 产物、NDK 版本、AGP strip 都会变），提交必然与真实库脱节；进库的只有生成器与单测（`tools/pvm-compiler/README.zh-CN.md`「为什么 manifest 不提交」）。

### 3.5 Java 解析器与测试证据

`PvmCompilerManifest`（`runtime/fastlane-capability/src/main/java/io/github/nyzeep/orchestella/runtime/fastlane/capability/accelerator/PvmCompilerManifest.java:35`）零依赖、自带最小严格 JSON reader（:242）：拒重复键、尾随字符、前导零、深度越界（>8）、字符串内裸控制字符、分数/指数；未知字段忽略（前向兼容）、缺字段/类型错/越界一律 `Optional.empty()` 保守处理。任何缺陷都不算「存在」，`payloadCompilerPresent` 保持 false，策略拒绝车道而不是猜。

`PvmCompilerManifestTest`（19 个用例）覆盖：全字段解析、缺 manifest/坏 JSON/非对象根/尾随字符/逐必填字段缺失/schema 类型/拓扑/内存越界/空身份字段/payload 名/空 libs/重名/坏 sha/坏 size/坏库名/顶层重复键/未知字段/浅层未知对象/排序/不可变。

## 4. facts 与选道（单测、决策样例 JSON）

### 4.1 facts 读 manifest

`AndroidAcceleratorFactProvider` 新增第六个 seam `PayloadManifestSource`（构造函数与 `create()` 均接线，`AndroidAcceleratorFactProvider.java:105-143`）。读取路径：

- 只读 asset `readBoundedAsset`（:358）：16 KB 上限（`PVM_MANIFEST_MAX_BYTES`，:68），超限/缺失/不可读一律 empty，不落盘；
- `readPayloadManifest`（:391，public static）是唯一的 asset 名出现点，同一 reader 同时喂 facts 和生产 lane starter（`PvmPayloadLaneStarter.createIfInstalled`），两者对「装没装」永远一致；
- 解析失败/字段缺失/schema 不认识 → `payloadCompilerPresent=false`、version/triple 为空串，保守处理（`fromStatus` :218-277）；
- `protectedVmSupported` 的 `MODE_UNSUPPORTED` 映射未动（:235-244）：protected-only 平台是 payload 后端的算力面，但「编译器真的在不在」只由 manifest 决定。专门测试 `payloadFactsDoNotDecideProtectedVmSupport`（`AndroidAcceleratorFactProviderTest.java:307`）锁定 READY≠protected。

单测覆盖（`AndroidAcceleratorFactProviderTest` 新增 8 个）：正常解析（version=22.1.8、triple=aarch64-unknown-linux-android）、asset 缺失、坏 JSON、字段缺失、blank version+triple、源抛异常、protected 判定不受 payload facts 影响、seam 全链路。

### 4.2 决策样例（truth-table v2 原文）

资源：`runtime/fastlane-capability/src/test/resources/accelerator/truth-table-v2.json`（26 例，`AcceleratorPolicyTest` 参数化执行）。protected-only + payload 可用即选中 PVM_PAYLOAD 的样例原文：

```json
{"case":"protected-only-payload-selected","decision":{"mode":"QEMU_PLUS_ACCEL","reasonCode":"ACCEL_AVAILABLE","backend":"PVM_PAYLOAD","degradedCapabilities":[],"rejections":[]}}
```

产生该决策的 facts 构造（`AcceleratorPolicyTest.java:120-126`）：`protectedOnly()` + `payloadCompilerPresent(true)` + `payloadCompilerVersion("clang-20.1.0")` + `payloadTargetTriple(= requiredTriple)`。对照样例同样锁定：manifest 缺失 → `PAYLOAD_IMAGE_MISSING`、triple 不符 → `PAYLOAD_COMPILER_MISMATCH`、温度预算 → `THERMAL_BUDGET`，且 payload 优先于 CUSTOM_VM（`payload-priority-over-custom-vm`）。

### 4.3 哈希校验的分层归属

- **与磁盘一致性的校验在构建期 Python verify 侧**（§3.3 的 20 个用例承担，含 size/SHA-256 篡改）；
- **Java 解析器只验格式**（字段类型、hex 形状、范围、名字合规），不做任何磁盘 I/O——它读的是 APK asset，没有可对照的磁盘目录。工单任务清单里的「哈希不匹配」用例由此由 verify 侧承担，Java 侧不重复。

## 5. 车道启动（fake VM 测试、失败码）

### 5.1 `PvmPayloadLaneStarter`

`app/src/main/java/io/github/nyzeep/orchestella/PvmPayloadLaneStarter.java`：

- 反射 AVF API：`ReflectiveFastLaneApi.setProtectedVm(true)` 路径（`createIfInstalled` :74 用与 facts 相同的 manifest reader 构造）；
- spec 全字段来自 manifest（`launchSpec` :207）：`payloadBinary`/`memoryMiB` 取 manifest，拓扑固定 `ONE_CPU`（manifest 的 `vcpu_topology` 按契约恒为 `one_cpu`），`DEBUG_LEVEL.NONE`（生产），加密存储取最小 1 MiB（payload 只读 APK 内库、零持久写，平台要求正数，:101）；
- 四个 vsock 端口来自 payload 契约（control/terminal/proxy/distcc），启动期间只 distcc 承载编译服务；
- `toolboxManifestSha256` 传空串：诚实声明无 FLN1 toolbox，不用占位哈希假装有（:219-222）；
- **triple 预检在任何平台调用之前**（`start()` :145-147）：请求身份与 manifest 不符 → `PAYLOAD_COMPILER_MISMATCH`，测试断言 `api.startCount == 0`（`tripleMismatchRefusesBeforeAnyPlatformCall`）；
- 停止不在本类实现：`ProtectedLaneRuntime.stop → FastLaneSession.stopRuntime`，QEMU→ACCEL 顺序由 `DualLaneCoordinator` 既有逻辑保持；`stopGraceMs()` 只是把自己配置的宽限交给执行停止的 owner。

### 5.2 失败映射

三个固定码 + unknown 兜底（`mapStartFailure` :226）：

| 固定码 | 触发 |
| --- | --- |
| `PAYLOAD_UNAVAILABLE_NO_PROTECTED` | `PROTECTED_UNAVAILABLE` / `HIDDEN_API_BLOCKED` / `PERMISSION_DENIED` |
| `PAYLOAD_LOAD_FAILED` | `PAYLOAD_VERIFY_FAILED` / `VM_CREATE_FAILED` / `CONFIG_INVALID` / `VSOCK_FAILED` / `STOP_UNCONFIRMED`，以及一切未知 reason 与意外 `RuntimeException` |
| `PAYLOAD_COMPILER_MISMATCH` | triple 预检不符（平台调用前拒绝） |

无异常文本、路径、token 离开本类。

### 5.3 fake VM 测试清单（`PvmPayloadLaneStarterTest`，10 用例）

| 测试 | 覆盖 |
| --- | --- |
| `startBuildsTheCompilerPayloadSpecAndWrapsTheSession` | spec 全字段（payload 名/内存/ONE_CPU/NONE/四端口/正数加密存储/空 toolbox digest），返回 `ProtectedLaneRuntime` |
| `readinessFollowsTheBackendWithoutBlockingStart` | start 返回时 payload 未 ready，之后 `awaitReady` 观察 READY——不阻塞 start |
| `readyTimeoutReportsLoadFailed` | 预算耗尽 → LOAD_FAILED |
| `deadVmReportsLoadFailed` | VM 死亡 → LOAD_FAILED |
| `unavailableProtectedVmKeepsTheNoProtectedCode` | 创建被拒 3 reason → NO_PROTECTED |
| `loadSideFailuresKeepTheLoadFailedCode` | load 侧 5 reason → LOAD_FAILED |
| `unexpectedRuntimeFailureKeepsTheLoadFailedCode` | 意外 RuntimeException → LOAD_FAILED |
| `tripleMismatchRefusesBeforeAnyPlatformCall` | 平台零调用（startCount==0） |
| `emptyRequiredTripleSkipsTheComparison` | 空 triple = 不比较 |
| `stopDelegatesToTheSessionAndConfirmsOnce` | stop 一次确认，二次幂等（不再触发平台停止） |

### 5.4 就绪链路（重点）

```
AVF onPayloadReady 回调
  → ReflectiveFastLaneApi 状态置位（ReflectiveFastLaneApi.java:411）
  → FastLaneHandle.payloadReady()（ReflectiveFastLaneApiTest.payloadReadyIsObservable 锁定）
  → FastLaneSession.payloadReady()（FastLaneSession.java:58）
  → ProtectedLaneRuntime.isReady()（app/src/main/java/io/github/nyzeep/orchestella/ProtectedLaneRuntime.java:34）
```

三层各有归属，**生产门只有一道**：

- `PvmPayloadLaneStarter.start()` 遵守 `LaneStarter` 契约：只创建并运行 VM，不等待就绪（类注释明说两条规则的张力及处理方式）；
- **生产 AXP1 门 = `DualLaneCoordinator.awaitAcceleratorReady(long)`**（`app/src/main/java/io/github/nyzeep/orchestella/DualLaneCoordinator.java:213`，AX-01 既有 API）：编译工作连接 AXP1 前调用，看到 `isReady()` 才放行；超时/车道丢失返回 false = 本地编译，QEMU 不受影响；
- `starter.awaitReady` 是独立的有界等待，给**没有 coordinator 的直连调用方**（设备验证驱动/工具）用，不是第二道生产门。

编译工作的生产调用方（guest distcc → 宿主桥 → payload vsock 的转发）属 DV-PVM-02b 设备工作，本票不接——**这是边界，不是遗漏**。

## 6. token 结论

按工单 §4 三条口径写全，无悬案：

1. **静态 asset token 不作为安全边界**：它随 APK 分发、每个装了这个 build 的设备都一样，不是 per-run 秘密；
2. **访问控制 = 只有持有 VM 句柄的宿主进程能 `connectVsock` + app 沙箱**：这是平台真实边界，token 对它没有增量；
3. **静态 token 降级为构建期防误连**：剩余价值是让同包不同 build 的桥接不会互串；per-run 认证需要平台提供 payload 配置/env 通道（与 `execute_no_trans` 同级诉求），不在本票，也不用「看起来像秘密的常量」假装实现。

落地位置：

| 位置 | 形态 |
| --- | --- |
| `fastlane-payload/src/pvm_compiler_payload_main.cpp:101-110` | 纯注释（零代码改动），写在 `read_token_from_apk_contents` 上方 |
| `tools/pvm-compiler/README.zh-CN.md`「AXP1 token 的口径（PVM-09 结论）」整节 | 文档，含「为什么不存在合规通道」的三条平台事实 |
| `PvmPayloadLaneStarter` / `AndroidAcceleratorFactProvider` 相关注释 | 代码注释 |

QA 构建把 token 写进 `app/src/main/assets/accel_token`（读取顺序 env → apkContents/assets，见 README），token 文件本身不进库。

## 7. 未验证项与偏差

### 7.1 未验证项（不许当已验证读）

| 项 | 状态 | 归谁 |
| --- | --- | --- |
| 选道在真设备上生效 | not-tested | DV-PVM-02b |
| payload 真启动（protected VM 真实加载编译 payload） | not-tested（fake VM 测试之外无证据） | DV-PVM-02b |
| DV-PVM-02b 全部（guest 端到端、桥接转发链路） | not-tested | DV-PVM-02b |
| Gradle 单测 | 已在 ASCII worktree 验证（§2，1346/0/0/0）；本机仓库路径（中文）下仍跑不了，CI 结果待回填 | CI |

### 7.2 偏差

1. **target_triple 值**：工单示例 manifest 的 `aarch64-unknown-linux-gnu` 被产品要求 `aarch64-unknown-linux-android`（`AcceleratorLaunchDispatcher.GUEST_COMPILER_TRIPLE`）取代；生成器不设默认，必填显式传入。README 与全部夹具已按 android triple 记录。
2. **内存无运行时回退**：没有「manifest 缺 `memory_mib` 时回退 2048」的逻辑——整个 manifest 被拒（比工单更保守）；2048 只是生成器 CLI 的默认值（`--memory-mib`），写进 manifest 后即成契约。
3. **`FastLaneLaunchSpec.toolboxManifestSha256` 放宽为允许空**：编译 payload 无 FLN1 toolbox，空串=诚实声明缺席；已核实该字段不在 AXP1 wire 上（仅 spec 内部），正反测试齐全（`FastLaneLaunchSpecTest.acceptsEmptyToolboxHash` + 非法哈希仍拒绝）。
4. **「两个版本进 decision 可观测字段」的落地形态**：决策 JSON 保持稳定契约不扩字段（扩字段需显式版本化 golden）；声明态（manifest 的 version/triple）改为 flag-on 时在生产日志打一行 `payload-compiler present=… version=… triple=…`（`MainActivity.kt`，与 `backend=` 决策行相邻，flag 关闭时不发生该读取）；握手实测版本（`Axp1Capabilities.ID_COMPILER_VERSION`）随 DV-PVM-02b 编译工作接线一起进观测。评审（两轴）确认此改道。
5. **`requiredCompilerTriple` 非空对 CUSTOM_VM 同样生效**：`GUEST_COMPILER_TRIPLE` 会与加速镜像 manifest 的 triple 严格比较（`AcceleratorPolicy.evaluateCustomVm`）。这是设计意图（不匹配即拒绝、不带病运行，与 PVM-01 纪律一致），且无已发布行为变化——flag 关闭路径不变，flag 打开但无镜像仍是 `QEMU_ONLY`（镜像缺失主导）。**推论**：未来的加速镜像（AX-08）manifest 必须声明 `aarch64-unknown-linux-android`，否则 flag-on 的 CUSTOM_VM 会被 `COMPILER_MISMATCH` 拒绝；仓内历史夹具的 `aarch64-linux-gnu` 只是测试值。

### 7.3 计划外改动声明

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `docs/agent-handoff/accelerator/device-cross-lane-test-prompt-20261023.zh-CN.md` | +2 行改道提示 | 历史任务书假定 non-protected 设备（bit2），当前设备 protected-only，按 DV-PVM-01 重走；原文保留 |
| `.github/workflows/ci.yml` | 仅新增「PVM compiler manifest unit tests」一步（`python3 -B -m unittest discover -s tools/pvm-compiler`） | manifest 生成器与校验门禁的 CI 覆盖；夹具自建最小 ELF 桩，无需 NDK/设备 |
| `.scratch/` 下 5 个工单/决策地图文件 | 勾选与状态同步（共 +31 行） | 本票完成后的工单簿记；**按工单纪律保持本地未提交、不进 PR** |

## 8. 给 DV-PVM-02b 的交接（怎么在真机上把两条车道同时拉起来）

### 8.1 前置

- 设备：`qa-alt-01`，API 37，arm64-v8a，`capabilities_raw = 1`（protected-only）——与 DV-PVM-02a 同一台；
- 权限：`MANAGE_VIRTUAL_MACHINE`、`USE_CUSTOM_VIRTUAL_MACHINE`（02a 已 `pm grant` 成功）。

### 8.2 开关组合（两条车道同时拉起来）

| 开关 | 值 | 说明 |
| --- | --- | --- |
| `orchestella.acceleratorLane`（Gradle 属性） | 打开 | 映射为 BuildConfig 字段 `ACCELERATOR_LANE_ENABLED`（`app/build.gradle.kts:118` 与 `:143`） |
| `orchestella.pvmCompiler.libDir` | 指向 staging 目录（含 `arm64-v8a/` 与 `assets/PVM-COMPILER-MANIFEST`） | 还须同时给 `orchestella.fastlane.payloadLibDir`（属性间锁步，`app/build.gradle.kts:222-226`） |
| staging 生成 | 先跑 `tools/pvm-compiler/assemble_payload_libs.py` | README「命令」一节的完整命令，`--target-triple aarch64-unknown-linux-android` 必填；产物在 `--out`（默认 `build/pvm-compiler/staging`） |

装配链：`MainActivity.kt:1573` 调 `AcceleratorLaunchDispatcher.dispatch` → `AcceleratorLaneAssembly.assemble` 产出决策 → `DualLaneCoordinator.startAll()` 同时起 QEMU（主车道）与选中的加速车道。

### 8.3 选道预期

| 设备状态 + 输入 | 预期 decision |
| --- | --- |
| protected-only + manifest 在场且解析通过 | `backend=PVM_PAYLOAD`，`mode=QEMU_PLUS_ACCEL`，`reasonCode=ACCEL_AVAILABLE` |
| protected-only + manifest 缺失/损坏 | `QEMU_ONLY`，reason 可解释（`PAYLOAD_IMAGE_MISSING` 等） |
| 要走 CUSTOM_VM | 加速镜像三件套（kernel/initrd/rootfs.ext4）须在 `filesDir/accelerator-image/` |

### 8.4 观测

- `adb logcat -s OrchestellaAccelerator`：`LANE_STARTED` / `LANE_READY` / `LANE_LOST` 事件 + `backend=<…>` 与 decision JSON 行 + `payload-compiler present=… version=… triple=…` 声明态身份行（flag-on 时，`MainActivity.kt` dispatch 结果处理块）；
- 失败码含义：

| 码 | 含义 |
| --- | --- |
| `PAYLOAD_UNAVAILABLE_NO_PROTECTED` | 平台拒绝给 protected VM（权限/隐藏 API/能力） |
| `PAYLOAD_LOAD_FAILED` | 加载侧一切失败（verify/create/config/vsock/stop/未知），含就绪超时 |
| `PAYLOAD_COMPILER_MISMATCH` | triple 预检不符（任何平台调用之前拒绝） |
| `ACCEL_NO_BACKEND` | 决策选中的 backend 没有可启动的 starter（HOST_NATIVE/NONE） |

### 8.5 已知设备坑（从 DV-PVM-02a 继承）

1. **HyperOS 冻结**：VM 起后约 3 s 冻结 QA 进程 → `payloadReady` 后立即 `am start` 把应用前台化，否则所有线程停调度、症状伪装成「payload 卡死」；
2. **`adb forward` 本机不投递**：宿主→设备的 TCP 转发不可信 → 走文件通道 `tools/device-validation/dv_pvm_compiler.py --mode files`（bridge 模式保留但标注未打通）；
3. **残留 VM**：上次异常退出会让 `create` 失败 → 先 force-stop 应用并删 VM 目录再重试。

### 8.6 就绪等待与 token

- 编译工作连接 AXP1 前**必须过 `coordinator.awaitAcceleratorReady(timeoutMs)`**，不要自己写轮询——超时返回 false = 本地编译，QEMU 不受影响；
- token：QA 构建需把 `accel_token` 写进 `app/src/main/assets/`（不进库），payload 读取顺序 env → asset（README「AXP1 token 的口径」）。

### 8.7 用户要求

02b 测试完成后，把测试手机关机。

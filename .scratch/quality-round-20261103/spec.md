# Spec · 零设备质量收敛（Q1–Q5）

- 日期：2026-11-03
- 基线：main `24aef0e`（origin = github.com/Nyzeep/Orchestella-Studio，工作树干净）
- 主题：**让失败可解释**。当前痛点是「静默失败」与「构建期缺件不报错」，不是缺功能。
- 纪律：全程零设备。任何需真机的判据标 `not-tested` 并给下个窗口入口。不裁定开发通道产品形态（ACR 未投票），不引车道相关产品行为，不改版本号/签名/发布通道/ADR 正文，不拆分巨型文件。
- 侦查笔记：`.scratch/quality-round-20261103/notes/B1..B5-*.md`（本地工作产物，含本机绝对路径，**不入库**）

## 0. 任务陈述

把「静默失败」与「构建期缺件不报错」两类不可解释的失败，改成零设备可验证的显式失败与可追溯诊断：构建期缺件 fail-fast（Q1）、三处静默失败收口（Q2）、孤儿网关启动期判定设计与纯 JVM 组件（Q3）、依赖与版本目录显式化（Q4）、Tracker 真伪对账（Q5）。

## 1. 与 prompt 的两处事实修正（已核实，以此为准）

| prompt 表述 | 仓库事实 | 处置 |
|---|---|---|
| Q1「`Image` 与 `initramfs.cpio.gz` 被 gitignore，干净 clone 的 assembleDebug 照样成功」隐含两者都在 `jniLibs/` | `Image` 在 `app/src/main/assets/Image`（52,070,912 B，`aaptOptions.noCompress`）；`initramfs.cpio.gz` 在 `app/src/main/resources/initramfs.cpio.gz`（1,267,115 B，走 java resource，因 aapt 会改 `.gz` 名）；只有 `libdroidlayer_qemu.so` 在 `jniLibs/arm64-v8a/`（`app/build.gradle.kts:4-11` 注释即为此） | 门禁校验三个**实际路径**，不按 prompt 字面猜目录 |
| Q2「端口转发一旦失败，用户只看到连不上」隐含 `SocketPortForwarder` 在生产链路上 | 生产端口转发走 gvproxy + `NetworkGatewayDaemon.ForwardingEngine`；`SocketPortForwarder` **生产零引用**，是 QEMU profile 的契约参考实现（仅 `PortForwardingManagerContractTest:48` 构造，经 `testJar` 发布给 `engine-avf`） | 仍按 Q2 收口（它是发布出去的契约工件），但报告须如实写明「该处静默失败当前不直接产生用户可见症状」；Q2 里**唯一用户可感**的是 `proxy_relay` 静默降级 |

另两条已核实的事实，直接约束实现：

- `QemuRuntimeBootstrapper` 是**纯 JVM 类**（只 import `java.*` 与 `runtime.*`），不得引入 `android.util.Log`，否则 23 个既有 JVM 测试全红。诊断必须走注入式 sink。
- `runtime/network` 与 `runtime/instance` 都是 `-Xlint:all -Werror`，且 `runtime/network` 的 `test` 任务 `exclude("**/*")`——**新增测试不挂 `JavaExec` + `check` 就永远不跑**。

## 2. 功能规格

### Q1 · 构建期缺件 fail-fast

- 在 `app/build.gradle.kts` 顶层（`android {}` 之外、`packageOrchestellaRelease` 之前）注册 `verifyRuntimeAssets`（`group = "verification"`），挂 `tasks.named("preBuild") { dependsOn(...) }`，与既有 `verifyPvmCompilerManifest`（`:295-308`）同形。
- 判据（三条，且只比这三条）：
  1. `app/src/main/assets/Image`
  2. `app/src/main/resources/initramfs.cpio.gz`
  3. `app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so`
  逐件校验「存在 + 非空 + sha256 等于 `app/src/main/assets/runtime-artifacts-v1.sha256` 里同名条目的摘要」。清单按**名字**查表，不按行号——入库清单第三行是 `QEMU-BUILD-INPUTS` 而非 `.so`。
  **不得校验清单全部 7 项**：`NOTICE.QEMU` 与 `QEMU-GPL-SOURCES.md` 自 `e16af2c` 起已与清单漂移，全量校验当场就红。
- 两条豁免，都必须显式且留痕：
  1. 外部载荷模式：任一 `orchestella.qemu.*` **或** `droidlayer.qemu.*` 属性非空 → 只断言三个目录存在且非空，跳过文件与摘要校验（`qemu-android.yml:372-374` 用的是遗留 `droidlayer` 前缀）。
  2. 干净 checkout 豁免：`-Porchestella.assets.allowMissingPayload=true` → `logger.warn` + 在 `build/` 写 `runtime-assets-missing.flag` + 跳过。
- 失败信息必须可操作：列出缺哪件、期望什么摘要、怎么取件（指向 `guest-assets/run-in-builder.sh` 与 CI 的 `-P` 路径），不回显本机绝对路径。
- 同批改 CI 调用面（否则 PR 门禁红）：`ci.yml:197`、`release.yml:139`、`release.yml:145` 三处干净 checkout 构建加豁免属性并写注释说明「这是已知缺件基线，真实载荷由 `publish-draft` 的 `check_runtime_payload.py --apk` 兜底」。
- 文档事实（**不改产物**）：入库清单、`qemu-android.yml:205-208/222-229` 的 GA base 摘要、`tools/qemu-android/ci/apk-postbuild-expectations-v1.json` 三组锚点锚的**不是同一批字节**（initramfs 分别为 `c96fe6e6…`/1,267,115 B、`92766a6f…`/1,091,309 B、`f8db1b52…`），不可互相套用。写进本轮报告。

### Q2 · 三处静默失败收口

原则：**保持对外契约不变**（`PortForwardingEngine` 不加抽象方法、`PortForwardingRule` 一行不改、`QemuRuntimeBootstrapper` 既有 `bootstrap(...)` 重载签名不动），改为「节流诊断 + 可注入观察者」。

- 新增 `runtime/network/.../PortForwardingObserver.java`：多事件接口，**每个方法都有 `default {}` 空实现**（对齐 `DownloadProgressCallback:33/42`），提供 `NOOP` 常量。事件至少覆盖：accept 失败（含 `fatal` 标志）、客户端连接失败、relay 异常结束、关闭汇总。
- `SocketPortForwarder`：
  - 新增 `SocketPortForwarder(PortForwardingObserver)` 重载，保留无参构造（委托 `NOOP`）；`null` 视为 `NOOP`。
  - **observer 必须绝缘**：回调走统一 `notify(...)`，内部 catch `RuntimeException` 吞掉并注释说明——这是全类唯一允许保留的空 catch。
  - accept 循环的 `IOException` 分支（`:101-105`）：由「静默忙旋」改为「节流记录 + 50ms→200ms 指数退避、上限 1s + 连续失败 ≥10 次则记录并中止该 listener（`fatal=true`）」。`SocketException` + `isClosed()` 的正常移除路径保持静默。
  - `handleClient` 的 `catch (Exception)`：收窄为 `IOException`「节流记录 + 继续」；`RuntimeException` 单独 catch「记录 + 重抛给线程默认处理器」。
  - `relay`：由 `static` 改实例方法，携带 `ruleId`/方向；「本端已关闭或对端干净 EOF」静默，其余「节流记录」。
  - `AcceptLoop.close()` 的 `catch (IOException)`：保持不抛（`Closeable` 契约），记录一条 DEBUG 级、不节流的事件。
  - `addRule` 同 id 重复：改为幂等替换（先关旧 listener 再绑定），并记一条事件——现状是旧 `ServerSocket` 既不关闭也不退出接受循环，属静默 fd 泄漏。
- 节流策略：粒度 key = `ruleId + eventKind + cause 简单类名`；同类 key 每 1000ms 最多 1 条；首条必出；`close()` 时以一条汇总事件报「抑制了 N 条」。**禁止按连接或按 4KB 打点**。
- `QemuRuntimeBootstrapper`：
  - 新增注入式诊断 sink（`java.util.function.Consumer<String>`，包私有构造重载，默认 `NOOP`），launcher 侧接 `LaunchTimeline::noteDegradation`。
  - `LaunchTimeline` 新增 `public synchronized void noteDegradation(String)`，写入既有 `diagnostics` 列表（序列化进 `"diagnostics":[...]`）。
  - `proxy_relay` 读取：**保留 `null` 容忍**（测试夹具对未知资源恒返回 `null`，23 个既有测试依赖此语义），但——资源缺失（`null`）记 `proxy-relay-absent`；抛异常记 `proxy-relay-unreadable: <cause>` 并**上抛 `IOException("proxy-relay-unavailable", failure)`**。区分「没打包」（可容忍）与「打包了但读不出来」（打包损坏，不可容忍）。这与 `guest_init` 的 `guest-init-unavailable` 口径对齐。
- 测试（全部纯 JVM，且必须挂 `JavaExec` + `check`）：
  - `SocketPortForwarderDiagnosticsTest`（`main` + `@Test` 双入口）：注入会抛 `IOException` 的 `PortForwardingObserver` 观察者不得打死 accept 循环；guest 端口未监听时 `onClientFailure` 至少 1 次；同 key 连续失败被节流（记录数 < 失败次数）；同 id 二次 `addRule` 不泄漏监听口。
  - `QemuRuntimeBootstrapper` 诊断测试：注入会抛 `IOException` 的 `resourceStreams`，断言 `proxy_relay` 读取失败**被记录**且不再静默；注入返回 `null` 的流，断言记录 `proxy-relay-absent` 且 bootstrap 仍成功。

### Q3 · 孤儿网关启动期判定（设计 + 纯 JVM 组件，**零接线**）

- 交付一个纯 JVM 深模块（`runtime/instance`，无 Android、无线程、无 I/O 副作用），形态对齐 `InstanceStopSignal`：
  - `GatewaySlotVerdict`（枚举，保守命名）：`FREE` / `ORPHAN_OF_THIS_INSTANCE` / `HELD_BY_UNKNOWN` / `EVIDENCE_UNAVAILABLE`。
  - `ProcessRef`（pid + cmdline 快照，值对象）。
  - `GatewaySlotEvidence`（值对象：`portBound`、`apiSocketPresent`、`List<ProcessRef>`、`evidenceComplete`）。
  - `GatewaySlotArbiter`（深模块）：`static` 纯判定 `verdict(instanceId, port, evidence)` + `describe(...)` 稳定短句；`requiredCmdlineMarker(instanceId)` / `derivedPort(instanceId)` 等派生函数集中在此，避免调用方各写一份。
- **身份判据**：`portBound` 只是必要不充分条件。`ORPHAN_OF_THIS_INSTANCE` 必须同时满足「端口被占」**且**「进程表中存在 cmdline 含 `net-<instanceId>.yaml` 的进程」。端口号永不是身份。证据缺失 → `HELD_BY_UNKNOWN` 或 `EVIDENCE_UNAVAILABLE`，**禁止**回收。
- **零接线**：`NetworkGatewayDaemon`、`QemuInstanceLauncher`、`MainActivity` 一行不改。类 javadoc 逐字对齐 `InstanceStopSignal:37-41` 的「Intentionally not wired into production」措辞，写明为何不接线（跨进程无句柄、误杀会让另一实例的 QEMU 因 netdev 断开而崩、回收确认链只能在真机验）。
- 契约测试 `GatewaySlotArbiterContractTest`（`main` + `@Test` 双入口），挂 `JavaExec` + `check`。场景含反向对照：端口空闲不得判孤儿；端口被占但身份不匹配不得判孤儿（这是最容易犯的错）；`/proc` 不可读时必须落到 `EVIDENCE_UNAVAILABLE` 而不是 `FREE`。
- 报告补「判定 → 提示 → 可选回收」三段设计与设备窗口执行入口（回收动作留设备窗口）。

### Q4 · 依赖与版本目录显式化

- `gradle/libs.versions.toml` 新增：`coroutines = "1.6.4"`、`shizuku = "13.1.5"`、`hiddenapibypass = "6.1"`、`desugarJdkLibs = "2.1.5"`、`buildtools = "36.1.0"`、`ndk = "27.0.12077973"`，以及对应 library 别名。
- `coroutines` **钉 1.6.4**：这是当前唯一解析版本（来自 `androidx.lifecycle:lifecycle-common:2.6.2` 的 POM）；`MainActivity.kt:1405,1409` 用了 `Dispatchers.Main`，故**必须显式声明 `-android`**（只声明 `-core` 能编译但运行期 `Main` 调度器初始化失败）。显式声明 `-core` 与 `-android` 两条。
- `app/build.gradle.kts`：`buildToolsVersion`、`ndkVersion`、`coreLibraryDesugaring`、shizuku ×2、hiddenapibypass 全部改 catalog 引用；`:576-577` 的 zipalign 查找路径改用同一 `libs.versions.buildtools.get()`（否则 `36.1.0` 二次漂移）；删除与 `:483` 逐字重复的 `:495` `androidTestImplementation(project(":runtime:fastlane-terminal"))`。
- `accel-extension/build.gradle.kts:168` 同步用 catalog（不改则留下第二份 `36.1.0` 字面量）。
- **不动**：`:190` `cmake { version = "3.22.1" }`、`:491` 的 `androidTestImplementation(engine-fastlane)`（属传递冗余而非逐字重复，删它属顺手改）、`applicationId`/`versionCode`/`versionName`（`check_release_fields.py` 用正则抓这些字段，catalog 化会让它 `ValueError`）。
- `research/uml-runner` 是独立 Groovy 构建、不读本 catalog，无需连带回归。

### Q5 · Tracker 对账

- `.scratch/p0-2-runtime-supervisor/issues/01..05`：逐条用代码证据核对后标 `resolved` 并写 `## Comments`（首次为这些票补上该段——它们「零 Comments」正是本轮问题的根因）。每条 Comments 必须给 `file:line` + 实跑命令 + 结果，并区分「已验证 / not-tested」。
- `06`（集成回归）：本轮 Phase 4 会实跑 `:runtime:engine-qemu:check`、`:runtime:instance:check`、`:app:testDebugUnitTest`、`git diff --check`，据此回填；未跑到的项写 `needs-info` 并说明缺哪条证据。
- 新建 Q1–Q4 四张票到 `.scratch/quality-round-20261103/issues/`，沿用 p0-2 范式（`# Q<n> 标题` + `Status:`/`Type:`/`Blocked by:`/`Effort:` + `## 背景`/`## 交付`/`## 验收`/`## 测试`/`## 不在范围`/`## Comments`），实现完成后回填 Comments。
- fastlane 侧：`P13`（`DualLaneCoordinator` 的 ACCEL_LOST 不释放 `accelReservation`）是唯一「票面与代码一致」的真未完成项，**本轮不修**（Q5 只对账，不做车道行为改动），在报告里记录坐标与影响。P03/P09/P11 的状态回写与报告死链属另一 effort 的文档债，本轮只记录不动。

## 3. 验收口径（实施前冻结）

1. Q1：`./gradlew :app:assembleDebug` 在缺件时必须失败且错误可操作；**反证输出**（故意移走 `Image` → 失败 → 放回 → BUILD SUCCESSFUL）贴进报告；CI 三处干净 checkout 构建不因此转红。
2. Q2：三处静默失败各有「先红后绿」测试；高频路径有节流（同类事件 1s 内不超过 1 条）；`PortForwardingManagerContractTest` 8 个 scenario 原样通过。
3. Q3：契约测试全绿且挂进 `:runtime:instance:check`；生产代码零改动（`git diff --stat` 证明 `app/src/main` 与 `NetworkGatewayDaemon` 未被触碰）。
4. Q4：依赖全部显式、构建绿；`MainActivity` 仍能编译（`Dispatchers.Main` 有实现）。
5. Q5：每张被动过的票都有代码证据支撑的状态与 Comments；Q1–Q4 四张票存在且格式一致。
6. 全量：`./gradlew :app:assembleDebug :app:testDebugUnitTest` 与改动模块的 `:check` 全绿；新单测在 **ASCII 路径（`X:`）** 跑绿；敏感数据门三扫通过；报告入库。

## 4. 排除项

- 车道形态裁定与任何车道相关产品行为（UML/AVF/QEMU 选择）。
- 任何真机/模拟器操作（全部 `not-tested`）。
- 巨型文件拆分（`MainActivity.kt` 1,993 行、`InstanceLifecycle.java` 1,696 行）。
- 版本号变更、签名、发布通道、`docs/adr/` 正文改写、ADR-0015 缺口补造。
- 上一轮已落地的 provision / FGS / staging 逻辑。
- `guest-assets/` 任何改动（initramfs 产物本机无法重打，改了也不会生效；`runtime-entry.sh` 的 `proxy-relay` 缺 else 分支只记录不动）。
- 重新生成 `runtime-artifacts-v1.sha256`（属「改产物」，需产品裁决）。
- fastlane `P13` 的代码修复。

## 5. 执行波次

| 波次 | 票 | 并行性 | 理由 |
|---|---|---|---|
| W1 | Q2、Q3、Q5 | 并行 | 文件集互不相交（Q2: `runtime/network` + `app/.../QemuRuntimeBootstrapper.java`/`LaunchTimeline.java`；Q3: `runtime/instance`；Q5: `.scratch/**` 纯 markdown） |
| W1 门 | `:runtime:network:check`、`:runtime:instance:check` | 串行 | 避免并发 Gradle 争同一构建目录 |
| W2 | Q1 → Q4 | 串行 | 两票都要改 `app/build.gradle.kts` |
| 终门 | `:app:assembleDebug`、`:app:testDebugUnitTest`、反证、敏感门 | 串行 | 全量 |

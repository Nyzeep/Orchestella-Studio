# 09: T3 · InstanceStopSignal 停止信号组件（不接线）

Type: implementation
Status: ready-for-agent
Priority: P1
Owner: （待分配）
Blocked by: None
External gates: 无（零设备，100% 可验证）

## 背景（已实证）

真机第二轮 T8①（`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md:59-63`）三条路径全 PASS，其中 P1 是「启动期清理陈旧 stop 文件」修复的真机实证：11-07 轮 T5 门1 cycle1 的假失败（`files/uml-stop` 遗留 → 新实例 549ms 自杀）自此闭环。

**但产品侧没有对等宿主**（已核实）：全仓 `uml-stop` / `stopFile` / `STOP_FILE` 零命中；产品的停止是「控制套接字 + 有界执行器」模型，8 条停止路径全部收敛到 `RuntimeProcessSupervisor.stop()`（`app/src/main/java/.../RuntimeProcessSupervisor.java:120-137`），类注释明写「唯一有界执行点」（`:8-22`）。

用户已裁定：**方案 B —— 抽独立组件 + 契约测试，不接线**。理由：产品没有 stop-file 的需求来源（研究 APK 无控制面才用文件信号），机制不可平移、不变量可平移。

## 必须做

1. 新增 `runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/InstanceStopSignal.java`：
   - 构造：`InstanceStopSignal(Path stopMarker)`（纯 `java.nio.file`，**零 Android 依赖**）。
   - `void prepareRound()`：删除陈旧 marker（启动期 sweep，权威修法）。
   - `boolean watch(BooleanSupplier alive, Runnable onConsume)`：≤500ms 轮询；`alive` 为假即返回；命中 marker → 执行 `onConsume` 一次 → 删除 marker → 置 `consumed`（一次性）；返回本次是否消费。
   - `void completeRound()`：post-exit sweep（`waitFor` 返回后的兜底），保证不变式「任何一轮结束，marker 不存在」。
   - 线程安全：`watch` 只允许一个调用线程；`consumed` 用 `volatile`。**不启后台线程**（由调用方决定线程模型），符合产品「不新增轮询线程」的约束。
   - 类注释写清三段式 A/B/C 的对应关系与它**当前无生产调用方**（避免被误认为死代码）。
2. 新增 `runtime/instance/src/test/java/.../InstanceStopSignalContractTest.java`，**镜像** `research/uml-runner/tools/stop_watcher_sim.sh` 的 4 场景 6 断言：
   - S1 运行中 stop：watch 消费并删除 marker；
   - S2 死后竞态（**双向**）：先复制「不修」语义断言遗留（用一个只 sweep 不兜底的对照路径），再断言 `completeRound()` 清理；
   - S3 陈旧遗留 + 新实例：`prepareRound()` 后 marker 消失，且 watch 不立即 consume；
   - S4 自然退出：无 marker 残留。
   - 用 `@Rule TemporaryFolder`（`app/src/test/.../RuntimeProcessSupervisorTest.java:24-60` 同型）；`alive` 用可变布尔、轮询间隔用构造参数注入（默认 500ms，测试传 20ms），**测试不得 sleep 太长**。
3. 注册到 `:runtime:instance:check`：`runtime/instance/build.gradle.kts` 现有 `check.dependsOn(...)` 清单（约 `:96-108`）。若 `test` task 已能发现 `@Test` 类则只需确认 `check` 覆盖 `test`；若沿用本模块的 `main()` harness 风格（`InstancePowerControllerTest.java:22-60` 是 `main`+`@Test` 双入口），照该风格加 `JavaExec` 并挂 `check`。**不挂 check 就等于没测**。

## 边界

- **零生产接线**：不得修改 `app/` 下任何文件、不得修改 `QemuInstanceLauncher` / `RuntimeProcessSupervisor` / `ExecutionSessionRegistry` / `OrchestellaForegroundService`。
- 不改 `research/uml-runner/`（参照实现只读）。
- 模块开 `-Xlint:all -Werror`（`runtime/instance/build.gradle.kts:1-20`），warning 即失败。

## 完成判据

`./gradlew :runtime:instance:check` 绿；`check` 的 task 列表里能看到新契约任务；组件类零 `android.*` import。

## Comments

- 2026-11-02 / 主会话：建票。侦查依据 `.scratch/orchestella-uml-fabric/notes/A2-launcher.md` §2.2（无对等宿主）、§3（三方案对比，B 推荐）、§4（可测性：必须放 `runtime:instance`）。用户裁定方案 B。
- 2026-11-02 / 实现子代理 + 主会话复核：**resolved**。新增 `runtime/instance/.../InstanceStopSignal.java`（`prepareRound` / `watch(alive,onConsume)` / `completeRound`，纯 `java.nio.file`、零后台线程、一次性消费、轮询间隔可注入）+ `InstanceStopSignalContractTest`（S1–S4，S2 双向断言「不兜底则遗留 / 兜底则清除」）。`runtime/instance/build.gradle.kts` 注册 `instanceStopSignalContractTest` 并挂 `check`。证据：`INSTANCE_STOP_SIGNAL_TEST_OK`，`TEST-…InstanceStopSignalContractTest.xml` `tests=4 failures=0`，`:runtime:instance:check` BUILD SUCCESSFUL。**零生产接线**：`app/`、`QemuInstanceLauncher`、`RuntimeProcessSupervisor` 无 diff。接线（方案 C）另立票且需设备复验。

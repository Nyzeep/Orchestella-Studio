# PVM-01 实施报告：编译服务后端抽象与选道

- Type: implementation report
- Date: 2026-10-24
- Ticket: `.scratch/orchestella-pvm-compiler/issues/PVM-01-编译服务后端抽象与选道.md`
- Base: `main 9b1a17a`
- Zero-device: 是（全程未接触设备 / adb / SELinux）

## 1. 改了什么

加速车道选道从「non-protected VM 或什么都没有」改为**有序多后端判据**：`PVM_PAYLOAD` → `CUSTOM_VM` → `HOST_NATIVE`（本轮恒不可选）。输出从「是/否」变为「选中哪个后端 + 每个被否后端的固定理由」。

| 文件 | 改动 |
| --- | --- |
| `runtime/fastlane-capability/.../accelerator/CompileBackend.java` | 新增：`PVM_PAYLOAD` / `CUSTOM_VM` / `HOST_NATIVE` / `NONE` 四值枚举 |
| `runtime/fastlane-capability/.../accelerator/BackendDecision.java` | 新增：单后端判定（backend + 固定 reasonCode；`ACCEL_AVAILABLE` ⇔ 选中） |
| `runtime/fastlane-capability/.../accelerator/AcceleratorFacts.java` | 增 4 个 payload 维度字段（builder 默认即保守拒绝）：`protectedVmSupported=false`、`payloadCompilerPresent=false`、`payloadCompilerVersion=""`、`payloadTargetTriple=""` |
| `runtime/fastlane-capability/.../accelerator/AcceleratorReasonCode.java` | 增 8 个固定码（spec §5.3）；旧 6 个码原样保留 |
| `runtime/fastlane-capability/.../accelerator/AcceleratorDecision.java` | record 扩为 `(mode, reasonCode, degradedCapabilities, backend, rejections)`；`available()` / `qemuOnly(reason)` 旧签名保留；`toJson()` 增加 `backend` 与 `rejections` 键 |
| `runtime/fastlane-capability/.../accelerator/AcceleratorPolicy.java` | 有序判据重写；每后端独立候选资格门 + 评估门；否决明细全部保留在 decision 上 |
| `runtime/fastlane-capability/.../AcceleratorPolicyTest.java` | truth table v2 参数化（26 组）+ 归因/兼容/永不被选断言 |
| `runtime/fastlane-capability/src/test/resources/accelerator/truth-table-v2.json` | 新增 golden（v1 16 组全保留 + 10 组新覆盖）；v1 文件原样保留 |
| `app/.../AndroidAcceleratorFactProvider.java` | 仅接 `protectedVmSupported`（`MODE_UNSUPPORTED` ⇒ true）；payload 4 字段不接（已知缺口） |
| `app/src/test/.../AndroidAcceleratorFactProviderTest.java` | 补 protected/payload 维度的保守性断言 |
| `app/src/test/.../AcceleratorLaneAssemblyTest.java` | 3 处顶层期望码更新（见 §3），其余断言未动 |

## 2. 选道语义

- **候选资格门**（不满足只记录、不评估）：`PVM_PAYLOAD` ⇔ `protectedVmSupported`；`CUSTOM_VM` ⇔ `avfPresent && nonProtectedSupported && !protectedOnly`。
- **评估门**（产生「最后一个/最相关」否决）：payload = 编译器存在（`PAYLOAD_IMAGE_MISSING`）→ 身份（triple 相等 + 版本非空 + 若给 required 版本则相等，否则 `PAYLOAD_COMPILER_MISMATCH`）→ 热/电（`THERMAL_BUDGET`）；custom VM = 镜像 / vsock / triple / 热/电（沿用 v1 顺序与码）。
- **终态规则**：reason/backend = 最后一个**真正被评估**的后端的否决结果；零个后端通过候选资格时终态 `ACCEL_UNAVAILABLE_NO_BACKEND` + `backend=NONE`。对照 kick-off 案例 1–7 逐条吻合（优先级、回退、归因、热/电）。
- `HOST_NATIVE` 有枚举值但无评估门，单测显式断言它永不被选中。

## 3. 预期内的 v1 行为变化（3 组顶层码）

| truth table 组 | v1 顶层码 | v2 顶层码 | v1 语义去向 |
| --- | --- | --- | --- |
| `no-avf` | `ACCEL_UNAVAILABLE_NO_NON_PROTECTED` | `ACCEL_UNAVAILABLE_NO_BACKEND`（backend=NONE） | 保留在 rejections 明细（CUSTOM_VM 分支） |
| `protected-only`（未声明 protected 事实） | 同上 | 同上 | 同上 |
| `non-protected-unsupported` | 同上 | 同上 | 同上 |

这是 kick-off 明文新语义（「ACCEL_UNAVAILABLE_NO_NON_PROTECTED 只允许由 CUSTOM_VM 分支产生」「全部不可用 → NO_BACKEND」）。`AcceleratorLaneAssemblyTest` 三处 `assertEquals` 期望码同步更新，测试未删除、其余断言未弱化；`AcceleratorLaunchDispatcherTest` 为子串断言（rejections 明细仍含旧码），未改动。

## 4. 已知缺口（明确不做，等后续票）

- `AndroidAcceleratorFactProvider` 不读 payload manifest：生产里 `payloadCompilerPresent` 恒为 false，`PVM_PAYLOAD` 在真机上暂时选不中（选道会在该门给出 `PAYLOAD_IMAGE_MISSING`）。manifest 格式由 PVM-04/08 定，Android 侧读取另开 app 票。
- 编译器版本分发未实现（已决 #3）：本轮只定义表达——`decide(facts, triple, requiredVersion)` 三参重载；两参入口不比对版本（历史签名）。
- `PAYLOAD_LOAD_FAILED` / `ARGV_REJECTED` / `JOB_TOO_LARGE` / `JOB_TIMEOUT` 已入 enum，但在选道期不可触发（它们是 payload 运行期失败码，归 PVM-03/06 接线）。
- `tools/accelerator-perf` 的 `ALLOWED_CODES` 未纳入新 PVM 失败码（PVM-07 范围外，避免夹带协议修订）。

## 5. 验证证据

- 本机（Git Bash / Windows，中文路径）：
  - `./gradlew :runtime:fastlane-capability:compileTestJava` → BUILD SUCCESSFUL（含 `-Xlint:all -Werror`）；
  - `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac` → BUILD SUCCESSFUL；
  - `./gradlew :app:compileDebugUnitTestJavaWithJavac :app:compileDebugUnitTestKotlin` → BUILD SUCCESSFUL。
- **单测未在本机执行**：`:runtime:fastlane-capability:test` 在本机复现既有环境问题（每个测试类 `initializationError: ClassNotFoundException`，中文路径下 Gradle test worker 类加载问题，与本轮改动无关）。单测与 truth table 断言以 PR 上 CI 的 Contract & Unit Tests 为准。
- flag 关闭零副作用：`AcceleratorLaneAssemblyTest.flagOffNeverReadsPlatformFacts` 等既有测试原样保留（断言 facts 不得被读），装配根代码路径未动。

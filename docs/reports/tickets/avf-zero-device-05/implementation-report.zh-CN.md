# 工单05实施报告：平台访问与 hidden API 接缝

- 票号/状态：avf-zero-device-05；**resolved**（零设备代码就绪）。
- 执行：Agent C / 2026-10-20。
- 基线：`ticket/07-ninep-and-port-forwarding` @ `face91f`；分支 `ticket/05-platform-access-hidden-api`（从 `lane/agent-c` 分出）。
- 证据边界：全部验收在 JVM / fake classloader / fake host 上完成。真实 AVF 样本仍为 **0**；`pm grant`、目标 ROM 的 hidden API 行为、真实 `getCapabilities` actor 均未验证。

## 1. 问题与范围

`AvfHostFactsProbe.readModeFacts()` 在 Activity 里直接 `Class.forName` + `getSystemService` + `getCapabilities`，没有 hidden API 豁免；受限设备返回 `UNKNOWN`，UI 无法区分“仅 protected”和“未知”。引擎 `ReflectiveAvfVirtualMachineApi` 又维护另一套 class shape、capability bit 和失败语义，探针与引擎可能对同一台设备给出不同结论。

本票只做平台访问接缝：宿主可见事实仍由 App 采集；平台事实、权限、capability bit、失败分类与豁免状态收进一个 adapter。未做 shell-owner/UserService/ACR-01、protected、真机 grant、targetSdk 或全局 hidden_api_policy 修改。

## 2. 实际实现

### 2.1 单一平台事实类型

- `runtime/capability/.../AvfPlatformFacts.java`：`AccessState`（`AVAILABLE` / `HIDDEN_API_BLOCKED` / `PERMISSION_DENIED` / `REFLECTION_UNAVAILABLE` / `MODE_UNSUPPORTED` / `MODE_UNKNOWN`）、`ExemptionState`（`NOT_ATTEMPTED` / `INSTALLED` / `FAILED`）、两个权限状态、`AvfModeFacts` 与稳定的 `summary()`。
- `runtime/capability/.../AvfPlatformAccess.java`：窄接口，允许未来第二个实现；当前只有反射 adapter。
- `runtime/engine-avf/.../ReflectiveAvfPlatformAccess.java`：唯一拥有 `android.system.virtualmachine.VirtualMachineManager` / `android.content.Context` 的 shape、`CAPABILITY_PROTECTED_VM=1` / `CAPABILITY_NON_PROTECTED_VM=2`、`getCapabilities()`、`checkSelfPermission()` 与失败分类的实现。
  - 失败映射：`SecurityException` → `PERMISSION_DENIED`；`ClassNotFoundException` / `NoClassDefFoundError` → `REFLECTION_UNAVAILABLE`；`NoSuchMethodException` / `NoSuchFieldException` / `IllegalAccessException` → `HIDDEN_API_BLOCKED`；`UnsupportedOperationException` → `MODE_UNSUPPORTED`；其余 → `MODE_UNKNOWN`。
  - 权限缺失时不再调用 `getCapabilities`，避免把权限拒绝误报成模式未知。
  - 豁免在任何平台 `loadClass` 之前执行，每个 adapter 只执行一次；失败只记录 `FAILED`，不重试、不猜测。

### 2.2 App 侧

- `AvfHostFactsProbe` 只保留 Android 可见事实（SDK、ABI、Build、feature、`SystemProperties`）与组装逻辑；删除 `Class.forName("android.system.virtualmachine.*")`、bit 常量与 `readModeFacts` 重复实现。`probe(Context)` 通过 `ReflectiveAvfPlatformAccess.observe()` 取得平台事实，权限和 mode 不再二次读取。
- 旧 7/8 参数 `probe(...)` 重载保留；新增带 `AvfPlatformFacts` 的重载。旧的仅 MANAGE 输入仍不能绕过 CUSTOM（既有 `manageGrantAloneDoesNotCompletePreflight` 继续通过）。
- 新增 `AvfHiddenApiExemption`：进程级一次性安装，`NOT_ATTEMPTED` / `INSTALLED` / `FAILED`，失败不重试。
- 新增 `OrchestellaApplication`，在 `attachBaseContext` 调用豁免；`AndroidManifest.xml` 增加 `android:name=".OrchestellaApplication"`。
- `AvfInstanceLauncher` 构造反射 adapter 时也传入 `AvfHiddenApiExemption::installOnce`，作为 Application 之外的第二道幂等保护。

### 2.3 评估与 UI

- `AvfCapabilityEvaluator` 新增 `HIDDEN_API_BLOCKED` / `REFLECTION_UNAVAILABLE` 状态；`AvfHostFacts` 增加可空 `platformFacts`（旧调用方保持兼容）。平台事实存在时以它为准：隐藏 API 受限、平台接口缺失、权限拒绝、仅 protected、读取未知分别给不同状态与可行动原因；`AVAILABLE` 仍走两项权限门。
- UI（MainActivity / SettingsActivity / SetupWizardActivity / ShizukuClientDialog）显示平台访问摘要；新状态不再落入“模式未知”或空白分支。`CapabilityMatrix.from(SessionCapabilities)`、`GRAPHICS_RFB` / `SHARED_DIRECTORY` 与 QEMU/AVF 矩阵未改。

### 2.4 依赖与许可

- `app/build.gradle.kts` pin `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`（AAR，POM 声明 Apache-2.0，`minCompileSdk=1`，与 `compileSdk 36` 兼容）。
- 新增 `app/src/main/assets/LICENSE.APACHE-2.0`（Apache 官方文本）；`third-party-notices.txt` 增加组件、上游、版本与用途说明；`tools/release/release_bundle_manifest.json` 纳入该许可文件。
- `tools/release/check_licenses.py` 通过：33 passed / 0 failed。

## 3. Red → green 证据

Red（实现前，实际执行）：

```bash
./gradlew :runtime:capability:check --no-daemon --stacktrace
# 失败：AvfPlatformFacts / 新 AvfStatus 不存在，20 个编译错误

./gradlew :runtime:engine-avf:check --no-daemon --stacktrace
# 失败：ReflectiveAvfPlatformAccess / AvfPlatformFacts 不存在

./gradlew :app:testDebugUnitTest --tests '*AvfHostFactsProbe*' --tests '*AvfHiddenApiExemption*' --no-daemon
# 失败：AvfPlatformFacts / AvfHiddenApiExemption 不存在
```

Green（实现后，实际执行）：

```bash
./gradlew :runtime:capability:check :runtime:engine-avf:check --no-daemon --stacktrace
# BUILD SUCCESSFUL；engine-avf 的 avfPlatformAccessTest 输出
# AVF_PLATFORM_ACCESS_TEST_OK states=6 exemption=2 engine_mapping=3

./gradlew :app:testDebugUnitTest --tests '*AvfPlatformAccess*' \
  --tests '*AvfHostFactsProbe*' --tests '*AvfCapabilityEvaluator*' \
  --tests '*AvfHiddenApiExemption*' --no-daemon
# BUILD SUCCESSFUL

./gradlew :app:testDebugUnitTest --no-daemon
# BUILD SUCCESSFUL（全部 app JVM 单元测试）

./gradlew :app:lintDebug :app:assembleDebug --no-daemon --stacktrace
# BUILD SUCCESSFUL（lint 0 error；APK 含 HiddenApiBypass 与 LICENSE.APACHE-2.0）
```

新增/更新的测试：

- `AvfPlatformAccessTest`（engine-avf JavaExec harness）：available / hidden-blocked / permission-denied / protected-only / missing-class / unexpected-exception / SecurityException / 豁免顺序与只执行一次 / 豁免失败记录 / 引擎映射同一 typed state。
- `AvfCapabilityEvaluatorTest`：六种 access state 的状态与原因；权限门不被平台事实绕过；仅 protected 与未知保持区分。
- `AvfAuthorizationWizardModelTest`：`HIDDEN_API_BLOCKED` / `REFLECTION_UNAVAILABLE` 不打开授权向导。
- `AvfHostFactsProbeTest`：平台事实直通、权限拒绝不被显示成 mode unknown、hidden API 原因可行动、protected-only/mode-unknown。
- `AvfHiddenApiExemptionTest`：只安装一次；失败记录且不重试。

## 4. 零设备状态与未验项

- 已通过（JVM / fake）：class shape、bit 常量、权限读取、失败分类、豁免顺序/幂等、评估矩阵、UI 编译、lint、assemble。
- 未验（真机，不属于本票）：目标 ROM 的 hidden API 是否真的被豁免；`pm grant` 两项权限的真实行为；真实 `getCapabilities()` 的 bits/actor；Application 在真实进程启动时的豁免时机；真实 AVF create/run/vsock/stop。
- 因此本报告只声明“代码就绪”，不声明 AVF 已支持/已通过/已加速。

## 5. 兼容、回滚与风险

- `AvfHostFacts`（engine-avf record）未改；旧 `AvfHostFactsProbe` 重载保留；`AvfModeFacts` 旧路径（`platformFacts == null`）保持原行为。
- 回滚：移除 `OrchestellaApplication` / manifest `android:name` / HiddenApiBypass 依赖 / 两个新 capability 类型与 adapter，恢复旧 probe 的 `readModeFacts`；类型化 facts 可作为测试工具保留。
- 风险：HiddenApiBypass 6.1 在目标 ROM 上可能失败（报告记录为 `FAILED`，不重试）；受限设备的 `NoSuchMethodException` 与真正的类缺失依赖平台表现，分类是启发式但 typed；未改 targetSdk、未关 lint、未改全局 hidden_api_policy 或 SELinux。

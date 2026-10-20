# 04 能力真相单一来源 — 实施报告

- 分支：`ticket/04-capability-truth`（基线 `ticket/08-android17-network-contract` @ `8918488`）
- 执行者：Agent B
- 日期：2026-10-20
- 状态：代码就绪（JVM/fake），真机未验

## 改了什么

1. `runtime/contract` 新增 `SessionCapabilities`：只描述一次启动实际装配的 terminal / bridge / display / audio / clipboard / pointer capture / 9p / forward / hardware acceleration / owner。构造器拒绝 software + hardware acceleration，以及 hardware 不声明 acceleration 的非法组合。
2. `FullLinuxContract.CapabilityMatrix.from(SessionCapabilities)` 成为唯一投影。新增 `Capability.GRAPHICS_RFB`、`Capability.SHARED_DIRECTORY`，并提供兼容别名 `Capability.GRAPHICS_DISPLAY = GRAPHICS_RFB`；`GRAPHICS_WAYLAND` 只允许真实 Wayland/DLFR adapter 声明。`graphicsBadge()` 对 RFB 输出 `RFB 显示（本地套接字）` / `RFB 显示（vsock CPU 拷贝）`。
3. AVF：`AvfInstanceLauncher.AvfSession` 在构造点生成描述符，`capabilityMatrix()` 只做投影；无 display 时不声明图形。`AvfVirtualMachineEngine.capabilityMatrix()` 改为无 session 的上限（terminal/bridge/HA/RFB），不再声称 audio/clipboard/pointer/GPU。
4. QEMU：`QemuInstanceLauncher.Session` 覆写 `capabilityMatrix()`，按实际构造的 `waylandChannel`（RFB）与 forward 生成；桌面 HUD 不再把 QEMU 显示读成 software baseline。
5. UI：删除 `MainActivity.currentCapabilityMatrix()` 死代码；桌面 HUD 只读 `session.capabilityMatrix()`。
6. `AvfCapabilityEvaluator`：移除硬编码 `BLOCKED_SOC_HINTS`；只有调用方显式提供的 `extraBlockedHints` 才参与拒绝，SoC/设备型号本身不再 disqualify。非 READY 的 readiness 矩阵也改为 descriptor 投影（terminal+bridge）。
7. 1 vCPU 用户可见约束：`AvfLaunchAdmission.cpuSupport(int)` 返回 `SUPPORTED`/`NEEDS_SINGLE_VCPU`；`SetupWizardActivity` 在 AVF READY 且 vCPU != 1 时在硬件步骤显示 `wizard_config_vcpu_avf_notice`。不改变产品默认值，不改变 03 的 admission。

## 测试（red → green）

红（实现前）：

```bash
./gradlew :runtime:contract:check --no-daemon
# 46 个编译错误：SessionCapabilities 包不存在、GRAPHICS_RFB/SHARED_DIRECTORY 不存在
```

绿（实现后实际执行）：

```bash
./gradlew :runtime:contract:check :runtime:capability:check :runtime:engine-avf:check \
  --no-daemon --stacktrace        # BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest --tests '*Capability*' --tests '*Desktop*' \
  --no-daemon                     # BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest --no-daemon   # 854 tests, 0 failed
```

新增/更新的关键测试：

- `SessionCapabilitiesTest`：描述符只记录装配项、hardware/software 互斥、Wayland 与 RFB 是选择关系。
- `CapabilityProjectionTest`：QEMU/AVF 表驱动投影、RFB 不映射 Wayland、9p 仅在接线时出现、forward 不进入矩阵、`GRAPHICS_DISPLAY` 别名。
- `AvfCapabilityTruthTest` / `QemuCapabilityMatrixTest`：launcher 层 `session.capabilityMatrix()` = 实际装配；QEMU HUD 有 RFB 图形。
- `CapabilitySourceOfTruthTest`：`MainActivity` 不再有第二矩阵；HUD 只读 session。
- `AvfCapabilityEvaluatorTest`：SM8750 单独不再被拒绝；显式 hint 仍可拒绝。
- `AvfLaunchAdmissionTest`：1 vCPU SUPPORTED、2/4 vCPU NEEDS_SINGLE_VCPU。
- `AvfCapabilityMatrixAudioTest`：AVF 静态上限无 audio/clipboard/pointer/GPU，badge 诚实。

## 零设备状态

- fake/JVM 通过：能力投影、AVF/QEMU session 矩阵、wizard 1 vCPU 约束、SoC hint 行为。
- not-tested：真机 `getCapabilities` 对照、真实 RFB 帧/输入、HUD 屏幕显示、wizard 实际视觉验收。

## 偏差与风险

- 旧 `*WithGraphics/*WithPeripherals` 工厂保留为 legacy fixture（未标 `@Deprecated`，因为 `-Werror` 会把测试中的 deprecation warning 变成编译失败）；生产已无调用方。
- QEMU 矩阵目前只声明 terminal/bridge/RFB display；QEMU 的 audio/clipboard/pointer 仍由 UI 层直接驱动，未进入 session 声明。这是保守声明，不是能力回退。
- 1 vCPU 的 UI 修复只显示诚实状态，未改变默认 vCPU 计算（产品决策留给用户/后续票）。
- 未改动 03 的 admission 策略、02 的生命周期行为。

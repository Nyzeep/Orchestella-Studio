# 04: 能力真相单一来源

Type: implementation
Status: resolved
Priority: P0
Owner: capability/UI contract owner
Blocked by: None
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

同一能力在 evaluator、engine、session、MainActivity、SoC blocklist 各说一套；engine 声称 graphics/audio/clipboard/pointer，session 只承认 baseline，UI 又声称 graphics。QEMU session 没有覆写 `capabilityMatrix()`，桌面 HUD 反而把 QEMU 显示读成 software baseline；`MainActivity.currentCapabilityMatrix()` 目前没有调用方。用户看到的“支持”与真实接线不一致。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §4；`AvfCapabilityEvaluator.java:129-136`；`AvfVirtualMachineEngine.java:23-25`；`AvfInstanceLauncher.java:59-61`；`MainActivity.kt:744-753`；`DesktopActivity.java:493-495`；`QemuInstanceLauncher.java`。

## What to build / 范围

1. 新增窄 `SessionCapabilities`（或 `CapabilityDeclaration`）描述符：只记录“这次启动实际装配了什么”（terminal、bridge、display、audio、clipboard、pointer、9p、forward、hardware acceleration、owner）。
2. `FullLinuxContract.CapabilityMatrix` 变成描述符的纯投影（`from(SessionCapabilities)`），不再由调用方各选一个静态工厂。
3. 每个 launcher 在装配点生成描述符：
   - `AvfInstanceLauncher.AvfSession` 从实际构造的 display/clipboard/audio/forward/owner 生成；
   - `QemuInstanceLauncher.Session` 覆写 `capabilityMatrix()`，按实际构造的 display/forward 生成；
   - engine 的静态 `capabilityMatrix()` 删除或改为“无 session 时的能力上限”，不得与 session 冲突。
4. UI 只读 `session.capabilityMatrix()` 或启动结果的描述符；删除/修正 `MainActivity.currentCapabilityMatrix()` 的死代码；桌面 HUD 与 dashboard 用同一来源。
5. 移除 `AvfCapabilityEvaluator.BLOCKED_SOC_HINTS` 硬编码黑名单，改为设备事实输入（`AvfHostFacts` 的 SoC hint 可由设备配置提供），或删除并用 `getCapabilities` + 实际 create 的 typed 结果替代。
6. 未接线的能力不得出现：audio/clipboard/pointer 在 06/07 完成前一律 absent；`GRAPHICS_WAYLAND` 只有在真实 Wayland adapter 存在时才出现。
7. **协议命名诚实**：RFB ≠ Wayland。新增 `DISPLAY`/`GRAPHICS_RFB`（或把 `GRAPHICS_WAYLAND` 改名为 `GRAPHICS_DISPLAY` 并保留兼容别名）；QEMU/AVF 的 RFB 路径声明新能力，不得映射成 `GRAPHICS_WAYLAND`。`GRAPHICS_WAYLAND` 只留给实际 Wayland/DLFR 适配器。更新 UI/能力测试和 ADR-0017 的引用说明（不重写 ADR，只记录实现差异）。
8. `HARDWARE_ACCELERATION` 只表示 AVF 硬件虚拟化，不表示 GPU；`GPU_HARDWARE_ACCELERATION` 在 AVF v1 明确 absent。

## 行为验收（零设备）

- [ ] 同一 fixture 下，AVF `session.capabilityMatrix()` = UI 显示 = 启动描述的预计能力；不再出现 engine withPeripherals / session baseline 冲突。
- [ ] QEMU 桌面 session 的 matrix 包含 display（若实际构造了 display），HUD 不再显示“无图形”。
- [ ] `MainActivity.currentCapabilityMatrix()` 被删除或实际使用；没有“定义了但没人调用”的第二真相源。
- [ ] AVF 未接线的 audio/clipboard/pointer/9p/forward 不出现；接线的能力在 06/07 测试中出现。
- [ ] RFB 路径声明 `DISPLAY`/`GRAPHICS_RFB`（或改名后的 display 能力），不再声明 `GRAPHICS_WAYLAND`；QEMU/AVF 一致；旧调用方有兼容映射。
- [ ] `BLOCKED_SOC_HINTS` 不再作为硬编码分支；设备事实缺失时返回 UNKNOWN/UNSUPPORTED，不猜 false。
- [ ] 矩阵构造器仍拒绝“software virtualization 声称 hardware acceleration”等非法组合。
- [ ] 表驱动测试覆盖：QEMU baseline/display/peripherals、AVF baseline/display、未接线能力。

## 测试 seam 与 red 命令

- 新增 `SessionCapabilitiesTest`、`CapabilityProjectionTest`；扩展 `FullLinuxContractTest`、`AvfCapabilityMatrixAudioTest`。
- App 层：`AvfInstanceLauncher` 启动 fake session，断言 matrix；`QemuInstanceLauncher` 用 fake display/forward 断言 matrix；`DesktopActivity` 的矩阵来源可用 JVM/UI fixture 或提取纯函数测试。
- 红命令：
  ```bash
  ./gradlew :runtime:contract:check :runtime:engine-avf:check \
    :runtime:capability:check :app:testDebugUnitTest \
    --tests '*Capability*' --tests '*Desktop*' --no-daemon --stacktrace
  ```
- 先 red：engine 声称 audio/session 不承认；QEMU session 返回 software baseline；MainActivity 死代码。

## 失败、数据与兼容

- 能力缺失是正常状态，不抛异常；UI 显示“不支持/未接线”，不显示“即将支持”。
- 不把 QEMU 的 `virgl`/GPU 能力映射成 AVF 的 GPU；不把 RFB 映射成 Wayland。
- 兼容旧调用方：`CapabilityMatrix` 的公开方法保留；新增描述符不破坏 `ExecutionSession` 默认方法。
- 不记录设备指纹/用户配置原文。

## 迁移、回滚与依赖

- 纯声明/投影重构，无实例数据迁移。
- 回滚：恢复静态矩阵工厂与 UI 调用；描述符可保留为测试工具。
- 依赖：不新增第三方库；与 05 的 `AvfHostFacts` 字段变更协调（04 不改 probe，05 改）。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-04/implementation-report.zh-CN.md`。
- 完成 = 单一来源测试全绿 + QEMU HUD 修复 + 无死代码 + 全量 check + 报告明确“真机未验”。

## 不做

- 不实际接线 display/audio/9p/forward（06/07）；不改 AVF launch/stop（02/03）；不做真机能力对照；不改 QEMU 功能。

Claimed by: Agent B / 2026-10-20T01:34:41Z

## Answer

- 新增 `SessionCapabilities`（executionStrategy / ownership / terminal / bridge / display / audio / clipboard / pointerCapture / sharedDirectory / serviceForward / hardwareAcceleration / gpuAcceleration），构造时拒绝“software 声称 hardware acceleration”和“hardware 不声明 acceleration”。
- `FullLinuxContract.CapabilityMatrix.from(SessionCapabilities)` 成为唯一投影；新增 `GRAPHICS_RFB`、`SHARED_DIRECTORY` 和兼容别名 `GRAPHICS_DISPLAY = GRAPHICS_RFB`。`GRAPHICS_WAYLAND` 只由真实 Wayland adapter 声明。旧的 `*WithGraphics/*WithPeripherals` 工厂保留为 legacy fixture，但注明不得用于 live session。
- AVF `AvfSession` 从实际构造的 display/token 生成描述符；QEMU `Session` 覆写 `capabilityMatrix()`，按实际构造的 display/forward 投影。`AvfVirtualMachineEngine.capabilityMatrix()` 改为“无 session 上限”：terminal/bridge/HA/RFB，无 audio/clipboard/pointer/9p/GPU。
- 删除 `MainActivity.currentCapabilityMatrix()` 及其 import；桌面 HUD 只读 `session.capabilityMatrix()`。
- 删除 `AvfCapabilityEvaluator.BLOCKED_SOC_HINTS`；SoC/设备名只有调用方显式传入 `extraBlockedHints` 才参与拒绝，模型名本身不再 disqualify。
- 1 vCPU：`AvfLaunchAdmission.cpuSupport(int)` 暴露 `SUPPORTED` / `NEEDS_SINGLE_VCPU`；wizard 硬件步骤在 AVF READY 且 vCPU != 1 时显示 `wizard_config_vcpu_avf_notice`，不改变默认值、不改 03 的 admission。
- 红→绿：先加 `SessionCapabilitiesTest`/`CapabilityProjectionTest` 和 app 层 `AvfCapabilityTruthTest`/`QemuCapabilityMatrixTest`/`CapabilitySourceOfTruthTest`，contract 编译以 46 个“符号不存在”错误红；实现后 `:runtime:contract:check`、`:runtime:capability:check`、`:runtime:engine-avf:check`、`:app:testDebugUnitTest` 全绿。
- 未验：没有真机能力对照；RFB/显示/输入仍是 fake/JVM seam。hud 文案只从矩阵派生，未做截图验收。

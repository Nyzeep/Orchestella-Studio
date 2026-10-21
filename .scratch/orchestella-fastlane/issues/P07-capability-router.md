# P07: Fast Lane 能力矩阵与 Lane Router（零设备）

Type: implementation
Status: ready-for-agent（zero-device）
Priority: P0（产品真相）
Owner: capability/router owner
Blocked by: None（零设备）；产品集成由 DV-04 验证
External gates: ACR-03 未决前不对外声明

## 目标

建立 Fast Lane 的唯一能力事实源与选道逻辑：

- 新 Runtime 类别 `Accelerated Linux Workspace`（工作名，待 ACR-03）；
- 能力：terminal、hardwareCpu、encryptedStorage、sealedToolbox、tcpProxy、exportImport、distccOffload；
- 非能力：nativeNetwork、nativePackageInstall、docker/systemd、gpu/audio/usb、display；
- Router：`FULL` / `FAST` / `UNSUPPORTED` + reason；
- UI 只消费矩阵，不写策略分支；不静默切换。

## 零设备范围

- 纯 Java 数据类 + 纯函数 truth table；
- 用 device facts/workload fixtures 驱动；
- 不要求设备。

## 行为验收

- [ ] 能力矩阵单一事实源；UI/session/engine 不各自硬编码。
- [ ] Router truth table：protected-only、no AVF、hidden API blocked、no storage、no proxy、full-linux requirements、unknown。
- [ ] 需要 systemd/Docker/native network/GPU/package install → FULL。
- [ ] Fast Lane 不可用 → FULL + typed reason；不静默。
- [ ] 未知设备/能力 → FULL（保守），不猜。
- [ ] 与 Full Lane 现有能力矩阵兼容；不改变 QEMU 默认。
- [ ] 文案明确“Fast Lane 不是 Full Linux”。

## 联网调研

- 本仓 `FullLinuxContract.CapabilityMatrix`/`SessionCapabilities`；
- Podroid `EngineSelection`/`EngineHolder.decideBackend`（纯函数模式）；
- ACR-03 的裁定问题；
- Android `getCapabilities` 语义（protected/non-protected bits）。

## 交付

- `FastLaneCapabilities`、`FastLaneRouter`、`FastLaneDecision`；
- truth-table tests；
- UI 文案/徽章数据（不含 Android 代码）。

## 停止条件

- ACR-03 拒绝第三类：Router 只保留内部实验，不产品化。
- 能力矩阵与真机不符：以 DV-01/02 结果修正；不把声明当通过。

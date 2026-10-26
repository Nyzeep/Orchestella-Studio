# P07 设计说明：Fast Lane 能力矩阵与 Lane Router（零设备）

## 数据模型

- `FastLaneCapabilities` 是不可变数据类，`v1()` 是唯一 canonical 矩阵：terminal/hardwareCpu/encryptedStorage/sealedToolbox/tcpProxy/exportImport/distccOffload=true；nativeNetwork/nativePackageInstall/docker/systemd/gpu=false。
- `DeviceFacts` 用三态 `Support {YES,NO,UNKNOWN}` 表达 protected VM、AVF、权限、隐藏 API、storage、proxy、toolbox、distcc；另含 `fullLaneAvailable`。未知不猜。
- `Workload` 是 `INTERACTIVE_SHELL/COMPILE/FULL_LINUX/UNKNOWN`；`RequirementProfile` 显式列出 systemd/docker/nativeNetwork/gpu/packageInstall/storage/exportImport/proxy/toolbox/distcc/terminal 需求。

## 纯函数规则（按优先级）

1. 任一 Full-Linux-only 需求（systemd/docker/nativeNetwork/gpu/packageInstall）或 workload=FULL_LINUX → FULL + `REQUIRES_FULL_LINUX`；显式需求优先于未知 workload。
2. workload=UNKNOWN → FULL + `UNKNOWN_WORKLOAD`；任一关键 device fact 为 UNKNOWN → FULL + `UNKNOWN_DEVICE`（保守）。
3. Fast Lane 不可用：no AVF → `NO_AVF`；hidden API → `HIDDEN_API_BLOCKED`；permission denied → `PERMISSION_DENIED`；protected 不支持 → `PROTECTED_UNAVAILABLE`；统一 FULL + typed reason。
4. 能力缺口：需要 storage 或 exportImport 但 encrypted storage 不可用 → FULL + `STORAGE_UNAVAILABLE`；需要 proxy 但不可用 → FULL + `PROXY_UNAVAILABLE`；需要 toolbox/distcc 同理。
5. 全部满足 → FAST + `FAST_LANE_AVAILABLE`。
6. 明确 `fullLaneAvailable=false` 且 Fast 不可用 → UNSUPPORTED + 触发原因（`NO_AVF` / `REQUIRES_FULL_LINUX` / `UNKNOWN_DEVICE` 等），保留诊断信息；其余不可用一律 FULL，不静默切换。

## 决策与文案

- `FastLaneDecision` 强制携带 lane + reason + effective `FastLaneCapabilities`；FAST 不允许空 reason，FULL/UNSUPPORTED 不允许 `FAST_LANE_AVAILABLE`。
- `FastLaneCopy` 提供中文徽章/限制文案，明确“Fast Lane 不是 Full Linux：无 systemd/Docker/原生网络/GPU/包安装”。
- UI 只读 decision/capabilities，不写策略分支；本模块不依赖 Android，不改变 QEMU 默认，也不改 `FullLinuxContract`。

## 测试接缝

- truth table：protected-only→FAST、no AVF→FULL/NO_AVF、hidden API→FULL、permission denied→FULL、no storage→FULL、no proxy→FULL、full-linux requirements→FULL、unknown→FULL、no full lane→UNSUPPORTED。
- 数据类：canonical 矩阵逐项断言；非能力恒 false；decision 不变量；文案包含“不是 Full Linux”。
- `not-tested`：真实设备 capability 读数和产品 UI 接线，留给 DV-01/DV-04。

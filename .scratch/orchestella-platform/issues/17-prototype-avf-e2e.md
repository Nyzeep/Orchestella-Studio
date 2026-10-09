# 原型：AVF 端到端验证（真机）

Labels: wayfinder:prototype
Type: prototype
Status: open
Blocked by: 04, 05

## Question

在真实 AVF 设备（Pixel 等）上，无 Root + adb 授权路径能否端到端走通：custom kernel+rootfs 的 custom VM 启动、vsock 通道、网络、（顺带）GPU 配置可用性、App 更新后 VM instance 行为？

## Why it matters

这是 L2 路线（near-native 卖点）的可行性总闸门。其中「App 更新 → VM instance/secret 行为」直接影响分发架构；「custom VM 网络/GPU」决定网络模型与图形路线的真实选项空间。全部无法靠文档回答。

## Known facts

- custom VM 路线被 Podroid 源码证实存在（反射 `VirtualMachineCustomImageConfig`），但其跨设备可靠性、非 Pixel 设备行为未知（01 票）。
- AVF instance secret 派生自载荷/内核代码——App 更新是否作废既有 instance 需实测（round-0 报告 §四.7）。

## Prototype plan sketch

adb 授权 → 最小 custom VM（initrd busybox）→ 逐步换真实 kernel + Alpine rootfs → vsock shell → 网络连通 → GPU 配置探测 → 记录 capability 矩阵与坑。产出：docs/research/avf-e2e-prototype.md + capability 数据。

## Reversibility

纯实验，无不可逆成本。需要用户提供一台 AVF 设备与 ADB 环境（HITL 协作）。

## Placement（to-tickets 裁定）

- **阻塞**：票 34（AVF 执行策略）——其全部验收以本票数据为前提。
- **验证的假设**：custom VM 可启动；第三方 VM 网络实际可用；GPU 探测结果；app 更新后 instance 身份行为。
- **重开触发器**：
  - 网络不可用 → 重开 ADR-0008 的 AVF 档范围（决策票重开，实现不推进）；
  - instance 身份随 app/内核更新失效 → OTA 雾区毕业为决策票 + 评估修订 ADR-0005 分发策略；
  - GPU 不可用 → 仅影响 M4 里程碑（spec 后续版本），不阻塞 MVP；
  - 反射路径在某 OEM 失效 → 能力矩阵如实降级 + 授权向导回退路径强化。
- **性质**：prototype，不是 production implementation；其结论只进入能力矩阵与决策重开流程，不直接产生产品代码（实验代码不进主干）。

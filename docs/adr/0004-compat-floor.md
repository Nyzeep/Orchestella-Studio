---
status: accepted
date: 2026-09-05
---

# 兼容性地板：minSdk 26 / arm64-only / RAM ≥ 3GB，诚实拒绝

支持地板四条：

1. **L1（QEMU 档）minSdk 26（Android 8.0）**——对齐 Podroid 真实用户验证过的覆盖面，不重新赌。
2. **L2（AVF 档）不设独立版本门槛，纯运行时检测**：API 34+ 且声明 `android.software.virtualization_framework` 且权限可授且 custom VM 能力谱通过（与 ADR-0001 的能力谱原则一致）。
3. **架构仅 arm64-v8a**；x86_64 guest 列为非目标（跨架构 TCG 性能不可接受，发行版与测试矩阵翻倍）。
4. **RAM ≥ 3GB 且存储余量达标**才可创建 Full Linux 实例（创建时实测）。

低于地板的设备**诚实拒绝并解释原因**，不提供静默降级的运行路径；未来 Userspace Compatibility Runtime 是低配设备的显式出口（ADR-0001 已留位）。

## Considered Options

- **minSdk 29+ 收窄历史包袱（否决）**：Android 8–9 覆盖的边际成本已被 Podroid 证明可控，收窄收益小、不可逆伤害大。
- **低配设备降级运行（否决）**：2GB 设备跑 TCG + guest kernel 必然频繁 OOM；假包容转化为最伤口碑的"闪退"差评。诚实拒绝 + 预告兼容模式才是预期管理。

## Consequences

- minSdk 提高容易、降低极难——26 留有收紧余地，不把放开权交给未来。
- RAM/存储地板是软件门槛，可随「QEMU TCG 实机基线」原型数据微调（必要时做实验性放行开关）。
- 发行版仓库、性能基准、真机测试矩阵均为 arm64 单维。
- x86_64 未来若做 = 新增 rootfs 仓库 + 新 TCG 目标，属加法，不推翻任何契约。
- 模拟器（x86_64 host）在检测时给出清晰提示，避免假 bug 报告淹没真机问题。

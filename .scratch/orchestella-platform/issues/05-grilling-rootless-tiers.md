# 无 Root 三级承诺与 MVP 出厂形态

Labels: wayfinder:grilling
Type: grilling
Status: resolved

## Question

「无 Root」对用户的三级承诺（L1 安装即用 / L2 一次 ADB/Shizuku 授权 / L3 OEM 级）中，MVP 出厂支持哪几级？L2 的授权引导体验是否是 MVP 必需？

## Why it matters

L1 只能走 QEMU（5–20× 慢）；L2 才能解锁 AVF near-native，但要求用户开 USB 调试并执行一次授权（或用 Shizuku）。这决定 MVP 的性能故事、目标用户群（极客 vs 大众）与授权引导 UI。

## Known facts

- AVF 权限为 development 级，adb pm grant 可授（AOSP 源码 + Podroid/koiTerminal 实践核实）。
- Play 正式上架的第三方 App 拿不到 AVF 权限（该授权仅 "development purposes"）。
- 前置讨论已提出三级框架，但未定 MVP 出厂形态。

## Working recommendation

MVP 同时交付 L1（QEMU，全设备兜底）与 L2（AVF，带应用内授权向导：Shizuku 优先、ADB 命令兜底）；L3 仅作为路线图愿景，不做承诺。

## Reversibility

先只做 L1、后补 L2 会导致 AVF 契约后补；建议 MVP 就把两级纳入能力检测框架，实现可分期。

## Answer

2026-09-05 用户采纳。固化于 **docs/adr/0003-rootless-tiers.md** 与 CONTEXT.md 词条（无 Root 等级 / 授权向导）。

决定要点：
1. MVP 出厂 **L1 + L2**：L1=安装即用（软件虚拟化，全 arm64 兜底）；L2=一次开发级授权（AVF 硬件虚拟化）。
2. L2 授权向导：先检测（feature flag + 权限状态 + custom VM 能力谱）、后引导；Shizuku 优先、ADB 命令兜底；检测不通过的设备不展示向导。
3. L3（OEM/系统级）仅路线图愿景，产品内不承诺。
4. 目标用户 = 技术早期采用者；MVP 不按大众产品增长叙事规划。
5. 设置页常驻"提升到硬件加速"入口管理 L1→L2 升级路径。

未决事实：用户是否持有 AVF 设备未声明——领取原型票「AVF 端到端验证（真机）」时需先确认（该票随本票解决而解锁）。

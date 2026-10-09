# 原型：QEMU TCG 实机基线

Labels: wayfinder:prototype
Type: prototype
Status: open
Blocked by: 03, 07

## Question

QEMU/TCG 在中端真机上的真实基线：Alpine/Debian 启动时间、shell 响应、编译/包管理吞吐、内存占用；jniLib 打包 + 16KB 页对齐在本项目构建链中的可行性验证。

## Why it matters

L1（全设备兜底）路线的产品体验上限与打包方案都需要数据；TCG 5–20× 慢是文献值，需要自己目标设备上的分布。

## Known facts

- Podroid QEMU 11.0.2 + jniLib 方案已被源码证实可行（01 票）。
- 文献基线：aarch64 TCG 约慢 8–12×（Linaro），社区共识 5–20×。

## Prototype plan sketch

复用/裁剪 Podroid 式构建链（仅作实验，不把 GPL 代码带进产品）→ Alpine rootfs 启动 → unixbench/编译微基准 → 记录 docs/research/qemu-baseline.md。

## Reversibility

纯实验。若使用 Podroid 构建产物，注意 GPL 义务仅限实验、不进产品分发。

## Placement（to-tickets 裁定）

- **阻塞**：票 35（硬化——性能验收数字回填）；间接影响票 26 的启动耗时预算标注。
- **验证的假设**：私有目录（FUSE）随机写性能（ADR-0007 唯一可能被推翻的点）；TCG 实测数字（启动时间/shell 响应/编译吞吐）；内存占用曲线。
- **重开触发器**：
  - FUSE 随机写性能不可接受 → **重开 ADR-0007（存储选型）**，spec 存储章节相应修订；
  - TCG 性能远差于文献基线 → 性能承诺章节与徽章文案修订（不重开 ADR，属参数调整）；
  - 内存占用使 RAM≥3GB 地板失真 → 修订 ADR-0004 地板参数。
- **性质**：prototype；实验构建可复用票 25 的资产，不进产品分发。

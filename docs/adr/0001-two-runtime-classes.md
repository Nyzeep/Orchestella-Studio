---
status: accepted
date: 2026-09-05
---

# 两层 Runtime 类别：VM-first 的 Full Linux Runtime 与独立的 Userspace Compatibility Runtime

DroidLayer 的产品架构确定为两类并列的 Runtime 类别：**Full Linux Runtime**（产品主体，VM-first——AVF/pKVM 硬件虚拟化优先、QEMU/TCG 软件虚拟化兜底；二者是同一类别下的**执行策略**，共享同一 guest kernel 契约、rootfs 模型、桥接协议与产品承诺）与 **Userspace Compatibility Runtime**（PRoot 及未来用户态方案：独立的能力等级与契约，**不是** VmEngine 的第三个 backend）。Compatibility Runtime 定义为正式产品等级但 MVP 不交付；交付决策推迟到有实测数据与真实需求时。

## Considered Options

- **PRoot 作为 VmEngine 第三 backend（否决）**：PRoot 不满足 Full Linux 抽象的任何不变量（无 guest kernel、无块设备存储、无虚拟通道、无真实 namespace/cgroup），塞进同一接口会让每个方法带上"PRoot 下不支持"分支。正确共享点是更低层的 distro/rootfs 管理与终端 UX。
- **同一"Ubuntu 实例"跨类别静默降级（否决）**：VM 实例是磁盘镜像、兼容实例是目录树，数据模型不可互换；静默降级既构成虚假宣传又制造数据迁移陷阱。实例创建时定死类别；UI 按"**类别诚实、后端隐形**"呈现——徽章区分「完整 Linux（硬件加速 / 软件模拟·较慢）」与「兼容模式（受限：无 Docker/systemd/真实 root）」，不出现 AVF/QEMU/PRoot 技术名词；兼容模式必须用户主动选择。
- **把 PRoot 彻底踢出架构（否决）**：保留等级定义成本近乎为零，而它有真实价值场景（低内存设备、CPU 密集负载、未来非 ARM64 主机）。定义为等级使未来交付只是加工作量，不是改架构。

## Consequences

- 实例元数据从第一天携带 runtime 类别字段。
- 下游决策票（存储/网络/生命周期/桥接）必须显式区分"平台级契约"与"Full Linux 专属契约"。
- "**VM-first ≠ 全设备硬件加速**"必须写入产品文案：多数 2026 设备的默认体验是软件虚拟化（AVF 实际覆盖率待研究票数据），性能承诺必须分层且以 benchmark 为准。
- 已毕业研究票：「PRoot on modern Android 现状与性能特征」（为未来交付决策供数）。

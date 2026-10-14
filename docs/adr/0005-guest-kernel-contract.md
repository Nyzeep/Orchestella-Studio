---
status: accepted
date: 2026-09-05
---

# Guest Kernel 契约：单一 pinned 版本、清单进构建校验、CI 复现构建、MVP 打包分发

Guest kernel 是 Full Linux Runtime 与发行版之间的契约载体，两个执行策略（AVF/QEMU）**共用同一个 kernel**（实例互换的前提）。四条约定：

1. **版本策略**：单一 pinned 版本（当前 stable mainline），升级是显式决策 + 显式迁移流程，不自动跟随上游。
2. **必选能力清单 = 构建校验**：config 以 arm64 精简基线 + 必选清单（virtio 全家、vsock、文件共享所需文件系统、squashfs/ext4/overlayfs/FUSE、cgroup v2 全套、全部 namespaces、seccomp、netfilter/nftables、tun/veth/bridge；USB 栈视 USB 范围票结论增补）。**清单进构建校验，缺项即构建失败**。
3. **构建**：项目自有 CI 可复现构建（固定工具链容器 + 全输入 SHA256 manifest）；禁止维护者本机构建发布。
4. **分发**：MVP 打包进 App（APK 资产）+ 解压时 SHA256 自检，离线优先、供应链锚定 APK 签名；"kernel 与 App 解耦下载"作为 v2 选项，**等 AVF 原型对 instance 身份/密钥敏感度的实测数据回来后再决定**。

## Considered Options

- **复用 Podroid 预编译内核（否决）**：继承其零校验供应链、版本不受我们控制，且直接复制 GPL 代码（许可证风险，见许可证策略票）。
- **MVP 即下载解耦（推迟而非否决）**：最大动机（kernel 升级独立于 App 发版、避免 AVF 实例身份搅动）依赖未实测的 AVF 约束；数据没到不预付复杂度。打包是解耦的安全超集，迁移路径渐进。

## Consequences

- App 体积增加约 30–40MB，如实标注。
- kernel 变更 = 显式迁移流程；默认长周期 pin，升级频率刻意低。
- 能力清单受构建测试保护，增补是显式变更；清单随后续票（文件共享机制、USB 范围）微调。
- initramfs（含首次引导 provisioning 逻辑）属于 kernel 交付物的一部分。
- 若 AVF 原型证实 instance 身份对 kernel 敏感，AVF 档的 kernel 升级需额外谨慎（可能长于 QEMU 档锁定）。

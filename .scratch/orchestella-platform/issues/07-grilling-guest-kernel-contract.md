# Guest Kernel 契约：版本、config 清单、构建与分发

Labels: wayfinder:grilling
Type: grilling
Status: resolved
Blocked by: 04

## Question

我们的 guest kernel 契约是什么：版本策略、必选 config 集（virtio/vsock/9p/overlay/cgroup v2/namespaces/netfilter…）、由谁构建（本项目 CI vs 复用上游预编译）？kernel 与 App 是否解耦分发（下载 vs 打包）？

## Why it matters

Guest kernel 是"我们的产品与发行版之间的契约"：它决定 Docker/Podman/systemd 能力、启动时间、镜像体积，且 AVF custom VM 的 identity 与内核代码绑定（换内核影响 VM secret）。这是产品级决策而非实现细节。

## Known facts

- Podroid：Linux 7.1.5 打包为 APK asset，defconfig+fragment，Docker 构建、无 CI、无校验和（教训清单见 01 票）。
- kernel config 需覆盖：virtio 全家、vsock、9p、squashfs/overlayfs/ext4、cgroup v2、namespaces、seccomp、netfilter、tun/veth/bridge、（可选）USB 栈。

## Working recommendation

单一 pinned 版本 + config fragment 管理 + CI 可复现构建 + SHA256 清单；kernel 独立于 App 版本演进（下载+校验），不与 App 升级强绑。

## Reversibility

config 集可增量扩；"kernel 与 App 解耦分发"一旦定下、逆向绑定的迁移成本中等——先定分发策略。

## Answer

2026-09-05 用户采纳。固化于 **docs/adr/0005-guest-kernel-contract.md** 与 CONTEXT.md 词条（Guest Kernel 契约）。

决定要点：
1. 单一 pinned stable 版本，两执行策略共用；升级 = 显式决策 + 显式迁移。
2. 必选能力清单进构建校验（缺项即失败）；清单随后续票（文件共享、USB）微调。
3. 自有 CI 可复现构建（固定工具链容器 + 全输入 SHA256）；禁止本机构建发布。
4. MVP kernel 打包进 App + 解压 SHA256 自检（离线优先）；"解耦下载"为 v2 选项，等 AVF 原型 instance 身份实测数据再定。
5. initramfs 属 kernel 交付物一部分（含首次引导 provisioning）。
6. 明确否决复用 Podroid 预编译内核（零校验 + GPL 复制义务）。

未决依赖：AVF instance 身份对 kernel 变更的敏感度 → 原型票「AVF 端到端验证」验证清单。

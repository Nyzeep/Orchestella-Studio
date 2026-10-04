---
status: accepted
date: 2026-09-05
---

# 发行版模型：官方 tarball + 首次引导 Provisioning，OCI 否决出 MVP

发行版契约 = **DistroManifest**（元数据：id / 版本 / 架构 / 来源 URL / SHA256 / 大小 / init 期望 / 按 runtime 类别的能力说明 / 风味）。rootfs **输入形态为发行版官方 rootfs tarball**，不自建发行版镜像、不托管自有 squashfs；VM 实例的块镜像在**首次引导时由 initramfs 在 guest 内完成 provisioning**（格式化数据盘 + 解包 tarball）——无 Root 环境下镜像构建的唯一可行位置（App 自身不能 mount）。校验：官方 URL + manifest 内置 SHA256 强制校验；GPG 签名验证列为增强。首批发行版：**Alpine + Debian**（覆盖 OpenRC/systemd 两端）。**OCI 否决出 MVP**：容器镜像与发行版系统镜像引导语义不同（base image 不带 init/systemd 配置），Manifest 预留 `variant` 字段供未来 distro-as-OCI 增强立场。

## Considered Options

- **自建预构建镜像（Podroid 模式，否决）**：下载即用，但为每个发行版每个版本永久维护构建管线与托管——结构性负债。
- **OCI 进 MVP（否决）**：修正前置讨论的"OCI 优先"建议——OCI 是容器打包格式，base image 不保证可作完整用户空间引导；MVP 引入 registry 协议复杂度换不来产品承诺。留 variant 扩展位。
- **GPG 签名验证进 MVP（推迟）**：各发行版签名机制不一；MVP 以锚定在版本化 manifest 的 SHA256 为准，残余风险（manifest 托管渠道被篡改）记录在案。

## Consequences

- 新增发行版 = 一条 manifest 数据 + 必要的风味适配（目标：接近零工程）。
- 首次创建 = 下载 tarball（几十~百 MB）+ 首次引导 provisioning（分钟级）；断点续传为增强。
- initramfs 的 provisioning 逻辑是必须实现且按风味测试的组件。
- 实例根盘由用户在 guest 内自行升级（apt/apk），App 升级不触碰实例内容——与 Podroid 的 squashfs 系统层模式是两种语义，不沿用。
- manifest 托管渠道的安全成为供应链一环，纳入风险清单。

## Amendments

### Amendment 1: Debian 13 (Trixie) Manifest & systemd Init Flavor (Ticket 32)

1. **第二官方发行版清单（OfficialDebianCatalog）**：
   - 增加 Debian 13 (Trixie) aarch64 minimal rootfs 官方清单（`rootfs.tar.xz`，90,371,792 字节，固定 SHA-256：`7677f708be8b4df232ebdd177147b1b77beef7c784ce0fa3fbef5dd3e85ab461`）；
   - 官方源与镜像站契约保障，支持与 Alpine 3.24.1 实例在宿主实例存储内无缝并存。
2. **装配风味（DistroFlavor）**：
   - 抽象 `DistroFlavor` 模型以消除发行版之间的底层装配与 init 差异；
   - **Init 系统适配**：Debian 采用 `SYSTEMD` 作为 PID 1（`/lib/systemd/systemd`），配置 systemd service unit (`droidlayer-guest-agent.service`) 挂载于 `multi-user.target.wants`，与 Alpine (`OPENRC` / `BUSYBOX`) 的 `/etc/inittab` / `/etc/init.d/` 隔离适配；
   - **包管理器与网络 DNS 穿透**：适配 Debian `apt`，预置 `/etc/resolv.conf`（`nameserver 10.0.2.3` 对应 QEMU SLIRP DNS 代理，`nameserver 8.8.8.8` 兜底），保证首启后 `apt update` 与软件包安装开箱即用；
   - **解压格式支持**：initramfs provisioning 支持 `.tar.xz` (`tar -xJpf`) 与 `.tar.gz` (`tar -xzpf`)。
3. **架构零侵入性核准**：
   - 运行时引擎 `runtime:engine-qemu` 与生命周期状态机 `runtime:instance` 保持零行修改，严格兑现“新增发行版 = 清单数据 + 风味适配”架构承诺。

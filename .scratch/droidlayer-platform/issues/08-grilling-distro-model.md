# 发行版模型：rootfs 形态、OCI 立场、校验与首批清单

Labels: wayfinder:grilling
Type: grilling
Status: open
Blocked by: 07

## Question

发行版如何建模：kernel/rootfs 解耦的产品契约是什么？rootfs 形态用 tarball / squashfs / OCI layer？来源与校验/签名要求？首批支持哪些发行版？

## Why it matters

决定"新增一个发行版"的成本结构与供应链安全；决定能否直接用官方 rootfs 而不自建镜像；也决定用户可见的产品面（发行版目录）。

## Known facts

- Podroid：单 Alpine，squashfs(zstd)+ext4 overlay，自建镜像管线，无校验（01 票）。
- Alpine/Debian/Ubuntu/Arch/Fedora 均发布官方 aarch64 rootfs tarball / OCI 镜像（公共事实；实现期需逐一核对 URL 与签名机制）。

## Working recommendation

定义 DistroManifest 契约（id/版本/架构/URL/校验/大小/init 期望）；MVP 支持 Alpine + Debian（体积/速度/稳定性组合）；rootfs 用官方 tarball + SHA256 校验；OCI 支持列为后续增强而非 MVP。

## Reversibility

契约先行则格式演进可控；MVP 直接做 OCI 运行时则复杂度显著上升。

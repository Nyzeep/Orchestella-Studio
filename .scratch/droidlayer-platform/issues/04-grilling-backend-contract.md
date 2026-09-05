# 后端契约：VmEngine 是否为产品级 seam，中性/专有能力如何切分

Labels: wayfinder:grilling
Type: grilling
Status: open
Blocked by: 03

## Question

我们是否把「VM 后端」抽象为稳定的产品级能力边界（上层不感知 AVF/QEMU 差异）？哪些能力必须 backend-neutral、哪些允许作为 backend-specific 可选增强下沉？

## Why it matters

决定整个代码架构的地基与测试 seam；也决定能力差异（网络机制、文件共享、USB）如何向上呈现。Podroid 自认"后端非对称是第一大 bug 来源"——契约设计直接决定我们是否重蹈。

## Known facts

- Podroid VmEngine 接口（state/bootStage/console/portForward/terminalSession…）+ EngineHolder 路由已被源码证实可用；其把 `qmpClient`（QEMU 专属）放进接口是反面教材。
- 两后端机制差异真实存在：网络（SLIRP vs TAP+DHCP）、文件共享（virtio-9p vs 手写 9p-over-vsock）、USB（fd 直通 vs 无）。

## Working recommendation

VmEngine 为核心 seam 但接口里零后端专属类型；能力差异用 capability 声明 + 可选增强接口表达；后端专属细节下沉到 backend 模块内部。

## Reversibility

接口早期收紧、后期扩宽成本低；反之一旦上层依赖后端专属 API，抽出成本高。

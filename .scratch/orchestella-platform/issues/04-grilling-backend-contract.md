# 后端契约：VmEngine 是否为产品级 seam，中性/专有能力如何切分

Labels: wayfinder:grilling
Type: grilling
Status: resolved
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

## Comments

- 2026-09-05：前置决策 **ADR-0001** 已定——两层 Runtime 类别，Full Linux 内 AVF/QEMU 为"执行策略"。本票范围相应收窄：要定义的是 **Full Linux Runtime 的策略中性契约**（上层/UX/发行版/桥接不感知 AVF/QEMU 差异；能力差异用能力声明 + 可选增强表达）。PRoot 不在本票范围——它属于 Compatibility Runtime，未来单独定契约。本票已解锁。

## Answer

2026-09-05 用户全盘采纳。固化于 **docs/adr/0002-full-linux-contract.md** 与 CONTEXT.md 词条（Full Linux 契约 / 能力矩阵 / 策略专属增强）。

决定要点：
1. 三层划分：**平台层**（Runtime 类别无关）/** Full Linux 契约**（策略中性，契约内零策略专属类型）/** 策略专属增强**（声明制，永不进契约）。
2. 三条纪律：能力差异只许以能力矩阵数据表达（上层禁止策略分支）；契约测试套件从第一天存在并对所有策略运行；契约 v0 只固化已验证部分（"待原型"条目允许迭代）。
3. 契约覆盖面 v0：实例生命周期、控制台/终端通道、端口转发语义、共享目录语义、桥接协议、磁盘镜像存储模型。
4. 典型增强示例（声明制）：USB 直通（当前仅 QEMU）、GPU 配置（AVF，未验证）、QMP 控制（QEMU 内部）、AVF 反射细节。
5. 解锁：Guest Kernel 契约、网络模型、宿主↔Guest 桥接、生命周期、USB/音频范围五票（均须按"平台级 vs Full Linux 专属"区分契约归属）。

# Wayfinder Map: DroidLayer Linux Runtime Platform 决策地图

Labels: wayfinder:map
Effort: droidlayer-platform

## Destination

产出一套已与用户对齐、可进入 `/to-spec` 的产品与架构决策集：DroidLayer（无 Root Android Linux Runtime Platform）的 MVP 边界、执行模型与后端策略、Guest 契约（kernel/发行版/存储）、宿主↔Guest 集成、安全与生命周期边界、许可证立场——每项关键决策有 ADR 支撑，spec 能直接拆垂直切片工单。

## Notes

- **领域**：Android/Linux 系统工程。产品 = 无 Root 的 Linux Runtime Platform（VM-first、能力驱动后端选择、guest 内容器支持）。
- **Spec 已发布**：[.scratch/droidlayer-platform/spec.md](spec.md)（ready-for-agent，v1.0，依据 ADR-0001~0016 + 六份研究；原型回填条目已标注）。
- **实现工单已发布（needs-triage）**：issues/25–38，共 13 张（1 task + 11 implementation + 1 hardening）+ 2 张原型票（17/18）构成依赖图；发布后待用户审核放行，暂不实施。
- **用户背景**：领域新手。所有 grilling 必须用通俗语言解释术语（kernel / rootfs / container / namespace / pKVM / AVF / vsock / 9p …），并按固定六段格式提问：①真正要决定的问题 ②为什么重要 ③推荐答案 ④优点 ⑤代价/风险 ⑥可逆性。
- **工作节奏**：每次 grilling 只问一个问题，等用户回答，写 ADR/CONTEXT.md，再问下一个（`/grill-with-docs` 的节奏）。不把实现细节伪装成需求决策。
- **Skills**：grilling 票 → 调用 `grilling` + `domain-modeling`；research 票 → 调用 `research`；prototype 票 → 调用 `prototype`。
- **事实基线**：`docs/research/`（AVF 权限与设备矩阵、TCG 性能、W^X/生命周期、Podroid 源码分析）。`探讨文档1.md` 是历史输入，不是已批准需求。
- **Standing preferences**：决策先于实现；性能承诺必须可测量（禁用"0% 损耗"）；GPU/USB/桌面不过早进入 MVP；backend abstraction 与 kernel/rootfs 解耦是既定设计倾向（仍需正式确认）；许可证上"借鉴架构 ≠ 复用代码"。
- **并发提示**：本机 agent 并发额度为 1，research 票逐个领取执行，不要并行开多个子代理。

## Decisions so far

- [Podroid 源码深度分析](issues/01-research-podroid-source.md): VmEngine+双后端+运行时路由属实且值得借鉴；squashfs+ext4-overlay rootfs、vsock 通道、QEMU-as-jniLib 规避 W^X 均核实；警示：GPL-2.0、无 CI、零校验和、guest root 密码公开。
- [AVF 第三方边界（文档级）](issues/02-research-avf-third-party-boundary.md): AVF API 全 @SystemApi，公开 API 仅 Microdroid 载荷；custom kernel 需 USE_CUSTOM_VIRTUAL_MACHINE（development 级，adb 可授）；TCG 慢 5–20×；设备矩阵碎片化；Android 16 官方 Terminal=Debian VM 先例。
- [执行模型：两层 Runtime 类别](issues/03-grilling-execution-model.md): Full Linux Runtime（VM-first；AVF/QEMU 为执行策略、共享同一契约）+ Userspace Compatibility Runtime（PRoot，定义等级、MVP 不交付）；UI 类别诚实、后端隐形，禁止静默降级。见 ADR-0001。
- [Full Linux 契约：三层划分与三条纪律](issues/04-grilling-backend-contract.md): 平台层 / 策略中性契约 / 策略专属增强；能力差异只许用能力矩阵数据表达；契约测试套件对所有策略运行；契约 v0 只固化已验证部分。见 ADR-0002。
- [无 Root 三级承诺](issues/05-grilling-rootless-tiers.md): MVP 出厂 L1+L2（安装即用 + 一次授权解锁硬件虚拟化）；授权向导先检测后引导；L3 仅愿景；目标用户=技术早期采用者。见 ADR-0003。
- [兼容性地板](issues/06-grilling-compat-floor.md): minSdk 26 / arm64-only / RAM≥3GB；L2 纯运行时检测无版本门槛；低于地板诚实拒绝、不留降级假象。见 ADR-0004。
- [Guest Kernel 契约](issues/07-grilling-guest-kernel-contract.md): 单一 pinned 版本两策略共用；能力清单进构建校验；CI 复现构建全输入校验；MVP 打包分发 + SHA256 自检，解耦下载等 AVF 原型数据。见 ADR-0005。
- [发行版模型](issues/08-grilling-distro-model.md): DistroManifest 元数据契约；官方 tarball + 首次引导 provisioning；SHA256 强校验；首批 Alpine+Debian；OCI 否决出 MVP 留 variant 位。见 ADR-0006。
- [存储模型](issues/09-grilling-storage-model.md): raw 稀疏单根盘（两策略同格式）、guest 内自升级；扩容进 MVP、快照不进；SAF 导出备份进 MVP；全私有 + 自动备份排除。FUSE 性能待原型实测。见 ADR-0007。
- [网络模型](issues/10-grilling-networking.md): 默认出站 NAT + 显式端口转发（默认绑 127.0.0.1）；两策略机制各异语义一致；IPv6/VPN/host mode 为不承诺的非目标。见 ADR-0008。
- [宿主↔Guest 桥接协议](issues/11-grilling-host-guest-bridge.md): 传输无关版本化协议 + per-VM token；MVP 六条命令；控制/数据面分离（只读共享 Downloads）；N-1 兼容窗口。见 ADR-0009。
- [安全边界](issues/12-grilling-security-boundary.md): 隔离承诺三级分述（硬件级/进程级/无实质隔离）；guest root 仅触达白名单；暴露面白名单制初始三项；每实例随机凭据；承诺只加码。见 ADR-0010。
- [生命周期](issues/13-grilling-lifecycle.md): 前台可靠、后台尽力而为；进程死亡=VM 停止但实例持久化+冷恢复；电池两档；热恢复进雾区。见 ADR-0011。
- [图形路线分期](issues/14-grilling-graphics-phasing.md): MVP 终端 only；M2=VNC+输入/IME、M3=Wayland 评估、M4=GPU（gfxstream 原型前置）；MVP 预留显示通道位。见 ADR-0012。
- [USB/音频范围](issues/15-grilling-usb-audio-scope.md): MVP 均不做（非目标）；USB 挂 M2+ QEMU 档先行；音频挂 M2 仅输出；文案用路线图语言。见 ADR-0013。
- [许可证策略](issues/16-grilling-licensing.md): Apache-2.0 + 零代码复制立场；GPL 义务 CI 清单流程化；分发边界三分互不传染。见 ADR-0014。
- [发布渠道定位](issues/23-grilling-release-channels.md): GitHub 首发、F-Droid 随后、Play 观望；签名密钥一次做对；更新检查可关闭无遥测。见 ADR-0015。
- [多实例模型](issues/24-grilling-multi-instance.md): MVP 单运行实例；Distro ≠ Image ≠ Instance ≠ Session 实体模型入册；并发为后续增强。见 ADR-0016。**全部 grilling 票关闭。**

## Not yet specified

- **Compatibility Runtime 契约设计（若未来交付 PRoot）**：与 Full Linux 契约平行的轻量契约——实例=目录树、无 vsock/virtio、终端通道语义、共享面复用白名单。触发条件：用户决定交付 Compatibility Runtime（研究票「PRoot on modern Android」已供数：docs/research/2026-09-05-proot-modern-android.md）。
- 发行版镜像与 guest 系统的更新/OTA 通道；与 AVF instance identity（VM secret 随代码变化）的交互（等原型「AVF 端到端验证」结果）
- 性能 benchmark 套件与各档验收数字（等原型「QEMU TCG 实机基线」产出数据）
- UI/UX 产品形态细化：发行版目录、实例管理、终端集成的具体交互（spec 后由 prototype 票承接）
- AVF 档独立存活 VM、热恢复、passt 网络增强等能力（等对应原型数据后按能力矩阵声明）

## Out of scope

- 非 Android 平台（iOS、桌面 OS 移植）——产品定位即 Android App。

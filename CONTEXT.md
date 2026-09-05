# DroidLayer

无 Root 的 Android Linux Runtime Platform：在不要求 Root 的 Android 手机上运行完整 ARM64 Linux 用户空间的单一产品。

## Language

### Runtime

**Runtime 类别 (Runtime Class)**:
产品向用户承诺能力的最高层分类。现有两类：Full Linux Runtime 与 Userspace Compatibility Runtime；实例在创建时归属唯一类别，之后不变。
_Avoid_: backend（那是执行策略层面的词，不是类别）、mode、level

**Full Linux Runtime**:
Guest 拥有自己的 Linux kernel 的 Runtime 类别，产品主体。真实 namespace/cgroup、systemd、Docker/Podman、真实 root 等承诺仅在此类别成立。
_Avoid_: VM 模式（VM 只是它的实现载体之一）、真 Linux

**执行策略 (Execution Strategy)**:
Full Linux Runtime 内部执行 guest 指令的方式。对上层与用户不可感知，唯一例外是性能徽章；同一 Full Linux 实例应可在策略间互换。
_Avoid_: backend、engine（实现层词汇，禁入产品语言）

**硬件虚拟化 (Hardware Virtualization)**:
由 CPU 硬件直接执行 guest 指令的执行策略（AVF/pKVM）。
_Avoid_: 向用户直呼 KVM/pKVM

**软件虚拟化 (Software Virtualization)**:
由软件翻译执行 guest 指令的执行策略（QEMU/TCG）。性能特征为 CPU 均匀减速，对用户表述必须以实测数据为准。
_Avoid_: "模拟器"混称；向用户表述仅限性能徽章用语

**Userspace Compatibility Runtime**:
不提供 guest kernel、以用户态手段（当前候选：PRoot）提供受限 Linux 环境的 Runtime 类别。能力承诺显著低于 Full Linux（无 Docker/systemd/真实 root），隔离较弱；它是与 Full Linux 并列的独立能力等级，不是 Full Linux 的降级形态。
_Avoid_: PRoot Linux、轻量模式、第三 backend

### Contract

**Full Linux 契约 (Full Linux Contract)**:
Full Linux Runtime 内所有执行策略必须共同满足的策略中性产品契约，覆盖实例生命周期、控制台通道、端口转发、共享目录、桥接协议与磁盘镜像存储。契约内不允许出现执行策略专属概念。
_Avoid_: VmEngine 接口、backend API（实现层词汇，禁入产品语言）

**能力矩阵 (Capability Matrix)**:
每个执行策略以数据形式声明"我支持什么"的唯一事实来源；UI 徽章与能力差异提示只消费它，不写策略分支。
_Avoid_: feature flag（能力声明不是功能开关）

**策略专属增强 (Strategy-specific Enhancement)**:
仅部分执行策略支持的能力。只能通过能力矩阵声明存在，永不升级进契约，除非所有策略都能满足它。
_Avoid_: 特殊分支、hack、临时绕过

### Rootless tiers

**无 Root 等级 (Rootless Tier)**:
产品对安装/授权成本的分级承诺。L1=安装即用（仅软件虚拟化）；L2=一次开发级授权（解锁硬件虚拟化）；L3=OEM/系统级（路线图愿景，产品内不承诺）。MVP 出厂 L1+L2。
_Avoid_: root（产品语言永不声称 root）、特权模式、解锁完整版

**授权向导 (Authorization Wizard)**:
L2 的应用内引导流程：先能力检测、后引导授权（Shizuku 优先、ADB 命令兜底）；检测不支持的设备不展示向导。
_Avoid_: 一键 root、一键授权（夸大表述）

### Support floor

**支持地板 (Support Floor)**:
创建 Full Linux 实例的最低设备条件：Android 8（minSdk 26）、arm64-v8a、RAM ≥ 3GB、存储余量达标。L2（硬件虚拟化）无独立版本门槛，纯运行时检测。
_Avoid_: 最低配置（易与 targetSdk 混淆）、门槛值散落

**诚实拒绝 (Honest Refusal)**:
设备低于支持地板时明确告知原因并拒绝创建实例，绝不提供静默降级的运行路径。
_Avoid_: 降级模式（Compatibility Runtime 是显式选择，不是降级）

### Guest

**Guest Kernel 契约 (Guest Kernel Contract)**:
Full Linux Runtime 的内核约定：单一 pinned 版本、两个执行策略共用；必选能力清单进构建校验（缺项即构建失败）；CI 可复现构建、输入全校验；MVP 随 App 分发并附 SHA256 自检。
_Avoid_: 内核版本浮动、复用第三方预编译内核、内核升级静默发生

### Distro

**发行版清单 (DistroManifest)**:
一个发行版的完整产品描述：id、版本、架构、来源 URL、SHA256、大小、init 期望、按 runtime 类别的能力说明、风味。新增发行版 = 新增清单数据 + 必要的风味适配。
_Avoid_: 镜像（未指明形态时）、发行版配置

**Provisioning（首次引导装配）**:
实例创建后由 initramfs 在 guest 内完成的一次性格式化与 rootfs 解包；无 Root 环境下构建块镜像的唯一位置。
_Avoid_: 安装（与 App 安装混淆）、刷机、初始化（过泛）

### Storage

**根盘 (Root Volume)**:
Full Linux 实例的唯一持久块镜像（raw 稀疏格式，两个执行策略同格式）；Provisioning 时由发行版 tarball 装配，系统升级发生在 guest 内。
_Avoid_: 系统盘/数据盘分层、镜像（未指明时）

**导出 (Export)**:
经 SAF 将根盘镜像与实例元数据写入用户选定位置的单向备份操作；用户数据离开 App 沙箱的唯一正规通道。
_Avoid_: 云备份、同步（均未提供）

### Network

**端口转发规则 (Port Forward Rule)**:
入站访问的唯一通道：`{协议, 宿主端口, guest 端口, 绑定范围}`。默认绑定 127.0.0.1，LAN 暴露必须显式选择；规则持久化、可热更；两个执行策略语义一致、机制各异。
_Avoid_: 端口映射混称（规则是产品对象）、默认对外开放

### Bridge

**Guest Bridge（桥接协议）**:
宿主与 guest 之间传输无关、版本化的控制协议：握手、版本协商、per-VM token 鉴权、命令集与应答。宿主向后兼容 N-1 版 guest agent。
_Avoid_: 控制后门、私有接口、无版本协议

**Guest Agent（桥接代理）**:
guest 内承载桥接协议的静态单二进制；随 Provisioning 按 DistroManifest 风味安装为 init 服务。
_Avoid_: 后门程序、守护进程（过泛）

**共享目录 (Shared Directory)**:
独立于桥接控制面的文件数据通道（9p 家族）。MVP 仅只读共享 Downloads；扩大范围是显式产品决策。
_Avoid_: 文件传输（混入控制协议）、任意目录挂载

### Security

**隔离承诺 (Isolation Commitment)**:
按 runtime 类别与执行策略分级的隔离表述：硬件级（L2·pKVM）、进程级（L1·QEMU）、无实质隔离（Compatibility）。措辞宁紧勿松，只加码不减码。
_Avoid_: 完全隔离、绝对安全、军用级

**暴露面白名单 (Exposure Whitelist)**:
guest 可触达宿主资源的显式清单（初始：桥接六命令+token、只读共享 Downloads、转发默认 127.0.0.1）。加面 = 显式产品决策并修订清单。
_Avoid_: 顺手开口、隐藏通道、临时放开

### Lifecycle

**实例状态 (Instance State)**:
实例元数据中的生命周期字段（运行中 / 已停止 / 异常终止…），跨 App 进程死亡持久存在；冷恢复与异常诊断的依据。
_Avoid_: 进程状态（两回事）、会话

**冷恢复 (Cold Restore)**:
App 重启后从持久化实例状态一键重建实例运行环境的恢复方式（非内存态热恢复）。
_Avoid_: 热恢复（未做）、快照恢复（无快照）

**显示通道 (Display Channel)**:
guest 图形输出到宿主呈现的数据通道（未来增强能力类型：VNC 软渲染 → Wayland → GPU 加速渐进）；MVP 不存在。
_Avoid_: 屏幕（含混）、远程桌面（夸大）

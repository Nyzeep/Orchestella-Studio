# Orchestella Phase 5 第二会话开工 Prompt

以下正文可以完整复制到新会话。它是一份任务指令和并行边界，不是“这些能力已经实现”的声明。日期：2026-10-13。

---

你正在接手 Orchestella 的第二条长期开发线。请调用我指定的 ask-matt，结合 wayfinder/research 与当前任务需要的实现、审查能力，启动多个子代理并行推进。我要你完成广泛联网调研、按项目名称在本地拉取公开参考源码、形成有证据的技术决策、实施原创方案、构建和验证，并提交可实际审查的本地产物。不要停在研究报告或建议清单。

本会话的目标包括：

1. Phase 5A：AVF 硬件图形加速与 QEMU 图形能力对齐，探索 rutabaga_gfx/gfxstream、virtio-gpu、vsock、共享内存/缓冲导入的实际可行边界。
2. Phase 5B：无 Root 的 Linux guest 内 Rootless Podman/Docker CLI、开发工具与 code-server/VS Code Server 服务，支持手机端访问与用户显式开启的局域网协作。
3. Phase 5C：无 Root 的 OTG USB 热插拔集成与 guest 存储/文件共享性能优化，调查 usbip、QEMU USB 后端、9p、virtiofs 及可实现的替代传输。
4. 多发行版图形环境：让创建实例时可选择经过验证的图形搭配，例如 KDE Plasma、niri、labwc、轻量桌面；用户选择后自动下载、验证、配置并可恢复地启动，终端与真实桌面平滑切换，失败不破坏基础 Linux。

用户明确禁止向任何上游推送。所有参考仓库只用于本地拉取、分析和借鉴；Orchestella 本项目也不得 push、创建远程 PR、发布 release 或上传项目内容。未经用户另行明确要求，不进行 commit。不要把“调研发现”写成“真机已验证”。

## 一、工程背景与必须先读取的资料

总工作目录：

`D:\Development project(开发项目)\Orchestella开发目录`

当前第一会话的活动主仓：

`D:\Development project(开发项目)\Orchestella开发目录\Orchestella_dev`

先读取活动主仓下：

- `AGENTS.md`。
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 以及该目录全部 policy。
- `docs/agent-handoff/ARTIFACT_ORGANIZATION_POLICY.md`、`HOST_ENVIRONMENT_POLICY.md`、`ARCHITECTURE_CHANGE_POLICY.md`。
- `CONTEXT.md` 与任务相关 ADR，尤其 0001/0002/0005/0006/0007/0008/0009/0010/0011/0014/0016/0017/0018/0019。
- `docs/agents/issue-tracker.md` 与 `.scratch/orchestella-platform/map.md`，已有 M2/M3/M4 spec。
- `.scratch/orchestella-platform/spec-quality-20261013.md`：第一会话正在实施的 Linux 基线、终端、主题、网络与诚实 UI 的验收依据。只读参考，不修改其工单或抢占文件。
- `docs/research/2026-10-13-linux-reference-survey.md`、`2026-10-13-distro-validation-matrix.md`、`2026-10-13-guest-runtime-audit.md`、`2026-10-13-terminal-reference-survey.md`、`2026-10-13-theme-ui-audit.md`。
- 历史 GPU/Wayland/AVF/Podroid 研究可按相关问题定点读取，不把旧“验收全通过”文档直接当成当前真实功能证明。

旧 policy 使用过 DroidLayer 名称，实际现有目录已改为 Orchestella。不要因此新建一套 DroidLayer 影子目录。用户本次明确授权当前目录内隔离开发和下载公开参考输入，仍须遵守数据边界与宿主系统规则。

## 二、两会话并行隔离：先建立，后实现

第一会话目前仍在修改活动主仓，并有大量未提交变化。禁止对 `Orchestella_dev` 执行 reset、clean、checkout 覆盖、stash、批量格式化、git add/commit 或任何会丢失当前修改的操作。禁止在同一工作树直接并行修改图形与 guest 启动链。

你应自主建立一个本地隔离工作区，例如：

`D:\Development project(开发项目)\Orchestella开发目录\Orchestella_phase5_dev`

优先使用新的本地 worktree 和 `phase5/graphics-productivity-storage` 分支；先核对目标不存在，分支不存在再创建。现有 HEAD 若仍为 `8d0e8c75d769ecc645ea102b90a304c8657344a1`，它只代表本轮开始前基线，不能把第一会话新增能力当作已经进入 HEAD。当前 HEAD 需要重新核对，不硬猜。

如果 Phase 5 需要第一会话尚未提交的接口：

- 先读取所需接口及对应研究/规格，列出精确依赖文件。
- 仅把必要的已核实生产源文件本地复制到你的隔离工作区，记录本地基线与复制时的摘要；不要生成整仓或 Git 历史归档，不复制凭据、签名材料、实例数据、外部大资产。
- 多文件依赖仍在变化时，优先设计小的 adapter 与新模块，不在第一会话工作树中“顺手修复”。需要合流时交付本地 patch 与清晰的 API/资源依赖清单，由协调会话整合。
- 不能因为隔离分支缺少某个当前修复，就删除第一会话功能或把旧启动模板重新带回来。

隔离编译：

- Android SDK：`D:\AndroidSDK`；Java：`D:\JDK17`。
- 第一会话 `W:` 指向活动主仓，`V:` 指向已有 Gradle cache/工具根，不能重绑这两个盘符。
- 你的工作区需要短路径时先检查一个未使用盘符，例如 `P:`，再 subst 到你自己的目录。使用独立 build 输出和独立 Gradle cache/daemon，现有依赖可只读复用或定点复制。
- `local.properties` 的 SDK 路径用正斜杠 `sdk.dir=D:/AndroidSDK`。
- 不修改系统 PATH、注册表、驱动、Windows 虚拟化功能、ADB server 或用户其他工具配置。缺少系统权限时，完成可独立的工作后只报告具体阻塞。

资源与证据：

- Phase 5 大型下载/镜像/构建/原始证据放 `Orchestella\artifacts\phase5-parallel-20261013\` 等明确目录，按 A/B/C/desktop 用途分层。
- 正式研究放隔离工作区 `docs/research/`；任务图/spec 放你自己的 `.scratch/orchestella-phase5/`；实施和审查报告按项目已有 canonical 规则。
- 原始证据不用 `.log` 扩展名，使用 `.log.txt`、`.json`、`.md` 等，并及时保全。保留 Class A SDK、根镜像与工具链输入。
- 不读取或输出 `.env`、私钥、签名密码、provider 配置，不把宿主源码或日志上传到联网分析服务。

真机暂由第一会话独占：

- 已连接荣耀 MEY-AN00，Android 16/API 36。这台设备不是已证明可运行 custom AVF 的 Android 15 pKVM 旗舰。
- 第一会话正在安装 APK、启动 VM、截屏、输入和测试。你不能同时 install、force-stop、启动 VM、修改屏幕方向、截获输入或切换应用；也不要重新连接/重启 adb。
- 你可准备隔离 APK、独立测试脚本、探针与测试步骤；需要真机时通过本地交接记录请求设备时间，待明确交接再执行。
- 后续若用户提供另一台设备或第一会话释放设备，重新核对授权、能力和目标。不接受“Android 15 + Snapdragon”作为 AVF/GPU 可用性证明。

## 三、当前已知真实状态：必须按源码再次核对

第一会话正在做 Linux 基线与终端品质优化，其实时状态以本地源码/验证记录为准：

- 原启动链曾覆盖发行版 init 且提前写首次配置完成标记，已修为保留真实 init、幂等恢复、失败不写 ready。
- Ubuntu 真机已观察到 systemd PID1、cgroup2、DBus/SSH active、DNS 与出站网络正常、cold boot 文件保留；这不等于所有发行版或图形环境已验收。
- 当前 catalog 为 Ubuntu 24.04、Debian 13、Fedora 43、ArchLinuxARM、openSUSE Leap 16、Alma major 9、Rocky 9.6。固定 Alma 根包真实版本是 9.8，不要根据 major catalog 猜成 9.6。
- 七个固定输入已经精确校验大小与 SHA-256，并有实际 ARM64 软件仓元数据包名核查；各包的 init/CA/软件管理能力不同，不能套同一组包名。
- 网络为 gvproxy v0.9.0，打包 QEMU 无 slirp；legacy `-netdev socket,connect=127.0.0.1:port` 与 `virtio-net-device` 已真机工作。不要改回已知不可用的 passt、PCI ROM 依赖或剥离网卡的参数清洗。
- gvproxy QEMU listener 不能用 connect 探测 readiness：它是单 QEMU 客户端，探测 EOF 会破坏网关。第一会话新增 app-private Unix services API 做显式 TCP loopback 转发，尚在真机整合验证；不要抢改该模块。
- 控制协议保持 v1、per-VM token、唯一 reader。第一会话新增同 token 幂等 HELLO 与启动期限内重试，并实际交叉编译静态 AArch64 musl guest-agent、更新运行包校验。不能只修改 C 源却继续分发旧二进制。
- 终端已有原创 VT/alternate screen、IME/UTF-8、scrollback、真实 resize 与命令分段集成；用户名/命令高亮依据 shell 标记，未知 shell 或编辑时异步混流的边界需要诚实说明。
- App 当前主题是用户认可的构建主义风格，已做真实主题绑定、动态硬件数据与去首次 App 引导。不要全面重设计 UI。

图形链存在重要未闭合项，必须作为 Phase 5 图形前置任务：

- 当前 `QemuWaylandChannel` 曾默认标 CONNECTED，并生成本地渐变/上下栏模板帧；其输入只返回成功/记录日志，没有 guest 图形帧读取或输入转发。安装 KDE/labwc 不会自动把这张模板变成真实 Linux 桌面。
- `DesktopActivity` 的启动逻辑与 `BridgeClient.desktopLaunch()`/guest-agent 的 DISPLAY LAUNCH 路由不完整；guest DISPLAY 前缀长度和命令覆盖还有实际差异。必须从真实 client 请求、鉴权、guest compositor 进程、帧传输到 Surface 和输入回传全链验证。
- 现有静态能力矩阵或日志中的 VirGL/nativeInit success 不能证明 EGL/GLES、guest Mesa、virtio-gpu 与 Android Surface 的真实硬件加速。模拟 JVM mock、模板图像和本地“60 FPS”不算验收。
- Phase 5 不能把同一 VM 的共享串口输出视图冒充独立 PTY session，不应破坏第一会话的诚实会话语义。

## 四、并行子代理组织与决策顺序

请由主协调者建立共享任务图和模块所有权后，同时启动相互独立的研究子代理，子代理最终返回结论、引用、最小实施建议，不返回整文件 dump。供应商不可用时报告失败并由主会话继续必要工作，不因子代理故障停止任务。

建议第一轮四条研究线：

- 渲染/AVF：AOSP AVF/Terminal/crosvm、rutabaga/gfxstream、virtio-gpu、buffer 和传输权限。
- 桌面/发行版：KDE Plasma、niri、labwc、Mesa、DBus/seat、登录会话、字体输入与发行版包矩阵。
- 生产力：Podman/rootless containers、Docker CLI、code-server/官方 VS Code Server、服务与移动 WebView。
- 外设/存储：Android USB Host、rootless 权限、usbip/QEMU、9p/virtiofs、SAF/FUSE 与数据面性能。

研究形成证据后，再分配实现 worker。共享协议/能力模型由主协调者或一个指定 worker 独占；不得多个 worker 并改 native bridge、guest init、App 页面或 Gradle。

建议实施层次：

1. 先修图形真链与可验证 capability probe，建立最小真实桌面示踪切片。
2. 再加桌面 profile 与安装/恢复状态，先一发行版一组合真实跑通，再扩矩阵。
3. 容器与开发服务器可并行作为独立 guest feature profile。
4. 外设/存储先测量和确定无 Root 可行边界，再实现明确的一条数据面。
5. AVF 深度 GPU 若被平台权限阻塞，保留原型和证据，继续已可实现的 QEMU/桌面/服务工作，不用 mock 填补能力。

使用 wayfinder 时，把未决问题记录为决策票，用户已明确要求继续实现；本轮 Notes 应写明研究后推进交付。技能过程不能被用作反复询问“是否继续”的理由。涉及真正改变安全暴露面、产品契约或系统权限的决策按现有架构变更门处理，提交具体证据和可审核方案。

## 五、联网与参考源码要求

先查当前已有目录，复用第一会话只读参考源码：

`DroidSpace`（ravindu644/Droidspaces-OSS）、`Podroid`、`termux-app`、`proot-distro`、`Droidspaces-rootfs-builder`、`AndronixOrigin`、`LinuxDeploy`、`UserLAnd`、`termux-x11`。

Droidspaces 是 Root namespace 容器，PRoot 项目是受限用户空间，Podroid/AVF 是 VM，Termux:X11 是显示服务器。必须区分职责和权限，不能把 Root 容器/PRoot sandbox workaround 写入独立 Linux VM。禁止复制公开固定 root 密码、关闭包签名、关闭 TLS、setenforce 0 等做法。

新增参考先联网确认官方维护仓/版本/许可证，再按名称拉取到总目录或明确 `phase5-references/<name>`；目录已有就只读，不 checkout/pull 改掉第一会话固定 revision。建议覆盖但不限定：

- AOSP `packages/modules/Virtualization`、官方 Terminal、crosvm/rutabaga、gfxstream、QEMU/virglrenderer、Mesa。
- KDE Plasma/KWin、niri、labwc、wlroots、Weston、waypipe，必要时 x11vnc/noVNC 作明确可用性对照。
- Podman、containers/common、netavark、aardvark-dns、slirp4netns/pasta、rootlesskit、Moby CLI/daemon。
- code-server、官方 VS Code Server/remote tunneling 文档、OpenVSCode Server 的许可与服务模式。
- Linux usbip、usbipd-win 的职责对照、QEMU/libusb USB host 实现、Android USB Host framework。
- virtiofsd、libfuse、virtio-fs/9p 文档、crosvm share 模型、Android SAF/scoped storage 官方文档。

每条关键判断记录官方 URL、固定 revision、源码路径/行号、权限前提、许可证、未运行边界。搜索词不携带本项目秘密；不用外部代码上传服务。GPL/无许可证源码按本项目零代码复制策略研究设计，原创实现；若确需引入独立组件，先明确兼容许可、NOTICE、对应源码和分发边界。

## 六、Phase 5A：真实图形与 AVF 硬件加速

不要把“vsock 连接可用”与“共享内存/GPU buffer 零拷贝透传”混为一谈。逐项回答并实验：

- 当前 App 实际能否通过非平台/非 Root 权限运行 custom AVF VM？MANAGE 与 USE_CUSTOM 权限、服务可见性、保护/非保护 VM、OEM 与 Android 版本分别是什么？
- AVF/crosvm 是否允许第三方配置 GPU backend、virtio-gpu、render node、host dma-buf/memfd/AHardwareBuffer 导入？哪些仅平台构建可用？
- vsock 是字节流，不能自然传 host 文件描述符；buffer handle 在 host/guest 两个命名空间如何建立、授权、同步与回收？需要 virtio 设备、blob resource、host visible memory 或平台特权的证据是什么？
- rutabaga_gfx、gfxstream、virglrenderer 分别提供哪种 API/传输，与 guest Mesa/virtio-gpu blob/renderer caps 的具体配对是什么？Android host EGL/Vulkan 驱动可否直接使用，ABI 与线程模型怎样验证？
- fencing、帧格式、pitch/stride、颜色空间、尺寸变化、队列上限、丢帧策略、显存限额和 context loss 怎样处理？

最小可执行切片应包括真实 guest 图案/应用帧、跨 VM 通道、Android Surface 上屏与触摸/鼠标/键盘回传。先允许有明确标签的 CPU copy fallback，再优化零拷贝；fallback 必须保留同一 Full Linux 实例，不能退为 PRoot 或生成模板帧。

验收：

- 截图内容随 guest 实际操作改变，能证实 guest 绘制而非 host 模板。
- 记录 guest renderer `glxinfo/eglinfo/vulkaninfo` 与 host 实际 backend，不能凭“VirGL 初始化成功”定性。
- 帧率、frame time、CPU、RSS、copy bytes 等有实测来源；冷启动、旋转、IME、resize、后台/前台、context loss、断流不会崩溃或写坏用户实例。
- 明确 Android 15 目标旗舰是否实测；当前荣耀机若 AVF custom VM 不可用，只标该设备结果，不否定所有型号，也不把结果报为 AVF GPU 已完成。

## 七、多发行版可选桌面与合成器

定义独立 `DesktopProfile`/同等模块：id、显示名称、支持发行版/版本/架构、所需 kernel/graphics/seat 功能、包集合、服务/环境、配置版本、磁盘/RAM估算、已验证 backend、状态与失败原因。

建立真实矩阵：

- KDE Plasma：优先 Wayland，核对发行版版本、KWin、Qt、DBus、logind/session、portal、Polkit、字体和软件渲染后备。
- niri：核对版本/架构包供应、wlroots 或独立渲染能力、seat/libseat/logind、Mesa/Vulkan/DRM 需求；不假定所有发行版已有包。缺包时调查可信源码构建并记录维护成本，不能显示不可安装选项。
- labwc/轻量桌面作为低资源可用组合；根据实际数据提供推荐，不强制所有手机安装 KDE。
- 只声明每个发行版真正验证过的组合。未验证组合可标实验状态并有具体限制，不能对所有 profile 统一绿色徽章。

实例创建 UI 保持现有设计，在明确的图形选择区说明体积/内存、下载需求与能力。用户选“终端环境”则不后台安装整套 GUI；选桌面后安装计划与进度真实可见，可取消与重试。

安装为可恢复的 guest 内事务：下载/包签名/版本约束、依赖检查、用户会话与 runtime dir、service/unit 生成、启动探针、真实帧握手都成功后才 ready。重启幂等，失败不提前 marker、不丢 rootfs、不覆盖用户配置。不要给 Wayland 合成器长期 root 运行或默认 `--no-sandbox`；优先正常 guest 用户与 logind/seat。

启动与切换：

- compositor 启动进程与真实通道握手是不同阶段，两者均可诊断。
- 终端切桌面、桌面切终端不停止 VM 或重新 fork compositor；错误能回终端查看日志。
- onResume/Surface 重建能重新附着，分辨率/DPI/IME/鼠标捕获有一致行为。
- 安装未完成、compositor crash、GPU不支持、传输断开、帧超尺寸分别提示，不用相同“连接中”无限等待。
- 增加被拒的 DISPLAY 请求仍保持控制连接、未知命令不冒充成功等协议负例。扩展命令必须版本化并遵循 token/暴露面契约。

## 八、Phase 5B：容器与开发服务器

无 Root 指 Android host 不需要 Root，不等于 guest 内永远不能有 root。优先实现普通 guest 用户可运行的 Rootless Podman，说明 user namespace、subuid/subgid、cgroup v2、seccomp、fuse-overlayfs/native overlay、网络/DNS、slirp/pasta/netavark 等实际能力。

不要把 Docker CLI 当成已运行 Docker daemon：分别验收 CLI、rootless daemon、连接已有 daemon/socket 的配置与能力。禁止默认暴露 Docker TCP API、装无签名脚本、把 socket 绑定到 Android 外部存储。

提供用户可选的开发/容器 profile，固定或可审计的 ARM64 输入、明确基础镜像来源与 digest，真实测 `podman run`、DNS/TLS、volume、端口、重启服务与普通用户权限。不要把拉取容器镜像仅成功当作完整容器支持；kernel缺项明确拒绝对应 profile。

code-server 与官方 VS Code Server 分别研究许可证、分发/登录/tunnel条件和维护方式，不能把二者误认为可任意替换的同一产品。首个落地服务优先本地回环端口、每实例随机凭据、后台服务生命周期、启动/停止与健康检查。

手机 WebView：只访问选定的本地服务，默认不桥接任意 JavaScript 到 Android 权限；navigation allowlist、外链、文件选择、剪贴板权限、IME、证书与错误状态有明确边界。认证信息不放 URL/query/logcat，导入凭据不上传。

局域网协作必须用户显式开启、可见绑定/端口、认证、关闭与重启一致；默认只回环。远程云 tunnel 将涉及第三方身份、端点与用户数据传输，不能静默启用；先完成本地方案、研究记录具体用户授权需求，不用“用户提到了隧道”扩展成无限数据外发。

验收包含弱/缺凭据、端口冲突、服务 crash、重复点击启动、断网、guest shutdown、WebView 后台、LAN关闭后实际 listener 移除；禁用后重启不能再次暴露端口。

## 九、Phase 5C：USB 与共享存储

OTG：

- 先区分 Android USB Host 的用户授权接口与 Linux kernel usbfs/libusb host privilege。没有 host Root 时，QEMU `-device usb-host` 是否能直接打开目标节点必须实测，不照抄桌面命令。
- Android 应用授权得到 UsbDeviceConnection/FD 后是否可经可审计 native backend 供 QEMU 使用，热插拔、descriptor/endpoint、bulk/interrupt/control/isochronous各自边界是什么？
- usbipd-win 是 Windows 服务工具，不是手机端无 Root kernel usbip exporter。不要为解决 Android 需求擅自安装 Windows 服务或驱动。
- AVF 是否支持 USB 透传能力需单独调查；不可实现时给真实 capability 和合理替代，不使用蓝牙/模拟事件冒充 USB passthrough。
- 授权拒绝/拔出/重插/VM停止能正确回收 FD、取消传输、不 crash、不把外设长期自动绑定给新实例。

存储：

- 9p/virtiofs 的 host server、QEMU/crosvm设备支持、guest kernel、共享权限与 Android scoped storage 是四个独立环节。
- SAF Document URI 不是可随意传给 QEMU 的 POSIX 文件路径；Android `/sdcard`/FUSE 行为和 App 私有存储要分别测量。
- 调查实际可用的只读 Downloads 共享、用户显式选定的目录、FD/stream bridge、guest侧缓存或增量同步；保持既有白名单和默认只读，新增写能力必须有产品/架构决策。
- 禁止把整个宿主工作区/Git/凭据纳入后台同步。要写入同步，显示范围、冲突、取消、磁盘满、失败与保留策略，不静默双向覆盖。

基准必须用合成数据、准确路径与未缓存/已缓存条件，至少大文件顺序读写、小文件创建/stat/遍历、双向传输、CPU/内存、文件一致性和断流。给基线与改善数值、环境和限制，不能只报某次 fio 峰值；benchmark不得写坏用户文件。

## 十、实施、验证与最终交付

持续推进已授权的可逆代码和研究，不反复询问常规实现偏好。每个重要决策记录事实、推荐、代价、可逆性；真正平台权限、外部账号/身份、系统驱动或安全边界变更的阻塞才交用户。

测试要有意义，既有成熟测试优先复用。至少包括：

- GPU/桌面通道：非法尺寸/stride/帧大小、GPU失效、断流、重连、鉴权失败、能力不足、两后端行为一致。
- profile 安装：失败/取消/磁盘满/包签名失败不写 ready，升级重入、用户配置保留、可选模块失败不破坏基础 Linux。
- 容器/服务：普通用户权限、端口/认证、生命周期、关闭持久性、没有虚构 listener/health 状态。
- USB/存储：拒绝授权、热拔出、超范围路径、symlink/URI escape、只读写拒绝、断传/冲突/数据一致性。
- UI：真实进度、空状态、明确实验能力、动作对齐、浅深主题、IME与图形输入；不再加无意义的静态性能数值。

没有实际设备可验证的步骤，要给可运行脚本和明确“未运行”，继续能独立做的单元/集成/宿主验证。受平台能力限制的功能可交付证据完备的原型与诚实 fallback，但不能用空方法、mock图像或“将来实现”按钮冒充成功。

本地交付至少包括：

1. 参考源码目录列表、官方 URL/revision/许可证及研究结论。
2. Phase 5 决策图/spec/子任务状态，明确真实完成和未完成项。
3. 隔离工作区内实际修改、新模块/API、当前第一会话依赖和可合流的本地 patch；不覆盖活动主仓。
4. 可安装 APK/必要 guest 二进制或 profile输入，其 SHA、对应源码、构建锁、NOTICE与验证结果。
5. 真实图形、容器、开发服务器、外设/存储的运行证据及复现命令；每发行版/每profile/每backend验证矩阵。
6. 能力不足/权限阻塞、性能代价、已知风险与用户需处理的精确动作。未实测不写“完全可用”。
7. 当前会话恢复/集成指南，按文件所有权交接并列出需要重新运行的回归。

先读取资料和建立隔离工作区/任务图，立即并行研究；取得足够证据后继续实现与验证。所有上游与本项目远端保持无推送。用户希望形成有自己方案、有真实运行质量的无 Root Linux 作品，请用真正的通路和测试结果回答，而不是增加装饰性能力名称。

---

## 第一会话仍在执行的边界记录

当前会话负责完成 Linux 基线、终端命令分段/输入/尺寸、主题/首页、真实 SSH 转发、七发行版检查和最终 APK。新会话负责独立 Phase 5 图形/生产力/外设/存储增强。

此文档是截至 2026-10-13 的开工指令快照。读者在需要依赖当前接口或独占设备前，应核对实时源码/验证报告，不把文档中的“正在”自动解释为已经完成。

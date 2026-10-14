# Phase 5：真实显示、可恢复功能与权限诚实的移动 Linux 工作站

Status: ready-for-agent
Date: 2026-10-13
Owner: phase5-coordinator
Input: 用户 Phase 5 第二会话开工文档、当前源码与并行研究；运行能力均以实际探针/验证分级。

## Problem Statement

用户已有 Full Linux 实例，却不能从现存图形通道获得 guest 绘制的真实桌面；模板帧、静态能力徽章和未执行输入掩盖了这个缺口。桌面、容器与开发服务尚无统一的可恢复安装/启动流程；Android USB 授权、文件 URI、VMM 支持和 guest 驱动也不能视为同一种能力。需要在保持实例、终端、现有设计与安全边界的前提下交付可运行的通路和诚实状态。

## Solution

保留同一 Full Linux 实例，以真正 Wayland 合成器产生的帧接入宿主 Surface。首通路允许清楚标示 CPU 拷贝传输，并单独记录 guest renderer 与宿主呈现能力。桌面/开发/容器通过用户可选 profile 安装，失败可重试且不破坏基础 Linux；开发服务默认仅手机回环访问。外设和文件共享以真实可用探针与严格只读、明确范围的数据面交付，平台权限受阻的增强保持不可宣称可用。

## User Stories

1. 作为手机用户，我要默认创建终端环境，让未选图形的实例不下载整套桌面。
2. 作为手机用户，我要选择发行版真正有 ARM64 软件包的桌面组合，并了解下载、磁盘、RAM与实验限制。
3. 作为手机用户，我要看见真实安装阶段、失败原因与重试入口，让可选功能失败后仍可使用 Linux 终端。
4. 作为桌面用户，我要画面随 guest 应用与输入改变，确认显示的是我的 Linux 桌面。
5. 作为桌面用户，我要在终端与桌面之间切换而不停止实例或重复启动合成器。
6. 作为桌面用户，我要触摸、鼠标、键盘与 IME 输入被实际发送，能力不支持时明确失败。
7. 作为桌面用户，我要旋转/Surface 重建后重新附着，断流与超限有明确状态。
8. 作为用户，我要 CPU 拷贝和 GPU 加速有独立证据，避免把宿主 GLES 上屏误认为 guest GPU 加速。
9. 作为开发者，我要普通 guest 用户运行 Rootless Podman，kernel 缺项得到具体拒绝。
10. 作为开发者，我要区分 Docker CLI 与 daemon 的状态，避免把已安装 CLI 误认为 Docker 服务可用。
11. 作为开发者，我要安装可审计 ARM64 code-server，在手机内打开实际本地服务。
12. 作为开发者，我要每实例随机开发服务凭据，认证信息不进入 URL、query或日志。
13. 作为开发者，我要启动/停止/健康状态来自真实服务探针，重复点击不会创建多份服务。
14. 作为协作者，我要显式开启带认证与到期关闭的 LAN 监听，并在关闭与重启后确认端口移除。
15. 作为用户，我要 WebView 仅访问所选本地服务，外链、文件与剪贴板操作有明确边界。
16. 作为 OTG 用户，我要授权、拒绝、拔出与 FD 回收具有可验证状态，不把 Android 键鼠事件冒充 USB 透传。
17. 作为用户，我要显式选定 Downloads 范围的只读共享，路径/URI/symlink越界与写入被拒绝。
18. 作为用户，我要大文件/小文件/缓存与断流基准有合成数据、环境和实际数字，避免损坏现有文件。
19. 作为维护者，我要在隔离分支审查源码、测试、APK校验与本地patch，并知道第一会话接口的精确依赖。
20. 作为维护者，我要每发行版/profile/执行策略的研究、宿主测试、guest和Android实测分别标识，未运行不写成功。

## Implementation Decisions

- 保留 VM-first/Wayland-first、现有策略中性显示/输入契约与 per-VM 鉴权。修复源码与 accepted ADR 的事实矛盾时先建立 ACR；不擅自修改正式决策。用户已明确允许 CPU copy fallback，因此可实现实际 Wayland 合成器加有界字节流帧传输。X11/Xvnc仅诊断原型，不替换正式 Wayland 路线。
- 首通路优先普通 guest 用户的 labwc headless/pixman 与发行版签名 wayvnc；RFB 3.8 是明确标注的 CPU 拷贝传输。输入/帧完全来自服务。缺少包、headless renderer或虚拟输入协议时确定性拒绝，不返回模板帧。
- 专用显示数据流先执行版本1、per-VM token 握手，再承载严格受限 RFB；使用 app-private/实例独立连接，不复用控制 reader。QEMU用已有 virtio-serial 设备能力；AVF用真实 VM 的 vsock connector，仅有连接API且认证/帧成功才声明显示能力。
- 显示 codec 是纯 Java 深模块：流握手、格式协商、Raw rectangles、DesktopSize、输入写入、限额与统计。总像素≤4,194,304、边长≤4096、RGBA framebuffer≤16MiB；先校验再分配。帧只在真实像素更新后发布，初始 CONNECTED 需合法首帧；最旧帧可丢、队列有界。未知编码/非法尺寸/断流失败即停。
- 帧 immutable 契约校验维度、stride乘法、format、实际长度；宿主呈现修复 pitch/format/context-loss，不把 EGL 初始化成功升级为 guest renderer验证。性能仅从真实帧时间与传输字节测量，未取得样本显示暂无数据。
- profile 元数据包含发行版/版本/架构、依赖与包集合、backend、资源预算和验证状态。安装状态区分 planned/installing/installed/starting/awaiting-frame/ready/failed/cancelled；没有真实帧确认不写 desktop ready。
- 可选 guest 安装器独立于基础 runtime；正常用户会话/runtime dir与 owned service配置，原子状态写入与单实例锁。包管理沿用签名验证；配置存在时保留用户文件。部分包安装失败保留安装事实与可重试状态，不删除根盘、不覆盖 init、不提前 ready。
- version1 feature扩展需显式 capability协商，旧 agent 拒绝时降级为不可用。仅白名单 profile/action，鉴权前不执行。长安装启动后台job并立即返回受理状态，状态轮询有期限；失败请求不破坏原控制连接。不新增guest任意执行命令或host文件传输命令。
- Rootless Podman依赖运行探针而非静态kernel配置；Docker CLI、rootless daemon分别标识。普通用户、subuid/subgid、cgroup、seccomp、overlay/FUSE、DNS/TLS/volume/port必须分别验证。
- code-server从锁定官方ARM64输入或发行版签名输入安装；每实例随机凭据、owned后台service、真实健康检查。默认guest loopback，可显式guest NIC relay至现有host loopback转发适配；不修改第一会话gvproxy模块语义。LAN仅用户显式开启的限定转发、有认证、到期关闭，进程死亡与禁用后重启不重新暴露。
- 手机WebView按scheme+精确IP+端口+origin限制，禁任意Android JS bridge、file/content访问、mixed content与证书绕过；凭据由用户显式显示/输入，URL不携带秘密。
- 只读存储模块仅接收明确授权的Downloads内文件集合，受数量/字节/名称/取消限额，原子generation发布与一致性验证；不递归扫描host工作区。不具备物理9p/virtiofs支持时显示不可用；代码可作独立已测原型，不能宣称guest已挂载。
- USB仅先交付用户授权与FD生命周期探针/原型，真实VMM透传及新guest exposure需架构门；不安装Windows驱动/服务。不把buffer零拷贝、usbip exporter或AVF GPU功能用空方法补齐。
- 本地工作区/模块所有权隔离，必要第一会话生产依赖以逐文件hash基线记录，合流patch以此为参照，禁止覆盖活动主仓；不commit/远端写。SDK/缓存只读复用，独立build/daemon/cache，真机未经明确交接不触碰。

## Testing Decisions

- 沿用纯Java/JUnit契约测试、现有BridgeResponsePump测试与guest process/script测试。测外部行为、真实输入输出与失败状态，不为实现细节造镜像测试。
- 显示：有实际RFB stream的握手/鉴权/像素变化/输入字节集成；非法大小、stride/长度、断流/重连、未知编码、格式与资源限额负例。合成协议fixture仅证明codec，不标guest或GPU实测。
- 控制：未鉴权/未知命令/被拒DISPLAY后PING仍工作，单reader、重复HELLO、旧agent不支持feature的明确拒绝。
- profile：安装失败/签名失败/取消/磁盘满/重复/配置保全/升级重入不写ready；desktop首帧确认与installed分离。
- 服务：loopback origin/弱凭据/端口冲突/crash/重复启动/关闭持久性/timeout、真实listener建立与移除；WebView访问边界与无URL秘密。
- 存储/USB：授权拒绝、热拔出/取消/关闭、名称/路径/URI/symlink越界、只读拒写、断传及hash一致性；合成大/小文件冷热基准，不接触用户实例盘。
- 构建APK及guest ELF有源码、固定输入、SHA256、NOTICE与环境记录；独立review修复后重跑相关gate。没有独占设备时准备可运行步骤与交接记录，所有guest/Android未运行项明确保留。

## Out of Scope

静默cloud tunnel/外部身份配置；默认Docker TCP；任意host/Git/凭据共享；默认写共享/双向同步；主仓覆盖、设备争抢、系统驱动与虚拟化重配；PRoot替代；虚构frame/GPU/60FPS/独立PTY；所有commit/push/远端PR/release/项目内容上传。

## Further Notes

对现实与ADR0017的共享内核/零拷贝等假设以及ADR0010的抽象套接字UID隔离说法记录证据与ACR，受影响零拷贝/GPU/USB增强冻结；独立CPU-copy、鉴权、capability修正与既有语义bug修复继续。

用户已给具体并行和验收授权。本规格的测试接缝采用既有显示/桥接/服务流输入输出，按授权自主推进，不追加常规确认。Profile exact pins/package矩阵由并行研究补充为输入锁，未知组合不成为可选择安装项。规格完成不等于设备完成；最终矩阵逐级分开。

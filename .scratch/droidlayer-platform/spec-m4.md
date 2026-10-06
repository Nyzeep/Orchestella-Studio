# Orchestella Studio Phase 4（M4 里程碑）— 产品规格说明书（Spec）

Labels: ready-for-agent  
Version: 4.0（2026-10-06）  
依据：ADR-0001 ~ ADR-0019、`docs/research/2026-10-06-phase4-gpu-acceleration.md` 与 `docs/research/2026-10-06-virtualization-ux-and-competitors.md`  
变更纪律：本规格说明书的每个承诺必须可追溯到项目架构基准与既定 ADR；未经架构决议，不得擅自修改技术边界或新增隐性承诺。

---

## 1. 问题陈述（Problem Statement）

在完成 Phase 1（M1 终端底座）、Phase 2（M2 Wayland 图形桌面）与 Phase 3（M3 系统互通与音频外设）后，Orchestella Studio 已经实现了在 Android 免 Root 环境下运行完整独立 Linux 内核、60 FPS 满帧桌面呈现、低延迟音频流式输出与双向防环剪贴板。

然而，在产品形态、终端交互与渲染能效层面，当前版本依然暴露出严峻的工业级可用性瓶颈：
1. **渲染瓶颈与硬件发热**：虽然 Wayland 呈现达到了 60 FPS，但 Linux Guest 内部的图形渲染完全依赖 CPU 软件光栅化（llvmpipe/pixman）。当运行轻量 3D 渲染、现代 Web 浏览器（Chromium/Firefox）、或带动态视觉效果的应用时，CPU 单核与多核负载飙升至 80%~100%，引发移动设备剧烈发热并迅速触发芯片降频；宿主呈现层借由 Java `DoubleBufferedRenderer` 进行位图上传，存在 JNI 跨层与双重缓冲开销；
2. **硬件参数黑盒不可控**：虚拟机 CPU 核心数、RAM 运存与磁盘大小在代码中静态硬编码，用户无法根据宿主物理配置（例如 16GB 大运存平板 vs 6GB 紧凑手机）调整划拨配额，经常导致低配机型因超出可用内存而触发 Android LMK（Low Memory Killer）闪退，或高配机型算力被白白浪费；
3. **实例生命周期缺损造成存储泄漏**：现有 `InstanceLifecycle.Store` 仅支持实例的创建与加载，缺乏物理删除接口。损坏或废弃的实例无法在应用内彻底清除，导致私有目录中动辄数 GB 的 `.raw` 磁盘镜像与临时交换文件永久滞留，形成严重的移动端存储空间泄漏；
4. **弱网与跨境网络下载极度脆弱**：当前 rootfs 下载强依赖单一官方源，不支持 HTTP `Range` 断点续传与多镜像源调度。在复杂移动网络或跨境网络波动下，数十至数百兆的镜像下载频繁断流，重试只能从零重下，用户首次使用失败率极高；
5. **终端交互处于原始阶段**：`TerminalActivity` 仍为基础的单色 `TextView` 追加模式，无 ANSI/VT100 256 色/真彩色渲染解析，移动端软键盘缺少开发必需的辅助按键（Esc、Ctrl、Alt、Tab、方向键、管道符 `|`），缺乏美观的 Shell 提示符与语法高亮，严重挫伤专业开发者的使用意愿；
6. **新手用户认知壁垒高陡**：缺乏开箱即用的新手向导（Setup Wizard），普通用户面对“无 Root”、“虚拟化权限（AVF）”、“Alpine 与 Debian 差异”等复杂概念无所适从，文档缺乏分层指导。

因此，M4 里程碑必须彻底解决这些产品级短板，交付**GPU 硬件加速渲染、VMware 级硬件参数可定制、实例生命周期完整闭环（物理回收）、多源抗阻弹性下载引擎、现代化终端模拟器与美观 Shell 集成、以及双轨分层交互引导**。

---

## 2. 解决方案概述（Solution Overview）

**Orchestella Studio M4**：在坚持 VM-First、免 Root、契约中立与 Class A 资产绝对保护的前提下，全面实施产品体验跃迁与底层渲染加速重构。

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        Orchestella Studio M4 全栈架构全景图                            │
└────────────────────────────────────────────────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ 【用户体验与交互层 (Presentation Tier - Android 17 / Material 3 Expressive)】        │
 │   - Material 3 Expressive Dashboard 仪表盘 (替换 activity_main.xml)                  │
 │   - UTM 级虚拟机卡片: 动态呼吸状态 Badge、长按操作弹窗 (桌面/终端/硬件/备份/删除)      │
 │   - 首次启动三步闭环设置向导 (Setup Wizard: 权限嗅探 -> 硬件检测 -> 推荐配置)        │
 │   - 现代终端模拟器 (ANSI 256色 / TrueColor / 移动辅助按键栏 Esc|Ctrl|Alt|Tab|Pipe)   │
 └───────────────────────────────────┬──────────────────────────────────────────────────┘
                                     │
 ┌───────────────────────────────────▼──────────────────────────────────────────────────┐
 │ 【平台管理与弹性引擎层 (Platform & Engine Tier)】                                    │
 │   - VmHardwareConfig 硬件配置模型 (vCPU 1~8核, RAM 512M~8G, 稀疏磁盘 2G~64G, GPU开关)│
 │   - 宿主硬件自适应嗅探器 (HostHardwareDetector: 物理核/可用RAM探测, 动态安全滑块)    │
 │   - 实例生命周期 CRUD 闭环 (安全熔断拦截 RUNNING 删除, 物理级联清扫 .raw 与元数据)     │
 │   - 多源抗阻弹性下载引擎 (GitHub / 全球 CDN / 国内镜像 Fallback + HTTP Range 断点续传)│
 └───────────────────────────────────┬──────────────────────────────────────────────────┘
                                     │
 ┌───────────────────────────────────▼──────────────────────────────────────────────────┐
 │ 【底层渲染与 GPU 硬件加速管线 (Native GPU Acceleration Pipeline)】                   │
 │   - 宿主 NDK 原生直通层: NativeGlesSurfacePresenter (ANativeWindow + GLES3 PBO 双缓冲)│
 │   - QEMU L1 内嵌 VirGL 3D (libvirglrenderer.so NDK 精简版, virtio-gpu-gl-device)    │
 │   - 三重安全防御纵深: EGL Robustness (防整机崩溃) + 显存配额守卫 (防 LMK 闪退)        │
 │   - Guest 操作系统: 搭载 Mesa virgl 3D 驱动 + Starship 现代化极简美观 Shell 预设      │
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 七大核心功能规格详细定义

### 3.1 视觉体系与 UI 交互（Android 17 / Material 3 Expressive）
1. **全新 Dashboard 仪表盘**：
   - 彻底废除旧版线性堆叠布局（`activity_main.xml`），引入全新 Material 3 Expressive 仪表盘；
   - 接入 Material You 动态色彩提取系统，界面背景、卡片表面与强调色随系统壁纸自适应调色；
   - 适配沉浸式状态栏与导航栏（Edge-to-Edge Display），安全区（WindowInsets）精准自适应；
2. **UTM 级虚拟机卡片式管理**：
   - 每个实例呈现为独立悬浮圆角卡片（`InstanceCardView`）；
   - 卡片头部展示发行版图标（Alpine/Debian/自定义）与实例名称；
   - **动态呼吸状态微标（State Badge）**：
     * `RUNNING`：翠绿色呼吸微光动画，标注当前执行引擎（如 `pKVM 硬件虚拟化` 或 `QEMU 软模拟`）；
     * `STOPPED`：中性灰色胶囊微标；
     * `CRASHED`：珊瑚红色故障微标，带点击查看错误诊断入口；
   - 硬件资源摘要标签（如 `4 vCPU · 4096 MB · 20 GB 磁盘 · GPU 开启`）；
3. **交互手势与上下文操作弹窗**：
   - **单击卡片**：直接唤醒最近运行的会话（图形桌面或终端）；
   - **长按卡片 / 点击更多按钮（⋮）**：唤出底部操作面板（`InstanceActionBottomSheet`），包含：
     * 🖥️ **启动图形桌面**（直接进入 `DesktopActivity`）；
     * 💻 **打开终端 Shell**（进入升级后的 `TerminalActivity`）；
     * ⚙️ **硬件参数配置**（打开 `VmHardwareConfigDialog`，仅在关机状态下可编辑）；
     * 📦 **导出备份文件**（通过 Android SAF 导出当前实例为独立存档）；
     * 🗑️ **彻底删除实例**（触发带二次确认的安全熔断物理删除流程）。

### 3.2 VMware 级硬件参数可定制（VmHardwareConfig）
1. **硬件配置实体契约（`:runtime:contract`）**：
   - 定义策略中立的 `VmHardwareConfig` 不可变实体：
     * `cpuCores`：分配给 Guest 的虚拟 CPU 核心数，取值范围 $[1, 8]$，默认 $2$；
     * `ramMegabytes`：分配给 Guest 的运存大小，取值范围 $[512, 8192]\text{ MB}$，默认 $2048\text{ MB}$；
     * `diskCapacityBytes`：虚拟根磁盘逻辑容量，取值范围 $[2\text{ GB}, 64\text{ GB}]$，默认 $8\text{ GB}$；
     * `gpuAccelerationEnabled`：GPU 硬件加速开关（布尔值），默认随设备能力自动开启；
     * `extraKernelArguments`：高级开发者内核追加参数（可选字符串）；
2. **宿主物理硬件感知探测器（`HostHardwareDetector`）**：
   - 实时读取宿主物理 CPU 核心总数（`Runtime.getRuntime().availableProcessors()`）；
   - 通过 Android `ActivityManager.MemoryInfo` 读取系统总物理 RAM 与当前可用空闲 RAM；
   - **动态安全阈值算子**：
     * 推荐 RAM 上限：$\min(\text{物理总内存} - 2048\text{ MB}, \text{可用空闲内存} \times 0.75)$，确保宿主 Android 系统永远保留至少 2GB 内存，彻底防止触发 LMK 杀后台；
     * 推荐 CPU 上限：$\max(1, \text{物理核心数} - 1)$，保留 1 个核心调度宿主 UI；
3. **VMware 风格配置交互面板**：
   - 在创建实例向导与关机卡片菜单中提供直观滑块调节器：
     * vCPU 核心数调节滑块（步进为 1）；
     * RAM 运存调节滑块（步进为 256MB，滑块划分绿-黄-红安全警戒区间）；
     * 磁盘容量扩容输入器（仅允许扩容，不允许缩减以防文件系统元数据损坏）；
     * GPU 3D 硬件加速开关（若宿主不支持则提供置灰原因提示）。

### 3.3 实例生命周期完整闭环（CRUD & 存储物理回收）
1. **持久化存储契约扩展（`:runtime:instance`）**：
   - 扩容 `InstanceLifecycle.Store` 接口：
     ```java
     public interface Store {
         void save(Instance instance) throws IOException;
         Instance load(String id) throws IOException;
         List<Instance> list() throws IOException;
         void delete(String id) throws IOException; // Phase 4 新增物理删除契约
     }
     ```
2. **安全熔断机制（Safety Circuit Breaker）**：
   - `Store.delete(id)` 执行前必须进行状态前置检查：
     * 实例状态必须为 `STOPPED`、`CREATED` 或 `CRASHED`；
     * 若实例当前处于 `RUNNING` 或 `PROVISIONING` 状态，抛出 `IllegalStateException("Cannot delete instance while running or provisioning")` 阻断操作；
3. **级联物理清扫（Cascade Physical Purge）**：
   - 执行删除时，物理遍历并彻底销毁该实例关联的私有资产：
     * 虚拟磁盘文件（`<instanceId>.raw`）；
     * 临时交换与 Overlay 文件（`<instanceId>.swap`、`*.tmp`）；
     * 实例元数据描述文件（`<instanceId>.properties`）；
     * 关联的日志和崩溃转储文件；
   - 回收完成后广播实例列表刷新，彻底释放物理存储空间。

### 3.4 多源抗阻弹性下载引擎（Resilient Download Engine）
1. **多镜像通道调度列表（Mirror Channels）**：
   - 构建多级分发镜像策略，为官方 rootfs 提供高可用通道表：
     * 通道 1（官方源）：GitHub Releases 官方 CDN；
     * 通道 2（全球加速源）：Fastly / Cloudflare 官方加速镜像；
     * 通道 3（国内备用加速源）：国内镜像代理与镜像源（如 ghproxy / 清华大学开源镜像源备用通道）；
2. **HTTP Range 断点续传与分片恢复**：
   - 采用 HTTP 请求头 `Range: bytes={offset}-`；
   - 本地写入临时文件（`<filename>.download`），记录已成功接收的字节游标；
   - 发生网络断开或超时时，重试直接从 `offset` 处向后追加写入，拒绝从 0 重下；
3. **超时自动故障转移与指数退避（Failover & Exponential Backoff）**：
   - 单通道连接超时时间设为 $10\text{ 秒}$，传输停滞超时 $15\text{ 秒}$；
   - 触发网络异常时，在当前镜像源重试最多 3 次（退避间隔 1s、2s、4s）；
   - 若重试耗尽仍失败，自动 Failover 切换至下一个镜像通道，无缝继续断点下载；
4. **流式 SHA-256 强校验与 UI 进度感知**：
   - 下载过程中双写流式 SHA-256 摘要计算器，下载完毕瞬间完成哈希对账，防网络篡改；
   - 提供精确的进度监听器接口（已下载字节、总字节、下载速率 MB/s、剩余预计时间、当前镜像节点名称与重试状态）。

### 3.5 现代化终端模拟器与美观 Shell 集成
1. **现代化终端渲染器（升级 `TerminalActivity`）**：
   - 彻底废除原始的 `TextView` 追加模式，引入现代虚拟终端解析与渲染引擎（`ModernTerminalView`）；
   - 完整支持 ANSI / VT100 控制序列、256 色调色板以及 24-bit TrueColor 真彩色显示；
   - 预设精美终端调色方案（默认采用工业标杆 Dracula 主题，备选 Solarized Dark）；
   - 采用等宽字体（JetBrains Mono / monospace）渲染，严格对齐表格与树状字符；
2. **移动专属辅助键盘栏（Mobile Auxiliary KeyBar）**：
   - 在软键盘上方固定集成高频快捷按键栏：
     * 功能键：`ESC`、`TAB`、`CTRL`、`ALT`；
     * 符号键：`|`（管道符）、`-`、`/`、`~`、`$`、`\`；
     * 导航键：`↑`（上翻历史命令）、`↓`、`←`、`→`；
   - 支持 `Ctrl` 与 `Alt` 的粘滞锁定模式（Sticky Modifier State），点击高亮激活，配合物理键发送组合键信号（如 `Ctrl+C` 发送 `\x03` 中断信号）；
3. **Guest 现代化 Shell 预设装配（`guest-assets`）**：
   - 在 Alpine 与 Debian 发行版初始安装流程（provisioning）中部署极简、现代的 Shell 配置：
     * Starship 风格极简双行提示符（展示当前目录、Git 分支、执行耗时）；
     * 预置丰富色彩的 `LS_COLORS`（目录深蓝、可执行文件亮绿、压缩包红亮、软链接青色）；
     * 预置常用别名（`ll='ls -lah --color=auto'`、`la='ls -A'`、`grep='grep --color=auto'`）；
   - 零依赖、纯 POSIX sh 兼容器件，不拖慢启动与响应速度。

### 3.6 双轨分层交互引导与文档重塑
1. **首次启动设置向导（Setup Wizard）**：
   - 新用户首次打开应用自动进入沉浸式三步引导流程：
     * **第一步：环境与授权检测**：自动探测宿主硬件架构（ARM64 检查）、可用 RAM 与物理存储空间，探测 AVF 硬件虚拟化是否就绪；若可解锁 L2 硬件虚拟化，弹出一次性 ADB/Shizuku 授权指引卡片，并明确告知即便不授权也可通过 L1 软模拟直接运行；
     * **第二步：选择首个 Linux 发行版**：提供直观的发行版对比选单（Alpine Linux：极简轻巧、仅 50MB、适合容器开发与极客；Debian Linux：开箱即用、生态浩瀚、兼容性极佳）；
     * **第三步：推荐配置与一键就绪**：根据设备探测结果预填“最佳推荐配置”，点击“立即初始化”启动弹性下载引擎并完成部署；
2. **重构工程与用户文档（双轨分层）**：
   - **《快速入门指南（小白专版）》**（`docs/user-guide/quick-start.zh-CN.md`）：
     * 0 基础图文教程：从安装 APK、首次向导三步点选、到在手机上敲出第一个 `apk update`；
     * 常见移动端操作贴士：如何调出键盘辅助栏、如何在手机和 Linux 间互相复制粘贴、如何外接蓝牙鼠标；
   - **《高级调优与架构白皮书（开发者专版）》**（`docs/architecture/advanced-tuning-whitepaper.zh-CN.md`）：
     * 深度剖析：QEMU TCG 多线程优化（MTTCG）、AVF pKVM Stage-2 隔离机制、virglrenderer 显存配额调优；
     * 生产力实战：配置 Docker/Podman rootless 容器套件、端口映射搭建本地 Web 开发环境、VS Code Server 远程连接配置。

### 3.7 GPU 硬件加速与宿主 NDK 渲染直通（落实技术调研成果）
1. **宿主 NDK 原生渲染管线升级（`NativeGlesSurfacePresenter`）**：
   - 基于 ADR-0017 定义的 `DoubleBufferedRenderer.SurfacePresenter` 接缝，实现 C++/NDK 原生呈现器；
   - 彻底废除 Java 层 `Bitmap.setPixels()` 和 `lockHardwareCanvas()`；
   - 借助 NDK `ANativeWindow` 与 OpenGL ES 3.0 双缓冲 PBO（Pixel Buffer Object）异步流式上传，解耦 CPU 写入与 GPU 纹理采样，将 1080p 纹理上传耗时压降 80% 以上；
2. **QEMU L1 软模拟档内嵌集成 VirGL 3D（`libvirglrenderer.so`）**：
   - 交叉编译微型 `libvirglrenderer.so`（ARM64-v8a，严格符合 16KB 页对齐与 Android Bionic 标准）；
   - 采用自研轻量 `virgl_android_gl_loader.c`（基于 `eglGetProcAddress` 动态分发），彻底剔除庞大的 `libepoxy` 与 `libdrm` 依赖；
   - 在 QEMU 启动规范（`QemuLaunchSpec`）中增加虚拟 GPU 设备 `-device virtio-gpu-gl-device`；
   - 采用单进程内嵌模型（In-Process JNI Link），杜绝 Termux-virgl 独立进程 C/S 架构在 Android 冻结机制下的连接断开风险；
3. **GPU 三重防御纵深与宿主保护**：
   - **语法与边界解析**：`virglrenderer` 严格解析 TGSI/SPIR-V 指令，过滤非法寄存器越界访问；
   - **EGL 驱动鲁棒性保护**：强制声明 `EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT` 与 `EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT = EGL_LOSE_CONTEXT_ON_RESET_EXT`，当 Guest 死循环着色器引发 GPU 超时时，仅重置局部 EGL 上下文，杜绝 Android 宿主崩溃或整机重启；
   - **显存配额守卫（Memory Quota Sentinel）**：宿主对 VirGL 显存分配施加动态上限（默认不超过 1024MB），超限拒绝分配，防止触发系统级 LMK；
4. **Guest 操作系统 Mesa virgl 驱动适配**：
   - Guest 侧（Alpine / Debian）在 Wayland 桌面环境中启用 Mesa virgl Gallium 驱动（`virgl_dri.so`）；
   - 使得 Linux Guest 内的 WebGL、现代浏览器、轻量 3D 应用享受宿主物理 GPU 的硬件算力支持。

---

## 4. 用户故事全集（User Stories）

1. **US-19（作为颜值与体验敏感型用户）**：我想打开 Orchestella Studio 看到一个符合 Android 15/17 Material 3 设计规范、带有动态壁纸取色和流畅动效的现代仪表盘，而不是简陋的工程师原型按钮，让我感受到专业桌面级应用的精致感。
2. **US-20（作为大内存平板用户）**：我拥有一台 16GB 运存的 Android 平板，我想在创建虚拟机时把内存调到 6GB、CPU 设为 6 核，以便在 Linux 桌面中流畅编译大型 Rust 项目，不被硬编码的 2GB 内存卡死。
3. **US-21（作为小内存紧凑手机用户）**：我使用的是一台 6GB 内存的紧凑型手机，我想将 Linux 实例的 RAM 限制在 1GB 并开启防 OOM 保护，防止后台应用和宿主系统因为内存不足被 Android 系统杀掉。
4. **US-22（作为日常使用开发者）**：当我的某个测试实例配置错误损坏、或临时测试完毕不再需要时，我想长按卡片并在菜单中选择“彻底删除”，安全清理掉私有存储里几个 GB 的磁盘镜像，释放手机宝贵的存储空间。
5. **US-23（作为系统安全性敏感用户）**：当虚拟机正在后台运行编译任务时，我希望应用禁止我误点“删除实例”按钮，避免在磁盘正在写入时发生数据破坏或文件句柄锁死。
6. **US-24（作为国内弱网环境用户）**：我在较差的移动网络或校园网下首次下载 Debian 镜像时，如果中途网络断开，我希望重连后能够从断点处继续下载，而不是每次都从 0% 重新下几百兆文件，避免浪费时间和流量。
7. **US-25（作为跨境访问受限用户）**：当遇到 GitHub 主服务器连接超时或阻断时，我希望下载引擎能自动切换到备用国内高速镜像源继续下载，无需我自己去查教程改 Host 或换链接。
8. **US-26（作为深度终端极客）**：我想在 `TerminalActivity` 中看到类似 VS Code 终端的色彩高亮（ANSI 256 色/真彩色）和漂亮的 Starship 提示符，让我在手机上运行 `ls`、`git status`、`htop` 时赏心悦目、信息一目了然。
9. **US-27（作为移动端打字用户）**：手机输入法没有 `Esc`、`Tab` 和方向键，每次在 vim 里退出编辑模式或在命令行补全路径都极其痛苦；我希望在键盘上方有一排常驻的快捷辅助键，点击一下就能发送 `Esc` 或 `Tab`。
10. **US-28（作为刚接触虚拟化的新手）**：我第一次打开应用时不知道什么是 AVF，也不知道选 Alpine 还是 Debian；我希望应用提供一个友好的三步向导，帮我自动检测手机性能、推荐最佳配置，一键把环境拉起来。
11. **US-29（作为专业运维与系统开发者）**：我想查阅专门针对高级参数调优（如 QEMU MTTCG、vsock 性能、显存配额、Docker 配置）的技术白皮书，以便根据我的特定工作流进行深度定制。
12. **US-30（作为 3D 渲染与轻量设计用户）**：当我在 Linux 桌面里打开 Blender 预览 3D 模型或打开 WebGL 测试网页时，我希望画面帧率在 30 帧以上，手机后盖不要迅速滚烫降频，通过宿主 GPU 硬件加速分担 CPU 的计算压力。

---

## 5. 可观察的性能指标与 SLA 基准（Performance & SLA Metrics）

所有指标必须可在 Android 目标测试机（如 HONOR 80 GT，骁龙 8+ Gen 1）上量化测量，拒绝无法验证的空洞承诺：

| 指标维度 | 目标基准（Target SLA） | 观测与测量方法 | 劣于基准的处理策略 |
| :--- | :--- | :--- | :--- |
| **仪表盘冷启动首帧** | $\le 120\text{ ms}$（从 onCreate 到首帧绘制完毕） | Android Profiler / `adb shell am start -W` | 延迟非必要组件探测，采用异步线程加载实例列表 |
| **下载引擎断点恢复成功率**| $100\%$（服务端支持 Range 条件下） | 模拟弱网手动 kill 网络连接 5 次并断点重连测试 | 校验 HTTP 206 响应码，若服务端降级 200 则安全重置 |
| **镜像 Failover 切换时延**| $\le 3\text{ 秒}$（主节点超时判定至备用节点响应） | 网络请求时间戳打点日志 | 采用非阻塞 Socket 并将连接超时严格收敛在 3000ms |
| **实例物理删除彻底率** | $100\%$ 回收，私有空间泄漏 $0\text{ 字节}$ | 删除前后对比 `StatFs.getAvailableBytes()` | 递归清理所有关联 `.raw`、`.swap` 与 `.properties` 句柄 |
| **终端文本刷新延迟** | 键盘按键至字符上屏 $\le 25\text{ ms}$ | 高精度纳秒打点比对 | 采用轻量 Spannable 批处理更新，消除全量文本重排 |
| **GPU 3D 基准跑分** | `glmark2-es2` $\ge 30\text{ FPS}$（软渲染 $<5\text{ FPS}$）| Guest 内部执行 `glmark2-es2 --fullscreen` | 优化 TGSI JIT 编译缓存，开启 GLES 批处理 |
| **GPU 呈现上屏耗时** | 单帧 PBO 异步上传与呈现 $\le 3.5\text{ ms}$ | 原生渲染线程纳秒级打点 | 保持双 PBO 乒乓流，消除 CPU 显存同步等待 |
| **宿主整机防崩溃率** | $100\%$（Guest 恶意 GPU 着色器死循环不引起系统重启）| Guest 执行死循环 Shader，监控宿主 Logcat | 触发 `GL_LOSE_CONTEXT_ON_RESET_EXT` 安全重置局部上下文 |
| **宿主内存防御线** | 虚拟机 RAM 配置硬顶保护，宿主空闲保留 $\ge 1.8\text{ GB}$| `ActivityManager.MemoryInfo.availMem` 监控 | 严格拦截超额配置，滑块红色锁定 |

---

## 6. 验收标准（Acceptance Criteria, AC）

### AC-1：策略中立硬件参数契约与宿主硬件探测（Hardware Config & Host Detector）
- `:runtime:contract` 交付不可变实体 `VmHardwareConfig`，字段包含 `cpuCores`（1~8）、`ramMegabytes`（512~8192）、`diskCapacityBytes`（2GB~64GB）、`gpuAccelerationEnabled`；
- 在 `:app` 交付 `HostHardwareDetector`，准确探测物理 CPU 核心与可用空闲 RAM，计算推荐配置与危险防 OOM 阈值；
- 纯 JVM 单元测试覆盖各种边界输入（超限、负数、安全滑块计算算法）100% 通过。

### AC-2：实例生命周期物理删除与安全熔断闭环（Lifecycle Delete & Cascade Purge）
- `InstanceLifecycle.Store` 正式新增 `void delete(String id) throws IOException`；
- `InstanceLifecycle.FileStore` 实现删除前安全熔断检查：实例状态为 `RUNNING` 时抛出 `IllegalStateException` 强行阻断；
- 删除操作级联彻底物理清除 `<id>.raw`、`<id>.properties` 及所有关联临时文件；
- 单元测试验证正常删除、运行态拦截、不存在实例删除、文件物理销毁验证 100% 绿灯。

### AC-3：多源抗阻弹性下载引擎（Resilient Download Engine）
- 交付 `ResilientDownloadEngine`，包含多源镜像通道列表（GitHub 官方源、全球 CDN、国内镜像代理）；
- 支持 HTTP `Range` 头断点续传，网络中断后重新触发可从断点游标向后追加；
- 支持超时自动 Failover 与指数退避重试；
- 集成流式 SHA-256 哈希计算与精准下载速率（MB/s）、剩余时间回调；
- 编写 JVM 模拟网络断流单元测试，断点续传恢复与哈希校验 100% 通过。

### AC-4：Material 3 Expressive Dashboard 仪表盘与卡片交互（Modern Dashboard UI）
- 全面重写 `activity_main.xml`，采用 Material 3 Expressive 风格，支持 Material You 动态色彩提取与沉浸式窗口；
- 实例卡片展示发行版图标、动态呼吸状态徽标（`RUNNING` 绿光动效、`STOPPED` 灰阶、`CRASHED` 珊瑚红）；
- 实例支持长按或点击更多菜单弹出 BottomSheet：支持启动桌面、打开终端、硬件配置、导出备份、物理删除；
- 单元测试覆盖仪表盘状态机与列表刷新事件。

### AC-5：首次启动设置向导与分层文档（Setup Wizard & Tiered Docs）
- 交付三步闭环设置向导 `SetupWizardActivity`，引导用户完成：环境与权限检测 → 发行版选单 → 硬件推荐配置并启动首个实例；
- 输出分层文档：`docs/user-guide/quick-start.zh-CN.md`（小白专版）与 `docs/architecture/advanced-tuning-whitepaper.zh-CN.md`（开发者专版）。

### AC-6：现代终端模拟器与移动辅助键盘栏（Modern Terminal & Extra Keys）
- 升级 `TerminalActivity`，集成 ANSI 256 色与 24-bit TrueColor 解析渲染引擎，默认应用 Dracula 高对比调色盘；
- 终端界面集成常驻移动辅助按键栏（Esc, Ctrl, Alt, Tab, 方向键, 管道符 `|`），支持粘滞组合键状态；
- 单元测试验证 ANSI 颜色转码与按键事件派发。

### AC-7：Guest 现代化美观 Shell 装配（Guest Assets Shell Preset）
- 在 `guest-assets` 中打包轻量 Starship 风格极简提示符与彩色 `LS_COLORS` 预设；
- 在 `droidlayer-desktop-setup.sh` 及首次引导脚本中注入该预设，Alpine 与 Debian 均生效；
- ShellCheck 静态检查零告警，测试脚本断言预设文件正确部署。

### AC-8：宿主 NDK 原生渲染管线（NativeGlesSurfacePresenter）
- 在 `:app` 中实现 `NativeGlesSurfacePresenter`，将 Wayland 软渲染流平滑接入 C++/NDK `ANativeWindow` 与 GLES3 PBO 异步上传；
- 彻底废除 Java `Bitmap.setPixels()` 瓶颈，1080p 单帧呈现耗时 $\le 3.5\text{ms}$，维持 60 FPS 满帧；
- 具备与原有 `DoubleBufferedRenderer.SurfacePresenter` 接口的 100% 契约兼容性。

### AC-9：QEMU 内嵌 VirGL 3D 加速与 EGL 鲁棒性隔离（VirGL Toolchain & Integration）
- 建立 Android NDK 交叉编译构建脚本，产出轻量 `libvirglrenderer.so`（ARM64-v8a，16KB 页对齐，剔除 libepoxy/libdrm）；
- `QemuLaunchSpec` 新增 `-device virtio-gpu-gl-device` 设备挂载；
- 原生 EGL 初始化强制开启 Robustness 上下文隔离与显存配额守卫，防整机崩溃与系统 LMK。

### AC-10：Guest Mesa VirGL 3D 端到端加速打通（End-to-End 3D Acceleration）
- Guest 操作系统加载 Mesa virgl 驱动（`virgl_dri.so`）；
- 端到端验证：在 Guest 内部执行 `glmark2-es2` 跑分达 $30+\text{ FPS}$；
- 原生 Wayland 客户端与 XWayland 均可直接调用硬件加速通道。

---

## 7. 策略中立接缝设计（Seam Design）

本期严格恪守“高内聚、低耦合、最少接缝”的设计原则：

1. **硬件配置接缝（`HardwareConfigSeam`）**：
   - 在 `:runtime:contract` 中定义 `VmHardwareConfig` 实体，为纯不可变 POJO；
   - 执行策略适配器（`QemuLaunchSpec` 与 `AvfInstanceLauncher`）通过 `VmHardwareConfig` 提取参数并组装对应启动命令行，上层 UI 无需感知底层的 `-smp` 或 `-m` 参数。
2. **生命周期存储接缝（`InstanceLifecycle.Store`）**：
   - 维持既有的 `Store` 接口，仅在现有契约上正交扩展 `delete(String id)`；
   - 现有的 `save`、`load`、`list` 语义与调用方完全保持兼容。
3. **下载引擎接缝（`DownloadEngineSeam`）**：
   - 抽象出 `DownloadEngine` 接口：
     ```java
     public interface DownloadEngine {
         void download(List<String> mirrorUrls, Path targetFile, DownloadListener listener) throws IOException;
     }
     ```
   - 隔离具体的网络库与断点处理，未来可无缝替换底层 HTTP 实现。
4. **呈现器接缝（`SurfacePresenter`）**：
   - 直接复用 Phase 2 的 `DoubleBufferedRenderer.SurfacePresenter`；
   - `NativeGlesSurfacePresenter` 作为其原生高效实现，与 Java 版 `HardwareCanvasSurfacePresenter` 形成无缝平滑替代。

---

## 8. 范围界定与排除项（Out of Scope）

为了确保 M4 阶段的高质量闭环交付，明确划定以下非目标：

1. **AOSP gfxstream 官方私有协议深度绑定**：
   - 遵循调研报告合规审计，本期不将带有法务审查风险的 AOSP 专有 gfxstream 作为核心依赖，优先采用开源中立的 virglrenderer（MIT 许可）；
2. **全自动虚拟机热迁移（Live Migration）**：
   - 移动单机环境下不考虑跨宿主机的内存热迁移，仅维护单机冷保存与恢复；
3. **多实例同时并发运行（Multi-Instance Concurrency）**：
   - 严格遵循 ADR-0016 决策，M4 依然维持“单活动运行实例”原则，多实例列表仅用于管理与切换，禁止两台虚拟机同时开机抢占手机有限的 CPU/内存资源；
4. **任意外部 FUSE 动态挂载**：
   - 继续沿用已验证的 Downloads 共享目录模式，不引入实验性外部 FUSE 驱动。

---
*规格归档：`.scratch/droidlayer-platform/spec-m4.md`*

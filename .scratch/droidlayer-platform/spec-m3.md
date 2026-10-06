# Orchestella Studio Phase 3（M3 里程碑）— 产品规格说明书（Spec）

Labels: ready-for-agent  
Version: 3.0（2026-10-06）  
依据：ADR-0001 ~ ADR-0018 + `docs/research/2026-10-06-phase3-audio-clipboard-input.md`  
变更纪律：本规格说明书的每个承诺必须可追溯到 ADR-0013 与 ADR-0018；未经架构决议，不得擅自修改技术边界或新增隐性承诺。

---

## 1. 问题陈述（Problem Statement）

在 Phase 1（M1 终端底座）与 Phase 2（M2 Wayland 原生移动图形桌面）中，Orchestella Studio 成功构建了在 Android 免 Root 环境下 60 FPS 满帧渲染、移动触控手势、虚拟快捷键栏以及 Android IME 中文输入法无损穿透的完整 Linux 移动运行环境。

然而，在日常开发与多任务生产力使用中，移动宿主（Android Host）与虚拟机（Linux Guest）之间仍存在显著的“体验孤岛”：
1. **剪贴板完全隔离**：移动端复制的 Git 仓库链接、API Token 或命令无法直接粘贴进 Linux 终端与桌面浏览器；Linux 桌面内复制的编译错误日志或代码片段也无法粘贴回宿主应用，用户被迫在小屏幕上手动打字或迂回中转；
2. **多媒体与系统音频静音**：Linux 内运行的 Web 浏览器视频、多媒体播放器、编译完成提示音与终端蜂鸣均处于静音状态，缺乏完整的桌面听觉反馈；
3. **物理鼠标体验残缺**：接入蓝牙键盘/鼠标或 USB-OTG 鼠标时，Android 系统的默认鼠标光标受限于手机屏幕物理边界，容易误触系统底栏导航手势或状态栏下滑，且无法向虚拟机提供原生 PC 级的相对指针位移（Relative Pointer Displacement）；
4. **跨端文件交互摩擦**：宿主向虚拟机传入文件需依赖命令行或手动搬运，缺乏直观的拖拽操作（Drag & Drop）。

因此，M3 里程碑必须彻底打破这些系统壁垒，交付**双向防环剪贴板同步、极简低延迟音频流式输出、物理鼠标相对指针捕获以及 SAF 文件拖拽暂存分发**。

---

## 2. 解决方案概述（Solution Overview）

**Orchestella Studio M3**：在 Android 免 Root 环境下，构建系统级深度互通、音频流式输出与外设直通体系。

核心架构特征包括：
1. **策略中立的剪贴板与音频契约（`:runtime:contract`）**：
   - 扩容 `FullLinuxContract.Capability`：`AUDIO_OUTPUT`、`CLIPBOARD_SYNC`、`POINTER_CAPTURE`；
   - 扩容 `BridgeControl` 线协议命令：`DROIDLAYER/CLIPBOARD PUT base64:<data>\n` 与 `DROIDLAYER/CLIPBOARD GET\n`；
   - 定义策略中立的 `AudioStreamContract`（固定 PCM 16-bit 48000Hz 立体声格式）与扩容 `PointerEvent`（相对位移与物理按键掩码）；
2. **Android 宿主高性能音频与剪贴板管理（`:app`）**：
   - **LowLatencyAudioPlayer**：基于 Android 原生 `AudioTrack`（`STREAM_MUSIC`, `MODE_STREAM`）构建双缓冲环形流式播放流水线，接入自适应时钟漂移补偿算法与音频焦点管理，实现端到端延迟 $\le 80\text{ms}$；
   - **ClipboardBridgeManager**：结合前台焦点监听（`OnPrimaryClipChangedListener` + `onWindowFocusChanged`）与 SHA-256 哈希指纹破环状态机，实现 100% 确定性、无死循环的双向剪贴板同步；
   - **PointerCaptureHandler**：接入 Android API 26+ `View.requestPointerCapture()`，隐藏宿主光标，将原始无边界相对位移 $(\Delta x, \Delta y)$ 直通给 Wayland 指针通道，配合快捷键热键释放；
   - **FileDropTargetListener**：基于 `View.OnDragListener` 接入系统级文件拖入，安全异步抽取 SAF Content URI 写入共享目录暂存池并派发通知。
3. **Guest 操作系统极简音频与守护代理（`guest-assets`）**：
   - 部署极简无头 PulseAudio Daemon（`module-null-sink` + `module-simple-protocol-tcp` 监听本地端口 9104），常驻内存仅 $\sim 10\text{ MB}$；
   - 部署轻量剪贴板守护代理（`wl-paste --watch` + `wl-copy`），与宿主 BridgeControl 协同防环；
   - 在 `droidlayer-desktop-setup` 自动化脚本中注入 Alpine 与 Debian 的音频及剪贴板套件装配；
4. **AVF 硬件虚拟化专有 vsock 通道对齐（`:runtime:engine-avf`）**：
   - 在 AVF 硬件虚拟化档开辟专有 vsock 端口 9104 作为音频数据通道，剪贴板事件复用 9102 控制面 vsock 通道，确保与 QEMU 档功能与性能 100% 对齐。

---

## 3. 用户故事（User Stories）

1. **US-11（作为开发者）**：我想在手机微信或浏览器中复制一段 GitHub 仓库链接或命令，切回 Orchestella Studio 时能直接在 Linux 终端或桌面编辑器中按 `Ctrl+V` 粘贴出来，无需手动二次输入。
2. **US-12（作为开发者）**：我想在 Linux 终端或图形 IDE 中选中文本并复制后，切回手机宿主备忘录或聊天软件中能直接粘贴，以便将编译日志或排错信息快速分享给同事。
3. **US-13（作为安全与稳定敏感用户）**：我想复制上百行长代码或复杂包含特殊符号的脚本时，剪贴板同步不会发生截断、乱码，也不会因为两端同时修改而引发手机卡死的死循环（Ping-Pong Loop）。
4. **US-14（作为多媒体与 Web 用户）**：我想在 Linux 桌面内的 Firefox 或 Chromium 浏览器中观看在线视频或收听播客时，声音能够平滑、同步地从手机扬声器或蓝牙耳机播放出来，没有刺耳的爆音或明显音画不同步（延迟 $<80\text{ms}$）。
5. **US-15（作为终端极客用户）**：我想在后台执行耗时编译或测试脚本时，听到系统的 `printf '\a'` 终端提示音或完成提示音频，以便及时获知任务状态。
6. **US-16（作为平板外接键鼠用户）**：我想将蓝牙鼠标或 USB-OTG 鼠标连接到手机/平板时，光标不再被 Android 屏幕边缘卡住，能在 Linux 桌面上像在 PC 上一样自由移动、框选文本并顺畅使用滚轮和鼠标右键。
7. **US-17（作为多任务外设用户）**：当我在全屏 Linux 桌面中完成操作想切回手机微信时，能通过按下 `Ctrl+Alt` 快捷键或点击屏幕顶部浮标立即释放鼠标控制权，无缝操作 Android 宿主系统。
8. **US-18（作为跨端文件传输用户）**：我想在手机分屏或悬浮窗状态下，从系统“文件”管理应用中拖动一份 PDF 或压缩包直接丢进 Orchestella Studio 桌面窗口，文件能自动保存到共享暂存目录并在 Linux 桌面中弹出就绪提醒。

---

## 4. 可观察的性能指标（Observable Performance Metrics）

所有指标必须可在 Android 实机（如基准机 HONOR 80 GT）上量化测量，拒绝不可证伪的模糊承诺：

| 指标维度 | 目标基准（Target SLA） | 观测与测量方法 | 劣于基准的处理策略 |
| :--- | :--- | :--- | :--- |
| **音频端到端延迟** | $\le 80\text{ ms}$（从 Guest 产生音频到宿主 DAC 播放） | 音视频同步测试视频（音画对齐闪烁）或毫秒级时间戳比对 | 缩减 AudioTrack 缓冲区，动态微调水位上限至 60ms |
| **音频卡顿/丢帧率** | 播放状态下欠载（Underrun）率 $\le 0.5\%$ | Android `AudioTrack.getUnderrunCount()` 监控 | 启用自适应线性淡入插值与微量缓冲区拉伸 |
| **剪贴板双向同步延迟**| $\le 50\text{ ms}$（复制完成至远端就绪） | 两端时间戳日志打点（Timestamp Delta） | 剪贴板事件直接通过长连接桥接即时发送，杜绝轮询 |
| **剪贴板有效载荷上限**| 单次最大 $256\text{ KB}$ UTF-8 文本 | 契约单元测试与边缘数据包注入 | 超限时直接拒绝返回 `payload-too-large`，防内存溢出 |
| **宿主内存增量** | 常驻内存增量 $\le 15\text{ MB}$（AudioTrack + Bridge） | Android Profiler / dumpsys meminfo | 环形缓冲区定长复用，单例释放机制 |
| **Guest 侧音频内存** | PulseAudio 极简进程常驻 $\le 15\text{ MB}$ | Guest 内部 `ps aux` / `free -m` | 禁用一切非必要 PulseAudio 模块 |
| **CPU 负载（音频播放中）**| QEMU 档 CPU 占用 $\le 3\%$；AVF 档 $\le 1\%$ | Android CPU Profiler / top | 避免任何软件重采样，统一固定 48kHz 直通 |

---

## 5. 验收标准（Acceptance Criteria, AC）

### AC-1：策略中立契约与线协议扩展（Contract Compliance）
- `:runtime:contract` 扩容 `Capability.AUDIO_OUTPUT`、`Capability.CLIPBOARD_SYNC`、`Capability.POINTER_CAPTURE`；
- `BridgeControl` 正式集成 `DROIDLAYER/CLIPBOARD PUT base64:<data>\n` 与 `DROIDLAYER/CLIPBOARD GET\n`，包含 $256\text{KB}$ 安全上限过滤与 Base64 编解码；
- 定义 `AudioStreamContract` 接口，表达标准 PCM 16-bit 48000Hz 双声道流规范；
- 契约层 JVM 纯单元测试保持 100% 覆盖，纯 Java 编写，无平台污染。

### AC-2：Android 宿主 AudioTrack 低延迟音频流播放器（AudioTrack Player）
- 在 `:app` 交付 `LowLatencyAudioPlayer`，封装 Android 原生 `AudioTrack`（`STREAM_MUSIC`, `MODE_STREAM`）；
- 实现动态时钟漂移补偿算法，维持环形缓冲水位在 $40\text{ms} \sim 80\text{ms}$，端到端音频延迟 $\le 80\text{ms}$；
- 接入 `AudioManager.OnAudioFocusChangeListener`，电话呼入时平滑降低音量或暂停，挂断后恢复；
- 具备完整的生命周期管控，实例销毁时立即释放底层音频硬件资源。

### AC-3：Android 宿主剪贴板双向同步与防环机制（Clipboard Bridge）
- 在 `:app` 交付 `ClipboardBridgeManager`，接入 `OnPrimaryClipChangedListener` 与 Activity 焦点感知；
- 维护双向 SHA-256 哈希指纹与“远端应用中”标志位，100% 杜绝 Ping-Pong 死循环；
- 支持宿主到 Guest、Guest 到宿主的双向文本同步，单次支持最高 256KB 纯文本/代码段，同步延迟 $\le 50\text{ms}$；
- 当 Activity 退入后台时自动注销剪贴板监听，消除后台权限告警与无谓唤醒。

### AC-4：外部鼠标原生相对指针捕获与 Wayland 映射（Pointer Capture）
- 在 `:app` 桌面视口中接入 Android API 26+ `View.requestPointerCapture()`；
- 点击桌面视图自动获取指针捕获并隐藏宿主鼠标光标，解除屏幕边缘碰撞限制；
- 在 `onCapturedPointerEvent` 中精确提取相对位移向量 $(\Delta x, \Delta y)$，映射并派发至 Wayland 相对指针协议；
- 提供按下 `Ctrl+Alt` 或悬浮操作栏按钮平滑调用 `releasePointerCapture()` 释放鼠标控制权。

### AC-5：Guest Linux 极简音频汇聚端与剪贴板守护代理（Guest Assets）
- 在 `guest-assets` 中提供极简 PulseAudio 配置文件，以 null-sink + simple-protocol-tcp 形式在端口 9104 暴露 PCM 流；
- 提供轻量剪贴板守护代理脚本，结合 `wl-paste --watch` 与 `wl-copy` 挂接 BridgeControl；
- 在 `droidlayer-desktop-setup.sh` 中为 Alpine Linux 与 Debian 自动化配置并拉起音频与剪贴板守护；
- ShellCheck 零告警通过，纯 JVM 装配测试通过。

### AC-6：AVF 硬件虚拟化 vsock 通道对齐（AVF Parity）
- `:runtime:engine-avf` 为 vsock 专有端口 9104 开辟低开销音频流转发通道；
- 剪贴板事件经由 9102 控制面 vsock 通道传输；
- 行为与 QEMU 软模拟档完全对齐，契约与集成测试全绿通过。

### AC-7：宿主文件拖入暂存共享与通知（File Drag & Drop Staging）
- 在 `DesktopActivity` 接入 `View.OnDragListener` 捕获跨应用拖入文件；
- 异步读取 SAF Content URI 并流式写入共享目录暂存区（`/mnt/shared/downloads/Inbound/`）；
- 写入完成后通过 BridgeControl 派发通知提醒用户，文件完整无损。

---

## 6. 范围界定与排除项（Out of Scope）

为了确保 M3 阶段集中攻克系统互通核心痛点，严格划定以下非目标：

1. **麦克风录音输入（Host → Guest Audio Input）**：
   - 遵循 ADR-0013，M3 仅交付 Guest → Host 音频播放，麦克风需要敏感的 `RECORD_AUDIO` 隐私权限且使用场景较弱，本期明确不纳入；
2. **剪贴板大图/富文本二进制文件**：
   - 本期首发严格锁定为 UTF-8 文本与代码段（上限 256KB），富文本图像剪贴板留待后续增强；
3. **原生 FUSE 任意目录动态双向映射**：
   - 保持复用 ADR-0009 已验证的共享 Downloads 目录暂存池，不在本期引入实验性免 Root FUSE 驱动；
4. **硬件 3D GPU 加速（VirtIO-GPU Venus / Turnip）**：
   - 保持为 **M4 里程碑** 规划不变。

---
*规格归档：`.scratch/droidlayer-platform/spec-m3.md`*

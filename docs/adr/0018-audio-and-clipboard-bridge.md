---
status: accepted
date: 2026-10-06
---

# ADR-0018: 系统级深度互通、低延迟音频流输出与外设指针捕获架构

## Context

在 Phase 2（M2 里程碑）中，Orchestella Studio 成功交付了基于 Wayland 原生协议的 60 FPS 移动图形桌面、移动触控手势以及 Android IME 中文输入法无损穿透（ADR-0017，Ticket 38–43 全量合流）。
然而，桌面使用体验依然面临宿主与虚拟机之间的“体验孤岛”：
1. **剪贴板完全隔离**：移动宿主与 Linux 虚拟机无法共享剪贴板，文本和代码段无法跨端复制粘贴；
2. **音频静音**：遵照 ADR-0013 早期规划，音频输出尚未落地，桌面多媒体、网页视频与终端提示音均处于静音状态；
3. **物理外设体验受限**：当接入蓝牙键鼠或 USB-OTG 鼠标时，Android 系统的默认鼠标捕获受限于屏幕物理边界并产生双光标现象，缺乏 PC 级无边界相对指针位移；
4. **跨端文件交互摩擦**：宿主向虚拟机传入文件需依赖命令行或手动搬运，缺乏直观的拖拽操作。

根据 2026-10-06 前沿专项调研（`docs/research/2026-10-06-phase3-audio-clipboard-input.md`）以及与用户的架构需求推敲（`/grill-with-docs`），本决议正式固化 Phase 3（M3 里程碑）系统级互通、音频与外设集成的顶层设计方案。

---

## Decision

确立“**前台焦点防环剪贴板、极简 PulseAudio 单向 PCM 输出、原生指针捕获直通、共享暂存池文件拖拽**”的核心架构体系，严格划定模块职责边界：

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                   Orchestella Studio M3 系统深度互通与音频外设架构全景                  │
└────────────────────────────────────────────────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. 宿主服务与呈现层 (:app)                                                            │
 │    - ClipboardBridgeManager (OnPrimaryClipChangedListener + 焦点感知 + SHA256 抑环)   │
 │    - LowLatencyAudioPlayer (原生 AudioTrack, 48kHz 立体声, 动态时钟漂移补偿)           │
 │    - PointerCaptureHandler (View.requestPointerCapture, 提取相对位移 Δx, Δy)           │
 │    - FileDropTargetListener (View.OnDragListener, SAF URI 提取与暂存写入)             │
 └──────────────────────────────────────────┬───────────────────────────────────────────┘
                                            │ 依赖策略中立接口
 ┌──────────────────────────────────────────▼───────────────────────────────────────────┐
 │ 2. 策略中立契约层 (:runtime:contract)                                                 │
 │    - FullLinuxContract.Capability 扩容: AUDIO_OUTPUT, CLIPBOARD_SYNC, POINTER_CAPTURE    │
 │    - BridgeControl 命令集扩容: DROIDLAYER/CLIPBOARD PUT base64:<data>                │
 │    - AudioStreamContract: 策略中立音频流句柄与采样格式定义 (PCM 16-bit 48000Hz 立体声)  │
 │    - InputEvent: PointerEvent 扩容相对位移字段与物理按键掩码                          │
 └─────────────────────┬──────────────────────────────────────────┬─────────────────────┘
                       │ 传输通道 (LocalSocket / TCP 9104)         │ 传输通道 (vsock 9104/9102)
 ┌─────────────────────▼────────────────┐        ┌────────────────▼─────────────────────┐
 │ 3a. QEMU 软模拟档 (L1)                │        │ 3b. AVF 硬件虚拟化档 (L2)            │
 │ - 宿主与 Guest 共享物理 Android 内核   │        │ - 独立 EL2 Stage-2 硬件隔离虚拟机    │
 │ - 音频流: LocalSocket @droidlayer-audio│        │ - 音频流: 专有 vsock 端口 9104 流式传输│
 │ - 剪贴板: 经 BridgeControl 本地套接字  │        │ - 剪贴板: 经 vsock 端口 9102 控制面通道│
 └─────────────────────┬────────────────┘        └────────────────┬─────────────────────┘
                       └────────────────────────┬─────────────────┘
                                                │
 ┌──────────────────────────────────────────────▼───────────────────────────────────────┐
 │ 4. Guest 操作系统层 (Linux Guest / guest-assets)                                     │
 │    - 极简 PulseAudio Daemon (module-null-sink + module-simple-protocol-tcp 端口 9104)  │
 │    - Guest 剪贴板守护代理: wl-paste --watch + wl-copy + 哈希指纹破环                   │
 │    - 外设集成: labwc 原生接入 zwp_relative_pointer_v1 相对指针位移                    │
 │    - 发行版自动化脚本扩容: droidlayer-desktop-setup 注入音频与剪贴板依赖 (Alpine/Debian)│
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

### 1. 剪贴板双向无损同步契约与防环机制
- **数据范围与边界限制**：
  - M3 首期聚焦 **UTF-8 纯文本与代码段**；
  - 单次同步有效载荷上限为 **256 KB**，超过此限制将确定性拒绝并返回 `DROIDLAYER/ERROR payload-too-large\n`；
  - 暂不扩展富文本图像文件（留作后续增强）；
- **生命周期与安全边界**：
  - 严格遵守 Android 14/15/16 剪贴板安全限制，仅在宿主 Activity 处于前台且持有焦点时（`onWindowFocusChanged(true)`）激活同步；
- **哈希指纹防环状态机（Echo Suppression State Machine）**：
  - 宿主与 Guest 双向维护最后同步内容的 SHA-256 哈希值；
  - 写入本地系统剪贴板时打上“正在应用远端更新”保护标志，确保本地触发的变更监听不会将相同数据反弹回远端，100% 杜绝 Ping-Pong 死循环。

### 2. 音频子系统单向流式输出架构
- **音频流方向与参数规范**：
  - 遵循 ADR-0013，M3 **仅做 Guest → Host 的单向音频流输出**，麦克风（录音）明确排除；
  - 统一采样格式：采样率 $48,000\text{ Hz}$，采样深度 $16\text{ bit}$ 有符号小端（`s16le`），双声道立体声（Stereo），单秒吞吐量固定为 $192\text{ KB/s}$；
- **Guest 音频服务栈选型**：
  - 选用极简无头 PulseAudio Daemon（内存占用仅 $\sim 10\text{ MB}$，零 D-Bus/systemd 依赖），配置 `module-null-sink` 与 `module-simple-protocol-tcp` 监听本地端口 9104；
- **Android 宿主 AudioTrack 播放器**：
  - 基于 Android 原生 `AudioTrack`（`STREAM_MUSIC`, `MODE_STREAM`）构建双缓冲环形流式播放流水线；
  - 引入**自适应动态水位漂移补偿算法**，缓冲水位稳定在 $40\text{ms} \sim 80\text{ms}$，端到端音频延迟 $\le 80\text{ms}$；
  - 接入 `AudioManager.requestAudioFocus()` 实现电话呼入与多媒体抢占时的平滑避让。

### 3. 外部物理键鼠直通与相对指针捕获
- **指针捕获机制**：
  - 在 Android 8.0+（API 26+）上调用 `View.requestPointerCapture()`；
  - 自动隐匿 Android 系统默认鼠标光标，解除屏幕物理边缘碰撞限制；
  - 从 `onCapturedPointerEvent(MotionEvent)` 中提取原始位移向量 $(\Delta x, \Delta y)$ 并映射至 Wayland `zwp_relative_pointer_v1` 协议；
- **双模激活与释放策略**：
  - 点击桌面窗口内部自动激活指针捕获；
  - 用户按下组合快捷键（`Ctrl + Alt`）或点击悬浮操作栏“释放鼠标”时调用 `releasePointerCapture()`，安全回归宿主控制。

### 4. SAF 跨端文件拖拽与共享机制
- **拖拽捕获**：
  - 在 `DesktopActivity` 中注册 `View.OnDragListener` 捕获 `DragEvent.ACTION_DROP`；
  - 异步从 `ClipData` 中提取 SAF Content URI；
- **暂存分发流（Drop-to-Staging Pipeline）**：
  - 宿主在后台将拖入文件复制至共享目录的暂存子文件夹（如 `/mnt/shared/downloads/Inbound/`）；
  - 通过 Bridge 向 Guest 派发通知或弹窗，用户可即时在 Linux 桌面文件管理器中双击打开。

---

## Considered Options

1. **剪贴板首期支持大图与富文本文件（否决）**：
   - 理由：图像多 MIME 格式（PNG/JPEG/BMP）在 Wayland 与 Android 间需要多轮交互协商，且几十兆大图在控制流传输会引发显著的内存抖动与 GC 停顿；256KB 纯文本已能满足 99% 的核心开发场景。
2. **音频采用 PipeWire + WirePlumber 全功能栈（否决）**：
   - 理由：在 Alpine Linux 极简环境和无 systemd 环境下，PipeWire 强依赖复杂的会话管理器（WirePlumber/Lua）与 D-Bus，常驻内存高达 40MB–60MB；而极简 PulseAudio 仅需 10MB 内存且配置极度稳定。
3. **增加麦克风录音全双工支持（否决）**：
   - 理由：严格恪守 ADR-0013。麦克风录音需要敏感的 `RECORD_AUDIO` 隐私权限，在移动虚拟化中缺乏实际高频场景，且双向音频将大幅增加时钟同步与回声消除成本。
4. **自研 FUSE 驱动实现 SAF 动态双向挂载（否决）**：
   - 理由：在免 Root 限制下，无法为虚拟机挂载动态任意 FUSE 块设备；复用 ADR-0009 已验证的共享 Downloads 目录暂存池是成本最低、最稳健的方案。

---

## Consequences

1. **积极收益**：
   - 宿主与虚拟机无缝打通剪贴板，彻底消除复制粘贴壁垒；
   - Linux 虚拟机获得低延迟（$<80\text{ms}$）、低 CPU 占用的原生听觉输出；
   - 物理鼠标获得无边界 PC 级相对位移操控手感；
   - 跨端文件支持拖拽即用。
2. **工程代价与边界约束**：
   - `:runtime:contract` 需扩容 `AUDIO_OUTPUT`、`CLIPBOARD_SYNC`、`POINTER_CAPTURE` 能力，并为 `BridgeControl` 增加剪贴板线协议命令；
   - `:app` 需实现 `AudioTrack` 环形缓冲播放器与 `ClipboardBridgeManager`；
   - AVF 硬件虚拟化档需对齐 vsock 专有端口 9104 音频流转发。
3. **纪律红利**：
   - 契约层依旧保持 100% 纯 Java，纯 JVM 测试毫秒级全绿回归；
   - 零新增隐私敏感权限，保持零遥测与纯净沙盒承诺。

---
*关联工单：Ticket 44–48（M3 里程碑）*

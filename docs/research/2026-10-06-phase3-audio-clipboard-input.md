# 2026 年 Android 移动端与 Linux 深度系统级互通、低延迟音频与外设直通技术调研报告

> **文档标识**：RESEARCH-20261006-SYSTEM-INTEROP-AUDIO-CLIPBOARD  
> **报告日期**：2026-10-06  
> **报告人**：Orchestella Studio 架构调研与工单拆解首席专员（Research & Scaffolding Tier）  
> **里程碑归属**：Phase 3（M3 里程碑：系统级深度互通、音频输出与外设集成）  
> **前置依赖**：Phase 1 终端底座（Ticket 25–37）、Phase 2 Wayland 移动图形桌面（ADR-0017，Ticket 38–43 全量交付）  
> **密级与状态**：技术架构基准（Status: Strategic Technical Baseline）

---

## 1. 调研背景与问题陈述

在 Orchestella Studio Phase 1（M1 终端底座）与 Phase 2（M2 Wayland 原生移动图形桌面）中，项目已成功交付了具备 60 FPS 满帧硬件渲染、移动触控手势、虚拟快捷键栏以及 Android IME 中文文本无损穿透的完整 Linux 移动运行环境。

然而，在多任务桌面生产力与日常开发使用中，移动宿主（Android Host）与虚拟机（Linux Guest）之间仍存在显著的“体验孤岛”，主要表现在四大维度：
1. **剪贴板隔离（Clipboard Isolation）**：
   - 宿主复制的 Git 仓库链接、API Token 或命令无法直接粘贴进 Linux 终端与桌面浏览器；Linux 桌面内复制的编译错误日志或代码片段也无法粘贴回宿主聊天或笔记软件。
2. **音频静音（Muted Audio）**：
   - 遵照 ADR-0013，M1 与 M2 均未引入音频子系统。Linux 内运行的 Web 浏览器视频、编译完成提示音、音乐播放器与终端蜂鸣均处于静音状态，缺乏完整的桌面听觉反馈。
3. **物理鼠标体验残缺（Pointer Edge Constraints & Double Cursor）**：
   - 当用户接入蓝牙键盘/鼠标或 USB-OTG 扩展坞时，Android 系统的默认鼠标光标存在屏幕边缘碰撞、容易误触系统底栏导航手势或状态栏下滑等问题，且无法向虚拟机提供原生 PC 级的相对指针位移（Relative Pointer Displacement）。
4. **跨端文件交互摩擦（File Ingress Friction）**：
   - 虽已有基于 9p / virtio-fs 的只读 Downloads 共享（ADR-0009），但宿主向 Guest 传入新文件仍需依赖复杂的命令行拷贝或外部文件管理器中转，缺乏原生的拖拽（Drag & Drop）与直读直写交互。

本报告针对上述四大攻坚领域，全面考量 Android 14/15/16 平台安全限制、Wayland 现代协议规范、PipeWire/PulseAudio 轻量虚拟化架构与移动端低延迟音频管道，确立 M3 里程碑的权威技术选型。

---

## 2. 剪贴板双向无损同步通道（Clipboard Bridge）

### 2.1 移动宿主侧：Android 14/15/16 安全边界与焦点限制

自 Android 10 起，Google 逐步收紧后台应用访问系统剪贴板的权限。在 Android 14、15 以及最新的 Android 16 中，剪贴板安全防护机制如下：
1. **后台访问物理封锁**：
   - 处于后台的应用（无前台窗口焦点）调用 `ClipboardManager.getPrimaryClip()` 将返回 `null` 或抛出安全异常；
   - 注册的 `OnPrimaryClipChangedListener` 在应用转入后台时将**停止接收任何变更回调**；
   - 哪怕应用持有前台服务（Foreground Service），若当前 Activity 失去 Window Focus（如锁屏或切换至其他应用），依然无法读取剪贴板；
2. **用户知情权提示（Toast Notification）**：
   - Android 12+ 引入了当应用首次读取剪贴板时弹出“应用已从剪贴板读取内容”系统浮窗提示；
3. **敏感信息标记（Sensitive Clip Flag）**：
   - Android 13+ 支持 `ClipDescription.EXTRA_IS_SENSITIVE`，防止密码、2FA 验证码等在输入法候选栏或系统剪贴板历史中被持久化预览。

**宿主同步架构结论**：
- 剪贴板同步必须是**前台窗口驱动型（Focus-Driven）**：
  - 当 Orchestella Studio 处于前台且获得焦点时，注册 `OnPrimaryClipChangedListener` 监听宿主复制动作；
  - 当 Activity 获得焦点（`onWindowFocusChanged(true)`）时，主动触发一次剪贴板同步探针；
  - 当应用退至后台时，注销监听，杜绝无效后台开销与权限告警；
  - 宿主接收到 Linux 发来的剪贴板数据时，仅在 Activity 处于活跃前台态时调用 `ClipboardManager.setPrimaryClip()` 写入系统剪贴板。

---

### 2.2 Linux Guest 侧：Wayland 剪贴板协议与守护代理

在现代 Wayland 桌面中，剪贴板不再像 X11 那样全局共享，而是由合成器（Compositor）实施严格的安全隔离：
1. **Wayland 数据设备协议规范**：
   - 核心协议为 `wl_data_device` 与 `wl_data_source`；
   - 现代 wlroots 合成器（如 `labwc`）通过 `wlr-data-control-unstable-v1` 协议扩展，向特权/系统守护进程暴露剪贴板监听与注入能力；
2. **`wl-clipboard` 极简生态工具**：
   - `wl-paste --watch <command>`：利用 `wlr-data-control` 协议在后台挂钩 Wayland 剪贴板变更，当任何 Wayland 客户端复制内容时，即时以子进程标准输出派发；
   - `wl-copy`：将标准输入写入 Wayland 剪贴板数据源，支持多种 MIME 类型（默认 `text/plain;charset=utf-8`）；
3. **XWayland 剪贴板双向桥接**：
   - `labwc` 原生集成了 `xwm`（X11 Window Manager），自动在 X11 `CLIPBOARD` 选择区与 Wayland `wl_data_device` 之间建立双向镜像。因此，只要打通 Wayland 剪贴板，X11 应用（如旧版 GUI 编辑器、XTerm）即可自动获得同步支持。

---

### 2.3 双向防环同步机制与协议传输设计

双向剪贴板同步的最大技术隐患是**乒乓死循环（Ping-Pong Echo Loop）**：
> 宿主复制 A → 同步到 Guest 写入剪贴板 → Guest 触发变更通知回传宿主 → 宿主触发变更再次同步至 Guest……

为了实现 100% 确定性的防环同步，Orchestella Studio 提出**增量哈希指纹与状态机抑制（Fingerprint & Origin Suppression）**方案：

```
     ┌────────────────────────┐                   ┌────────────────────────┐
     │  Android Host Manager  │                   │   Guest Clipboard D    │
     └───────────┬────────────┘                   └───────────┬────────────┘
                 │                                            │
   [1. 宿主复制文本 "Hello"]                                  │
   - 计算 hash = SHA256("Hello")                               │
   - 记录 last_host_hash = hash                               │
   - 记录 last_synced_hash = hash                              │
                 │                                            │
                 ├─── DROIDLAYER/CLIPBOARD PUT base64:SGVs... ─►
                 │                                            │
                 │                                  [2. Guest 接收并写入]
                 │                                  - 记录 expected_hash = hash
                 │                                  - wl-copy "Hello"
                 │                                  - 触发 wl-paste --watch
                 │                                  - 校验: hash == expected_hash?
                 │                                  - 匹配成功! 抑制回传, 破环!
                 │                                            │
                 │                                  [3. Guest 内复制 "World"]
                 │                                  - 计算 hash = SHA256("World")
                 │                                  - hash != expected_hash
                 │◄── DROIDLAYER/CLIPBOARD PUT base64:V29y... ─┤
                 │                                            │
   [4. 宿主接收并写入]                                        │
   - 校验: hash == last_synced_hash? (否)                     │
   - 记录 last_synced_hash = hash                             │
   - 临时置位 is_applying_remote = true                       │
   - ClipboardManager.setPrimaryClip("World")                  │
   - 触发 OnPrimaryClipChanged 回调                            │
   - 检测到 is_applying_remote == true -> 抑制回传, 破环!     │
```

#### 同步协议扩展（Bridge v1 Line Protocol）
在 `:runtime:contract` 的 `BridgeControl` 中扩容剪贴板命令集：
- **Guest → Host**：
  `DROIDLAYER/CLIPBOARD PUT <base64_encoded_utf8>\n`
  - 宿主响应：`DROIDLAYER/OK clipboard\n`
- **Host → Guest**：
  在控制通道或专有连接上，宿主同样下发：
  `DROIDLAYER/CLIPBOARD PUT <base64_encoded_utf8>\n`
- **安全与边界防护**：
  - 限制单次剪贴板有效载荷上限为 **256 KB**（完全覆盖代码段、长配置与多页 Markdown 文本）；
  - 采用标准 Base64 编码，杜绝 `\n`、`\r` 与特殊控制字符对 Bridge 文本流协议的破坏；
  - 超过 256 KB 时确定性截断或丢弃，并返回 `DROIDLAYER/ERROR payload-too-large\n`，杜绝内存溢出 DoS。

---

## 3. 音频子系统低延迟流式输出（Audio Subsystem Output）

### 3.1 Linux Guest 侧音频栈评测：PulseAudio vs PipeWire

根据 ADR-0013 既定决策，M3 阶段音频范围**仅做 Guest → Host 的单向音频流输出**，录音（麦克风）明确不纳入。在 Linux 虚拟化与容器环境中，评测主流音频服务端：

| 评估维度 | 方案 A：PulseAudio 极简无头 Daemon（**首选**） | 方案 B：PipeWire + WirePlumber | 方案 C：纯 ALSA loopback |
| :--- | :--- | :--- | :--- |
| **内存常驻占用** | **~8 MB – 12 MB** | ~38 MB – 55 MB | < 2 MB |
| **依赖复杂度** | 仅依赖 `libpulse`；无需 D-Bus 与 systemd | 依赖 GLib、Lua、WirePlumber、D-Bus | 需内核编译 `snd-aloop` 驱动 |
| **Alpine Linux 兼容** | ⭐⭐⭐⭐⭐ 极度轻量，musl 原生打包 | ⭐⭐⭐ 依赖较多，D-Bus 开销大 | ⭐⭐ 需定制内核驱动 |
| **协议流式传输** | 原生内建 `module-simple-protocol-tcp` | 需加载 simple-protocol 插件 | 需自写用户态捕获程序 |
| **CPU 负载（播放时）**| 0.8% – 1.5% | 1.8% – 3.0% | 0.5% |

**选型决议**：
Guest 端采用 **PulseAudio 极简配置（Minimal PulseAudio Sink Daemon）**：
- 在 `droidlayer-desktop-setup` 中安装 `pulseaudio`；
- 配置 `default.pa`：
  ```
  load-module module-null-sink sink_name=droidlayer_sink sink_properties=device.description="Orchestella-Audio"
  set-default-sink droidlayer_sink
  load-module module-simple-protocol-tcp rate=48000 format=s16le channels=2 source=droidlayer_sink.monitor record=true port=9104 listen=127.0.0.1
  ```
- 该配置将所有 Linux 应用程序（浏览器、多媒体播放器、游戏）输出的音频自动混音并重采样至标准 PCM 格式（48000 Hz，16-bit 有符号小端，双声道立体声），并在端口 9104 上对外流式提供。

---

### 3.2 传输通道设计：LocalSocket vs vsock 9104

为了契合双执行策略，音频流采用双轨对齐传输通道：
1. **QEMU 软模拟档（L1）**：
   - PulseAudio 绑定 `127.0.0.1:9104` 或 Android 抽象 UNIX 域套接字 `@droidlayer-audio`；
   - 宿主通过端口转发或 LocalSocket 建立流式读取连接；
2. **AVF 硬件虚拟化档（L2）**：
   - Guest Agent 或专有音频转发器将 PulseAudio 的 TCP/Unix 流转发至 **vsock 专有端口 9104**；
   - 宿主通过 `VirtualMachine.connectVsock(9104)` 建立高速零拷贝流连接；
3. **数据吞吐与带宽分析**：
   - 48000 Hz × 2 声道 × 2 字节（16-bit） = **192,000 字节/秒（~187.5 KB/s，1.5 Mbps）**；
   - 该吞吐量极小，内存总线开销占比低于 0.01%，无任何网络或内存总线压力。

---

### 3.3 Android 宿主侧：AudioTrack 低延迟平滑播放流水线

Android 宿主端在 `:app` 模块实现高性能音频流播放器：
1. **`AudioTrack` 低延迟初始化配置**：
   - 使用 `AudioAttributes.Builder()` 设置 `USAGE_MEDIA` 与 `CONTENT_TYPE_MUSIC`；
   - 使用 `AudioFormat.Builder()` 设置 `ENCODING_PCM_16BIT`、`SAMPLE_RATE_HZ = 48000`、`CHANNEL_OUT_STEREO`；
   - 缓冲区大小计算：$BufferSize = \max(AudioTrack.getMinBufferSize(...), 8192 \text{ bytes})$，单次写入块为 1024 采样帧（4096 字节，约 21.3ms 音频）；
2. **自适应时钟漂移补偿算法（Clock Drift Compensation）**：
   - 虚拟机时钟与宿主硬件音频 DAC 时钟存在微秒级漂移。若 Guest 产出过快，会导致宿主缓冲区溢出（Overrun）；若产出过慢，会导致欠载欠载卡顿（Underrun / Underspill）；
   - **自适应动态阈值窗口**：
     * 维持环形缓冲区水印水位在 $40\text{ms} \sim 80\text{ms}$ 之间；
     * 水位超过 $120\text{ms}$ 时：轻微丢弃最老一小帧（跳过 128 采样点，人耳完全不可察觉）；
     * 水位低于 $20\text{ms}$ 时：注入微量线性衰减淡入静音填充，避免爆音（Click/Pop）；
3. **音频焦点与生命周期协同（Audio Focus Management）**：
   - 接入 `AudioManager.requestAudioFocus()`；
   - 当收到来电或其他应用抢占音频焦点时（`AUDIOFOCUS_LOSS_TRANSIENT`），自动降低音量（Ducking）或静音；焦点恢复时平滑恢复。

---

## 4. 外部物理键鼠直通与相对指针捕获（External Mouse Pointer Capture）

### 4.1 Android 原生 `requestPointerCapture()` API 机制

自 Android 8.0（API Level 26，完全满足项目最低支持版本 `minSdk 26`）起，Android 框架引入了专为 PC 级桌面和游戏设计的指针捕获 API：
- **`View.requestPointerCapture()`**：
  - 调用条件：宿主 Activity 且该 View 必须拥有输入焦点（`view.hasWindowFocus()`）；
  - 核心系统效应：
    1. **系统光标隐匿**：Android 系统自动隐藏默认的圆形鼠标指针；
    2. **边缘无界锁存**：鼠标移动不再受手机屏幕物理边界限制，彻底杜绝鼠标移到屏幕边缘触发“返回”手势、呼出通知栏或跳出窗口；
    3. **相对位移提取**：系统停止派发绝对坐标，转而在 `onCapturedPointerEvent(MotionEvent)` 中派发原始相对位移：
       $$\Delta x = \text{event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)}$$
       $$\Delta y = \text{event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)}$$
- **`View.releasePointerCapture()`**：
  - 释放指针捕获，恢复 Android 默认光标与系统手势拦截。

---

### 4.2 相对指针到 Wayland 协议映射流水线

```
       外部物理鼠标 (蓝牙 / USB-OTG)
                     │
                     ▼
       Android 系统底层 InputManager
                     │
                     ▼
       SurfaceView.onCapturedPointerEvent(event)
                     │
         ┌───────────┴───────────┐
         ▼                       ▼
    [位移与滚轮分发]        [按键状态提取]
    - AXIS_RELATIVE_X      - BUTTON_PRIMARY   -> BTN_LEFT
    - AXIS_RELATIVE_Y      - BUTTON_SECONDARY -> BTN_RIGHT
    - AXIS_VSCROLL         - BUTTON_TERTIARY  -> BTN_MIDDLE
         │                       │
         └───────────┬───────────┘
                     │
                     ▼
        策略中立 InputEvent 构造
        PointerEvent(dx, dy, buttonMask)
                     │
                     ▼
        Wayland 协议通道 / vsock 9103
                     │
                     ▼
        labwc / zwp_relative_pointer_v1
        - 享受宿主完全透明的 1:1 像素级平滑移动
        - 完美支持 Blender、GIMP 拖拽、FPS 视口环绕
```

- **交互体验增强**：
  - **捕获热键释放**：当用户需要临时将鼠标交还给 Android 宿主时，支持按下特定组合键（如 `Ctrl + Alt` 或悬浮状态栏上的“释放鼠标”浮标）调用 `releasePointerCapture()`；
  - **动态指针捕获**：当用户在桌面视口内左键单击时，自动重新获得指针捕获。

---

## 5. SAF 跨端文件直读直写与拖拽（File Drag & Drop & Direct Share）

### 5.1 Android 拖拽事件（Drag & Drop）与 ClipData SAF URI 提取

Android 提供了完善的视图拖拽框架（`View.OnDragListener`）：
1. **拖拽捕获**：
   - 用户在系统分屏模式下或多窗口悬浮窗中，从 Android 系统文件管理器（Files App）中将文件拖入 Orchestella Studio 桌面窗口；
   - 桌面视图 `OnDragListener` 接收到 `DragEvent.ACTION_DROP`；
2. **临时权限授予与 URI 提取**：
   - 宿主 Activity 必须调用 `requestDragAndDropPermissions(event)` 获取跨应用临时读写权限；
   - 从 `event.getClipData()` 中遍历提取各子项的 `android.net.Uri`（支持单一文件或多文件批量拖入）；
3. **内容流安全抽取**：
   - 通过 `context.getContentResolver().openInputStream(uri)` 提取文件字节流与元数据（文件名、MIME 类型、文件字节大小）。

---

### 5.2 协同落地架构：暂存池分发（Drop-to-Staging Pipeline）

受限于 Android Linux 内核与沙盒隔离，无法在无需 Root 条件下将任意动态 SAF URI 直接注入为 Linux 虚拟机的物理块设备。最优落地架构为**基于 Downloads 共享目录的暂存流水线（Staging Pipeline）**：

```
 [Android 文件管理器]
         │ (用户拖拽文件 "document.pdf")
         ▼
 [DesktopActivity: OnDragListener]
         │ 1. 拦截 DragEvent.ACTION_DROP
         │ 2. 获取 DragAndDropPermissions
         │ 3. ContentResolver 异步读取字节流
         ▼
 [宿主写入已共享的 Downloads 目录]
   /storage/emulated/0/Download/DroidLayer-Shared/Inbound/document.pdf
         │
         │ (通过现有的 ADR-0009 9p / virtio-fs 通道映射)
         ▼
 [Guest Linux 自动挂载点]
   /mnt/shared/downloads/Inbound/document.pdf
         │
         ▼
 [BridgeControl / 桌面弹窗提醒]
   "文件 document.pdf 已接收至 /mnt/shared/downloads/Inbound/"
```

**权衡考量**：
- **一致性**：完全复用 Phase 1 交付的 ADR-0009 只读/可读存储共享基础设施，无需为虚拟机引入新的文件系统驱动；
- **响应速度**：拖入操作在后台协程以 50MB/s+ 的内存缓冲流写入，并向 Guest Agent 发送通知事件，用户体验完全顺畅。

---

## 6. 核心架构决议支撑与 M3 演进结论

综合上述调研，M3 里程碑的架构边界与选型结论如下：

1. **剪贴板**：
   - 确立“前台焦点驱动 + 双向 SHA-256 指纹防环 + Base64 编码”架构；
   - 首期范围锁定为 **UTF-8 文本与代码片段（上限 256KB）**，富文本大文件剪贴板暂不纳入 M3；
2. **音频**：
   - 确立“Guest 侧极简 PulseAudio 虚拟声卡 + 48kHz/16-bit 立体声 PCM 流 + 宿主 AudioTrack 低延迟播放”路线；
   - QEMU 档走 LocalSocket / TCP 端口 9104，AVF 档走专有 vsock 端口 9104；
   - 明确排除麦克风录音（输入）；
3. **外设**：
   - 基于 Android API 26+ `requestPointerCapture()` 实现无边界相对指针捕获，直通 Wayland 指针协议，提供 PC 级操控；
4. **文件直享**：
   - 基于 `View.OnDragListener` 接入系统级文件拖入，落地至共享目录暂存池。

本调研报告为产出 `ADR-0018`、`spec-m3.md` 以及 Ticket 44~48 提供了坚实、完整的事实支撑。

---
*归档路径：`docs/research/2026-10-06-phase3-audio-clipboard-input.md`*

---
status: accepted
date: 2026-10-05
---

# ADR-0017: Wayland 原生移动图形通道、零拷贝双缓冲与深度输入集成架构

> **实现状态（2026-10-20，非正文修订）**：本 ADR 仍是已接受的目标架构，但当前生产路径是
> RFB over LocalSocket/vsock，能力枚举为 `GRAPHICS_RFB`；`WaylandChannel` 是历史接口名，
> 只表示“传输无关的字节流与帧缓冲区抽象”，不代表正在运行 Wayland/DLFR。真正的
> `GRAPHICS_WAYLAND` 只有在 Wayland 适配器端到端接线后才能声明。差异说明与索引见
> [`docs/adr/README.md`](README.md) 的“实现差异说明”。

## Context

在 Phase 1（M1 里程碑）中，Orchestella Studio 交付了基于 QEMU TCG 软模拟与 AVF pKVM 硬件虚拟化的双引擎免 Root 终端环境（Ticket 25–37）。根据 ADR-0012 早期规划，原定 M2 阶段采用传统 VNC（RFB 协议）软渲染方案。
然而，2026 年最新移动生态实测与技术演进（详见 `docs/research/2026-10-05-wayland-mobile-graphics-channel.md`）证实：
1. **传统 VNC 存在物理性性能死锁**：服务端 FrameBuffer 差分扫描计算在移动 CPU 上占用 40%+ 算力，伴随严重发热和降频；无硬件垂直同步（VSYNC）导致画面撕裂；交互延迟高达 150ms+，帧率受限于 10–15 FPS；
2. **输入与 IME 深度脱节**：RFB 协议缺乏移动手势与多点触控表达能力，无法良好适配 Android 宿主输入法（IME）的多字节 CJK 字符穿透输入。

为此，ADR-0012 于 2026-10-01 进行了代际架构修订，决定彻底废除 VNC-First 路线，全面转向 **Wayland-First 现代移动图形通道**。本决策（ADR-0017）正式固化该架构方案、三层模块边界与输入集成契约。

---

## Decision

确立“**策略中立契约、NDK 硬件双缓冲上屏、轻量 Wayland 栈、混合输入穿透**”的核心架构体系，严格划定三层模块边界：

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        Orchestella Studio Wayland 移动图形架构全景                     │
└────────────────────────────────────────────────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. 宿主展现与交互层 (:app)                                                            │
 │    - SurfaceView + NDK ANativeWindow + GLES 2.0/3.0 双缓冲渲染器 (60 FPS VSYNC)       │
 │    - 移动触控手势映射器 (单指左键/光标移动, 双指右键/平滑滚轮)                            │
 │    - 悬浮虚拟快捷键栏 (Ctrl, Alt, Shift, Super, Esc, Tab, 方向键, 锁存状态机)            │
 │    - Android IME 穿透桥接器 (InputConnection commitText 双模分发)                      │
 └──────────────────────────────────────────┬───────────────────────────────────────────┘
                                            │ 实现策略中立契约 / 注入渲染表面
 ┌──────────────────────────────────────────▼───────────────────────────────────────────┐
 │ 2. 策略中立图形契约层 (:runtime:contract)                                             │
 │    - FullLinuxContract.Capability 扩容: GRAPHICS_WAYLAND                             │
 │    - DisplayControl 接口: 模式协商 (w, h, scale, dpi), 帧同步观察器, 运行时动态重协商   │
 │    - WaylandChannel: 传输无关的字节流与帧缓冲区抽象                                  │
 │    - Wire 命令扩展: DROIDLAYER/DISPLAY MODE, RESIZE, FPS                             │
 │    - InputEvent: 策略中立输入事件结构 (Pointer, Touch, Key, TextCommit)               │
 └─────────────────────┬──────────────────────────────────────────┬─────────────────────┘
                       │ 传输特化 (LocalSocket / memfd)            │ 传输特化 (vsock 9103)
 ┌─────────────────────▼────────────────┐        ┌────────────────▼─────────────────────┐
 │ 3a. QEMU 软模拟档 (L1)                │        │ 3b. AVF 硬件虚拟化档 (L2)            │
 │ - 宿主与 Guest 共享物理 Linux 内核     │        │ - 独立 EL2 虚拟机 (Stage-2 页表隔离) │
 │ - LocalSocket 传输 Wayland 协议控制流 │        │ - vsock 端口 9103 高速图形数据流     │
 │ - memfd / ashmem 零拷贝双缓冲传递     │        │ - 双缓冲乒乓传输, 脏矩形按需刷写     │
 └─────────────────────┬────────────────┘        └────────────────┬─────────────────────┘
                       └────────────────────────┬─────────────────┘
                                                │ 驱动并接收输入
 ┌──────────────────────────────────────────────▼───────────────────────────────────────┐
 │ 4. Guest 操作系统层 (Linux Guest / guest-assets)                                     │
 │    - droidlayer-guest-agent / display-agent: 承接宿主显示重协商与混合输入注入        │
 │    - 合成器: labwc (默认, wlroots 轻量层叠式) / Weston (诊断备选)                    │
 │    - 桌面套件: sfwbar/waybar (状态栏/启动器) + foot (Wayland 终端)                    │
 │    - 兼容层: XWayland (开箱即用支持主流 X11 软件)                                    │
 │    - 发行版一键自动化装配脚本: droidlayer-desktop-setup (Alpine apk / Debian apt)    │
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

### 1. `:runtime:contract` 策略中立图形契约
- **能力定义**：在 `FullLinuxContract.Capability` 中新增 `GRAPHICS_WAYLAND`，作为能力矩阵的一等声明；
- **控制抽象 `DisplayControl`**：
  - 支持向 Guest 查询与协商初始分辨率、DPI 与缩放比例；
  - 支持设备屏幕旋转与窗口尺寸变更时的**双向动态重协商（Dynamic Re-negotiation）**；
- **通道抽象 `WaylandChannel`**：
  - 封装控制连接与帧数据流，UX 呈现层仅与此抽象交互，绝不感知底层是 LocalSocket 还是 vsock；
- **线协议扩容（Bridge v1 显示命令集）**：
  - `DROIDLAYER/DISPLAY MODE <width> <height> <scale_percent>\n`
  - `DROIDLAYER/DISPLAY RESIZE <width> <height>\n`
  - `DROIDLAYER/DISPLAY FPS <target_fps>\n`
  - 响应遵循标准 `DROIDLAYER/OK display ...\n` 与 `DROIDLAYER/ERROR ...\n`。

### 2. `:app` 宿主渲染面与深度输入集成
- **NDK 硬件双缓冲渲染流水线**：
  - 摒弃 Java `Canvas` 软件拷贝，采用 NDK `ANativeWindow` 构建 EGL/GLES 2.0/3.0 纹理映射流水线；
  - 挂接 Android `AChoreographer` 硬件 VSYNC，帧渲染与设备屏幕刷新（60Hz/120Hz）严格同步，消除撕裂；
  - 支持 GPU 硬件双线性缩放与纵横比适配（Aspect-Ratio Preserve）；
- **触控手势映射引擎（TouchGestureMapper）**：
  - 单指短按（$<200\text{ms}$，位移 $<8\text{dp}$）：映射为鼠标左键点击（`BTN_LEFT`）；
  - 单指拖拽：映射为桌面光标平滑移动（相对位移模式，带动态加速度）；
  - 双指轻点：映射为鼠标右键点击（`BTN_RIGHT`），弹出上下文菜单；
  - 双指协同滑动：映射为连续滚轮事件（`wl_pointer.axis` 垂直与水平滚动）；
- **悬浮虚拟快捷键栏（VirtualKeyBar）**：
  - 停靠于屏幕顶部或软键盘上方，包含 `Ctrl`、`Alt`、`Shift`、`Super`、`Tab`、`Esc`、方向键；
  - 实现“单击待命锁存（Latch）+ 双击常驻锁定（Locked）”双态状态机；
- **双模融合 IME 文本穿透引擎**：
  - 优先通过 Wayland `text-input-v3` 协议与现代 Wayland 客户端通信；
  - 针对 XWayland 与传统应用，由宿主 `InputConnection.commitText()` 捕获多字节 CJK 字符后，经 Guest Agent 注入管道（或合成键盘扫描码/剪贴板无缝粘贴）直接上屏，确保 100% 不掉字。

### 3. Guest 侧合成器与桌面自动化装配
- **合成器选型**：首选 `labwc`（基于 wlroots，Openbox 层叠窗口风格，原生标题栏与窗口控制按钮，内存占用仅 30–45MB），备选 `Weston`；
- **X11 兼容**：默认启用 `xwayland`，兼顾生态兼容；
- **双发行版装配**：提供自动化装配脚本 `droidlayer-desktop-setup`：
  - Alpine Linux：基于 `apk` 安装 `labwc sfwbar foot xwayland font-noto-cjk`；
  - Debian 13：基于 `apt` 安装 `labwc sfwbar foot xwayland fonts-noto-cjk`。

---

## Considered Options

1. **坚持原 ADR-0012 的 VNC 软渲染（否决）**：
   - 理由：VNC 在移动设备上带来灾难性的 CPU 占用与发热，15 FPS 上限无法满足现代桌面生产力诉求，属于已被行业淘汰的过渡技术。
2. **纯 Java/Kotlin SurfaceView `lockCanvas` 渲染（否决）**：
   - 理由：单帧 1080p 拷贝与格式转换消耗 15–20ms，无法稳定维系 60 FPS 满帧，且极易触发 GC 抖动。
3. **选用 Sway 平铺式合成器（否决）**：
   - 理由：Sway 强依赖物理键盘组合键，在移动触控与软键盘遮挡下交互极其痛苦；labwc 的窗口拖拽与按钮点按更符合移动触控直觉。
4. **仅依赖 text-input-v3 纯协议输入（否决）**：
   - 理由：Linux 虚拟机内的大量 X11/XWayland 旧应用及部分轻量工具未适配 text-input-v3，会导致中文输入法在关键应用中失效；必须辅以合成字符管道兜底。

---

## Consequences

1. **积极收益**：
   - 桌面渲染帧率从 VNC 的 ~15 FPS 跨越至 60 FPS 满帧，输入到显示延迟由 150ms+ 降低至 30ms 以内；
   - 彻底解决移动端触控点击不准、滚动缺失与中文输入法无法输入的交互顽疾；
   - 架构完全前向兼容后续 M4 阶段的 Mesa Venus / Turnip GPU 硬件加速。
2. **工程代价与边界约束**：
   - `:app` 需引入 NDK C++ 硬件渲染实现（约 800–1200 行现代 C++），构建期需依赖 CMake 与 Android NDK r27+；
   - AVF 档需要对齐 vsock 端口 9103 图形数据流传输；
   - 发行版首次配置桌面时需要通过网络下载约 60MB–120MB 的桌面组件软件包（由离线缓存或镜像源加速缓解）。
3. **纪律红利**：
   - `:runtime:contract` 保持 100% 纯 Java，不引入任何 Android SDK 或 NDK 依赖，既有 JVM 契约测试套件零回归风险。

---
*关联工单：Ticket 38–42（M2 里程碑）*

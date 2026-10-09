# Orchestella Studio Phase 2（M2 里程碑）— 产品规格说明书（Spec）

Labels: ready-for-agent  
Version: 2.0（2026-10-05）  
依据：ADR-0001 ~ ADR-0017 + `docs/research/2026-10-05-wayland-mobile-graphics-channel.md` + 词汇表  
变更纪律：本规格说明书的每个承诺必须可追溯到 ADR-0012（修订版）与 ADR-0017；未经架构决议，不得擅自修改技术边界或新增隐性承诺。

---

## 1. 问题陈述（Problem Statement）

在 Phase 1（M1 里程碑）中，Orchestella Studio 成功构建了无需 Root 权限的 Android 完整 Linux 运行环境，支持基于 QEMU TCG 软模拟与 AVF pKVM 硬件虚拟化的双执行策略，实现了出站 NAT、端口转发、只读存储共享以及全功能的 root-shell 终端交互。

然而，仅有终端无法满足移动端开发者与极客用户的全部诉求。用户迫切希望在手机或平板上直接运行图形化开发工具（现代 Web 浏览器、轻量 IDE、文档阅读器、图形化 Git 客户端与文件管理器）。
历史上基于 VNC（RFB 协议）的移动 Linux 方案存在三大致命问题：
1. **高 CPU 消耗与设备过热**：VNC 依赖服务端的 CPU 差分轮询与高密度图像压缩，在移动端运行极易触发温控降频；
2. **缺乏硬件垂直同步（VSYNC）**：无双缓冲机制，画面撕裂严重，帧率被锁定在 10–15 FPS 的极低水平；
3. **输入体验破碎**：无法良好识别移动端触摸手势（如双指滚轮滚动），缺失键盘修饰符支持，且完全无法利用 Android 宿主输入法（IME）输入中日韩（CJK）等多字节文本。

因此，M2 必须彻底废除 VNC-First 路线，交付基于 Android 原生 `SurfaceView` 与 Wayland 协议的原生移动图形通道，实现 60 FPS 满帧、低延迟的 Linux 移动桌面与深度输入集成。

---

## 2. 解决方案概述（Solution Overview）

**Orchestella Studio M2**：在 Android 免 Root 环境下，构建直连 Linux Guest 的 Wayland 原生移动图形桌面体系。

核心架构特征包括：
1. **策略中立的图形与控制契约（`:runtime:contract`）**：
   - 扩容 `FullLinuxContract.Capability.GRAPHICS_WAYLAND`，上层通过统一的 `DisplayControl` 与 `WaylandChannel` 抽象与实例交互；
   - 统一管理桌面模式协商（分辨率、DPI、缩放比例）、双向动态重协商（屏幕旋转/窗口自适应）及帧率同步。
2. **Android 宿主高性能渲染面（`:app`）**：
   - 基于 `SurfaceView` 与 NDK `ANativeWindow` 构建 EGL/GLES 2.0/3.0 双缓冲硬件纹理渲染流水线；
   - 对齐 Android `AChoreographer` 硬件 VSYNC，实现端到端 60 FPS 满帧、无撕裂渲染；
   - 在 QEMU 软模拟档采用本地套接字与匿名共享内存（`ashmem` / `memfd`）零拷贝直通；在 AVF 硬件虚拟化档采用专有 vsock 高速图形数据通道（端口 9103）。
3. **移动端触控与深度输入集成**：
   - **高精度手势映射引擎**：单指轻点（左键）、单指拖动（光标相对移动与动态加速度）、双指轻点（右键菜单）、双指协同滑动（平滑滚轮）；
   - **悬浮/固定虚拟快捷键栏**：常驻提供 `Ctrl`、`Alt`、`Shift`、`Super`、`Tab`、`Esc`、方向键，支持单次待命锁存与双击锁定；
   - **双模融合 Android IME 穿透引擎**：通过 Wayland `text-input-v3` 协议与 Guest 字符合成管道无缝桥接 Android 输入法，确保 100% 中文 Unicode 字符串准确上屏。
4. **Guest 侧轻量合成器与开箱即用桌面**：
   - 预置 `labwc`（基于 wlroots 的轻量层叠式合成器，原生支持窗口标题栏与控制按钮，内存占用 $<45\text{MB}$）与 `Weston`；
   - 集成 `sfwbar` / `waybar` 状态栏、`foot` 终端与 `xwayland` 兼容层；
   - 提供针对 Alpine Linux 与 Debian 13 的一键无人值守自动化装配脚本。

---

## 3. 用户故事（User Stories）

1. **作为用户**，我想在实例卡片上一键启动“图形桌面”，应用在 3 秒内呈现清晰的 Linux 桌面界面，以便立即使用图形化工具。
2. **作为移动端用户**，我想在 60 FPS 的流畅帧率下浏览网页和操作窗口，没有撕裂、顿挫感或明显的手机发热。
3. **作为触屏用户**，我想通过单指点击打开文件、单指拖拽移动光标、双指轻点弹出右键菜单，以便像使用实体鼠标一样精确操作桌面。
4. **作为阅读长文档/网页的用户**，我想通过双指在屏幕上滑动手势实现平滑页面滚动，以便高效阅读内容。
5. **作为中文用户**，我想在 Linux 桌面应用（如浏览器、文本编辑器、终端）中点击输入框时直接调出我手机上的系统输入法（微信输入法/Gboard等），并能流畅打出汉字和 Emoji 且不掉字。
6. **作为命令行重度用户**，我想在没有物理键盘的情况下，通过屏幕上的快捷键栏方便地按下 `Ctrl+C`、`Alt+Tab`、`Tab` 补全与 `Esc`，以便自如使用 vim 和 shell。
7. **作为多任务用户**，我想在手机旋转（横屏/竖屏切换）或分屏多任务时，Linux 桌面能够自动重新协商并无缝适配新的屏幕分辨率，无需手动重启桌面。
8. **作为不同发行版的用户**，我想在 Alpine Linux 或 Debian 实例中都能一键安装并运行该图形桌面，获得完全一致的交互体验。
9. **作为注重透明度的用户**，我想在界面中清楚地看到当前的图形连接类型（Wayland 原生）、帧率监控徽章与底层虚拟化策略，以便对性能有清晰预期。
10. **作为需要切换场景的用户**，我想在同一个实例中自由在纯终端 Shell 视图与图形桌面视图之间无缝切换，后台服务保持稳定运行。

---

## 4. 可观察的性能指标（Observable Performance Metrics）

所有性能指标必须可在实机（如测试基准机 HONOR 80 GT）上量化测量，禁止不可证伪的模糊表述：

| 指标维度 | 目标基准（Target SLA） | 观测与测量方法 | 劣于基准的处理策略 |
| :--- | :--- | :--- | :--- |
| **渲染帧率 (FPS)** | 目标 $60\text{ FPS}$（基准波动 $\ge 55\text{ FPS}$） | 宿主渲染器内部计算 FPS 并在 UI 调试 HUD 显示 | 若连续 5 帧掉帧，启用局部脏矩形优化 |
| **端到端输入延迟** | $\le 32\text{ms}$（触控输入至屏幕像素刷新 $< 2$ 帧） | 高速相机（240fps）录屏测量或事件打点时间戳对比 | 优化触控事件队列合并算法，丢弃过期中间点 |
| **宿主内存增量** | 宿主图形渲染面常驻增量 $\le 60\text{ MB}$ | Android Profiler / dumpsys meminfo | 双缓冲显存复用，严禁帧泄漏 |
| **Guest 桌面空闲内存** | Guest 端桌面基线常驻 $\le 150\text{ MB}$ | Guest 内部 `free -m` 测量（labwc + bar + agent） | 剔除冗余后台守护进程，坚持极简栈 |
| **CPU 负载（空闲状态）** | QEMU 档 CPU 占用 $\le 8\%$；AVF 档 $\le 2\%$ | Android CPU Profiler / top | 静态画面无损伤时彻底停止 VSYNC 纹理上传 |

---

## 5. 验收标准（Acceptance Criteria, AC）

### AC-1：策略中立图形契约合规（Contract Compliance）
- `:runtime:contract` 包含完整的 `DisplayControl`、`WaylandChannel` 抽象与 `Capability.GRAPHICS_WAYLAND` 声明；
- 包含分辨率与 DPI 动态协商、帧率通知线协议解析（`DROIDLAYER/DISPLAY MODE`, `RESIZE`, `FPS`）的 100% JVM 单元测试覆盖；
- 契约无任何 Android SDK/NDK 专有依赖，保证纯 JVM 可测性。

### AC-2：宿主 SurfaceView 硬件双缓冲渲染（Host SurfaceView Rendering）
- 在 Android 宿主端交付继承自 `SurfaceView` 的硬件渲染面，借助 NDK `ANativeWindow` 与 GLES 2.0/3.0 实现纹理渲染；
- 接入 `AChoreographer` 垂直同步锁频，实测画面无撕裂；
- 支持在 QEMU 档通过 LocalSocket + 共享内存接收缓冲区，支持帧尺寸动态变换与平滑缩放。

### AC-3：移动触控手势高精度映射（Touch Gesture Mapping）
- 单指轻按触发精确的鼠标左键点击（`BTN_LEFT`）；
- 单指拖拽平滑移动鼠标光标，无阶跃跳动；
- 双指轻点精准触发鼠标右键点击（`BTN_RIGHT`）；
- 双指上下/左右滑动精准转换为 `wl_pointer.axis` 平滑垂直/水平滚轮事件；
- 提供纯直触（Touchscreen）与相对触控板（Trackpad）双模式切换开关。

### AC-4：宿主 IME 与虚拟快捷键栏深度集成（IME & Extra-Keys Integration）
- 界面常驻/呼出虚拟快捷键栏，包含 `Ctrl`、`Alt`、`Shift`、`Super`、`Tab`、`Esc`、方向键，状态机准确支持“单击锁存 + 双击锁定”；
- 点击输入框时宿主输入法正常弹出且不遮挡焦点输入区域（支持画面自适应平移）；
- 宿主软键盘输入的任意中文文本（经 `InputConnection.commitText()`）能够完整、准确地呈现在 Linux 应用中，无乱码、不丢字。

### AC-5：Guest 侧轻量合成器自动化装配与一键拉起（Guest Desktop Provisioning）
- 提供策略中立的自动化装配脚本 `droidlayer-desktop-setup`；
- 在 Alpine Linux 官方源下自动安装 `labwc sfwbar foot xwayland font-noto-cjk` 并成功拉起；
- 在 Debian 13 下通过 apt 自动化装配并成功拉起；
- 启动后能通过轻量终端与图形浏览器（Firefox/Chromium）正常浏览网页与交互。

### AC-6：AVF 硬件虚拟化 vsock 高速通道对齐（AVF Graphics Parity）
- 在 AVF 硬件虚拟化环境下，图形数据面通过 vsock 专有端口（9103）以双缓冲乒乓传输；
- 功能行为与 QEMU 软模拟档完全一致，且契约测试在两个引擎上全绿通过。

---

## 6. 范围界定与排除项（Out of Scope）

为了确保 M2 阶段聚焦于高质量的 2D/Wayland 流畅交互桌面与深度输入集成，严格划定以下非目标：

1. **硬件 3D GPU 加速（VirtIO-GPU Venus / Turnip / VirGL 3D 透传）**：
   - 明确归属于 **M4 里程碑**；M2 专注于高质量 2D 软渲染 + 宿主 GPU 2D 纹理加速上屏，不承诺在 Guest 内运行重度 3D 游戏或硬件 3D 渲染器；
2. **音频输入与输出（Audio Subsystem）**：
   - 遵照 ADR-0013，音频输出挂接在 M2 之后或独立子系统中实现，本 spec 不包含 PulseAudio / PipeWire 音频流传输；
3. **USB 物理外设直通**：
   - 遵照 ADR-0013，除 Android 识别的标准蓝牙/USB 键盘鼠标作为宿主输入事件转发外，不提供虚拟机级别的原生 USB 拓扑直通；
4. **多屏与外部显示器 HDMI 独立扩展**：
   - 仅支持手机/平板本机单屏幕的主显示输出，Type-C / HDMI 独立异显作为后续增强能力。

---
*规格归档：`.scratch/orchestella-platform/spec-m2.md`*

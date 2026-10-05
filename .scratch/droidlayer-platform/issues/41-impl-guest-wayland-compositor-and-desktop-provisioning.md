# 41: Guest 侧 Wayland 代理、轻量合成器自动化装配与双发行版桌面一键拉起

Type: implementation  
Status: ready-for-agent  

## What to build

在 Guest Linux 环境（Alpine Linux 与 Debian 13）中构建开箱即用的 Wayland 运行环境、桌面合成器以及自动化装配脚本，打通从宿主点击“启动图形桌面”到 Guest 内流畅运行图形应用的完整链路，遵循 ADR-0006、ADR-0012 与 ADR-0017。

主要包含：
1. **Guest 侧桥接代理（`droidlayer-display-agent`）**：
   - 作为后台守护进程（或内置于 `droidlayer-guest-agent`），监听宿主图形通道（QEMU 下的 LocalSocket 或 AVF 下的 vsock 9103）；
   - 暴露标准 Wayland 套接字（`/run/user/1000/wayland-0`），供所有 Linux 客户端程序连接；
   - 将合成器生成的完整桌面双缓冲或脏矩形推送到宿主数据流；
   - 接收宿主发来的 `DISPLAY RESIZE` 命令，自动调用 `wlr-randr` 无缝刷新桌面虚拟显示输出（Virtual Output）；
   - 接收宿主发来的触控、按键与多字节文本提交事件，派发给当前获得焦点的 Wayland 窗口；
2. **轻量合成器（Compositor）预置与配置**：
   - 选用 `labwc`（基于 wlroots）作为默认合成器，具备层叠式窗口、原生标题栏和方便触控点按的最小化/关闭按钮，内存常驻仅 30–45MB；
   - 预置 `Weston` 配置文件作为诊断基准备选；
   - 预置轻量状态栏 `sfwbar`（提供应用程序启动菜单、窗口任务栏、系统托盘和时钟）；
   - 默认启用 `xwayland` 兼容支持，确保传统 X11 软件无需修改即可在 Wayland 桌面上直接运行；
   - 预置极速终端 `foot` 作为默认桌面终端；
3. **双发行版无人值守自动化装配脚本（`droidlayer-desktop-setup`）**：
   - **Alpine Linux 3.24+**：使用 `apk add labwc sfwbar foot xwayland font-noto-cjk` 自动装配，写入 OpenRC 自启动服务或桌面启动入口脚本；
   - **Debian 13 (Trixie)**：使用 `apt-get install -y --no-install-recommends labwc sfwbar foot xwayland fonts-noto-cjk` 自动装配；
   - 宿主端通过 Bridge Control 发送一条初始化指令，即可在 3 秒内拉起完整桌面。

**Blocked by:** 38, 39, 40

## Acceptance criteria

- [ ] 自动化装配脚本在 Alpine 和 Debian 系统下均能一键无报错执行完成，成功部署合成器及桌面必要依赖；
- [ ] `droidlayer-display-agent` 成功拉起并与宿主建立双向通信，正确创建并维护 `wayland-0` 套接字；
- [ ] `labwc` 成功启动，在宿主 `SurfaceView` 上呈现清晰、完整的层叠桌面与状态栏；
- [ ] 点击状态栏启动器可成功打开 `foot` 终端与图形应用（如 Firefox/Chromium、Gedit），窗口可通过触摸拖动与缩放；
- [ ] 宿主输入法打出的中文汉字与快捷键栏按键在 Guest 桌面应用中能够准确响应并上屏；
- [ ] 桌面空闲状态下，Guest 端常驻内存占用 $\le 150\text{ MB}$。

## Test strategy

- Guest 端自动化 Smoke 脚本（`guest-assets/smoke/desktop-smoke.sh`）：
  - 在无头/QEMU 环境下静默执行 `droidlayer-desktop-setup`；
  - 启动 `labwc` 并运行测试 Wayland 客户端（如 `wayland-info`、`foot --version`），检查套接字状态与返回值；
- 双发行版兼容性测试：分别在 Alpine 3.24 镜像与 Debian 13 根盘上验证软件包依赖与服务启动流程；
- 真机集成验证：通过 Android 应用界面一键启动桌面，进行多窗口拖拽、中文输入与关闭测试。

## Prototype/research dependency

依赖 Ticket 38、39、40 提供的渲染与输入管道，以及 `docs/research/2026-10-05-wayland-mobile-graphics-channel.md` 第 3 节的合成器对比与自动化装配调研。

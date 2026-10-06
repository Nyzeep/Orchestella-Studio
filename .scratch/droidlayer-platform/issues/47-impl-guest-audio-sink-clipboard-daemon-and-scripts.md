# 47: Guest Linux 音频虚拟汇聚端、剪贴板守护代理与自动化配置

Type: implementation  
Status: ready-for-review  

## What to build

在 `guest-assets/` 中构建 Linux Guest 侧极简音频虚拟声卡汇聚端、Wayland 剪贴板守护代理以及双发行版自动化装配流程，遵循 ADR-0013 与 ADR-0018。

主要包含：
1. **PulseAudio 极简无头虚拟声卡配置（Minimal Audio Sink Config）**：
   - 编写 `guest-assets/scripts/desktop/pulseaudio/default.pa`：
     * 禁用重度模块，配置极简 null-sink（`module-null-sink`，命名为 `droidlayer_sink`）；
     * 挂载 `module-simple-protocol-tcp`，锁定格式为 48000Hz 16-bit 立体声（`rate=48000 format=s16le channels=2`），监听本地端口 9104；
     * 常驻内存压制在 $10\text{ MB}$ 以内，CPU 占用率低于 1.5%；
2. **Guest 端 Wayland 剪贴板守护代理（`droidlayer-clipboard-agent`）**：
   - 基于 `wl-clipboard` 工具链构建轻量级守护脚本/服务：
     * 利用 `wl-paste --watch` 挂接 `wlr-data-control` 协议，当 Guest 内部发生复制时即时捕获文本；
     * 计算 SHA-256 哈希并与预期哈希比对，滤除来自宿主的回环更新；
     * 将更新内容进行 Base64 编码，通过本地 Bridge 套接字派发 `DROIDLAYER/CLIPBOARD PUT` 指令；
     * 监听 Bridge 控制通道下发的宿主剪贴板更新，调用 `wl-copy` 注入 Wayland 剪贴板；
3. **桌面自动化装配脚本扩容（`droidlayer-desktop-setup.sh`）**：
   - Alpine Linux：扩容包列表引入 `pulseaudio`、`pulseaudio-utils`、`wl-clipboard`；
   - Debian 13：扩容包列表引入 `pulseaudio`、`pulseaudio-utils`、`wl-clipboard`；
   - 自动化初始化 PulseAudio 守护进程与剪贴板代理的配置目录及服务自启；
4. **`labwc/autostart` 协同启动脚本更新**：
   - 在桌面环境启动时，并行拉起 PulseAudio 后台守护与剪贴板桥接守护。

**Blocked by:** 44

## Acceptance criteria

- [x] `default.pa` 配置文件格式正确，PulseAudio 在无头环境下启动时间 $< 500\text{ms}$，内存常驻 $\le 15\text{ MB}$；
- [x] 端口 9104 稳定输出 48000Hz 16-bit 双声道 PCM 音频字节流，浏览器和终端播放音频时能被正常流式捕获；
- [x] `droidlayer-clipboard-agent` 能稳定捕获 Wayland 及 XWayland 窗口的复制动作并即时上报；
- [x] `droidlayer-desktop-setup.sh` 在 `--check` 模式与真实安装流程中均能完整校验新增软件包；
- [x] 所有 Shell 脚本通过 ShellCheck 静态检查，纯 JVM 测试套件（`DesktopSetupScriptTest` 等）100% 绿灯。

## Test strategy

- 纯 JVM 单元测试：
  - `PulseAudioConfigVerifierTest`：验证 PulseAudio 配置语法与采样参数；
  - `DesktopSetupScriptTest`：扩容验证 Alpine 与 Debian 依赖包清单及 autostart 脚本逻辑；
  - `ClipboardAgentProtocolTest`：验证剪贴板守护命令构造、哈希防环比对与 Base64 格式；
- 自动化 ShellCheck 与脚本静态回归：
  - 确保零语法错误与零环境变量污染。

## Prototype/research dependency

依赖 Ticket 44 产出的剪贴板命令集，以及 `docs/research/2026-10-06-phase3-audio-clipboard-input.md` 中的 PulseAudio 与 wl-clipboard 架构。

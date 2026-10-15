# Orchestella Studio

<div align="center">

**无需 Root 的 Android 原生双引擎 Linux 虚拟机与移动工作站**  
*Native Dual-Engine Linux VM Runtime & Mobile Workstation for Android (No Root Required)*

[![Release](https://img.shields.io/badge/Release-v0.4.5%20Dev%20Preview-orange.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.4.5)
[![License](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg?style=flat-square)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg?style=flat-square)](#)
[![CI](https://img.shields.io/github/actions/workflow/status/Nyzeep/Orchestella-Studio/ci.yml?branch=main&label=CI&style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)

</div>

---

## 开发预览版说明

> **当前版本 v0.4.5 仍为开发预览版（Development Preview），尚非稳定正式版。** 欢迎下载体验，并积极反馈启动、终端输入、主题显示、网络或设备兼容性问题。不同机型、Android 定制系统与发行版的表现可能存在差异，重要数据请先备份。

- **下载安装**：[v0.4.5 发行说明与安装包](https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.4.5)。请同时阅读其中的验证范围、已知限制和升级说明。
- **问题反馈**：[提交 Issue](https://github.com/Nyzeep/Orchestella-Studio/issues/new)，建议附上应用版本、设备型号、Android / 系统版本、发行版、复现步骤及脱敏日志或截图。
- **体验交流**：[GitHub Discussions](https://github.com/Nyzeep/Orchestella-Studio/discussions)。欢迎报告问题、提出建议，也欢迎分享成功运行的机型与使用场景。

### v0.4.5 本次更新

- **终端与登录**：修复点击终端无法重新呼出键盘、Fedora / Debian 重复登录与输入干扰，以及终端协议应答回路。
- **主题与配置**：统一浅色、深色和跟随系统的页面/终端配色，修复 ANSI 索引色、容量按钮换行与高度不齐；切换主题保留向导配置。
- **Guest 恢复**：修复 BusyBox 遮蔽原生命令导致 Ubuntu SSH 软件包配置失败，已有实例重启即可应用，无需重建根盘。
- **更新与构建**：开发预览通道按语义版本检查更新，避免旧版误报升级；默认使用官方依赖仓库，补齐 Python 3 和 lint 验证。
- 包含发行版独立卡片、40 套明暗主题及应用内更新入口。**AVF 已验证授权与回退，当前测试设备仍受隐藏接口访问限制，实际使用 QEMU。**

## 项目概述

**Orchestella Studio** 是一款面向 64 位 Android 设备的原生 Linux 运行环境与移动工作站。无需对手机进行 Root 解锁，即可在 Android 应用沙盒内启动具有独立内核的 Linux 环境。项目持续开发中，具体功能与兼容性以当前预览版的实际验证结果为准。

### 为什么选择 Orchestella？

在传统的 Android Linux 方案中（如 PRoot、chroot 容器），系统通过拦截和转换系统调用（syscall）来伪装环境。这类方案往往存在以下局限：
* **无法加载真实内核特性**：缺乏独立的内核空间，无法支持原生 systemd、cgroups 资源控制、真正的虚拟文件系统与完整网络协议栈；
* **系统调用兼容性差**：复杂软件或涉及内存映射、进程追踪（如 Docker/Podman、GCC/Clang、大型数据库）时极易崩溃；
* **环境污染与权限穿透**：容器与宿主系统共享内核，受限于 SELinux 策略，难以实现安全隔离。

**Orchestella Studio 的根本不同在于**：
它通过**硬件虚拟化（AVF）与软件虚拟化（QEMU TCG）双引擎机制**，在 Android 应用沙盒内启动**拥有独立 Linux 内核、独立虚拟块设备和独立网络堆栈**的虚拟机。硬件虚拟化是否可用取决于设备平台、系统接口及授权；预检不满足或启动失败时使用软件虚拟化。当前真机回归主要覆盖 QEMU 路径，不代表所有设备的 AVF、图形或工作负载均已验证。

---

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Orchestella App UI                       │
│      (Material 3 / 科技暗黑与纸感浅色 / 五级控制台导航)       │
└──────────────────────────────┬──────────────────────────────┘
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
┌───────────────────────────────┐   ┌─────────────────────────┐
│     AVF / pKVM 硬件引擎       │   │     QEMU TCG 仿真引擎    │
│  (Android 13+ 芯片级硬件虚拟化) │   │ (ARM64 JNI 高性能二进制翻译) │
└───────────────┬───────────────┘   └────────────┬────────────┘
                │                                │
                └──────────────┬─────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 用户态网络层 (GVProxy / SLIRP)                │
│    无需 Root 权限实现 NAT 转换、多重 DNS 路由与端口双向映射    │
└──────────────────────────────┬──────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│              Guest 操作系统环境 (Guest Assets)               │
│   • initramfs 自愈装配与 EXT4 虚盘持久化挂载                  │
│   • 7 大官方主流发行版 Rootfs (Ubuntu / Debian / Arch 等)    │
│   • orchestella-agent 控制平面与双向串口交互 Shell          │
└─────────────────────────────────────────────────────────────┘
```

---

## 核心特性与工程亮点

### 1. 双引擎自适应虚拟化技术
* **硬件级虚拟化（AVF / pKVM）**：针对 Android 13+ 且支持 Google 原生虚拟化框架（Android Virtualization Framework）的设备，自动利用芯片硬件扩展实现硬件级特权隔离，带来极高的运算吞吐与超低开销。
* **软件仿真虚拟化（QEMU TCG aarch64）**：面向所有 ARM64 Android 设备（Android 8.0+），提供深度定制的 JNI 动态二进制翻译运行时，实现全机型兼容保障。

### 2. 现代科技美学与分级控制台
* **多套主题与一致的明暗模式**：
  * 内置 20 组、共 40 套明暗主题预设，包括 Constructivist、Moonshot、Claude、Catppuccin 等风格；
  * 支持 **跟随系统 / 浅色 / 深色**，明确选择亮色或暗色预设后立即应用，也可在设置中重新选择跟随系统；
  * 终端背景、ANSI 调色板、页面按钮与选中状态统一适配，即时切换保留正在编辑的向导配置。
* **五级二级选项卡控制台**：
  * **概览（Overview）**：实时监控实例运行状态，提供一键复制 SSH 直连指令；
  * **硬件（Hardware）**：支持动态分配 vCPU 核心数与内存阈值配额；
  * **存储（Storage）**：虚拟磁盘卷管理与读写拓扑；
  * **网络（Network）**：虚拟局域网 IP 显示、网络网关与端口转发映射；
  * **维护（Maintenance）**：重置、快照备份与销毁等敏感操作具备二次安全锁确认。

### 3. 专业移动终端交互体验
* **滑动式辅助快捷键栏**：针对手机触屏重构，提供 `ESC`、`TAB`、`CTRL`、`ALT`、`^C`、`CLR`、`HOME`、`END` 等开发者高频控制符，集成常用 Shell 符号直达；
* **粘滞键（Sticky Modifier）状态机**：支持单手组合键操作，清晰展示修饰键激活状态；
* **多会话终端管理（Multi-Session Sheet）**：内置会话管理器，支持多终端 Shell 窗口的快速新建、无缝切换与优雅销毁；
* **自适应高对比度调色板**：针对明暗主题分别适配锐利 ANSI 色彩阶，保证终端语法高亮清晰锐利。

### 4. 嵌入式 SSH 连接管理中心
* **自动端口分配与映射**：为每个虚拟机实例自动绑定本地网络回环端口（默认映射宿主 `2222 -> 22`）；
* **一键连接复制**：提供标准连接命令示例（如 `ssh -p 2222 root@127.0.0.1`），支持与外部第三方 SSH 客户端无缝桥接配合。

### 5. 七大主流官方发行版阵容与运行时韧性
* **官方发行版目录**：提供 Ubuntu、Debian、Fedora、Arch Linux、openSUSE、AlmaLinux、Rocky Linux 的独立入口；具体版本与可用镜像以应用内目录为准，本轮真机回归覆盖 Fedora 43 与 Debian 13；
* **多源镜像自动故障转移**：境内优先调度清华大学 TUNA 镜像站与阿里云镜像站加速下载（实测单线程拉取达 8.3 MB/s），海外节点自动容灾备选；
* **自愈装配与极简环境容错**：initramfs 启动脚本集成多 DNS 冗余（阿里/腾讯/Cloudflare/Google），并对精简根文件系统缺少诊断工具（如 `ping`）的场景实现智能放行。

---

## 快速上手

### 1. 安装应用

从 [GitHub Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) 下载最新的开发预览版 APK（Android 8.0+，ARM64-v8a），可使用同页提供的 SHA-256 校验文件核对安装包。

已有用户建议先正常关闭实例并备份重要数据，再覆盖安装；**不要为升级而卸载旧应用或清除应用数据**，这些操作会删除应用私有目录中的实例。若系统提示签名不一致，请先保留原应用与数据，并在 Issue 中反馈旧版本及安装来源。

### 2. 创建并启动虚拟机

1. 打开 **04/发行版**，选择发行版卡片上的 **装配实例**；也可从 **02/实例 → + 新建实例** 进入向导；
2. 选择所需 Linux 发行版（初次体验推荐 Ubuntu 24.04），镜像源可保留默认的多层级自动回退策略；
3. 根据设备余量配置内存、vCPU 与磁盘容量，并检查预检结果；
4. 确认后下载镜像并进行首次装配，耗时取决于网络、镜像大小和设备性能；
5. 装配完成后点击 **开机**，等待控制台进入 Shell。已有实例升级后正常重启即可应用本轮登录配置修复。

本地实例控制台沿用自动登录行为；SSH 或需要认证的命令仍使用实例凭据。自动进入本地 Shell 与密码认证是不同的访问场景。

### 3. 外部终端与网络连接
实例开机后，在 **详情控制台 → 概览** 页面直接复制 SSH 指令：
```bash
ssh -p 2222 root@127.0.0.1
```
该回环地址用于本机终端应用。局域网访问需要显式配置对应的绑定范围与宿主地址，不会由默认回环规则自动开放。

---

## 本地编译与构建

开发环境要求：
* **JDK 17** (推荐 Eclipse Temurin 17)
* **Android SDK Platform 36** 与 **Build-Tools 36.1.0**
* **Android NDK 27+** 与 **CMake 3.22+**

```bash
# 1. 克隆代码仓库
git clone https://github.com/Nyzeep/Orchestella-Studio.git
cd Orchestella-Studio

# 2. 运行协议合规检查与核心单元测试
./gradlew :runtime:contract:check :app:testDebugUnitTest

# 3. 运行 Guest Assets 资产验证套件
./gradlew -p guest-assets test

# 4. 构建生成安装包并执行 Android lint
./gradlew :app:assembleDebug :app:lintDebug
```

开发主分支默认从官方 Google Maven 与 Maven Central 获取依赖。如所在网络确实需要区域镜像，可显式使用 `-Porchestella.repositories.useAliyunMirror=true`；不启用时不会优先访问镜像。首次完整执行测试和 lint 后可使用 `--offline` 复用依赖缓存；缺少依赖时应先通过正常解析补齐，不跳过检查。

Guest shell 回归需要 Python 3；容器构建依赖安装器已显式包含该工具，入口会在启动 Gradle 前检查它。本地开发构建使用明确的预览通道标记检查 GitHub Releases，版本比较不会将较旧版本当作更新；发布稳定构建前需同步检查通道标记。

---

## 当前版本验证范围

v0.4.5 已完成以下回归，验证结果仅覆盖所列场景：

| 校验维度 | 本轮结果 |
| :--- | :--- |
| **构建与检查** | 713 项 App 测试、65 项 Guest 检查及 Android lint 通过；lint 仍有非阻断警告 |
| **终端与主题** | 键盘收起后点击唤起、前后台返回、实际命令输入、浅深色显示与向导状态保留验证通过 |
| **发行版回归** | Fedora / Debian 登录修复已验证；本轮 Ubuntu 包恢复达到 ready/verified，DBus 与 SSH 服务 active |
| **数据保留** | 四个已有实例的身份、凭据和根盘引用保持不变，测试后均正常停止 |
| **AVF 边界** | 两项权限已授予；自定义 VM 隐藏接口被系统拦截，实际回退 QEMU，未实现 AVF 端到端运行 |

验证覆盖所列 ARM64 真机与场景，不代表所有机型、发行版、输入法或图形/GPU 工作负载均已兼容。完整限制见发行说明；欢迎反馈未覆盖的使用场景。

---

## 技术边界与使用说明

1. **软件仿真性能边界**：在未搭载硬件虚拟化（pKVM）扩展或系统未开放 AVF 接口的机型上，系统采用 QEMU TCG 动态二进制翻译运行，性能受手机单核 CPU 性能约束，这是移动端无 Root 虚拟机的技术客观规律；
2. **厂商后台管理策略**：部分 Android 定制系统（如 MagicOS、MIUI/HyperOS、EMUI）具备激进的后台清理机制。如需运行长时后台服务，建议在系统设置中将 Orchestella 设置为「允许后台运行 / 无限制」；
3. **权限与可用性**：默认的软件虚拟化路径在 Android 应用沙盒内运行；硬件虚拟化需要设备平台支持及相应开发级授权。预检通过不等于实际启动成功，界面会显示当前授权与回退状态。

---

## 开源协议

本项目主体采用 **GNU General Public License v3.0 or later (GPL-3.0-or-later)** 开源许可证。

* **强开源保障 (Strong Copyleft)**：保障本项目及其衍生版本持续保持自由与开源，防止任何第三方进行闭源商业专有化封装；
* **反 Tivo 化与专利授权保障**：依照 GPLv3 条款，保护移动设备用户的自由修改与安装运行权利，并提供显式专利防御；
* **第三方组件授权遵循**：
  * Android 依赖库（Jetpack、Material 3 等）遵循 Apache 2.0 许可证（与 GPLv3 完全兼容）；
  * 随包分发的 Linux Kernel 及 QEMU 运行时二进制严格遵循原作者著作权及 GPL-2.0 / LGPL 等相应条款，对应源码与构建脚本详见 `QEMU-GPL-SOURCES.md` 及官方 Release 对应源码包；
  * 各发行版 Guest Rootfs 镜像遵循上游对应发行版的开源许可。

---

## 社区交流与反馈

**项目仍处于开发预览阶段，欢迎积极反馈体验问题。** 启动失败、输入异常、颜色冲突、网络不通、机型兼容性或不易理解的交互都可以提交 [Issue](https://github.com/Nyzeep/Orchestella-Studio/issues/new)；功能建议和使用交流可前往 [Discussions](https://github.com/Nyzeep/Orchestella-Studio/discussions)。

为了便于复现，建议提供：

- 应用版本与安装来源；设备型号、Android 版本及厂商系统版本。
- Linux 发行版与版本、是否为已有实例升级、运行引擎（如界面有显示）。
- 实际操作步骤、预期表现和实际表现；必要时附上截图或短录屏。
- **运行日志**中的相关片段，并说明问题发生的时间。发布前请删除密码、令牌、SSH 私钥及个人文件内容。

无需自行定位根因，也不必等到问题必现后再反馈；清晰的现象描述和使用场景同样有帮助。

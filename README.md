# Orchestella Studio

<div align="center">

**无需 Root 的 Android 原生双引擎 Linux 虚拟机与移动工作站**  
*Native Dual-Engine Linux VM Runtime & Mobile Workstation for Android (No Root Required)*

[![Release](https://img.shields.io/badge/Release-v0.3.0--preview.1-blue.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg?style=flat-square)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg?style=flat-square)](#)
[![CI](https://img.shields.io/github/actions/workflow/status/Nyzeep/Orchestella-Studio/ci.yml?branch=main&label=CI&style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)

</div>

---

## 项目概述

**Orchestella Studio** 是一款专为现代 64 位 Android 设备打造的原生 Linux 运行环境与移动工作站。无需对手机进行 Root 解锁或破解系统权限，即可在 Android 沙盒内提供完整、独立的真实 Linux 操作系统体验。

### 为什么选择 Orchestella？

在传统的 Android Linux 方案中（如 PRoot、chroot 容器），系统通过拦截和转换系统调用（syscall）来伪装环境。这类方案往往存在以下局限：
* **无法加载真实内核特性**：缺乏独立的内核空间，无法支持原生 systemd、cgroups 资源控制、真正的虚拟文件系统与完整网络协议栈；
* **系统调用兼容性差**：复杂软件或涉及内存映射、进程追踪（如 Docker/Podman、GCC/Clang、大型数据库）时极易崩溃；
* **环境污染与权限穿透**：容器与宿主系统共享内核，受限于 SELinux 策略，难以实现安全隔离。

**Orchestella Studio 的根本不同在于**：
它通过**硬件级虚拟化（AVF）与高性能软件仿真（QEMU TCG）双引擎机制**，在 Android 应用沙盒内拉起一个**拥有独立 Linux 内核、独立虚拟块设备和独立网络堆栈**的完整虚拟机。在保证宿主系统绝对安全的前提下，为开发者带来与真实云服务器、物理服务器一致的原生 Linux 运行体验。

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
* **沉浸双主题设计**：
  * **科技深空（Moonshot）**：沉浸式深暗背景，配合半透明毛玻璃质感卡片与微光描边，契合移动极客审美；
  * **自然纸感（Claude）**：温润柔和的浅色调，降低长时文本阅读时的视觉疲劳。
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
* **官方原版镜像支持**：内置支持 Ubuntu (24.04/22.04)、Debian (12 Bookworm)、Fedora (41/42)、Arch Linux、openSUSE (Leap 15.6/16)、AlmaLinux (9)、Rocky Linux (9)；
* **多源镜像自动故障转移**：境内优先调度清华大学 TUNA 镜像站与阿里云镜像站加速下载（实测单线程拉取达 8.3 MB/s），海外节点自动容灾备选；
* **自愈装配与极简环境容错**：initramfs 启动脚本集成多 DNS 冗余（阿里/腾讯/Cloudflare/Google），并对精简根文件系统缺少诊断工具（如 `ping`）的场景实现智能放行。

---

## 快速上手

### 1. 安装应用
从 [GitHub Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) 下载最新的预编译 APK（要求 Android 8.0 及以上版本，ARM64 架构），安装到设备。

### 2. 创建并启动虚拟机
1. 打开应用，点击首页的 **创建虚拟机** 按钮进入向导；
2. 选择所需 Linux 发行版（推荐新手使用 Ubuntu 24.04），选择就近下载源（国内建议保留默认的清华源）；
3. 根据设备硬件规格配置 vCPU 与磁盘大小（常规开发推荐 4 核 / 4GB 内存 / 8GB 虚拟磁盘）；
4. 确认配置后系统自动拉取系统镜像并初始化装配；
5. 装配完成后点击 **开机**，数十秒内即可直接进入交互式终端 Shell。

### 3. 外部终端与网络连接
实例开机后，在 **详情控制台 → 概览** 页面直接复制 SSH 指令：
```bash
ssh -p 2222 root@127.0.0.1
```
可在本机终端应用或局域网设备中直接登录管理。

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

# 4. 构建生成安装包
./gradlew assembleDebug
```

---

## 真机实测基准

核心功能在物理实体设备上经过完整 ADB 联调验收：

| 校验维度 | 实测数据 / 环境指标 |
| :--- | :--- |
| **测试机型** | 荣耀 Honor AGT-AN00 (搭载 MagicOS) |
| **操作系统** | Android 15 (API Level 35) / Linux 6.x aarch64 |
| **运行时引擎** | QEMU TCG aarch64 + GVProxy SLIRP NAT |
| **镜像拉取速度** | 清华大学开源镜像站 (Ubuntu 24.04) 实测 **8.3 MB/s** |
| **稳定性指标** | 引导启动、终端长时运行、多任务切换过程中 **Logcat 崩溃为 0** |
| **渲染性能** | 终端及控制台界面 SurfaceFlinger 保持 **60 FPS** 流畅刷新 |

---

## 技术边界与使用说明

1. **软件仿真性能边界**：在未搭载硬件虚拟化（pKVM）扩展或系统未开放 AVF 接口的机型上，系统采用 QEMU TCG 动态二进制翻译运行，性能受手机单核 CPU 性能约束，这是移动端无 Root 虚拟机的技术客观规律；
2. **厂商后台管理策略**：部分 Android 定制系统（如 MagicOS、MIUI/HyperOS、EMUI）具备激进的后台清理机制。如需运行长时后台服务，建议在系统设置中将 Orchestella 设置为「允许后台运行 / 无限制」；
3. **安全提示与权限**：本应用不申请敏感系统特权，完全运行于 Android 标准应用沙盒（untrusted_app）之内。

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

欢迎在 GitHub 提交 [Issues](https://github.com/Nyzeep/Orchestella-Studio/issues) 与 [Discussions](https://github.com/Nyzeep/Orchestella-Studio/discussions) 进行探讨与反馈。提交问题时建议附带：**「详情控制台 → 运行日志」** 输出以及您的机型与 Android 系统版本。

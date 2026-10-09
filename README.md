# Orchestella Studio

<div align="center">

**无需 Root 的 Android 原生双引擎 Linux 运行环境与移动工作站**  
*Native Dual-Engine Full Linux Runtime & Mobile Workstation for Android Without Root*

[![Release](https://img.shields.io/badge/Release-v0.4.0-blue.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio)
[![License](https://img.shields.io/badge/License-Apache--2.0-orange.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg?style=flat-square)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg?style=flat-square)](https://developer.android.com/ndk/guides/abis)
[![Tests](https://img.shields.io/badge/Tests-100%25%20Passing-success.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)

[简体中文](#简体中文) | [English](#english)

</div>

---

<a name="简体中文"></a>
## 🇨🇳 简体中文

### 1. 项目简介

**Orchestella Studio** 是一款面向现代化 Android 设备的**免 Root 完整 Linux 虚拟机运行环境与移动开发工作站**。

与传统的 PRoot 容器、chroot 方案或受限终端不同，Orchestella Studio 依托 Android 原生生态，为每个运行实例提供**具备真实独立 Linux 内核（Linux 6.6 LTS）的完整系统虚拟机**。它摆脱了 PRoot 对 `ptrace` 机制的重度依赖与性能惩罚，彻底消除了系统调用拦截失效、多线程竞态与伪特权陷阱，在移动设备上提供 100% 工业级标准的 Linux 开发、计算与图形桌面体验。

---

### 2. 核心架构与功能特性

```
+-----------------------------------------------------------------------------------+
|                            Orchestella Studio (App Layer)                         |
|   [Material 3 仪表盘]  [即时交互终端]  [Wayland 图形桌面]  [SAF 零拷贝导入导出]   |
+-----------------------------------------+-----------------------------------------+
|      硬件虚拟化引擎 (AVF / pKVM)        |        软件虚拟化引擎 (QEMU TCG 8.2)      |
|    - 近原生硬件加速 (L2 硬件级)          |    - 全架构兜底兼容 (L1 软件级)           |
|    - Vsock 高性能双向桥接通道           |    - Unix 抽象套接字与桥接代理通道        |
|    - 自动透明优雅降级保障               |    - GPL 边界与 nativeLibraryDir 纯净装配 |
+-----------------------------------------+-----------------------------------------+
|                             不可变契约层 (:runtime:contract)                      |
|   [Rootfs 装配]  [出站 NAT 网络]  [动态端口转发]  [双向剪贴板]  [音频与外设管理]   |
+-----------------------------------------------------------------------------------+
|                        Linux Guest 独立虚拟机环境 (Linux 6.6)                     |
|           Alpine Linux 3.24.1 (apk)     |     Debian 12/13 Bookworm/Trixie (apt)  |
+-----------------------------------------------------------------------------------+
```

#### 🚀 1. 双执行引擎融合（Dual-Engine Architecture）
- **硬件虚拟化（AVF / pKVM 策略）**：在支持 Android 虚拟化框架的设备上，获得 CPU 与内存的近原生硬件加速执行；
- **软件虚拟化（QEMU TCG 8.2 策略）**：在未解锁虚拟化权限或不受支持的设备上，自动无缝回退至工业级软件动态翻译引擎，确保 100% 机型覆盖；
- **诚实能力矩阵与透明降级**：内置严谨的设备硬件探针，透明汇报当前性能档位，绝不虚标假体验。

#### 🐧 2. 真实完整的 Linux 系统环境
- **独立完整内核**：搭载独立 Linux 6.6 LTS 内核，原生支持完整 Linux 系统调用（System Calls）、命名空间（Namespaces）、Cgroups 与网络协议栈；
- **官方主流发行版**：开箱即用支持 Alpine Linux 3.24.1（轻量迅捷）与 Debian 12 Bookworm / 13 Trixie（全生态包仓库），支持使用原生 `apk` 与 `apt` 包管理器；
- **真实 Root 特权**：提供真正的 `uid=0 (root)` 权限环境，告别 PRoot 的假 root 破坏与权限模拟崩溃。

#### 🖥️ 3. Wayland 原生移动图形桌面与 High-DPI 缩放
- **Wayland-First 架构**：内置轻量级合成器与原生 Wayland 显示通道，支持一键拉起 Linux 图形应用；
- **NDK 高性能渲染管线**：基于 `ANativeWindow` 与 OpenGL ES 双缓冲流水线，保障流畅上屏；
- **动态 High-DPI 缩放协商**：自动适配高分屏移动设备，根据物理屏幕密度自适应调整缩放比例，告别界面字体过小；
- **双模热切换**：终端控制台与图形桌面平滑无缝切换，后台虚拟机生命周期不中断。

#### ⌨️ 4. 现代风格化 Shell 与即时交互终端
- **字符级按键即打即显（Real-time Keystroke Echo）**：告别单行输入弹窗，击键、退格与控制序列实时渲染；
- **现代化 Prompt 视觉**：集成风格化胶囊提示符与状态指示，提供高品质命令行交互体验；
- **人体工程学触控辅助栏**：内置 `ESC`、`TAB`、`CTRL`、`ALT`、方向键、管道符 `|` 与反引号等关键开发按键，触控区域完全符合触控规范。

#### 🌐 5. 完整网络协议栈与系统级深度互通
- **用户态出站 NAT**：虚拟机内部开箱即用访问公网，流畅进行软件包更新、Git 操作与网络请求；
- **动态运行态端口转发**：支持运行时添加/移除端口映射规则（如 `8080 -> 8080`, `22 -> 2222`）；
- **防回环双向剪贴板同步**：基于 SHA-256 哈希比对与容量保护，实现 Android 宿主与 Linux Guest 的剪贴板无缝双向互通；
- **低延迟音频通道**：内置 PulseAudio 48kHz PCM 单向低延迟音频串流通道。

#### 🎨 6. Material 3 移动生产力交互
- **卡片化实例管理**：清晰呈现实例运行状态、资源占用与快捷操作入口；
- **人体工程学规范**：所有核心交互按钮保证触摸靶心 $\ge 48\times 48\text{ dp}$，操作精准舒适；
- **硬件参数灵活定制**：支持自由配置虚拟 CPU 核心数、内存上限及磁盘容量。

#### 🛡️ 7. 安全合规与后台长效常驻
- **每实例高熵独立凭据**：杜绝默认弱密码，支持安全查看、复制与凭据重置；
- **Android 14+ 前台服务合规**：常驻运行态通知与系统唤醒锁（WakeLock），保障长时间编译与后台服务不中断；
- **零遥测隐私铁律**：更新检查可完全关闭；关闭状态下保持 0 外部网络连接，绝不收集设备信息或隐私数据。

---

### 3. 性能表现概览

- **极速热启动**：秒级极速热恢复进入终端；
- **交互低延迟**：基于本地双工数据通道，实现肉眼零延迟击键体验；
- **宿主内存友好**：轻量级内存开销，针对移动端资源限制深度调优；
- **磁盘弹性扩容**：支持稀疏镜像在线扩容与 Storage Access Framework (SAF) 零拷贝整机备份。

---

### 4. 快速上手指南

#### 4.1 运行要求
- **系统版本**：Android 8.0 (API 26) 或更高版本；
- **芯片架构**：ARM64 (arm64-v8a)；
- **物理内存**：建议 ≥ 3 GiB（低于配置时应用将提供友好提示以保护系统稳定性）；
- **存储空间**：建议可用内部存储空间 ≥ 4 GiB。

#### 4.2 极速上手三步法
1. **下载安装**：前往 [Releases 页面](https://github.com/Nyzeep/Orchestella-Studio/releases) 下载最新 APK 安装包；
2. **创建实例**：打开应用，选择创建 Alpine 或 Debian 实例，应用将自动完成初始环境校验；
3. **启动使用**：
   - 点击实例卡片中的「启动」进入交互终端；
   - 执行 `uname -a` 查看 Linux 6.6 LTS 内核信息；
   - 执行 `apk` 或 `apt` 安装所需开发工具链，尽享完整的移动 Linux 环境。

#### 4.3 硬件加速升级（可选）
在支持 Android 虚拟化框架（pKVM）的设备上：
- 可在应用向导中通过 **Shizuku** 一键完成虚拟化权限配置；
- 或在电脑端通过 ADB 执行授权：
  ```bash
  adb shell pm grant io.github.nyzeep.orchestella android.permission.MANAGE_VIRTUAL_MACHINE
  ```

---

<a name="english"></a>
## 🌐 English

### 1. Introduction

**Orchestella Studio** is a rootless full Linux virtual machine runtime and mobile workstation engineered for modern Android devices.

Unlike traditional PRoot containers, chroot hacks, or restricted terminal environments, Orchestella Studio runs an **independent virtual machine powered by a genuine Linux kernel (Linux 6.6 LTS)** for every instance. It eliminates PRoot's heavy `ptrace` overhead, avoids system call interception race conditions, and provides a genuine 100% industrial-grade Linux computing and graphical desktop experience right inside your pocket.

---

### 2. Architectural Highlights & Features

- **Dual Execution Engine Architecture**:
  - **AVF / pKVM Engine**: Delivers near-native CPU and memory performance through hardware virtualization on supported devices;
  - **QEMU TCG 8.2 Engine**: Universal software dynamic translation engine fallback providing 100% device compatibility;
  - **Honest Capability Matrix**: Transparently evaluates hardware capabilities and degrades gracefully without fake claims.
- **Genuine & Full Linux Operating System**:
  - Independent Linux 6.6 LTS kernel with full namespaces, cgroups, and network stack support;
  - Native distributions: Alpine Linux 3.24.1 & Debian 12/13 with official package managers (`apk` & `apt`);
  - True `uid=0 (root)` privilege inside the guest without host sandbox compromises.
- **Wayland Graphical Desktop & High-DPI Adaptation**:
  - Lightweight compositor integration and native Wayland graphics channel;
  - NDK `ANativeWindow` and OpenGL ES double-buffered rendering pipeline;
  - Dynamic High-DPI scale negotiation tailored for high-resolution mobile displays;
  - Smooth dual-mode hot switching between interactive terminal and desktop.
- **Modern Styled Shell & Real-time Keystroke Echo**:
  - Character-level interactive terminal engine with immediate keystroke echo, VT100 controls, and prompt styling;
  - Ergonomic mobile auxiliary key bar (`ESC`, `TAB`, `CTRL`, `ALT`, arrows, `|`, etc.) meeting Material 3 touch target standards.
- **Complete Networking & Deep Host Integration**:
  - Outbound NAT for internet access and dynamic runtime port forwarding;
  - Bidirectional clipboard synchronization with SHA-256 echo suppression;
  - Low-latency PulseAudio 48kHz PCM audio output streaming;
  - Storage Access Framework (SAF) zero-copy backup, export, and dynamic disk resizing.
- **Material 3 Productivity UI & Ergonomics**:
  - Touch target standards ($\ge 48\times 48\text{ dp}$) across all interactive controls;
  - Customizable virtual hardware allocation (vCPU cores, RAM limits, sparse disk).
- **Security & Background Longevity**:
  - High-entropy per-instance random credentials;
  - Android 14+ persistent foreground service with wake lock protection;
  - Zero-telemetry privacy policy with optional update notifications.

---

### 3. Quick Start

1. **Download & Install**: Grab the latest APK from the [Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) page.
2. **Create Instance**: Launch the app and select your preferred Linux distribution (Alpine or Debian).
3. **Launch & Develop**: Tap "Launch" to start the instance, access the shell, and start developing!

---

### 4. License

Orchestella Studio is distributed under the [Apache License 2.0](LICENSE).  
The guest kernel and QEMU components are licensed under the GNU General Public License (GPL) and strictly isolated at the distribution and process boundary in full compliance with ADR-0014.

# Orchestella Studio

<div align="center">

**无需 Root 的 Android 原生双引擎 Linux 运行环境与移动工作站**  
*Native Dual-Engine Full Linux Runtime & Mobile Workstation for Android Without Root*

[![Release](https://img.shields.io/badge/Release-v0.1.0--preview-blue.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)
[![License](https://img.shields.io/badge/License-Apache--2.0-orange.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg?style=flat-square)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg?style=flat-square)](https://developer.android.com/ndk/guides/abis)
[![Tests](https://img.shields.io/badge/Tests-100%25%20Passing%20(458%2B)-success.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)

[🇨🇳 简体中文](#-简体中文) | [🌐 English](#-english)

</div>

---

<a name="-简体中文"></a>
## 🇨🇳 简体中文

### 1. 项目简介

**Orchestella Studio** 是一款面向现代化 Android 设备的**免 Root 完整 Linux 虚拟机运行环境与移动专业开发工作站**。

与传统的 PRoot 容器、chroot 方案或受限终端不同，Orchestella Studio 依托 Android 原生生态，为每个运行实例提供**具备真实独立 Linux 内核（Linux 6.6 LTS）的完整系统虚拟机**。它彻底摒弃了 PRoot 对 `ptrace` 机制的系统调用拦截与性能损耗，消除了多线程竞态与伪特权陷阱，在移动设备上带来具备工业级稳定性的真实 Linux 开发、编译、网络与桌面体验。

在全新的 **v0.1.0-preview** 版本中，项目历经全面架构升级与工业级 UX 重构，引入了**媲美 VMware 的快速创建向导**、**7 大主流 Linux 发行版矩阵**、**二级实例详情控制台**、**外部电源与快照管理**、**直接键入式交互终端**及**多终端并发抽屉**等里程碑特性。

---

### 2. 核心架构与功能特性

```
+---------------------------------------------------------------------------------------------------+
|                                 Orchestella Studio (App Layer)                                    |
|   [Material 3 仪表盘]  [极简卡片]  [二级详情控制台]  [多终端滑出抽屉]  [直接键入Shell]  [Wayland桌面]       |
+-------------------------------------------------+-------------------------------------------------+
|          硬件虚拟化引擎 (AVF / pKVM)            |           软件虚拟化引擎 (QEMU TCG 8.2)           |
|        - 近原生硬件加速 (L2 硬件级)              |       - 全架构高保真动态翻译 (L1 软件级)           |
|        - Vsock 高性能双向桥接通道               |       - Unix 抽象套接字与桥接代理通道             |
|        - 自动透明优雅降级保障                   |       - 严格遵守 GPL 隔离与 JNI 纯净加载           |
+-------------------------------------------------+-------------------------------------------------+
|                                 不可变契约层 (:runtime:contract)                                  |
|   [Rootfs 装配]  [出站 NAT 网络]  [动态端口转发]  [双向剪贴板]  [音频与外设通道]  [PTY 多路复用]    |
+---------------------------------------------------------------------------------------------------+
|                             Linux Guest 独立虚拟机环境 (Linux 6.6 LTS)                             |
|   Ubuntu 24.04 | Debian 12 | Arch ARM | Fedora 41 | openSUSE Tumbleweed | Kali | Alpine 3.20  |
+---------------------------------------------------------------------------------------------------+
```

#### 🚀 1. 双执行引擎融合（Dual-Engine Architecture）
- **硬件虚拟化（AVF / pKVM 策略）**：在支持 Android 虚拟化框架的旗舰设备上，获得 CPU 与内存的近原生硬件加速；
- **软件虚拟化（QEMU TCG 8.2 策略）**：在未解锁虚拟化权限或普通设备上，自动无缝回退至工业级软件动态翻译引擎，确保 100% 机型覆盖；
- **透明能力感知与优雅降级**：内置严谨的宿主硬件探针，透明汇报当前性能档位，绝不虚标假体验。

#### 🧙 2. VMware 级快速创建向导与初始自动化装配
- **四步分步引导体验 (`SetupWizardActivity`)**：
  - **步骤 1：发行版矩阵选择**：可视化挑选主流官方发行版，支持极速轻量与完整开发生态；
  - **步骤 2：虚拟存储预检**：稀疏磁盘分配（Sparse raw image），内置宿主可用存储深度预检，容量不足时精准预警拦截；
  - **步骤 3：网络模式与国内镜像源配置**：出站 NAT 网络配置，内置官方源与国内高速镜像源（清华大学、阿里云、中科大等）下拉切换，可选首启自动执行更新；
  - **步骤 4：硬件配置与初始凭据注入**：灵活分配 vCPU 核心数、内存及 GPU 加速；配置主机名（默认 `orchestella`）与初始管理员凭据（默认 `root / 1234`），支持自定义普通用户与免密 sudo 权限。
- **Guest 首启管线联动 (`FirstBootProvisionPipeline`)**：在 Guest 内核启动时通过 initramfs 自动固化主机名、账户凭据、包管理器镜像源及首次启动脚本。

#### 🐧 3. 7 大主流 Linux 发行版矩阵与全包管理器覆盖
- **官方发行版矩阵支持**：
  - **Ubuntu 24.04 LTS (Noble Numbat)**：通用服务器与开发首选，全生态包支持；
  - **Debian 12 Bookworm**：极致稳定与通用基础平台；
  - **Arch Linux ARM Rolling**：最新软件栈，极客首选滚动发行版；
  - **Fedora 41 Minimal**：前沿技术体验；
  - **openSUSE Tumbleweed JeOS**：企业级高可靠滚动系统；
  - **Kali Linux Rolling**：网络安全与渗透测试环境；
  - **Alpine Linux 3.20**：极致轻量（~15MB 极速下载与启动），集成 `AlpineEssentialPackageSuite` 开箱常用工具链（bash, curl, tar, sudo, htop 等）。
- **5 大包管理器深度抽象**：统一支持 `APT`、`APK`、`PACMAN`、`DNF`、`ZYPPER`，提供标准的更新与软件安装支持。

#### 🎛️ 4. 极简卡片与全屏二级详情控制台（Instance Detail Console）
- **一级概览卡片极简化**：
  - 彻底移除主界面卡片上堆砌的滑块与易误触删除按钮；
  - 呈现发行版徽标、运行状态胶囊 Badge（`● 运行中`、`○ 已关机`、`⏸ 已挂起`、`⚠ 崩溃告警`）、硬件规格摘要及虚拟 IP；
  - 保留高优先级「快捷电源」操作，点击卡片主体通过 Material 缩放动画直达二级控制台。
- **全屏二级管理控制台 (`InstanceDetailActivity`)**：
  - **分区 1：头部状态看板与核心操作**：动态负荷看板，智能亮起 `[💻 打开终端]`、`[🖥️ 进入桌面]` 及 `[⚡ 电源管理]`；
  - **分区 2：硬件规格调整 (Hardware Specs)**：运行态严格**置灰锁定**硬件滑块，显著提示「请先关机后再调整硬件参数」，彻底消除运行中热改硬件引发内核崩溃的隐患；关机状态下提供内存安全阈值变色指示器；
  - **分区 3：存储与挂载管理 (Storage & Mounts)**：物理/逻辑容量对比、增量无损扩容与 SAF 零拷贝整机导出备份；
  - **分区 4：网络与端口转发 (Networking)**：NAT 虚拟 IP 查看，可视化增删端口映射规则（如 `8080 -> 8080`, `22 -> 2222`）；
  - **分区 5：危险管理区 (Danger Zone)**：一键克隆派生新实例、死锁状态复位自愈、物理删除二次输入实例名校验隔离。

#### ⚡ 5. 外部电源管理与 COW 状态快照系统
- **工业级外部电源控制 (`InstancePowerController`)**：
  - **平滑关机 (ACPI Shutdown)**：外部一键向 Guest 发送 ACPI 电源关机信号，安全同步脏页并携带 30 秒超时优雅等待守卫；
  - **强制断电 (Force Kill)**：不可阻挡地终止 VMM 进程，彻底回收宿主内存与网络端口；
  - **挂起与恢复唤醒 (Suspend & Resume)**：瞬间冻结 vCPU 调度与计算资源，进入 `SUSPENDED`（琥珀色）状态，恢复时秒级续接。
- **轻量级快照管理系统 (`InstanceSnapshotManager`)**：
  - 关机态下为磁盘根卷创建差分检查点，记录 `snapshots.json` 元数据索引；
  - 在系统变更或高危编译前打下快照节点，出现异常随时**一键秒级回滚 (`revertToSnapshot`)**，完全还原磁盘数据与硬件规格；
  - 支持多快照历史时间线与级联清理。

#### ⌨️ 6. 原生直接键入式交互终端与多终端并发抽屉
- **直接键入式终端交互**：
  - 彻底废除“输入框打字并确认”的落后设计，点击终端画布直接键入，所敲即所得；
  - 软键盘呼出随动、滚动查看与轻触打字精准防误触；
  - 物理与手势返回键、音量键精准透传，不再被终端盲目拦截；
  - 串行 FIFO 队列保障高频输入与粘贴零乱序；
  - 人体工程学移动辅助按键栏（`ESC`, `TAB`, `CTRL`, `ALT`, 方向键等）随软键盘平滑动态吸附。
- **后台会话常驻与零卡死连接池 (`TerminalSessionPool`)**：
  - 虚拟机与终端后台长效运行，切出切入秒级重连，彻底消灭历史版本 `[no live session]` 卡死缺陷。
- **多终端并发与右侧滑出抽屉 (`TerminalDrawerPanel`)**：
  - 屏幕右边缘向左滑动手势或点击右上角手柄（`◫ 终端 (N)`）呼出 Dracula 主题抽屉；
  - 单实例支持多达 16 个并发终端，后台各终端独占 Ring Buffer 独立输出，帧协议（`PtyMultiplexerProtocol`）杜绝串流；
  - 支持长按重命名终端标签、平滑会话切换与关闭活动会话时的指针自动迁移（Failover）。

---

### 3. 运行要求与快速上手

#### 3.1 运行要求
- **系统版本**：Android 8.0 (API 26) 或更高版本；
- **芯片架构**：ARM64 (arm64-v8a)；
- **物理内存**：建议 ≥ 3 GiB（4 GiB 及以上体验最佳）；
- **存储空间**：建议可用内部存储空间 ≥ 4 GiB。

#### 3.2 安装与上手
1. 前往 [Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) 下载最新的 `Orchestella-Studio-v0.1.0-preview.apk` 安装包；
2. 打开应用，点击首页 **「+ 创建新实例」** 按钮进入快速向导；
3. 选择发行版（如 Alpine 或 Ubuntu）、分配磁盘与内存、确认用户名与密码；
4. 点击卡片上的 **「启动」** 按钮，直接在终端中开始您的移动 Linux 工作流！

---

<a name="-english"></a>
## 🌐 English

### 1. Introduction

**Orchestella Studio** is a rootless full Linux virtual machine runtime and mobile workstation engineered for modern Android devices.

Unlike traditional PRoot containers, chroot hacks, or restricted terminal environments, Orchestella Studio runs an **independent virtual machine powered by a genuine Linux kernel (Linux 6.6 LTS)** for every instance. It eliminates PRoot's heavy `ptrace` system call interception overhead, avoids multi-threading race conditions, and provides an authentic 100% industrial-grade Linux computing, networking, and graphical desktop experience right inside your pocket.

In the brand-new **v0.1.0-preview** release, Orchestella Studio undergoes a monumental architectural elevation and UX overhaul, featuring a **VMware-grade 4-step creation wizard**, a **7-distro ecosystem matrix**, a **full-screen secondary detail console**, **external power and COW snapshot management**, **direct-input native interactive shell**, and **multi-terminal concurrent sessions with right slide-out drawer**.

---

### 2. Architectural Highlights & Features

- **Dual Execution Engine Architecture**:
  - **AVF / pKVM Engine**: Hardware-accelerated CPU and memory virtualization delivering near-native performance on supported devices;
  - **QEMU TCG 8.2 Engine**: Universal software dynamic binary translation fallback providing 100% device compatibility;
  - **Transparent Capability Matrix**: Rigorous hardware probe reporting honest performance tiers with zero false claims.
- **VMware-Grade 4-Step Creation Wizard & First-Boot Automation**:
  - Step 1: Visual distro selection across 7 major distributions;
  - Step 2: Sparse raw disk preflight checks with host storage exhaustion protection;
  - Step 3: Outbound NAT network setup and high-speed mirror repository configuration;
  - Step 4: Virtual hardware sizing and credentials provisioning (`orchestella` hostname, `root/1234` default credentials, custom sudo user).
  - Automated guest provisioning pipeline injecting `/etc/hostname`, `/etc/shadow`, mirror sources, and first-boot bootstrap scripts.
- **7 Major Linux Distributions & Unified Package Manager Abstraction**:
  - Ubuntu 24.04 LTS, Debian 12 Bookworm, Arch Linux ARM Rolling, Fedora 41 Minimal, openSUSE Tumbleweed JeOS, Kali Linux Rolling, and Alpine Linux 3.20 (with `AlpineEssentialPackageSuite`);
  - Unified package manager contract across `APT`, `APK`, `PACMAN`, `DNF`, and `ZYPPER`.
- **Minimalist Instance Cards & Full-Screen Secondary Detail Console (`InstanceDetailActivity`)**:
  - Clean overview cards displaying distro emblem, status badge, hardware spec summary, and IP address;
  - 5-section detail console: Header status billboard, hardware spec editor (locked during runtime to prevent kernel crashes), storage and SAF backup governance, network and port forwarding manager, and isolated danger zone with confirmation safeguards.
- **Industrial External Power Controls & COW Snapshot System**:
  - External ACPI graceful shutdown (30s guard), force kill, and suspend & resume (`SUSPENDED` state);
  - Lightweight snapshot manager (`InstanceSnapshotManager`) providing copy-on-write disk checkpoints, atomic metadata persistence (`snapshots.json`), and instant byte-for-byte rollbacks.
- **Direct-Input Native Terminal & Multi-Terminal Concurrency**:
  - Canvas direct input eliminating antiquated separate input boxes;
  - Smooth soft keyboard integration, touch slop scroll separation, and system key transparency;
  - Zero-lag persistent session pool eliminating `[no live session]` deadlocks;
  - Right slide-out gesture drawer managing up to 16 concurrent background terminals with zero cross-session data bleed.

---

### 3. Quick Start

1. Download the latest `Orchestella-Studio-v0.1.0-preview.apk` from [GitHub Releases](https://github.com/Nyzeep/Orchestella-Studio/releases).
2. Launch the app and tap **「+ Create Instance」** to walk through the creation wizard.
3. Choose your preferred distro (e.g., Alpine or Ubuntu), customize hardware specs and credentials.
4. Tap **「Launch」** on the instance card to start using your mobile Linux workstation!

---

### 4. License

Orchestella Studio is distributed under the [Apache License 2.0](LICENSE).  
The guest kernel and QEMU binaries are licensed under the GNU General Public License (GPL) and strictly isolated at the distribution and process boundaries in full compliance with ADR-0014.

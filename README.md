# Orchestella Studio

<div align="center">

**无需 Root 的 Android 原生双引擎 Linux 运行环境与移动工作站**  
*Native Dual-Engine Full Linux Runtime & Mobile Workstation for Android Without Root*

[![Release](https://img.shields.io/badge/Release-v0.2.0-blue.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)
[![License](https://img.shields.io/badge/License-Apache--2.0-orange.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg?style=flat-square)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg?style=flat-square)](https://developer.android.com/ndk/guides/abis)
[![Tests](https://img.shields.io/badge/Tests-100%25%20Passing-success.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)

[🇨🇳 简体中文](#-简体中文) | [🌐 English](#-english)

</div>

---

<a name="-简体中文"></a>
## 🇨🇳 简体中文

### 1. 项目简介

**Orchestella Studio** 是一款面向现代化 Android 设备的**免 Root 完整 Linux 虚拟机运行环境与移动专业开发工作站**。

与传统的 PRoot 容器、chroot 方案或受限终端模拟器不同，Orchestella Studio 依托 Android 原生软硬件生态，为每个运行实例提供**具备真实独立 Linux 内核（Linux 6.6 LTS）的完整系统虚拟机**。平台彻底摒弃了 PRoot 对 `ptrace` 机制的重度系统调用拦截损耗，规避了多线程竞态与伪特权陷阱，在移动设备上带来具备工业级稳定性的真实 Linux 开发、编译、容器、网络与桌面计算体验。

在全新的 **v0.1.5-preview** 版本中，项目全面攻坚七大官方发行版生态、多层级镜像源容灾网络、Material 3 容器呼吸感与纯正矢量图标重构、AVF 硬件虚拟化探针感知及直接键入式交互终端体验。

---

### 2. 系统整体架构

```
+---------------------------------------------------------------------------------------------------+
|                                 Orchestella Studio (App Layer)                                    |
|   [Material 3 呼吸感容器]  [官方纯正矢量图标]  [二级详情控制台]  [16并发终端抽屉]  [设置与测速中心]          |
+-------------------------------------------------+-------------------------------------------------+
|          硬件虚拟化引擎 (AVF / pKVM)            |           软件虚拟化引擎 (QEMU TCG 8.2)           |
|        - 近原生硬件加速 (L2 硬件级)              |       - 全架构高保真动态翻译 (L1 软件级)           |
|        - Vsock 高性能双向桥接通道               |       - Unix 抽象套接字与桥接代理通道             |
|        - 启动前自动探针感知与平滑降级           |       - 严格遵守 GPL 隔离与 JNI 纯净加载           |
+-------------------------------------------------+-------------------------------------------------+
|                                 不可变契约层 (:runtime:contract)                                  |
|   [多层级镜像源容灾]  [Docker镜像加速]  [毫秒测速优选]  [出站NAT网络]  [双向剪贴板]  [PTY多路复用]  |
+---------------------------------------------------------------------------------------------------+
|                             七大官方主流 Linux Guest 虚拟机环境 (Linux 6.6 LTS)                    |
|   Ubuntu 24.04 LTS | Debian 13 Stable (含Minimal) | Fedora 41 Workstation | Arch Linux Rolling   |
|   openSUSE Tumbleweed | AlmaLinux 9.5 | Rocky Linux 9.5  (APT / DNF / pacman / zypper)            |
+---------------------------------------------------------------------------------------------------+
```

---

### 3. 核心特性与技术突破

#### 🐧 1. 七大官方主流 Linux 发行版生态矩阵
根据开发者主流采用率与权威调研，全面提供以下七大发行版开箱支持：
- **Ubuntu LTS (24.04 LTS Noble Numbat)**：通用开发、云原生与团队协作首选，拥有最完备的第三方开发包生态；
- **Debian Stable (Debian 13 Trixie)**：稳如磐石，生产服务器、CI 节点与长期免维护场景基石；
- **Fedora Workstation (Fedora 41)**：前沿技术栈尝鲜，集成最新工具链与 Red Hat 生态深度支持；
- **Arch Linux (Rolling)**：极客 DIY 首选，滚动更新模型与全量 pacman / AUR 生态；
- **openSUSE Tumbleweed**：滚动更新并兼具企业级快照回滚能力的高可靠开发环境；
- **AlmaLinux (9.5)**：企业级生产模拟，1:1 二进制兼容 RHEL，超长生命周期；
- **Rocky Linux (9.5)**：CentOS 生产级替代品，面向企业部署验证的稳健选择。

全量抽象统一接入 `APT`、`DNF`、`pacman`、`zypper` 四大主流包管理器生命周期，涵盖镜像源注入、系统更新与软件包安装。

#### 🧹 2. 彻底移除 Alpine 实例与平滑承接方案
- **彻底下线**：全面清理 Alpine 实例类型、构建流水线与兼容残留；
- **决策考量**：Alpine 基于 musl libc 和 BusyBox，缺乏桌面支持与 GNU 完整工具链（缺少 apt、标准 ping、glibc 兼容层），严重影响开发者体验；
- **承接方案**：由 **Debian Stable 最小化安装（Debian Minimal）** 承接轻量化场景，兼顾极小资源开销与完备的 APT 包管理能力。

#### 🌐 3. 多层级容灾镜像源与 Docker 容器加速网络
解决移动网络与跨地域环境下依赖拉取缓慢、频繁失败的痛点：
- **系统源三层容灾机制**：
  - **第一层（国内主流云源）**：阿里云（Aliyun）、华为云（Huawei Cloud）、腾讯云（Tencent Cloud）；
  - **第二层（教育网镜像站）**：清华大学 TUNA、中国科学技术大学 USTC；
  - **第三层（官方上游仓库）**：各发行版全球官方主仓库作为终极兜底。
- **Docker 容器加速源预置**：实例首启默认配置多地域高可用加速源（`docker.xuanyuan.me`、`docker.1ms.run`、`docker.m.daocloud.io` 等），并保留官方 Docker Hub 兜底。
- **一键测速与自动优选 (`MirrorSpeedTester`)**：向导与独立设置页内置毫秒级并发连通测速，一键自动锁定当前网络环境下响应最快的优质镜像节点。
- **二级设置中心 (`SettingsActivity`)**：支持查看当前镜像源配置、增删自定义源、测速选优及一键恢复出厂预设。

#### 🎨 4. Material 3 视觉呼吸感规范与官方品牌纯正矢量
- **官方纯正品牌矢量图标**：
  - **Ubuntu**：重制为 Canonical 官方橙色圆环底徽 + 内部白色 Circle of Friends（友谊之环 3 人环切矢量），杜绝暗色背景下的走样；
  - **Debian / Fedora / Arch / openSUSE / AlmaLinux / Rocky**：全量提取各发行版官方 SVG 规范矢量重构。
- **16dp 呼吸感边距重构**：修复 `WindowInsetsListener` 覆写清空横向 padding 的根本缺陷，全界面统一注入 `16dp` 安全内边距，彻底消除卡片贴边相切。
- **硬件规格选择器修复**：重写磁盘预设单选状态逻辑，彻底消除原 8GB 按钮粘滞高亮缺陷；选中态应用高对比度实心填充，未选中态还原轻量线框。
- **显性下拉菜单**：创建向导配备带边框与 `▼` 三角箭头的定制下拉选择组件，清晰展示发行版官方图标、版本标签与特性说明。

#### 🛡️ 5. AVF 硬件虚拟化探针与透明降级保障
- **硬件探针检测**：启动向导自动调用底层探针检测设备是否支持 AVF / pKVM 硬件虚拟化扩展；
- **透明降级说明**：未开放硬件虚拟化权限时，清晰呈现 QEMU TCG 动态翻译降级策略，确保 100% 完整支持所有 7 大发行版与开发工具链；
- **灵活向导**：提供“跳过引导”选项，满足熟练用户直达主界面的诉求。

#### ⌨️ 6. 直接键入式原生交互终端与 16 并发会话抽屉
- **直接触控键入**：点击终端画布直接唤起软键盘并键入，彻底废除落后的单行输入框提交模式；
- **闪烁光标感知**：终端末尾常驻动态闪烁光标，直观提示当前输入焦点状态；
- **`clear` 命令原生支持**：修复 ANSI ESC 序列解析，支持标准 `clear` 命令即时清空终端屏幕并将光标移回顶行；
- **16 并发终端抽屉与长效会话池**：右边缘手势滑出 Dracula 终端抽屉，支持最多 16 个并发后台会话无死锁常驻，切出切入秒级重连。

#### 🎛️ 7. 全屏二级控制台与 ACPI 外部电源/快照管理
- **全屏二级管理控制台 (`InstanceDetailActivity`)**：集成动态看板、硬件规格调整（运行态防热改置灰锁定）、SAF 存储管理、NAT 端口映射及危险隔离区；
- **工业级外部电源控制**：ACPI 平滑关机（30 秒超时优雅等待守卫）、强制断电（Force Kill）与挂起恢复唤醒；
- **COW 状态快照**：高危编译与系统变更前一键打下差分快照，出现故障秒级回滚。

---

### 4. 运行要求与快速上手

#### 4.1 运行要求
- **系统版本**：Android 8.0 (API 26) 或更高版本；
- **芯片架构**：ARM64 (arm64-v8a)；
- **物理内存**：建议 ≥ 3 GiB（4 GiB 及以上体验最佳）；
- **存储空间**：建议可用内部存储空间 ≥ 4 GiB。

#### 4.2 安装与上手指南
1. 前往 [Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) 下载最新的 `Orchestella-Studio-v0.1.5.apk`（或 `Orchestella-Studio-v0.1.5-preview.apk`）安装包；
2. 打开应用，欢迎向导将自动展示设备虚拟化支持状态（支持跳过）；
3. 点击 **「+ 创建 Linux 实例」**，通过下拉菜单挑选心仪发行版（如 Ubuntu 24.04 LTS 或 Debian 13）；
4. 分配磁盘规格并一键测速优选镜像源；
5. 创建完成后在卡片上点击 **「启动」**，直接在全功能终端中开启移动 Linux 开发之旅！

---

### 5. 质量工程与测试门禁

- **单元测试通过率**：全模块纯 JVM 测试通过率 **100%**；
- **构建门禁**：通过 Android `lintVitalRelease` 严苛校验；
- **真机实测**：在 Android 15 实体设备（HONOR AGT-AN00）上完成下拉菜单切换、磁盘高亮状态切换、镜像测速及终端交互全流程实机验证。

---

### 6. 安装包资产与校验

| 资产文件名 | 文件大小 | 适用架构 | 签名状态 |
| :--- | :--- | :---: | :---: |
| **`Orchestella-Studio-v0.1.5.apk`** | **57.3 MB** (60,101,387 字节) | `ARM64-v8a` (Android 8.0+) | 🟢 已签名 (Release Keystore) |
| **`Orchestella-Studio-v0.1.5-preview.apk`** | **57.3 MB** (60,101,451 字节) | `ARM64-v8a` (Android 8.0+) | 🟢 已签名 (Release Keystore) |

#### SHA-256 完整性校验哈希
```text
# v0.1.5 (正式版)
1106D924EF1DCEB1FD5DE365CBB237EBBD76ED1AC31BAB07196D8DBE3EDEDAD8

# v0.1.5-preview (预览版)
F340C5FF34F6667FBA4B7CE4CE83475DADB062179220BBD53F9CAACB1E90E1E4
```

---

<a name="-english"></a>
## 🌐 English

### 1. Introduction

**Orchestella Studio** is a rootless, full Linux virtual machine runtime and mobile workstation engineered for modern Android devices.

Unlike traditional PRoot containers, chroot hacks, or restricted terminal emulators, Orchestella Studio runs an **independent virtual machine powered by a genuine Linux kernel (Linux 6.6 LTS)** for every instance. It eliminates PRoot's heavy `ptrace` system call interception overhead, avoids multi-threading race conditions and pseudo-privilege traps, and provides an authentic industrial-grade Linux computing, development, networking, and graphical desktop experience right inside your pocket.

In the brand-new **v0.1.5-preview** release, Orchestella Studio delivers an expansive 7-distro official ecosystem, a multi-tier high-availability mirror fallback matrix with Docker acceleration, Material 3 16dp edge breathing room and official brand vector icons, hardware virtualization probe awareness, and direct-input interactive shell enhancements.

---

### 2. System Architecture

```
+---------------------------------------------------------------------------------------------------+
|                                 Orchestella Studio (App Layer)                                    |
|   [Material 3 Breathing Layout]  [Official Vector Icons]  [Detail Console]  [16 Drawer Terminals] |
+-------------------------------------------------+-------------------------------------------------+
|          Hardware Virtualization (AVF / pKVM)   |           Software Virtualization (QEMU TCG 8.2)|
|        - Near-native hardware acceleration      |       - Full-architecture binary translation    |
|        - High-speed vsock dual-direction bridge |       - Unix abstract socket & proxy channels   |
|        - Automatic probe & transparent fallback |       - Strict GPL isolation & clean JNI loader |
+-------------------------------------------------+-------------------------------------------------+
|                                 Immutable Contract Layer (:runtime:contract)                      |
|   [Multi-Tier Mirrors]  [Docker Mirrors]  [1-Click Speedtest]  [Outbound NAT]  [PTY Multiplexer]  |
+---------------------------------------------------------------------------------------------------+
|                             7 Official Linux Guest Virtual Machines (Linux 6.6 LTS)               |
|   Ubuntu 24.04 LTS | Debian 13 Stable (incl. Minimal) | Fedora 41 Workstation | Arch Linux Rolling|
|   openSUSE Tumbleweed | AlmaLinux 9.5 | Rocky Linux 9.5  (APT / DNF / pacman / zypper)            |
+---------------------------------------------------------------------------------------------------+
```

---

### 3. Key Features & Innovations

#### 🐧 1. 7 Official Mainstream Linux Distributions
Built around developer demand and community surveys, providing out-of-the-box support for:
- **Ubuntu LTS (24.04 LTS Noble Numbat)**: General development, cloud-native, and team collaboration with the richest package ecosystem;
- **Debian Stable (Debian 13 Trixie)**: Rock-solid stability for production servers, CI nodes, and long-term maintenance;
- **Fedora Workstation (Fedora 41)**: Cutting-edge toolchains and deep Red Hat ecosystem integration;
- **Arch Linux (Rolling)**: Geeks' DIY choice with rolling-release packages and complete pacman / AUR ecosystem;
- **openSUSE Tumbleweed**: Rolling release coupled with enterprise-grade snapshot rollback capabilities;
- **AlmaLinux (9.5)**: Production simulation with 1:1 RHEL binary compatibility and long lifecycle;
- **Rocky Linux (9.5)**: Enterprise-grade CentOS replacement for production deployment validation.

Unified lifecycle abstraction across `APT`, `DNF`, `pacman`, and `zypper` package managers.

#### 🧹 2. Complete Alpine Deprecation & Debian Minimal Transition
- **Complete Removal**: Eradicated all Alpine instance types, build pipelines, and legacy remnants;
- **Architectural Rationale**: Alpine's musl libc and BusyBox lack desktop support and GNU tools (no apt, ping, or glibc compatibility), degrading developer workstation usability;
- **Smooth Replacement**: **Debian Stable Minimal Installation (Debian Minimal)** seamlessly takes over ultra-lightweight scenarios, preserving full APT package management and GNU toolchains.

#### 🌐 3. Multi-Tier High-Availability Mirrors & Docker Acceleration
Eliminates package download timeouts and network stalls on mobile devices:
- **3-Tier Package Repository Matrix**:
  - **Tier 1 (Domestic Cloud CDNs)**: Aliyun, Huawei Cloud, Tencent Cloud;
  - **Tier 2 (University Mirrors)**: Tsinghua University TUNA, USTC;
  - **Tier 3 (Official Upstream Repos)**: Global official repositories as reliable failover.
- **Docker Container Acceleration Mirrors**: Pre-configured with resilient registry mirrors (`docker.xuanyuan.me`, `docker.1ms.run`, `docker.m.daocloud.io`, etc.) with official Docker Hub fallback.
- **One-Click Speedtest & Auto-Selection (`MirrorSpeedTester`)**: Concurrent millisecond latency testing to lock the fastest mirror in seconds.
- **Secondary Settings Console (`SettingsActivity`)**: Inspect mirrors, customize sources, trigger speedtests, or restore defaults.

#### 🎨 4. Material 3 Breathing Layout & Official Brand Vector Emblems
- **Authentic Brand Vector Emblems**:
  - **Ubuntu**: Redrawn with Canonical's official orange roundel and white Circle of Friends (CoF);
  - **Debian / Fedora / Arch / openSUSE / AlmaLinux / Rocky**: Reconstructed directly from official SVG brand guidelines.
- **16dp Breathing Space**: Resolved `WindowInsetsListener` clearing horizontal margins, injecting uniform `16dp` safe margins across all screens.
- **Disk Spec Preset Selection**: Fixed sticky highlight bug on 8GB button; active options receive high-contrast solid fills, inactive options revert to clean outlines.
- **Explicit Dropdown Menu**: Custom bordered dropdown with `▼` arrow indicators showcasing distro logos, version tags, and positioning descriptions.

#### 🛡️ 5. AVF Hardware Virtualization Probe & Graceful Degradation
- **Hardware Probe**: Launch wizard tests device support for AVF / pKVM virtualization extensions;
- **Transparent Fallback**: When hardware acceleration is unavailable, transparently falls back to QEMU TCG dynamic translation without breaking any of the 7 distros;
- **Flexible Flow**: "Skip Wizard" option allows experienced users to bypass onboarding directly to the main screen.

#### ⌨️ 6. Direct-Input Native Shell & 16 Concurrent Drawer Terminals
- **Direct Canvas Typing**: Tap terminal canvas to summon software keyboard and type directly, abolishing antiquated single-line input boxes;
- **Blinking Cursor**: Persistent blinking cursor at line tail indicates active focus;
- **ANSI `clear` Support**: Correctly clears screen and resets cursor to top line;
- **16 Concurrent Drawer Sessions**: Slide-out Dracula drawer manages up to 16 concurrent background sessions without deadlocks.

#### 🎛️ 7. Full-Screen Detail Console & ACPI External Power / Snapshots
- **Full-Screen Console (`InstanceDetailActivity`)**: Billboard status, runtime hardware spec locking to prevent kernel crashes, SAF storage manager, and NAT port forwards;
- **Industrial Power Controls**: ACPI graceful shutdown (30s guard), Force Kill, and Suspend & Resume;
- **COW Snapshots**: Instant differential checkpoints and byte-for-byte rollbacks before risky commands.

---

### 4. Requirements & Quick Start

#### 4.1 System Requirements
- **OS**: Android 8.0 (API 26) or higher;
- **Architecture**: ARM64 (arm64-v8a);
- **Memory**: Recommended ≥ 3 GiB (4 GiB+ optimal);
- **Storage**: Recommended internal storage ≥ 4 GiB.

#### 4.2 Quick Start
1. Download the latest `Orchestella-Studio-v0.1.5.apk` (or `Orchestella-Studio-v0.1.5-preview.apk`) from [GitHub Releases](https://github.com/Nyzeep/Orchestella-Studio/releases).
2. Launch the app and check virtualization status in the wizard (or tap Skip).
3. Tap **「+ Create Linux Instance」**, pick your distro from the dropdown (e.g. Ubuntu 24.04 LTS or Debian 13).
4. Allocate disk space and run 1-click speedtest to select the fastest mirror.
5. Tap **「Launch」** on the instance card to start your mobile Linux workstation!

---

### 5. Quality Assurance & Verification

- **Unit Test Pass Rate**: **100%** pure JVM test pass rate across all modules;
- **Build Verification**: Clean pass through Android `lintVitalRelease`;
- **Physical Device Validation**: Fully tested on Android 15 (HONOR AGT-AN00) for dropdown menus, disk selection, speed testing, and terminal interactions.

---

### 6. Release Asset & Integrity Verification

| Asset Filename | Size | Architecture | Signature |
| :--- | :--- | :---: | :---: |
| **`Orchestella-Studio-v0.1.5.apk`** | **57.3 MB** (60,101,387 bytes) | `ARM64-v8a` (Android 8.0+) | 🟢 Signed (Release Keystore) |
| **`Orchestella-Studio-v0.1.5-preview.apk`** | **57.3 MB** (60,101,451 bytes) | `ARM64-v8a` (Android 8.0+) | 🟢 Signed (Release Keystore) |

#### SHA-256 Integrity Hash
```text
# v0.1.5 (Official Release)
1106D924EF1DCEB1FD5DE365CBB237EBBD76ED1AC31BAB07196D8DBE3EDEDAD8

# v0.1.5-preview (Preview Release)
F340C5FF34F6667FBA4B7CE4CE83475DADB062179220BBD53F9CAACB1E90E1E4
```

---

### 7. License

Orchestella Studio is licensed under the [Apache License 2.0](LICENSE).  
The guest kernel and QEMU binaries are licensed under the GNU General Public License (GPL) and strictly isolated at process and binary boundaries in full compliance with ADR-0014.

# Orchestella Studio

<div align="center">

**无需 Root 的 Android 原生 Linux 虚拟机与移动工作站运行环境**  
*Native Full-Linux VM Runtime & Mobile Workstation for Android — No Root Required*

[![Release](https://img.shields.io/badge/Release-v0.2.0-blue.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases)
[![License](https://img.shields.io/badge/License-Apache--2.0-orange.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg)](#)
[![CI](https://img.shields.io/github/actions/workflow/status/Nyzeep/Orchestella-Studio/ci.yml?branch=main&label=CI&style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)

**🚧 项目处于积极演进期（Active Development）**  
当前版本提供公测预览能力（v0.2.0），底层内核架构已在 Android 15 实体机完成全链路闭环验证。

</div>

---

## 📖 核心概述

**Orchestella Studio** 致力于在**无需 Root 权限**的现代 Android 移动设备上，提供极致优雅、性能卓越的**完整 Linux 操作系统与移动开发者工作站**。

与传统的 PRoot/chroot 用户空间系统调用拦截容器不同，Orchestella 为每个 Linux 实例提供**独立的 Linux 内核空间、独立虚拟磁盘驱动器与完备的用户态网络栈**。无论是开发调试、运行后台服务、编译构建代码，还是探索现代 Linux 发行版，您都将拥有近乎原生 Linux 的操控体验。

---

## 🌟 核心亮点与特性矩阵

### 1. 双引擎自适应虚拟化架构
* **硬件级虚拟化（AVF / pKVM）**：针对 Android 13+ 且支持 Google AVF（Android Virtualization Framework）的旗舰芯片，自动启用微内核硬件级隔离，提供接近原生的指令吞吐与极低延迟。
* **软件仿真虚拟化（QEMU TCG aarch64）**：针对所有搭载 64 位 ARM 处理器的 Android 设备（Android 8.0+），提供基于高度优化动态二进制翻译的通用虚拟化兼容层。

### 2. 现代科技美学与分级导航重构（UI/UX）
* **深空暗黑与极简明亮双重美学**：
  * **Moonshot AI（月之暗面）科技深空**：深邃背景色（`#0B0F19`）、毛玻璃半透明卡片与 1px 微光轮廓线，带来未来移动终端沉浸感。
  * **Claude 优雅纸感浅色**：采用温润米白底色（`#FAF8F5`）与大地赤褐色高亮（`#D97706`），长时间文本阅读不刺眼。
* **二级导航分级架构**：
  * 告别冗长无序的单页滚动！控制台提供标准 **5 级二级选项卡**：
    * `📌 概览`：运行状态、SSH 快捷指令、架构与环境特征。
    * `⚡ 硬件`：动态 vCPU 分配、运行内存与安全阈值监管。
    * `💾 存储`：虚拟磁盘拓扑、挂载点及读写配额。
    * `🌐 网络`：虚拟 IP 状态、GVProxy 端口映射与用户态网关。
    * `⚠️ 维护`：高危操作专区分离，配备解锁安全锁机制。
* **居中磁贴化控制面板**：操作按钮采用 `iconGravity="textTop"` 结构，居中对齐，彻底消除中文多字排版在小屏上的折行尴尬。

### 3. 对标 Termux 与移动终端的最佳输入体验
* **全功能横向滑动辅助按键栏**：
  * 集成移动端开发者高频按键：`ESC`、`TAB`、`CTRL`、`ALT`、`^C`、`^Z`、`^D`、`^E`、`CLR`、`PASTE`、`HOME`、`END`。
  * 常用 Shell 字符快捷触达：`|`、`~`、`/`、`-`、`_`、`=`、`$`。
  * 具备 **Sticky Modifier（粘滞键）** 视觉高亮提示，组合键单手操作无门槛。
* **多会话终端管理（Multi-Session Sheet）**：
  * 顶部常驻悬浮会话指示器，一键拉起多终端会话抽屉，支持创建、切换与关闭多个并发 Shell 窗口。
* **高对比度 ANSI 调色板自适应**：
  * 动态适配明暗主题，Terminal 文字边缘锐利清晰，杜绝浅色模式下的灰白文本看不清问题。

### 4. 对标 Termius 的专业级 SSH 远程终端集成
* **内置 Dedicated SSH Profile Manager**：
  * 为每个虚拟机实例自动分配并管理本地网络回环端口（默认 `2222 -> 22`）。
  * 详情页支持一键复制标准连接命令：`ssh -p 2222 root@127.0.0.1`。
  * 提供图形化 SSH 配置文件管理，方便配合外部第三方 SSH 客户端无缝桥接。

### 5. 7 大主流官方发行版阵容与运行时韧性加固
* **官方原版校验**：支持 Ubuntu (24.04/22.04)、Debian (12 Bookworm)、Fedora (41/42)、Arch Linux、openSUSE (Leap 15.6/16)、AlmaLinux (9)、Rocky Linux (9)。
* **多源镜像自动故障转移（Failover）**：
  * 境内优先调度清华大学 TUNA 开源镜像站、阿里云镜像站加速下载（真机实测单线程拉取达 8.3 MB/s）。
  * 官方源与全球 CDN 自动备选容灾。
* **极简 Rootfs 启动诊断与网络优化**：
  * initramfs 内置多重 DNS 冗余（阿里 `223.5.5.5`、腾讯 `119.29.29.29`、Cloudflare `1.1.1.1`、Google `8.8.8.8`）。
  * 智能嗅探诊断逻辑：在极简镜像缺少 `iputils-ping` 时优雅放行，杜绝假性报错中断安装。

---

## 🏗️ 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    Orchestella App UI                       │
│  (Material 3 / Moonshot & Claude 设计语言 / 二级选项卡导航)   │
└──────────────────────────────┬──────────────────────────────┘
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
┌───────────────────────────────┐   ┌─────────────────────────┐
│     AVF / pKVM 引擎 (硬件)     │   │   QEMU 引擎 (TCG 软件)   │
│   (Android 13+ Microdroid)    │   │ (ARM64 JNI + VirGL 3D)  │
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
│   • initramfs 自愈装配与 EXT4 虚盘挂载                        │
│   • 多发行版 Rootfs (Ubuntu / Debian / Arch / Fedora 等)     │
│   • orchestella-agent 控制平面与双向串口 Shell               │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚀 快速开始

### 安装与运行
1. **下载安装**：从 [Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) 获取最新的预编译 APK（要求 Android 8.0+，ARM64 架构）。
2. **启动向导**：
   - 打开 Orchestella Studio，点击首页的 **「🚀 选择发行版并启动创建向导」**。
   - 选择您偏好的发行版（如 Ubuntu 24.04），选择就近镜像源（默认国内智能优选清华源）。
   - 根据手机剩余空间与性能配置 vCPU 与磁盘配额（推荐 4 核 / 4GB 内存 / 8GB 虚拟磁盘）。
3. **初始化与开机**：
   - 向导确认后自动进入秒级拉取与磁盘自愈装配阶段。
   - 装配完成后点击 **「开机」**，系统将在数十秒内引导完成并直接唤起交互式 Starship 终端。

### SSH 外部连接
当虚拟机开机后，在 **「详情控制台」** 概览页直接点击 **「📋 复制命令」**，即可在宿主机或局域网中使用任何 SSH 客户端连接：
```bash
ssh -p 2222 root@127.0.0.1
```

---

## 🛠️ 本地编译与构建

确保您的开发机已正确安装：
* JDK 17 (推荐 Eclipse Temurin 17)
* Android SDK Platform 36 & Build-Tools 36.1.0
* Android NDK 27+ & CMake 3.22+

```bash
# 1. 克隆代码仓库
git clone git@github.com:Nyzeep/Orchestella-Studio.git
cd Orchestella-Studio

# 2. 运行协议合规检查与基础单元测试
./gradlew :runtime:contract:check :app:testDebugUnitTest

# 3. 运行 Guest Assets 资产测试
./gradlew -p guest-assets test

# 4. 组装生成 Debug APK
./gradlew assembleDebug
```

---

## 📱 真机验证基准报告

本项目的核心能力已在实体物理设备上通过全链路 ADB 联调验收：

| 校验维度 | 实测数据 / 环境指标 |
| :--- | :--- |
| **测试机型** | 荣耀 Honor AGT-AN00 (搭载 MagicOS) |
| **操作系统** | Android 15 (API Level 35) / Linux 6.x aarch64 |
| **运行时引擎** | QEMU TCG aarch64 + GVProxy SLIRP NAT |
| **镜像拉取速度**| 清华大学源 (Ubuntu 24.04 Rootfs) 达到 **8.3 MB/s** |
| **稳定性指标**| 启动、终端操作、多任务切换过程中 **Logcat 崩溃为 0** |
| **渲染性能**  | 终端与 UI 界面 SurfaceFlinger 稳定保持 **60 FPS** |

---

## ⚠️ 已知边界与说明

1. **软件仿真性能特性**：在未搭载 pKVM 硬件虚拟化特性的旧款或未授权机型上，QEMU TCG 需执行动态二进制翻译，速度受 CPU 单核性能制约。这是 Android 无 Root 虚拟机技术客观边界。
2. **厂商后台管控策略**：部分厂商系统（如 EMUI/MagicOS/MIUI）具备严格的激进杀后台策略，长时间高负载任务建议在系统设置中将 Orchestella 设为「允许后台活动 / 无限制」。
3. **部分国产机型安装确认**：首次通过 ADB 侧载安装时，系统可能弹出安全模式提醒，需手动勾选并允许安装。

---

## 📜 许可证

本项目开源许可证为 **Apache License 2.0**。  
底层引用的第三方开源组件（包括但不限于 QEMU、Linux Kernel、Busybox、Dropbear 等）均严格保留其原作者著作权及对应的 GPL/LGPL/MIT 开源授权。详细信息参见各目录下的许可证及 `NOTICE` 文件。

---

## 🤝 参与贡献与社区交流

欢迎提交 Issue 与 Pull Request！
* 提交 Bug 请附带：**「控制台详情页 → 应用日志 / Linux 日志」** 输出以及机型与 Android 版本信息。
* 代码合并请严格遵循项目既有的代码规范，并通过 `./gradlew check` 自动化流水线。

# Orchestella Studio

<div align="center">

**无需 Root 的 Android 原生 Linux 虚拟机运行环境**  
*Native Full-Linux VM Runtime for Android — No Root Required*

[![Release](https://img.shields.io/badge/Release-v0.2.0-blue.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases)
[![License](https://img.shields.io/badge/License-Apache--2.0-orange.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg)](#)

**🚧 项目正在积极开发中（Active Development）**  
当前版本为公开预览（v0.2.0），功能持续演进，[已知问题与限制](#已知问题与限制-)见下文。

</div>

---

## 这是什么

Orchestella 在**不需要 Root** 的 Android 手机上运行**真正的 Linux 虚拟机**：

- **完整 Linux 内核**（非 PRoot/chroot 模拟）：每个实例是带独立内核的 VM，真实 root 权限、真实文件系统；
- **双引擎**：AVF/pKVM 硬件虚拟化（设备支持时）与 QEMU 软件虚拟化（全设备兼容）自动选择；
- **7 大主流发行版**：Ubuntu / Debian / Fedora / Arch / openSUSE / AlmaLinux / Rocky，全部经官方 SHA256 校验的多层镜像分发；
- **现代终端**：ANSI 256 色、多输出视图、快捷键辅助栏、运行日志查看；
- **移动工作站能力**：端口转发、只读 Downloads 共享、SAF 备份导入导出、主题自定义（内置 Catppuccin / Dracula / Nord 等 13 套）。

## 快速开始

1. 从 [Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) 下载最新 APK 安装（ARM64，Android 8.0+）；
2. 打开 App → 选择发行版 → 跟随向导完成配置（镜像源/磁盘/硬件规格）；
3. 首次启动会自动完成系统装配（**首次约需数分钟**，取决于机型与网络），完成后自动进入 Shell；
4. `apt update` / `pacman -Syu` / `dnf update` 即可使用。

## 已知问题与限制 🚧

本项目仍在开发中，以下为**当前已知**的问题与平台级限制（部分受 Android 系统不可抗力约束）：

- **软件虚拟化性能**：无 pKVM 的设备使用 QEMU TCG 模拟，速度明显慢于真机（这是无 Root 场景的技术边界，非缺陷）；
- **华为/鸿蒙设备安装**：adb 安装需在手机上手动确认「USB 安装」弹窗；
- **终端输入**：极快连续输入时可能丢字；外接键盘场景更稳定；
- **多终端标签**：当前为同一 Shell 的多路输出视图（界面已如实标注），真·多路 PTY 在路线图中；
- **厂商碎片化**：不同厂商对后台进程的管控策略不同，极端情况下 VM 可能被系统回收。

> 💡 **遇到问题请提 [Issue](https://github.com/Nyzeep/Orchestella-Studio/issues)** —— 附上「实例详情 → Linux 日志 / 应用日志」的输出，可以直接定位问题。每一份反馈都会进入修复队列。

## 技术架构（简）

```
App（Kotlin/Java, Material 3）
 ├─ QEMU 引擎（打包于 jniLibs，TCG）+ VirGL GPU 加速
 │    └─ 网络：gvproxy 用户态网关（gVisor netstack，无 Root NAT）
 ├─ AVF/pKVM 引擎（设备支持时）
 ├─ Guest：initramfs 自愈装配 + 多发行版 rootfs + guest-agent 桥接
 └─ 持久化：实例元数据 / 稀疏根盘 / SAF 导出
```

## 构建

```bash
# Android SDK + NDK 27 + CMake 3.22 已就绪后：
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest   # JVM 单元测试
```

## 版本与更新

版本号遵循 [Semantic Versioning 2.0.0]（`MAJOR.MINOR.PATCH[-preview]`，预发布后缀 `-alpha/-beta/-rc`）：修复递增 PATCH、新功能递增 MINOR、不兼容变更递增 MAJOR；发布前 `versionName`、关于页、Release tag 与 README 徽章强制一致。

## 许可证

Apache License 2.0。第三方组件（QEMU/busybox/Linux kernel 等）按其各自许可证分发，见 `NOTICE` 与对应源码发布说明。

## 参与贡献

- 提交 [Issue](https://github.com/Nyzeep/Orchestella-Studio/issues)：bug 报告请附「Linux 日志 + 应用日志」输出；
- PR 欢迎遵循现有代码风格与 ADR 决策记录。

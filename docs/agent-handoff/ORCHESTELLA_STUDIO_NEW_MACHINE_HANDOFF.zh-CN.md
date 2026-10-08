# Orchestella Studio 全栈交接与新电脑接入指引（Engineering & Strategy Handoff）

**更新时间**：2026-10-08  
**当前版本阶段**：**Phase 4.5（成品稳定性与真机体验死磕打磨期）**  
**发版基线**：`v0.4.0`（GitHub Latest Release）+ Phase 4.5 核心缺陷歼灭与品牌对齐提交  
**代码主仓**：`https://github.com/Nyzeep/Orchestella-Studio`（分支：`main`）  
**文档性质**：**新电脑开发接管权威指南与项目现状全景汇报**

---

## 🧭 一、项目宏观定位与设计哲学

**Orchestella Studio**（曾用工作名 DroidLayer）是一款面向现代化 Android 设备的**免 Root 原生双引擎 Linux 虚拟机运行环境与移动工作站**。

### 核心设计原则
1. **真实 Linux 内核，非 PRoot / chroot**：
   - 绝非基于 `ptrace` 系统调用劫持的用户态模拟，而是为每个实例运行一个具备真实 Linux 内核（自研 guest-assets 7.2.3 内核）的独立虚拟机；
   - 彻底摆脱伪特权、系统调用竞态与性能惩罚，支持真实的 namespace、cgroup、systemd 与容器环境。
2. **双引擎透明降级（Dual-Engine Seamless Degradation）**：
   - **L2 硬件虚拟化（AVF / pKVM）**：面向支持 Android Virtualization Framework 的设备，实现近原生 CPU 性能与硬件直通；
   - **L1 软件虚拟化（QEMU TCG 8.2+）**：面向未开放 AVF 或受 OEM 厂商策略拒止的设备，提供全指令集兜底兼容；
   - 统一由不可变契约层（`:runtime:contract`）调度，上层 UI 与业务逻辑完全中立。
3. **移动优先的高性能交互**：
   - 双重呈现态：极简高性能 PTY 终端（`TerminalActivity`）与沉浸式 Wayland 硬件加速桌面（`DesktopActivity`）；
   - NDK 原生双 PBO 循环渲染上屏（`orchestella_gles`）、Mesa VirGL 3D 渲染加速、低延迟 AudioTrack 音频通道、双向剪贴板同步桥与 SAF 零拷贝数据导入导出。

---

## 🏆 二、本次策略会话攻关与缺陷歼灭战果（Phase 4.5 最新成就）

在 `v0.4.0` 发版后，真机体验测试中暴露出数个隐藏较深且严重的系统级缺陷。本策略会话坚决执行 **“不推 Phase 5 新功能，死磕打磨现阶段稳定性”** 的方针，全部予以根除：

| 缺陷编号 | 严重级别 | 缺陷现象 | 根本原因 | 修复机制与交付成果 |
| :---: | :---: | :--- | :--- | :--- |
| **D1** | **P0** | 干净设备上 Debian 13 根文件系统下载全路径 404 | 资产仓库私有化导致 Release 不可达，快照源失效 | 仓库公开化（Public），统一接入 `DistroMirrorCatalog`，实机验证 SHA-256 校验 100% 吻合 |
| **D2** | **P0** | `runtime-artifact-checksum-mismatch`：QEMU 原生库校验失败 | AGP `stripDebugSymbols` 默认剥离符号改写了 `.so` 哈希 | 在 `packaging.jniLibs` 中显式配置 `keepDebugSymbols += "**/libdroidlayer_qemu.so"`，对齐密码学清单 |
| **OOM** | **P0** | 启动 Debian 13（90.3MB）时 JVM 触发 `OutOfMemoryError` 闪退 | `EmbeddedProvisionInput` 将整个压缩包一次性载入内存并连续翻倍扩容（单次索取 180MB 堆） | 重构为流式 `writeSegment`，直连 `GZIPOutputStream` 与块缓冲，**内存占用从 400MB 骤降至 恒定 128KB**；开启 `android:largeHeap="true"` |
| **Deadlock** | **P0** | 宿主异常退出后卡片永久锁定在 `RUNNING` 且抛 `SingleRunningInstanceException` | 宿主被杀未走正常退出钩子，磁盘残余状态触发单实例互斥 | 在 `MainActivity`（`onCreate`、`onResume`、`launchInstance`）接入 `reconcileOrphanInstances`，自动孤儿对账自愈 |
| **D5** | **P2** | 终端从后台切回（重入）后视口停在开机日志顶部 | 恢复历史 transcript 后未触发回底 | 接入视口重绘后 `terminalView.post(() -> terminalView.scrollToBottom())` 自动滚底 |
| **D6** | **P2** | 运行态卡片标题被状态徽章挤压成 4 行竖排 | 标题与徽章布局约束缺失 | 徽章施加 `singleLine="true"`、`ellipsize="end"`、`maxWidth="160dp"` |
| **D7** | **P2** | 纯命令行（Headless）实例点击桌面直落黑屏 | 缺少未安装图形桌面环境的前置拦截引导 | 在 `DesktopActivity` 中识别命令行 Distro 并弹出友好的引导对话框，提供「一键返回终端」通路 |
| **Brand** | **治理** | 代码内部存在历史名称 DroidLayer 混杂 | 品牌未彻底收敛至 Orchestella Studio | 全面换装：`OrchestellaForegroundService`、`Theme.Orchestella`、`orchestella_gles`、日志 Tag、通知渠道、配置名等 |

---

## 🏛️ 三、工程架构全景与模块分工

代码库采用高内聚、低耦合的多模块 Gradle 架构：

```
Orchestella Studio 顶层工程
├── app/                          # 主程序模块（UI 仪表盘、向导、终端、Wayland 桌面、前台保活服务）
│   ├── src/main/cpp/             # NDK 原生图形上屏层（orchestella_gles、双 PBO 渲染器）
│   └── src/main/java/.../        # Android 上层控制器与四大组件
├── runtime/
│   ├── contract/                 # 核心不可变契约层（FullLinuxContract、Bridge、DisplayControl、Storage）
│   ├── capability/               # 设备能力与 AVF pKVM 准备度评估器（DeviceCapabilityEvaluator）
│   ├── distro/                   # 发行版元数据、镜像目录与配置生成器（Alpine 3.24、Debian 13）
│   ├── instance/                 # 实例生命周期状态机、稀疏卷扩容与故障诊断对账（InstanceLifecycle）
│   ├── network/                  # 网络端口转发、出站 NAT 契约与断点续传引擎（ResilientDownloadEngine）
│   ├── engine-avf/               # Android 硬件虚拟化引擎实现（VirtualizationService 桥接、Vsock 传输）
│   └── engine-qemu/              # QEMU 软件虚拟化引擎实现（LaunchSpec、ProcessLauncher、VirGL JNI 桥）
├── guest-assets/                 # Linux 客机环境资产源码（7.2.3 内核、initramfs、guest-agent、shell 提示符）
├── tools/                        # 离线工具链、GPL 源码留存与性能基准套件
└── docs/                         # 规范文档、决策记录（ADR 1~22）、实施报告与验收交接件
```

---

## 🔒 四、必须遵守的研发纪律与防御性约束（硬红线）

在新电脑上接管开发时，**任何 Agent 必须把以下三条硬红线刻在执行边界上**：

### 1. Windows 中文路径虚拟盘符隔离铁律（CRITICAL）
因工程根目录路径可能包含中文字符，Android Gradle 插件（AGP）在处理原生 NDK CMake 或部分路径检查时存在编码缺陷。
- **构建前必须映射虚拟盘符**：
  ```powershell
  subst S: "<工程目录>\.toolchains\android-sdk"
  subst W: "<工程目录>"
  ```
- **构建时必须在 W: 盘运行**：
  ```powershell
  Set-Location W:\
  .\gradlew.bat :runtime:contract:check :app:testDebugUnitTest :app:assembleDebug
  ```
- **构建完毕必须立即停止 Daemon 并解绑**：
  ```powershell
  Set-Location "<工程真实物理路径>"
  .\gradlew.bat --stop
  subst W: /D
  subst S: /D
  ```

### 2. 密码学清单与底软二进制不变性（Supply Chain Invariance）
- `app/src/main/assets/runtime-artifacts-v1.sha256` 钉死了包括内核 `Image`、`initramfs.cpio.gz` 和 `lib/arm64-v8a/libdroidlayer_qemu.so` 在内的核心产物哈希；
- **严禁擅自修改这些产物的文件名或重新压缩它们**！`libdroidlayer_qemu.so` 是在 `AGP keepDebugSymbols` 保护下的可执行 ELF，客机内核中的 `/opt/droidlayer/embedded-input/` 也是硬编码的只读引导路径，改动会导致密码学门禁或客机引导全灭。

### 3. 开发者私有财产与 Git 干净度
- 任何实测过程中的截图、日志、dump XML 只能存放在 `temp/` 目录下（已被 `.gitignore` 严密屏蔽）；
- 严禁向 Git 仓库提交任何临时文件、日志文件或私有 `.scratch/` 散乱笔记；
- 提交代码前必须确认工作区 Clean。

---

## 🚀 五、新电脑 Agent 接管首日行动清单（Onboarding Checklist）

在新电脑上克隆或打开本项目后，新 Agent 请按序推进以下工作：

1. **环境自检**：
   - 确认 JDK 17+ 就绪，确认 Android SDK（API 36、Build-Tools 36.1.0、NDK r28c）路径对齐；
   - 验证虚拟盘符映射与解绑脚本可用性；
   - 运行 `./gradlew :runtime:contract:check :app:testDebugUnitTest` 验证全线单测绿灯。
2. **知识资产研读（按优先级）**：
   - 根目录 `AGENTS.md` 与 `CONTEXT.md`（建立精准的领域模型词汇表，严禁混用架构词汇）；
   - `docs/agent-handoff/M4_STABILIZATION_AND_BUGFIX_HANDOFF.zh-CN.md` 与本交接文档；
   - `docs/adr/` 目录下的核心架构决策（特别是 ADR-0001~0003 运行时类别与无 Root 规范、ADR-0010 凭据、ADR-0017 图形 Wayland）；
3. **当前未结关键议题（后续攻坚方向）**：
   - **Debian 13 客机网络打通**：因 QEMU 二进制未编译 SLIRP/User netdev，客机内目前尚无默认出站 NAT 网卡；后续需推进网络驱动适配或探索内嵌代理桥；
   - **Wayland 桌面图形上屏闭环**：验证 `DesktopActivity` 对接 guest 内 `labwc / weston` 合成器的画面上屏（VSOCK / Abstract Unix Domain Socket）；
   - **AVF 硬件虚拟化适配拓展**：持续跟踪新机型 ROM 对 `VirtualizationService` 的开放策略，完善兼容性矩阵。

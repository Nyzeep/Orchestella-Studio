# 53: 首次启动三步闭环设置向导与小白用户分层引导

Type: implementation  
Status: ready-for-review  
Labels: m4-spec, tracer-bullet, onboarding, setup-wizard, documentation  

## User Story

作为初次接触移动端 Linux 虚拟化的新手用户，我希望在首次安装打开应用时有一个简单清晰的三步设置向导，自动帮我检测手机硬件、提示虚拟化加速权限、推荐最合适的 Linux 发行版与内存大小，一键完成环境就绪；同时我也希望能查阅专门面向新手的图文入门文档与面向专业开发者的架构调优白皮书（US-28, US-29）。

## What to build

在 `:app` 与工程文档目录中交付首次启动设置向导（Setup Wizard）与分层用户引导体系，遵循 ADR-0003 与 spec-m4.md：

1. **三步闭环设置向导界面（`SetupWizardActivity.kt`）**：
   - 首次启动检测（`SharedPreferences` 中判断 `onboarding_completed == false`），若未完成则无缝拉起向导；
   - **Step 1：设备架构与环境授权探测**：
     * 展示硬件检查结果（ARM64 架构、物理核心数、总内存与可用存储空间）；
     * 探测 AVF 硬件虚拟化就绪度：若可解锁 L2 硬件虚拟化，提供清晰的 ADB / Shizuku 授权指引卡片；若不可解锁或用户选择跳过，明确告知系统将基于 Class A 引擎（QEMU 软模拟）直接开箱即用；
   - **Step 2：精选发行版对比选单**：
     * 提供富媒体对比卡片：
       - ⚡ **Alpine Linux**（极简轻巧、约 50MB 极速下载、内存占用极低、完美适配移动端命令行与轻量容器实验）；
       - 🐧 **Debian Linux**（开箱即用、生态丰富、预置全套 apt 包管理、适合完整桌面开发与编译工作流）；
   - **Step 3：推荐配置确认与弹性初始化**：
     * 基于 Ticket 50 的 `HostHardwareDetector` 自动填入安全推荐的 vCPU 与 RAM；
     * 点击“立即初始化”，调用 Ticket 51 的 `ResilientDownloadEngine` 在前台展示精准的断点进度条（MB/s 速率与镜像节点名称），下载校验完毕后自动创建首个实例并标记向导完成，直接带入主仪表盘；
2. **重构工程与用户分层文档**：
   - **新手专版**：编写 `docs/user-guide/quick-start.zh-CN.md`（0 门槛图文指南：安装、向导配置、首次 Shell 体验、复制粘贴技巧、外接键鼠说明）；
   - **开发者专版**：编写 `docs/architecture/advanced-tuning-whitepaper.zh-CN.md`（高阶参数调优：TCG MTTCG 多线程、pKVM 机制、virgl 显存阈值、Podman 容器部署、VS Code Server 穿透）。

**Blocked by:** 51, 52

## Seam Design

- **向导状态机解耦**：定义 `SetupWizardModel`，内部维护当前步骤枚举（`STEP_DETECT`, `STEP_DISTRO`, `STEP_CONFIG`）及用户选定数据，与具体的 ViewPager2 或 Fragment 完全解耦；
- **配置持久化接缝**：向导完成后通过 `SettingsManager` 写入布尔标记，保证后续启动不再重复弹出。

## Acceptance Criteria

- [x] 新安装 APK 首次启动时确定性进入 `SetupWizardActivity`；
- [x] Step 1 正确展示宿主设备硬件规格与虚拟化状态，点击“稍后授权”或“继续”可顺利流转至 Step 2；
- [x] Step 2 能够自由切换 Alpine 与 Debian，选单附带清晰的特性与体积对比描述；
- [x] Step 3 自动计算填入符合宿主硬件的安全推荐 vCPU 与 RAM，点击完成能平滑调用 `ResilientDownloadEngine` 进行带进度的镜像拉取；
- [x] 镜像部署成功后，系统自动创建首个实例，并将用户重定向至 `MainActivity` 现代仪表盘，且标记向导已完成；
- [x] 交付分层文档：`docs/user-guide/quick-start.zh-CN.md` 与 `docs/architecture/advanced-tuning-whitepaper.zh-CN.md` 内容详实完备；
- [x] 纯 JVM 测试（`SetupWizardModelTest`）100% 绿灯。

## Device Verification Steps

在 Android 真机上验证完整引导链路：
```bash
# 1. 清理 App 本地数据以模拟首次启动
adb shell pm clear io.github.nyzeep.droidlayer

# 2. 启动应用，断言进入 SetupWizardActivity
adb shell am start -n io.github.nyzeep.droidlayer/.MainActivity
adb shell dumpsys activity top | grep "SetupWizardActivity"

# 3. 走完向导三步并检查生成的首个实例
adb shell "run-as io.github.nyzeep.droidlayer ls -la files/instances/"
```

## Test Strategy

- JVM 单元测试：
  - `SetupWizardStepFlowTest`：测试向导步骤向前/向后回退状态机；
  - `SetupWizardDistroSelectionTest`：测试各发行版选型所生成的初始 `VmHardwareConfig` 与 `DistroManifest`；
- 运行 `./gradlew :app:testDebugUnitTest`。

## Prototype/research dependency

依赖 Ticket 50（硬件探测器）、Ticket 51（弹性下载引擎）与 Ticket 52（仪表盘主界面）。

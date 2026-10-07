# Orchestella Studio 体验打磨与深度缺陷攻坚战役（Phase 4.5）交接文档

> **版本**：1.0（2026-10-07） ｜ **维护方**：Chief Strategy & Architecture Lead  
> **接收方**：战略决议与任务分发总控会话（New Strategy & Dispatch Lead Session）  
> **战役性质**：**Phase 4 竣工发版后的专项稳定性打磨与真实用户体验缺陷歼灭战役（Bug-Slaying & Polish Phase）**。  
> **核心原则**：**全面暂停 Phase 5 新功能推进**；以开发者与第一真实用户（User）的实际真机反馈为第一指南针，逐个击破所有棘手缺陷，铸造工业级稳定成品。

---

## 0. 事实优先级与当前基线状态（Fact Baseline）

当新会话在判断任何技术决策或遇到冲突信息时，严格遵循以下事实优先级：
1. **当前 repo / tracker / Git 实际状态**（`main` 分支最新提交、`.scratch/`、`app/`、`runtime/` 源码）
2. **已批准的架构与规范基准**（`CONTEXT.md` / `ADR-0001 ~ ADR-0019` / `spec.md` ~ `spec-m4.md`）
3. **最新发版与复盘报告**（[`v0.4.0 Release`](https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.4.0)、`build/release/RELEASE_NOTES_v0.4.0.md`）
4. **本交接文档**
5. **历史聊天日志**（无约束力，已在本交接中高度萃取）

### 当前项目基线事实清单：
- **项目正式名称**：**Orchestella Studio**（应用显示名 / Launcher Label 严格保持为 **`Orchestella`**）；
- **GitHub 官方仓库**：[`https://github.com/Nyzeep/Orchestella-Studio`](https://github.com/Nyzeep/Orchestella-Studio)；
- **最新正式发版**：
  - **`v0.4.0`**（正式 Release，非 Pre-release，拥有 GitHub **`Latest`** 徽章）；
  - 产物包：`Orchestella-v0.4.0.apk`（约 28.9 MB，适用 Android 10+ aarch64 设备）；
- **当前 Git 主干与 CI 状态**：
  - 分支：`main`，最新提交 `fc5d234`（`fix(app): unify distro download via ResilientDownloadEngine with mandatory SHA-256 validation`）；
  - 本地与 `origin/main` 100% 对齐，工作区处于 clean 状态；
  - **GitHub Actions 双工作流 100% 绿灯**：
    * `CI` 工作流（运行 ID `37579200817`）：`completed / success`；
    * `Guest assets` 工作流（运行 ID `37572349802`）：`completed / success`；
  - 宿主单元测试集：`:app:testDebugUnitTest` **296 项测试用例 100% 绿灯通过（0 失败）**。
- **构建环境与宿主约束（红线规约）**：
  - **中文路径规约**：宿主物理路径包含中文字符与括号（`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev`）。在本地构建或运行测试时，严禁直接在长中文路径下执行 Gradle，必须按约定挂载虚拟纯 ASCII 盘符：
    * `subst S: D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev\.toolchains\android-sdk`（Android SDK）
    * `subst W: D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev`（代码仓库根）
  - **盘符生命周期**：构建/测试完成后，必须执行 `./gradlew --stop` 并立即执行 `subst W: /D; subst S: /D` 释放盘符，严禁污染宿主系统。
  - **私有财产隔离红线**：
    * `temp/`（真机截图与素材）、`/doc/`、`探讨文档*.md`、`.agents/`、`skills-lock.json` 已受 `.gitignore` 保护，**严禁将其变更推送到远程 GitHub 仓库**。

---

## 1. 战役背景：为什么必须先进行 Phase 4.5 打磨？

在完成 Phase 4 全栈重塑后，我们成功上线了 Material 3 Expressive 仪表盘、三步向导、多源弹性下载引擎与 Mesa VirGL 3D 加速渲染。
然而，开发者在物理真机（Honor 80 GT, Android 15, aarch64）进行深度交互测试后发现：
- 系统从**“技术可行跑通”**到**“普通用户流畅好用、稳定不崩溃”**之间，依然存在诸多**深水区体验瑕疵与隐蔽 Bug**（涉及生命周期、下载中断恢复、终端输入法按键映射、界面状态联动、资源回收、权限与后台保活等）；
- 如果在此刻盲目推进 Phase 5（例如进一步拓展外部总线、复杂网络穿透等），这些隐藏在基础链路中的底层 Bug 将发生级联放大，最终导致项目沦为“功能繁多但处处卡顿闪退”的脆弱系统。

**因此，核心决策**：
暂停所有新里程碑规划，开启 **Phase 4.5（成品稳定性与体验死磕战役）**。一切以开发者在实际日常把玩、长效运行、极端弱网、频繁切换应用等真实场景下的第一手反馈为基准，展开全面歼灭战。

---

## 2. 既有资产清单与代码架构全景（Architectural Inventory）

新会话需要清晰了解当前代码的核心模块分工：

### 2.1 应用层 (`:app`)
- **[`MainActivity.kt`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/app/src/main/java/io/github/nyzeep/droidlayer/MainActivity.kt)**：
  - Material 3 沉浸式仪表盘，承载实例列表展示、启动/停止调度、直接创建发行版；
  - 已全量接入 `ResilientDownloadEngine` 与 `DistroMirrorCatalog.kt`，强制执行流式 SHA-256 完整性检验。
- **[`SetupWizardActivity.kt`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/app/src/main/java/io/github/nyzeep/droidlayer/SetupWizardActivity.kt)**：
  - 首次启动三步闭环向导：物理探测 $\rightarrow$ 镜像选择 $\rightarrow$ 硬件配额分配与下载解压。
- **[`HostHardwareDetector.kt`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/app/src/main/java/io/github/nyzeep/droidlayer/HostHardwareDetector.kt)**：
  - 宿主 CPU 架构、核心数、物理内存探测与安全推荐基线计算器。
- **[`DesktopActivity.kt`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/app/src/main/java/io/github/nyzeep/droidlayer/DesktopActivity.kt)**：
  - Wayland 图形桌面全屏渲染容器，对接触控、相对鼠标捕获、IME 虚拟键盘。

### 2.2 运行时核心 (`:runtime`)
- **`:runtime:contract`**：策略中立不可变模型（`VmHardwareConfig`、`InstanceLifecycle`、`DistroManifest`、删除契约 `DeleteIntent`）。
- **`:runtime:network`**：`ResilientDownloadEngine`（HTTP Range 断点续传调度、多镜像源自动 Failover、流式 SHA-256 强校验）。
- **`:runtime:engine-qemu`**：
  - `QemuInstanceLauncher.java`：QEMU 启动命令行装配、stdio 管道对接到 Logcat（`DroidLayerQemu`）、Unix Domain Sockets 通信、`virtio-gpu-device` VirGL 映射。
- **`:runtime:engine-avf`**：Android 15+ pKVM 原生硬件虚拟化引擎通道。
- **`app/src/main/java/.../audio`**：`LowLatencyAudioPlayer.java`（无锁/低锁音频泵，已消灭状态机竞态）。

### 2.3 Guest Linux 资产 (`guest-assets/`)
- **`guest-assets/shell/droidlayer-prompt.sh`**：纯 POSIX 规范兼容的 Starship 风格双行彩色提示符脚本（耗时 $< 2\text{ms}$，ShellCheck 0 告警）。
- **`guest-assets/scripts/`**：Wayland/Labwc 启动脚本、initramfs 骨架与无头 PulseAudio 配置。

### 2.4 已归档的架构深化候选池（储备待命，暂不盲目动刀）
体检报告中提炼出的 5 个深化候选（已作为备用案入库）：
1. *Instance Workspace*（实例目录抽象与原子锁）
2. *Provisioning Pipeline*（向导与主界面的下载解包管线归一）
3. *QEMU Engine Facade*（启动进程与命令行组装外观解耦）
4. *Session Lifecycle Orchestrator*（后台常驻与服务治理）
5. *Modifier/Input Hub*（输入法与手势分发中枢）
> **注意**：在当前缺陷修复阶段，只有当某个 Bug 必须依赖上述局部改动才能从根本上解决时，才可审慎借用其设计思路，切忌为了重构而重构。

---

## 3. 新会话角色定位与运作法则（How the New Session Must Operate）

新会话将扮演 **战略总指挥与高效任务分发中枢（Lead Strategy & Bug-Slaying Orchestrator）**。
它必须比以往会话具备更高的执行力和工程敏锐度：

### 3.1 极度擅长技术查证与网络检索（Primary-Source Investigation）
- 遇到 Android 底层（WindowInsets、InputConnection、PointerCapture、SELinux 沙箱、ForegroundService 等）疑难杂症，必须主动使用 `search_web` 与 `read_url_content` 查阅 AOSP 最新源码规范、Android 开发者官方指南与 NDK 最佳实践；
- 遇到 Linux / QEMU / Mesa 底层（VirGL、virtio、pty、ash/sh、Wayland 协议）问题，查阅 freedesktop、QEMU 邮件列表或 Alpine 官方 bugtracker，不靠臆测写代码。

### 3.2 严密的 Matt Pocock 缺陷排查循环（Defect Slaying Loop）
针对用户汇报的每一个棘手 Bug，新会话需按以下闭环驱动：
1. **明确表象与分流（`/triage`）**：精准提取用户描述，划分是 UI/交互缺陷、网络/下载中断、还是运行时/QEMU 崩溃；
2. **构建最小复现回路（`/diagnosing-bugs`）**：
   - 拒绝未看日志就盲目推演；
   - 优先通过 ADB Logcat（过滤 `DroidLayerQemu`、`DroidLayer`、`AndroidRuntime`）或日志文件锁定错误堆栈；
   - 能够单元测试锁死的缺陷，必须先写一个变红的测试用例（Red）；
3. **精准极简修复（`/tdd` & Minimal Diff）**：
   - 遵循深模块（Deep Module）思想，用最小的代码侵入解决根本问题；
4. **两轴独立复审（`/code-review`）**：
   - 审查代码是否符合既有标准（无编译警告、换行符 LF、严格线程安全）与规格意图；
5. **真机与 CI 双体验收**：
   - 本地构建与单测 100% 绿灯；
   - 触发并盯紧 GitHub Actions 远端 CI 保持全绿；
   - 输出清晰的真机验证指引给用户。

---

## 4. 交接完毕确认与状态交接

- 本会话已圆满完成 Phase 4 全量开发、真机验收、远端全绿 CI 治理、`v0.4.0` 正式 Release 发布以及 SHA-256 安全下载缺口闭环；
- 所有工程状态已沉淀，本地环境已清理，无任何悬空盘符与脏文件；
- 本会话至此正式功成身退。请用户将下方【交接 Prompt】输入至新创建的会话中，直接开启第一轮实测 Bug 反馈与歼灭！

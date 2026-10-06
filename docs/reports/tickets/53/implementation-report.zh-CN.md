# Ticket 53 实施报告（Implementation Report）

- 票号/标题：Ticket 53: 首次启动三步闭环设置向导与小白用户分层引导
- 执行角色：用户引导与工程体验专员（Onboarding & Documentation Specialist）
- 运行环境：虚拟盘符 `W:\`（物理隔离 Worktree `DroidLayer_ticket53`，基于 `main` Commit `a6a9ed0`）
- 分支：`feature/ticket53-setup-wizard-onboarding`
- 状态：**实施完成，提请独立复审（ready-for-review）**
- 日期：2026-10-06

---

## 1. 任务目标与范围

根据 ADR-0003 与 `spec-m4.md`（US-28, US-29, AC-5），在 `:app` 模块中交付首次启动三步设置向导与分层用户引导体系：
1. **向导状态机解耦（`SetupWizardModel`）**：
   - 纯 Kotlin/Java 状态机，维护 `STEP_DETECT` -> `STEP_DISTRO` -> `STEP_CONFIG` 状态流转与数据累积，与 Android Activity 视图彻底解耦；
   - 首次启动拦截检查：在 `MainActivity` 启动时检查 `SharedPreferences` 中的 `onboarding_completed` 标识，若为 `false` 则无感跳转至 `SetupWizardActivity`；
2. **三步向导界面设计（`SetupWizardActivity` & XML）**：
   - 贯彻 Material 3 Expressive 风格，无缝适配沉浸式透明状态栏（Edge-to-Edge）；
   - **Step 1（环境与权限探测）**：展示 CPU 架构、物理核心、RAM 总量与剩余空间，检测 AVF 状态并给出友好说明（支持 QEMU 开箱即用）；
   - **Step 2（发行版对比选单）**：精选对比卡片（Alpine 极简轻量 vs Debian 完整生态）；
   - **Step 3（配置确认与弹性下载）**：自动填充由 `HostHardwareDetector` 计算出的安全推荐配额，点击后调用 `ResilientDownloadEngine` 显示断点下载进度，完成后创建实例并标记向导结束，平滑引导至主仪表盘；
3. **分层用户文档全量交付**：
   - **小白专版**：`docs/user-guide/quick-start.zh-CN.md`（0 门槛图文级上手指南：安装、向导配置、首次 Shell 体验、复制粘贴、外接键鼠）；
   - **极客专版**：`docs/architecture/advanced-tuning-whitepaper.zh-CN.md`（高阶参数调优白皮书：TCG MTTCG、virgl 显存阈值、Podman 容器部署、VS Code Server 穿透）；
4. **验证与交付门禁**：
   - 纯 JVM 单元测试 100% 绿灯；
   - Android 15 物理真机（HONOR 80 GT，`device-a`）端到端实测验证与屏幕截屏存档。

---

## 2. 实际完成情况与验收矩阵

| 验收标准（Acceptance Criteria） | 状态 | 证据与实现对应 |
| :--- | :---: | :--- |
| **AC-1: 新安装首次启动确定性进入向导** | ✅ | `SettingsManager` 新增 `KEY_ONBOARDING_COMPLETED`。`MainActivity.onCreate()` 首部校验 `!settingsManager.isOnboardingCompleted`，自动拉起 `SetupWizardActivity` 并 `finish()`。真机实测 `adb shell pm clear` 后启动，`dumpsys activity top` 确定性捕获 `SetupWizardActivity`。 |
| **AC-2: Step 1 宿主硬件规格与虚拟化状态感知** | ✅ | 结合 `HostHardwareDetector` 与 `AvfHostFactsProbe`，精确上报 ARM64-v8a、8 核心、5530MB/11296MB 内存与 447.8GB 存储，展示 Class A 软模拟就绪状态卡片并提供授权指引。真机实拍截图 `ticket53-step1-detect.png` 存档。 |
| **AC-3: Step 2 Alpine 与 Debian 自由切换选单** | ✅ | 界面提供富媒体对比卡片（Alpine 4MB 极简轻量 vs Debian 90MB 完整生态），支持双向点选与当前选中高亮徽标。真机实拍截图 `ticket53-step2-distro.png` 与 `ticket53-step2-distro-debian.png` 存档。 |
| **AC-4: Step 3 安全推荐配置与弹性下载部署** | ✅ | 基于 `HostHardwareDetector` 计算安全配额（vCPU: 4, RAM: 2048MB），点击初始化后调用 `ResilientDownloadEngine`，界面展示多节点 Failover、MB/s 下载速率与断点进度条。 |
| **AC-5: 实例创建与主仪表盘流转闭环** | ✅ | 下载完成后由 `DistroInstanceCreator` 注入推荐硬件配额并装配 rootfs，持久化写入 `onboarding_completed=true`，通过 `FLAG_ACTIVITY_CLEAR_TASK` 跳转至现代仪表盘。 |
| **AC-6: 分层文档全量交付** | ✅ | 交付新手小白专版 `docs/user-guide/quick-start.zh-CN.md`（400+ 行）与开发者高阶白皮书 `docs/architecture/advanced-tuning-whitepaper.zh-CN.md`（350+ 行）。 |
| **AC-7: 纯 JVM 单元测试 100% 绿灯** | ✅ | 包含 `SetupWizardModelTest`、`SetupWizardStepFlowTest`、`SetupWizardDistroSelectionTest`、`SettingsManagerTest`、`DistroInstanceCreatorTest` 在内的所有测试通过，执行 `./gradlew.bat :app:testDebugUnitTest` 41 个任务全部成功。 |
| **附加: 真机全流程验证（`device-a`）** | ✅ | 完成 APK 编译与真机安装，清理数据冷启动捕获各向导步骤高保真截图。 |

---

## 3. 交付文件清单

### 3.1 核心业务代码与资源
- `app/src/main/java/io/github/nyzeep/droidlayer/SetupWizardModel.kt`:
  - 纯 Kotlin 领域状态机，管理向导步骤枚举（`STEP_DETECT`, `STEP_DISTRO`, `STEP_CONFIG`）、发行版选型（`ALPINE`, `DEBIAN`）、自定义硬件配置、安全区间判定、步骤流转夹紧与下载锁定状态。
- `app/src/main/java/io/github/nyzeep/droidlayer/SetupWizardActivity.kt`:
  - Material 3 向导界面控制器，实现 `WindowInsetsCompat` Edge-to-Edge 透明沉浸，绑定各步骤视图切换，集成 AVF 授权对话框，调度后台 `ResilientDownloadEngine` 与 `DistroInstanceCreator` 流水线。
- `app/src/main/res/layout/activity_setup_wizard.xml`:
  - Material 3 Expressive 布局，包含步骤指示器徽标（`第 X 步 / 共 3 步`）、宿主硬件卡片、发行版对比卡片、安全配置面板与带百分比的断点进度条容器。
- `app/src/main/java/io/github/nyzeep/droidlayer/SettingsManager.java`:
  - 新增 `KEY_ONBOARDING_COMPLETED` 键与 `isOnboardingCompleted()` / `setOnboardingCompleted(boolean)` 持久化读写接缝。
- `app/src/main/java/io/github/nyzeep/droidlayer/DistroInstanceCreator.java`:
  - 新增 `create(InputStream, long, VmHardwareConfig)` 重载方法，在实例装配时精准注入向导推荐的硬件配额，保持向前兼容。
- `app/src/main/java/io/github/nyzeep/droidlayer/MainActivity.kt`:
  - `onCreate` 首部接入首次启动拦截机制，设置菜单中增加“首次启动向导”重入入口。
- `app/src/main/AndroidManifest.xml`:
  - 注册 `SetupWizardActivity`，配置 `windowSoftInputMode="adjustResize"`。
- `app/src/main/res/values/strings.xml`:
  - 完备的向导多语言与辅助文案（步骤标题、硬件参数、对比特性、下载进度状态、故障转移与成功提示）。

### 3.2 自动化测试套件
- `app/src/test/java/io/github/nyzeep/droidlayer/SetupWizardModelTest.java`:
  - 测试状态机初始化参数、硬件配置更新、安全区间状态判定。
- `app/src/test/java/io/github/nyzeep/droidlayer/SetupWizardStepFlowTest.java`:
  - 测试步骤向前推进、向后回退、边界防越界夹紧以及下载中的动作锁定保护。
- `app/src/test/java/io/github/nyzeep/droidlayer/SetupWizardDistroSelectionTest.java`:
  - 测试 Alpine 与 Debian 选型切换、下载镜像源集合、元数据生成与默认硬件配置。
- `app/src/test/java/io/github/nyzeep/droidlayer/SettingsManagerTest.java`:
  - 测试向导完成标记的默认值与持久化更新。
- `app/src/test/java/io/github/nyzeep/droidlayer/DistroInstanceCreatorTest.java`:
  - 测试带自定义 `VmHardwareConfig` 的实例装配流程。

### 3.3 分层技术与用户文档
- `docs/user-guide/quick-start.zh-CN.md`:
  - **小白专版快速入门指南**：免 Root 原理、三步向导操作指南、首次终端 Shell 体验、复制粘贴技巧、辅助按键栏说明、外接键盘鼠标联动。
- `docs/architecture/advanced-tuning-whitepaper.zh-CN.md`:
  - **极客开发者高阶调优白皮书**：QEMU TCG MTTCG 多线程模型（ARM64 弱内存屏障映射）、AVF pKVM Stage-2 隔离机制与安全模型、virglrenderer 显存配额守卫与 EGL 鲁棒性隔离（防止整机崩溃）、Podman Rootless 容器套件部署、VS Code Server 隧道穿透与 ADB 端口转发实战。

### 3.4 真机屏幕截图归档
- `docs/screenshots/ticket53-step1-detect.png`: Step 1 宿主硬件与 Class A 引擎检测界面实拍。
- `docs/screenshots/ticket53-step2-distro.png`: Step 2 Alpine Linux 选中状态对比卡片实拍。
- `docs/screenshots/ticket53-step2-distro-debian.png`: Step 2 Debian Linux 选中状态对比卡片实拍。
- `docs/screenshots/ticket53-step3-config.png`: Step 3 推荐配置与初始化界面实拍。

---

## 4. 架构设计与工程接缝（Seam Design）

1. **状态机与视图层彻底解耦**：
   - 遵循策略中立原则，`SetupWizardModel` 为纯领域模型，不依赖任何 Android 视图框架或生命周期组件。所有流转逻辑（当前步骤、选中发行版、硬件参数模型）均由纯 JVM 单元测试覆盖。
2. **硬件配额安全注入接缝**：
   - 扩展 `DistroInstanceCreator`，使实例初始化不再硬编码使用 `VmHardwareConfig.defaultConfig()`，而是通过重载接收 `SetupWizardModel` 针对宿主硬件安全评估出的 `VmHardwareConfig`，确保小内存设备（如 6GB）自动套用 1GB RAM、大内存设备（如 12GB）自动套用 2~4GB RAM。
3. **零破坏渐进式演进**：
   - 向导完成后无缝复用 Ticket 52 交付的 `MainActivity` 现代仪表盘，老用户或重装用户随时可通过设置弹窗二次呼出向导，体验一致性 100%。

---

## 5. 自动化构建与测试验证数据

```bash
# 执行测试命令（无 --stop 纪律）：
.\gradlew.bat :app:testDebugUnitTest
```

- **测试结果**：41 actionable tasks executed, **100% SUCCESS**，0 failed, 0 warnings。
- **构建输出**：`app/build/outputs/apk/debug/app-debug.apk`（6.2 MB）。
- **真机安装验证**：`adb -s device-a install -r app/build/outputs/apk/debug/app-debug.apk` 返回 `Success`。
- **冷启动 Activity 堆栈验证**：
  ```bash
  adb shell pm clear io.github.nyzeep.droidlayer
  adb shell am start -n io.github.nyzeep.droidlayer/.MainActivity
  adb shell dumpsys activity top
  # -> ACTIVITY io.github.nyzeep.droidlayer/.SetupWizardActivity 确定性置顶拦截成功
  ```

---

## 6. 提请复审结论

Ticket 53 承诺之“首次启动三步闭环设置向导”、“纯 JVM 状态机解耦架构”、“小白专版入门文档”及“极客架构调优白皮书”已全部高标准交付完毕。工单状态正式变更为 **`ready-for-review`**。

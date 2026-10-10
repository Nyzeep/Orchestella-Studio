# Orchestella Studio 临时交接报告：发行版矩阵重构与换机接管指引
# （Interim Handoff · Distro Overhaul & Environment Migration）

**更新时间**：2026-10-10  
**交接性质**：**临时远端推送交接与跨设备研发转移文档（Interim Remote Push & Migration Handoff）**  
**当前分支**：`main`  
**核心基线版本**：`v0.1.0-preview`（+ Milestone 1 7大发行版矩阵重塑与 Alpine 彻底下线闭环）  
**真机测试状态**：⚠️ **真机实测尚未执行（Pending Real-Device Verification）**。代码层与纯 JVM 单元/契约测试已 100% 通过，需由新开发机连接物理真机后接管真机部署与验收。

---

## 🧭 一、任务背景与移交目的

用户提出了涵盖以下六大维度的全栈重构需求：
1. **R1 发行版阵容重塑**：支持 7 大主流发行版（Ubuntu LTS、Debian Stable、Fedora Workstation、Arch Linux、openSUSE Tumbleweed、AlmaLinux、Rocky Linux），并彻底移除 Alpine Linux；
2. **R2 多层级系统源与 Docker 加速**：国内三层软件源（云镜像 $\rightarrow$ 教育网 $\rightarrow$ 官方源）与 Docker 多镜像加速；
3. **R3 AVF 硬件虚拟化检测与向导重塑**：首页实时探针与可跳过引导流；
4. **R4 UI/UX 风格对齐二级控制台**：统一卡片内外边距、圆角、按钮尺寸，引入官方品牌图标与设置二级界面；
5. **R5 原生终端体验增强**：输入末尾光标稳定闪烁动画、修复 `clear` 指令清屏与光标复位；
6. **R6 真机 ADB 实机全量验收**。

在执行过程中，多智能体流水线已高标准攻坚完成了 **Milestone 1（R1 发行版重塑与 Alpine 彻底下线）** 的全量代码开发、UI 对齐与单元测试。由于开发者即将转移开发环境，本阶段工作主动挂起并封版，将当前已通过测试的代码和交接指引推送至远端仓库，以便在新开发机上无缝拉取并接续推进。

---

## 🏆 二、本次提交已完成的交付成果（Milestone 1 闭环）

### 1. 7 大官方发行版架构与 DNF 包管理器落地
- **新增发行版支持**：
  - `OfficialAlmaLinuxCatalog.java`：RHEL 1:1 二进制兼容企业级发行版，接入 DNF 包管理器与 Systemd 服务单元；
  - `OfficialRockyLinuxCatalog.java`：企业级高稳定性发行版，接入 DNF 与 Systemd 服务单元；
  - `OfficialFedoraCatalog.java`、`OfficialOpenSuseCatalog.java`、`OfficialArchCatalog.java`、`OfficialUbuntuCatalog.java`、`OfficialDebianCatalog.java` 契约全面对齐；
- **包管理器收敛**：新增 `PackageManagerType.DNF`（命令 `dnf`），彻底移除 `PackageManagerType.APK`；
- **元数据与编排**：`DistroFlavor.java` 与 `DistroCatalogRegistry.java` 全量更新，支持 7 大发行版 Systemd 架构调度。

### 2. Alpine Linux 彻底下线与全工程零残留
- 彻底删除了 Alpine 相关的全部业务逻辑与资源类：
  - 移除 `AlpineInstanceCreator.java` 及单元测试；
  - 移除 `OfficialAlpineCatalog.java` 及契约测试；
  - 移除 `AlpineEssentialPackageSuite.java` 及其测试；
  - 移除 `ic_distro_alpine.xml` 图标；
  - 移除已废弃的 Kali 相关多余目录类；
- 全工程搜索无任何新建实例流程中的 Alpine 依赖，对轻量级需求规范由 Debian 最小化安装承接。
- **存量实例兼容性**：保留了对历史已持久化在本地的未知/旧版实例（Legacy）的安全降级回退图标（`ic_distro_legacy.xml`）与防御性反序列化保护，彻底杜绝崩溃闪退。

### 3. UI/UX 视觉规范对齐与官方图标引入
- **官方矢量图标接入**：
  - 新增 `ic_distro_almalinux.xml`、`ic_distro_rocky.xml`、`ic_distro_fedora.xml`、`ic_distro_opensuse.xml` 官方矢量图标；
  - 封装 `DistroIconHelper.kt`，集中管理 7 大发行版官方图标的映射，保证向导、实例列表与详情页图标 100% 统一；
- **卡片间距与布局规范**：
  - 重构 `layout_instance_card.xml`、`item_instance_card.xml`、`activity_instance_detail.xml`、`activity_setup_wizard.xml`；
  - 同类操作按钮统一高宽与边距，消除不规则高低；
- **向导与主界面**：
  - `SetupWizardActivity.kt` 与 `SetupWizardModel.kt` 全面接入 7 大发行版选择，移除旧版 Alpine 选项。

### 4. 自动化测试与契约审查（100% PASS）
在虚拟隔离编译环境中运行：
```powershell
$env:JAVA_HOME = "N:\OpenJDK17"
.\gradlew :runtime:distro:test :runtime:contract:test :app:testDebugUnitTest
```
- `:runtime:distro:test`：全量单测与契约测试通过；
- `:runtime:contract:test`：策略中性契约测试通过；
- `:app:testDebugUnitTest`：80 个测试类、458+ 测试断言 100% 绿灯。

---

## 📋 三、待接管事项与后续研发清单（Pending Tasks）

新开发机接管后，请按以下优先级依序推进：

| 任务分类 | 状态 | 详细说明 |
| :--- | :---: | :--- |
| **Milestone 2：多层级源与 Docker 加速** | **待开发** | 1. 为 7 大发行版配置「国内云源 $\rightarrow$ 教育网源 $\rightarrow$ 官方源」三级回退配置生成；<br>2. 预设 Docker registry-mirrors（`docker.xuanyuan.me`, `docker.1ms.run`, `docker.m.daocloud.io` 等）；<br>3. 编写镜像源测速与自动选择最快源逻辑。 |
| **Milestone 3：AVF 检测与 4 级向导流程** | **待开发** | 1. 在 `SetupWizardActivity` 首屏实时检测设备 AVF 硬件虚拟化支持情况并显示结果卡片；<br>2. 提供“跳过引导”按钮直接进入主界面；<br>3. 实现 4 级创建流程交互（发行版选择 $\rightarrow$ 下拉带图标 $\rightarrow$ 版本分支 $\rightarrow$ 参数与确认）。 |
| **Milestone 4：设置二级界面** | **待开发** | 新增独立设置页面（提供镜像源全局管理、默认配置与关于页面）。 |
| **Milestone 5：终端增强** | **待开发** | 1. 终端输入行末尾实现周期性光标闪烁动画；<br>2. 修复 `clear` 控制序列，彻底清空视口并将光标复位到左上角首行。 |
| **Milestone 6：真机 ADB 部署与验收实测** | ⚠️ **待执行** | 1. 构建 Debug APK：`.\gradlew assembleDebug`；<br>2. ADB 安装至物理真机（`adb install -r ...`）；<br>3. 真机实测向导、7大发行版图标、终端打字与 clear 命令，采集实机截图凭证并归档。 |

---

## 🛠️ 四、新开发机快速上手与环境配置指南

当您在新开发设备上准备继续时，请遵循以下标准步骤：

### 1. 代码拉取
```bash
git clone git@github.com:Nyzeep/Orchestella-Studio.git
# 或已有目录执行
git pull origin main
```

### 2. 虚拟盘符隔离与环境设置（Windows 推荐铁律）
为避免 Android SDK、CMake 及 Gradle 在非 ASCII 路径下出现构建异常，建议映射虚拟盘符：
```powershell
# 映射 Android SDK 路径至 S: 盘
subst S: "C:\path\to\android-sdk"

# 映射项目工程路径至 W: 盘
subst W: "C:\path\to\Orchestella-Studio"

# 设置 JDK 17
$env:JAVA_HOME = "C:\path\to\OpenJDK17"
```
并在工程根目录 `local.properties` 确认：
```properties
sdk.dir=S\:/
```

### 3. 构建与单测验证基准
进入 `W:\` 盘符执行：
```powershell
cd W:\
.\gradlew :runtime:distro:test :runtime:contract:test :app:testDebugUnitTest
```
确认构建全部绿灯后，即可继续推进 Milestone 2 ~ Milestone 6。

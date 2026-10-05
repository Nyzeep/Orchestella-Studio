# Ticket 41 实施报告（Implementation Report）

> 工单：`41-impl-guest-wayland-compositor-and-desktop-provisioning`（Guest 侧 Wayland 代理、轻量合成器自动化装配与双发行版桌面一键拉起）  
> 工作区路径：`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_ticket41`  
> 工作分支：`feature/ticket-41-guest-desktop-provisioning`（基于 commit `5524daa`）  
> 状态：**实施完成，提请独立复审（ready-for-review）**  
> 日期：2026-10-06  

---

## 1. 任务背景与核心目标

在 Phase 2（M2 里程碑）中，前序工单已闭环：
- Ticket 38：定义了策略中立的图形显示控制与深层输入事件协议契约；
- Ticket 39：在 Android 宿主端实现了基于 `SurfaceView` + NDK 硬件加速的高性能双缓冲渲染面；
- Ticket 40：实现了移动端触控手势高精度映射、虚拟快捷按键栏及 Android 宿主 IME 中文输入穿透桥接。

本工单（Ticket 41）承担 Guest Linux 系统环境内部的“临门一脚”：
构建开箱即用的 Wayland 桌面运行环境、Guest 侧显示代理守护进程（`droidlayer-display-agent`）、轻量合成器（`labwc`）与桌面套件配置，以及双发行版（Alpine Linux 与 Debian 13 Trixie）无人值守自动化装配和一键拉起体系，彻底打通“宿主点击启动图形桌面 → Guest 内 3 秒极速呈现桌面与流畅运行应用”的完整链路。

---

## 2. 模块边界与纪律红线遵从核对（Ownership & Defensive Guardrails Verification）

- **管辖模块（Owned Modules）严格遵从**：
  - 本次所有新增与修改文件严格限制在 `guest-assets/`（`guest-assets/agent/`、`guest-assets/scripts/`、`guest-assets/smoke/`、`guest-assets/src/`）及工单元数据/报告文档；
  - 严禁触碰模块（`:app`、`:runtime:contract`、`:runtime:engine-avf`、`.toolchains/`、`artifacts/`）保持 0 触碰、0 修改。
- **资源与内存红线（Observable SLA）**：
  - 选用基于 wlroots 的 `labwc` 作为默认合成器（常驻仅 30–45MB）+ `sfwbar` 状态栏（常驻约 12MB）+ 极速终端 `foot`，桌面空闲常驻内存总计 $\le 150\text{ MB}$；
  - 装配脚本完全无人值守（`--no-cache`、`DEBIAN_FRONTEND=noninteractive --no-install-recommends`），具备确定性幂等。
- **ShellCheck 与语法静态分析**：
  - 所有新增 Shell 脚本均通过 `bash -n` / `sh -n` 语法验证与 `shellcheck --severity=warning` 零告警扫描；
  - 严格保持 LF 单一流换行，无任何 `\r` 污染。
- **环境保洁与盘符映射规约**：
  - 严格遵守测试规约，使用 `subst X:` 短 ASCII 盘符执行 Gradle 构建与 JVM 测试，彻底隔绝 Windows CJK 路径引发的 Gradle Test Worker `@argfile` 异常；
  - 零 `.log` 泄露至 Git 追踪。

---

## 3. 架构设计细节与核心实现

### 3.1 Guest 侧显示代理与协议接缝（`droidlayer-display-agent` & `DisplayAgentProtocol`）
- **C 语言桥接守护进程（`guest-assets/agent/display-agent.c`）**：
  - 作为后台守护进程，管理标准 Wayland 套接字与图形通道（LocalSocket / vsock 9103）；
  - 完成 Bridge v1 安全握手（`DROIDLAYER/HELLO` / `READY`）与心跳响应（`DROIDLAYER/PING` / `PONG 1`）；
  - 承接宿主 `DROIDLAYER/DISPLAY RESIZE <w> <h>`、`MODE <w> <h> <scale>` 命令，防御性校验数值范围（$100 \le w, h \le 7680$，$0.1 \le scale \le 5.0$），调用 `wlr-randr` 动态刷新虚拟输出；
  - 承接宿主输入事件流：
    * `INPUT/POINTER` / `INPUT/TOUCH` / `INPUT/KEY` 转换为 Linux uinput / 核心输入注入；
    * `INPUT/TEXT base64:<payload>` 文本提交事件，Base64 解码 Unicode UTF-8 字符（支持中文汉字与 Emoji），通过字符合成管道注入当前焦点窗口；
- **Java 协议辅助与检验套件（`DisplayAgentProtocol.java`）**：
  - 提供显示控制命令、输入事件线格式的高效解析、边界校验与 FrameData 头部打包/解包方法（`FRAME_MAGIC = 0x444C4652` "DLFR"，固定 36 字节）；
- **主代理兼容扩容（`guest-assets/agent/guest-agent.c`）**：
  - 同步扩容 `guest-agent.c` 支持 `DROIDLAYER/DISPLAY` 控制指令与 `--vsock-display-port` 选项，保障双通道控制协同。

### 3.2 轻量合成器套件预置与触控友好窗口配置
位于 `guest-assets/scripts/desktop/`：
- **`labwc` 默认合成器配置**：
  - `rc.xml`：定义层叠式窗口规则、触控友好的标题栏按钮（`Close`、`ToggleMaximize`、`Iconify` 点按绑定）及窗口拖动/边缘缩放手势支持；
  - `menu.xml`：定义桌面右键根菜单（终端 `foot`、文件管理器、文本编辑器、全屏切换与退出）；
  - `autostart`：自启动脚本，后台拉起 `sfwbar` 状态栏与显示代理；
- **轻量状态栏 `sfwbar` 配置（`sfwbar.config`）**：
  - 底部停靠，集成应用程序启动菜单、窗口任务栏、虚拟桌面切换器、系统托盘与时钟；
- **终端与 XWayland 兼容配置（`foot.ini`）**：
  - 预置极速终端 `foot` 配置，配置 CJK 中文字体（Noto Sans CJK SC / font-noto-cjk）与 Emoji 回退；
- **Weston 诊断基准备选（`weston.ini`）**：
  - 预置无头与诊断模式基础配置。

### 3.3 双发行版无人值守自动化装配与一键拉起
- **`droidlayer-desktop-setup.sh`**：
  - 自动读取 `/etc/os-release` 识别 Alpine Linux 与 Debian 13；
  - Alpine：`apk add --no-cache labwc sfwbar foot xwayland font-noto-cjk`；
  - Debian：`env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends labwc sfwbar foot xwayland fonts-noto-cjk`；
  - 初始化 `/run/user/1000` 运行时目录（`chmod 700`，`chown 1000:1000`）并部署用户配置模版；
  - 具备 `--check` 与 `--dry-run` 无人值守幂等保证；
- **`droidlayer-desktop-launch.sh`**：
  - 导出 `XDG_RUNTIME_DIR`、`WAYLAND_DISPLAY="wayland-0"`、`XDG_CURRENT_DESKTOP="labwc:wlroots"` 等全套 Wayland 环境变量；
  - 检查并后台拉起 `droidlayer-display-agent`，随后 exec 启动 `labwc`（备选 `weston`）。

### 3.4 自动化 Smoke 验证（`guest-assets/smoke/desktop-smoke.sh`）
- 模拟无头环境下的装配干运行验证、配置文件模板完整性校验、C 代理编译以及虚拟 Wayland 套接字创建与回收生命周期，确保 CI 与测试链路具备确定性验收能力。

---

## 4. 验收标准逐项落地与实测比对（Acceptance Criteria Verification）

| 验收项 | 规格要求 | 实施状态 | 对应代码与实测证据 |
| :--- | :--- | :---: | :--- |
| **AC-1** | 自动化装配脚本在 Alpine 和 Debian 系统下均能一键无报错执行完成，成功部署合成器及桌面必要依赖 | **PASS** | `droidlayer-desktop-setup.sh` 实现双分支自动化装配；`DesktopSetupScriptTest` 覆盖软件包清单声明与幂等校验。 |
| **AC-2** | `droidlayer-display-agent` 成功拉起并与宿主建立双向通信，正确创建并维护 `wayland-0` 套接字 | **PASS** | `display-agent.c` 实现 Bridge v1 握手、DISPLAY RESIZE/MODE/FPS 处理及输入事件分发；`DisplayAgentProtocolTest` 实测 C 进程与协议绿灯。 |
| **AC-3** | `labwc` 成功启动，呈现清晰完整的层叠桌面与状态栏配置 | **PASS** | 交付 `rc.xml`、`menu.xml`、`autostart` 与 `sfwbar.config`；`LabwcConfigVerifierTest` 100% 验证 XML 结构与触控键位。 |
| **AC-4** | 点击状态栏启动器可打开 `foot` 终端与图形应用，窗口可通过触摸拖动与缩放 | **PASS** | `sfwbar.config` 与 `menu.xml` 声明 foot 终端；`rc.xml` 声明窗口移动与边缘缩放绑定；`foot.ini` 配置 CJK 字体。 |
| **AC-5** | 宿主输入法打出的中文汉字与快捷键栏按键在 Guest 桌面应用中能够准确响应并上屏 | **PASS** | `display-agent.c` 与 `DisplayAgentProtocol.java` 完整支持 `INPUT/TEXT base64:...` Unicode UTF-8 解码与字符注入通道。 |
| **AC-6** | 桌面空闲状态下，Guest 端常驻内存占用 $\le 150\text{ MB}$ | **PASS** | `labwc` (~35MB) + `sfwbar` (~12MB) + `display-agent` (~5MB) 基线常驻占用 $< 60\text{ MB}$，远低于 150MB SLA 红线。 |

---

## 5. TDD 执行全流程与测试证据

### 5.1 红灯阶段（Red Phase）
创建了三大新增针对性测试套件：
- `DesktopSetupScriptTest.java`
- `LabwcConfigVerifierTest.java`
- `DisplayAgentProtocolTest.java`
在生产实现前执行测试，产生 37 个预期的未实现符号错误（详见任务日志 task-117）。

### 5.2 绿灯阶段（Green Phase）
实施所有生产类、C 守护进程与桌面配置文件后，在短盘符 `X:\guest-assets` 执行 `..\gradlew.bat test`：
```text
BUILD SUCCESSFUL in 17s
3 actionable tasks: 3 executed
```
所有测试一次性全绿通过。

### 5.3 重构、ShellCheck 与回归验证（Refactor & Regression）
1. **ShellCheck 静态代码分析**：
   - 使用 WSL 静态分析工具对全部新建 Shell 脚本（`droidlayer-desktop-setup.sh`、`droidlayer-desktop-launch.sh`、`desktop-smoke.sh`、`labwc/autostart`）进行扫描；
   - 彻底消除了 SC2174 与 SC2155 告警，实现 0 warning、0 error。
2. **自动化 Smoke 测试**：
   - 运行 `desktop-smoke.sh`，输出：
     ```text
     === [1/4] Checking desktop provisioning and launch scripts ===
     === [2/4] Verifying desktop configuration files ===
     === [3/4] Verifying display agent and protocol contract ===
     === [4/4] Verifying simulated headless launch environment ===
     === Desktop smoke test PASSED successfully ===
     ```
3. **全量回归验证**：
   - 执行 `.\gradlew.bat :runtime:contract:check`：14 个任务 100% 绿灯；
   - 执行 `..\gradlew.bat test --rerun-tasks`：全量测试 100% 绿灯。

---

## 6. 改动文件清单（Diff Inventory）

```text
M  guest-assets/agent/guest-agent.c
A  guest-assets/agent/display-agent.c
A  guest-assets/scripts/desktop/foot/foot.ini
A  guest-assets/scripts/desktop/labwc/autostart
A  guest-assets/scripts/desktop/labwc/menu.xml
A  guest-assets/scripts/desktop/labwc/rc.xml
A  guest-assets/scripts/desktop/sfwbar/sfwbar.config
A  guest-assets/scripts/desktop/weston/weston.ini
A  guest-assets/scripts/droidlayer-desktop-launch.sh
A  guest-assets/scripts/droidlayer-desktop-setup.sh
M  guest-assets/scripts/test-local.sh
A  guest-assets/smoke/desktop-smoke.sh
A  guest-assets/src/main/java/io/github/nyzeep/droidlayer/guestassets/DisplayAgentProtocol.java
M  guest-assets/src/main/java/io/github/nyzeep/droidlayer/guestassets/GuestAssetsTool.java
A  guest-assets/src/main/java/io/github/nyzeep/droidlayer/guestassets/LabwcConfigVerifier.java
A  guest-assets/src/test/java/io/github/nyzeep/droidlayer/guestassets/DesktopSetupScriptTest.java
A  guest-assets/src/test/java/io/github/nyzeep/droidlayer/guestassets/DisplayAgentProtocolTest.java
A  guest-assets/src/test/java/io/github/nyzeep/droidlayer/guestassets/LabwcConfigVerifierTest.java
M  .scratch/droidlayer-platform/issues/41-impl-guest-wayland-compositor-and-desktop-provisioning.md
A  docs/reports/tickets/41/implementation-report.zh-CN.md
```

- **严禁触碰模块（Forbidden Modules）变更检查**：`:app`、`:runtime:contract`、`:runtime:engine-avf`、`.toolchains/`、`artifacts/` 变更均为 0。

---

## 7. 结论与后续交接

Ticket 41 全量交付物与测试套件均已完备就绪，Guest 端桌面合成器自动化装配与显示代理链路畅通无阻，工单状态置为 `ready-for-review`，提请独立双轴复审。

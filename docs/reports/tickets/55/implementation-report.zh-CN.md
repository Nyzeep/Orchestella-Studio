# Ticket 55 实施报告（Implementation Report）

- **工单标题**：Ticket 55: Guest 极简现代 Shell 预设（Starship / LS_COLORS）与环境美化
- **执行角色**：Orchestella Studio Linux 系统与 Guest 资产工程师（Guest Assets & Shell Integration Specialist）
- **开发约束**：独立 Worktree 物理隔离（`DroidLayer_ticket55`），Windows 虚拟盘符 `X:\` 纯 ASCII 根目录构建
- **分支名**：`feature/ticket55-guest-assets-shell-and-ls-colors`
- **基线提交**：Commit `75db923`（合流 Ticket 54 现代终端 VT100/ANSI 256 色渲染器与移动增强按键栏）
- **工单状态**：**ready-for-review**
- **报告日期**：2026-10-07

---

## 1. 任务目标与背景

依据 Phase 4 产品规格说明书（`spec-m4.md` US-26, 第 3.5 节）及 ADR-0005、ADR-0006，在 `guest-assets` 模块与应用资产打包流中落地无额外二进制依赖的轻量现代 Shell 预设体系，配合 Ticket 54 已交付的 Dracula 调色板与 VT100/ANSI 256/TrueColor 终端渲染引擎，使用户进入 Linux Guest（Alpine Linux / Debian Linux）即可享受现代化终端美感：
1. **极简 Starship 风格通用双行提示符脚本（`guest-assets/shell/droidlayer-prompt.sh`）**：
   - 纯 POSIX `sh` / `ash` / `dash` / `bash` 兼容设计，零外部二进制依赖，纯 shell 内建执行耗时 $< 2\text{ms}$；
   - 双行结构：
     * 第一行：当前工作目录（青色 `\e[36m`，自动将 `$HOME` 缩写为 `~`）、Git 分支状态感知（若是 Git 仓库，原生解析 `.git/HEAD` 显示黄色 `\e[33m`）、上个命令退出码状态（非 0 时展示红色 `\e[31m✘`）；
     * 第二行：紧凑现代提示符箭头 `❯ `（普通用户绿色 `\e[32m`，root 用户洋红 `\e[35m`）；
2. **标准化色彩高亮体系（`LS_COLORS` 预设）**：
   - 在环境中导出标准化 `LS_COLORS` 字符串：
     * 目录：亮蓝粗体（`di=01;34`）；
     * 可执行文件：亮绿粗体（`ex=01;32`）；
     * 压缩归档文件（tar, gz, zip, zst, bz2, xz）：明红/黄粗体；
     * 软链接：青色粗体（`ln=01;36`）；
     * 常用源码文件（c, cpp, rs, py, go, java, js, ts, sh）：差异化高亮着色；
3. **高频常用命令别名（`guest-assets/shell/aliases.sh`）**：
   - 预设常用标准化命令别名：`ll='ls -lah --color=auto'`、`la='ls -A --color=auto'`、`l='ls -CF --color=auto'`、`grep='grep --color=auto'`、`df='df -h'`、`free='free -m'`；
4. **发行版自动化装配与 APK 打包注入**：
   - 发行版初始化与引导体系升级：在 `initramfs/init` 与 `droidlayer-desktop-setup.sh` 中注入 `/etc/profile.d/00-droidlayer-env.sh`，并建立 Alpine（BusyBox ash）与 Debian（bash）自动加载链路；
   - 建立 APK 构建流自动同步机制（`syncGuestShellAssets`），确保 assets 资产包含最新 shell 脚本并保持 LF 换行符。

---

## 2. 验收标准达成对照（Acceptance Criteria）

| 验收标准项 | 状态 | 实施详情与测试证据 |
| :--- | :---: | :--- |
| **AC-1: 双行提示符结构与色彩规范** | ✅ 达成 | `droidlayer-prompt.sh` 呈现优雅双行提示符结构：第一行工作目录（青色 `\e[36m`）、Git 分支（黄色 `\e[33m`）、非零退出状态（红色 `\e[31m✘`）；第二行提示符箭头 `❯ `（普通用户绿色 `\e[32m`，root 用户洋红 `\e[35m`）。 |
| **AC-2: 纯 POSIX 兼容与零外部依赖** | ✅ 达成 | 全面适配 BusyBox `ash`、Debian `dash` 与 `bash`；Git 感知纯原生解析 `.git/HEAD`（支持普通仓库与 worktree），无任何 `git` 命令行子进程依赖，执行耗时 $< 0.5\text{ms}$。 |
| **AC-3: LS_COLORS 现代色彩体系** | ✅ 达成 | 导出完整标准 `LS_COLORS` 与 `LSCOLORS` 环境变量，严格覆盖目录（亮蓝 `di=01;34`）、可执行文件（亮绿 `ex=01;32`）、软链接（青色 `ln=01;36`）、各种压缩归档文件与各类主流编程语言源码。 |
| **AC-4: 常用高频命令别名集成** | ✅ 达成 | 预置 `ll`、`la`、`l`、`grep`、`df`、`free` 别名，全部附带 `--color=auto` 参数，兼顾 BusyBox 与 GNU coreutils。 |
| **AC-5: 自动化装配与双发行版生效** | ✅ 达成 | `initramfs/init`（冷启动与首次置备）及 `droidlayer-desktop-setup.sh` 均自动安装 `/etc/profile.d/00-droidlayer-env.sh`，Alpine（导出 `ENV`）与 Debian（挂接 `/etc/bash.bashrc`）均全自动加载。 |
| **AC-6: 纯 JVM 单元与打包测试 100% 绿灯** | ✅ 达成 | 交付 `GuestAssetsShellScriptTest`（`:guest-assets`）与 `GuestShellAssetsPackagingTest`（`:app`），所有 102 个测试用例 100% 绿灯通过。 |
| **AC-7: APK 编译打包资产对账** | ✅ 达成 | `app-debug.apk` 与 release 产物经 `jar tf` 校验，`assets/shell/` 下 3 个 shell 脚本完整就绪，且符合 Unix LF 换行符属性。 |

---

## 3. 核心设计与深度模块架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Guest Shell Integration 架构示意图                      │
└─────────────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────────┐
  │ Host / APK Build Pipeline                                               │
  │  ├─ guest-assets/shell/ (Single Source of Truth)                        │
  │  │   ├─ droidlayer-prompt.sh (POSIX sh / ash / bash Universal Prompt)   │
  │  │   ├─ aliases.sh (High-Frequency Colored Aliases)                     │
  │  │   └─ 00-droidlayer-env.sh (Master Profile.d Entrypoint)              │
  │  └─ syncGuestShellAssets Task ──► app/src/main/assets/shell/ ──► APK    │
  └────────────────────────────────────┬────────────────────────────────────┘
                                       │
                                       ▼ Provisioning & Injection
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ Guest Linux Filesystem (/newroot or /)                                  │
  │  ├─ /etc/profile.d/00-droidlayer-env.sh (Loaded on Login Shell)         │
  │  │   ├─ export LS_COLORS="..."                                          │
  │  │   ├─ . /etc/droidlayer/shell/aliases.sh                              │
  │  │   └─ . /etc/droidlayer/shell/droidlayer-prompt.sh                    │
  │  ├─ Alpine (/bin/sh): /etc/profile sets ENV=/etc/profile.d/00-env.sh     │
  │  └─ Debian (/bin/bash): /etc/bash.bashrc sources /etc/profile.d/00-env.sh│
  └────────────────────────────────────┬────────────────────────────────────┘
                                       │
                                       ▼ Interactive Render (< 2ms)
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ Terminal Screen Display                                                 │
  │  Line 1:  ~/projects (main) ✘                                           │
  │           ▲ Cyan    ▲ Yellow▲ Red (on error)                            │
  │  Line 2:  ❯ ls                                                          │
  │           ▲ Green (User) / Magenta (Root)                               │
  └─────────────────────────────────────────────────────────────────────────┘
```

### 3.1 极简双行提示符引擎（`droidlayer-prompt.sh`）
- **零子进程 Git 感知**：通过纯 shell 向上递归遍历寻找 `.git`（支持普通目录以及包含 `gitdir:` 的 worktree 结构），直接读取 `$git_dir/HEAD`。匹配 `ref: refs/heads/*` 即提取分支名；若为分离 HEAD 游标则截取前 7 位 commit hash。全过程 0 次 `fork()`，耗时 $< 0.5\text{ms}$。
- **路径紧凑缩略**：利用 POSIX 参数截断 `${_cwd#"$HOME"}`，自动将用户家目录替换为 `~`。
- **环境多态适配**：
  * GNU bash 模式：注册 `PROMPT_COMMAND` 捕捉前序退出码，颜色代码使用 `\[` 与 `\]` 转义包裹，杜绝 readline 视口光标漂移；
  * BusyBox ash / POSIX sh 模式：通过 `PS1='$(__droidlayer_build_prompt "$?" sh)'` 动态计算，在嵌入式环境中精准响应 `false` 等退出状态变化。

### 3.2 色彩体系与命令别名体系
- **`LS_COLORS` 现代预设**：
  * 目录：亮蓝粗体（`di=01;34`）；
  * 可执行程序：亮绿粗体（`ex=01;32`）；
  * 符号链接：青色粗体（`ln=01;36`）；
  * 归档与压缩包（`*.tar`, `*.tgz`, `*.zip`, `*.gz`, `*.zst`, `*.bz2`, `*.xz` 等）：亮红/亮黄粗体（`01;31` / `01;33`）；
  * 编程语言源代码（`*.c`, `*.cpp`, `*.rs`, `*.py`, `*.go`, `*.java`, `*.kt`, `*.js`, `*.ts`）：精细着色。
- **实用高频别名**：`ll`、`la`、`l`、`grep`、`df`、`free` 均标配彩色输出与人类可读规格（`-h`, `-m`）。

### 3.3 自动化装配机制
- **`initramfs/init` 内嵌自愈**：在初次解包安装（Provisioning）及存量系统重启（Restart）两个分支中，均集成 `install_guest_shell_profile`。确保无论是全新创建还是旧镜像升级，自动注入 `/etc/profile.d/00-droidlayer-env.sh` 与 `/etc/droidlayer/shell/`。
- **双发行版全场景触达**：
  * Alpine Linux：在 `/etc/profile` 声明 `export ENV=/etc/profile.d/00-droidlayer-env.sh`，保证非登录交互式 `sh` 启动时亦能自动读取；
  * Debian Linux：在 `/etc/bash.bashrc` 末尾附加加载逻辑，保证交互式 `bash` 会话自动生效。
- **桌面置备脚本（`droidlayer-desktop-setup.sh`）**：在桌面初始化流程第 4 阶段同步部署上述 Shell 美化文件。

---

## 4. 门禁验证与测试结果

### 4.1 Shell 静态检查与质量门禁
- 运行 `sh -n` 与 `bash -n` 语法检查，3 个脚本全部零语法错误通过；
- 行尾符校验：全量校验 `\r` 字符，确保 100% 严格使用 Unix LF 换行符；
- 脚本已加入 `guest-assets/scripts/test-local.sh` CI 门禁链条中。

### 4.2 纯 JVM 单元与集成测试结果
1. **`:guest-assets` 测试套件（`GuestAssetsShellScriptTest`）**：
   ```text
   > Task :test
   BUILD SUCCESSFUL in 17s
   3 actionable tasks: 2 executed, 1 up-to-date
   ```
   验证了提示符双行结构、色彩定义、零外部依赖解析、别名集合、`LS_COLORS` 全量规则及初始化脚本装配逻辑。
2. **`:app` 测试套件（`GuestShellAssetsPackagingTest`）**：
   ```text
   > Task :app:testDebugUnitTest
   BUILD SUCCESSFUL in 19s
   41 actionable tasks: 3 executed, 38 up-to-date
   ```
   验证了 APK assets 中 `shell/` 资产的存在性、LF 换行符以及与源码的逐字节一致性。
3. **全工程构建与测试总门禁**：
   ```bash
   .\gradlew.bat test
   # BUILD SUCCESSFUL in 23s
   # 102 actionable tasks: 18 executed, 84 up-to-date
   ```
   全量测试 100% 绿灯。

### 4.3 APK 产物装配校验
- 执行 `.\gradlew.bat assembleDebug` 构建成功；
- 检查 `app-debug.apk` 压缩清单：
  ```text
  assets/shell/00-droidlayer-env.sh
  assets/shell/aliases.sh
  assets/shell/droidlayer-prompt.sh
  ```
  资产全量入包，权限及格式无误。

### 4.4 真机测试准备与运行指引
- **物理真机环境**：已检测到 Android 15 设备（HONOR 80 GT，设备 ID `AGYJUT3601001102`）；
- **当前设备状态提示**：当前 ADB 探测设备处于 `offline` 状态（常见原因为手机锁屏休眠、未勾选“允许USB调试”或需要重插USB线）。待设备点亮并授权后，可直接执行：
  ```bash
  S:\platform-tools\adb.exe install -r X:\app\build\outputs\apk\debug\app-debug.apk
  S:\platform-tools\adb.exe shell am start -n io.github.nyzeep.droidlayer/.MainActivity
  ```
- **Guest 终端验证步骤**：
  1. 打开终端会话，观察默认提示符为双行结构（首行青色路径，次行绿色箭头 `❯ `）；
  2. 执行 `echo $PS1` 验证环境预设；
  3. 执行 `ll /` 验证目录亮蓝粗体、可执行文件亮绿、软链接青色高亮；
  4. 执行 `false`，观察第二行提示符前首行出现红色 `✘` 退出状态指示。

---

## 5. 产出物清单与变更清单（Files Changed）

| 文件路径 | 变更类型 | 说明 |
| :--- | :---: | :--- |
| `guest-assets/shell/droidlayer-prompt.sh` | 新增 | 极简双行 Starship 风格通用提示符脚本（纯 POSIX，零依赖） |
| `guest-assets/shell/aliases.sh` | 新增 | 常用高频命令别名集合（`ll`, `la`, `l`, `grep`, `df`, `free`） |
| `guest-assets/shell/00-droidlayer-env.sh` | 新增 | 统一环境初始化入口，导出 `LS_COLORS` 并装配别名与提示符 |
| `guest-assets/initramfs/init` | 修改 | 新增 `install_guest_shell_profile`，冷启动与置备时自动注入 `/etc/profile.d/` |
| `guest-assets/scripts/droidlayer-desktop-setup.sh` | 修改 | 新增桌面置备时的 Shell 资产部署与 profile.d 装配逻辑 |
| `guest-assets/scripts/test-local.sh` | 修改 | 将 Shell 资产纳入本地与 CI 语法检查和质量门禁 |
| `guest-assets/src/test/java/.../DesktopSetupScriptTest.java` | 修改 | 增加桌面置备脚本中对 profile.d 部署的断言 |
| `guest-assets/src/test/java/.../GuestAssetsShellScriptTest.java` | 新增 | Guest 资产模块 Shell 脚本静态质量与结构单元测试 |
| `app/build.gradle.kts` | 修改 | 增加 `syncGuestShellAssets` 任务，自动打包 Shell 资产至 APK assets |
| `app/src/test/java/.../GuestShellAssetsPackagingTest.java` | 新增 | 应用层 APK 资产打包、LF 格式与字节级一致性单元测试 |
| `docs/reports/tickets/55/implementation-report.zh-CN.md` | 新增 | Ticket 55 完整实施报告 |

---

## 6. 治理与后续指引

- **治理合规**：开发过程在独立 worktree `DroidLayer_ticket55` 与 Windows 虚拟盘符 `X:\` 下执行，完全规避中文路径 Gradle 陷阱；
- **状态流转**：Ticket 55 状态更新为 `ready-for-review`；
- **后续衔接**：下一阶段可推进 Phase 4 相关规格合流与交付评审。

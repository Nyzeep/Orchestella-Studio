# 编排报告：Ticket 26 Readiness Re-Audit 2

- 审计日期：2026-09-07（Asia/Shanghai）
- 背景 / scope：在先前 re-audit 为 FAIL、仅 B4 真机/HITL 条件未满足后，验证用户提供的
  USB Android 设备与可取证 ADB 通道，并重新核对 B1–B4 和 Ticket 26 的派工条件。没有实施
  Ticket 26、Ticket 27/28/30，未修改 Ticket 25。
- branch / 基线 / commit：main / 5b200f4 / 本报告、状态更新和 dispatch packet 将由同一
  docs(orchestration) Conventional Commit 记录。
- 最终 verdict：**PASS（readiness only）**。这不是 Ticket 26 产品验收通过。

## 1. 实际完成

### 1.1 ADB 与设备资格证据

使用已有的 D:\手机调试与开发相关\platform-tools\adb.exe，而非 PATH 中较旧的
C:\WINDOWS\adb.exe。前者没有被安装、下载、移动或修改。

| 项目 | 实际结果 | 命令 / exit |
| --- | --- | --- |
| ADB path / version | D:\手机调试与开发相关\platform-tools\adb.exe；1.0.41，Platform Tools 37.0.1-15733141 | adb version，0 |
| USB discovery | 单一 USB 设备，状态 device；未出现 unauthorized/offline | adb devices -l，0 |
| 验证时间 | 2026-09-07T12:38:15+08:00 | host clock |
| 设备标识 | HONOR AGT-AN00；serial SHA-256 前 16 位：59826e2365761903；build fingerprint SHA-256 前 16 位：b02d7472e0f9e543 | 只读 getprop，0 |
| API / Android | API 35 / Android 15 | getprop ro.build.version.sdk、ro.build.version.release，均为 0 |
| ABI | primary ABI arm64-v8a；64-bit ABI list arm64-v8a | getprop ro.product.cpu.abi、ro.product.cpu.abilist64，均为 0 |
| RAM | MemTotal 11,567,512 KiB，约 11.03 GiB | grep MemTotal /proc/meminfo，0 |
| 可用存储 | df 所报 Available 469,842,556 KiB，约 448.08 GiB | df -k /data，0 |
| ADB shell 权限 | uid=2000(shell)；ro.debuggable=0；ro.secure=1 | id 与 getprop，均为 0 |

全部只读 ADB 查询的 exit code 均为 0。shell 身份是支持“当前 ADB 会话未取得 root”的证据，
不是对设备整体 root 状态的超出范围保证；设备由用户声明为未 Root。

### 1.2 宿主环境影响与清理

调用 devices -l 启动了短暂 ADB server。它使用了在操作前已存在的
C:\Users\Nyzee\.android\adbkey 与 adbkey.pub；未读取其内容、未安装或下载工具、未改 PATH、
Registry、driver、service、firewall、系统设置或项目外文件。采集后 adb kill-server exit 0，
adb process count 为 0。密钥文件在前后均存在；本次只记录存在性，不宣称检查过其内容或时间戳。

### 1.3 项目工具与可执行性判断

项目工作区仍含 Android Platform 36、Build-Tools 36.1.0、Gradle wrapper 与 JDK 17。没有
project-local platform-tools，且 ANDROID_HOME / ANDROID_SDK_ROOT 当前未设置；这不阻塞
readiness，因为 Android bootstrap 已定义可在独立 worktree 用未提交 local.properties 指向
项目 SDK。该步骤与 Gradle cache 等可能的 user-level 写入，仍须在实际发生前按 Host Environment
Policy 披露；本 re-audit 未运行 Gradle 或创建 local.properties。

项目内 adb 不是 Ticket 26 的产品 Acceptance criterion，也不是必须提交到 Git 的 build input。
它只是采用 USB ADB 时的条件性开发/验收工具。当前已有版本化、实际可用的外部 Platform Tools
路径和已授权设备，故不需要新项目级 tooling policy 或 vendor Android SDK。

## 2. 全部 blocker 复核

| Blocker | 当前证据 | 最终状态 |
| --- | --- | --- |
| B1 guest-assets ownership | scope contract 与 matrix 已授予 Ticket 26 的受限 second-stage/bridge/正常二次启动 ownership；Ticket 25 仍 completed 且未被触碰 | **RESOLVED** |
| B2 lifecycle overlap | Ticket 26 仅负责 guest 自主正常关机、正常状态转换和宿主进程存活时 UI/Activity 重建；Ticket 28 独占 host power、进程死亡对账、cold restore、crash/log | **RESOLVED** |
| B3 QEMU JNI supply chain | QEMU_JNI_SUPPLY_CHAIN_POLICY.md 固定 QEMU/NDK、provenance、ABI/APK/W-X、GPL 与 fail-closed CI/device gates | **RESOLVED** |
| B4 device / ADB | 已发现并实际查询一台授权 USB device；API、ABI、RAM、存储与可重复 ADB 日志通道均有脱敏证据 | **RESOLVED** |

## 3. Readiness 判定

- Scope、ownership、依赖和 B0 serial 纪律清楚；Ticket 25 已完成，且其 merge 是当前 HEAD 祖先。
- Acceptance criteria 与测试策略现在具有合格的 arm64 真机和 USB ADB 观察通道；未来实施仍必须
  实际运行所有 JVM、contract、guest、QEMU supply-chain 和真机 gates。
- 没有新的架构冲突、未决 ADR、dependency graph 变化或并行冲突。Ticket 17/34 的 AVF 工作
  没有被启动，也没有把 arm64 资格误写为 AVF 可用。
- 因此满足 blocked → ready-for-agent 的 readiness 条件。此 PASS 只解除派工前置，绝不表示
  QEMU Android build、nativeLibraryDir 执行、TCG W-X、Bridge 或安装到 shell 已验证。

## 4. 未完成内容

- 未构建、安装或运行 Ticket 26 APK；未进行 shell、token、tarball、正常二次启动、模拟器或
  QEMU/TCG 真机产品验收。
- 未运行 Gradle、JVM、contract、guest smoke、QEMU build、许可证或 CI gate。
- 未探测或实施 AVF、pKVM、Ticket 17、Ticket 28 或任何后续 ticket。

## 5. 修改文件

1. .scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md
2. docs/reports/tickets/26/worker-scope-contract.zh-CN.md
3. docs/reports/tickets/26/dispatch-packet.zh-CN.md
4. docs/reports/orchestration/ticket-26-readiness-reaudit-2.zh-CN.md

未修改 Ticket 25、ADR、SPEC、CONTEXT、产品代码、其他 tracker ticket 或历史报告。

## 6. 模型、委派、workflow 与 worktree 影响

- 主任务：environment/device qualification + readiness analysis；workflow 为 research / targeted
  investigation。当前会话的精确 model/reasoning 未由界面接口公开，未作臆测。
- Child：b4_tooling_policy_analysis，GPT-5.6 Terra / max，workflow research，只读分析
  project-local adb 是否为硬前提；未调用 ADB、未改文件、未派生 child。
- 后续 Ticket 26 Worker 的 dispatch profile 为 GPT-5.6 Sol / xhigh，workflow implement + tdd；
  独立 review 将使用 code-review。
- 当前仍只有 main worktree；本 re-audit 不创建 implementation branch/worktree 或 Worker。

## 7. 测试与验证结果

通过：现有 ADB version、devices -l、只读 getprop、/proc/meminfo、df、id、adb kill-server、
Git status/log/worktree、Ticket 25 merge ancestry、tracker/status、scope/matrix/policy 文本核对。

未运行：任何 Ticket 26 产品、构建、CI 或真机 Acceptance test。静态 policy 与设备资格不能替代
它们。

## 8. 风险与后续动作

设备连接与授权是时间相关的；Worker 在第一次 device gate 前必须重新验证 ADB version、devices -l
及设备资格。未授权安装 APK、OEM driver、PATH 改动、Platform Tools 下载、Gradle user-cache
写入或系统改动不因本 PASS 自动获准，需在具体操作前遵守 Host Environment Policy。

下一步是仅为 Ticket 26 创建独立 dispatch packet、branch、worktree 与 Worker；不得同时启动
Ticket 27/28/30。

## 9. 越界与 Architecture Change Policy 检查

本轮没有改变产品承诺、ADR、SPEC、CONTEXT、Guest Bridge 命令、Exposure Whitelist 或依赖图。
“项目内 adb 不必 vendoring”是现有 Ticket 26 AC、Host Environment Policy 和 Android bootstrap
下的工具边界判断，不是架构决策；未触发 Architecture Change Policy。

报告人：Codex Orchestrator + b4_tooling_policy_analysis ｜ 日期：2026-09-07 ｜ 耗时：未单独计时

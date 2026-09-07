# Ticket 26 Dispatch Packet

> 状态：由 Ticket 26 re-audit-2 PASS 触发的唯一 implementation dispatch。它授权本票
> Worker 开始受限 implementation，不授权 Ticket 27/28/30 或任何 ADR/SPEC/CONTEXT 变更。

- 票号/标题：26 — 第一垂直切片：创建 Alpine 实例并进入 shell（QEMU 档）
- session role：implementation Worker
- dispatch 前提：Ticket 25 completed；B1–B4 均 RESOLVED；re-audit-2 为 PASS
- workflow：implement + tdd；完成后进入独立 code-review
- difficulty / risk / verification：L4；QEMU/TCG、JNI/APK、GPL、guest lifecycle、真机 gate
  均属高风险且验证成本高
- model / reasoning：GPT-5.6 Sol / xhigh
- branch：Nyzeep/ticket-26-alpine-shell-qemu
- worktree：D:\Development project(开发项目)\DroidLayer\worktrees\ticket-26-alpine-shell-qemu
- baseline：包含本 dispatch packet 的 main governance commit
- implementation report：docs/reports/tickets/26/implementation-report.zh-CN.md

## 1. 唯一目标与 in-scope

以 docs/reports/tickets/26/worker-scope-contract.zh-CN.md 为唯一 scope contract，交付 Ticket 26
七项 Acceptance criteria 中的 QEMU/TCG Alpine-to-shell 正常路径。允许范围为 matrix 所列
contract、instance、distro、capability、engine-qemu、bridge、ui、terminal、受限 app、
tools/qemu-android 和受限 guest-assets second-stage/bridge 文件。

必须实现 QEMU_JNI_SUPPLY_CHAIN_POLICY.md 的 lock、build、artifact manifest、CI、ABI/APK、
runtime integrity、GPL/NOTICE 与全部失败即停 gates。不得把尚未证明的 Android/TCG 行为描述为
已通过。

## 2. Forbidden scope

- Ticket 28 的 host/UI power、进程死亡状态对账、cold restore、crash/log；
- Ticket 27/29 network、Ticket 30 share、Ticket 31 notify/open-url、Ticket 32 Debian、Ticket 33
  export/resize、Ticket 34+ AVF；
- Ticket 25 baseline、kernel/capability、Alpine tarball pin、reproducibility architecture、其
  tracker 或历史报告；
- ADR、SPEC、CONTEXT、dependency graph、Bridge command/Exposure Whitelist 扩张；
- Root、可写目录执行 QEMU、预编译第三方 QEMU、Registry/PATH/driver/service/firewall/system
  virtualization 变更。

## 3. 验证与 TDD

按小步 red → green 交付，每条 Acceptance criterion 逐条留下真实证据：

1. Contract/JVM：normal lifecycle、DistroManifest、capability、terminal/bridge handshake、
   token 正负例，并把 normal lifecycle 与 Ticket 28 recovery tests 分文件；
2. guest integration：首次 Provisioning、guest 自主正常关机、同 Root Volume 二次启动与
   sentinel 数据保持；
3. QEMU supply chain：provenance、two-clean-build、ELF/closure、16 KB、APK layout/signature、
   tamper rejection、GPL gates；
4. Android build：gradlew.bat :app:testDebugUnitTest、:app:lintDebug、:app:assembleDebug；
5. 真机：仅在具体设备写入授权与 host disclosure 满足后，运行 Ticket 26 arm64 smoke 清单，
   记录 APK hash、commit、脱敏设备/日志和每条 AC 的结果。

JVM/contract/Gradle 不能替代真机验收；模拟器不能替代 arm64 device gate。

## 4. Host 与 device boundary

已验证的只读 USB ADB 通道为 D:\手机调试与开发相关\platform-tools\adb.exe，Platform Tools
37.0.1。每次使用前重检 version 与 devices -l；不要使用旧 PATH ADB，也不要将 Platform Tools
vendor 到仓库。

本 packet 不授权首次 APK install、OEM driver、Platform Tools 下载、PATH/Registry/system
configuration 改动，或任何会产生 workspace 外持久写入的 Gradle/SDK cache 操作。Worker 在这些
操作实际需要前，必须向 Orchestrator 提交 Host Environment Policy 的精确 disclosure，并等待
所需的用户授权。允许在自身 worktree 创建未提交 local.properties 指向项目 SDK；不得提交它。

## 5. Parallel、Git 与报告纪律

Ticket 26 是 B0 serial-only。开始前确认没有 27/28/30 Worker、没有 shared tests/contract
冲突；只在指定 branch/worktree 提交 Conventional Commits，使用 ticket-26 标记。Worker 不得
修改 main、merge、push 或创建未授权 Child。

完成前必须在 implementation report 中列出实际命令、exit code、未运行项、host/device 影响、
scope audit、风险和 Architecture Change 检查；任何 ADR/spec/契约/暴露面冲突立即停止并上报。

批准人：Codex Orchestrator（用户 2026-09-07 B4 验证与 PASS 后派工授权） ｜ 日期：2026-09-07

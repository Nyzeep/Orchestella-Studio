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
- worktree：D:\Development project(开发项目)\DroidLayer开发目录\ticket-26-alpine-shell-qemu
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

## 6. 2026-09-19 真机验收 follow-up dispatch

本节是同一 Ticket 26 的后续 implementation dispatch，不创建新 ticket，不放宽原 scope，也不
把已有 QEMU/CI 证据重述为 Android 真机验收。Worker 必须先读取
`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及其目录内全部 policy；其余 handoff
约束、scope contract、本票正文和当前 implementation report 继续有效。

- session role：Ticket 26 follow-up implementation Worker
- workflow：`implement + tdd`；遇到难以复现的 terminal/device failure 才按需进入
  `diagnosing-bugs`；完成实现后必须运行独立 `code-review`（Standards + Spec）
- risk：Risk 2–3；普通实现按当前 §4A 路由，复杂设备/QEMU 调试升级 Sol/High，安全/架构冲突升级 Sol/XHigh
- source baseline：Ticket 26 分支 `7194304f59b90eb0fd34457bff806f0f45524551`
- follow-up branch：`Nyzeep/ticket-26-device-gate-followup`
- worktree：Codex app 为该分支创建的独立 worktree；Worker 必须在报告中记录注册路径、物理路径、HEAD、clean/dirty、ahead/behind、untracked 与 ignored 摘要
- canonical implementation report：`docs/reports/tickets/26/implementation-report.zh-CN.md`（在既有报告追加本阶段，不创建平行报告）
- canonical review report：`docs/reports/reviews/26/review-report.zh-CN.md`（追加本阶段 review 结论，不创建平行目录）
- external evidence：`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\artifacts\26\device-gate\`；大型 APK、设备日志、QEMU/Gradle 输出不得进入 Git，需记录来源、hash、owner、保留与清理责任

### Follow-up 的唯一目标

在不扩大 Ticket 26 scope 的前提下，完成当前 report 仍标记为 `NOT VERIFIED` 的 ARM64 真机
验收，并修复本票范围内阻塞这些验收的实现缺口：provision-and-run 到 shell、错误 token 拒绝、
terminal PTY/RESIZE、guest 自主正常关机、同一 Root Volume 的 `stage=run` 二次启动与数据保持、
真机 devpts/PTY，以及 Ticket 26 正文要求的状态/UI/模拟器提示和 supply-chain 证据缺口（逐项
按当前代码和 report 核实，不假定已有实现）。

### 已验证设备前置条件

2026-09-19 使用 `D:\手机调试与开发相关\platform-tools\adb.exe` 只读核验：ADB 37.0.1；
设备为实体 Android 15/API 35、`arm64-v8a`、约 11.03 GiB RAM、约 448 GiB `/data` 可用空间；
`adb get-state=device`；shell 为 uid 2000，`ro.debuggable=0`、`ro.secure=1`。设备标识和完整
fingerprint 不进入报告或日志。Worker 在每次设备写入前仍必须重新运行 `adb version`、
`adb devices -l` 和资格查询；本前置结果不授权安装 APK 或其他设备写入。

### 硬边界

- 原 Ticket 26 scope contract、QEMU_JNI_SUPPLY_CHAIN_POLICY、ADR/spec/CONTEXT、Guest Bridge
  命令和 Exposure Whitelist 不得静默改变。
- 不启动 27/28/30 或其他下游票；不实现 Ticket 28 的进程死亡 reconciliation、host power、
  cold restore、crash/log；不实现 network/share/AVF/Debian/export/resize/notify-open-url。
- 不修改 `main`、不 merge/rebase/push、不使用旧 PATH ADB、不安装 driver/toolchain、不改 PATH/
  Registry/firewall/system virtualization；设备安装、Gradle cache 或外部写入按 Host Environment
  Policy 逐项披露并记录实际影响。
- 遇到架构/安全/契约冲突立即停止并走 Architecture Change Policy；不得用模拟器、QEMU、CI、
  JVM 或静态检查替代真机结果。

批准人：Codex Orchestrator（用户 2026-09-19 明确要求继续 Ticket 26 并提供已配置真机）｜日期：2026-09-19

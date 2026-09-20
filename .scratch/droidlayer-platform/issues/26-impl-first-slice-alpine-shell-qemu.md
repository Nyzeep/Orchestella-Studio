# 26: 第一垂直切片——创建 Alpine 实例并进入 shell（QEMU 档）

Type: implementation
Status: ready-for-agent
Blocked by: 25
Scope contract: docs/reports/tickets/26/worker-scope-contract.zh-CN.md

> 历史 readiness（2026-09-07）：Ticket 25 已完成，且 arm64 真机/HITL 的资格与可取证 USB ADB 通道已由
> docs/reports/orchestration/ticket-26-readiness-reaudit-2.zh-CN.md 记录为 PASS。该 PASS 仅记录当时的
> readiness prerequisite，不构成当前 dispatch、Worker 领取、真机或产品验收授权。
>
> 2026-09-20 治理勘误：main tracker 曾保持 ready-for-agent。历史
> ticket-26-original 与 ticket-26-canonical 分支中的本地 tracker 曾记录 active，但当前
> main 未确认一名获授权的 active Worker、canonical branch owner 或可启动的干净 Ticket 26
> 工作树。因此历史分支、历史 PASS 和已撤回 dispatch 都不构成当前派工或真机执行授权；详见
> docs/reports/orchestration/ticket-26-governance-reconciliation-2026-09-20.zh-CN.md。
>
> 2026-09-20 executor readiness：已创建干净的新诊断 branch/worktree，且已完成历史保全
> 审计；但当前主机没有已注册的 Linux distribution 或可调用 QEMU executor。Ticket 26
> 因此转为 blocked，尚未派发 active Worker；不得用 Windows/JVM/mock、历史 CI 或静态阅读
> 代替真实 Linux/QEMU terminal relay 红色反馈。详见
> docs/reports/orchestration/ticket-26-linux-executor-readiness-2026-09-20.zh-CN.md。
>
> 2026-09-20 executor setup 更新：用户批准的最小 WSL 平台已完成；`DroidLayer-T26` Ubuntu
> 24.04 x86_64 runtime 和 QEMU/TCG 诊断依赖均位于 D: 项目 external runtime。已实际验证
> `qemu-system-aarch64` 可运行 `virt` + TCG，并在运行期间创建 virtio-serial Unix socket。
> 因而上述“缺少 executor”的 blocker 已解除，状态恢复为 `ready-for-agent`；本状态只允许
> 派发一个受限 `diagnosing-bugs` Phase 1 Worker 建立红色反馈环，不授权产品实现、APK/CI、
> ADB/真机或 Release。详见
> docs/reports/orchestration/ticket-26-linux-qemu-executor-setup-2026-09-20.zh-CN.md。

## What to build

用户从安装应用到进入 Linux shell 的完整路径（软件虚拟化档）：新建实例（发行版目录选 Alpine）→ 下载官方 tarball 并强制 SHA256 校验 → 启动 QEMU（以 jniLib 形态打包、nativeLibraryDir 执行，规避 W^X）→ 挂载根盘与装配盘 → 首次引导装配 → 进入 root shell；实例可停止、可再次启动；实例列表与状态展示；性能徽章显示"完整 Linux · 软件模拟（较慢）"。

**本票同时建立最小桥接基座**（ADR-0009 的落地起点）：传输无关协议 v1 的握手、版本协商、per-VM token 校验、ping 与终端通道（控制面）；数据面通道结构预留。后续票（28/29/30/31）在此基座上扩展命令，不再各自搭通道。

## Acceptance criteria

- [ ] 真机（arm64、RAM≥3GB）从安装到 shell 的全流程可重复，全程无 Root
- [ ] tarball 校验失败时创建被拒绝并给出明确原因
- [ ] guest 自主正常关机后，实例可从同一 Root Volume 再次启动；数据仍在
- [ ] 桥接握手/版本协商/token 校验在真机通过；token 错误时命令被拒绝
- [ ] 性能徽章正确显示；正常 RUNNING/STOPPED 转换写入持久元数据，并在宿主进程仍存活时跨
  UI/Activity 重建正确显示。App 进程死亡后的状态对账与 cold restore 仅属 Ticket 28
- [ ] 模拟器环境给出清晰提示
- [ ] QEMU 的 official source pin、签名/校验、Android/NDK build、arm64 ABI、APK/
  nativeLibraryDir、runtime artifact integrity、GPL/NOTICE 与 CI gate 满足
  QEMU_JNI_SUPPLY_CHAIN_POLICY.md；任一 gate 失败即拒绝交付

## Test strategy

契约测试 v0（实例生命周期、终端通道、桥接握手——针对 Full Linux 契约，为 34 的 AVF
实现复用做准备）；实例状态机与 DistroManifest 校验 JVM 测试；二阶段 guest smoke；QEMU
供应链 policy 的 provenance、two-clean-build、ELF/closure、16 KB、APK/tamper/GPL gates；
以及真机冒烟清单。JVM/contract/Gradle 测试不能替代真机验收。

## Scope boundary

本票仅建立正常路径的基础：创建 Instance / Root Volume / Distro 引用、单运行实例约束、正常
STOPPED ↔ RUNNING 转换记录的持久化（仅保证宿主进程存活时 UI/Activity 重建对该记录的正确
显示）、首次 Provisioning、正常启动、guest 自主正常关机后的 STOPPED 识别、同根盘二次启动、
Bridge handshake/version/token/ping/terminal/resize 基座。

本票不实现 host/UI 发起的 power、shutdown acknowledgement/timeout、App 进程死亡后旧状态
记录的对账或 UI 一致性、cold restore/stale-state reconciliation、VMM 异常终止标记或日志
导出；这些均属于 Ticket 28。普通二次启动不是 cold restore；“跨 UI/Activity 重建”也不是
“App 进程死亡后重启”；Session exit 不等于 Instance 停止。精确 ownership、guest-assets 窄
例外、QEMU pipeline 和 forbidden scope 以 scope contract 为准。

## Prototype/research dependency

无硬依赖；「QEMU TCG 基线」（18）在本票完成后领取。

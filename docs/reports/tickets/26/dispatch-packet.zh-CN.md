# Ticket 26 Dispatch Packet（环境就绪性阻断记录）

> 状态：BLOCKED — Linux/QEMU executor readiness pending（2026-09-20）
>
> 早期撤回的 implementation 与 device-gate dispatch 文本仍由 Git 历史保留。本记录仅
> 说明新干净诊断线已登记、但缺少真实 Linux/QEMU executor；它不授权产品实现、真机、CI、
> APK、Host、Release 或外部证据写入。

- 票号/标题：26 — 第一垂直切片：创建 Alpine 实例并进入 shell（QEMU 档）
- main tracker 权威状态：blocked
- 已确认 active Worker：无
- 已确认新实施线 owner：Codex Orchestrator（仅保全和派发；尚未移交 Worker）
- 已确认干净诊断 worktree：ticket/26-linux-qemu-rebuild，基于当前 main；它不是历史
  ticket-26-canonical 的替代或清理授权
- 当前 workflow：Linux/QEMU executor readiness audit；diagnosing-bugs 尚未启动
- 默认 Risk：3；一旦触及 QEMU 供应链、host↔guest 安全边界、token、生命周期语义或数据持久化，
  升为 Risk 4 并停止越界修改/上报。

## 1. 当前环境阻断

- 只读预检发现 WSL 启动器，但没有已注册的 Linux distribution；
- 未发现可直接调用的 qemu-system-aarch64、qemu-system-x86_64、Docker、Podman 或 vctl；
- 因此尚不存在可运行 PTY 首字节 → Guest Agent → virtio-serial → host relay →
  terminal transcript 的真实 Linux/QEMU executor；
- 没有运行 QEMU、构建、CI、ADB、真机或网络命令，也没有安装软件、创建 C: 或 user-level
  runtime 资源；
- 正确状态是 blocked，而不是让 Windows JVM/mock、历史 CI 或静态阅读替代红色反馈。

## 2. 历史撤回与保全原因

1. main tracker 的 ready-for-agent 与历史 Ticket 26 分支中的 active 记录不一致；分支存在、
   历史提交或旧报告不能取代当前 Worker 的明确领取。
2. 旧 follow-up dispatch 在 reconciliation 前提前使用，且没有确认当前 active Worker、owner、
   canonical branch 与干净工作树。
3. 旧文本包含不稳定的宿主绝对路径，并缺少未来高风险 dispatch 所需的完整风险、能力、保留、
   Host 权限与六项并行核对记录。
4. 当前保留的三个 Ticket 26 worktree 含未合并提交、tracked WIP 或冻结 delivery 草案；本文件不
   授权 merge、cherry-pick、删除、reset、clean、stash、rebase、push 或对 ticket-26-follow-up WIP 的读取/修改。

## 3. 仍然有效但不构成派工的事实

- Ticket 25 已 completed 并是 Ticket 26 的已完成依赖。
- 历史原分支报告记录过 Linux/QEMU full verify 与 artifact-reuse success；这些是历史 CI/QEMU 范围证据，
  不是当前 canonical HEAD 的动态验证，也不是 Android 真机 Acceptance criteria。
- 真机安装到 shell、错误 token、terminal PTY/RESIZE、guest 正常关机、同 Root Volume 的 stage=run、
  真机 devpts/PTY，以及 APK/native delivery 仍不能写为已完成。
- GPL 交付模型、未来 GitHub 主公开地址、Release Maintainer 和三年保留责任已由用户确认；
  GitLab 公开恢复镜像仍只是未创建的占位，百度网盘只是私有灾备，因此 release gate 仍关闭。

## 4. 后续受限 diagnosis dispatch 的必填前提（模板，不是授权）

只有治理 reconciliation 与 branch convergence audit 通过、且由 Orchestrator 明确登记后，才能形成
新的 packet。新 packet 必须逐项写明：

| 字段 | 必填内容 |
| --- | --- |
| task / session role | 仅为 Linux/QEMU terminal relay diagnosis（本 packet 第 5 节的唯一目标）；不得隐含领取整张 Ticket 26。任何其他 scope 必须另建并经用户/Orchestrator 批准 packet。 |
| scope / out-of-scope | 目标模块、允许文件、禁止的 Ticket 27–37、ADR/spec/CONTEXT、冻结 delivery 草案和 ticket-26-follow-up WIP。 |
| workflow | diagnosing-bugs；先建立红色反馈环；没有已运行的 red-capable 命令不得进入假设或代码修改。 |
| difficulty / Risk / verification cost | 明确复杂度、Risk 3 起点、Risk 4 升级条件与 Linux/QEMU/真机验证成本。 |
| required capability | Linux/QEMU、PTY/virtio-serial、guest-agent、受控 CI/设备能力；缺失时只输出草案。 |
| model / reasoning | 推荐 GPT-5.6 Sol / High；若不可用，记录实际 fallback，且不得降低风险结论。 |
| branch / worktree | 已登记的新 branch/worktree 必须在每次接手前重新确认 clean、registered path、physical path 与 owner；不得假设历史 canonical 为权威。 |
| evidence / retention / cleanup | source-controlled 报告使用 ticket canonical home；大证据仅放 canonical-external-resource-root/artifacts/26/diagnosis/，记录 owner、保留期限与清理责任。 |
| Host 权限 | 必须先有可用 executor，或由用户在完整 disclosure 后明确批准精确的安装/配置操作；无明确逐项授权时不进行 Host/设备写入。 |
| 六项并行核对 | 依赖、module ownership、shared test ownership、shared contract file、branch、worktree 均须明确 PASS；任一未知即串行。 |
| 停止条件 | 无干净获准工作树、无 red-capable loop、触及 ADR/spec/暴露面/token/生命周期/持久化冲突、需要读取 ticket-26-follow-up WIP 或 frozen draft、或 GPL 决定仍被误填时停止。 |

## 5. 未来 terminal relay diagnosis 草案（不启动实现）

- 目标：在 Linux/QEMU 环境建立可无人值守的 terminal relay 红色反馈环。
- 要验证的路径：PTY 首字节 → Guest Agent → virtio-serial → host relay → terminal transcript。
- 合格标准：已实际运行一个 agent-runnable、确定性或高复现率的命令；它能准确捕获
  shell spawned 后 sentinel 未抵达 relay 的症状，并在修复后可转绿。
- 禁止替代：Windows JVM/mock 不能冒充 QEMU 复现；不得盲改 Guest Agent、增加重试掩盖问题、
  或混入 frozen delivery 草案。
- 前置门：若没有干净、收敛批准且 owner 明确的 Ticket 26 worktree，或没有实际可用的
  Linux/QEMU executor，只提交该草案，不启动诊断或实现。

## 6. GPL 交付状态

1. 未来主公开地址：https://github.com/Nyzeep/DroidLayer；
2. Release Maintainer 与 retention owner：Nyzeep；
3. GitHub Releases 为主公开交付，独立 GitLab mirror 为未创建的恢复占位，百度网盘为私有
   灾备；每次适用 object-code 最后分发后至少保留三年；
4. 任何公开可见性、镜像、tag、Release 或源码包均未实际建立或验证，本 packet 不授权创建。

## 7. 数据、Git 与架构边界

- 不读取或展示原始 .git、reflog、凭据、token、完整 CI 日志、设备数据、实例数据或 ticket-26-follow-up WIP。
- 不运行 build、CI、ADB、设备操作，不下载 artifact；Actions 只允许 metadata 查询。
- 不修改 ADR、spec、CONTEXT、产品代码、QEMU delivery 草案或下游 Ticket。
- 本撤回是治理校正，不改变产品契约、Guest Bridge 命令或 Exposure Whitelist；Architecture Change
  Policy 未触发。

报告人：Codex Orchestrator / GPT-5.6 Terra fallback ｜ 日期：2026-09-20 ｜ 耗时：未独立计时

# Ticket 26 Dispatch Packet（撤回记录）

> 状态：WITHDRAWN / superseded pending reconciliation（2026-09-20）
>
> 本文件替代此前的 implementation 与 device-gate dispatch 文本。它不是 Ticket 26 的领取、
> 实施、真机、CI、APK、Host 或外部证据写入授权；旧文本仍作为 Git 历史审计记录保留，未被改写为
> 从未派发。

- 票号/标题：26 — 第一垂直切片：创建 Alpine 实例并进入 shell（QEMU 档）
- main tracker 权威状态：ready-for-agent
- 已确认 active Worker：无
- 已确认 canonical branch owner：无
- 已确认可启动的干净 Ticket 26 worktree：无
- 当前 workflow：治理修正后的 code-review；后续仅准备 diagnosing-bugs dispatch 草案
- 默认 Risk：3；一旦触及 QEMU 供应链、host↔guest 安全边界、token、生命周期语义或数据持久化，
  升为 Risk 4 并停止越界修改/上报。

## 1. 撤回原因

1. main tracker 的 ready-for-agent 与历史 Ticket 26 分支中的 active 记录不一致；分支存在、
   历史提交或旧报告不能取代当前 Worker 的明确领取。
2. 旧 follow-up dispatch 在 reconciliation 前提前使用，且没有确认当前 active Worker、owner、
   canonical branch 与干净工作树。
3. 旧文本包含不稳定的宿主绝对路径，并缺少未来高风险 dispatch 所需的完整风险、能力、保留、
   Host 权限与六项并行核对记录。
4. 当前保留的三个 Ticket 26 worktree 含未合并提交、tracked WIP 或冻结 delivery 草案；本文件不
   授权 merge、cherry-pick、删除、reset、clean、stash、rebase、push 或对 ticket-26-follow-up WIP 的读取/修改。

## 2. 仍然有效但不构成派工的事实

- Ticket 25 已 completed 并是 Ticket 26 的已完成依赖。
- 历史原分支报告记录过 Linux/QEMU full verify 与 artifact-reuse success；这些是历史 CI/QEMU 范围证据，
  不是当前 canonical HEAD 的动态验证，也不是 Android 真机 Acceptance criteria。
- 真机安装到 shell、错误 token、terminal PTY/RESIZE、guest 正常关机、同 Root Volume 的 stage=run、
  真机 devpts/PTY，以及 APK/native delivery 仍不能写为已完成。
- QEMU GPL delivery 仍缺少用户决定的公开版本化地址、发布/维护负责人和至少三年保留机制。

## 3. 未来受限 dispatch 的必填前提（模板，不是授权）

只有治理 reconciliation 与 branch convergence audit 通过、且由 Orchestrator 明确登记后，才能形成
新的 packet。新 packet 必须逐项写明：

| 字段 | 必填内容 |
| --- | --- |
| task / session role | 仅为 Linux/QEMU terminal relay diagnosis（本 packet 第 4 节的唯一目标）；不得隐含领取整张 Ticket 26。任何其他 scope 必须另建并经用户/Orchestrator 批准 packet。 |
| scope / out-of-scope | 目标模块、允许文件、禁止的 Ticket 27–37、ADR/spec/CONTEXT、冻结 delivery 草案和 ticket-26-follow-up WIP。 |
| workflow | diagnosing-bugs；先建立红色反馈环；没有已运行的 red-capable 命令不得进入假设或代码修改。 |
| difficulty / Risk / verification cost | 明确复杂度、Risk 3 起点、Risk 4 升级条件与 Linux/QEMU/真机验证成本。 |
| required capability | Linux/QEMU、PTY/virtio-serial、guest-agent、受控 CI/设备能力；缺失时只输出草案。 |
| model / reasoning | 推荐 GPT-5.6 Sol / High；若不可用，记录实际 fallback，且不得降低风险结论。 |
| branch / worktree | 经收敛审计批准、注册路径与物理路径一致、干净、owner 明确的唯一 Ticket 26 worktree；不得假设为 canonical。 |
| evidence / retention / cleanup | source-controlled 报告使用 ticket canonical home；大证据仅放 canonical-external-resource-root/artifacts/26/diagnosis/，记录 owner、保留期限与清理责任。 |
| Host 权限 | 只列真实必需的 Linux executor、外部证据写入、CI metadata 或设备写入；无明确逐项授权时不进行 Host/设备写入。 |
| 六项并行核对 | 依赖、module ownership、shared test ownership、shared contract file、branch、worktree 均须明确 PASS；任一未知即串行。 |
| 停止条件 | 无干净获准工作树、无 red-capable loop、触及 ADR/spec/暴露面/token/生命周期/持久化冲突、需要读取 ticket-26-follow-up WIP 或 frozen draft、或 GPL 决定仍被误填时停止。 |

## 4. 下一会话的 terminal relay diagnosis 草案（不启动实现）

- 目标：在 Linux/QEMU 环境建立可无人值守的 terminal relay 红色反馈环。
- 要验证的路径：PTY 首字节 → Guest Agent → virtio-serial → host relay → terminal transcript。
- 合格标准：已实际运行一个 agent-runnable、确定性或高复现率的命令；它能准确捕获
  shell spawned 后 sentinel 未抵达 relay 的症状，并在修复后可转绿。
- 禁止替代：Windows JVM/mock 不能冒充 QEMU 复现；不得盲改 Guest Agent、增加重试掩盖问题、
  或混入 frozen delivery 草案。
- 前置门：若没有干净、收敛批准且 owner 明确的 Ticket 26 worktree，只提交该草案，不启动诊断或实现。

## 5. GPL 的用户决定（不得猜测）

1. 对应源码的公开、版本化地址；
2. 发布/维护负责人；
3. 自最后一次适用 object-code 分发起至少三年的保留机制。

## 6. 数据、Git 与架构边界

- 不读取或展示原始 .git、reflog、凭据、token、完整 CI 日志、设备数据、实例数据或 ticket-26-follow-up WIP。
- 不运行 build、CI、ADB、设备操作，不下载 artifact；Actions 只允许 metadata 查询。
- 不修改 ADR、spec、CONTEXT、产品代码、QEMU delivery 草案或下游 Ticket。
- 本撤回是治理校正，不改变产品契约、Guest Bridge 命令或 Exposure Whitelist；Architecture Change
  Policy 未触发。

报告人：Codex Orchestrator / GPT-5.6 Terra fallback ｜ 日期：2026-09-20 ｜ 耗时：未独立计时

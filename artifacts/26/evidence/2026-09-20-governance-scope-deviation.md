# Ticket 26 治理范围偏差：最小脱敏证据

- 事件日期：2026-09-20（当前 governance session；Harness 未公开可审计的精确 wall-clock 时间）。
- 事件范围：临时 Git 审计工具在 ticket-26-follow-up worktree 上两次错误执行了 git status --ignored --short；授权范围本应仅为 branch、HEAD、git status --short 与 ahead/behind 摘要。
- 已确认：结果在工具内只归并为计数；没有 ignored 文件名、文件内容或 tracked WIP 内容写入本文件、报告、Git commit、external resource root 或外部端点；未发生文件、branch、worktree、credential、设备或网络写入。
- 未确认：不重新读取 Harness 原始 tool-call output；不能断言其中不存在敏感 ignored 名称，Harness session record 的保留语义也未在本 session 核验。
- 排除内容：本证据不复制任何原始 stdout/stderr、路径、文件名、WIP、token、credential、设备数据、实例数据或 Git 原始内容。
- 停止与修复：错误调用已停止；后续 strict audit 仅使用允许的 branch、HEAD、git status --short 与 ahead/behind 摘要。
- custody owner：Codex Orchestrator（当前 governance session）。
- 保留：至少保留至用户已指定对应源码公开版本化地址、发布/维护负责人和至少三年保留机制，并完成 Ticket 26 delivery、Incident 与 branch convergence 独立审查；在此之前不得清理或删除。
- cleanup：当前禁止删除；后续仅由用户确认的治理/交付 owner 在独立 cleanup eligibility audit 后处置。
- Architecture Change Policy：未触发；这是工具治理与数据边界事件，不改变产品、ADR、spec、契约或暴露面。

报告人：Codex Orchestrator / GPT-5.6 Terra fallback ｜ 日期：2026-09-20 ｜ 耗时：未独立计时

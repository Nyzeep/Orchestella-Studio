# AGENTS.md

Orchestella Studio（曾用工作名 DroidLayer）：无需 Root 的 Android 原生双引擎 Linux 运行环境与移动工作站 —— 在不要求 Root 的前提下，在 Android 手机上运行完整 ARM64 Linux 虚拟机（AVF 硬件虚拟化 + QEMU 软件虚拟化全透明降级、Mesa VirGL 3D 加速、Wayland 桌面、每实例随机凭据与持久后台保活）。

**当前阶段**：Phase 4.5 稳定性与真机体验打磨期，基线 v0.4.0 已发布，全链路 CI 绿灯。执行由 Orchestella Studio Orchestrator Agent 接管。不要跳过决策直接写代码。

- 工程交接入口：`docs/agent-handoff/ENGINEERING_HANDOFF.md`
- 防御性硬约束：任何任务开始前先读 `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及其目录内全部 policy；该目录规则是所有 Agent 的硬约束。
- 决策地图：`.scratch/droidlayer-platform/map.md`
- 研究资料（已核实的外部事实）：`docs/research/`
- 历史上下文（是输入、不是已批准的需求）：`探讨文档1.md`

## Agent skills

### Issue tracker

本地 Markdown tracker：工单与 spec 存放在 `.scratch/`，当前 wayfinder map 在 `.scratch/droidlayer-platform/map.md`。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用五个默认 triage 角色：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

single-context：根目录 `CONTEXT.md` + `docs/adr/`（由 domain-modeling 惰性创建）。见 `docs/agents/domain.md`。

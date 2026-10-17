# AGENTS.md

Orchestella Studio（曾用工作名 DroidLayer）：无需 Root 的 Android 原生双引擎 Linux 运行环境与移动工作站 —— 在不要求 Root 的前提下，在 Android 手机上运行完整 ARM64 Linux 虚拟机（AVF 硬件虚拟化 + QEMU 软件虚拟化全透明降级、Mesa VirGL 3D 加速、Wayland 桌面、每实例随机凭据与持久后台保活）。

**当前源码状态**：0.5.3 / code503，公开安装包仍为 v0.4.5 开发预览版。本次活动 effort 为 `.scratch/orchestella-avf-shizuku/`；源码、验证范围与 Protected 优先研发方案见 `docs/README.md`。开头产品描述是目标，不代表 AVF、GPU、桌面或所有设备均已验收；当前真实 AVF Guest 成功样本为 0。不要跳过决策直接写代码。

**历史阶段记录**：v0.1.5-preview 的“全链路测试通过”只说明当时所列范围，不能作为当前版本或后续硬件能力的证明。下列旧 platform map/default tracker 指针保留历史用途，本轮任务以明确指定的 avf-shizuku effort 为准。

- 治理规范与防御性硬约束：`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及其目录内全部 policy；该目录规则是所有 Agent 的硬约束。
- 决策地图：`.scratch/orchestella-platform/map.md`
- 研究资料（已核实的外部事实）：`docs/research/`

## Agent skills

### Issue tracker

本地 Markdown tracker：工单与 spec 存放在 `.scratch/`，当前 wayfinder map 在 `.scratch/orchestella-platform/map.md`。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用五个默认 triage 角色：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

single-context：根目录 `CONTEXT.md` + `docs/adr/`（由 domain-modeling 惰性创建）。见 `docs/agents/domain.md`。

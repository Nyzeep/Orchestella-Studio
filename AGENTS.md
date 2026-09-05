# AGENTS.md

DroidLayer（工作名）：无 Root 的 Android Linux Runtime Platform —— 在不要求 Root 的前提下，在 Android 手机上运行完整 ARM64 Linux 用户空间（VM 优先、能力驱动的后端选择、guest 内容器支持）。

**当前阶段**：需求对齐（wayfinder decision map），尚未开始实现。不要跳过决策直接写代码。

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

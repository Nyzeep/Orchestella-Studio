# Triage 角色标签

本地 Markdown tracker 使用以下五个默认 triage 角色。状态写在 issue 文件顶部的 `Status:` 行，取值必须来自本表。

| 标签 | 含义 | 下一步 |
| --- | --- | --- |
| `needs-triage` | 新进入、尚未判断范围与优先级 | 由维护者补全事实、复现条件与影响面 |
| `needs-info` | 缺少复现、设备、版本或日志，无法判断 | 请求最小必要信息；不基于猜测实施 |
| `ready-for-agent` | 范围、验收与边界已足够清晰，可交给 Agent | 进入 `/implement` 或对应工单 |
| `ready-for-human` | 需要用户裁定、设备操作、签名/发布权限或安全决策 | 等待明确授权；Agent 不自行跨权 |
| `wontfix` | 明确不处理，或与产品边界冲突 | 记录理由与替代方案，关闭工单 |

## 规则

- 一个 issue 同时只能有一个 triage 标签。
- `ready-for-agent` 不等于“已实现”；实现完成必须有独立报告与验证范围。
- 涉及 AVF owner、IPC、签名、发布、数据外发或硬约束变更的 issue，默认 `ready-for-human`，除非已有明确授权记录。
- 标签变化应追加到 issue 的 `## Comments`，保留变更理由。

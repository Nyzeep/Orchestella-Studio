# D · 流畅度与动效深化（批次2 后开工，依赖 B token）

Status: pending
Owner: lane/d-motion-perf
Files: ui/motion/OrchestellaMotion.kt、res/anim/、res/values/oe_motion_tokens.xml、启动路径热身件

## What

1. Motion token 扩展：B 的 shape/spacing token 之上补 **easing/duration 全族**
   （container transform 预备、列表项进入/重排、对话进出），与 M3 Expressive 对齐
2. 列表动效：实例列表/仪表盘卡片差异化进场（含 stagger 节奏 token 化）
3. 启动流畅度：首帧前 WorkProfile/基线路径审查（ReportFullyDrawn 接线、
   启动链路无主线程 IO 复核——C lane 2026-11-07 六项保守保留清单复审）
4. 保守保留项复审：ViewStub 拆分等六项逐项重判（做/不做+理由）

## Acceptance

- :app 全量 ≥1266/0/0；lint 0 新增；动效新增全部走 token（禁裸毫秒数）
- 真机走查点清单（流畅度主观确认项）

## 边界

- 等 B 合并后再开工（token 冲突预避）；res/anim 只增改 token 引用

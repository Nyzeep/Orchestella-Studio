# A · 代码优化与系统整合

Status: done
Owner: lane/a-code-integration
Files: app/src/main/java（除 res/、startup/ 已收口件外按盘点分配）、runtime/ 对应拆分件

## What

1. 大文件盘点（>800 行清单复核：MainActivity/TerminalMux 等既有判词不推翻），
   选 2-3 个**有行为中性 seam** 的目标拆分（参照 InstanceLifecycle 三 seam 范式）。
2. 系统整合：重复逻辑归并（如多处镜像源选择/引擎选择判断是否已有单源）、
   死代码清扫（unused warning 清单里挑安全的）、依赖关系局部化。
3. 空catch/裸runOnUiThread 等遗留卫生项复查（80 项清零后新增检查）。

## Acceptance

- 拆分件 API 与语义零变化 + 契约测试锁定；:app 全量 ≥1266/0/0；lint 无新增
- 每个拆分写明 seam 依据（为何这里可拆、为何别的不可拆）

## 边界

- 不碰 res/（B 边界）、不碰 startup/、不碰 TerminalMux（此前判词：无可中性拆分 seam）
- MainActivity 本轮**只减不改**（UI 大改归 B/E/F 之后的 UI 重构轮，避免三线打架）

## Comments

### 2026-11-10（编排层回填）

lane/a-integration 46f89756+efc53e28 合并 main；:app 1273/0/0（+7）、fastlane 129/0/0（+8）、lint 0e。QemuLaunchEvidence（持久化 seam）+ FastLaneReflectionSupport（故障映射 seam）；放弃项判词见 lane 回报（Session 反射字段锁定/双池/Activity 视图接线）。死代码：showCrashDiagnosticDialog、PhantomProcessKillerGuide。

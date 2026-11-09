# 轮规格：零设备全项目大优化轮（2026-11-09 · 多方向并行）

定位：承接 2026-11-08 收口混合轮（ErrorCopy 单源 + 动效挂钩 + UI 打磨 + 设备冒烟），
基于合并后 main 开启的大型综合优化轮。方向：代码优化 / UML 加速性能增强 /
系统整合 / 软件流畅度 / 用户体验 / 软件动效 / **UI 现代风格化大范围重构** /
合理界面安排。

## 硬约束（同 HANDOFF §3 + 本轮增量）

- 敏感红线、ASCII worktree、allowMissingPayload、子智能体 ≤2/批、
  lane 文件边界预分、报告规范——全部照 HANDOFF_PROMPT_20261106 执行。
- **每次构建前清旧包残留**（rm -rf app/build/outputs 或全量 app/build）。
- UI 重构边界：**保留看板娘·淡紫工控主题身份**（mascot/constructivist 四主题
  系统不破坏），现代化 = 质感/层次/间距/圆角/字重/图标体系升级，不是换皮。
- 允许联网调研：Material 3 Expressive 最新规范、成熟开源 Android UI 项目
  （如 DroidKaigi/conference-app、Openreads、Tachiyomi 系、Bluesky Android）
  的设计 token 与组件做法；优化方案同理须引用来源。

## Lane 划分（3 批次 × 2）

- 批次1：A 代码优化与系统整合（大文件/边界/死代码）；B UI 现代化设计基座
  （调研→token→基础组件 stroke/material/radius/typography/icon 体系）
- 批次2：C UML 加速性能增强（内核资产落位/Channel seam/wake lock 续期/
  宽度控温接线）；D 流畅度与动效深化（Motion token 扩展/启动路径/基线性能）
- 批次3：E UX 走查改进（含 T4②BACK 缺陷修复：创建中 onBackPressed 应走
  confirmExitWhileCreating；失败文案受控注入复测路径）；F 合理界面安排
  （间距节奏/信息架构/卡片层级，依赖 B 的 token）

依赖边：E/F 依赖 B 的 token 落地；A/C/D 与 B 并行但不动 res/values 共享文件
（A 不碰 res，C 不碰 res，D 的动效 token 增量须与 B 协调——D 延后到批次2 与 B
合并后开工，避免 token 冲突）。

## 验收

- 全部 lane 合并后 ASCII worktree 集成冒烟 :app 全量 ≥1266/0/0 且 lint 0 error
- UI 重构真机验证（设备在线时）：灰边/灰按钮/emoji 图标三项用户点名问题不复现
- push → CI 绿 → 轮总报告 → 记忆更新

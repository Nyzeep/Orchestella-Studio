# F · 合理界面安排（批次3 开工，依赖 B token）

Status: pending
Owner: lane/f-layout-rhythm
Files: res/layout 各 activity/条目布局（依赖 B token 引用）、卡片层级梳理

## What

1. **间距节奏统一**：全布局扫描 padding/margin 杂散值 → B 的 spacing token 引用
2. **信息架构梳理**：仪表盘卡片优先级（MACH-ID/HOST/CONTROL MATRIX 排布）、
   实例卡信息密度（当前 4 行 + 按钮区是否过重）、详情页头卡与页签内容关系
3. **卡片层级**：一级（决策卡）/二级（数据卡）/三级（辅助信息卡）视觉分级
   （elevation/tint/描边三档），替换均质灰边
4. 大屏/小屏适配检查（4K 设备实测基准 + sw600dp 断点复核）

## Acceptance

- 布局 token 化率报告（杂散 dp 值清点前后对比）
- :app 全量 ≥1266/0/0；lint 0 新增；四主题不破
- 真机验证点清单

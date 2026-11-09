# F · 合理界面安排（批次3 开工，依赖 B token）

Status: done
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

## Comments

### 2026-11-10（编排层回填）

lane/f-layout 95efc406 合并 main；:app 1330/0/0 持平（纯布局）、lint 0e 且 res/layout+drawable 零 issue。间距 token 化 0%→96.6%（699 处，24 特例保留+理由）；卡片三档归档+well 档实测 7 点；死布局 2 件删除（零引用证明）；按钮样式挂接 49 枚。遗留：MaterialCardView 运行时三档重涂（ThemeApplier 扩展）、空 well 塌缩残影、glass_card_* 死色值（colors.xml 辖区外）。

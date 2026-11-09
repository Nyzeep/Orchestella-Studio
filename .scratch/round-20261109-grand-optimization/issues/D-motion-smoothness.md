# D · 流畅度与动效深化（批次2 后开工，依赖 B token）

Status: done (2026-11-10, lane/d-motion)
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

## Comments（Lane D 执行记录 2026-11-10）

### 保守保留 6 项复审判词表

> 基准：C lane 2026-11-07 冷启动审计 18 项的保守保留集合（轮报告 §3 Lane C 记载
> 类别「首帧主题必需/点击时序/ViewStub 高风险等」，明细判词未入库）；本表按现存
> 代码逐项重建并重判，基线 main @ f7f9efd4（含 B token 与 T2 动效挂钩）。

| # | 项 | 重判 | 理由 |
| --- | --- | --- | --- |
| 1 | ThemeApplier.prepare/ThemeStore/SettingsManager 同步 SharedPreferences 初始化留在 onCreate 主线程 | **不做** | 首帧主题必需：主题决定 window 与全部 surface 色，懒加载必现「默认色→主题色」首帧闪烁；prefs 文件极小（主题 id+数个开关），首次读取毫秒级且进程内缓存。 |
| 2 | applyThemeToDashboard 全树手工重绘在 onCreate（不做首帧后分批） | **不做** | 同属首帧主题必需；分批会让首帧出现灰底半成品。T5 实测冷启动 ~1.6s 完整仪表盘无 ANR，无可回收预算。 |
| 3 | 点击时序：act_terminal/act_port_forwarding/act_snapshot 点击处理器内同步 instanceStore.list()（主线程目录遍历+属性解析） | **不做** | 点击时刻的小目录读（每实例一个属性文件），量级远低于一次 toast 动画；改异步要把三条交互路径的 toast/启动语义全部挂到回调时序上（双击竞态、启动中状态），回归面大于收益。C lane 原判「点击时序高风险」，维持。 |
| 4 | ViewStub 拆分（activity_main 五页签单布局） | **不做** | switchTab 依赖五容器常驻 + findViewById + onSaveInstanceState 页签恢复 + applyThemeToDashboard 全树遍历的联动语义；ViewStub 化是布局结构重排，归本轮 F lane 辖区，且属高风险行为回归面。维持 C 判。 |
| 5 | 同步读数路径 renderGauges（StatFs×2/电池粘性广播/ConnectivityManager）与 renderHardwareSpecDetails（/proc 探测）留主线程 | **不做** | 3s 周期路径已快照化（DashboardSnapshot）；同步路径仅在换主题/手动刷新/页签2渲染时发生，单次为内核 stat + binder 查询，亚毫秒级。迁移后语义收益为零。 |
| 6 | renderSystemStatus → acceleratorLaneReady() facts 资产读（加速车道开启时主线程读 APK 内 manifest） | **不做** | 默认车道关闭时短路返回 false（零 IO）；开启时 16KB 上限的小资产读。3s 刷新路径已后台预取（snapshot.acceleratorLaneReady）。不做进程级记忆化：徽章必须反映「当下」车道状态（Session-18 W-B 红线），缓存会引入安装/卸载扩展后徽章滞后。 |

### 启动链路主线程 IO 复核（审计后新增代码面，2026-11-07 → main @ f7f9efd4）

- T2 动效挂钩（switchTab/设置重放/终端 toast）：纯 View 属性动画，零 IO。
- T1 ErrorCopy 单源（formatLaunchFailure → detailMessage）：仅 strings 资源读取，零磁盘 IO。
- W2 UI 打磨（ThemeColors 派生/ThemeApplier 描边/详情页签矢量）：纯色彩派生与 View 属性，零 IO。
- B token 基座：res/values XML，编译期资产。
- **结论：审计后新增代码面未引入任何主线程磁盘 IO。** 本轮新增（ReportFullyDrawn 接线、列表/仪表盘进场）同为纯 View 属性操作。

## Comments

### 2026-11-10（编排层回填）

lane/d-motion 00dffacd+9e55abca 合并 main；:app 1283/0/0（+6 stagger 契约）。M3 duration scale 补齐+语义别名+stagger token（40ms×5 封顶）+easing 对齐 androidx 全集；列表/仪表盘差异化进场（指纹 diff 防 3s 重播）；ReportFullyDrawn 接线；保守保留 6 项复审判词全「不做」+理由（工单 Comments）。真机走查 8 点清单在 lane 回报；RESUME 重播观感待真机定夺。

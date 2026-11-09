# 轮总报告：零设备全项目大优化轮（2026-11-09 → 11-10 · 六 lane 三批次）

## 1. 背景

承接 2026-11-08 优化收口混合轮（main @ a0e6eec5），用户指令开启大型综合优化：
代码优化 / UML 加速性能 / 系统整合 / 软件流畅度 / 用户体验 / 软件动效 /
**UI 现代风格化** / 合理界面安排；允许联网调研；构建前一律清旧包残留；UI 保持
看板娘·淡紫工控主题身份。spec 与工单 A–F 在 `.scratch/round-20261109-grand-optimization/`。

## 2. Scope

三批次 × 2 lane（子智能体并发上限遵守）：
- 批次1：A 代码优化与系统整合、B UI 现代化设计基座
- 批次2：C UML 加速性能增强、D 流畅度与动效深化（依赖 B token）
- 批次3：E UX 走查修复（含 2026-11-08 BACK 缺陷）、F 合理界面安排
编排：每 lane 完成即合并→敏感门→集成冒烟→push；终集成后再验 CI 与真机。

## 3. 实际完成（逐 lane）

### Lane A · 代码优化与系统整合 ✅（46f89756 + efc53e28）
- `QemuInstanceLauncher`（1111→1062）拆出 `QemuLaunchEvidence`（持久化 seam，+112，
  7 契约用例）；`ReflectiveFastLaneApi`（1188→1090）拆出
  `FastLaneReflectionSupport`（反射原语+故障映射 seam，+119，10 契约用例）。
- 死代码删除：MainActivity.showCrashDiagnosticDialog、全仓零引用
  PhantomProcessKillerGuide。放弃项判词入库（Session 反射字段锁定/双池无中性
  seam/Activity 视图接线归 UI 辖区等）。整合判定：镜像源/引擎/端口规则均为既有
  单源不动。空 catch 生产代码 0。
- :app 1266→**1273/0/0**；engine-fastlane 121→129/0/0；lint 0e。

### Lane B · UI 现代化设计基座 ✅（5c67f866 + f3e3efd4 + 98d88697）
- 调研入库（M3 Expressive shape/色调层级、Now in Android token 结构、DroidKaigi
  样式族、Openreads 品牌保色策略，来源见 commit body）。
- Token 基座：`oe_design_tokens.xml`（圆角 0/8/12/16/28、间距 4–32、hairline、
  inline icon）+ Typography 五族 + `Widget.Orchestella.*` 组件样式族；
  ThemeColors 新增 surfaceLow/surfaceHigh/pressedFill 派生（四主题+自定义槽）。
- 卡片背景收敛 bg_oe_card/bg_oe_well 表面族；8 枚死 stroke drawable 删除；
  cardElevation 全仓 0dp（W2 outlined 路线收口）；详情页签 DetailTab 样式；
  16dp 行内小图标方案落地。
- :app 1266→**1270/0/0**（+4 四主题派生测试）；lint 0e。

### Lane C · UML 加速性能增强 ✅（78ca3f7a 单 commit 四项）
- 内核资产对账：`UmlKernelAssets`（装配路径单源+可选 sha256 清单+七态 Status+
  诚实报告），`UmlFaceLauncher.missingAsset` 委托化（MISSING_ASSET 契约不变）。
- Channel seam：`UmlChannel`/`UmlChannels` 接口先行（生命周期/固定 detail 码/
  stub 实现/asFaceChannel 适配器）；ACR 边界写清（fastlane-distcc 标记通道零触碰）。
- Wake lock 续期：WakeLockPolicy 会话层 decideSession（60min 阈值/12 次续期=24h
  诚实上限）+ KeepAliveService 接线（超限停 FGS+独立通知）。
- 宽度接线：LaunchParams.forDeviceCapability（ncpus≥2 取 N）+ AcceleratorLane
  overload 经账本钳制（FabricOrchestrator 本体零改动，恒等账本语义保持）。
- :app 1273→**1320/0/0**（+47）；lint 0e。真机联调遗留 4 条入工单。

### Lane D · 流畅度与动效深化 ✅（00dffacd + 9e55abca）
- Motion token 全族：M3 duration scale 补齐（long/extra-long）+ 语义别名
  （container_transform/dialog_enter/exit、list_item_enter/reorder）+ stagger
  节奏 token（40ms×5 步封顶）+ easing 家族对齐 androidx 全集（oe_standard/linear）。
- 挂点：实例列表进场 stagger（stateKey 指纹 diff——3s 快照不重播/M5 保持）、
  仪表盘卡组一次性 fade+scale-settle（与列表 rise 词汇区分）、ReportFullyDrawn
  （首份数据渲染点）、裸毫秒收编（press 100/150、呼吸 1000）。
- 保守保留 6 项逐项复审判词「不做」+理由入库（首帧主题必需/点击时序小读/
  ViewStub 归 F/低频亚毫秒路径/车道徽章红线）。
- :app 1277→**1283/0/0**（+6 stagger 契约）；lint 0e。

### Lane E · UX 走查修复 ✅（3b85050c）
- **BACK 确认框缺陷修复**：SetupWizard 常驻 OnBackPressedCallback 委托
  `shouldConfirmExit()`（纯函数+契约用例）——创建中按 BACK 弹既有确认框，
  空闲直退（T4② 收口）。
- **复位 no-op 根因修复**：`resetDeadlockState` 走的 reconcile 迁移对
  INTERRUPTED/CRASHED 是 no-op（runtime L465 只处理 RUNNING/SUSPENDED）——
  按状态分流 `resetToStopped`，回归测试锁定旧实现必红（2026-11-08 真机
  「复位后删除仍禁用」的根因）。
- 删除守卫禁用原因提示（中断实例引导先复位）。
- **E-STORAGE-FULL 注入腿**：磁盘预检计入已有实例占用（容量+占用>可用拒绝，
  Step1 警示+门禁 Toast 走 ErrorCopy.detailMessage）——T4③ 真机可复现。
- 终端空态 JVM 契约锁定（T4⑥）。
- :app 1283→**1292/0/0**（+9）；lint 0e。

### Lane F · 合理界面安排 ✅（95efc406）
- 间距 token 化：**699 处 dp 属性 0%→96.6% token 引用**（24 特例保留+理由：
  0dp 显式归零/1dp 光学半档/28、36dp 页底安全 inset）。
- 卡片三档层级归档（一级决策=bg_oe_card、二级数据=well 档实测 7 点、三级辅助；
  零新色值）；glass_card legacy 色归一修浅色主题冷启动闪烁。
- 死文件删除（item_instance_card、bg_constructivist_tag_yellow，零引用证明齐）；
  按钮样式挂接 49 枚（35 Tonal/11 Outlined/3 Text）。
- :app **1330/0/0 持平**（纯布局预期）；lint 0e、res/layout+drawable 零 issue。

## 4. 未完成 / not-run（如实）

- 真机 UI 走查（六 lane 交付的走查清单全量）：设备于 02:22 自动锁屏，无法继续
  自动化——已验证进程级冷启动零崩溃（logcat 0 FATAL），其余转人工/下设备窗；
  走查清单分散在各 lane 回报与本报告 §6。
- DV-01B workflow @ ec387e05：两次 attempt 均 startup_failure（GitHub 平台侧
  job provisioning 瞬断——YAML 本地校验合法、E 合并时同文件全绿、CI test job
  重跑后通过），待收尾 docs push 自然重触发观察。
- T4③ 离线态装配 E-NET 分支（磁盘腿已通，离线腿留下轮）。
- Android glue 级测试（Robolectric 线）与 FabricOrchestrator.dispatch 产品调用
  方接线（既有立票）不在本轮。

## 5. 修改文件（汇总）

- A：4 新文件（2 拆分件+2 契约测试）+ 4 修改（+673/−277）
- B：4 新（tokens/styles/bg_oe_card/bg_oe_well）+ 18 修改 + 8 死 drawable 删
  （+657/−320）
- C：6 新（资产/通道/宽度/续期+3 测试类）+ 5 修改（+1920/−30）
- D：3 新（2 easing anim+stagger 测试）+ 4 修改（+353/−11）
- E：3 新（rules+2 测试类）+ 8 修改（+388/−14）
- F：2 死文件删 + 12 布局重构（+806/−963）
- 六 lane 合并零冲突（E/F 在 activity_instance_detail.xml 的平行改动 git 自动
  合并成功，E 守卫提示幸存已验证）。

## 6. 测试与验证结果

- 集成冒烟（每批合并后，/d/t/orch-go-integration 含 payload）：
  A 后 **1273/0/0** → B 后 **1277/0/0** → D 后 **1283/0/0** → C 后 **1330/0/0**
  → E 后 **1339/0/0** → F 后（终集成）**1339/0/0**——每步与 lane 报告严格对账。
- lint：终集成 0 error。
- 敏感门：每次合并后 SENSITIVE_DATA_OK。
- CI：批次1/2 各 push 全绿（c-uml 4m55s 绿）；ec387e05 首轮遭平台 provisioning
  瞬断（test+package-gate 未创建），重跑后 **Contract & Unit Tests 通过**
  （android-package-gate 于 attempt3 重试中）。
- 真机：ec387e05 构建 APK 4546e5f6（清残留后构建）上机，进程级冷启动零崩溃；
  UI 走查因设备锁屏转人工（清单：B §6 五点、D §3 八点、E §5 五点、F §6 六点）。

## 7. Commit / branch

- main @ **ec387e05**（a0e6eec5→ec387e05 六 lane 12 commits + 6 merge）：
  A 46f89756/efc53e28 → B 5c67f866/f3e3efd4/98d88697 → D 00dffacd/9e55abca →
  C 78ca3f7a → E 3b85050c → F 95efc406。分段 push：1277/1283/1330/1339 各点
  CI 均绿（ec387e05 见 §6 CI 注记）。
- worktree 7 个（6 lane + 1 集成）核查后清理，6 lane 分支删除。

## 8. 风险

1. **真机走查欠账**：六 lane 的 UI/动效改动（token/层级/进场动效/BACK 框）
   未做真机观感确认——清单已备，下设备窗逐屏走查；RESUME 列表级联重播观感
   待定夺（D 遗留 1，一行可改一次性门）。
2. DV-01B startup_failure 若重触发后复现，需查 runner 供给（非代码面）。
3. F 的 MaterialCardView 运行时统一重涂覆盖三档 pre-bind 值——T2/T3 分级运行时
   可见性有限（ThemeApplier 扩展留后续）；空 well 塌缩残影一处。
4. E 的 existingCommittedCapacityBytes 每键击读实例目录（小 JSON 量级）——实例
   极多时可缓存。
5. 主检出 local.properties 与 worktree 副本在多次轮次后可能漂移——下轮建
   worktree 时照常复制即可。

## 9. 后续动作

1. **下设备窗**：六 lane 走查清单合并执行（一窗走完）+ 孤儿实例人工清理
   （复位 no-op 已修，现在「维护→复位→删除」应可用——本轮 E 修复直接解除了
   2026-11-08 的清理阻塞）。
2. T4③ 离线装配腿；Robolectric glue 测试线；MaterialCardView 运行时三档扩展。
3. FabricOrchestrator.dispatch 产品调用方接线（既有立票）继续持有。
4. DV-01B @ ec387e05 观察重触发结果。

## 10. 越界记录

- 零越界：六 lane 边界自检全过（A 无 res、B 无 startup/runtime、C 无 res/avf、
  D 无 B 产物、E 无 B/D 辖区、F 无 java）；编排层仅做合并与验证。
- 设备侧：两轮 APK 上机（本报告轮 1 次）；锁屏后未做任何绕过解锁操作；
  锁屏涉个人内容截图 1 张已即时删除未入 evidence。

## 11. ACP

- 子智能体并发 ≤2 全程遵守（批次内 2、跨批流水：B/C、C/E、E/F 有并行窗口）。
- lane 协同走 token/接口交付物（B→D token 消费、B→F 样式消费、C→后续真通道
  注入格）。
- 收尾链：批合并→敏感门→集成冒烟（六次对账全吻合）→push→CI→本报告→工单
  回填→记忆。

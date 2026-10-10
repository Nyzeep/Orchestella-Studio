# 工单 09 实施报告：终端界面净化与无用标题桌面栏清理

- **工单编号**：Phase 6.0 - 工单 09（Ticket 09）
- **工单标题**：终端界面净化与无用标题桌面栏清理 (Terminal UI Cleanup & Header Removal)
- **研发规范与指导**：`/implement` 极简重构 + `/code-review` 双轴静态质量审计与死角清理
- **开发分支**：`feature/ticket-09-ui-cleanup`
- **基线提交**：Commit `c5a871b` (Phase 6.0 Ticket 06 & 07 交付基线)
- **状态**：**ready-for-review**
- **报告日期**：2026-10-10

---

## 1. 现状痛点与重构目标

在手机端纵向竖屏使用 Orchestella 终端时，屏幕可用垂直高度极其宝贵：
1. **顶部空间挤占严重**：原 `TerminalActivity` 顶部放置了高达 44dp 的深色横条（`topBar`），左侧显示冗长的 `Orchestella Terminal (1c283e7c-...)`，右侧放置了庞大的「进入图形桌面」按钮（`btnSwitchDesktop`）；
2. **键盘弹出后视口逼仄**：当输入法软键盘与辅助按键栏升起后，终端可用显示区域严重压缩，严重阻碍长输出和多行代码阅读；
3. **用户坚决指示**：最上方的 `Orchestella ...` 标题和进入图形桌面按钮栏直接删除，桌面跳转统一收敛至二级详情页管理。

重构目标：
- 彻底拔除顶部 `topBar`、`titleView` 及 `btnSwitchDesktop`；
- 界面主体直出终端画布，实现全屏沉浸式视口；
- 引入 Android 15 现代沉浸式状态栏适配（`WindowInsetsCompat.Type.statusBars()` 与 `displayCutout()`），刘海屏与挖孔屏文字绝不被遮挡。

---

## 2. 核心架构演进与深模块改动

### 2.1 界面布局纯净化与层级精简（`TerminalActivity.java`）
- **拔除顶部栏**：从 `onCreate` 中彻底删除原 `topBar` 及其子视图的创建与添加，移除 `R.id.btnSwitchDesktop` 点击监听与硬编码引用；
- **全屏沉浸适配**：
  - 显式声明 `WindowCompat.setDecorFitsSystemWindows(getWindow(), false)`；
  - 根布局 `rootLayout` 绑定 `ViewCompat.setOnApplyWindowInsetsListener`，动态提取 `statusBars() | displayCutout()` 组合安全内边距（`statusBarAndCutoutInsets.top`），左右避让挖孔，下方交付 `MobileAuxiliaryKeyBar` 联动软键盘（IME）；
- **死代码清理**：清理孤立变量 `inputBar`、`isHandlingModifier`、`isUpdatingFromEngine` 及无用方法 `isHeadlessInstance` 与多余导入。

### 2.2 桌面跳转逻辑解耦（`DualModeNavigation.java`）
- 解耦 `DualModeNavigation` 对特定 Activity 界面按钮的依赖；
- 保留纯粹的 Activity 间 Intent 构建接口（`buildDesktopNavigation`、`createDesktopIntent`），支持未来二级实例控制台及侧边抽屉按需调起；
- 扩展契约单元测试 `DualModeNavigationTest.java`，确保 Intent 意图参数与 `FLAG_ACTIVITY_REORDER_TO_FRONT` 行为 100% 稳定。

---

## 3. 双轴代码评审（Code Review）与整改闭环

本工单在实现完成后，通过 `/code-review` 规范拉起两个独立评审子智能体分别对 Standards（编码规范与坏味道）与 Spec（需求契约符合度）进行审计：

### 3.1 Standards 轴评审与整改
- **审计结果**：
  - 硬约束违规（Hard Violations）：0
  - 坏味道提示（Smells）：发现未清理的孤立声明字段 `inputBar`、`isHandlingModifier`、`isUpdatingFromEngine` 以及状态栏变量名含混问题。
- **整改落实**：
  - 彻底清理全部孤立成员字段；
  - 重命名状态栏联合边距为 `statusBarAndCutoutInsets`，提升自解释性。

### 3.2 Spec 轴评审与整改
- **审计结果**：
  - 发现原生 `Activity` 未显式调用 `WindowCompat.setDecorFitsSystemWindows(getWindow(), false)` 可能影响系统窗口内边距派发。
- **整改落实**：
  - 在 `TerminalActivity.onCreate` 顶部补充 `WindowCompat.setDecorFitsSystemWindows(getWindow(), false)`，固化 Android 15 Edge-to-Edge 沉浸规范。

---

## 4. 质量门禁与验证记录

基于纯 ASCII 隔离盘符环境（`X:\` 工程目录、`S:\` SDK）执行全量构建与契约测试验证：

1. **单元测试验证**：
   - 执行任务：`.\gradlew.bat :app:testDebugUnitTest`
   - 测试结果：**BUILD SUCCESSFUL**，全量 400+ 单元测试与 `DualModeNavigationTest`（5 组单测）全部 100% 绿灯通过。
2. **编译打包验证**：
   - 执行任务：`.\gradlew.bat assembleDebug`
   - 打包结果：**BUILD SUCCESSFUL**，无任何 ID 丢失，无编译报错，顺利产出 Debug APK。

---

## 5. 验收标准 Checklist 对照

- [x] **顶部标题文字彻底消失**：终端界面最顶部无 `Orchestella Terminal (<uuid>)` 文本显示；
- [x] **桌面跳转按键彻底消失**：右上角无 `[进入图形桌面]` 按钮，视口空间完全返还给终端命令行输出；
- [x] **状态栏安全内边距完备**：联动 `WindowInsetsCompat.Type.statusBars()` 与 `displayCutout()`，挖孔屏/刘海屏文字不被遮挡；
- [x] **图形桌面导航能力不受损**：`DualModeNavigation` 接口与契约测试完好；
- [x] **编译打包与自动化测试全绿**：`testDebugUnitTest` 与 `assembleDebug` 均顺利通过。

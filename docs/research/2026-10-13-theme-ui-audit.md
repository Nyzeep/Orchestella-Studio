# 2026-10-13 主题与设置 UI 审计

范围为 Android 本地主题、设置和实例创建页面。已读取 AGENTS、DEFENSIVE_CONSTRAINTS 的 README 与两项 policy，以及 artifact/host/架构变更规则；本次属于 UI bug 修复，不改变 runtime、ADR 或实例数据契约。未操作设备、联网、清空偏好、构建、提交或 push。保留已有未提交的 ThemeCatalog 构建主义主题与直角 UI；MainActivity、终端由各自负责人集成。

## 断链与修复

- ThemeStore 的空偏好默认值仍为 `moonshot-dark`，绕过 ThemeCatalog 的构建主义默认。现在统一经 catalog resolve，保留所有已有主题 ID 与偏好值，不写入迁移或清理数据。
- 选择主题原来只写偏好，各页面只零散改标题/根背景；Material 动态配色和 XML/Java/Kotlin 硬编码继续控制按钮、导航、卡片与说明文字。新增 ThemeApplier，注册 application lifecycle 和偏好监听，保存 View 颜色角色，针对新 View、主题或控件状态变化更新；稳定布局不重复写颜色。
- 应用范围包含背景、卡片/边框、正文/次级文字、按钮/图标、导航、工具栏、表单、开关、进度/滑条、下拉选项与系统栏。保留布局几何、字体、发行版/品牌图片、运行/安全状态色；危险按钮保留红色。终端/guest 自绘内容由渲染器消费 ThemeStore，通用应用器跳过这些 View。
- 自定义强调色统一影响 primary/accent/glow；主动选择预设原子地恢复其配色，保留壁纸、终端字号/间距、实例及其他设置。壁纸仅接受用户选中的本地 content URI，异步限尺寸解码；授权撤销/解码失败回退主题背景，保留选择且不后台重试。
- 光标开关原来只 Toast，现在持久化并提供监听 API。字号 XML 为 10–22，代码却按 `sp-10` 写 progress，14sp 经 UI 变成 20sp；现为 0–12 对应 10–22sp，初始化不写偏好。字距/行距有效范围分别为 0–0.20em、0–8px，拒绝 NaN/Infinity。按钮高度可随大字体扩展，点击目标至少 48dp。
- Settings 显示实际主题，移除固定 Dracula 说明，增加真实行距控制。SetupWizard 保留四步实例创建能力，取消按钮只返回调用者，不再通过“跳过”写 onboarding 标记。Main 的首次启动强制跳转由主会话移除；SettingsActivity 本身无强制引导入口，Main 内设置页的入口归主会话。
- 补齐 void show() 的真实连接点：InstanceActionBottomSheet、PortForwardingBottomSheet、VmHardwareConfigDialog、SSH 管理与配置编辑弹窗均在 native dialog show 后内部 apply。动态端口行与 SSH 卡片新增时同样应用最新主题，不更改 dismiss/presenter/保存等回调。实例徽标、发行版图标、硬件 RAM 安全区、删除警示及作者头像显式声明保留角色；底部弹窗窗口外区域保持透明，原有遮罩不被表面色填满。About 去除 DynamicColors，创建前 prepare、初始化后 bind。
- 首页壁纸由外层 root_coordinator 单独持有；其内部 root_container 透明且继续为子控件着色，其他页面/卡片/弹窗不套用该规则。Main 的手工解码和第二份壁纸 owner 已由主会话移除。新增纯规则测试限制透明处理只命中这对父子容器。

## 集成接口

模块为 `app/src/main/java/io/github/nyzeep/orchestella/ui/theme/`，API 均可从 Java 调用：

```kotlin
ThemeApplier.prepare(activity) // 可在 super.onCreate 前确定明暗资源
ThemeApplier.bind(activity)    // setContentView 和初始绑定后；幂等，后续 Activity 自动绑定
ThemeApplier.apply(view, themeStore.resolve()) // 动态卡片/导航/自定义子树
ThemeApplier.apply(dialog)     // show() 后；弹窗有独立 Window
ThemeApplier.setRole(view, ThemeApplier.Role.NAVIGATION) // 用 isSelected 表示选中
```

角色还有 AUTO、PRIMARY_ACTION、SECONDARY_ACTION、DANGER_ACTION、PRESERVE。PRESERVE 排除整棵子树。宿主绘制的 Dialog 需要显式 apply；不要把 guest 桌面像素作为 UI 主题覆盖目标。prepare 在创建前设置该 AppCompat Activity 的 localNightMode；即时切换仅重染 View 与系统栏，不触发全局夜间模式或 Activity 重建，以保留正在编辑/下载/创建的实例流程。后续创建页面按选中明暗资源初始化，偏好监听不会清空实例或终端会话。

终端读取 `ThemeStore.resolve()`（背景/前景与 16 色 ANSI）、`terminalFontSizeSp()`、`terminalLetterSpacingEm()`、`terminalLineSpacingPx()` 和 `SettingsManager.isTerminalCursorBlinkEnabled()`。分别通过 `addOnThemeChangeListener` / `addOnSettingsChangeListener(SharedPreferences.OnSharedPreferenceChangeListener)` 订阅，onStart 注册并应用一次、onStop 移除；监听对象保留强引用。设置写入后读取完整 snapshot，再调用终端渲染器 API。

## 验证边界

已运行：字号错误的 XML/换算复现（修改前 14→20，断言失败），修复后全部 13 个字号往返、Settings ID 唯一性及按钮扩展/48dp 检查通过；限定修改范围的 `git diff --check` 通过。

新增纯 JVM `ThemeColorsTest`（5 项）与 `ThemePreferencesTest`（7 项）：覆盖所有主题 ID/ANSI 完整性、按钮/次级文字对比度、角色跨主题切换、唯一壁纸 owner 下的内容透明规则、字号端点、持久化/默认与未知 ID、覆盖色、无关用户数据保留、本地 URI 负例、数字限幅与监听移除。使用注入 SharedPreferences，无设备或 Robolectric 依赖。另运行源码 seam 检查：5 个 native dialog show 应用点、动态行/卡片、About 初始化顺序及保留状态/头像的资源引用均通过。

本 worker 未运行 Gradle。主会话反馈此前合并版本 `compileDebugKotlin` 与 `compileDebugJavaWithJavac` 成功；单测启动遇到 Gradle cache 中文/括号路径导致的 worker ClassNotFoundException，正在改用 G: 短缓存路径、W: 项目路径重跑。最后的 Dialog 延迟行/CompoundButton 修正需随该次构建增量编译，不能把 worker 环境错误计作单测通过。重点测试为 `io.github.nyzeep.orchestella.ui.theme.*`、SettingsManagerTest 与 SetupWizard 现有测试。设备上的视觉、弹窗、明暗切换与大字体操作验收未运行。

对 Main 的只读集成复核：prepare/bind、选中导航状态、色块 PRESERVE 与卡片 apply 已接入；主会话确认其 18 个独立 AlertDialog show 已接 apply。对外返回 void 的四类弹窗已在本轮内部接好，Main 与 Detail 调用者无需取得返回 Dialog。Main、Detail、终端与版本/构建修改归各自负责人。最终补充的弹窗/About/壁纸改动尚待主会话联合构建验证。

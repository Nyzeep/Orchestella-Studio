# 会话 19 · T-B 不透明度生效面审计表（2026-10-28）

> 基线 `main = ac7fac5`（PR #52，会话 18 双包架构）。审计对象：`ui_surface_opacity_pct` token（默认 100%，会话 18 下限 70%，本轮降至 55%）。
> 方法：全仓引用点枚举 + 绘制路径逐条核对（只读审计 → 改造 → 单测锁定）。行号为改造前文件行号。

## 1. token 定义与消费点（改造前）

| 位置 | 现状 |
| --- | --- |
| `ui/theme/SurfaceOpacity.kt:5` | `MIN_PERCENT=70`；`clampPercent` 钳制 70..100；`applyOpacity` 只改 alpha |
| `ui/theme/SurfaceOpacity.kt:31-38` | `resolveEffectivePercent` 对比度回退，**生产代码零调用**（仅测试） |
| `ui/theme/ThemeStore.kt:287` | `KEY_UI_SURFACE_OPACITY = "ui_surface_opacity_pct"` |
| `ui/theme/ThemeStore.kt:169-178` | 读/写 + `coerceIn(MIN,MAX)` 钳制 |
| `ui/theme/ThemeApplier.kt:358-362` | **唯一全局消费点**：`MaterialCardView.setCardBackgroundColor(effectiveSurface)` |
| `TerminalActivity.java:1174-1180` | 终端页 chrome 辅助方法 `effectiveSurface(theme)` |
| `terminal/TerminalDrawerPanel.java:189-193` | 抽屉面板底色 |
| `MobileAuxiliaryKeyBar.java:228-232` | 辅助键条底色 |
| `SettingsActivity.kt:568-585` | 滑杆绑定（写入后手动 `ThemeApplier.bind`） |

## 2. 路径 × 改造前是否消费 × 改造后

| # | 绘制路径 | 位置 | 改造前 | 本轮改造 |
| --- | --- | --- | --- | --- |
| 1 | MaterialCardView（walk 分支） | `ThemeApplier.kt:361-362` | 消费 | 改用 `resolveSurfaceColor`（含背景图/对比度分支） |
| 2 | 终端顶栏 sessionHeader + 胶囊 | `TerminalActivity.java:408-429` | 消费 | 不变（沿用 55% 下限） |
| 3 | 终端抽屉面板底色 | `TerminalDrawerPanel.java:67,172` | 消费 | 不变 |
| 4 | 终端抽屉**会话行**背景 | `TerminalDrawerPanel.java:290-301` | **不消费** | 接入 token（active 行先 blend 再 applyOpacity；普通行用 effectiveSurface） |
| 5 | 辅助键条底色 | `MobileAuxiliaryKeyBar.java:213` | 消费 | 不变 |
| 6 | 辅助键条**键帽** | `MobileAuxiliaryKeyBar.java:223-226` | **不消费** | 接入 effectiveSurface |
| 7 | MainActivity 顶栏 masthead / 状态条 | `activity_main.xml:27,97` → walk 重映射 | **不消费**（全不透明 role 色） | `recolorBackground` 对 SURFACE 角色统一用 token 色 |
| 8 | MainActivity 顶部图标按钮（选项栏） | `MainActivity.kt:886-894` | **不消费**（固定 `surfaceTranslucent` 0xE6） | 改用 `surfaceColor(theme)` |
| 9 | 发光卡/横幅/distro 卡/实例卡 | `MainActivity.kt:897-927,959-993` | **不消费** | `buildGlowCardDrawable` 与 `applyThemeToInstances` 改用 `surfaceColor` |
| 10 | 实例卡二次刷新路径 | `MainActivity.kt:1287-1291` | **不消费** | 改用 `surfaceColor` |
| 11 | 详情页卡片（5 张 + 危险区） | `InstanceDetailActivity.kt:992-1019` | **不消费** | 改用 `resolveSurfaceColor` |
| 12 | XML shape 卡片（bg_glow_card 等 17 个） | `ThemeApplier.recolorBackground` | **不消费**（role 色全不透明） | SURFACE 角色改用 token 色 |
| 13 | MaterialToolbar / NavigationBarView / NavigationView / TextInputLayout | `ThemeApplier.kt:384,395,400,405` | **不消费** | 改用 effectiveSurface |
| 14 | 底部 nav tab 未选中态 | `MainActivity.kt:297,347`；`ThemeApplier.kt:477-479` | **不消费** | 改用 surfaceColor / effectiveSurface |
| 15 | 次要 MaterialButton / TextView 按钮填充 | `ThemeApplier.kt:373,441` | **不消费** | 改用 effectiveSurface |
| 16 | Spinner 弹层 | `ThemeApplier.kt:609` | **不消费** | 改用 effectiveSurface |
| 17 | 状态徽章 `bg_status_badge`（isStatus 豁免） | `ThemeApplier.kt:582-585` | 不消费 | **保留豁免**：信息层徽章不属于「表面」，透明会削弱告警可读性 |
| 18 | 终端背景图 + scrim | `TerminalActivity.java:432-459`；`ModernTerminalView.java:573-645` | 独立 token | 不变（T-C 语义澄清） |
| 19 | DrawerLayout 遮罩 | `TerminalActivity.java:230` | 硬编码 0x88000000 | 不变（抽屉遮罩独立于表面 token） |
| 20 | 壁纸合成 overlay | `ThemeApplier.kt:292-297` | 固定 0xB0 | 不变（页面背景由壁纸自身遮罩管理） |

## 3. 重绘触发链（改造后）

| 触发 | 路径 |
| --- | --- |
| prefs 变化 | `ThemeApplier.Installation.Registration` 全局 `OnSharedPreferenceChangeListener` → 所有存活 Activity `bind()` → `walk(force = opacityChanged)` |
| MainActivity 手动绘制 | **新增** `surfaceOpacityListener`（onStart 注册 / onStop 注销）→ `applyThemeToDashboard()`；保留 onResume 兜底 |
| TerminalActivity | 自有 `appearanceListener` → `applyAppearance()` → `applyChromeColors` + `applyTheme`（键条/抽屉）+ `applyTerminalBackground`（scrim） |
| SettingsActivity | 滑杆 `onProgressChanged` 内显式 `ThemeApplier.bind` |
| 不自动重绘 | isStatus 徽章、SurfaceView/TextureView/TerminalView 子树（设计豁免） |

## 4. 下限 55% 的对比度依据

- `minSdk 26`，滑杆 55..100（46 档）。
- 纯色面：`resolveEffectivePercent` 以「surface 合成到 page 背景」实测 WCAG 对比度，<4.5:1 回退到 55%。
- 有壁纸的面：跳过对比度回退，由页面 scrim 兜底（用户选择背景图即接受该对比度权衡）。
- 单测：`SurfaceOpacityTest` 12 用例（钳制/混合/回退/背景图分支）。

## 5. 已知边界（如实记录）

1. 状态徽章 `isStatus()` 名单（`ThemeApplier.kt:582-585`）仍豁免，透明化会让「错误/警告」徽章与背景混同。
2. 终端页 chrome 与抽屉保持 `applyOpacity`（不做对比度解析）：终端背景图场景由 scrim 兜底，且该链路已有独立 token。
3. `surfaceTranslucent` 字段仍保留在 `AppTheme` 数据类（主题目录 20 套色板），仅退化为「未改造路径的兼容值」；生产消费点已全部切到 `SurfaceOpacity`。

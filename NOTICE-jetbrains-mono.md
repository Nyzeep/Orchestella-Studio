# 字体来源与许可证（JetBrains Mono NL）

本文件只覆盖 `app/src/main/res/font/jetbrains_mono*` 的终端/等宽角色字体资产
（W1-06，对应工单 `T-07`）。App 全局正文默认字体不受影响，仍由
`ThemeStore.KEY_UI_FONT_CHOICE`（`"system" | "mono"`）管理，两者互不干涉。

## 1. JetBrains Mono NL v2.304

- 上游：**JetBrains/JetBrainsMono** 官方发布 tag **v2.304**（纯正官方包，不含
  第三方 Nerd Font 补丁字形）
- 上游仓库：<https://github.com/JetBrains/JetBrainsMono/tree/v2.304>
- 发布包：`JetBrainsMono-2.304.zip`（5,622,857 字节），SHA-256
  `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`
- 取用变体：**NL（No Ligatures，无编程连字）**，即 zip 内 `fonts/ttf/` 下的
  4 个静态字重文件。NL 变体完全不含 GSUB 表（连字特性整体移除），不会把
  `->`、`!=`、`>>` 合形为宽字形，`TerminalScreenModel` 按格索引、光标对齐、
  选区手柄与后续 Canvas 逐格绘制均不受影响。
- 许可证：**SIL Open Font License 1.1**，官方全文随包落盘于
  `app/src/main/assets/licenses/JetBrainsMono-OFL-1.1.txt`（与上游 zip 内
  `OFL.txt` 字节级一致）
- 修改说明：仅重命名以匹配 Android 资源名约束（小写+下划线）；字形与度量
  数据零修改。四字重实测 upem=1000、全部字形 advance=600 units/em，
  跨字重度量一致（由 `JetBrainsMonoNoticeTest` 持续验证）。

| 落盘路径（res/font） | 上游文件（fonts/ttf） | SHA-256 |
|---|---|---|
| jetbrains_mono_regular.ttf | JetBrainsMonoNL-Regular.ttf | `fb3b2575d7b0657359707993288f12a7360344d39387bb26050e276d61f6bd2a` |
| jetbrains_mono_bold.ttf | JetBrainsMonoNL-Bold.ttf | `0198e841824025f8876e5c297f0b9b497ee8d6eb9969710a3328e1303f996ec3` |
| jetbrains_mono_italic.ttf | JetBrainsMonoNL-Italic.ttf | `c7392a134293e1af1d36fbf04940dd844f632afbf97f8325d1591d3af5096cb8` |
| jetbrains_mono_bold_italic.ttf | JetBrainsMonoNL-BoldItalic.ttf | `6c2716f2e85101f4109a8c16916061eb38176b3b10342834858d6221ec2fda1b` |

## 2. 消费面与回退契约

- Compose 侧：`OeFonts.JetBrainsMono`（`ui/design/tokens/OeFonts.kt`）为唯一
  家族单源，`OeTypeRole.Mono` 与 Theme Studio 等宽正文均指向它。
- View 侧：`OeLegacyViewStyle.monoTypeface(context, style)` 为唯一入口，经
  font-family XML（`res/font/jetbrains_mono.xml`，android: 与 app: 双映射）
  解析真实字重，杜绝合成假粗体；资源不可加载（如 JVM 单测环境）时回退平台
  `Typeface.MONOSPACE`。
- 换字体必须同时更新本文件与 `JetBrainsMonoNoticeTest` 的 SHA-256 pin，两者
  之一漂移即测试红。

# 54: 现代终端 VT100/ANSI 256 色渲染器与移动增强按键栏

Type: implementation  
Status: ready-for-agent  
Labels: m4-spec, tracer-bullet, terminal, modern-shell, extra-keys  

## User Story

作为在手机上进行日常排错与命令行开发的工程师，我希望终端不仅能输出单色文字，还能完美支持 ANSI 256 色与真彩色高亮渲染，并且在手机输入法上方提供一排高频快捷辅助按键（如 Esc、Tab、Ctrl、方向键和管道符 `|`），让我不用每次切换键盘就能顺畅使用 vim、nano 或命令行补全（US-26, US-27）。

## What to build

在 `:app` 中全面升级 `TerminalActivity`，替换既有的单色 `TextView`，遵循 ADR-0002 与 spec-m4.md：

1. **现代终端解析与渲染引擎（`ModernTerminalView`）**：
   - 彻底废除基于普通 `TextView` 粗暴追加字符串的原始模式；
   - 引入轻量高性能的 ANSI / VT100 转义序列解析状态机：
     * 支持 SGR（Select Graphic Rendition）控制码（粗体、斜体、下划线）；
     * 支持标准 16 色、256 色扩展调色板以及 24-bit RGB TrueColor 真彩色；
     * 采用工业界标杆 Dracula 终端配色方案（背景色 `#282A36`，前景色 `#F8F8F2`）；
   - 使用 Android `SpannableStringBuilder` 与 `ForegroundColorSpan` 进行批处理分段染色；
   - 采用等宽字体（JetBrains Mono 或 Android Monospace），确保字符网格严格对准；
2. **移动专属辅助键盘栏（`MobileAuxiliaryKeyBar`）**：
   - 固定吸附于软键盘输入区上方，横向滚动/紧凑排列核心开发按键：
     * 常用控制键：`ESC`、`TAB`、`CTRL`、`ALT`；
     * 命令行符号：`|`（管道符）、`/`、`-`、`~`、`$`、`\`；
     * 历史导航键：`↑`（上一条命令）、`↓`、`←`、`→`；
   - **粘滞修饰键状态机（Sticky Modifier State）**：
     * 点击 `Ctrl`：按钮高亮激活；用户按下下一个字符（如 `C`）时，自动合成发送对应控制字符（`\x03` 即 `SIGINT`），并自动复位锁定状态；
     * 双击 `Ctrl`：持久锁定（Lock State），便于连续发送控制组合；再次点击解锁；
3. **软键盘自适应与双模平滑跳转**：
   - 监听软键盘弹出/收起，动态调整终端视口滚动区域，保持光标始终位于可视区；
   - 顶部工具栏保留直达图形桌面的切换按钮（`btnSwitchDesktop`），与 `DesktopActivity` 双模无缝穿梭。

**Blocked by:** 44, 52

## Seam Design

- **终端解析状态机解耦**：将 ANSI 协议解析逻辑封装于纯 Java 的 `AnsiEscapeParser` 类中，输入为字节/字符流，输出为带样式属性的 `TerminalSpan` 列表，具备 100% 可测性；
- **按键事件派发接缝**：`MobileAuxiliaryKeyBar` 依赖 `TerminalInputSink` 接口，仅负责发送转义字符字节（如 `ESC -> \x1b`, `TAB -> \t`, `UP -> \x1b[A`），不感知底层套接字类型。

## Acceptance Criteria

- [ ] `ModernTerminalView` 成功解析 ANSI 转义序列，包含 16 色、256 色代码（如 `\x1b[38;5;196m`）与 TrueColor 代码（`\x1b[38;2;r;g;bm`），颜色正确映射至 Dracula 调色板；
- [ ] 软键盘上方的辅助按键栏能够准确发送 `ESC`、`TAB`、管道符 `|` 及方向键字节序列；
- [ ] `Ctrl` 粘滞键逻辑严密：点击 `Ctrl` 后按普通字符键（如 `c` 或 `z`），终端正确向底层 PTY 写入 `\x03` 或 `\x1a`；
- [ ] 界面在软键盘弹出与收起时无布局错乱，滚动条自动跟随最新日志输出；
- [ ] 纯 JVM 单元测试（`AnsiEscapeParserTest` 与 `StickyModifierStateMachineTest`）100% 通过。

## Device Verification Steps

在 Android 真机上验证现代终端颜色与辅助按键：
```bash
# 1. 打开实例终端
# 在终端中执行颜色测试脚本
printf "\e[31mRed \e[32mGreen \e[33mYellow \e[34mBlue \e[35mMagenta \e[36mCyan \e[0m\n"
printf "\e[38;2;255;100;0mTrueColor Orange\e[0m\n"
# 观察文字是否呈现丰富色彩，而非生硬的转义字符串

# 2. 验证辅助按键栏
# 点击软键盘上方的 ESC、TAB 与管道符 "|"，检查输入框中是否正常键入或补全
# 运行前台死循环命令：while true; do sleep 1; done
# 点击辅助栏的 [Ctrl] 键，再按物理/软键盘 [c]，观察命令是否被立即中断 (SIGINT)
```

## Test Strategy

- JVM 单元测试：
  - `AnsiEscapeParserTest`：测试基础颜色码、高亮粗体、256 颜色码、真彩色格式、以及残缺/畸形转义序列的鲁棒性容错；
  - `StickyModifierStateMachineTest`：测试单击激活、组合消费复位、双击锁定、手动取消状态流转；
- 运行 `./gradlew :app:testDebugUnitTest`。

## Prototype/research dependency

依赖 ADR-0002 终端契约与 `docs/research/2026-10-06-virtualization-ux-and-competitors.md` 中 Termux 辅助按键设计。

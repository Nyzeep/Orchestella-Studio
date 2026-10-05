# 40: 移动端触控手势映射、虚拟输入条与 Android IME 文本注入桥接

Type: implementation  
Status: completed  

## What to build

在 `:app` 中实现移动端多指触控手势映射、虚拟快捷按键辅助栏，以及 Android 宿主输入法（IME）中英文/多字节文本穿透注入引擎，全面解决移动触屏操作桌面 Linux 的交互难题，遵循 ADR-0017。

主要包含：
1. **移动触控手势映射引擎（`TouchGestureMapper`）**：
   - 接管 `SurfaceView` 的 `OnTouchListener` 原始 `MotionEvent` 事件流；
   - **单指轻按（Tap）**：持续时间 $<200\text{ms}$ 且位移 $<8\text{dp}$ 判定为左键点击，向 Wayland 通道派发 `wl_pointer.button(BTN_LEFT)` 按下与释放；
   - **单指平滑拖动（Drag）**：计算相对坐标位移量 $\Delta x, \Delta y$，结合动态加速度曲线平滑映射为光标移动；
   - **双指轻点（Two-Finger Tap）**：双指短时间内同时点击判定为右键点击（`BTN_RIGHT`），弹出上下文菜单；
   - **双指协同滑动（Two-Finger Scroll）**：垂直与水平滑动向量线性映射为连续平滑滚轮事件（`wl_pointer.axis`）；
   - 支持直触模式（Touchscreen）与相对触控板模式（Trackpad）实时切换；
2. **悬浮/固定虚拟快捷键栏（`VirtualKeyBar`）**：
   - 停靠在屏幕边缘或软键盘顶部的半透明操作栏；
   - 包含键位：`Ctrl`、`Alt`、`Shift`、`Super`（Win 键）、`Tab`、`Esc`、方向键（`←` `↑` `↓` `→`）与软键盘呼出/隐藏切换按钮；
   - **按键锁存状态机（Latch State Machine）**：
     * 单击修饰符：进入待命锁存状态（Latch），高亮显示，下一次普通按键触发后自动跟随释放；
     * 双击修饰符：进入常驻锁定状态（Locked），持续生效直至再次点击解除；
3. **双模融合 Android IME 穿透桥接器（`ImeBridgeInputConnection`）**：
   - 实现 Android 自定义 `InputConnection`，接管宿主软键盘的字符输入事件；
   - 捕获 `commitText(CharSequence text, int newCursorPosition)` 提交的任意 Unicode 字符串（包含中文汉字、标点与 Emoji）；
   - **优先路径**：通过 Wayland `text-input-v3` 协议（`commit_string`）将文本精准递送给已获得文本焦点的 Wayland 客户端（如 Chromium、Foot、Firefox）；
   - **兜底路径**：针对 XWayland 或未适配 text-input 协议的传统窗口，通过桥接通道合成 UTF-8 字符流直接注入，实现 100% 中文上屏无乱码、不漏字。

**Blocked by:** 38, 39

## Acceptance criteria

- [x] 触控手势映射器精准识别单指点击、长按、移动、双指右键与双指滚轮，手势误触率 $< 3\%$；
- [x] 虚拟快捷键栏完整展示，修饰符单次锁存与持续锁定状态机切换准确，`Ctrl+C`、`Ctrl+V`、`Alt+Tab` 等经典组合键准确生效；
- [x] 弹出 Android 软键盘时，桌面视图能自适应平移或保持焦点区域可视，软键盘不遮挡输入光标；
- [x] 宿主输入法打出的中文汉字与特殊符号通过 `commitText` 能够准确无误地呈现在 Guest 文本编辑器中；
- [x] 触控与文本事件序列化后可经由 `WaylandChannel` 稳定传送，无丢包与事件死锁。

## Test strategy

- JVM 单元测试：
  - `TouchGestureMapperTest`：模拟注入不同时间戳与坐标的多点触控 `MotionEvent` 序列，断言输出的指针动作与滚轮值；
  - `ModifierKeyStateMachineTest`：验证修饰符单击锁存、双击锁定、组合击键后自动释放的完整状态转移；
  - `ImeBridgeCommitTest`：验证多语言（英文、中文简体、特殊标点、Emoji）在 `InputConnection` 中的转码与分发逻辑；
- Android 真机交互测试：在 HONOR 80 GT 真机上调出 Gboard/微信输入法，验证在 Foot 终端与桌面文本框中连续输入中文段落。

## Prototype/research dependency

依赖 Ticket 38 的输入事件契约，以及 `docs/research/2026-10-05-wayland-mobile-graphics-channel.md` 第 4 节中的手势与 IME 调研方案。

# T2 · 动效挂钩 + 小项

Status: done
Owner: lane/t2-motion（子智能体）
Files: app/src/main/java/io/github/nyzeep/orchestella/MainActivity.kt（state 挂钩，注意与 T1 的文件交集——只动 state 切换调用点，不动 formatLaunchFailure）、app/src/main/java/io/github/nyzeep/orchestella/SettingsActivity.kt（playSectionStagger 重放）、app/src/main/java/io/github/nyzeep/orchestella/ModernTerminalView.java（copySelection 反馈）、app/src/test/java/io/github/nyzeep/orchestella/ControlChannelRecoveryTest.java（pump 预算）、ui/motion/OrchestellaMotion.kt（仅 KDoc 勾销注释）

## What

按 `ui/motion/OrchestellaMotion.kt` KDoc "NOTE (TODO, device round)" 清单接线剩余挂钩：

1. **MainActivity 控制台/列表态切换**：container_tab_console 状态交换处接
   `stateChangeEnter`/`stateChangeExit`（showInstances/console 态互换点；注意守卫
   防滞留半透明——参照 InstanceDetailActivity 五页签的既有守卫写法）。
2. **设置分区展开重放**：SettingsActivity 数据刷新/分区重建后
   `playSectionStagger`（一次性首开已由 XML layoutAnimation 承担，仅重放场景）。
3. **ModernTerminalView.copySelection 成功路径补复制反馈**：复制成功后用户可感知
   反馈（Toast 或 Snapdragon 风格轻提示；该文件本轮无主，直接改；文案走 strings.xml
   新键，中英一致）。
4. **ControlChannelRecoveryTest pump 用例实时预算**：`@Test(timeout=4000L)` +
   1s deadline 自旋（`Thread.yield()` 轮询 readinessState、`get(1, SECONDS)`）在
   冷启动负载下偶发超时。放宽整体 timeout 至 ≥10s、轮询 deadline 至 ≥5s
   （保持语义不变），或改确定性调度（注入 scheduler）。禁止删用例。

## Acceptance

- :app 全量单测 ≥1260/0/0（ASCII worktree）；lint 无新增 error。
- 每个挂钩的人工冒烟点写进工单 Comments（真机走查用）。
- OrchestellaMotion.kt KDoc 的 TODO 清单更新为已接线状态。

## 阻塞边 / 边界

- 不得触碰 LaunchFailureCopy.kt / ErrorCopy.kt / strings.xml 的 error_* 键区（T1 边界）；
  strings.xml 只新增本工单键。
- MainActivity 与 T1 有文件交集：改动仅限 state 切换调用点与 motion import；
  formatLaunchFailure/启动路径不动。

## Comments (T2 lane, 2026-11-08)

实现落点：
1. MainActivity.switchTab——带动画的页签切换（console/list 五容器互换）接
   stateChangeExit/stateChangeEnter；守卫：tabSwitchGeneration 世代计数 + 延迟
   GONE 双条件校验 + 非相关容器立即 GONE 扫荡。首开（tabsInitialized=false）与
   主题重绘（forceAll）保持瞬时，不动画。3 秒周期 dashboard 刷新不触发动效。
2. SettingsActivity.runMirrorSpeedTest 完成回调——playSectionStagger 重放
   settings_root_container；首开一次性仍由 XML layoutAnimation 独占，onCreate
   路径不调用，无双播。
3. ModernTerminalView.copySelection 成功路径 Toast（strings 新键 terminal_copy_done
   =「已复制」；仓库无 values-en，无需同步英文）；失败路径保持静默。
4. ControlChannelRecoveryTest 两条 @Test(timeout=4000L) pump 用例放宽至 10000L，
   内部 1s 预算（自旋 deadline / Future.get / await）同比例放宽至 5s；断言与
   语义未变，未删用例；awaitCurrentControlReady 的 1500L 业务参数保持原值。

人工冒烟点（真机走查）：
- [ ] M1 页签进场：主界面在「控制台/实例/设备/发行版/设置」五个页签间逐一点击切换，
      每次切入的容器应有淡入+轻微上浮（约 300ms），切出的容器先淡出（约 150ms）再消失。
- [ ] M2 快速连点不残影：在两个页签间以最快速度来回连点 ≥10 次，随后停在任一页签，
      等待 1s——当前页签内容完全可见（alpha=1，无半透明滞留），其余页签不可见且
      不可误触（连点期间被切走但退场未完成的页签不会盖住当前页签）。
- [ ] M3 回切不消失：点切到 B 页签后在 150ms 内立刻点回 A（在 A 淡出动画进行中），
      A 应回到完全可见且播放入场动画，不因旧退场定时器被 GONE 而整块消失。
- [ ] M4 首开无动画：冷启动进入主界面，首个页签直接呈现，不播放入场动画；
      「设置与主题」页里改主题色触发全局重绘（forceAll）时，页签切换不重播动画。
- [ ] M5 列表刷新不闪：停留在「实例」页签 10s 以上（后台每 3s 快照刷新），
      列表内容更新时不得反复淡入闪烁。
- [ ] S1 设置分区重放：设置页点「开始测速」，测速完成 Toast 出现的同时，
      设置各分区卡片按 stagger 重新淡入一遍（约 45ms 间隔逐个入场）；
      再次进入设置页时首开 stagger 只播一次（无双播）。
- [ ] T1 复制反馈：终端页长按选中一段文本，工具栏点「复制」（或物理键盘 Ctrl+C），
      出现「已复制」Toast；未选中任何文本时执行复制不出 Toast。
- [ ] R1 pump 用例：`./gradlew :app:testDebugUnitTest` 中
      ControlChannelRecoveryTest 的 reentered/obsolete 两条用例在冷启动后
      首轮运行仍稳定通过（预算 10s/5s）。

验证：ASCII worktree `:app:testDebugUnitTest` 1260/0/0；`lintDebug` 0 error
（34 warnings 均为存量）。改动未触碰 formatLaunchFailure/启动路径/LaunchFailureCopy/
ErrorCopy/error_* 键区/res/anim/themes.xml/SetupWizardActivity。

## Comments

### 2026-11-10（编排层回填）

T2 完成于 lane/t2-motion 13c9cb4f+73702b20。真机走查 M1/M2/M3 PASS（m1–m3 截图），S1/T1 由 W2 重装后覆盖验证。pump 用例 4s→10s/1s→5s 放宽，断言语义零变化。

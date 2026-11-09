# E · UX 走查改进（含缺陷修复，批次3 开工）

Status: done
Owner: lane/e-ux-walkthrough
Files: SetupWizardActivity.kt、SettingsActivity.kt、InstanceDetailActivity.kt、TerminalActivity.java（走查项）

## What

1. **T4② 缺陷修复**：向导创建中按 BACK 直接退出无确认框——onBackPressed/
   OnBackPressedCallback 在 isCreating 时走 confirmExitWhileCreating（与
   btnWizardSkip 同路）。
2. 删除守卫 UX：运行中断实例的删除按钮呈粉色禁用但无解释——补禁用原因提示
   （如「运行中断实例需先复位」），或自动引导走重置。
3. T4③ 受控注入复测路径：向导失败文案 E-* 展示的设备侧可复现注入点设计
   （如离线态装配、磁盘预检失败分支），至少打通一条真机可复现腿。
4. T4⑥ 空态半项：无实例时终端直启空态文案真机确认（本机无法安全清空实例，
   在 Emulator/次设备验证或 JVM 层补 UI 契约）。
5. 复制反馈/泵测试等 2026-11-08 轮遗留的走查项复核。

## Acceptance

- BACK 确认框：创建中两分支真机可复现（截图）
- :app 全量 ≥1266/0/0；lint 0 新增

## Comments

### 2026-11-10（编排层回填）

lane/e-ux 3b85050c 合并 main；:app 1292/0/0（+9）。BACK 确认框接线（shouldConfirmExit 纯函数）收口 T4②；**复位 no-op 根因修复**（reconcileAfterHostDeath 对 INTERRUPTED/CRASHED no-op → resetToStopped 分流，2026-11-08 真机缺陷解除）；删除守卫提示键；E-STORAGE-FULL 注入腿（预检计已有占用）打通 T4③；终端空态契约收口 T4⑥。离线装配腿留下轮。

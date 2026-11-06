# 零设备全项目优化轮 Spec（2026-11-07）

定位：设备窗 2026-11-07 收口后（main @ 496270c8，CI 全绿），用户授权开启的全项目
大型优化轮：代码优化 / 系统性能增强 / 系统整合 / 软件流畅度 / 用户体验 / 软件动效。
全部零设备可做；高风险 UI 重构以"机械抽取 + 行为保持 + 测试锁定"为边界。

## 硬约束（同 HANDOFF §3）
- 子智能体 ≤2/批；lane 各自 ASCII worktree + 独立分支；文件边界预分配（下表）越界禁改。
- 构建前清理旧包残留：每个 lane 的构建命令先 `./gradlew clean`（或删 outputs）再构建。
- 敏感门 origin/main..HEAD 提交前强制；commit message 无绝对路径/设备信息。
- 收口：编排层统一合并 → 敏感门 → ASCII worktree 集成冒烟（:app 全量单测）→ push → CI 确认 → 轮报告 → 记忆。

## Lane 矩阵与文件边界

| Lane | 方向 | 独占文件集 |
| --- | --- | --- |
| A 代码优化与整合 | 大文件拆分/去重/lint 收敛 | runtime/instance/InstanceLifecycle.java、runtime/contract/BridgeControl.java、runtime/fastlane-terminal/TerminalMux.java、app ProtectedWorkerPool.java、ProtectedWarmPool.java + 各自新抽取类/测试 |
| B 动效与流畅度 | Material Motion 全局过渡/动效体系 | app/src/main/res/**（strings.xml 除外）、新 ui/motion/ 包 |
| C 系统性能 | 冷启动/主线程卫生/懒加载 | MainActivity.kt、Application 类、启动路径新类 |
| D 用户体验 | 首启向导/设置页 UX/文案人性化 | SetupWizardActivity.kt、SettingsActivity.kt、strings.xml、TerminalActivity.kt/.java、InstanceDetailActivity.kt |

跨 lane 协同点（只读协商，不并行改）：
- MainActivity 的首启通知权限时机与错误展示人性化 → C 落地（D 出规格）。
- A 的拆分不得改公共 API 语义；C/D/B 禁改 runtime/**。
- themes.xml 归 B；若 C/D 需要主题属性只读引用。

## 验收
- 每 lane：新/改逻辑有测试锁定；受影响模块 check + :app 全量单测绿；lint 不新增 error。
- 轮级：全量合并后 :app 单测 ≥1227 全绿、CI 五 workflow 绿、轮报告入库。

# 首页与主题的真实行为

Type: research
Labels: wayfinder:research
Status: resolved
Assignee: theme-worker / coordinator
Parent: ../quality-20261013-map.md
Blocked by: none

## Question

现有优秀构建主义视觉如何保留并消除无意义装饰？主题选择到跨页面/弹窗/动态卡片有哪些断链？如何替换假终端、固定性能数据和虚构 SSH 地址，以周期更新的真实宿主数据、实例状态、实际执行策略与可用入口呈现？

## Comments

2026-10-13：主题 worker 负责存储/通用应用与设置，coordinator 负责 MainActivity 和主页布局。用户明确取消 App 首次启动引导，保留主动创建实例配置。禁止清除原有偏好/实例。

## Answer

保留既有构建主义几何设计，统一 ThemeApplier/ThemeStore 对页面、弹窗、导航、输入与动态卡片着色；设置字号/光标/间距真实持久化。去掉强制 App 引导、假终端及固定性能信息，改为真实操作、前台3秒设备刷新、实际运行引擎与 Linux 初始化进度。SSH 基于实际用户保存和应用的规则显示，转发关闭后不会重启再开放；图形真实通道未完成，UI 不再显示模板画面为可用桌面，该增强交独立 Phase 5 会话。

证据：[主题审计](../../../docs/research/2026-10-13-theme-ui-audit.md)、[独立 Phase 5 开工指令](../../../docs/agent-handoff/PHASE5_PARALLEL_SESSION_PROMPT.zh-CN.md)。

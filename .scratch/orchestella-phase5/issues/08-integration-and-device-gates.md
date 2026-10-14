# 同一实例的 Android / guest 真通路合流与本地交付

Type: implementation
Status: implemented-local; device-gates-open
Owner: phase5-coordinator
Blocked by: 05, 06, 07 (最终合流；独立基线/控制修复先行)

## What to build

以最小精确依赖保留第一会话的Linux/终端/主题/网络行为，把独立worker产物接入控制链、payload、实例选择、Surface与本地开发WebView；隔离构建、独立审查、patch与恢复交接。

## Acceptance criteria

- 复制必要生产闭包逐文件SHA与复制时状态，第一会话只读，Phase5 patch相对此基线生成，不把baseline修复反向覆盖成旧版本。
- DISPLAY请求真实鉴权/命令执行/帧/输入。被拒DISPLAY/FEATURE仍保持PING、唯一control reader不变；新FEATURES明确版本1与固定动作/profile，旧agent拒绝时明确不可用。
- 控制guest代码静态AArch64重建并更新bundle/manifest，不能分发旧agent。
- 默认terminal，明确可安装/实验desktop与optionalproductivity选择；安装/取消/恢复状态真实。保留构建主义设计。
- Defaultloopback本地服务严格WebVieworigin/真实health与stop；LAN必须独立显式timelease，不启用云tunnel。
- GPU/USB/storage能力诚实，不根据nativeinit/静态矩阵宣称完成；真机独占保持不触碰，交可运行验证脚本与设备时间请求。
- APK与SHA、必要输入/NOTICE/buildlock、精确API资源清单，本地可合流patch、回归清单、研究/测试/guest/Android矩阵齐全。
- 独立code-review修复后相关回归通过，缺项/架构门诚实。

## Ownership

协调者独占共享control/FrameData、guest-agent/init、QEMU/AVFadapter、现有App页面、Gradle与payload。worker所有新增模块在交回前不修改。无commit/push/remote写。

## Local delivery resolution

已实现、宿主验证并合流隔离分支。源码与报告：[实施报告](../../../docs/reports/tickets/phase5-08/implementation-report.zh-CN.md)。未运行设备/平台及部分原型能力见正式验证矩阵，不能以本地状态推断完全可用。无commit/push。

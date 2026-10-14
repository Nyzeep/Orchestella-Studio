# 有鉴权的真实 Wayland CPU 帧切片

Type: implementation
Status: implemented-local; device-gates-open
Owner: graphics-worker
Blocked by: 01

## What to build

从真实 Wayland framebuffer字节流解析并发布帧，发送鼠标/键盘/文本，首次合法帧之前不宣称连接成功。以独立I/O集成测试证实握手/帧变化/输入，不用host模板。Android/启动适配由协调者合流。

## Acceptance criteria

- 原创纯Java RFB3.8 codec/channel、版本1 display token preamble，受限Raw/DesktopSize/CopyRect（若支持），严格参数/长度/像素配额。
- 同一 InputStream/OutputStream 接缝适用于QEMU/AVF，backend无行为分叉；无None认证公网服务。
- 包括state、首帧、EOF、未知encoding、token拒绝、重连/close、键盘/Unicode文本/pointer/scroll等外部行为测试；未知能力明确拒绝。
- 单reader、有界frame/update请求、无虚构FPS/GPU；统计来源真实数据流。
- 中文报告、本地源码/测试patch，无commit。

## Ownership / dispatch

独立worktree `Orchestella_phase5_graphics`，branch `phase5/worker-graphics`。独占新增 contract `display/` package、同路径tests；可新增 `guest-assets/display/` 原创relay及其tests（若C需提供构建命令）。禁止编辑既有契约/FrameData/BridgeClient/App/Gradle/guest-agent/init。协调者负责这些接入。遵守总派工与spec；以 signed wayvnc真实frame流作为renderer，无代码复制。允许调用 tdd/implement 不再派生。

外部输出 A/implementation、独立gradle output/cache临时；保留Class A。风险高/实现精度高；工具配置模型无独立选型，使用general-purpose。必要feature auth relay提供versioned token但不新增host文件面。

## Local delivery resolution

已实现、宿主验证并合流隔离分支。源码与报告：[实施报告](../../../docs/reports/tickets/phase5-05/implementation-report.zh-CN.md)。未运行设备/平台及部分原型能力见正式验证矩阵，不能以本地状态推断完全可用。无commit/push。

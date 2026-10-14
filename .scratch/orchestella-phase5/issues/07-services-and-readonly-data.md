# 受限本地服务与只读文件数据面

Type: implementation
Status: implemented-local; device-gates-open
Owner: data-worker
Blocked by: 03, 04

## What to build

在明确端点/用户选定文件范围内提供手机本地开发服务导航与只读staging，不误报未有VMM支持的挂载或USB透传；可独立运行listener/文件一致性验证和基准。

## Acceptance criteria

- 纯Java本地service endpoint与认证/状态/生命周期模型，精确127.0.0.1端口/origin限制、reject userinfo/query-secret/file/content/javascript/外部host。
- 真正loopback listener/relay lifecycle（若需要），stop后监听消失、端口冲突真实失败、LAN未显式授权/超时拒绝，关闭后不persist重开。
- 只读明确文件集合staging，名称/URI来源/数量/字节quota、取消/failed cleanup、原子generation、symlink/path escape拒绝，不递归扫描宿主。
- 不读写真实用户文件，benchmark只合成数据，冷热条件/大小路径/CPU内存/一致性及断流真实摘要。
- 有意义外部行为JVM测试覆盖negative边界；USB grant/FD lifecycle作为纯原型/可运行probe设计，真实passthrough需ACR。
- 中文报告、外部benchmark结果与本地patch；原型/未运行界限明确。

## Ownership / dispatch

独立worktree `Orchestella_phase5_data`，branch `phase5/worker-data`。独占新增 `runtime/contract/.../services/`、`runtime/contract/.../sharing/`和其tests；新增 `prototypes/phase5-readonly-data/`/`prototypes/phase5-usb-fd/`（有明确prototype标记），与所属报告。不得改既有Avf9pServer（稍后单独bug票）、App、Bridge/guest、Gradle、ADR/tracker。协调者负责Android WebView/SAF接入；让API最小且清晰。call implement+tdd，不派生。

所有生成物外部 C/implementation、B/implementation、C/benchmarks，原始 .log.txt/json 即时保全；不删Class A。不扩大Downloads只读暴露面、不新系统permission、无宿主上传。高风险I/O独立review待协调者派发。

## Local delivery resolution

已实现、宿主验证并合流隔离分支。源码与报告：[实施报告](../../../docs/reports/tickets/phase5-07/implementation-report.zh-CN.md)。未运行设备/平台及部分原型能力见正式验证矩阵，不能以本地状态推断完全可用。无commit/push。

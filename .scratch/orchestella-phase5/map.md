# Orchestella Phase 5 并行开发决策图

Type: wayfinder:map
Status: local-delivery-complete; device-and-platform-gates-open
Owner: phase5-coordinator

## Destination

在隔离分支交付真实显示通路、可恢复桌面/容器/开发服务与严格外设存储原型；代码、APK、输入/源码锁、宿主验证、本地patch与交接均可审查。整个Phase5设备能力尚未完成，受阻项目保持明确状态。

## Notes

用户授权研究后继续实现。本轮ask-matt/wayfinder/research/spec/tickets/implement-spec/tdd/独立review已实际推进到本地交付。分支 `phase5/graphics-productivity-storage`，HEAD `8d0e8c75d769ecc645ea102b90a304c8657344a1`。活动主仓只读、设备第一会话独占、无commit/remote写。

[调度记录](../../docs/reports/archive/2026-10-phase5-quality/orchestration/phase5-dispatch.zh-CN.md)、[整合交付报告](../../docs/reports/archive/2026-10-phase5-quality/tickets/phase5-08/implementation-report.zh-CN.md)、[验证矩阵](../../docs/reports/archive/2026-10-phase5-quality/orchestration/phase5-verification-matrix.zh-CN.md)、[恢复集成指南](../../docs/reports/archive/2026-10-phase5-quality/orchestration/phase5-integration-guide.zh-CN.md) 是当前canonical交付入口；raw/patch/APK在外部delivery，worker工作区保留。

## Decisions so far

- [真实图形传输与AVF权限](issues/01-real-graphics-and-avf.md)：同一FullLinux的Wayland headless/pixman+私有鉴权CPUcopyfallback；MANAGE不是custom/GPU充分条件，bufferzero-copy另过门。
- [桌面组合与恢复事务](issues/02-desktop-package-matrix.md)：只开放有实现/包证据的Debian13 labwc；未知组合/KDE/niri不显示可安装，捕帧与传输ready分开。
- [Rootless容器与本地开发服务](issues/03-rootless-productivity.md)：普通guest用户、版本明确网络、CLI/daemon分开；code-server固定ARM64输入，每实例随机强凭据，默认loopback、LAN显式lease。
- [OTG与只读共享](issues/04-usb-readonly-storage.md)：Android用户授权FD、nativeVMM入口与kernel exporter不同；精确Downloads catalog/只读staging为已测原型，nativeUSB/SAFbroker不伪报。

## 本轮实施状态

- 图形codec/relay、profiles、services/staging、AVFAPI/FD/stop/persistentrestore、标准readonly9p已文件级合流。
- Phase5独立APK为 `.phase5`应用，已构建/签名/内容校验；host输入/generation/颜色/失败恢复以及sharedcontrol通过当前gate。
- Java/WSL/合成流程测试与失败历史保全；App61、控制50/50、RFB18/relay15/data26、AVF19及9presources实测见矩阵，不累计重跑凑数字。
- 精确before依赖、ownedpatch、source manifest、build lock、NOTICE与设备时间请求已交付；本地patch反向检查及141file实际forwardreplay通过，最终文档更新后仍按新manifest复核。

## Not yet specified / 未完成能力门

- 实际Android/ARM64guest第一条桌面切片与renderer/帧率/CPU/RSS/copybytes设备测量，待明确设备交接；所有未跑矩阵为NOT_RUN。
- 全七发行版KDE/niri/labwc组合扩展，需各signed包/seat/renderer验证而不是统一绿徽章。
- Dockerrootlessdaemon、AVFeditor服务转发、实际移动WebViewcookie/WS/SW全流量、CJK/emoji各应用输入。
- AndroidSAFheldFD来源证明/productionreadonlybroker、USBnative跨进程FD/VMM热插拔、真实手机IO基准。
- [GPU/buffer/USB权限架构复核](issues/ACR-01-gpu-buffer-and-usb-authority.md)保持needs-architecture-decision；既有ADR未被静默重写。

## Out of scope

远端推送/发布/PR/commit；主仓覆盖；未经交接设备操作；系统驱动/虚拟化/ADB重配；静默cloudtunnel、任意宿主/Git/凭据共享、默认写共享；PRoot替代；模板帧/虚构GPU/FPS/独立PTY。

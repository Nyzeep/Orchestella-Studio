# 可恢复的真实 guest 桌面与开发 profile

Type: implementation
Status: implemented-local; device-gates-open
Owner: profiles-worker
Blocked by: 02, 03

## What to build

用户明确选定后安装并启动真实普通用户 Wayland桌面、rootless容器或code-server，阶段与失败可查询/取消/重试，保持基础Linux和用户配置。guest执行脚本可独立运行，App/bridge由协调者适配。

## Acceptance criteria

- 独立白名单profilectl：probe/install/status/start/stop/cancel/confirm-frame；明确installed/start/awaiting-frame/ready。不同发行版/架构未知组合拒绝，签名包管理未关闭。
- 首组合Debian13 labwc headless/pixman+wayvnc Unix socket与普通用户session。Ubuntu24.04如没有labwc/wayvnc支持则不可选；KDE/niri保留具体实验gate，未能实现不显示可安装。
- 可恢复原子状态/单实例锁/取消/依赖检查/包安装失败不ready，用户配置已有则保留；不长期root compositor、不--no-sandbox、不写基础init。
- rootless Podman真实kernel与普通用户探针、subuids/cgroup/overlay/seccomp/DNS条件；Docker CLI状态独立daemon，不能以包存在冒充run成功。
- code-server固定official ARM64 sha输入、随机实例凭据/0600、普通用户loopback/systemd service/健康与stop，guest NIC relay只显式启用，认证不入URL/log。
- meaningful脚本/进程测试（合成fixture）覆盖失败/取消/磁盘/保留配置/实际listener生命周期；代码输出仅为未实测guest能力。
- 中文报告及本地patch，无commit；不足项精确说明。

## Ownership / dispatch

独立worktree `Orchestella_phase5_profiles`，branch `phase5/worker-profiles`；独占新增 `guest-assets/features/`、`guest-assets/scripts/test-phase5-features*`、新Java `runtime/instance/.../features/`和tests（若需要profile model）。禁止所有既有文件、App/BridgeClient/guest-agent/init/Gradle/policy/ADR/tracker。协调者负责payload embedding、Control路由和App界面。结合02/03研究；call implement+tdd，禁止进一步派生。

外部输出 desktop/implementation 与B/implementation；Class A保全。以config当前模型执行高精度实现，结果必须按真实测试等级记录。长install用后台job，经状态查询；控制不阻塞到下载结束。

## Local delivery resolution

已实现、宿主验证并合流隔离分支。源码与报告：[实施报告](../../../docs/reports/tickets/phase5-06/implementation-report.zh-CN.md)。未运行设备/平台及部分原型能力见正式验证矩阵，不能以本地状态推断完全可用。无commit/push。

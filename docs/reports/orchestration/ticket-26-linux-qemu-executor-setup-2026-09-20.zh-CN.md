# 编排报告：Ticket 26 Linux/QEMU executor 安装与受限诊断放行（2026-09-20）

- 背景 / scope：此前 Ticket 26 因缺少真实 Linux/QEMU executor 而处于 `blocked`。用户明确批准最小 WSL 平台系统安装，要求项目发行版与诊断 runtime 仅放在当前 D: 项目目录。本报告只记录实际环境变更、验证与诊断放行边界；不记录产品实现、APK、CI、真机或 Release 完成。
- branch / 基线 / commit：`ticket/26-linux-qemu-rebuild`，基线 `main` `5dc339b`；本次文档改动前该 worktree clean、与 `main` 0 ahead / 0 behind、无 upstream。

## 实际完成

1. 在用户逐次确认后启用 Windows `Microsoft-Windows-Subsystem-Linux` 与 `VirtualMachinePlatform` optional features，并在要求后重启。
2. 从 Microsoft WSL 官方 GitHub release 获取 `2.7.14` x64 MSI；下载包 SHA-256 为 `DB084E536279A59E90A26EC598D8AA8A4DFF8309F41D078FD06242953AC1EBCD`，Windows Authenticode 结果为有效 Microsoft Corporation 签名。用户手动执行静默 MSI 安装后，实测 `wsl --version` 返回 WSL `2.7.14.0` / kernel `6.18.33.2-2`，无默认 distribution。
3. 从 Ubuntu 官方 cloud image 下载 Ubuntu 24.04 (`noble`) amd64 rootfs；其 SHA-256 与官方 `SHA256SUMS` 的 `2EA580B9DD2D0E97BA9047798BECC63A94427679876DEC193221E345A6E0CDC9` 一致。
4. 使用 WSL2 导入唯一的 `DroidLayer-T26` distribution。rootfs 输入、WSL 虚拟磁盘、apt cache、安装包与诊断工具均在 approved external resource root 的 `test-runtimes/ticket26-wsl-ubuntu/` 下；未使用 `wsl --install <distro>`，因而没有创建默认 Ubuntu 或 C: 用户目录下的发行版。
5. 在该 Ubuntu runtime 仅从已验证的官方 apt endpoint 安装 `qemu-system-arm`、`qemu-utils` 与 `socat`，总计 22 个包、18.0 MB archive、约 94.4 MB guest disk 增量；QEMU 版本为 `8.2.2+ds-0ubuntu1.18`。
6. 实际启动 `qemu-system-aarch64 -machine virt,accel=tcg` 并配置 `virtio-serial-device`、`virtserialport` 与 Unix socket chardev；在 QEMU 仍存活时确认 socket 存在，随后有序终止，probe 通过。
7. 安装包默认启用的 `qemu-kvm.service` 只负责 KVM/KSM/hugepages 准备，不属于 TCG 诊断需求；已用标准 `systemctl disable` 移除 enablement link。当前 WSL 没有以 systemd 作为 PID 1，未运行该服务。

## 未完成内容

- 尚未建立或运行覆盖“PTY first byte → Guest Agent → virtio-serial → host relay → terminal transcript”的 red-capable command；
- 尚未启动 diagnosis Worker 或 implementation Worker；
- 未下载 Alpine、guest kernel、UEFI firmware、Android/NDK、APK/CI artifact，未运行 Gradle、CI、ADB、真机、产品 Guest Agent 或 terminal transcript；
- 未完成 Ticket 26 的任何 Acceptance Criteria、GPL delivery、GitHub Release、GitLab mirror、tag 或源码包。

## 修改文件

- `.scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md`：环境 blocker 更正为受限诊断 ready 状态；
- `docs/reports/tickets/26/dispatch-packet.zh-CN.md`：替换为唯一 Phase 1 diagnosis packet；
- `docs/reports/orchestration/ticket-26-linux-qemu-executor-setup-2026-09-20.zh-CN.md`：本报告。

## Host 与外部资源实际变化

| 分类 | 实际资源 | 写入 / 覆盖 | 清理责任与保留 |
| --- | --- | --- | --- |
| 已批准的 system-level WSL platform | Windows optional features 与 Microsoft WSL 受管安装位置 | 启用两项功能、安装 WSL MSI；无项目源码或项目 artifact 写入该位置 | Nyzeep 负责 Windows 工具生命周期；不属于项目 runtime 目录 |
| 项目 external runtime | approved external resource root 下的 `test-runtimes/ticket26-wsl-ubuntu/` | 新建目录、官方下载 MSI/rootfs、导入 distro virtual disk、guest package/cache | Nyzeep / Orchestrator；仅用于 Ticket 26 Linux/QEMU diagnosis，Ticket 26 完成后须单独审计是否保留或可逆退役 |
| 临时 QEMU probe | `DroidLayer-T26:/tmp/droidlayer-t26-qemu-probe.*` | 每次 probe 新建并由 trap 删除 | 已清理；不进入 Git 或 external artifact |

未覆盖已有项目文件、Git worktree、历史 Ticket 26 WIP、默认 Linux distribution 或用户数据。Windows WSL 平台不能重定向到 D:，这是已提前披露并获用户明确批准的工具强制系统级例外；Linux distribution 与所有项目诊断数据均在 D: 项目目录。

## 测试与验证结果

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| WSL 可用性 | PASS | `wsl --version` 为 2.7.14.0；默认版本 WSL2；无默认 distribution |
| Ubuntu 导入位置与系统 | PASS | `DroidLayer-T26` 为 Ubuntu 24.04 x86_64，WSL2；导入目录在 D: approved root |
| rootfs 输入校验 | PASS | 官方 Ubuntu SHA-256 清单与下载 rootfs 一致 |
| QEMU 可执行性 | PASS | `qemu-system-aarch64` 8.2.2 可执行 |
| TCG + virtio-serial socket | PASS | QEMU live 时 socket 存在，随后有序终止 |
| PTY / Guest Agent / relay / transcript | 未运行 | 留给受限 `diagnosing-bugs` Phase 1；当前不能宣称完成 |
| APK / CI / 真机 / GPL delivery | 未运行 | 明确不在本 scope |

## 模型、委派、workflow 或 worktree 影响

- 没有委派 Worker；Orchestrator 完成已获批准的 Host setup 与验证。
- Ticket 26 在本诊断线从 `blocked` 恢复为 `ready-for-agent`；它合入 `main` 前，`main` 保持旧的 `blocked` 记录。该状态只授权一个 GPT-5.6 Sol / High 的 `diagnosing-bugs` Phase 1 Worker；不等于正式 implementation 已开始。
- active Worker 在派发前仍为无；历史 original、follow-up、canonical worktree 均未读取、修改、merge、cherry-pick、reset、stash、clean 或删除。

## 风险与后续动作

- 该 x86_64 Ubuntu/QEMU/TCG runtime 是真实 Linux/QEMU executor，但它不是 Android arm64 APK 或真机环境；它只能用来建立和调试 terminal relay 的真实 QEMU 路径，不能替代 Ticket 26 的交付/真机验收。
- 下一步只能派发 packet 定义的 Phase 1 diagnosis：先实际得到 tight、red-capable 命令；没有该命令不得提出产品代码假设或写入实现。
- 若红色 loop 需要 kernel、Alpine、Guest Agent 构建输入、firmware、额外 apt 包、网络下载或任何 Host 写入，Worker 必须停止并申请新的具体授权。

## 越界与 Architecture Change Policy 检查

- Host 环境改动已逐项披露、用户批准、最小化并记录；未改 system PATH、防火墙、安全策略、项目源码、ADR、spec、CONTEXT、产品契约或暴露面白名单。
- 网络请求只针对 Microsoft WSL 官方 release、Ubuntu cloud image、Ubuntu 官方 apt endpoints，内容仅为用户批准的工具/rootfs/package 输入；未上传项目内容、Git 历史、凭据、用户数据或运行日志。
- 本次恢复 ready 状态是外部执行器事实更新，不改变已接受架构；Architecture Change Policy 未触发。

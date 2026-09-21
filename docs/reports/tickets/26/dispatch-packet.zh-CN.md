# Ticket 26 Dispatch Packet（Phase 1 Linux/QEMU terminal relay 红色基线已建立）

> 状态：READY-FOR-AGENT — Phase 1 资产 gate 与红色诊断已完成（2026-09-21）
>
> 用户指定的 Ticket 25 新鲜资产已独立校验，真实 Linux/QEMU/TCG terminal relay 红色反馈已
> 实际运行。此记录只保留完成后的诊断边界与下一次独立 implementation + TDD dispatch 所需事实；
> 它不授权本 session 实施产品、APK/CI、ADB/真机、Release、GitHub/GitLab 写入，或历史 WIP
> 的读取、修改、采用与处置。

- 票号/标题：26 — 第一垂直切片：创建 Alpine 实例并进入 shell（QEMU 档）
- 当前诊断线 tracker 状态：`ready-for-agent`；无 active Worker
- 已确认新实施线 owner：Codex Orchestrator；下一次实施必须先形成独立 implementation + TDD dispatch
- 已确认干净诊断 worktree：ticket/26-linux-qemu-rebuild，基于 main
  `8859234a7cd922d4a99385874d1638f31e20c518`，clean、0 ahead / 0 behind、无 upstream；它不是历史
  ticket-26-canonical 的替代或清理授权
- 当前 workflow：`diagnosing-bugs` Phase 1 已完成；未进入假设、instrumentation、fix 或产品实现
- 默认 Risk：3；触及 QEMU 供应链、host↔guest 安全边界、token、生命周期语义、数据持久化、
  Guest Agent 产品代码或任何 accepted ADR 时升为 Risk 4 并停止越界修改/上报。

## 1. 历史环境阻断（已由 2026-09-20 setup 解除）

> 以下段落是 setup 前的历史记录，保留用于说明为何曾标记 `blocked`；它不是当前环境事实。

- 只读预检发现 WSL 启动器，但没有已注册的 Linux distribution；
- 未发现可直接调用的 qemu-system-aarch64、qemu-system-x86_64、Docker、Podman 或 vctl；
- 因此尚不存在可运行 PTY 首字节 → Guest Agent → virtio-serial → host relay →
  terminal transcript 的真实 Linux/QEMU executor；
- 没有运行 QEMU、构建、CI、ADB、真机或网络命令，也没有安装软件、创建 C: 或 user-level
  runtime 资源；
- 正确状态是 blocked，而不是让 Windows JVM/mock、历史 CI 或静态阅读替代红色反馈。

## 2. 当前 executor 与 Host 边界

- 用户已明确批准并完成 Windows `Microsoft-Windows-Subsystem-Linux` 与
  `VirtualMachinePlatform`；Microsoft WSL `2.7.14.0` 已验证为 Microsoft 有效签名安装。
- 唯一注册的诊断 distro 为 `DroidLayer-T26`：Ubuntu 24.04 x86_64、WSL2。其 rootfs、虚拟
  磁盘、apt cache、安装包和诊断工具均在 approved external resource root 的 D: 项目目录
  `DroidLayer/test-runtimes/ticket26-wsl-ubuntu/` 下；Windows WSL 平台自身的系统位置是已披露、
  已批准且不可重定向的工具强制例外，不得用作项目 artifact、cache 或产品输入位置。
- Ubuntu rootfs 从官方 `https://cloud-images.ubuntu.com/noble/current/` 下载并与官方 SHA-256
  清单一致；诊断依赖只从 Ubuntu 官方 apt endpoint 安装：`qemu-system-arm`、`qemu-utils`、
  `socat`（QEMU `8.2.2+ds-0ubuntu1.18`）。
- 已实际验证 `qemu-system-aarch64 -machine virt,accel=tcg` 在运行期间创建配置了
  `virtio-serial-device` / `virtserialport` 的 Unix socket，并可有序终止。该 probe 不含 Android
  APK、Guest Agent、guest kernel、Alpine rootfs、PTY 或 terminal transcript；它只证明真实
  Linux/QEMU + virtio-serial executor 已可供 Phase 1 使用。
- apt package 默认启用的 `qemu-kvm.service` 是 KVM/KSM/hugepages 准备 unit，不属于 TCG
  诊断需求，已禁用；当前 WSL 没有 systemd 且该服务未运行。
- 除本 packet 的 Phase 1 范围外，禁止新增 Host、D: runtime、C:、Registry、PATH、service、
  firewall、device、network endpoint 或外部 artifact 写入。若 loop 需要额外依赖、kernel、
  rootfs、firmware 或网络下载，必须停止并报告，不得自行安装或下载。

## 3. 已解除 blocker：可复现 guest 资产与红色诊断

- 仅使用用户批准的 Ticket 25 外部 artifact 目录；`SHA256SUMS` 12/12 项匹配，`Image` SHA-256 为
  `d56677f4a2801ab9a9faa68be448d8ca92de5b1058c68b1e132377d810435dc5`，
  `initramfs.cpio.gz` SHA-256 为
  `92766a6f927124d8046348fc4622aa93a842b3eccc279c6ae913f97d8313d7ec`，
  `qemu-smoke-result.txt` 为 `DROIDLAYER_QEMU_SMOKE_OK`。
- 未运行资产构建、未安装或下载任何依赖；`guest-assets/out/` 的不存在不再是诊断 blocker，
  因为该唯一批准的新鲜外部 artifact 已满足本 Phase 1 的只读输入需求。
- 在真实 `DroidLayer-T26` Linux/QEMU/TCG 中，临时 `/tmp` harness 以实际 `/bin/sh` 内部启动点、
  Guest Agent `READY`、virtio-serial Unix socket、host `socat` relay 和 PTY transcript 组成
  red-capable loop。3 次均为 RED：同一首个 PTY payload 的 handshake 已产生 `READY`，但 terminal
  sentinel 未抵达 transcript；单次约 5.7–5.9 秒，所有临时目录均已清理。
- 此红色结果是待实现行为的诊断基线，不是 Ticket 26 产品完成、供应链/CI/真机验收或 GPL delivery
  的通过结论。

## 4. 历史撤回与保全原因

1. main tracker 的 ready-for-agent 与历史 Ticket 26 分支中的 active 记录不一致；分支存在、
   历史提交或旧报告不能取代当前 Worker 的明确领取。
2. 旧 follow-up dispatch 在 reconciliation 前提前使用，且没有确认当前 active Worker、owner、
   canonical branch 与干净工作树。
3. 旧文本包含不稳定的宿主绝对路径，并缺少未来高风险 dispatch 所需的完整风险、能力、保留、
   Host 权限与六项并行核对记录。
4. 当前保留的三个 Ticket 26 worktree 含未合并提交、tracked WIP 或冻结 delivery 草案；本文件不
   授权 merge、cherry-pick、删除、reset、clean、stash、rebase、push 或对 ticket-26-follow-up WIP 的读取/修改。

## 5. 仍然有效但不构成 Phase 1 通过的事实

- Ticket 25 已 completed 并是 Ticket 26 的已完成依赖。
- 历史原分支报告记录过 Linux/QEMU full verify 与 artifact-reuse success；这些是历史 CI/QEMU 范围证据，
  不是当前 canonical HEAD 的动态验证，也不是 Android 真机 Acceptance criteria。
- 真机安装到 shell、错误 token、terminal PTY/RESIZE、guest 正常关机、同 Root Volume 的 stage=run、
  真机 devpts/PTY，以及 APK/native delivery 仍不能写为已完成。
- GPL 交付模型、未来 GitHub 主公开地址、Release Maintainer 和三年保留责任已由用户确认；
  GitLab 公开恢复镜像仍只是未创建的占位，百度网盘只是私有灾备，因此 release gate 仍关闭。

## 6. 受限 Phase 1 diagnosis dispatch（已完成；不能延伸为 implementation）

治理 reconciliation、branch convergence audit、freshness gate、asset integrity gate 与 executor
setup 均已完成。下表记录本次唯一获准的 Phase 1 scope；其完成不隐含产品实现授权。

| 字段 | 必填内容 |
| --- | --- |
| task / session role | 仅为 Linux/QEMU terminal relay diagnosis（本 packet 第 7 节的唯一目标），现已完成；不得隐含领取整张 Ticket 26。任何其他 scope 必须另建并经用户/Orchestrator 批准 packet。 |
| scope / out-of-scope | 可读当前 worktree 中 packet、Ticket 26、scope contract、AGENTS/defensive policies、CONTEXT、ADR-0002/0009/0010/0011，及建最小 loop 绝对需要的 tracked source；禁止 Ticket 27–37、ADR/spec/CONTEXT 写入、冻结 delivery 草案和 ticket-26-follow-up WIP。 |
| workflow | diagnosing-bugs Phase 1；已建立并实际运行红色反馈环，未进入假设或代码修改。 |
| difficulty / Risk / verification cost | Risk 3；Linux/QEMU loop 必须实际运行。触及 QEMU supply chain、host↔guest 安全、token、lifecycle、persistence、Guest Agent 产品代码或 ADR 时升 Risk 4 并停。APK/CI/真机/GPL delivery 禁止且不构成通过。 |
| required capability | 已验证的 Linux/QEMU/TCG + virtio-serial executor；Worker 必须建立实际 PTY、Guest Agent、relay、transcript 路径，而不是 Windows/JVM/mock。 |
| model / reasoning | GPT-5.6 Sol / High；不可用时不静默降级，停止并由 Orchestrator 重新派发。 |
| branch / worktree | `ticket/26-linux-qemu-rebuild`；本次已重新确认 registered/physical path、HEAD、clean、0/0、untracked/ignored 与 owner。 |
| evidence / retention / cleanup | 默认零 Git 跟踪写入；临时 harness 仅在 `DroidLayer-T26:/tmp`，已在会话结束前删除。交回内容仅为脱敏命令、verdict、次数/耗时、覆盖说明和清理状态。 |
| Host 权限 | 已有 runtime 只可本地运行；不得新安装、下载、修改 Host 或 external runtime。发现需要 kernel、rootfs、firmware、包或 Host I/O 时停止。 |
| 六项并行核对 | 依赖、module ownership、shared test、shared contract、branch、worktree 均为 PASS：本 scope 零产品写入、零仓库测试写入、只读 contract、唯一 branch/worktree、无 active Worker。 |
| 停止条件 | 红色 loop 已达成；在独立 implementation + TDD dispatch 前，任何产品/Git 写入、额外下载或安装、Host 写入、frozen WIP、token/credential、ADR/spec 变更、CI/设备工作，或扩大 scope 均停止。 |

## 7. terminal relay diagnosis 结果（不启动实现）

- 目标：在 Linux/QEMU 环境建立可无人值守的 terminal relay 红色反馈环；已完成。
- 要验证的路径：PTY 首字节 → Guest Agent → virtio-serial → host relay → terminal transcript。
- 合格标准：已实际运行一个 agent-runnable、3/3 可复现的命令；它准确捕获实际 `/bin/sh` 启动后
  sentinel 未抵达 relay/transcript 的症状，并在后续修复后可转绿。
- 禁止替代：Windows JVM/mock 不能冒充 QEMU 复现；不得盲改 Guest Agent、增加重试掩盖问题、
  或混入 frozen delivery 草案。
- freshness、批准输入、owner 明确的 Ticket 26 worktree 与 Linux/QEMU executor 均已实际验证；
  Phase 1 已止于红色基线，不得在本 session 进入假设、instrumentation、fix 或 implementation。

## 8. GPL 交付状态

1. 未来主公开地址：https://github.com/Nyzeep/DroidLayer；
2. Release Maintainer 与 retention owner：Nyzeep；
3. GitHub Releases 为主公开交付，独立 GitLab mirror 为未创建的恢复占位，百度网盘为私有
   灾备；每次适用 object-code 最后分发后至少保留三年；
4. 任何公开可见性、镜像、tag、Release 或源码包均未实际建立或验证，本 packet 不授权创建。

## 9. 数据、Git 与架构边界

- 不读取或展示原始 .git、reflog、凭据、token、完整 CI 日志、设备数据、实例数据或 ticket-26-follow-up WIP。
- 不运行 build、CI、ADB、设备操作，不下载 artifact；Phase 1 只允许已安装 QEMU 与现有 WSL
  runtime 的本地诊断命令。
- 不修改 ADR、spec、CONTEXT、产品代码、QEMU delivery 草案或下游 Ticket。
- 本次状态恢复是治理校正，不改变产品契约、Guest Bridge 命令或 Exposure Whitelist；Architecture
  Change Policy 未触发。

报告人：Codex Orchestrator ｜ 日期：2026-09-21 ｜ 耗时：未独立计时

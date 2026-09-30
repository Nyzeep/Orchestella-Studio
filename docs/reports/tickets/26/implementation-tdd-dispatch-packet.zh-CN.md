# Ticket 26 正式 Implementation + TDD Dispatch Packet

> 状态：**active implementation dispatch**（2026-09-21）。
>
> 本文件是本次正式实施的唯一 dispatch；它不替代、复用或修改
> `dispatch-packet.zh-CN.md` 中已经完成的 Phase 1 Linux/QEMU 诊断。Phase 1 的 RED
> 结论仅作为本 dispatch 的已验证输入。

- 票号/标题：26 — 第一垂直切片：创建 Alpine 实例并进入 shell（QEMU 档）
- 执行角色：当前 Codex Orchestrator 在用户指定的既有 Ticket 26 branch/worktree 内串行实施；不创建 implementation Worker。
- 当前 branch / worktree / HEAD：`ticket/26-linux-qemu-rebuild` / `D:\Development project(开发项目)\DroidLayer开发目录\ticket-26-linux-qemu-rebuild` / `e0fc8b97ab6c8ba434fc8fcfa16ed1a87da1c12e`
- 基线：`main` / `8859234a7cd922d4a99385874d1638f31e20c518`；接管 freshness 已重新通过（registered path、branch、HEAD、merge-base、upstream、dirty、untracked、ignored、ACL owner）。当前无 staged 或 ignored 生成物；本 dispatch 当前预期并实际拥有的未提交范围为 `app/**`、`settings.gradle.kts`、scope 允许的 `guest-assets/` second-stage/bridge/Provisioning 文件与 smoke/process tests、`runtime/{contract,distro,instance,capability,engine-qemu}/**`、`tools/qemu-android/**`、本票两份报告及 Gradle incident report。该范围逐项落在 §2 ownership 内，未触及 AVF、Ticket 25 baseline、ADR/spec/CONTEXT/tracker 或历史 Ticket 26 worktree；merge-base 仍与基线一致。其他 Ticket 26 worktree 仅核对注册元数据，未读取、采用或修改其内容。
- 并行：B0 串行。依赖、module ownership、共享 `tests/contract`、契约文件、branch 与 worktree 六项均不允许并行 implementation。

## 1. 路由、风险与 workflow

- Workflow：`implement + tdd`；每次仅一条 RED → 最小实现 → GREEN → 相关回归的垂直切片。
- Risk：初始为 Risk 2–3（跨 runtime、guest、QEMU adapter、bridge 的集成与验证成本）。
- 现有执行模型：当前 Codex/GPT-5 session，按已配置 reasoning 执行；不得因本文件自行切换模型或降低验证要求。
- 后续独立 review：实施结束后运行 `code-review` 的 Standards 与 Spec 两轴独立审查；reviewer 必须自行读取 Ticket 26、scope contract、ADR、diff 和真实验证结果。
- 升级门：一旦需要改变已接受 ADR/spec/CONTEXT、Full Linux 策略中立契约、bridge command set、暴露面白名单、生命周期语义、QEMU APK-contained 原则或依赖图，立即停止相关工作并走 Architecture Change Policy；不得用实现绕过。

## 2. Ownership

### In scope

- `runtime/contract`、`runtime/instance`、`runtime/distro`、`runtime/capability`、`runtime/engine-qemu`、`bridge/`、`ui/`、`terminal/` 与 `app/` 中为本票最小切片必需的 source/test/wiring；
- `tools/qemu-android/`、`.github/workflows/qemu-android.yml` 中本票供应链 gate 所必需的内容；
- scope contract §2.2 列明的 `guest-assets/` second-stage / bridge / 正常二次启动窄例外；
- 本文件、`docs/reports/tickets/26/implementation-report.zh-CN.md` 与本票必要的小型脱敏 evidence。

### Out of scope / 不可修改

- `runtime/engine-avf`；Ticket 27–37；Ticket 25 kernel/capability、Alpine tarball pin 或 reproducibility baseline；
- 已接受 ADR、spec、`CONTEXT.md`、tracker 状态/依赖图、暴露面白名单、桥接命令集；
- 当前 branch 上已完成的 Phase 1 `dispatch-packet.zh-CN.md`、Phase 1 orchestration report，以及任何历史 Ticket 26 worktree/source/log/artifact/WIP；
- Root、Registry、PATH、service、driver、防火墙、OS virtualization setting、未披露的 C: 或 user-level tool write。

## 3. 已确认测试 seam 与首个切片

测试 seam 已由用户明确确认：**Full Linux Contract 的 terminal / bridge 外部可观察行为**。测试不得断言 QMP、virtio-serial 或其他 QEMU 私有类型；QEMU 只能实现该契约的 adapter。

首个切片：

1. 以真实 Linux/QEMU/TCG 复现并固化 RED：实际 `/bin/sh`、Guest Agent `READY`、virtio-serial、host `socat` PTY relay 均发生，但 terminal sentinel 不进入 transcript。sentinel 与 token 使用合成值，日志只保留脱敏 verdict。
2. 只实现使策略中立 bridge terminal input/output 到达该 transcript 的最小纵切片；不提前实现 power、network、share、AVF 或新 bridge command。
3. GREEN 后立即运行该真实 loop 与相关契约/JVM/guest 回归，再选择下一条 acceptance-criterion 切片。

Phase 1 已验证该 RED 为 3/3；它不是 Android APK、CI、GPL gate 或 arm64 真机通过证据。

## 4. 最小 I/O、网络与 artifact 边界

| 类别 | 允许的最小范围 | 写入 / 保留 / 清理责任 |
| --- | --- | --- |
| Repo 读取 | 本 worktree 中本票 owned source/test、Ticket 26、scope contract、必要 ADR/policy 与已验证 Phase 1 脱敏结论 | 只为本票；不读取历史 Ticket 26 worktree、其 source/log/artifact/WIP，亦不读取 `.git` 内容作开发输入。 |
| 指定外部输入 | 唯一 canonical Ticket 25 asset root：`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\artifacts\25\ticket25-reproducible-qemu-2026-09-21-main-8859234a7cd922d4`；只读，先解析 `SHA256SUMS`，再严格核验 `Image` 与 `initramfs.cpio.gz` | 不复制、不纳入 Git、不作为 production tracked input；仅在真实 integration run 时读取。该目录中的完整日志/结果不是本票输入。 |
| Worktree 写入 | 上述 in-scope production/test 文件，以及 `docs/reports/tickets/26/`、必要的 `artifacts/26/` 小型脱敏证据 | source-controlled 内容随本票保留；实施者在每次变更后自查 ownership。 |
| 既有 Linux/QEMU runtime | 仅既有 `DroidLayer-T26` 内的临时 `/tmp/droidlayer-t26-implementation-*` harness/relay 文件 | 每次 run 由 harness trap 清理；只保留命令、hash、verdict、耗时摘要，不保留完整 transcript 或 guest data。 |
| 临时 JVM contract test output | `D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\temp\ticket-26-runtime-contract-jvm-20260921` 中的 `javac` class files 与明确命名的 synthetic manifest fixture 子目录 | 无网络、无新增依赖；fixture `runtime-artifact-manifest-packaged-asset-20260921` 只含合成 QEMU/kernel/initramfs 字节，未含真实 artifact、token 或设备数据。一次按精确已验证路径的清理被本机执行策略拒绝，故当前保留为可识别的 ticket 临时残留；不得扩大目录或混入其它产物，完成前继续记录并在允许的 cleanup channel 中清理。source/test 仍在 worktree 的 `runtime/contract/`。 |
| Gradle test output / project cache | `D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\temp\ticket-26-guest-assets-gradle-20260921` 与 `...\ticket-26-gradle-project-caches-20260921` | 离线测试首次使用默认路径后，实施者发现其 generated output/cache 落入 worktree 的 ignored 目录；已在无覆盖、逐项 preflight 后迁出 29 个 output files 和 22 个 cache files，worktree 无此类残留。外部 temporary output 保留到当前实施/独立 review结束后按可用 cleanup channel 清理。 |
| 外部 build/device output（尚未创建） | `D:\Development project(开发项目)\DroidLayer\artifacts\26\` 下按用途/ticket 分目录 | 仅在获本 dispatch 之外所需的网络/build/device 写入批准后创建；保留至独立 review 完成，之后由 Orchestrator 按 artifact/worktree audit 决定保留或清理，绝不自动删除。 |
| 网络 | 已获批准并已完成的 APT 索引刷新：`https://snapshot.ubuntu.com/ubuntu/20251017T000000Z/`；以及只读 metadata lookup：同一官方服务下的候选 dated snapshot `dists/{noble,noble-updates,noble-security}/main/binary-amd64/Packages.gz`。 | 索引刷新只写入 WSL `DroidLayer-T26` 的 package index；metadata lookup 只在内存中解压/读取，未下载 `.deb`、未安装 package、未写入 repo 或 external artifact root。其余 QEMU archive/signature、NDK、Alpine tarball 或任何下载前，仍必须单独披露精确 endpoint、输出路径、校验、失败即停和清理计划，等待用户批准。 |
| 禁止外发 | 工作树、Git 历史、diff、token、完整日志、路径清单、设备数据与任何 artifact 均不得上传或发送到第三方。 | 发现疑似越界外发时立即停止并按 DEFENSIVE_CONSTRAINTS 事件流程处理。 |

## 5. 验证顺序与真实性纪律

1. 真实 Linux/QEMU terminal relay RED/GREEN（仅验证 Linux/QEMU adapter 路径）；
2. Full Linux Contract 与 JVM 行为测试（不能替代真机）；
3. guest provisioning / normal shutdown / same Root Volume restart / data retention 的真实 smoke；
4. QEMU source-lock、provenance、two-clean-build、ELF/closure、16 KB、APK layout/signature/tamper、GPL gates；
5. arm64 真机安装到 shell、bad tarball、bridge positive/negative token、state/UI reconstruction、emulator hint 的脱敏验收。

每项在实施报告中只标记为“已验证”“失败”“未运行”或“待真机”；不把 JVM、Linux/QEMU 或静态检查描述成 Android/真机通过。

## 6. 当前无需新增授权与可能的后续审批

本 dispatch 已授权的行动仅为：当前 worktree 内的实现/测试/报告、指定 Ticket 25 input 的只读再核验，以及既有 Linux/QEMU runtime 中可自动清理的最小 RED/GREEN harness。不得安装、下载、启用新依赖/MCP/plugin/系统组件/WSL package/Android SDK/NDK/Docker/Podman 或产生 build/APK/device output。

### 2026-09-21 已获准但未形成编译器的安装尝试

用户明确批准了 `apt-get update` 与 `apt-get install --yes --no-install-recommends build-essential`，以及既有 QEMU runtime 的 `setpriv --reuid=1000 --regid=1000 --clear-groups` 降权运行。实际结果如下：

- APT 索引刷新成功；唯一访问的 endpoint 为上表所列 Ubuntu snapshot。
- `build-essential` 在依赖解析阶段以 exit 100 失败，未安装任何 compiler 或 package；`dpkg --audit` 为空，`gcc`、`cc` 和 `build-essential` 均不存在。
- 不自洽的已安装/候选版本包括 `gcc-14-base`（`14.2.0-4ubuntu2~24.04.1` / `14.2.0-4ubuntu2~24.04`）、`libc6`（`2.39-0ubuntu8.9` / `2.39-0ubuntu8.6`）与 `libbz2-1.0`（`1.0.8-5.1ubuntu0.1` / `1.0.8-5.1build0.1`）。
- 没有修改 APT source、没有降级或强制修复、没有新建 WSL/runtime；已发生的外部写入仅是已获准的 APT package index 刷新。

因此，首个 guest/JVM RED 尚未执行；下一次操作必须先获得一个可审计的 AArch64 static 编译路径的单独授权。推荐先做只读的 Ubuntu snapshot metadata lookup，以确定能与现有 rootfs 版本一致的固定 snapshot；确定实际 endpoint、候选包与影响清单后，再提交单独的安装 disclosure。

### 2026-09-21 已获准的只读 snapshot metadata lookup

已完成的 metadata lookup 未改变 APT source 或 package database。结果表明：

- 当前源固定在 `20251017T000000Z`，其候选分别是 `gcc-14-base 14.2.0-4ubuntu2~24.04`、`libc6 2.39-0ubuntu8.6`、`libbz2-1.0 1.0.8-5.1build0.1`，故与运行时已安装版本不自洽。
- `https://snapshot.ubuntu.com/ubuntu/20260921T000000Z/` 的 `noble-updates` 同时提供已安装的 `gcc-14-base 14.2.0-4ubuntu2~24.04.1`、`libc6 2.39-0ubuntu8.9` 与 `libbz2-1.0 1.0.8-5.1ubuntu0.1`；这是目前唯一经实际 metadata 比对确认的一致固定快照。
- 同一快照的官方 metadata 提供最小 AArch64 GNU cross toolchain 的直接候选：`gcc-aarch64-linux-gnu 4:13.2.0-7ubuntu1`、`gcc-13-aarch64-linux-gnu 13.2.0-23ubuntu4cross1`、`binutils-aarch64-linux-gnu 2.42-4ubuntu2.10` 与 `libc6-dev-arm64-cross 2.39-0ubuntu8cross1`。`gcc-13-aarch64-linux-gnu` 对 target C library development headers 仅为 Recommend，因此在 `--no-install-recommends` 下必须显式请求 `libc6-dev-arm64-cross` 才能支持 static link。

这只是安装前事实调查；尚未修改 source、下载 package 或运行 compiler。下一步需要用户就 source switch、签名校验后的 index refresh 及最小 host/cross compiler 安装作单独批准。

### 2026-09-21 已获准的最小 AArch64 static 编译链安装

用户随后明确批准了临时 source switch、签名校验的 index refresh 与最小交叉工具链安装。实际执行严格遵循以下 fail-closed 顺序：

1. 将原 `droidlayer.sources` 备份到 `DroidLayer-T26:/tmp`，备份与原件 SHA-256 均为 `9d32da3e…826d1289`；临时将唯一 URI 切换为 `https://snapshot.ubuntu.com/ubuntu/20260921T000000Z/`。
2. `apt-get update` 成功；`apt-get --simulate install --yes --no-install-recommends gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu libc6-dev-arm64-cross` 解析为 27 个新增包、0 upgraded、0 downgrade、0 removed。
3. 实际安装上述请求与其闭合依赖：46.2 MB archive、171 MB WSL disk 增量，仍为 27 new / 0 upgraded / 0 removed；没有安装 host `build-essential`，没有降级或移除现有 runtime package。
4. 安装后 `dpkg --audit` 为空；`aarch64-linux-gnu-gcc` 为 Ubuntu GCC 13.3.0；请求包版本为 `gcc-aarch64-linux-gnu 4:13.2.0-7ubuntu1`、`binutils-aarch64-linux-gnu 2.42-4ubuntu2.10`、`libc6-dev-arm64-cross 2.39-0ubuntu8cross1`。
5. 原 source 已恢复，并与备份逐字节一致、SHA-256 恢复为 `9d32da3e…826d1289`。临时 source backup、20260921 APT list 与本次 27 个 `.deb` archive cache 均已逐项删除；不卸载获准保留的编译链。 
6. 在新建且随后删除的 `DroidLayer-T26:/tmp/droidlayer-t26-cross-verification` 内，当前 `guest-agent.c` 用 `-static -std=c11 -Wall -Wextra -Werror` 编译成功。`file` 与 `aarch64-linux-gnu-readelf` 确认产物是 AArch64 ELF64、无 dynamic section；临时 binary 和目录均已清理。

此验证仅证明 Linux-side 静态 ARM64 guest-agent 编译路径可用；它不是 Android/NDK、APK、QEMU supply-chain、CI 或 arm64 真机通过。

### 2026-09-21 首个 source-controlled terminal relay RED harness

为把 Phase 1 的事实转换为本次可重复的 implementation/TDD 输入，新增
`guest-assets/smoke/qemu-terminal-relay-smoke.sh`。它是 QEMU adapter 的测试 harness，而不是
Full Linux Contract 的公共接口：

- 仅接受调用方显式提供的、已批准 Ticket 25 asset root；先执行该目录的 `SHA256SUMS` 校验，绝不下载、复制、回退到其它 asset 或采用任何历史 Ticket 26 worktree 输入；
- 仅在 `DroidLayer-T26:/tmp/droidlayer-t26-implementation-terminal-relay.*` 创建 0700 临时目录，使用合成 token/sentinel，实际通过 QEMU virtio-serial 与 host `socat` PTY relay 观察 transcript；QEMU 与 relay 均由 `setpriv --reuid=1000 --regid=1000 --clear-groups` 降权启动；
- QEMU adapter 内部使用已观察到的 `/dev/vport0p1`，但该细节不进入契约或产品接口；harness 同时要求 guest `/bin/sh` 启动标记、`DROIDLAYER/READY 1` 和 terminal sentinel，输出只含脱敏 verdict；
- 任意 exit、超时或 transcript 不完整均 fail-closed，trap 清理所有 socket、PTY、transcript、日志、临时 initramfs 与 guest binary；不保留 token、完整 transcript 或 guest data。

已验证 `bash -n` 通过、`git diff --check` 通过；在指定 asset 缺失时，harness 以预期 exit 66 停止且没有 `/tmp/droidlayer-t26-implementation-terminal-relay.*` 残留。当前 WSL 没有 `shellcheck`，该静态检查未运行，未为其安装依赖。

### 2026-09-21 canonical asset migration and integrity preflight

历史记录中的旧位置不可见 blocker 已由用户批准的目录迁移解决；旧位置只保留为迁移事实，
本次不读取、不复制、不替代，也不采用任何历史 Ticket 26 worktree 输入。当前唯一 canonical
asset root 为：
`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\artifacts\25\ticket25-reproducible-qemu-2026-09-21-main-8859234a7cd922d4`。

接管预检确认该目录由当前用户可读，`SHA256SUMS`、`Image`、`initramfs.cpio.gz` 均为直接的
regular file；解析清单后 12/12 项严格 SHA-256 校验通过，其中 `Image` 为
`d56677f4…435dc5`、`initramfs.cpio.gz` 为 `92766a6f…d7ec`。未列入清单的 qemu smoke
日志/结果不作为 runtime input。真实 Linux/QEMU RED 在此预检之后运行；若运行失败仍必须
保留 fail-closed 结果，且不得把历史 Phase 1 结论冒充本次结果。

后续如需超出当前 worktree 与该只读 asset root 的写入，仍需先提交完整 disclosure：Operation /
Path / Reason / Can it be avoided? / What will be created? / What will be modified? / Overwrite? /
Cleanup? / Risk，并等待明确批准。

### 2026-09-21 implementation milestones and post-migration revalidation

- **真实 terminal RED → GREEN：** 诊断阶段的已知 RED 保持为 3/3（`READY=1`、sentinel=0）；
  本 dispatch 的最小修复将握手读取改为不预取 terminal bytes 的 byte-at-a-time `read()`，在
  flush `READY` 后交给实际 `/bin/sh`。使用当前 canonical asset root 的 Green 首批 3/3，
  路径迁移后的重新核验再为 3/3；每次 verdict 均为
  `init=1 virtio=1 stream=1 shell_launch=1 shell=1 agent_exit=0 ready=1 sentinel=1`，临时
  Linux/QEMU relay 目录无残留。该证据仅适用于 Linux/QEMU adapter，不是 Android、APK、CI、
  supply-chain 或真机通过。
- **桥接 v1 contract：** 新增策略中立 `runtime/contract` handshake seam 的 RED→GREEN：有效
  token/version 协商 v1，错误 token 拒绝，排除 v1 的 range 拒绝，malformed/non-canonical token
  拒绝。模块不含 QEMU/QMP/virtio/vsock/Android 类型。
- **guest parity：** 新增 `-1..1` invalid range RED；Guest Agent 现于 token comparison 前拒绝
  negative 或 inverted version range。目标 process behavior 已被固化；更新后的完整
  guest-assets Gradle suite 没有当前有效 verdict（见下文 Wrapper incident），不得用早期缓存
  suite 代替。
- **bridge control / terminal：** 随后各自从 RED 到 GREEN：`BridgeControl` 以策略中立 terminal
  adapter seam 支持已批准的 `ping` / `resize`，非法尺寸与 unavailable terminal 均 fail closed；
  `QemuBridgeAdapter` 只转接连接好的 byte stream。真实 QEMU 双通道 harness 的 valid 模式 3/3
  观察到 `READY/PONG/OK resize/stty 24 80/sentinel`，错误 token 模式 3/3 仅观察到
  `unauthorized`（无 `READY/PONG/resize`），均以 UID/GID 1000 运行并清理临时目录。这里的
  QEMU socket/virtio/tty 细节只在 adapter/harness 中，未进入 Full Linux Contract。
- **input fail-closed：** 两条 QEMU harness 现共享严格 Ticket 25 asset verifier；缺失 root
  均 exit 66 且不创建运行临时目录，canonical input 正向回归通过。
- **Provisioning / same Root Volume（真实 Linux/QEMU，合成 rootfs fixture）：** lifecycle harness
  已在既有实施证据中 3/3 GREEN，路径迁移后又实际复核一次：首次 checksum 通过后 Provisioning
  `switch_root`，guest 正常停止，再次启动复用同一 Root Volume 并读到 persistent sentinel；
  verdict 为 `DROIDLAYER_QEMU_PROVISIONING_RESTART_OK first_provisioning=1 second_boot=1 persisted=1`。
  故意错误的合成 tarball checksum 也在格式化前拒绝，verdict 为
  `DROIDLAYER_QEMU_PROVISIONING_CHECKSUM_REJECTION_OK rootfs_checksum=0 provisioning=0 root_volume_formatted=0`。
  这是 Linux/QEMU 上的合成 fixture 证据，不是官方 Alpine tarball、Android 或真机验收。
- **Provisioned Root Volume bridge / terminal TDD（真实 Linux/QEMU，合成 rootfs fixture）：** 新的
  source-controlled harness 首先真实 RED：初次 `switch_root` 后 control socket 没有 `READY`。最小
  implementation 只在 adapter 提供完整、已验证的两个 character-device pair 时映射 guest stable alias
  `/dev/droidlayer-bridge` / `/dev/droidlayer-terminal`，在 rootfs config 写入它们并启用 OpenRC service；
  QEMU-specific source device 名只在 harness kernel args，未进入 Full Linux Contract。Green 为 3/3，
  每次观察 provisioning、READY、PONG、resize `24x80`、terminal sentinel 和 agent exit 0；缺失 asset
  是 exit 66 且无 temp directory，之后 restart/checksum negative regression 均 Green。该证据是
  Linux/QEMU synthetic fixture，不是 Android、official Alpine 或真机。
- **共享 bridge/terminal device 的 Provisioning 拒绝（真实 Linux/QEMU，合成 rootfs fixture）：**
  harness 先将两个 adapter 参数故意指向同一 character device，真实 RED 为 `rejected=0` 且 Root
  Volume 已成 `ext4`，证明原实现会在安全配置错误时继续格式化。最小修复只在 initramfs 的既有
  device-pair validation 中拒绝相同 path，位于任何 root-volume mount 或 `mkfs` 之前；GREEN 输出
  `DROIDLAYER_QEMU_PROVISIONED_BRIDGE_SHARED_DEVICE_REJECTION_OK rejected=1 root_volume_formatted=0`。
  随后 valid provisioned bridge/terminal、restart、checksum rejection、terminal relay、valid bridge
  control 和错误 token 回归均 exit 0，且四类 harness 临时目录检查为零残留。此项只证明 Linux/QEMU
  synthetic-fixture 的 fail-closed 行为，不是 Android、official Alpine 或真机验收。
- **JVM 基线：** JDK 17 compile/run 的 bridge、adapter、DistroManifest、normal lifecycle、
  capability tests 已按真实结果通过；均为 JVM 结果，非 Android/真机。
- **QEMU source-only gates：** strict lock tamper/duplicate-key rejection、explicit
  `extractNativeLibs` / `nativeLibraryDir` policy check、runtime-artifact manifest tamper rejection 均
  在本地 synthetic fixture 通过；它们不是 Android/NDK build、APK inspection/signing、GPL delivery、
  CI 或 device gate。
- **runtime-artifact manifest completeness TDD：** 新增 parser-level RED 证明旧实现接受仅含
  QEMU 的半份 manifest；最小 GREEN 要求 kernel、initramfs、QEMU input/GPL/NOTICE records 与固定
  native QEMU entry 全部列入，缺失时稳定拒绝 `runtime-artifact-manifest-incomplete`。完整合成
  manifest 的正向 parse 与其余六个无 fixture JVM contract tests 已在 JDK 17
  `-Xlint:all -Werror` 下复跑 Green。该测试只验证 public manifest parse behavior，未读取或执行
  Android artifact；`runtime:engine-qemu` 的 future Gradle `check` 已接入对应 test tasks。
- **packaged kernel/initramfs integrity TDD：** 新增编译 RED 证明 runtime 缺少可在复制、解压或
  launch 前调用的 packaged-asset verifier；最小 GREEN 以 caller-owned stream 验证 manifest entry，
  正确合成 `Image` / `initramfs.cpio.gz` 通过，篡改 kernel 以
  `runtime-artifact-checksum-mismatch` 拒绝。相应 test 使用 dispatch 已登记的明确 external synthetic
  fixture 子目录；不会下载、复制真实 asset 或执行 QEMU/Android binary。
- **lock fixed-digest TDD：** local lock-test RED 显示旧 verifier 接受任意格式正确的 QEMU/NDK
  archive hash；最小修复将 policy 中的两个 SHA-256 固定为 exact values，Green test 同时拒绝 altered
  target、duplicate key 与两种 altered digest。此为 source-only policy gate，未下载或验证远端 bytes。
- **source integrity：** `git diff --check`、相关 shell `bash -n` / `sh -n` 已通过；静态审阅移除
  `MainActivity.kt` 的一个多余闭合大括号。`kotlinc` 当前不可用，离线 Gradle 又停在未缓存 Android
  Gradle Plugin，因此没有 Kotlin/Android compilation verdict，也没有用文本检查替代它。
- **本轮回归：** shared bridge/terminal device negative slice 的真实 RED 后最小 initramfs
  validation GREEN；随后 valid provisioned bridge/terminal、restart、checksum rejection、terminal relay、
  bridge valid 与 token rejection 皆 exit 0。相关 shell syntax、三个 source-only QEMU policy gate 与
  `git diff --check` 也 exit 0；四类 QEMU harness 和两类 source-gate harness 的 temporary prefix
  均无残留。JDK 17 `--release 17 -Xlint:all -Werror` 的九个纯 JVM test 在新的 approved external
  temporary subdirectory 中全部 exit 0。任何这些结果均非 Android、APK、CI 或真机证据。
- **仍未完成：** Android production wiring/UI terminal 的实际构建与安装、QEMU Android source/NDK build、
  two-clean-build、ELF/closure/16 KB/APK/GPL/CI 与
  arm64 device gates 均未声称完成，仍按一条纵切片逐项推进。

### 2026-09-22 官方 Alpine Provisioning RED → GREEN

用户一次性授权的官方输入仅为固定 HTTPS endpoint
`https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz`
及 external artifact root 下的已存在 archive；本轮没有访问网络、重下载、覆盖、解压到持久目录或
使用 `.partial`。锁定 hash 的批准事实源是 `guest-assets/inputs/inputs.lock`：Alpine 3.24.1、aarch64、
文件名和 `f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259`。本地 preflight
确认 archive 为直接 regular file、大小 `4023732` bytes、`Get-FileHash -Algorithm SHA256` 精确匹配，
且 `.partial` 不存在；此结果只确认已下载输入的完整性，不能替代发布供应链 gate。

- **真实 RED：** 以该 archive、唯一 Ticket 25 canonical `Image`/`initramfs.cpio.gz`、既有降权
  `qemu-system-aarch64` 和 `socat` PTY relay 运行 source-controlled official harness，实际得到
  `official Alpine Guest Agent did not return READY` 与
  `TICKET26_OFFICIAL_ALPINE_DIAGNOSTIC status=1 provisioning=1 provisioning_error=none agent_service=0
  bridge_stream_error=0 terminal_device_error=0 bridge_open_error=0 openrc_default_complete=0 control_bytes=0
  terminal_bytes=16856`。因此 Provisioning 已完成，但 guest Agent 未由 archive 的 init flavor 调度。
- **最小实现：** 只在完整 adapter device pair 已存在时，保留现有 OpenRC service/config path；对已锁定
  Alpine 3.24.1 的严格形态（`/sbin/openrc` 缺失、BusyBox、`/sbin/init` symlink、
  `usr/lib/os-release` 的 `ID=alpine`/`VERSION_ID=3.24.1`）额外写入 stable-alias Guest Agent launcher 和
  `/etc/inittab` 的 generic console `/bin/sh`。rootfs 配置没有 QEMU device name、QMP 或策略类型；
  unknown flavor 不会采用此 fallback。
- **真实 GREEN：** 同一 harness 首次及串行两次重复均 exit 0（3/3），每次输出
  `DROIDLAYER_QEMU_OFFICIAL_ALPINE_PROVISIONING_OK provisioning=1 bridge=1 terminal=1 resize=24x80 sentinel=1`。
  QEMU 与 relay 均继续以 UID/GID 1000 运行，三次 `/tmp/droidlayer-t26-implementation-official-alpine.*`
  均由 harness trap 清理。
- **官方 checksum 拒绝：** harness 的 `checksum-rejection` mode 仍先在 host 验证 archive 的真实固定
  SHA-256；仅在 auto-cleaned test initramfs 中写入故意错误的 expected hash。真实 Linux/QEMU 结果为
  `DROIDLAYER_QEMU_OFFICIAL_ALPINE_CHECKSUM_REJECTION_OK rootfs_checksum=0 provisioning=0
  root_volume_formatted=0`，随后正常 official Provisioning 再次 exit 0 且无 temporary directory 残留。
  archive 本身未被改写。
- **官方正常 restart / 数据保留：** 新的 source-controlled restart harness 用同一 verified archive
  首次 Provisioning 后经 terminal 写入 `persisted=1`、正常 `poweroff`；同一 Root Volume 第二次启动后
  观察到 restart marker、重新完成 bridge/terminal handshake，并经 terminal 读回数据。首轮和重复各 exit 0：
  `DROIDLAYER_QEMU_OFFICIAL_ALPINE_RESTART_OK first_provisioning=1 second_boot=1 persisted=1 bridge=1 terminal=1`。
  它只固化现有正常 restart 路径的外部行为，没有为了通过测试修改 product logic。
- **官方 bridge 负例：** `unauthorized` mode 以错误合成 token 得到
  `DROIDLAYER_QEMU_OFFICIAL_ALPINE_TOKEN_REJECTION_OK unauthorized=1 ready=0 provisioning=1`；
  `unsupported-version` mode 以合法 token 的 `2..3` range 得到
  `DROIDLAYER_QEMU_OFFICIAL_ALPINE_VERSION_REJECTION_OK unsupported_version=1 ready=0 provisioning=1`。
  两者均在 Provisioning 后、normal terminal `poweroff` 前观察，随后 normal v1 Provisioning 再次 GREEN。
- **official harness input negative：** 未提供 `TICKET26_ASSET_ROOT` 时 exit 64、输出稳定的缺失 approved
  Ticket 25 input diagnostic，并在创建 QEMU/temp 目录前停止：
  `TICKET26_OFFICIAL_ALPINE_MISSING_ASSET_REJECTION_OK exit=64 temp_created=0`。
- **最终 official 全模式回归：** 在同一串行 run 中，`provisioning`、`checksum-rejection`、
  `unauthorized`、`unsupported-version` 和 restart 均 exit 0，依次得到上述四类 verdict 与
  `DROIDLAYER_QEMU_OFFICIAL_ALPINE_RESTART_OK first_provisioning=1 second_boot=1 persisted=1 bridge=1 terminal=1`；
  `bash -n`（两个 official harness 与 `test-local.sh`）、`sh -n`（initramfs init）以及两个 `/tmp`
  official prefix 的 zero-residual check 同时通过。
- **相关回归：** terminal relay、有效 bridge、错误 token 拒绝、synthetic Provisioning/restart/data
  retention、checksum pre-format rejection、有效 provisioned bridge/terminal 与 shared-device pre-format
  rejection 各在当前 init 下 exit 0；对应 temporary prefixes 均无残留。`git diff --check`、`sh -n init`、
  official/restart harness 与更新后的 `test-local.sh` 的 `bash -n` 均通过；`shellcheck`、Gradle suite、Android build
  仍未运行。

这是 Linux/QEMU/TCG 的 official Alpine integration evidence，不是 Android APK、QEMU Android supply-chain、
CI 或 arm64 真机验收；未触及 ADR、Full Linux Contract、bridge command set、exposure whitelist 或 lifecycle
语义，未触发 Architecture Change Policy，也不需要新授权。

### 2026-09-22 非网络 JVM / source-gate 回归

在 dispatch 已登记、获准的 external temporary root
`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\temp\ticket-26-runtime-contract-jvm-20260921`
下，新建且不覆盖 `jvm-regression-official-alpine-rerun-20260922`。JDK 17 以
`javac --release 17 -encoding UTF-8 -Xlint:all -Werror` 编译全部 runtime source/test，并实际运行九项
bridge handshake/control、Full Linux Contract、QEMU adapter、manifest schema/asset verification、DistroManifest、
normal lifecycle 和 capability tests，全部 exit 0。它们是 JVM 证据，非 Android evidence。

本轮前一个新目录的创建命令错误使用了本机不支持的 `New-Item -LiteralPath` 参数；`javac` 随后创建了
`jvm-regression-official-alpine-20260922`，其中前六项 test 通过而 lifecycle 因遗漏其 required fixture arg
失败。该次不计为 suite verdict、不覆盖、不删除，作为可识别的 temporary residual 保留至 implementation/review
后的 permitted cleanup；rerun 使用新目录并提供全部 fixture arg 后才形成上述 9/9 Green verdict。

在既有 WSL runtime 运行的 source-only gates 也全部 exit 0：
`DROIDLAYER_QEMU_JNI_LOCK_TEST_OK altered_target_rejected=1 duplicate_key_rejected=1 fixed_digest_rejected=2`、
`DROIDLAYER_QEMU_APK_PACKAGING_POLICY_OK extracted_native_libs=1 native_library_dir=1 writable_exec_paths=0` 与
`DROIDLAYER_RUNTIME_ARTIFACT_MANIFEST_TEST_OK tamper_rejected=1`；两个 gate 的 `/tmp` prefixes 均由 trap 清理。
这些只证明 lock/policy/synthetic manifest 行为，并不是 QEMU Android build、APK、signature、GPL delivery、CI
或 device gate。

### 2026-09-22 per-Instance Bridge token RED → GREEN

ADR-0009/0010 已要求每 Instance 随机 Bridge credential；本条仅在既有 normal-lifecycle metadata seam
补齐该已批准的持久化输入，未增加 bridge command、guest→host exposure、QEMU type 或 lifecycle state。
测试不输出 credential value，且 `Instance` accessor 保持 package-private。

先在本 dispatch 已登记的 external temporary root 下新建、不覆盖
`jvm-red-bridge-token-20260922-b`。JDK 17
`javac --release 17 -encoding UTF-8 -Xlint:all -Werror` 的真实 RED 为 six `bridgeToken()` missing
compiler errors（exit 1），故此前 lifecycle model 确实尚未为 Instance 保存 credential。最小 GREEN 只在
`InstanceLifecycle.Controller.create` 使用 `SecureRandom` 生成 32 bytes，编码为 canonical 64-character
lowercase hexadecimal token；构造/load 同时拒绝 non-canonical metadata，正常 transition 复用同一 token，
而 `Properties` metadata 记录该值。新建、不覆盖的 `jvm-green-bridge-token-20260922-a` compile/test 均 exit 0，
验证 two unique Instance tokens 的 canonical shape 与 reconstructed `FileStore` 的 stable value；test/log 没有打印 token。

随后在新建、不覆盖的 `jvm-regression-bridge-token-20260922-a`，同一 JDK command 编译所有 runtime
source/test，并串行运行 nine bridge/contract/QEMU-adapter/manifest/distro/lifecycle/capability tests，全部 exit 0。
首个 `jvm-red-bridge-token-20260922-a` 因 PowerShell source-array argument construction 在编译前 exit 2，
不构成 RED 或 test verdict；它作为 identifiable temporary residual 保留，未覆盖或删除。上述均为 JVM
evidence；未运行 Android compilation、APK/NDK/supply-chain/CI 或 arm64 device gate。本 slice 不触及
Architecture Change Policy，且不需要新用户授权。

### 2026-09-21 Gradle Wrapper 未获准网络尝试

在隔离 guest-assets Gradle user-home 中重跑更新后的 suite 时，Wrapper 因缺少本地 Gradle 8.13
distribution 尝试访问 `https://services.gradle.org/distributions/gradle-8.13-bin.zip`。该 endpoint
未获本 dispatch 授权，发现后立即停止精确 downloader processes，未重试且没有有效 suite verdict；
本地仅留下 0-byte `.part/.lck` 于 approved Ticket 26 external temp root，未使用或删除。完整最小
脱敏记录见 `docs/reports/orchestration/ticket-26-gradle-wrapper-network-attempt-2026-09-21.zh-CN.md`。
该事件不改变产品 scope、ADR、契约或 host policy；后续 Wrapper bootstrap 必须先有本地 verified
distribution，或按完整 disclosure 获取用户对 endpoint/output/hash/cleanup 的单独批准。

### 2026-09-22 接管后的只读盘点与阻塞清单

本轮遵循 fail-closed 阶段边界，只完成 freshness gate、候选外部输入盘点和待人工输入清单准备；没有新的 RED/GREEN，没有构建、网络访问或代码实现。Ticket 26 继续为 **active implementation**，Android/Gradle 工具链继续为 **blocked**，不得据此建议 `review` 或 `completed`。

- freshness gate 已通过：目标 worktree 为 `D:\\Development project(开发项目)\\DroidLayer开发目录\\ticket-26-linux-qemu-rebuild`，branch 为 `ticket/26-linux-qemu-rebuild`，HEAD 为 `e0fc8b97ab6c8ba434fc8fcfa16ed1a87da1c12e`。
- 当前 HEAD 与 `main` 的 merge-base 为 `8859234a7cd922d4a99385874d1638f31e20c518`，相对 `main` ahead 2 / behind 0，branch 无 upstream。
- dirty/untracked 文件均与本 dispatch 声明的 app、guest-assets 窄例外、`runtime/{contract,distro,instance,capability,engine-qemu}/**`、`tools/qemu-android/**` 和 Ticket 26 报告 ownership 一致；未发现 ownership 外改动或 ignored 生成物。
- 未读取、采用、复制、移动、删除或修改历史 Ticket 26 worktree；候选缓存和 SDK 不因本轮盘点获得批准。

#### 候选输入的禁止采用结论

以下路径只能记录为“候选存在但未批准”，不得使用、复制、移动、删除、覆盖，也不得据此自动生成 verification metadata、lock 或 provenance：

- `D:\\Development project(开发项目)\\DroidLayer开发目录\\DroidLayer_dev\\.toolchains\\android-sdk`：本地可见 `platforms\\android-36\\android.jar` 和 Build-Tools `36.1.0`，但不在批准的 Ticket 26 external artifact root，缺少完整 SDK package provenance、archive/package 完整性清单和可审计 licenses/安装来源记录。
- `D:\\Development project(开发项目)\\DroidLayer开发目录\\DroidLayer_dev\\.toolchains\\gradle-home-ascii`：本地可见部分 AGP `8.13.2`、Kotlin `2.2.21`、JUnit `4.13.2` 与传递模块，但不在批准的 external artifact root，缺少 root Android build 全部传递依赖的批准 verification metadata、lock 或 provenance。
- `D:\\Development project(开发项目)\\DroidLayer开发目录\\DroidLayer_dev\\.toolchains\\gradle-8.13-bin.zip`：只能记录为候选存在，不能采用。用户人工提供且已按锁定 SHA-256 核验的根目录 `D:\\Development project(开发项目)\\DroidLayer开发目录\\gradle-8.13-bin.zip` 本轮也未解压、复制、配置或执行。
- 任意历史 Ticket 26 worktree、无法证明来源的用户级 Gradle cache 或系统目录均不属于本 dispatch 的输入。

#### 待人工提供或明确批准的外部输入

A. **Android SDK**

- canonical external artifact root；当前计划路径 `D:\\Development project(开发项目)\\DroidLayer开发目录\\DroidLayer\\artifacts\\26\\android-offline-build-20260922` 尚不存在，也未由本轮创建或批准。
- SDK 官方来源或官方发行包、可追溯的 package 列表，以及至少 `platforms\\android-36`（含 `android.jar`）和 Build-Tools `36.1.0`。
- 每个 archive/package 的 SHA-256、来源、版本和完整性/provenance 清单。
- `licenses` 或等价的可审计安装来源记录。
- partial、下载中断、校验失败或内容不完整时必须拒绝使用，保持 fail-closed。

B. **Root Android offline dependency closure**

- canonical external artifact root、cache/archive 来源和获取记录。
- AGP `8.13.2`、Kotlin `2.2.21`、JUnit `4.13.2` 及全部传递依赖。
- module / artifact / version 完整清单，以及每个 artifact 的完整 SHA-256 或覆盖 root Android build 全闭包的 Gradle verification metadata/等价锁定证明。
- 必须明确这是 root Android build 的闭包；`guest-assets/gradle/verification-metadata.xml` 不能替代它。
- 缺项、来源不明或 hash 不匹配时不得隐式下载、回退或换用其他 cache，必须 fail-closed。

C. **NDK（仅后续 QEMU Android build）**

- NDK r28c / revision `28.2.13676358`，固定 archive 来源及 acquisition record。
- archive SHA-256：`dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f`。
- 同时按 `tools/qemu-android/qemu-jni.lock` 记录 QEMU signer fingerprint `CEACC9E15534EBABB82D3FA03353C9CEF108B584` 与 immutable builder digest `mcr.microsoft.com/devcontainers/base@sha256:3612b800eaae8513e7dca54f21d2e4eda48eda57426f3746a78677f342ac7ee9`；这些 provenance 层不能互相替代。
- NDK 只服务于后续 QEMU Android build，不阻塞或替代 root Android skeleton build 的 SDK/依赖闭包证明；NDK 缺失时 QEMU Android build 保持 fail-closed。

#### 本轮未运行与下一停止点

本轮未运行 Gradle、`gradlew`、Android/Kotlin 编译、Android unit test、lint、debug APK、NDK build、QEMU Android native build、QEMU Android APK、CI、QEMU Android 供应链 gates、GPL 交付验证或 arm64 Android 真机验证；没有新的 RED/GREEN。完成上述文档记录后在此暂停，等待人工提供或明确批准外部输入。即使输入补齐，也必须先提交单独的离线构建授权申请，列出解压 staging、`GRADLE_USER_HOME`、JDK/SDK/NDK 路径、完整 `--offline` 命令、输出/日志路径、worktree 写入范围、失败清理范围及 fail-closed 行为。

### 2026-09-22 工具链元数据清单一致性审计结论

本轮只对已经结束的官方工具链 metadata discovery 记录做无网络、无写入一致性审计；没有重新查询 endpoint，没有下载 JAR、AAR、ZIP 或 SDK archive，没有解压、安装、构建或修改外部输入。当前原“待批准下载清单”不是可执行或可批准的 manifest，只能作为**非权威发现记录**，不得据此下载。

1. **坐标与条目不闭合。** 已记录为 158 个 selected coordinates、156 条 artifact 审计记录和 153 个成功 binary HEAD 结果，三者没有形成可逐项追溯的闭包。缺少 `org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.2.21` 的完整记录，也缺少 `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` 的完整 binary/metadata 对应记录；`com.android.application:com.android.application.gradle.plugin:8.13.2`、`org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:2.2.21` 和旧的 `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0` 记录是 404 phantom，不是有效 binary。
2. **Coroutine 版本选择未同步。** 初始解析记录含 `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0`，后续 metadata 说明要求选择 `1.9.0`；但 binary manifest、metadata-only 列表及其 hash/大小表没有同步更新。因此 coroutine 相关输入不得下载。
3. **Maven 来源和 URL 错配。** 已记录的 Maven Central 坐标被若干清单行错误改写为 Google Maven URL；Kotlin group path、Jetifier path、artifact path 或 filename 的错误拼接也必须标记 `INVALID/UNKNOWN`。Maven Central 坐标不得改写成 `dl.google.com` 地址；本轮不猜测、不自动修正为新下载地址。
4. **Checksum 分类保持证据原貌。** 审计结果只能记为 70 个有 SHA-256、81 个只有可解析的 SHA-1、2 个 `INVALID/UNKNOWN` checksum 字段。后两条为 `javax.inject:javax.inject:1` 与 `net.sf.kxml:kxml2:2.3.0` 的 SHA-1 sidecar，它们混入了 `/home/maven/repository-staging/...` 本地路径，不能当作纯 hash。不得把 SHA-1 当作 SHA-256。
5. **Android SDK archive 仍未批准。** `platform-36_r02.zip` 与 `build-tools_r36.1_windows.zip` 的官方 HEAD 都是 200、无重定向；当前只有 Android repository metadata 提供的 SHA-1，`.sha256` sidecar 为 404。两者没有足够证据被标记为已批准输入。
6. **Policy 结论。** `docs/agent-handoff/QEMU_JNI_SUPPLY_CHAIN_POLICY.md` §2（NDK 必须同时保留 metadata SHA-1 与 archive SHA-256，明确不得只使用 SHA-1）、§3（缺失 hash/lock 字段即 build failure，不得由 Worker 推断）和 §5（provenance gate 要求 official archive SHA-256）共同表明：SHA-1-only 不能作为该供应链的充分验收条件。对 Android SDK 与 root Maven closure，现有 policy 没有明确授权 SHA-1-only 下载验收；不得把下载后本地计算 SHA-256 自动解释为官方 provenance 或批准 hash，也不得修改 policy 绕过缺口。`docs/agent-handoff/ARTIFACT_ORGANIZATION_POLICY.md` §3.3 只要求 lock/manifest/provenance/checksum 可追溯，并未授予上述例外；`DEFENSIVE_CONSTRAINTS/README.md` §3.1、§3.4 要求默认拒绝和失败即停。
7. **仍缺少的输入。** SDK archive 的可接受完整性证明与安装 provenance；修正后的完整 root Android dependency closure；所有坐标的正确 repository、URL、版本、大小和 checksum；root Android Gradle verification metadata 或等价批准锁；NDK r28c 及其独立供应链证明。

本轮没有新的 RED/GREEN；Android/Gradle 继续 `blocked`，Ticket 26 继续 `active implementation`，不得建议 `review` 或 `completed`。本段不创建 verification-metadata.xml、lock、manifest 或新的 external artifact 目录。

### 2026-09-22 用户确认的外部 baseline exception

用户确认 `.agents/skills/**` 下 100 个 tracked、unstaged deletion 与根 `skills-lock.json` 的同类 deletion 已永久不可恢复，且接受它们是本 worktree 的外部既有状态，而非 Ticket 26 implementation。完整、逐路径的固定集合与 captured Git 身份仅记录于 worktree 外 TEMP 文件：`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\temp\ticket-26-external-baseline-waiver-20260922-TEMP.md`；该文件不是 ADR、spec、policy、tracker 或产品事实源，且不得提交。

本 worktree 仍然 dirty，不能报告为 clean。后续 freshness 必须使用 baseline-aware delta：例外集合必须逐路径精确匹配、每项仍为 tracked unstaged deletion；例外之外的 dirty/untracked 都必须属于本 dispatch ownership。任何新增或状态变化的 `.agents/skills/**`、`skills-lock.json` 或其他 ownership 外路径均 fail closed，并由用户/orchestrator 在 Ticket 26 之外决定。该例外不改变产品边界、安全、架构、供应链、真机、验收或 policy；项目 skill 可复现性另作为 tooling-maintenance 事项处理。

本轮创建 waiver 后的只读复核已通过：例外集合仍为精确的 100 + 1 个 tracked unstaged deletion，branch/HEAD/merge-base/upstream 与 capture 一致，例外之外未发现 ownership 外 dirty/untracked 或 ignored 生成物；`git diff --check` 无输出。正确状态为 `baseline-aware ownership gate passed; known external deletions remain intentionally dirty and excluded from Ticket 26 ownership.`

baseline-aware gate 通过后，Android bootstrap 的预网络 build-file 核验立即发现 `settings.gradle.kts` 的 `pluginManagement.repositories` 包含 `gradlePluginPortal()`。该 repository 不在用户允许的 `dl.google.com/android/repository/`、`dl.google.com/dl/android/maven2/`、`repo.maven.apache.org/maven2/` allowlist 中；依用户 fail-closed 规则，未访问网络、未创建 Android toolchain root、未下载/解压/执行 Gradle，也没有修改或绕过该 repository。继续 bootstrap 需要用户/orchestrator 在 Ticket 26 之外给出与 allowlist 相容的明确 scope 决定；当前 Android/Gradle 保持 blocked。

### 2026-09-22 固定 plugin repository 审计与最小收窄

用户随后授予的条件式授权先要求无网络审计。root build 实际只通过 version catalog 使用两个非 core plugin：`com.android.application:8.13.2` 与 `org.jetbrains.kotlin.android:2.2.21`；`java` / `java-library` 是 Gradle distribution 的 core plugin，不访问 plugin repository。现有旧 discovery 把两个 marker 当作 binary 404 phantom，不能作为下载清单或 repository 结论。

在无网络 audit 后，仅访问既有 allowlist 内的四个固定 POM metadata endpoint，全部 direct `200` 且没有重定向：Google Maven 的 `com.android.application:com.android.application.gradle.plugin:8.13.2` marker（1,330 bytes）指向 Google Maven 的 `com.android.tools.build:gradle:8.13.2` implementation POM（11,773 bytes）；Maven Central 的 `org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:2.2.21` marker（1,392 bytes；official SHA-1 header `b873508375cf92a6cbc8e7b81b3c82b519837873`）指向 Maven Central 的 `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21` implementation POM（13,034 bytes；official SHA-1 header `4c47570d411f1f3334dbdcdbce10e7c4b1d4f7a7`）。因此当前 fixed plugins 不需要 Gradle Plugin Portal。

`settings.gradle.kts` 属于本 dispatch ownership，故只删除 `pluginManagement.repositories` 的 `gradlePluginPortal()` 一项；保留 `google()` 和 `mavenCentral()`、全部 plugin/dependency versions 与既有 build logic。没有新增 network source，普通 dependencies 仍只经 Google Maven / Maven Central。`git diff --check` 无输出，root Android build declarations 中不再匹配 `gradlePluginPortal` 或 `plugins.gradle.org`；baseline-aware ownership 仍通过。此项不改变产品边界、Full Linux Contract、暴露面、lifecycle 或 QEMU/JNI supply-chain rule。

### 2026-09-22 Android SDK license preflight stop

配置收窄后，已核验本地 Gradle archive 为 direct regular file、`136983045` bytes，SHA-256 为锁定值 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`；`D:\JDK17\release` 确认 BellSoft `17.0.10+13-LTS`。计划 external root `D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\artifacts\26\android-toolchain-build-20260922` 不存在，尚未创建。

仅为许可识别而读取官方 allowlist metadata `https://dl.google.com/android/repository/repository2-3.xml`：HTTP `200`、`417556` bytes、无重定向。metadata 定义 `android-sdk-license`，并显示固定 `platforms;android-36` revision 2 与 `build-tools;36.1.0` 均有 `<uses-license ref="android-sdk-license"/>`。新的隔离 SDK root 尚不存在，当前没有用户接受该 Android SDK License 的可审计记录；依“不自动接受 license”规则，在任何 SDK archive、Maven artifact、Gradle unpack 或 Gradle command 前 fail closed。此次只读取 transient POM/repository metadata；没有保存/下载 SDK 或 Maven binary、没有创建 cache/build output/manifest/staging，也没有运行 Gradle/Android/NDK/QEMU/CI/device 命令。Android/Gradle 当前因未确认的 `android-sdk-license` 保持 blocked。

报告人：Codex / GPT-5 ｜ 日期：2026-09-22 ｜ 耗时：进行中

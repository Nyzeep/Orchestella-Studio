# Ticket 37 实施报告（Implementation Report）

> 工单：`37-impl-avf-parity-share-restore-bridge`（AVF 档能力对齐——只读共享、恢复语义与桥接命令）
> 分支：`ticket/37-avf-share-restore-bridge`（worktree `DroidLayer_dev_t37`，基线 main `c878b21`）
> 状态：**实施完成，提请独立复审（ready-for-review）**。实现者依纪律不自评。
> 日期：2026-10-05

## 1. 任务背景与核心目标

Ticket 34 交付了 AVF 执行策略与 vsock 桥接传输（控制 9100 / 终端 9101），并在报告中明确：guest 侧 vsock 代理、9p 数据通道、停止/恢复对齐由票 36/37 承接。本票补齐硬件虚拟化档的剩余契约能力：

1. **只读共享 Downloads** 经 **9p-over-vsock 数据通道**（自研 9p 服务端，ADR-0009/0010/0014）；
2. **优雅停止 / 冷恢复 / 异常标记** 的 vsock 路径对齐 Ticket 28；
3. **notify / open-url** 经 vsock 传输对齐 Ticket 31；
4. **MVP 全部契约测试在 QEMU 与 AVF 两侧全绿**（能力矩阵两列对齐）。

## 2. 模块边界与红线遵从核对（Ownership & Boundaries Verification）

- **`runtime:contract` 零修改**（本票硬约束）：`git diff main -- runtime/contract/` 为空。全部协议语义复用既有 `BridgeControl`（六命令 + POWER/Notification/UrlOpener seams）、`BridgeClient`、`BridgeHandshake`、`SharedDirectoryContract`/`MountOptions`、`InstanceLifecycle`（INTERRUPTED/CRASHED/reconcile/coldRestore/recordAbnormalTermination/exportDiagnostic）。
- **只写本票 owned 路径**：`runtime/engine-avf/`、`guest-assets/`（agent、initramfs/init、scripts）、`app/src/test/`、`runtime/engine-avf/build.gradle.kts`、看板 37、本报告。未触碰 ADR/规范/治理文件、其他票据、T34/T36 worktree、历史 worktree。
- **资产卫生**：零 `.log` 文件入库（构建/测试输出均未提交）；未触碰 Class A 资产；未运行任何打包变更（APK payload 三通道未动）。
- **离线构建**：全部 Gradle 运行带 `--offline`；无网络请求；无新依赖、无新端点。
- **审查独立性**：本报告只主张实现与自测证据；评审由 Orchestrator 另行指派。

## 3. 架构设计细节

### 3.1 数据面：9p-over-vsock 与端口约定

- **方向裁定**（依据 `docs/research/2026-09-05-前置研究独立审视.md`：官方宿主↔VM 通道 `vm.connectVsock(port)`，**VM 只能监听、不能主动连宿主，只有 owner app 能连接**）：guest agent 在 9p 数据端口**监听**（rendezvous），宿主 `connectVsock` 连入并在该字节流上**充当 9p 服务端**（Downloads 数据在宿主侧）；guest 侧由 agent 将已接受的连接 fd 交给内核 `mount -t 9p -o trans=fd,rfdno=3,wfdno=3`（Linux 9p 的 fd 传输），从而以零补丁内核、零新传输实现挂载。Podroid 的 200000 仅为旁证，本票沿用既有 9100/9101 编号序列取 **9102**（`AvfLaunchSpec.DEFAULT_9P_VSOCK_PORT`，且受既有 [1024,65535] 端口校验约束）。
- **端口互异**：控制/终端/9p 数据三端口在 `AvfLaunchSpec.build()` 无条件互异校验。
- **cmdline 通告**：配置共享时 guest cmdline 追加 ` droidlayer.vsock_9p_port=9102`；**无共享配置时零 9p 泄漏**（对齐 QEMU 侧 fsdev 的零泄漏不变式，`AVF_LAUNCH_SPEC_TEST_OK no_share_leak=1`）。
- **通道生命周期**：`AvfVmSession.shareChannel()` 惰性重试连接（同控制/终端的 100×200ms 模式）；`startShareServer()` 在守护线程跑 9p 服务端；`closeChannels()` 覆盖共享通道。

### 3.2 自研 9P2000.L 只读服务端（`Avf9pShareServer`）

按 9p 行为规范原创实现（ADR-0014 零代码复制立场；Podroid ~886 行仅作规模旁证）：

- **已实现关键操作集**：Tversion（9P2000.L 协商、msize 协商）、Tauth（无认证立场 → ENOSYS，客户端以 afid=NOFID attach）、Tattach（aname 必须等于 mountTag 或 "/"，根 qid）、Twalk（逐元素解析、首元素失败 Rlerror、中途失败按规范回退部分 walk）、Tlopen（读模式放行；OWRITE/ORDWR/O_TRUNC/O_APPEND/O_CREAT 一律 **EROFS**）、Tread、Treaddir（快照 + 逐条 offset）、Tgetattr（basic 集 0x7df、无写位模式 0444/0555）、Tstatfs、Tclunk、Tflush、Tfsync（只读无脏数据，成功应答）。
- **物理只读边界**：Twrite/Tlcreate/Tmkdir/Tmknod/Tsymlink/Tlink/Trename/Trenameat/Tunlinkat/Tremove/Tsetattr/Txattrcreate 在触碰文件系统**之前**统一 Rlerror **EROFS(30)**——与 QEMU `readonly=on` 等价；xattr 查询 ENOSYS；未知/遗留操作 ENOSYS。
- **包含性**：每步 walk 双重校验——词法（normalize + startsWith(root)）与真实路径（toRealPath + startsWith(rootReal)），符号链接指向共享树外即 EACCES 拒绝；`..` 在导出根处钳制（导出根语义）；绝对名/NUL/含 `/` 元素拒绝。
- **帧协议**：LE 4 字节 size 前缀；响应严格按序（tag 跳过不追踪）；干净 EOF 正常结束会话；帧损坏终止会话。

### 3.3 反射装配补缺：`setCmdline`

实施中发现 T34 的 `ReflectiveAvfVirtualMachineApi` 未把 `AvfLaunchSpec.guestCmdline()` 装配进 VM 配置——`droidlayer.*` guest 契约（含控制/终端端口与 9p 数据端口）无法到达 guest。本票以 `invokeOptional(setCmdline, spec.guestCmdline())` 补齐（可选步骤、留痕 reflectionNotes、缺失绝不静默成功——引擎测试断言 cmdline 到达观测配置且无共享时无 9p token）。测试假桩 `VirtualMachineCustomImageConfig` 相应支持 cmdline 记录。

### 3.4 guest 侧：agent vsock 服务模式（票 34 矩阵裁定的 36/37 共享面之 37 部分）

- **`guest-agent.c` 新增 vsock 模式**：`--vsock-control-port/--vsock-terminal-port/--vsock-share-port/--share-tag/--share-mount`。`poll` 多路监听 + 每连接 `fork`：控制连接逐字复用既有 Bridge v1 应答（认证/版本/PING/RESIZE）；终端连接 raw 双向中继 `--terminal-device`（AVF 下为 `hvc0`，`cfmakeraw` 语义展开为 POSIX 位操作）；9p rendezvous 连接 `dup2(fd,3)` 后 `execlp mount -t 9p -o trans=fd,rfdno=3,wfdno=3,version=9p2000.L,ro <tag> <mount>`——每启动**恰一次**挂载，之后停止通告该端口。`signal(SIGCHLD, SIG_IGN)` 防僵尸。
- **QEMU 字符设备路径字节级不变**：编解码函数 fd 参数化（`STDIN_FILENO/STDOUT_FILENO` 传参），认证与控制服务拆分为 `authenticate_control`/`serve_control_channel_fd`，无终端设备的 char 模式仍是"握手后 exec /bin/sh"（T26 终端交接语义不变）。
- **编译缝**：`-DDROIDLAYER_TEST_AF_INET` 仅替换 socket family（AF_VSOCK→AF_INET 环回），供无 vsock 命名空间的 Linux 宿主以进程级测试驱动**同一套服务逻辑**；生产装配仍为 `sockaddr_vm`/`VMADDR_CID_ANY`。
- **init 启动器**（alpine busybox + debian systemd 两处 heredoc）：运行时解析 `/proc/cmdline`，存在 `droidlayer.vsock_control_port` 即切换 vsock 模式（终端设备显式 `/dev/hvc0`；`vsock_9p_port` 存在才通告 9p 参数）；否则维持 QEMU 字符设备路径。fstab 的 virtio 9p 条目维持 `nofail`（QEMU 不变；AVF 下失败无副作用，下载共享由 agent 的 trans=fd 路径提供）。openrc 服务不变（仅 QEMU 注册）。

### 3.5 生命周期对齐（T28 语义）

`runtime:instance` 的状态机与对账语义是策略中立的，本票补 AVF 传输上的端到端证据与诊断源：

- **`AvfSessionDiagnostics.archive(...)`**：AVF 会话异常退出的宿主侧诊断归档（观察到的退出状态 + 反射装配事件逐条留痕；不虚构 VMM 日志——AVF 无 QEMU stderr 等价物，归档如实记录引擎观测）。
- `recordAbnormalTermination(id, CrashDiagnostic)` 绑定该归档为 `logPath`；`exportDiagnostic` 的导出报告内嵌归档全文。

## 4. 验收标准逐项落地与实测证据

### 4.1 AC-1（Downloads 只读共享，与 30 相同验收）— 已达成（JVM 契约级）

`AVF_9P_SHARE_CONTRACT_TEST_OK browse=1 write_refusals=1 containment=1 no_share_leak=1`：

- **browse**：同一 `SharedDirectoryContract.SharedDirectorySpec`（`defaultDownloads(hostPath)`）驱动；挂载侧最小客户端（`TestNinePClient`，`trans=fd` 内核客户端的替身）协商 9P2000.L → attach("downloads") → 两级 walk → lopen → read 内容逐字节一致 → getattr size 一致 → readdir 列举排序正确。
- **write_refusals**：write/mkdir/lcreate/setattr/remove/unlinkat 六类操作全部 Rlerror **EROFS**，无一触碰文件系统。
- **containment**：`..` 自根钳制回根 qid；缺失名 ENOENT；含 `/` 元素 ENOENT；子目录内 `..` 回根；未知 attach 名 ENOENT。
- **no_share_leak**：无共享配置时 `shareChannel()` 拒绝、cmdline 无 `droidlayer.vsock_9p_port`。

与 T30 的对应关系：QEMU 靠 `readonly=on` + guest `ro` 挂载；AVF 靠服务端 EROFS 物理拒绝 + guest `trans=fd,...,ro` 挂载。spec 类型与验收语义完全同一。

### 4.2 AC-2（优雅停止/冷恢复/异常标记，与 28 相同验收）— 已达成（JVM 契约级）

`AVF_RECONCILIATION_LIFECYCLE_CONTRACT_TEST_OK reconcile=1 cold_restore=1 abnormal=1 diagnostics_export=1`：

- **强杀对账**：实例 RUNNING（vsock 桥接握手存活）→ 模拟宿主死亡（无正常停止记录）→ RUNNING 槽位跨 FileStore 重建持久 → `reconcileAfterHostDeath` → INTERRUPTED 持久。
- **冷恢复**：`coldRestore` → RUNNING（单运行实例不变式重生效）→ 新 AVF 会话桥接握手可用 → 正常退出 STOPPED。
- **异常标记与诊断归档**：模拟 VMM 死亡（通道先断、宿主随后收尾）→ `AvfSessionDiagnostics.archive` 落盘（含 `exit_status=`）→ `recordAbnormalTermination` 标记 CRASHED 并绑定诊断 → 跨 store 重建持久 → `exportDiagnostic` 导出报告且内嵌归档 → `coldRestore` 清除崩溃标记。
- **优雅停止命令**：`AVF_BRIDGE_OVER_VSOCK_CONTRACT_TEST_OK power=1`——`DROIDLAYER/POWER shutdown|poweroff` 经 vsock 往返分类 `ok-power`，线行逐字到达 guest（契约层与 T28 同线协议；C agent 不实现 POWER 为两策略同等的既有边界，见 §7）。

### 4.3 AC-3（notify/open-url，与 31 相同验收）— 已达成

- 传输层（T34 已交付并保持）：`notify_open_url=1` —— `DROIDLAYER/NOTIFY <enc> <enc>` / `DROIDLAYER/OPEN-URL <url>` 经 vsock 往返分类 `ok-notify`/`ok-open-url`。
- 本票补 **app 流式路径全语义锁定**（`BridgeConnectorStreamsTest`，AVF vsock 入口即 `serveStreams`）：
  - guest 发起命令全分发：URL 编码解码 → 通知/URL seam 调用 → 契约应答逐字（`OK notify`/`OK open-url`）；
  - 白名单：`file:///`、`javascript:` 一律 `malformed-command`（ADR-0009 修订版线协议一致）；
  - adapter 拒绝如实 `notify-unavailable`/`open-url-unavailable`，绝不伪造成功（ADR-0010）。

### 4.4 AC-4（MVP 全部契约测试在两策略上全绿）— 已达成（含登记的存量失败）

- **runtime 七模块离线 `check` 全绿**（`BUILD SUCCESSFUL`，23 个测试类 OK 标记），其中 QEMU 侧（`SHARED_DIRECTORY_CONTRACT_TEST_OK`/`ENGINE_FSDEV_FORMATTERS_TEST_OK`/生命周期与桥接契约等）与 AVF 侧（上列 AVF_* 全套）两列对齐；`runtime:contract`、`runtime:instance` 等策略中立契约测试单实现覆盖两策略语义。
- **app**：42 例 39 绿 + 3 例既有 T33 SAF 失败（`AndroidSafExportAdapterTest`，git diff 0 行定界，与本票无关，T34 评审报告同样登记）；`:app:lintDebug`、`:app:assembleDebug` 绿。
- **guest-assets**：WSL 直验（见 §6.3）。

## 5. Git 提交记录（Conventional Commits + [ticket-37]）

| 提交 | 说明 |
| --- | --- |
| `f511c7b` | feat(engine-avf): 启动规格新增 9p-over-vsock 数据通道端口与共享目录装配 |
| `155f18d` | feat(engine-avf): 自研 9P2000.L 只读数据面服务端与 vsock 契约测试（含 setCmdline 装配补缺） |
| `99259a9` | feat(engine-avf): AVF 生命周期对齐 T28：强杀对账、冷恢复与崩溃诊断归档 |
| `f0833fb` | test(bridge): POWER 经 vsock 往返与 app 流式 notify/open-url 全语义锁定 |
| `dc7cdb6` | feat(guest-assets): guest-agent vsock 服务模式与 9p trans=fd 挂载、init 启动器适配 |
| （本次） | docs(report)/docs(tracker)：实施报告与看板 ready-for-review |

## 6. 测试执行证据

### 6.1 runtime 模块（离线）

`./gradlew --offline :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check :runtime:engine-avf:check :runtime:capability:check :runtime:network:check :runtime:distro:check` → **BUILD SUCCESSFUL in 9s**，关键 OK 行：

```
SHARED_DIRECTORY_CONTRACT_TEST_OK            （T30 契约，QEMU 侧）
ENGINE_FSDEV_FORMATTERS_TEST_OK              （QEMU 9p 装配）
AVF_LAUNCH_SPEC_TEST_OK defaults=1 cpu_count=1 validation=11 share_announce=1 no_share_leak=1
AVF_9P_SHARE_CONTRACT_TEST_OK browse=1 write_refusals=1 containment=1 no_share_leak=1
AVF_BRIDGE_OVER_VSOCK_CONTRACT_TEST_OK hello=1 ping_resize=1 unauthorized=1 notify_open_url=1 power=1
AVF_RECONCILIATION_LIFECYCLE_CONTRACT_TEST_OK reconcile=1 cold_restore=1 abnormal=1 diagnostics_export=1
AVF_INSTANCE_LIFECYCLE_CONTRACT_TEST_OK created=1 provisioning=1 running=1 stopped=1 bridge=1
AVF_TERMINAL_STREAM_CONTRACT_TEST_OK echo=1 large_chunk=1 guest_eof=1
AVF_VIRTUAL_MACHINE_ENGINE_TEST_OK assembly=1 cpu_count=1 retry=1 connect_guard=1 numeric_status=1 reflection_unavailable=1 manager_unavailable=1 hardware_unsupported=1 permission_denied=1
AVF_QEMU_INTEROP_TEST_OK same_kernel=1 same_initramfs=1 same_root_volume=1
```

### 6.2 app（`subst X:` ASCII 别名配方，离线）

- `:app:testDebugUnitTest`：**42 例，39 绿**；3 例失败全部为 `AndroidSafExportAdapterTest`（T33 范围存量，mockable android.jar `Uri` 返回 null 的 JVM NPE，本票 diff 0 行）。
- `:app:lintDebug :app:assembleDebug`：**BUILD SUCCESSFUL**（`app-debug.apk` 1,004,572 B，无 payload 装配变体，与 T34 无 payload 基线同型）。

### 6.3 guest-assets（WSL `DroidLayer-T26` 直验）

`bash scripts/check-guest-agent-vsock.sh`：

```
CROSS_COMPILED_OK prod=1 test_seam=1 compiler=/usr/bin/aarch64-linux-gnu-gcc target=aarch64
BEHAVIOR_RUN_SKIPPED reason=cross-compiled-binary-cannot-execute-here
```

- 本机（win32）无 POSIX native C 编译器、无 qemu-user：guest-agent 以 **aarch64 交叉编译**（恰为 guest 真实目标架构）通过 `-std=c11 -Wall -Wextra -Werror` 两变体（生产 AF_VSOCK + 测试缝）；行为层在该宿主如实跳过（脚本自报原因），待具备 native 编译器的环境或真机冒烟执行。
- 补充证据（本机）：MinGW gcc 编译 `_WIN32` 路径通过，运行行为 `无参数=64(usage)`、`缺 token=65(token-unavailable)`、vsock 请求被诚实拒绝。
- `sh scripts/test-initramfs-init-fallback.sh`：`ALPINE_BUSYBOX_INIT_FALLBACK_OK` + `DEBIAN_SYSTEMD_FLAVOR_OK`（两处 launcher heredoc 改造后安装函数仍产出正确入口）；生成的 launcher `sh -n` 语法通过。

## 7. 偏差、既有问题与新发现（如实记录）

1. **9p 数据端口取 9102 而非 Podroid 的 200000**：Podroid 端口值为旁证非规范；本仓 `AvfLaunchSpec` 端口校验域为 [1024,65535]，沿用 9100/9101 序列取 9102，语义（独立数据通道、与控制/终端互异）与 ADR-0009/0010 一致。
2. **`setNetworkSupported(true)` 归属票 36**（T34 评审 §9.5 裁定）：本票未触碰该装配步骤。
3. **POWER 的 C agent 侧实现为两策略同等既有边界**：T28 交付了契约层 power（BridgeControl/BridgeClient + 错误码），C agent 自 T26 起未实现 POWER（两策略一致）；QEMU 实机优雅停止走终端 `poweroff`（guest 发起），AVF 经 vsock 终端中继同语义。契约测试证明该命令在 vsock 传输上往返正确。agent 侧 POWER 实现如需补齐应作为独立范围裁定。
4. **打包 initramfs 重建未执行**：init/guest-agent 为源码级交付；含新 agent 的 initramfs 产物需走 guest-assets 构建链（容器构建，票 26 链路），本票未获授权运行——APK payload 三通道与 T34 基线一致未动。真机可用的 AVF 共享/终端闭环因此**待重建产物 + 真机冒烟（HITL，依赖票 17）**。
5. **行为层进程测试在本机跳过**：见 §6.3；`check-guest-agent-vsock.sh` 在具备 native 编译器的环境将执行完整行为层（握手/token/ping/resize 卫星/终端中继/挂载一次性与参数）。
6. **app 层共享装配未接线**：与 QEMU 侧 T30 交付级别对齐（engine/spec + 契约测试 + 冒烟；`LaunchParameters` 无共享字段，QEMU 的 app launcher 同样未接线）。Downloads 实际路径的宿主授权（存储权限 UX）属产品装配决策，两策略一并处理为宜。
7. **既有失败登记**：`AndroidSafExportAdapterTest` 3 例（T33）；guest-assets Gradle 测试在 D: CJK 路径下 `ConfigVerifierTest` 类加载失败为环境性存量（本票 guest-assets Java diff 0 行；该套件历史上即需 LF 镜像/WSL 路线运行）。
8. **实施中新发现并修复**：T34 反射装配缺 `setCmdline`（§3.3）——该缺口在真机上将使 T34 的 vsock 端口通告同样无法到达 guest，本票补齐后 `AVF_VIRTUAL_MACHINE_ENGINE_TEST_OK assembly` 场景断言 cmdline 到达。

## 8. 结论

- AC-1 / AC-2 / AC-3 在 JVM 契约层全部达成并逐字对齐 T30/T28/T31 既有语义与 ADR-0009/0010/0014 约束；AC-4 全量契约测试两策略全绿（存量失败如实登记、与本票零关联）。
- guest 侧 vsock 服务模式 + 9p trans=fd 挂载 + init 启动器适配以源码级交付并通过交叉编译与语法门；打包产物重建与真机冒烟（HITL）为遗留项，已登记（§7.4）。
- `runtime:contract` 零修改、零 `.log` 入库、离线构建、无新依赖/端点。
- **看板置 ready-for-review，提请 Orchestrator 指派独立评审（Standards + Spec 双轴）。**

# Phase 5 Ticket 09：AVF 官方 API 与 FD 连接适配修复

- 票号/标题：09 / AVF 官方 API 与 FD 连接适配修复；票与 tracker 由 integration 协调者管理，本 worker 只读。
- 执行者：avf-worker / Codex，会话配置模型，亲自执行 implement+tdd 与双轴自审，未再派生代理。
- 工作区：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_avf`。
- 分支：`phase5/worker-avf`；基线和当前 HEAD：`8d0e8c75d769ecc645ea102b90a304c8657344a1`；commit：无。
- 证据根 E：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/A/avf-api-repair/evidence`；原始执行记录使用 `.log.txt`/`.json`，即时保全副本位于 `E/preserved`。
- 状态：**原 API fidelity 与 P2 STOP 交付已完成；本轮补齐同名持久 VM cold restore 和 failed-run retry，配置一致才复用，19 项 JVM 契约已通过，八文件增量待协调者复制复评。设备/AVF/native 中断实测均未运行。**

## 1. 任务目标与已读约束

原实现把 custom image 当作 VM config，查询不存在的 createVm/addDisk(String,...)/connectVsock(int)，将真实 PFD 当成带 getInputStream 的对象，且未显式 run。测试 SDK 同样复刻错误形状，使契约通过却不证明官方 API 可执行。本票修复配置、调用时序、受限权限和 FD 生命周期，保持纯 Java 模块与明确 QEMU 回退 reason。

已读本 worktree AGENTS、只读 integration 的 `issues/09-avf-api-fidelity.md`、本 worktree `.scratch/orchestella-phase5/spec.md`。八份复制政策与研究阶段已读 canonical 版本逐文件 byte/hash 对照相同：DEFENSIVE_CONSTRAINTS README/两份 policy、HOST、ARTIFACT、ARCHITECTURE、ASSET、REPORTING。spec 已授权现有 API/Engine/流输入输出接缝，不追加常规确认。

implement/tdd/code-review 技能调用因项目与全局同名条目歧义被工具拒绝，直接读取项目 SKILL.md 及 tdd tests/mocking 参考。用户“不派生、不 commit”优先于技能中的后台 review/commit 指令。代码审查由本 worker 亲自进行，不声称有另一名独立 reviewer。没有启动 dynamic workflow run。

## 2. 实际完成与验收对应

| 验收项 | 实际完成与证据 |
| --- | --- |
| 固定官方 API、正确 builder/config/run | 从研究固定 AOSP snapshot 核对两层 Builder，custom Disk.RWDisk → addDisk(Disk)、addParam、VirtualMachineConfig.Builder(Context).setCustomImageConfig/setProtectedVm/setMemoryBytes/setCpuTopology；manager.create 后显式 run。官方形状 red→green 记录在 `config-run-*` |
| MANAGE 不能代表全部授权 | 启动前检查 MANAGE 与 USE_CUSTOM，manager/capability/non-protected 逐层拒绝；服务端 SecurityException 保留为 PERMISSION_DENIED；没有自动 grant、绕过或 direct KVM |
| connectVsock/PFD | 只接受 connectVsock(long) 返回 ParcelFileDescriptor；验证精确参数/返回签名。默认反射 PFD AutoCloseStreams，另提供 App 注入 DescriptorAdapter；不再查询伪 getInputStream |
| FD ownership/失败回收 | 原始 PFD 供 input owner，dup() 的独立 PFD 供 output owner；任一暴露流 close 都关闭整个 channel，幂等；dup/input/output 构造失败回收原始和已创建 duplicate；双向关闭错误聚合，不阻断后续回收 |
| 明确启动/API/权限/FD 拒绝 | official-shaped fixture 与新增 adapter/resource seam 测试覆盖配置、create/run、权限、protected-only、缺 API、伪 return type、PFD 接纳失败、close 抛错、closed/foreign handle、注入 adapter 失败 |
| Engine 契约与 resource 生命周期 | 除缓存 dedicated channel 外，额外 openChannel 也归 session；session teardown 即使第一项抛错也尝试全部 FD；永久权限/硬件/反射拒绝不做 boot 重试。P2 增量将连接/重试/future 等待及懒加载 I/O 移出 session monitor，关闭先终态，再锁外 platform stop，迟到资源回收一次；官方 shape 反射路径另解除 handle monitor 的相同阻塞 |
| 报告与 local patch | 本报告；`E/phase5-09-avf-api.patch`；精确 owned 文件/hash/范围记录 `E/patch-manifest.json`；patch 只在外部合成校验目录做 round-trip，不应用到主仓/integration |

`isReflectionAvailable()` 只表示官方方法签名可解析，不宣告权限、启动或 GPU 可用。所有必需配置缺项都明确拒绝；不保留“optional skipped 仍成功”、enum status、int vsock 或错误 config 继承作为兼容分支。

## 3. 官方依据与精确兼容边界

参考为 Apache-2.0 的 AOSP 源码和已记录固定 revision；本次没有复制官方实现到生产，按公开 API 重新实现适配。

- AVF android16-release revision：`c984fc337c11ca5edc03ccf02037b2455dd8fcaf`，[源码根](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/)；main 对照 `175a51b30123fa6b02b541f1969665708f7ec2c3`。
- [VirtualMachineManager.java:218、252、310](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineManager.java#218)：create(String, VirtualMachineConfig)、get(String)、getOrCreate。当前修复先 get；存在时逐字段核配置，只在不存在时 create。getOrCreate 会忽略已存在 VM 的新 config，本适配器不使用它。
- [CustomImageConfig.java:425、502–544](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineCustomImageConfig.java#425)：Disk.RWDisk(String)、addDisk(Disk)、addParam、kernel/initrd/name；VM config 外层承接 protected/memory/topology。
- [VirtualMachine.java:2093](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachine.java#2093)：connectVsock(long) → ParcelFileDescriptor；VM 有 run、numeric status、close。
- CPU 拓扑来自 main 官方 VirtualMachineConfig.java:155、164、1236–1259：只有 ONE_CPU/MATCH_HOST。**requested cpuCount=1 精确支持；任意 >1 明确 HARDWARE_UNSUPPORTED，不静默改成匹配宿主。** 协调者据此记录回退或调整可选 hardware config。本票没有改中立硬件契约或 App。
- 权限 main manifest:26–34 的 MANAGE=`signature|preinstalled|development`，USE_CUSTOM=`signature|development`；RawConfig 的服务端另查 custom 权限。实际权限/hidden API/OEM/系统编译功能必须设备测试后才能标可用。
- PFD Android 16 frameworks/base revision：`99b01a65cc4c104933788b3143285ab6bae65827`，[ParcelFileDescriptor.java:387、1015、1067](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/os/ParcelFileDescriptor.java#387)：实例 dup、AutoCloseInputStream(PFD)、AutoCloseOutputStream(PFD)。源码 SHA-256 `550983ac359a592b56a32e2161ea7d55aaa45abc36c2a0e395df1ae0dcb0ad3d`；请求及固定 URL 在 `E/pfd-source.json`。

## 4. Owned 文件与 API 接入

生产仅修改三份，路径前缀为 `runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf/`：

1. `ReflectiveAvfVirtualMachineApi.java`：精确形状/权限/配置/启动/FD 接纳与关闭。
2. `AvfVirtualMachineApi.java`：新增 `DescriptorAdapter` 纯 Java 注入接缝与所有权文档；原 startCustomVm/connectVsock/handle/channel 签名保留。
3. `AvfVirtualMachineEngine.java`：session 的 additional-channel 所有权、幂等 teardown、关闭错误聚合和永久失败 fail-fast。

测试修改/新增仅 engine-avf 的官方 SDK boundary fixture、SimulatedAvfGuest、AvfVirtualMachineApiFixture、AvfVirtualMachineEngineTest、AvfNetworkAssemblyContractTest，以及新增 ReflectiveAvfVirtualMachineApiTest/AvfVirtualMachineEngineResourceTest；新增 test-only `android/content/Context.java`、`android/os/ParcelFileDescriptor.java`。测试 PFD 是用于 JVM/socket 边界的模拟，不宣称 JVM 实现了 Android 真 FD 内核。

**Avf9pShareServer.java 与 Avf9pShareContractTest.java 无 diff，未运行该独占测试。** javac 编译现有依赖类不更改其源码，9p 的服务方法正文未修改；FD adapter 的改动属于本票共享 engine 生命周期，不改变 9p 协议。App、contract、Gradle、guest、ADR、tracker 和活动主仓没有写入。

协调者 App 可保持现有两参构造，默认精确 PFD 反射包装；或注入 typed App adapter：

```java
new ReflectiveAvfVirtualMachineApi(context, classLoader, accessHook, descriptorAdapter);
```

新增公开接缝：

```java
AvfVirtualMachineApi.DescriptorAdapter.adopt(Object parcelFileDescriptor)
    throws IOException;
```

调用方提供的 adapter 仅收到已验证 PFD。成功由返回 channel 拥有原始 PFD；失败必须先释放 adapter 自己创建的 duplicate，原始由 ReflectiveAvfVirtualMachineApi 关闭；adapter 不可在失败后保留描述符。缺省实现按上述语义执行。测试证明了空/异常注入 adapter 不会泄漏原始 FD。App FD 封装、Android hidden API/permission UI 和降级提示由协调者集成，本票未修改 App。

VM run 失败会 close 已获得的 runtime handle，不删除实例盘或持久 VM 配置；下一次启动先 get 同名 VM，核配置一致且状态 stopped 后 run，支持 failed-run retry 和 cold restore。配置不一致/无法证明一致返回 CONFIG_MISMATCH，不 getOrCreate、不 setConfig、不删除、不迁移数据；配置读取权限不足保留 PERMISSION_DENIED。运行中的同名 VM 明确 VM_CREATE_FAILED，既有实例不被额外 stop/close。

## 5. TDD、测试与验证结果

运行工具：已有 `D:/JDK17/bin/javac.exe` 与 java.exe，`--release 17`、UTF-8；不使用 Gradle、不写 Gradle 文件、不停 daemon。源码编译包括所需纯 Java contract/instance/network/engine-qemu 依赖，输出全部指定到 E。测试入口为 `E/run-jvm-tests.py`；命令、stdout/stderr、exit 原件各为 `.log.txt`，汇总 JSON 均在 E 和 preserved。

已完成的 red→green：

- 官方 config/service/run 形状：旧 adapter 在 typed Context service 查询失败，修复后配置和 run 顺序通过。
- long-vsock/PFD：旧 int 签名失败，修复后真实 fixture 字节读写、原始/duplicate 回收通过。
- stream close：任一方向 close 原先会造成再次关闭/未完整回收，修复为一个 channel close 入口。
- Engine resource：第一 close 抛错时其余 channel 泄漏且 additional-channel 未归 session，修复后全部一次回收。
- PFD stream 权限失败：原先被归入瞬时 vsock 失败，修正为 PERMISSION_DENIED，禁止永久权限拒绝的重试。
- invented run shape：伪 boolean run 原先误报 shape available，收紧 run/close/stop/create/build/PFD 等关键返回签名后拒绝。

**交付验证：19 项非 9p main JVM 契约全部 exit 0**，摘要位于 `E/delivery-engine-suite-results.json`。覆盖 bridge、terminal、instance/reconciliation 生命周期、network assembly/forwarding/interop、既有 graphics/audio/input codec 契约以及新 API/resource 测试。它们证明 host/JVM 契约回归，不代表真实 guest 桌面、GPU、音频或 Android 能力已实现。

首轮全契约 16/19 通过、3 个失败保留证据：两个 lifecycle test runner 漏传 output 参数；network assembly 断言旧伪 setter 名。修正 external runner 参数与该 owned test 的官方 useNetwork/addParam 断言后通过。JDK 首次参数文件用系统默认编码不能解析中文路径，后用 `-J-Dfile.encoding=UTF-8` 修复；原失败保留。`git diff --check` 为 0。

## 6. 亲自双轴审查

**Standards 轴**：仅三份生产文件和对应 tests；未写 Android import 到生产 pure-Java 模块；没有反射 setAccessible 或自动特权；配置缺失 fail-closed；PFD 原始/duplicate 与返回 channel ownership 明确；关闭不持有 session lock 调用 audio/stream teardown，避免锁顺序倒置；按键/frame/GPU 协议和 9p 服务不在本票扩展。

**Spec 轴**：四条 ticket AC 均有上述 JVM/固定源码证据；CPU exact count 的不支持分支明确，不借 mock 方法成功；权限不足/API缺失/启动失败/FD接纳失败可返回稳定 reason 并交上层回退。设备、App integration、平台 GPU 权限仍未验收。没有 ACR 变更、图形能力矩阵翻 verified 或将 JNI/mock 输出当实测。

审查发现并修复的实质问题有：永久 FD 权限错误误分为瞬时重试、input/output 直接 close 所有权冲突、Engine 第一错误阻断后续关闭、session 锁内调用 teardown 的潜在锁反转、伪 run 返回类型误报可用。对应新增可执行测试和 relevant rerun 通过。未声称第三方独立 review 已完成；协调者保留独立 review 权责。

## 7. 未完成、风险与后续动作

- **未运行设备**：没有 adb、安装、授予权限、启动 VM、截屏/输入或任何真机操作。原设备仍由第一会话独占。真实 AOSP/OEM SystemApi/SELinux、PFD/socket 行为、kernel boot/网络以及 guest capture 尚需交接后验证。
- 没有 APK build、NDK 构建或 platform GPU/blob/零拷贝实现，相关权限增强继续冻结。本票修复已定义行为的 API bug，不修改 accepted ADR/spec，按 ARCHITECTURE policy 的 bugfix 例外不新建 ACR。
- 若 App 默认请求 >1 核，当前官方能力会明确拒绝并回 QEMU，协调者需确保 UI/日志如实报告；不得静默降到 1 或宣告 matchHost 等价。
- 持久 VM 恢复的必要 API bugfix 已补齐：get/getConfig 严格核验后一致复用 run。CONFIG_MISMATCH 由协调者沿同 root QEMU fallback 处理，本 worker 不修改 App 分流逻辑；无法读齐官方字段时拒绝，不能宣称该 ROM 已通过 cold restore。无 delete/setConfig/路径归一化/磁盘写入。
- 原 AvfEngineException 的历史注释只提 MANAGE；生产错误现在指出缺失的 MANAGE/USE_CUSTOM 具体名，文案统一在协调者 App 接入时处理。本票不扩大授权范围。

## 8. 宿主路径偏差、保留与越界检查

**明确偏差**：首次全套 JVM runner 未设置 java.io.tmpdir，已有 AvfQemuInteropTest 创建了以下两处 C 盘合成测试目录，本次发现后未读取目录内容、未删除或移动；精确元数据在 `E/unexpected-test-temp-paths.json`：

- `C:/Users/Nyzee/AppData/Local/Temp/avf-qemu-interop8999134957882878899`
- `C:/Users/Nyzee/AppData/Local/Temp/avf-qemu-interop-tag17439617639676536058`

用途仅为测试自身合成输入，可通过设置 `-Djava.io.tmpdir` 避免；没有覆盖既有目录或改系统设置。这两项不属于默认批准 D 根，属于 worker 测试 runner 遗漏，**不能声称本次所有临时写入都无越界**。后续 javac/java 已设置独立 E/synthetic-temp-*；所有正式输出、原始证据/patch 在批准路径。残留由协调者按 policy 做定点判断，不主动清理。无系统 PATH/服务/驱动/registry/firewall/SDK 变更，无宿主内容外发。

其它边界：未修改 forbidden 9p/App/contract/Gradle/guest/ADR/tracker，未操作活动主仓或 integration 的生产文件，未 commit/merge/reset/push/PR/release，未派生。常规三 worktree 阈值已超过，但用户确认均 active；本 worker 保持其 dirty 产物与全部目录，不清理。公开源码/证据为本票可复用输入，Class A 资产全部保全。

E/生成 class、synthetic test 输入、日志和 patch 归属本 worker/票09，保留到协调者完成集成和审查，之后仅由协调者按资产政策分类清理；保全证据不自动删除。

## 9. 交付与工单建议

- 中文报告：本文件。
- 首次已合流 patch：`E/phase5-09-avf-api.patch` 与 `E/patch-manifest.json` 原样保留，不再覆盖或用它重放本次修复。
- P2 五文件 patch：`E/p2-stop-liveness/phase5-09-avf-stop-liveness.incremental.patch` 与 `E/p2-stop-liveness/final-manifest.json` 原样保留，不用于重放本轮更新。
- **当前八文件 cold restore 增量**：`E/persistent-cold-restore/phase5-09-avf-persistent-restore.incremental.patch`；old/new hash、source/target 绝对路径和显式 ignored 报告 copy 清单：`E/persistent-cold-restore/final-manifest.json`。基准为 P2 已交付的 frozen 文件字节，不是 HEAD 初始全票 diff。
- 当前测试摘要：`E/persistent-delivery-engine-suite-results.json`（19 项，含 P2 STOP 回归和 cold restore/retry/mismatch）；cold restore red/green 原始记录 `E/persistent-*.log.txt`，即时副本 `E/preserved`。
- 当前范围/应用 round-trip 检查：`E/persistent-cold-restore/delivery-check.json`；官方比较语义与测试摘要：`E/persistent-cold-restore/api-and-restore-evidence.json`。所有前次 evidence、patch、manifest 原样保留。
- commit：无；branch 和 HEAD 保持既定 worker 基线。
- 建议状态：代码/JVM 部分可进入协调者 review；不能标设备 AVF/GPU verified。本 worker 不修改工单状态。

## 10. P2 独立复评：STOP 不被连接与重试等待阻挡（增量修复）

### 问题、授权与范围

协调者反馈独立 reviewer（9p worker）的 P2：原 Engine connectWithRetry 及 control/terminal/graphics/share/audio 懒加载方法持有 session monitor 调用官方 connectVsock，100×200ms 重试等待也在同锁内；stop 先 closeChannels 再 handle.stop，导致 STOP 等待约 20 秒重试或无限单次 API 阻塞。原 Reflective connectVsock 另持 handle monitor，handle.stop 也需同锁，单修 Engine 仍不能到达平台调用。

本 worker 先复现两个失败。协调者明确补充授权 Reflective adapter 与对应测试；**本次只修改以下五份文件**，均在 worker worktree，integration/活动主仓保持只读：

1. `runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf/AvfVirtualMachineEngine.java`
2. `runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf/ReflectiveAvfVirtualMachineApi.java`
3. `runtime/engine-avf/src/test/java/io/github/nyzeep/orchestella/runtime/engineavf/AvfVirtualMachineEngineResourceTest.java`
4. `runtime/engine-avf/src/test/java/io/github/nyzeep/orchestella/runtime/engineavf/ReflectiveAvfVirtualMachineApiTest.java`
5. 本报告 `docs/reports/tickets/phase5-09/implementation-report.zh-CN.md`。

报告是 ignored 路径，协调者须显式复制，不依赖只复制 tracked source 的过滤。API signature 未改变，原 FD adapter 接入继续有效。本次不添加 GPU/9p/guest 功能，不修改 fixture 的平台签名，不创建 tracker/ADR，不 commit/merge/reset/push、不派生、不设备操作。

### 修复行为

- Engine control/terminal/graphics/share/audioVsock getter 与 startShareServer 不再以整个 synchronized 方法包住可能阻塞的连接；专用端口通过每端口单个 future 登记连接所有者，连接和 future.get 在锁外完成。audio wrapper 分配/丢弃也在锁外，只有发布/终态判定用短锁。
- close/stop 先在短锁内将 channelsClosed 置为终态并拆下资源及 pending future。closedSignal 唤醒锁外 retry backoff；pending waiter 收到确定的 closed 错误。100 次尝试仍有界，但终态后不再尝试或等待剩余 backoff。
- connectWithRetry 的 API 调用在锁外。若 API 在 close 后才返回，短锁拒绝发布，立即在锁外关闭迟到 owner，抛 VSOCK_CONNECT_FAILED/closed；caller/session 不会二次回收它。已成功发布的 SessionChannel 以短锁 claim 一次，raw.close 在锁外。
- stop 先 claim 终态，再锁外到 handle.stop，随后清理已发布 stream，避免 stream.close 阻塞推迟平台停止调用。重复 STOP 只由一个 caller 获得 stop claim。
- Reflective connectVsock(long) 和 handle.stop 的 VM.stop/close 均不持 handle monitor。启动参数和权限门保持前次实现。PFD 返回后先检查终态；已停止时直接关闭原始 PFD，不 dup、不接纳 stream。
- 若 STOP 恰好发生在 dup/stream 接纳过程中，在途接纳线程继续持有局部原始/duplicate ownership，STOP 不与它并发关闭这些正在构造的资源。接纳结束后以短锁发布；终态拒绝发布时由迟到 channel 一次关闭原始和 duplicate。已发布 HandleChannel 由 handle STOP/caller close 共享一次 owner claim，所有 close I/O 在 handle 锁外。
- future、状态集合与诊断 notes 只在短锁内更新；没有使用 Java Thread.interrupt 或关闭 FD 来声称能够打断 Android native/Binder connect。

### 实际并发验证

`E/p2-session-stop-red-AvfVirtualMachineEngineResourceTest.log.txt` 记录旧 Engine 在 latch-held connect 内 STOP 超过 1 秒的失败；`E/p2-official-stop-red-ReflectiveAvfVirtualMachineApiTest.log.txt` 记录旧 Reflective handle 同锁上的 TimeoutException。测试不会先释放 connect latch 帮 STOP 通过。

修复后的最终 19 项 JVM 契约全部 exit 0，详见 `E/p2-delivery-engine-suite-results.json`。其中资源/反射两项测试附加以下真实 JVM 并发断言：

| 并发场景 | 观测及判据 |
| --- | --- |
| control、terminal、graphics、audioVsock、openChannel 五条 API connect 实际等待 latch | 每条 STOP 在 1 秒内到达 fixture handle.stop 并返回；connect latch 仍未释放。最终采样分别 611700、144200、153699、163201、144301 ns；不是 native 性能数字 |
| 经生产 Reflective adapter → 官方形状 VirtualMachine.connectVsock(long)，API 内等待 latch | session STOP 在 connect 仍阻塞时调用实际 fixture VM.stop/close 各一次，最终采样 1183200 ns，满足 ≤1 秒；连接随后返回的原始 PFD close 一次、duplicate 未创建、caller 收到 closed |
| 同端口两个 lazy getter 实际并发等待 | API connect 一次；成功路径返回同一 owner；关闭路径 pending waiter 在 1 秒内被 closed 拒绝，owner 的仍阻塞 API 后到结果回收一次 |
| 第一次瞬时失败后配置 20 秒 retry backoff | close 在 1 秒内唤醒等待，无第二次 connect |
| 已发布 channel.close 自身被 latch 阻塞 | handle.stop 已先执行；关闭终态 getter 不被 teardown 锁阻挡 |
| PFD duplicate/AutoCloseStreams 已构造而接纳仍等 latch | STOP 在 1 秒内进入 VM stop/close，未提前并发关闭在途 FD；后到完成拒绝发布，原始/duplicate 各回收一次 |
| 直接 handle.stop 与 caller/stream close | published PFD 原始/duplicate 都只 close 一次；repeat stop 不再发平台调用，closed handle 不连接 |

扩展测试第一次出现的额外 TimeoutException 来自测试匿名 FixtureApi 的 `release` 字段遮蔽了 teardown 的局部 latch，已改名 `teardownRelease`，失败原件保留在 `E/p2-concurrency-expanded-*`。修复后相关测试和完整套件通过；没有隐藏失败或将静态锁审查替代实际并发运行。

### 边界、交付及复评

**≤1 秒证明的是 JVM fixture 下本项目 Java monitor/重试/FD 接纳调度不阻挡 STOP 到官方形状平台方法入口，不是 Android 系统的停止耗时保证。** 真设备的 VirtualMachine 内部锁、Binder/native connect、VM.stop/close 和 FD teardown 仍可能阻塞；本次均 NOT_RUN，未证明 native connect 可中断，也没有采用反射私有字段去修改平台行为。

五文件合流前 baseline 是与前次 manifest 相符的稳定字节，保存于 `E/p2-stop-liveness/baseline` 与 preserved；基线 hash、增量 patch 和更新后的全部 old/new SHA-256 在 final-manifest。没有从 integration 复制新生产文件或覆盖其源码。保留原全票 patch、原测试证据和所有 active worktree；本次新增证据全部位于批准 D 根，无新增 C 盘输出或 `.log` 扩展名证据。

本问题属于既有 API/lifecycle bugfix，未扩大权限或修改协议/ADR，不新建 ACR。建议协调者按 final-manifest 显式复制五文件（含 ignored 报告），跑 integration regression 后交原 reviewer 复评；原设备验证状态保持 NOT_RUN。

## 11. Final spec 复核：同名持久 VM 的 cold restore 与 failed-run retry

### 固定官方语义

原 create-every-launch 的必要修复：官方 create 文档明确同名持久 VM 报错；VM.stop/close 仅结束运行/释放 runtime，并不 delete 持久 VM 配置。故第一次启动成功后或第一次 run 失败后，下一次继续 create 会永久返回 VM_CREATE_FAILED，违反已有 Full Linux 冷恢复语义。

已核 `c984fc337c11ca5edc03ccf02037b2455dd8fcaf` 的 VirtualMachineManager.java:199–269（create/get）、VirtualMachine.java:729（getConfig）、VirtualMachineConfig.java:632–647（isCompatibleWith）、CustomImageConfig 的官方读取字段与 Disk.getPartitions。VirtualMachineConfig **未 override equals**；isCompatibleWith 明示允许变化 RAM/CPU，并且其字段比较未覆盖 custom kernel/initrd/root/network，不能单独用于判定请求配置相同。该固定 Config 原始文件 SHA-256 `e4a725dca5061183bbfb1efa98ceae8211937781abb50e1ed548f1588e34e3af`，HTTP 元数据在 `E/persistent-cold-restore/official-config-request.json`，源码只作为公开参考，未上传本地内容。

### 必要切片实现

启动完成期望配置构建后，先 official manager.get(instanceId)：null 才 create；存在则 getConfig，不使用 getOrCreate 的“忽略新配置”语义。身份 isCompatibleWith 成立还须逐字段核验：

- 外层 protected、memoryBytes、CPU topology、encrypted storage、hugepages 与本次请求一致。
- custom image 类型存在，name/kernel/initrd/bootloader/params/network 相同；额外 shared paths、audio/display/GPU/USB 和 touch/keyboard/mouse/switches/trackpad/balloon 与本票未配置的期望一致，拒绝复用一个带未请求增强的同名 VM。
- 恰好一块同 imagePath、同 writable 的 root disk，partition 列表与本票空 partition 期望一致；不 canonicalize path、不跟随 symlink、不检查/改写用户磁盘内容。
- 已存在 VM 必须 numeric STATUS_STOPPED；running/deleted/非法状态拒绝。本次不重复 run 或 close 一个尚在运行、由另一 handle 管理的 VM。

匹配才直接 run 同一个官方 VM 对象；run 失败只 close 该次 runtime owner，持久配置/根盘保留，随后允许再次 get、核配置并 run。所有 required getter 不可访问、getConfig 返回无配置或字段不一致时 fail-closed；新增 engine-local reason `AvfEngineException.CONFIG_MISMATCH`，只写失配字段名，不回显真实 kernel/root path。权限异常仍 PERMISSION_DENIED。不调用 delete、setConfig 或 data migration，不新增 GPU/system 权限，App 的同 root QEMU fallback 由协调者处理。

### 正向与负例证据

持久 manager fixture 按官方 shape 增加 get/getConfig；同名 create 确实报错，close 不移除 manager 中的持久 VM。fixture isCompatibleWith 刻意仅比较身份，未实现伪 equals 来帮测试通过。

`E/persistent-restore-red-ReflectiveAvfVirtualMachineApiTest.log.txt` 记录第二次同名 create 的 VM_CREATE_FAILED；必要实现后 red→green。新测试证明：

1. 第一次 create/run/stop 后，另一个 adapter instance 的第二次启动 get 同一个 manager 持久 VM，仅一次 create、两次 get、两次 run；两轮 descriptor 都按原始/duplicate 各一次回收。
2. 第一次 run 抛 IOException 后 runtime close，第二次 get 验证后一致重试 run 成功，无 delete、无 getOrCreate、无 config rewrite。
3. 12 类失配（kernel、initrd、root image、RAM、CPU topology、protected identity、network、params、bootloader、disk count、partitions、config 缺失）均 CONFIG_MISMATCH；无 run/stop/close/delete、原 config 对象保持，未打开任何 FD。
4. 一个仍 running 的持久 VM 被重复 launch 时 VM_CREATE_FAILED，既有 owner 不被 stop/close。
5. getConfig 异常不可核验返回 CONFIG_MISMATCH；权限撤销返回 PERMISSION_DENIED，两项均不 run/delete，原异常 cause 保留。

最终 `E/persistent-delivery-engine-suite-results.json` 记录 **19 项 main JVM 契约全部通过**，同时保留 P2 blocked-connect STOP、迟到 PFD 回收、single-flight/backoff 取消等并发回归。设备、真实 AOSP persistence 加载/FD、OEM getter 可访问性均 NOT_RUN；不把官方 shape JVM fixture 当设备验证。

### 本轮精确文件范围与交付

本轮八文件增量：ReflectiveAvfVirtualMachineApi.java、AvfEngineException.java、test fixture VirtualMachine.java/VirtualMachineManager.java/VirtualMachineConfig.java/VirtualMachineCustomImageConfig.java、ReflectiveAvfVirtualMachineApiTest.java、票09报告。**Engine P2 文件 SHA-256 `63a056ce46a1d099d7e6cef59245929c21601857e680fcc10921a5d145560973` 保持不变**；本轮 Reflective 的 baseline 为 `35689e7c12fb2b338f16c03b340cc11d35d4d27751bd6ae66f14a33e1ab2a956`，精确 before/after 在当前 final-manifest。

增量 apply/反向/round-trip 只在 E/persistent-cold-restore 的合成目录校验；协调者只复制此八文件，ignored 票09报告显式 copy。不改 App/Gradle/contract/guest/9p/ADR/tracker/integration，不 commit/merge/reset/push，不再派生，所有 active worktree 与证据保留。本次无新增系统或 C 盘写入；原历史临时路径偏差仍在第8节保留。API fidelity/cold restore 的 bugfix 未改变安全权限或 accepted 架构，不新增 ACR。

报告人：avf-worker / Codex ｜ 日期：2026-10-13 ｜ 耗时：未统计

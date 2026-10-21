# P01 实现报告：engine-fastlane 宿主适配器（零设备）

日期：2026-10-21 ｜ 票：[P01-engine-fastlane-adapter.md](../../../../.scratch/orchestella-fastlane/issues/P01-engine-fastlane-adapter.md) ｜ 分支：`feature/fastlane-runtime`

## 1. 目标与边界

在 `runtime/engine-fastlane` 内实现 protected Microdroid app payload 的宿主侧生命周期：反射访问 `android.system.virtualmachine`，构造 payload 配置，create/run/stop/close，暴露 `connectVsock` 字节流，并把失败与退出映射成 typed reason。

本票是**零设备**交付：没有运行 adb、没有连接真机、没有声称真实 AVF 通过。真机 create/run/vsock/encrypted storage/stop 留给 DV-01。

## 2. 设计接缝

- 平台接缝只有一个：`FastLaneVirtualMachineApi.start(FastLaneLaunchSpec)` 与 `connect(FastLaneHandle, int)`。`ReflectiveFastLaneApi` 是它的生产实现，测试 fake 实现同一接缝。
- `FastLaneSession` 是产品侧 owner：持有 handle 与全部 channel，负责有界 stop、关闭时收敛、UNCONFIRMED 可见且可重试。
- `ReflectiveFastLaneApi.ReflectiveHandle` 是平台侧 owner：只做反射调用、状态映射、callback 记录；不进入 App/Instance/UI。
- `FastLaneLaunchSpec` 是不可变校验对象：payload 名、内存、CPU topology、encrypted storage、debug level、四个 vsock 端口、toolbox manifest hash。
- 与 `runtime/engine-avf` 的 non-protected 适配器完全隔离：没有修改其代码、默认路径或 Gradle 依赖。

## 3. 改动清单

| 文件 | 改动 |
| --- | --- |
| `runtime/engine-fastlane/src/main/java/.../ReflectiveFastLaneApi.java` | 反射 shape 校验、config builder、getOrCreate、callback、状态/退出映射、PFD 采用、有界 stop |
| `.../FastLaneSession.java` | 新增 `openChannel(int)` 每连接工厂；保留 `channel(int)`/命名便捷方法的缓存单通道语义；session 追踪全部 channel；stream 关闭即释放 channel |
| `.../FastLaneHandle.java` | 新增默认 `payloadReady()`；`run()` 只代表启动，不代表 payload 就绪 |
| `.../FastLaneException.java` | 删除未使用的 `REFLECTION_UNAVAILABLE`；保留本票七类 reason |
| `src/test/java/android/**` | fake SDK：builder setter 计数、create/getOrCreate、run/stop/close、callback、PFD、vsock 网络、DELETED/崩溃/不可观测 |
| `src/test/java/.../ReflectiveFastLaneApiTest.java` | 50 个行为测试（见 §7） |
| `src/test/java/.../FastLaneSessionTest.java` | 10 个 session/通道所有权测试 |
| `src/test/java/.../FastLaneLaunchSpecTest.java` | 5 个校验测试 |

## 4. AOSP contract 表（实际核对）

本地 AOSP 参考 checkout：`references/aosp-virtualization/libs/framework-virtualization/src/android/system/virtualmachine/`。下表行号是相对该目录的文件行号。

| 适配器查找 | AOSP 签名/常量 | 位置 |
| --- | --- | --- |
| `VirtualMachineConfig$Builder(Context)` | `public Builder(@NonNull Context)` | `VirtualMachineConfig.java:998` |
| `setPayloadBinaryName(String)` | `public Builder` | `VirtualMachineConfig.java:1162` |
| `setProtectedVm(boolean)` | `public Builder` | `VirtualMachineConfig.java:1211` |
| `setMemoryBytes(long)` | `public Builder` | `VirtualMachineConfig.java:1236` |
| `setCpuTopology(int)` | `public Builder` | `VirtualMachineConfig.java:1255` |
| `setEncryptedStorageBytes(long)` | `public Builder` | `VirtualMachineConfig.java:1292` |
| `setDebugLevel(int)` | `public Builder` | `VirtualMachineConfig.java:1187` |
| `build()` | `public VirtualMachineConfig` | `VirtualMachineConfig.java:1017` |
| `getPayloadBinaryName` / `isProtectedVm` / `getMemoryBytes` / `getCpuTopology` / `getEncryptedStorageBytes` / `getDebugLevel` | public getters | `VirtualMachineConfig.java:493/514/526/537/559/504` |
| `isCompatibleWith(VirtualMachineConfig)` | `public boolean` | `VirtualMachineConfig.java:632` |
| `VirtualMachineManager.getCapabilities()` | `public int` | `VirtualMachineManager.java:188` |
| `VirtualMachineManager.getOrCreate(String, VirtualMachineConfig)` | `public VirtualMachine` | `VirtualMachineManager.java:310` |
| `VirtualMachineManager.create(String, VirtualMachineConfig)` | `public VirtualMachine` | `VirtualMachineManager.java:218`（shape 校验；getOrCreate 内部使用） |
| `VirtualMachine.run()` | `public void throws VirtualMachineException` | `VirtualMachine.java:1488` |
| `VirtualMachine.stop()` | `public void throws VirtualMachineException` | `VirtualMachine.java:1824` |
| `VirtualMachine.close()` | `public void`（`AutoCloseable`） | `VirtualMachine.java:1883` |
| `VirtualMachine.getStatus()` | `public int`（0/1/2） | `VirtualMachine.java:745` |
| `VirtualMachine.getConfig()` | `public VirtualMachineConfig` | `VirtualMachine.java:729` |
| `VirtualMachine.connectVsock(long)` | `public ParcelFileDescriptor throws VirtualMachineException` | `VirtualMachine.java:2069` |
| `VirtualMachine.setCallback(Executor, VirtualMachineCallback)` | `public void` | `VirtualMachine.java:852` |
| callback 五个方法 | `onPayloadStarted/Ready(VM)`、`onPayloadFinished(VM,int)`、`onError(VM,int,String)`、`onStopped(VM,int)` | `VirtualMachineCallback.java:146-161` |
| `CPU_TOPOLOGY_ONE_CPU=0` / `MATCH_HOST=1` | public static final int | `VirtualMachineConfig.java:155/164` |
| `DEBUG_LEVEL_NONE=0` / `FULL=1` | public static final int | `VirtualMachineConfig.java:129/137` |
| `CAPABILITY_PROTECTED_VM=1` | public static final int | `VirtualMachineManager.java:102` |
| `STATUS_STOPPED/RUNNING/DELETED=0/1/2` | public static final int | `VirtualMachine.java:200/203/210` |
| `ERROR_PAYLOAD_VERIFICATION_FAILED=1` / `CHANGED=2` / `INVALID_CONFIG=3` | public static final int | `VirtualMachineCallback.java:55/58/61` |
| `STOP_REASON_SHUTDOWN=3` / `..._HAS_CHANGED=12` / `..._VERIFICATION_FAILED=13` / `..._INVALID_PAYLOAD_CONFIG=14` | public static final int | `VirtualMachineCallback.java:104/131/134/137` |
| `ParcelFileDescriptor.dup()` / `close()` / `AutoCloseInputStream` / `AutoCloseOutputStream` | public | `android/os/ParcelFileDescriptor.java`（AOSP framework） |

结论：本适配器查找的每个方法都能在 AOSP 参考源码中找到精确签名。没有发现返回类型或参数形状不一致。`connectVsock` 的受检异常是 `VirtualMachineException`，反射调用用 `InvocationTargetException` 统一承接，不需要静态 catch。

## 5. 配置字段逐项传递

`buildConfig` 对同一个 builder 恰好调用一次：

| launch spec | builder 方法 | 值 |
| --- | --- | --- |
| `payloadBinaryName` | `setPayloadBinaryName` | 原样 |
| 固定 | `setProtectedVm` | `true`，恰好一次 |
| `memoryMb` | `setMemoryBytes` | `memoryMb * 1024 * 1024` |
| `ONE_CPU` / `MATCH_HOST` | `setCpuTopology` | `0` / `1` |
| `encryptedStorageBytes` | `setEncryptedStorageBytes` | 原样 |
| `NONE` / `FULL` | `setDebugLevel` | `0` / `1` |

生产路径的 `DebugLevel.NONE` 映射为 `DEBUG_LEVEL_NONE`；`FULL` 只作为 QA 显式输入，不改变保护。

## 6. 失败分类依据

分类只能依赖反射边界能观察到的信息；目标 ROM 的 APEX 版本差异必须在 DV-01 复核。

| reason | 触发条件 | 依据 |
| --- | --- | --- |
| `HIDDEN_API_BLOCKED` | 必需 class/method 缺失、`IllegalAccessException`、`LinkageError`、类加载 `SecurityException` | 隐藏 API 列表在反射层通常表现为 `NoSuchMethodException`/访问拒绝；无法与旧 ROM 缺方法区分，统一按 API 不可用处理 |
| `PERMISSION_DENIED` | `checkSelfPermission(MANAGE_VIRTUAL_MACHINE) != GRANTED`，或服务/PFD/`connectVsock` 抛 `SecurityException` | `VirtualMachineManager.create` 的 `@RequiresPermission` 只要求 `MANAGE_VIRTUAL_MACHINE`；app payload 不使用 `USE_CUSTOM_VIRTUAL_MACHINE` |
| `PROTECTED_UNAVAILABLE` | manager 为 null、`getCapabilities()` 无 `CAPABILITY_PROTECTED_VM`、`create/run/stop` 抛 `UnsupportedOperationException` | 设备/ROM 明确不提供 protected VM；不进入 non-protected 路径 |
| `PAYLOAD_VERIFY_FAILED` | callback `ERROR_PAYLOAD_VERIFICATION_FAILED(1)` / `ERROR_PAYLOAD_CHANGED(2)`；stop reason `12/13`；`VirtualMachineException` 消息含 verif/payload changed | APK/idsig/payload 校验失败；不重试 |
| `VM_CREATE_FAILED` | builder/build/getOrCreate/getConfig/run 失败、既有 config 不等、既有 VM 非 STOPPED、callback `ERROR_PAYLOAD_INVALID_CONFIG(3)`、stop reason `14` | 启动/配置阶段失败；不自动 delete encrypted storage |
| `VSOCK_FAILED` | 端口不在 launch contract、`connectVsock` 失败、返回非 PFD、`dup`/AutoClose 流采用失败、session 已关闭 | 传输边界失败；可重试，但由调用方 bounded retry |
| `STOP_UNCONFIRMED` | stop/close 后未观察到 STOPPED；DELETED 不视为停止证明 | 只有确认停止才允许写 STOPPED；UNCONFIRMED 保持可见、可重试 |

**预检顺序**：Manager 可用 → protected 能力位 → `MANAGE_VIRTUAL_MACHINE`。这样无 AVF 的设备报 `PROTECTED_UNAVAILABLE`，不会先要求一个无法帮助它的权限；权限拒绝仍报 `PERMISSION_DENIED`。

**异常解包**：PFD 采用失败用 `IOException` 包装真实边界异常；`mapped()` 只在内部异常不是 `VirtualMachineException` 时解包，避免把 payload 校验失败误判成传输失败。

## 7. 生命周期与资源所有权

### 7.1 create/run

- `getOrCreate(instanceId, config)` 是原子获取；避免 `get`-then-`create` 的 TOCTOU。
- 既有 VM 的 config 必须逐字段相等（`isCompatibleWith` + getter 比较），且状态必须是 STOPPED；否则拒绝，不隐式 delete encrypted storage。
- 新 VM 创建后安装 callback，再 `run()`；`run()` 失败时 `close()` 释放句柄。
- `run()` 只代表 VM 启动，不代表 payload 已就绪；`payloadReady()` 暴露 `onPayloadReady`，调用方应在 `connectVsock` 前等待或做有界重试。

### 7.2 每连接 channel

- `openChannel(port)` 每次调用都新建一条 vsock 连接；session 用 `ownedChannels` 追踪全部连接。
- `channel(port)` 与 `controlChannel/terminalChannel/proxyChannel/distccChannel` 是按端口缓存的单连接，只适合单个 control/terminal mux；proxy/distcc 并发必须用 `openChannel`。
- `OwnedChannel.inputStream()/outputStream()` 返回的包装流在 `close()` 时关闭整个 channel 并从缓存移除，避免缓存一个已死的流。
- session 关闭后 `openChannel`/`channel` 都失败；已建立但尚未登记的连接在 `channelsClosed` 后立即关闭。

### 7.3 stop

`stopRuntime(graceMs)` 的顺序固定：

1. 关闭全部 channel（避免阻塞读拖住 stop）；
2. `vm.stop()`；
3. 在 `graceMs` 内轮询 `getStatus()`；
4. `vm.close()` 释放平台句柄；
5. 再探测 500ms。

结果：

| 进入状态 | stop() | close() | 结果 |
| --- | --- | --- | --- |
| RUNNING | 成功/失败 | 总是尝试 | 观察到 STOPPED → `CONFIRMED`；否则 `UNCONFIRMED` |
| STOPPED | 不调用 | 调用（释放句柄） | `ALREADY_STOPPED` |
| DELETED | 不调用 | 调用（best effort） | `UNCONFIRMED`；退出观察仍是 `DELETED` |
| 不可观测 | 尝试 | 尝试 | `UNCONFIRMED`，可重试 |

`DELETED` 不作为停止证明：AOSP 可能在 backing files 已删除但 VM 仍在运行时返回 `STATUS_DELETED`。`UNCONFIRMED` 不写 STOPPED，重试后若观察到 STOPPED 则收敛为 `ALREADY_STOPPED`。

**边界**：`vm.stop()`/`vm.close()` 是同步 binder 调用；本适配器约束的是观察窗口（grace + 500ms probe），不是抢占一个已经阻塞的 binder 调用。若 ROM 的 stop 长时间不返回，DV-01 必须记录并评估是否需要独立 watchdog。

### 7.4 退出分类

| 观察 | 结果 |
| --- | --- |
| `getStatus()==DELETED` | `FastLaneExitInfo.Kind.DELETED` |
| host 已请求 stop 且观察到终止 | `STOP_REQUESTED` |
| callback payload finished exit 0 | `NORMAL` |
| callback payload finished exit != 0 | `CRASHED`（带 exit code） |
| stop reason `SHUTDOWN(3)` | `NORMAL` |
| stop reason 其它 | `CRASHED`（带 reason） |
| callback error 非校验类 | `CRASHED`（带 error code） |
| status 不可观测且无 callback | `OBSERVE_FAILED` |
| payload verification/changed | 抛 `PAYLOAD_VERIFY_FAILED` |
| payload invalid config | 抛 `VM_CREATE_FAILED` |

callback 终止状态即使 `getStatus()` 仍返回 RUNNING（AOSP 的 FINISHED→RUNNING 映射）也会立即收敛，不会无限等待状态变为 0。

## 8. 测试

### 8.1 命令

零设备 standalone harness（本环境无 Android SDK）：

```bash
javac --release 17 -Xlint:all -Werror \
  -d <out/main> $(find runtime/engine-fastlane/src/main/java -name '*.java')
javac --release 17 -Xlint:all -Werror \
  -cp <out/main>:<junit-4.13.2.jar>:<hamcrest-core-1.3.jar> \
  -d <out/test> $(find runtime/engine-fastlane/src/test/java -name '*.java')
java -cp <out/test>:<out/main>:<junit-4.13.2.jar>:<hamcrest-core-1.3.jar> \
  org.junit.runner.JUnitCore \
  io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpecTest \
  io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSessionTest \
  io.github.nyzeep.orchestella.runtime.fastlane.ReflectiveFastLaneApiTest
```

### 8.2 结果

- `javac --release 17 -Xlint:all -Werror`：主源码与测试均通过。
- JUnit：**65 tests OK**（LaunchSpec 5 + Session 10 + ReflectiveApi 50）。
- Gradle `:runtime:engine-fastlane:check`：**not-run**。本环境没有 Android SDK；根 `settings.gradle.kts` 包含 `:app`，配置阶段不可用。没有把 standalone 结果冒充 Gradle 结果。

### 8.3 覆盖的行为

- builder 六字段各恰好一次；`setProtectedVm(true)` 恰好一次；CPU/debug 映射。
- `getOrCreate` 复用/新建；config 不等拒绝且不 delete；既有 RUNNING 拒绝；DELETED 拒绝。
- create/run/get/getConfig 失败映射；`UnsupportedOperationException` → PROTECTED_UNAVAILABLE；缺 SystemApi → HIDDEN_API_BLOCKED；权限拒绝 → PERMISSION_DENIED；无 AVF + 权限拒绝 → PROTECTED_UNAVAILABLE。
- `connectVsock` 可读写；端口越界；connect 失败；PFD dup 失败；PFD 输出流采用失败并关闭原 PFD；PFD 权限拒绝。
- stop CONFIRMED/UNCONFIRMED/ALREADY_STOPPED；DELETED stop → UNCONFIRMED；stop 抛异常但 close 收敛 → CONFIRMED；不可观测状态 → UNCONFIRMED 后重试 ALREADY_STOPPED；重复 stop 幂等。
- 退出 NORMAL/STOP_REQUESTED/CRASHED/DELETED/OBSERVE_FAILED；payload verification/changed/invalid config 的 callback 与 stop reason；status 仍为 RUNNING 时 callback 终止不挂起。
- 每连接 channel 独立、缓存 channel 去重、关闭一条不影响另一条、stream 关闭驱逐缓存、关闭后拒绝新 channel、重复 close 幂等。
- notes 不包含 payload 名或路径。

## 9. 未验证（not-tested）

- 真实 ROM 的 `VirtualMachineManager` 获取、`MANAGE_VIRTUAL_MACHINE` 授权、`CAPABILITY_PROTECTED_VM` 声明。
- 真实 `create`/`run`/`payloadReady`/`connectVsock`/`stop`/`close`；20 次启停与孤儿检查（DV-01）。
- 真实 `getOrCreate` 是否存在于目标 ROM 的 APEX；不存在时本适配器报 `HIDDEN_API_BLOCKED`。
- 真实 `ParcelFileDescriptor.dup()`/AutoClose 流语义、vsock 半关闭、encrypted storage 创建/挂载。
- callback 在 binder 线程的真实顺序/延迟；`stop()` 阻塞时长。
- 目标 ROM 对 `setProtectedVm(true)`、`MATCH_HOST`、debug level 的实际行为。

## 10. 研究来源

- `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md` §4.1/§6/§7。
- `.scratch/orchestella-fastlane/issues/P01-engine-fastlane-adapter.md`。
- `docs/research/2026-10-21-cross-lane-reference-survey.md` §5/§9/§13。
- 本地 AOSP 参考 checkout：`references/aosp-virtualization`（`libs/framework-virtualization/src/android/system/virtualmachine/`），调研记录快照 `175a51b`。
- AOSP 官方：<https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/README.md>、<https://source.android.com/docs/core/virtualization/microdroid>。
- 本仓只读参考：`runtime/engine-avf/src/main/java/.../ReflectiveAvfVirtualMachineApi.java`（反射 shape/失败映射模式；未修改）。

## 11. 风险与下一步

1. **binder stop 无抢占**：grace 只约束观察窗口；若 ROM 的 `stop()` 卡住，DV-01 需要记录并决定是否加独立 watchdog。
2. **AOSP `getStatus()` 的观测盲区**：RemoteException 被 AOSP 内部吞成 STOPPED；`DELETED` 可能仍在运行。本适配器把 DELETED stop 标为 UNCONFIRMED，但仍无法证明 crosvm 已退出。
3. **`getOrCreate` 版本依赖**：目标 ROM 若没有该方法，会在 shape 预检阶段失败；DV-01 需记录实际 APEX 版本。
4. **payload readiness 未接线**：`payloadReady()` 已暴露，但 App/UI 尚未消费；DV-01 的 connect 重试策略需要在调用方实现。
5. **未接产品路径**：本票不创建 Instance、不改 App、不改 non-protected AVF 默认路径；DV-01/后续票负责接线。

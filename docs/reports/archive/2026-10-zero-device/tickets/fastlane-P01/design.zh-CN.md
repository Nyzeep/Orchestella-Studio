# P01 设计说明：engine-fastlane 宿主适配器（零设备）

## 接缝与接口

- 唯一平台接缝仍是 `FastLaneVirtualMachineApi`：`start(spec)` 返回 `FastLaneHandle`，`connect(handle, port)` 返回 `FastLaneChannel`。`ReflectiveFastLaneApi` 只实现这个接缝，不进入 App/Instance 产品路径。
- `FastLaneSession` 继续拥有 handle 与四条 vsock 通道；适配器不复制 session 的关闭/停止状态机。

## 平台 shape 与状态

- 预检只认 AOSP `android.system.virtualmachine` 的精确 shape：`VirtualMachineConfig$Builder(Context)`、`setPayloadBinaryName(String)`、`setProtectedVm(boolean)`、`setMemoryBytes(long)`、`setCpuTopology(int)`、`setEncryptedStorageBytes(long)`、`setDebugLevel(int)`、`build()`；`VirtualMachineManager.create/get/getCapabilities`；`VirtualMachine.run/stop/close/getStatus/connectVsock(long)`。
- 启动顺序：Manager 可用 → protected 能力位（`CAPABILITY_PROTECTED_VM`）→ `MANAGE_VIRTUAL_MACHINE` 权限 → 记录 builder 字段 → `get` 既有 VM →（字段全等则复用；不等则拒绝并保留 encrypted storage）→ `create` → 安装 callback → `run`。无 AVF 的设备先报 `PROTECTED_UNAVAILABLE`，不会先要求一个无法帮助它的权限。
- `setProtectedVm(true)` 恰好一次；`CPU_TOPOLOGY_ONE_CPU=0`、`MATCH_HOST=1`；`DEBUG_LEVEL_NONE=0`、`FULL=1`。

## 失败与退出分类

- 反射缺失/隐藏 API → `HIDDEN_API_BLOCKED`；`checkSelfPermission(MANAGE_VIRTUAL_MACHINE)` 拒绝或平台 `SecurityException` → `PERMISSION_DENIED`；能力位无 protected 或 `setProtectedVm(true)` 抛 `UnsupportedOperationException` → `PROTECTED_UNAVAILABLE`。
- callback `onError(ERROR_PAYLOAD_VERIFICATION_FAILED=1)` 或 `ERROR_PAYLOAD_CHANGED=2` → `PAYLOAD_VERIFY_FAILED`；stop reason `MICRODROID_PAYLOAD_HAS_CHANGED=12` / `MICRODROID_PAYLOAD_VERIFICATION_FAILED=13` 同样映射；builder/build/create/run 的其它失败 → `VM_CREATE_FAILED`；vsock 端口/PFD/流适配失败 → `VSOCK_FAILED`。
- 退出优先级：DELETED → 2；host stop 且 STOPPED → `STOP_REQUESTED`；callback payload verify error/stop reason → 抛 `PAYLOAD_VERIFY_FAILED`；payload exit code → NORMAL/CRASHED；stop reason SHUTDOWN → NORMAL，其它 → CRASHED；status 不可观测且无 callback → `OBSERVE_FAILED`。
- stop：先关通道 → `stop()` → 有界轮询 → `close()` → 再探测 500ms；返回 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED`，UNCONFIRMED 不写 STOPPED、可重试。进入时已经 STOPPED/DELETED 也调用 `close()` 释放平台句柄，但不调用 `stop()`。
- 通道所有权：`openChannel(port)` 每次建立独立 vsock 连接，proxy/distcc 并发必须使用它；`channel(port)` 与四个命名便捷方法是按端口缓存的单连接，只适合单个 control/terminal mux。

## 测试接缝

- `src/test/java/android/system/virtualmachine/*` fake SDK：记录 builder 每次 setter、可注入 create/run/callback/status/connect 行为。
- `android.os.ParcelFileDescriptor` fake 用内存 vsock 流，验证 `dup` + AutoClose 流所有权与关闭。
- 纯 JVM：字段逐项传递、复用时字段全等、拒绝不同配置、create/run/callback 失败映射、stop 三态、exit 五类、端口拒绝。
- `not-tested`：真实 ROM 的 APEX/权限/`setProtectedVm(true)`/vsock/encrypted storage 行为，留给 DV-01。

# P01: engine-fastlane 宿主适配器（零设备）

Type: implementation
Status: implemented（zero-device；Gradle not-run；DV-01 pending）
Priority: P0（Fast Lane 基础）
Owner: engine owner
Blocked by: None（零设备）；真机集成由 DV-01 验证
External gates: 无

## 目标

新增 `runtime/engine-fastlane` 模块，封装 protected Microdroid app payload 的宿主侧生命周期：

- 反射访问 `android.system.virtualmachine` 的 `VirtualMachineConfig` / `VirtualMachineManager` / `VirtualMachine`；
- 构造 `setPayloadBinaryName` + `setProtectedVm(true)` + `setMemoryBytes` + `setCpuTopology` + `setEncryptedStorageBytes` + `setDebugLevel`；
- create / run / stop / close；退出分类；`connectVsock(port)` 字节流；
- 不依赖 Android UI；fake SDK 可测。

## 零设备范围

- 纯 Java 模块 + 测试 fake classes（`android.system.virtualmachine.*`）；
- 不调用真实 AVF；不要求 adb/设备；
- 可编译进 Gradle `:runtime:engine-fastlane:check`。

## 接口

```java
interface FastLaneVirtualMachineApi {
    FastLaneHandle start(FastLaneLaunchSpec spec) throws FastLaneException;
    FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException;
}
interface FastLaneHandle {
    String instanceId();
    int status();
    void stop(long graceMs);
    void close();
}
```

`FastLaneLaunchSpec`：payload binary name、memory MB、vCPU topology、encrypted storage bytes、debug level、control/terminal/proxy/distcc ports、toolbox manifest hash。

## 行为验收（JVM/fake）

- [ ] 配置字段逐项传给 builder；`setProtectedVm(true)` 恰好一次；`setDebugLevel(NONE)` 生产路径。
- [ ] create/run 成功后返回 handle；`connectVsock` 返回可读写流。
- [ ] stop 有界：`stop` → 等待 → `close` → 二次探测；返回 CONFIRMED / UNCONFIRMED / ALREADY_STOPPED。
- [ ] 退出分类：正常、host stop、崩溃、DELETED、status 不可观测分别映射；`STOP_UNCONFIRMED` 不写 STOPPED。
- [ ] 失败分类：`HIDDEN_API_BLOCKED`、`PERMISSION_DENIED`、`PROTECTED_UNAVAILABLE`、`PAYLOAD_VERIFY_FAILED`、`VSOCK_FAILED`、`VM_CREATE_FAILED`。
- [ ] 不把异常吞成成功；不无限重试；不在日志输出 token/路径。
- [ ] 与现有 `ReflectiveAvfVirtualMachineApi` 的 non-protected 适配器隔离，不修改其默认路径。

## 联网调研

- AOSP `VirtualMachineConfig` / `VirtualMachine` / `VmAttestationDemoApp`；
- `libs/framework-virtualization/README.md` 的 encrypted storage/connectVsock；
- 本仓 `runtime/engine-avf/.../ReflectiveAvfVirtualMachineApi.java` 的反射 shape/failure mapping；
- Podroid `AvfReflect.kt` 的 builder/CPU/config 处理；
- 目标 ROM 的 `getCapabilities`/`setProtectedVm` 行为（只作设计输入，不作真机结论）。

## 交付

- 新模块 `runtime/engine-fastlane` + `build.gradle.kts`；
- `FastLaneVirtualMachineApi`、`ReflectiveFastLaneApi`、`FastLaneLaunchSpec`、`FastLaneSession`、`FastLaneException`；
- fake SDK 测试；`settings.gradle.kts` 注册；
- `:runtime:engine-fastlane:check` 绿。

## 停止条件

- AOSP API shape 与目标 ROM 不兼容：记录 failure，不硬编码多套分支；等 DV-01。
- 需要真实设备才能决定：标 `not-tested`，不在零设备票里假装通过。

## 设备交接

DV-01 需要：真实 create/run/stop、vsock 握手、encrypted storage、20 次启停、失败分类证据。

## Comments

### 2026-10-21 零设备实现

状态：zero-device 范围完成；DV-01 真机待验。

- `ReflectiveFastLaneApi` 反射适配器、fake `android.system.virtualmachine`、config 六字段、`getOrCreate`、callback、PFD 采用、有界 stop 与失败/退出分类已落地。
- `FastLaneSession` 新增 `openChannel(port)` 每连接工厂；`channel(port)` 与四个命名便捷方法保留为缓存单通道，proxy/distcc 并发必须用 `openChannel`。
- `FastLaneHandle.payloadReady()` 暴露 `onPayloadReady`；`run()` 只代表启动，不代表 payload 就绪。
- 65 个 JUnit 4 测试通过（standalone `javac --release 17 -Xlint:all -Werror` + JUnitCore）。
- Gradle `:runtime:engine-fastlane:check` **not-run**：本环境无 Android SDK，根配置包含 `:app`；未把 standalone 结果冒充 Gradle。
- `not-tested`：真实 ROM 的权限/APEX/`create`/`run`/`connectVsock`/encrypted storage/stop/20 次启停；留给 DV-01。
- 实现报告：`docs/reports/tickets/fastlane-P01/implementation-report.zh-CN.md`。

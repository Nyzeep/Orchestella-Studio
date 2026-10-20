# 07 9p 与端口转发接线 — 实施报告

- 分支：`ticket/07-ninep-and-port-forwarding`（叠加在 `ticket/06-display-audio-clipboard-truth` @ `be6de2d`）
- 执行者：Agent B
- 日期：2026-10-20
- 状态：9p 代码就绪（JVM/fake）；forward 明确 UNSUPPORTED；真机 vsock/9p 未验

## 改了什么

1. **9p 参数与装配**：`InstanceStrategyLauncher.LaunchParameters` 新增可选 `SharedDirectorySpec sharedDirectory`（旧构造器保持兼容，默认 null）。`AvfInstanceLauncher` 将它写入 `AvfLaunchSpec`，并在 engine launch 后调用 `startShareIfConfigured()`：
   - `sharedDirectory == null` → 不启动、不公告端口；
   - 启动成功 → `declareSharedDirectory(true)`，能力描述符/矩阵出现 `SHARED_DIRECTORY`；
   - 启动失败（引擎 `AvfEngineException` 或本地路径/文件系统 `RuntimeException`）→ `declareSharedDirectory(false)` + 记录 typed reason（`VSOCK_CONNECT_FAILED` / `INVALID_LAUNCH_CONFIG`），launch 继续（终端/显示不受影响）。
2. **单 owner 生命周期**：`AvfVmSession.startShareServer()` 在 `shareLock` 内只允许一个 server；重复调用返回同一实例；`channelsClosed` 后调用抛 `VSOCK_CONNECT_FAILED`，不会创建第二个 server/线程/连接。stop/close 走既有 `closeChannels()`，share 通道 EOF 后 serve 线程退出；新增 `isShareServing()` 供测试/诊断。
3. **端口统一**：9p 用 `spec.sharedDirectoryVsockPort()`，显示用 `spec.graphicsVsockPort()`；`AvfLaunchSpec` 删除未接线的 `audioVsockPort` 字段/常量/Builder/冲突校验。Builder 继续拒绝越界端口和 control/terminal/9p/graphics 冲突。
4. **端口转发 UNSUPPORTED**：guest 的 `orchestella-proxy-relay` 走 virtio-serial，没有 vsock→TCP relay，因此 AVF v1 不提供转发：
   - `AvfSession.openServiceForward()` 抛稳定 `IOException`（`SERVICE_FORWARD_UNSUPPORTED`）；
   - 删除 `AvfVsockPortForwarder`、`AvfVsockPortForwarderTest`、`AvfNetworkForwardingParityContractTest` 及 JavaExec 任务；
   - 能力矩阵无 forward；`PortForwardingBottomSheet` 在无 live QEMU engine 时已显示“AVF 尚未接入端口转发，需切换 QEMU 使用”。
5. **测试 fixture**：`AvfVirtualMachineApiFixture` 自带 `VsockChannelFactory`，不再依赖已删除的 forwarder。

## 测试（red → green）

红（过程中真实出现）：

```bash
./gradlew :runtime:engine-avf:check --no-daemon
# 删除 AvfVsockPortForwarder 后 fixture 仍引用其 VsockChannelFactory → compileTestJava 红
# 删除 audio 端口后 AvfVirtualMachineEngineResourceTest 仍引用 audioVsockChannel → compileTestJava 红
```

说明：9p 幂等/共享接线的 `AvfShareWiringTest`、`Avf9pShareLifecycleTest` 是在 seam 建立后新增的，未保留独立的 failure-first 运行记录；这是本票的流程偏离，已在“偏差与风险”记录。

绿（实际执行）：

```bash
./gradlew :runtime:engine-avf:check --no-daemon --stacktrace
# BUILD SUCCESSFUL（含 avf9pShareContractTest、avf9pShareLifecycleTest、avfRfbOverVsockTest）
./gradlew :app:testDebugUnitTest --tests '*NineP*' --tests '*Share*' --tests '*Forward*' --no-daemon
# BUILD SUCCESSFUL（AvfShareWiringTest 5/5）
./gradlew :runtime:contract:check :runtime:capability:check :runtime:engine-avf:check --no-daemon
# BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest --no-daemon
# 866 tests, 0 failures, 0 errors, 0 skipped
./gradlew :app:lintDebug --no-daemon
# BUILD SUCCESSFUL
```

关键断言：

- `Avf9pShareLifecycleTest`：第二次 start 返回同一 server；guest 只接受 1 条 share 连接；stop 后 `isShareServing()` 变 false；closed 后 start 抛 typed 错误且不多开连接。
- `AvfShareWiringTest`：未配置不启动；配置成功声明 9p；失败撤回 9p 但 launch 继续；forward 抛稳定 IOException；launcher 无 9102/9104 硬编码。
- `AvfLaunchSpecTest`：9p/graphics 端口可由 spec 指定，越界/冲突拒绝；audio 端口已移除。

## 零设备状态

- fake/JVM 通过：9p server 单 owner、启动/停止/重复调用、fake vsock 上的 9p 协议（既有 `Avf9pShareContractTest`）、forward UNSUPPORTED、端口校验。
- not-tested：真机 vsock 9102 连通、guest `trans=fd` mount、真实共享目录读写、stop UNCONFIRMED 的平台实际行为。

## 偏差与风险

- `stop UNCONFIRMED` 场景没有独立的平台 fake；但 `stopRuntime` 无论确认与否都会关闭 session 通道，`startShareServer()` 在 `channelsClosed` 后直接 typed 拒绝，因此不会出现第二个 server（`Avf9pShareLifecycleTest` 覆盖 closed 路径）。
- `openChannel(int)` 作为通用 vsock seam 保留（`AvfVirtualMachineEngineResourceTest` 的 blocked-connect 生命周期用例仍使用它），但它不再有生产转发消费者。
- 9p 失败的用户可见文案目前只有 `shareFailureReason()` reason code，UI 未新增专门提示；这属于后续 UI 接线，不影响“共享不可用”的诚实状态。
- 流程偏离：07 的 9p 幂等与共享接线测试没有像 04/06 那样先跑一次纯 red；当时的编译级 red 来自删除 forwarder/audio 端口后的旧引用。测试有效且全绿，但 red→green 证据不完整。

# 06 显示/音频/剪贴板真相 — 实施报告

- 分支：`ticket/06-display-audio-clipboard-truth`（叠加在 `ticket/04-capability-truth` @ `a4e2e7a8`）
- 执行者：Agent B
- 日期：2026-10-20
- 状态：代码就绪（JVM/fake），真机显示未验

## 改了什么

1. **中性 RFB adapter**：`QemuWaylandChannel` → `RfbDisplaySession`（app 包）。QEMU 与 AVF 共用同一实现；AVF 的 connector 改用 `session.graphicsChannel()`，端口来自 `AvfLaunchSpec`，不再出现 `openChannel(9103)`。`AvfInstanceLauncher` 对 `runtime.engineqemu` 的唯一 import 是共享资产 `RuntimeArtifactManifest`。
2. **死路径删除**：`AvfWaylandChannel`、`AvfGraphicsVsockProtocol`、`AvfPingPongBuffer`、`AvfAudioChannel`、`AvfAudioVsockProtocol` 从 main/test 删除，对应 JavaExec 任务和 `avfAllTestTasks` 条目同步移除；`AvfVmSession` 删除 `waylandChannel()`/`audioChannel()`/`audioVsockChannel()`/`vsockAudioPort()` 及音频字段清理逻辑。
3. **音频 UNSUPPORTED**：guest 的 pulseaudio 仅在 `127.0.0.1:9104` 提供 `module-simple-protocol-tcp`（`guest-assets/scripts/desktop/pulseaudio/default.pa`），没有 vsock relay；AVF 能力描述符与矩阵都不含 `AUDIO_OUTPUT`。
4. **剪贴板 UNSUPPORTED**：`registerClipboardBridge` 只是宿主回调注册，不能证明 Bridge v1 + guest clipboard agent 的 PUT/GET 端到端契约；`AvfCapabilityTruthTest` 明确验证“注册 bridge 后仍不声明”。
5. **指针**：RFB 指针事件属于显示输入，不声明 `POINTER_CAPTURE`；`GRAPHICS_RFB` 与 `POINTER_CAPTURE` 在描述符/矩阵中分离。
6. **RFB 契约**：新增 `AvfRfbOverVsockTest`（fake vsock 上 connect → frame → close → reconnect）；保留 `RfbDisplaySessionTest`（失败不伪报连接、回调关闭停止 fanout）。

## 测试（red → green）

红：

```bash
./gradlew :app:testDebugUnitTest --tests '*Display*' --no-daemon
# 6 个编译错误：RfbDisplaySession 不存在
```

绿：

```bash
./gradlew :runtime:engine-avf:check --no-daemon --stacktrace   # BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest --tests '*Rfb*' --tests '*Display*' --tests '*Clipboard*' --no-daemon
# BUILD SUCCESSFUL（AvfDisplayWiringTest 5/5, RfbDisplaySessionTest 3/3）
./gradlew :app:testDebugUnitTest --no-daemon                    # BUILD SUCCESSFUL
```

关键断言：

- `AvfDisplayWiringTest`：两个 launcher 都用 `RfbDisplaySession`；`QemuWaylandChannel`/`AvfWaylandChannel`/音频类文件不存在；engine 无 Wayland/audio 工厂；AVF 无 `openChannel(9103)`。
- `AvfRfbOverVsockTest`：fake vsock 上每个 connect 恰好打开一个会话，帧到达，close 后重连成功。
- `AvfCapabilityTruthTest`：AVF matrix 只有 terminal/bridge/HA/RFB；无 audio/clipboard/pointer capture/9p/GPU。

## 零设备状态

- fake/JVM 通过：RFB over fake vsock、adapter 生命周期、能力缺失、源码边界。
- not-tested：真机 vsock 9103 连通、`display-relay.py` 实际帧、输入注入、音频/剪贴板（按 UNSUPPORTED 不做声明）。

## 偏差与风险

- RFB 的 `WaylandChannel` 接口名仍保留（contract 层历史命名），但实现与能力都按 RFB 声明；重命名 contract 接口会波及 QEMU/AVF/UI 大量调用方，不在本票范围。
- `AvfClipboardOverVsockTest` 仍是 loopback 单测（其注释里的 9102 不是生产控制端口），不能作为剪贴板已接线的证据；本票因此不声明剪贴板。
- 删除的是无生产调用方的死路径及其专属测试，不是跳过失败测试；RFB 输入/帧行为由 `RfbDisplayChannelTest` 继续覆盖。

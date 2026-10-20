# 06: 显示/音频/剪贴板真相

Type: implementation
Status: resolved
Priority: P1
Owner: AVF data-plane owner
Blocked by: 04
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

AVF 生产显示用的是 `QemuWaylandChannel` + RFB over vsock 9103；`engine-avf` 里的 `AvfWaylandChannel`/`AvfGraphicsVsockProtocol` 在生产引用为 0。音频 `AvfAudioChannel` 也没有生产调用，且 guest 的 pulseaudio 只监听 `127.0.0.1:9104`，不是 vsock。能力矩阵却声称 graphics/audio/clipboard/pointer。用户会看到“有图形/音频”的承诺，实际只有一条 RFB 路径。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §4；`AvfInstanceLauncher.java:39-45`；`AvfVirtualMachineEngine.java:123-206`；`AvfWaylandChannel.java`；`AvfAudioChannel.java`；`guest-assets/display/display-relay.py:31`；`guest-assets/scripts/desktop/pulseaudio/default.pa:25`。

## What to build / 范围

1. **显示选一条活路径**：RFB over vsock 9103 作为 AVF v1 唯一生产路径。把 `QemuWaylandChannel` 的通用 RFB 逻辑抽成中性的 `RfbDisplaySession`/adapter（名称不再带 QEMU），QEMU 与 AVF 共用；AVF launcher 不再 import `runtime.engineqemu` 类型。
2. `AvfWaylandChannel`/`AvfGraphicsVsockProtocol`：如果不在本票端到端接线，删除或移到明确的 research/experimental 位置，并从生产 classpath/测试默认路径移除；不能保留“看起来已实现”的死路径。
3. **音频**：AVF v1 明确 UNSUPPORTED。`AvfAudioChannel` 删除或隔离为 experimental；不在能力矩阵出现。原因写进报告：guest 只有 TCP loopback 9104，没有 vsock relay。
4. **剪贴板**：仅在 Bridge v1 控制通道 + guest clipboard agent 已接线的条件下声明支持；若本票不能端到端证明（fake bridge + guest agent 契约），明确 UNSUPPORTED。不得只因为 `registerClipboardBridge` 存在就声称支持。
5. **指针/输入**：RFB 输入编码支持键盘/指针；ticket 要明确 `POINTER_CAPTURE` 是否算支持（RFB 指针事件 ≠ Android pointer capture）。建议：v1 只声明 RFB 输入，不声明 `POINTER_CAPTURE`。
6. 能力描述符（04）由实际构造的 display/clipboard adapter 生成；删除“withGraphics”式的静态声明。

## 行为验收（零设备）

- [ ] AVF 显示走中性 RFB adapter；生产代码没有 `QemuWaylandChannel`/`runtime.engineqemu` import（除共享 manifest/bootstrap seam）。
- [ ] `AvfWaylandChannel` 不再出现在生产引用；若保留，测试/文档明确 experimental，且不影响默认启动。
- [ ] 音频在 AVF v1 明确 UNSUPPORTED；能力矩阵无 `AUDIO_OUTPUT`；生产无 `AvfAudioChannel` 调用。
- [ ] 剪贴板只有 fake bridge + guest agent 契约测试通过才出现；否则 UNSUPPORTED。
- [ ] RFB 显示契约测试：connect/reconnect/input/close；fake vsock；失败有 typed 状态。
- [ ] RFB 路径在能力描述符中声明 `DISPLAY`/`GRAPHICS_RFB`（或改名后的 display 能力），不是 `GRAPHICS_WAYLAND`；UI 不把 RFB 说成 Wayland。
- [ ] `POINTER_CAPTURE` 不因 RFB 指针事件而虚报。
- [ ] 04 的 `SessionCapabilities` 能表达 display/clipboard 的真实状态。

## 测试 seam 与 red 命令

- 复用 `QemuWaylandChannelTest`/`RfbDisplayChannel` 测试，新增 AVF RFB over fake vsock 测试。
- 新增 `AvfDisplayWiringTest`：断言 AVF launcher 构造中性 adapter、没有 QEMU import；可用 ArchUnit 或源码扫描（若项目无 ArchUnit，用简单 grep 测试）。
- 剪贴板：`AvfClipboardOverVsockTest` 扩展为生产 wiring；否则删除/标 UNSUPPORTED。
- 红命令：
  ```bash
  ./gradlew :runtime:engine-avf:check :app:testDebugUnitTest \
    --tests '*Rfb*' --tests '*Display*' --tests '*Clipboard*' --no-daemon --stacktrace
  ```
- 先 red：AVF launcher 引用 QemuWaylandChannel；AvfWaylandChannel 死路径；能力矩阵声称 audio。

## 失败、数据与兼容

- 显示失败要区分“未配置/连接失败/协议失败/guest 未 ready”；不把空帧当成功。
- 不记录屏幕内容、剪贴板内容、token；测试用合成帧/合成文本。
- 不改变 QEMU 显示行为；QEMU/AVF 共用 RFB adapter 时，QEMU 回归测试必须保留。
- 音频/剪贴板 UNSUPPORTED 是正常状态；UI 不显示“即将支持”。

## 迁移、回滚与依赖

- 纯适配器/能力声明重构；无实例数据迁移。
- 回滚：恢复 QEMU/AVF 原 adapter 与矩阵；死路径删除可回滚到 research 分支。
- 依赖：不新增第三方库；不引入 gfxstream/virglrenderer/Wayland 服务。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-06/implementation-report.zh-CN.md`。
- 完成 = 一条 RFB 活路径 + 死路径清除/隔离 + 音频/剪贴板/pointer 诚实状态 + 全量 check + 报告明确“真机显示未验”。

## 不做

- 不做真机显示/输入/音频/剪贴板验收；不接 Wayland/DLFR 全套；不做 GPU 加速；不做 9p/forward（07）。

Claimed by: Agent B / 2026-10-20T01:45:00Z

## Answer

- 显示：`QemuWaylandChannel` 重命名为中性 `RfbDisplaySession`，QEMU 与 AVF 共用；AVF 通过 `session.graphicsChannel()`（spec 端口）取流，不再 import QEMU 显示类型，也不再硬编码 9103。`AvfDisplayWiringTest` 源码扫描守住这条边界（engineqemu 仅允许 `RuntimeArtifactManifest`）。
- 死路径：删除 `AvfWaylandChannel`、`AvfGraphicsVsockProtocol`、`AvfPingPongBuffer`、`AvfAudioChannel`、`AvfAudioVsockProtocol` 及其专属测试/JavaExec 任务；`AvfVmSession` 不再暴露 `waylandChannel()`/`audioChannel()`/`audioVsockChannel()`/`vsockAudioPort()`。
- 音频：AVF v1 UNSUPPORTED。原因：guest 的 pulseaudio 只开 `127.0.0.1:9104`（`module-simple-protocol-tcp listen=127.0.0.1`），没有 vsock relay；能力矩阵与描述符均无 `AUDIO_OUTPUT`。
- 剪贴板：UNSUPPORTED。`registerClipboardBridge` 只证明宿主回调存在，不能证明 Bridge v1 + guest clipboard agent 的 GET/PUT 端到端契约；`AvfCapabilityTruthTest` 明确“注册 bridge 也不声明支持”。
- 指针：RFB 指针事件只作为显示输入，不声明 `POINTER_CAPTURE`（Android 捕获是另一能力）。
- RFB 契约：新增 `AvfRfbOverVsockTest`（fake vsock：connect / frame / close / reconnect）并保留 `RfbDisplaySessionTest`（失败状态、无伪帧、回调关闭）。
- 红→绿：`RfbDisplaySessionTest` + `AvfDisplayWiringTest` 先以“RfbDisplaySession 不存在 / 死文件仍在”红，重命名与删除后转绿；engine 的 `avfRfbOverVsockTest` 新绿。
- 未验：真机 RFB 帧、输入、guest 显示 relay 连通；音频/剪贴板按 UNSUPPORTED 交付，不做真机声明。

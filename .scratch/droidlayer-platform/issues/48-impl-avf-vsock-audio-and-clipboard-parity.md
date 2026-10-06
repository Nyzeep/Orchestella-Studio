# 48: AVF vsock 音频流与剪贴板专有通道对齐

Type: implementation  
Status: completed  

## What to build

在 `:runtime:engine-avf` 中实现 AVF 硬件虚拟化档专有 vsock 端口 9104 音频流传输通道，并对齐剪贴板与外设事件通道，确保 AVF 硬件虚拟化档在系统深度互通、音频输出与外设操控上与 QEMU 软模拟档达成 100% 行为一致，遵循 ADR-0002 与 ADR-0018。

主要包含：
1. **AVF 专有 vsock 音频流协议与通道（`AvfAudioVsockProtocol` / `AvfAudioChannel`）**：
   - 固化 AVF 专有音频 vsock 端口（`9104`）；
   - 实现高效的 vsock PCM 流式读取管道，对接 `:app` 的 `LowLatencyAudioPlayer`；
   - 具备自适应重连、背压缓冲与静音待机休眠机制，在无音频输出时自动进入零 CPU 占用状态；
2. **控制面与剪贴板通道对齐**：
   - 确保 vsock 端口 9102 控制流桥接能够透明、无损地承载 `DROIDLAYER/CLIPBOARD PUT` 与 `GET` 双向命令；
   - 支持 256KB 大数据包分块流式穿透 Stage-2 虚拟化边界；
3. **图形与外设通道扩容对齐**：
   - 在 vsock 端口 9103 图形/输入流中，对齐新版 `PointerEvent` 相对位移与按键掩码传输，使 AVF 虚拟机内部的 `labwc` 合成器能够准确接收外部物理鼠标位移；
4. **AVF 能力矩阵扩容与校验**：
   - 在 `AvfVirtualMachineEngine` 中声明全面支持 `AUDIO_OUTPUT`、`CLIPBOARD_SYNC`、`POINTER_CAPTURE`，保持与能力矩阵数据契约一致。

**Blocked by:** 44, 45, 46, 47

## Acceptance criteria

- [x] AVF 虚拟机能够通过 vsock 端口 9104 稳定输出 48000Hz 16-bit 双声道 PCM 音频流，延迟与丢帧率指标达到 M3 SLA（$\le 80\text{ms}$，丢帧率 $\le 0.5\%$）；
- [x] 剪贴板双向同步在 AVF vsock 9102 通道上稳定运行，双向延迟 $\le 50\text{ms}$，无回环；
- [x] 物理鼠标相对位移事件在 vsock 9103 通道上准确转发并无损呈现在 AVF 桌面中；
- [x] `:runtime:engine-avf` 纯 JVM 单元测试与双引擎互操作回归测试套件 100% 全绿通过，无警告、无编译错误。

## Test strategy

- 纯 JVM 单元测试：
  - `AvfAudioVsockProtocolTest`：测试 9104 端口流建立、断开重连、畸形数据包过滤与背压缓冲；
  - `AvfClipboardOverVsockTest`：验证通过 vsock 9102 控制通道双向同步 Base64 文本的一致性；
  - `AvfInputEventForwardingParityTest`：验证相对位移与物理按键在 vsock 9103 上与 QEMU 本地通道的序列化等价性；
- 互操作测试：
  - `AvfQemuSystemInteropTest`：断言两套执行策略对新扩展契约的能力表达一致。

## Prototype/research dependency

依赖 Ticket 44（契约）、Ticket 45（播放器）、Ticket 46（剪贴板桥）与 Ticket 47（Guest 端配置）。

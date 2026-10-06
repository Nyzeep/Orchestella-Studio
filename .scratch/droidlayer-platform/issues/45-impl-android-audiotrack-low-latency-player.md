# 45: Android 宿主 AudioTrack 低延迟音频流播放器

Type: implementation  
Status: ready-for-review  

## What to build

在 `:app` 中实现 Android 宿主低延迟流式音频播放器（`LowLatencyAudioPlayer`），打通从 Linux Guest 音频源到 Android 硬件声卡的实时 PCM 输出管道，遵循 ADR-0013 与 ADR-0018。

主要包含：
1. **原生 `AudioTrack` 低延迟播放流水线**：
   - 采用 `AudioAttributes`（`USAGE_MEDIA`, `CONTENT_TYPE_MUSIC`）与 `AudioFormat`（`ENCODING_PCM_16BIT`, `SAMPLE_RATE_48000`, `CHANNEL_OUT_STEREO`）；
   - 以 `AudioTrack.MODE_STREAM` 模式初始化，配置两倍最小缓冲区大小（约 8KB–16KB）；
   - 后台工作线程从音频流（LocalSocket 或 TCP 套接字）持续读取 PCM 原始字节块并写入 `AudioTrack`；
2. **自适应时钟漂移补偿算法（Clock Drift Compensator）**：
   - 实时监控播放器环形缓冲区的水位线（Watermark）；
   - 动态将缓冲延迟约束在 $40\text{ms} \sim 80\text{ms}$ 最佳窗口内；
   - 当水位持续高于 $120\text{ms}$ 时，执行微量跳采样（128 点丢帧，人耳无感知），防止延迟逐步累积；
   - 当发生极端欠载（Underrun）时，执行微量平滑淡入静音插值，消除爆音（Click/Pop）；
3. **音频焦点与生命周期协同（Audio Focus Management）**：
   - 注册 `AudioManager.OnAudioFocusChangeListener`，处理音频焦点的临时抢占与释放；
   - 电话呼入或系统提示音播放时自动执行静音或降音（Ducking），挂断后平滑恢复；
   - 在应用切入后台、实例暂停或销毁时，立即暂停或安全释放 `AudioTrack`，防止电池消耗与显式硬件句柄泄漏；
4. **QEMU 软模拟档音频流对接**：
   - 对接 LocalSocket `@droidlayer-audio` 或本地端口转发（`127.0.0.1:9104`），实现即连即播。

**Blocked by:** 44

## Acceptance criteria

- [x] `LowLatencyAudioPlayer` 能够稳定以 48000Hz 16-bit 双声道格式播放 PCM 音频流，端到端延迟 $\le 80\text{ms}$；
- [x] 自适应时钟漂移补偿算法有效运作，长时间播放（>30 分钟）无爆音、无杂音、无延迟单向膨胀；
- [x] 正确处理 Android 音频焦点变化，在来电或其他应用播放音乐时平滑避让并可正常恢复；
- [x] 播放器生命周期与 Activity 及虚拟机状态严格同步，实例停止时 100% 释放系统底层音频资源，无内存泄漏与常驻线程悬挂；
- [x] 宿主音频播放常驻内存增量 $\le 10\text{ MB}$，CPU 占用率 $\le 3\%$。

## Test strategy

- JVM 单元测试与测试桩（Fixtures）：
  - `AudioDriftCompensatorTest`：模拟快时钟、慢时钟与抖动网络注入，验证丢帧与静音补帧算法边界；
  - `AudioFocusHandlerTest`：验证各音频焦点状态迁移（GAIN, LOSS, LOSS_TRANSIENT_CAN_DUCK）的正确性；
- Android 纯 JVM / Robolectric 单元测试：
  - 验证 `AudioTrack` 参数配置合法性与生命周期流转；
- 实机功能与延迟验证：
  - 播放音视频对齐测试片源，验证延迟与卡顿率指标达标。

## Prototype/research dependency

依赖 Ticket 44 产出的 `AudioStreamContract` 契约，以及 `docs/research/2026-10-06-phase3-audio-clipboard-input.md` 中的音频流水线与漂移补偿设计。

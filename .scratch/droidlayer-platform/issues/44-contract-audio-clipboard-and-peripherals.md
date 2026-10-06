# 44: 策略中立剪贴板与音频契约扩展

Type: contract  
Status: ready-for-review  

## What to build

在 `:runtime:contract` 中扩展策略中立的剪贴板双向同步、低延迟音频流与外部外设指针捕获契约，使上层 UI 呈现与底层执行策略（QEMU 软模拟 vs AVF 硬件虚拟化）完全解耦，遵循 ADR-0002 与 ADR-0018。

主要包含：
1. **能力矩阵扩容（`FullLinuxContract.Capability`）**：
   - 新增能力枚举：`AUDIO_OUTPUT`、`CLIPBOARD_SYNC`、`POINTER_CAPTURE`；
   - 更新 `CapabilityMatrix`，提供完整的支持校验方法与对应的性能/特性徽章描述（例如音频与剪贴板能力声明）；
2. **Bridge v1 线协议命令扩容（`BridgeControl`）**：
   - 新增剪贴板命令集：
     * `DROIDLAYER/CLIPBOARD PUT base64:<data>\n`
     * `DROIDLAYER/CLIPBOARD GET\n`
   - 实现输入校验与安全防御：
     * 校验 Base64 合法性与 UTF-8 字符流；
     * 设定最大有效载荷（$256\text{ KB}$），超限确定性返回 `DROIDLAYER/ERROR payload-too-large\n`；
     * 格式畸形返回 `DROIDLAYER/ERROR malformed-command\n`；
   - 在 `BridgeControl.Seams` 中增加 `Clipboard` 适配器 seam，支持事件派发；
3. **音频流契约抽象（`AudioStreamContract`）**：
   - 定义策略中立的音频流格式契约：固定采样率 $48,000\text{ Hz}$、采样位深 $16\text{ bit}$（小端有符号）、双声道立体声（Stereo）；
   - 定义音频流控制抽象（启动、暂停、停止、状态监听）；
4. **策略中立指针输入扩容（`PointerEvent`）**：
   - 在 `PointerEvent` 中扩容相对位移字段（$\Delta x, \Delta y$）与物理按键掩码（Primary/Secondary/Tertiary），支持标准无边界指针捕获模式。

**Blocked by:** 38, 43

## Acceptance criteria

- [x] `FullLinuxContract.Capability` 正式扩容 `AUDIO_OUTPUT`、`CLIPBOARD_SYNC`、`POINTER_CAPTURE`，能力矩阵能准确反映执行策略对互通特性的支持；
- [x] `BridgeControl` 成功集成并派发 `CLIPBOARD PUT` 与 `CLIPBOARD GET` 命令，合法载荷正确转码，非法或超长（$>256\text{KB}$）载荷被确定性拦截；
- [x] `AudioStreamContract` 接口定义完备，采用纯 Java 编写，无任何 Android SDK 专有依赖；
- [x] `PointerEvent` 完整支持相对位移与物理按键状态的序列化与反序列化；
- [x] 契约层 JVM 纯单元测试保持 100% 覆盖率，在既有构建中全绿通过，保持 Java 17 + `-Xlint:all -Werror` 零告警。

## Test strategy

- JVM 纯单元测试：
  - `BridgeControlClipboardTest`：验证合法 Base64 文本、长文本（256KB 边界）、畸形输入、超长截断拦截与 Seam 回调派发；
  - `FullLinuxContractAudioCapabilityTest`：验证新能力枚举加入后的能力矩阵合法性与徽章输出；
  - `AudioStreamContractTest`：验证音频流格式参数、采样率/声道数数学约束；
  - `PointerEventRelativeMotionTest`：验证相对位移与按键掩码在序列化通道中的无损编解码；
- 运行 `./gradlew :runtime:contract:test` 确保毫秒级全绿回归。

## Prototype/research dependency

依赖 `docs/research/2026-10-06-phase3-audio-clipboard-input.md` 与 ADR-0018 设计规范。

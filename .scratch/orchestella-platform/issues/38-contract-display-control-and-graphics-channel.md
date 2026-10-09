# 38: 策略中立图形显示与控制协议契约

Type: implementation  
Status: completed  

## What to build

在 `:runtime:contract` 中扩展策略中立图形显示与控制协议契约，使上层 UI 呈现与交互逻辑完全解耦于底层虚拟化执行策略（QEMU 软模拟 vs AVF 硬件虚拟化），遵循 ADR-0002 与 ADR-0017。

主要包含：
1. **能力矩阵扩容**：在 `FullLinuxContract.Capability` 中新增 `GRAPHICS_WAYLAND`，并在 `CapabilityMatrix` 中提供合法性校验与性能徽章描述；
2. **显示控制抽象（`DisplayControl`）**：定义策略中立的显示模式协商接口（包含分辨率 `width`, `height`、缩放百分比 `scale`、DPI、目标帧率 `targetFps`）与双向尺寸动态重协商（`resizeDisplay(int width, int height)`）；
3. **图形通道抽象（`WaylandChannel`）**：定义传输无关的连接句柄，封装 Wayland 协议控制流与双缓冲帧数据交换机制；
4. **Bridge v1 线协议命令扩容（`BridgeControl`）**：
   - `DROIDLAYER/DISPLAY MODE <w> <h> <scale>\n`
   - `DROIDLAYER/DISPLAY RESIZE <w> <h>\n`
   - `DROIDLAYER/DISPLAY FPS <fps>\n`
   - 提供命令线解析、边界过滤（如 $100 \le w, h \le 7680$）与应答派发；
5. **策略中立输入事件模型（`InputEvent`）**：定义标准指针（`PointerEvent`）、多点触控（`TouchEvent`）、按键（`KeyEvent`）与文本提交（`TextCommitEvent`）数据对象，支持紧凑序列化。

**Blocked by:** 26, 37

## Acceptance criteria

- [x] `FullLinuxContract.Capability` 扩容 `GRAPHICS_WAYLAND`，能力矩阵能准确反映执行策略对图形通道的支持；
- [x] `DisplayControl` 与 `WaylandChannel` 契约接口定义完备，纯 Java 编写，无任何 Android SDK/NDK 平台依赖；
- [x] `BridgeControl` 成功集成并派发 `DISPLAY MODE`、`DISPLAY RESIZE`、`DISPLAY FPS` 命令，非法参数确定性返回 `DROIDLAYER/ERROR malformed-command\n`；
- [x] 策略中立输入事件数据结构支持序列化与校验，单行上限（2048 字符）及格式畸形均被安全拦截；
- [x] 契约层 JVM 单元测试覆盖率达到 100%，所有测试在既有构建中全绿通过。

## Test strategy

- JVM 纯单元测试：
  - `DisplayControlContractTest`：验证模式协商、状态监听与尺寸重协商契约；
  - `BridgeControlDisplayTest`：覆盖合法/非法 wire 格式、边界数值、畸形输入与参数解析；
  - `InputEventSerializationTest`：验证各输入事件编码/解码一致性与边界拦截；
- 运行 `./gradlew :runtime:contract:test` 确保零回归。

## Prototype/research dependency

依赖 `docs/research/2026-10-05-wayland-mobile-graphics-channel.md` 与 ADR-0017 设计规格。

# 42: AVF 硬件虚拟化 vsock 高速图形传输通道与双引擎对齐

Type: implementation  
Status: ready-for-agent  

## What to build

在 `:runtime:engine-avf` 与 AVF 执行策略中补齐基于 vsock 的专有高速图形数据通道（端口 9103），实现硬件虚拟化档与 QEMU 软模拟档在图形契约与用户体验上的全面对齐，遵循 ADR-0002 与 ADR-0017。

主要包含：
1. **vsock 专有图形通道（端口 9103）集成**：
   - 在 AVF VM 启动规约中分配并监听 vsock 端口 9103（`AVF_GRAPHICS_VSOCK_PORT = 9103`）；
   - 在宿主侧实现 `AvfWaylandChannel`，对接 NDK `ANativeWindow` 双缓冲硬件渲染器；
   - 在 Guest 侧由 `droidlayer-display-agent` 接入 vsock 9103 端口，向宿主持续流式传输渲染帧；
2. **双缓冲乒乓传输与脏矩形按需刷写优化**：
   - 实现 Front Buffer 与 Back Buffer 双缓冲交替传输机制（Ping-Pong Buffer），确保 60 FPS 满帧刷新时内存传输延迟 $< 3\text{ms}$；
   - 支持脏矩形（Damage Dirty Regions）按需刷写：在画面无变化（静态桌面）时暂停推流，降低整机功耗与总线占用；
3. **双执行策略能力矩阵全面对齐**：
   - 在 AVF 档的 `CapabilityMatrix` 中将 `GRAPHICS_WAYLAND` 状态置为支持；
   - 确保上层 UX（`DesktopActivity` / `WaylandSurfaceView`）无感知调用底层通道；
   - 实现冷恢复与生命周期同步：在宿主前后台切换或 VM 冷恢复后，vsock 图形通道能自动重连并恢复桌面同步。

**Blocked by:** 37, 38, 41

## Acceptance criteria

- [ ] AVF 虚拟机通过 vsock 端口 9103 稳定传输桌面帧与输入事件，连接握手与鉴权严密可靠；
- [ ] 传输吞吐达到 1080p 60 FPS 刷新要求，端到端画面刷新延迟 $\le 30\text{ms}$；
- [ ] 脏矩形按需刷写机制生效，静态桌面时 GPU 渲染与 vsock 吞吐自动回落，CPU 占用率 $\le 2\%$；
- [ ] 契约测试集（`DisplayControl` 与图形通道测试）在 QEMU 与 AVF 两个执行引擎上 100% 全绿通过；
- [ ] 用户在支持 AVF 的设备上启动图形桌面，享有完全一致且更流畅的近原生体验。

## Test strategy

- vsock 图形数据通道性能与吞吐基准测试：压测连续推送 1080p 帧，检测丢帧率与内存使用情况；
- 契约对齐测试：对 AVF 引擎运行完整的 `DisplayControl` 契约套件与尺寸重协商测试用例；
- 硬件真机冒烟测试：在具备 AVF 权限的 Android 设备（如 Pixel 或天玑 9400 机型）上启动 Debian/Alpine 桌面，测试多窗口操作与浏览器网页浏览。

## Prototype/research dependency

依赖 Ticket 37（AVF vsock 桥接基石）、Ticket 38（契约定义）与 Ticket 41（Guest 侧代理实现）。

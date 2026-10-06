# 46: Android 宿主剪贴板双向同步桥接器与指针捕获

Type: implementation  
Status: ready-for-review  

## What to build

在 `:app` 中实现 Android 宿主剪贴板双向防环同步桥接器（`ClipboardBridgeManager`）、物理鼠标相对指针捕获处理器（`PointerCaptureHandler`）以及文件拖拽暂存监听器（`FileDropTargetListener`），遵循 ADR-0018。

主要包含：
1. **剪贴板双向无损防环同步桥接器（`ClipboardBridgeManager`）**：
   - **前台焦点驱动监听**：结合 Activity 声明周期与窗口焦点（`onWindowFocusChanged`），仅在前台获得焦点时激活 `ClipboardManager.OnPrimaryClipChangedListener`；后台自动注销，完全规避 Android 14/15/16 后台剪贴板限制；
   - **哈希指纹破环状态机（Echo Suppression）**：
     * 维护宿主与远端数据的 SHA-256 哈希值；
     * 接收到 Guest 端剪贴板更新时置位 `isApplyingRemote = true`，写入本地系统剪贴板并忽略随后的本地回调，100% 杜绝 Ping-Pong 死循环；
   - **安全传输与容量过滤**：单次传输上限严格限制为 $256\text{ KB}$，采用 Base64 编码包装，经由 `BridgeControl` 进行可靠的双向派发；
2. **物理鼠标相对指针捕获（`PointerCaptureHandler`）**：
   - 在 `DesktopActivity` 的桌面渲染视图中接入 Android API 26+ `View.requestPointerCapture()`；
   - 捕获状态下自动隐藏 Android 系统默认光标，消除屏幕边缘碰撞与触发系统手势的问题；
   - 在 `onCapturedPointerEvent(MotionEvent)` 中提取原始位移向量 $(\Delta x, \Delta y)$ 与按键掩码，封装为策略中立的 `PointerEvent` 派发至 Wayland 通道；
   - 提供平滑释放机制：当用户按下 `Ctrl+Alt` 组合键或点击顶部快捷释放按钮时调用 `releasePointerCapture()`；
3. **SAF 文件拖拽暂存分发（`FileDropTargetListener`）**：
   - 注册 `View.OnDragListener`，响应 `DragEvent.ACTION_DROP`；
   - 通过 `requestDragAndDropPermissions` 获取跨应用读取权限，异步通过 `ContentResolver.openInputStream(uri)` 抽取文件流；
   - 将文件写入共享目录暂存池（`/storage/emulated/0/Download/DroidLayer-Shared/Inbound/`），并通过 `BridgeControl` 发送通知。

**Blocked by:** 44

## Acceptance criteria

- [x] 宿主复制的文本在 50ms 内成功同步至 BridgeControl，格式完好无损，且不引发回环同步；
- [x] Guest 发送的剪贴板更新能准确写入 Android 系统剪贴板，多行代码与特殊符号保留完整；
- [x] 物理鼠标接入后能成功激活指针捕获，宿主光标隐匿，位移无边界限制，PC 级手感顺滑；
- [x] 按下 `Ctrl+Alt` 能够 100% 稳定释放指针捕获并恢复系统光标；
- [x] 拖入系统文件能够异步安全写入共享目录，不阻塞主 UI 线程，超大文件或异常中断具备容错防御；
- [x] 模块纯 JVM 测试与 Android Lint 保持零错误、零告警。

## Test strategy

- JVM 纯单元测试：
  - `ClipboardEchoSuppressionTest`：模拟本地连续复制、远端推送、同时修改等场景，断言哈希指纹防环状态机行为正确；
  - `ClipboardPayloadLimitTest`：验证空文本、多语言 UTF-8、长文本（256KB 边界）与超长包拦截；
  - `CapturedPointerEventMapperTest`：验证相对位移与各种物理鼠标按键组合的准确映射；
- UI 交互与生命周期集成测试：
  - 验证 `onWindowFocusChanged` 状态迁移下剪贴板监听器的注册与注销。

## Prototype/research dependency

依赖 Ticket 44 产出的 `BridgeControl` 剪贴板扩展，以及 `docs/research/2026-10-06-phase3-audio-clipboard-input.md` 中的剪贴板与指针捕获设计。

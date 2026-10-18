# T5 只读输入盘（宿主侧）

Status: ready-for-agent
Type: task
Priority: P2（条件票）
Blocked by: T3

条件：只有当 T3 的度量仍显示首次启动宿主准备或 initrd 内存占用是主要成本时，才进入实现；否则保持 `deferred`，不阻塞其他票。

## 交付

QEMU 启动参数可选地把 distro tarball 作为独立的只读 virtio-blk 设备暴露：

- 使用独立 drive id 与只读标志，不改变 root 盘的 `cache=writeback` 语义；
- 宿主不再需要把 rootfs 嵌入 initramfs（由 T6 完成 guest 侧消费）；
- 未启用时启动参数与今天逐项相同。

## 验收

- [ ] 参数构造测试断言只读、raw 格式、独立设备，且 root 盘参数不变。
- [ ] 未启用时与现有 `QemuLaunchSpec` 输出完全一致。
- [ ] 不引入 9p；当前打包 QEMU 已 `--disable-virtfs`。
- [ ] `:runtime:engine-qemu:check` 通过。

## 测试

- 扩展 `QemuLaunchSpecTest` / `QemuGpuLaunchSpecTest`。
- `./gradlew :runtime:engine-qemu:check`

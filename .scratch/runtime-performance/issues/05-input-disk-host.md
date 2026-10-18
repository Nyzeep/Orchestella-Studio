# T5 只读输入盘（宿主侧）

Status: ready-for-human
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

## Comments

- 状态：`deferred`（未进入实现，未创建 `perf/t5-input-disk-host` 分支）。
- 决策门证据：T3 只有 JVM 合成 fixture 数据（credentials-only segment 523 B / 全量 903 B）；本会话 `command -v adb` 不可用，没有 Ubuntu/Arch 真机启动耗时、首次启动宿主准备耗时或 initrd 内存数据。
- 结论：无法证明“首次启动宿主准备或 initrd 内存仍是主要成本”，不满足 spec 的 T5 启动条件；按 spec 保持现状，不硬做。
- 未改 `QemuLaunchSpec`、未启动 T6、未新增设备依赖。
- 后续：若设备数据证明首次启动/initrd 内存是主要瓶颈，再按 prompt 实现宿主只读 virtio-blk 输入盘；届时必须保留 T7 `QemuTuning` 的默认委托与默认 argv 等价测试。
- 报告：`docs/reports/tickets/runtime-performance-05/implementation-report.zh-CN.md`。

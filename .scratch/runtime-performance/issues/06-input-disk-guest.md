# T6 只读输入盘（guest 侧）

Status: ready-for-human
Type: task
Priority: P2（条件票）
Blocked by: T5

## 交付

guest init 优先从第二个 virtio-blk 设备读取并校验 rootfs，embedded-input 保留为兜底：

- 只读设备上的 tarball 必须按 manifest 的 size + SHA-256 校验后才允许解包；
- 校验失败、设备缺失、格式不识别时 fail，不得写根盘；
- 已装配根盘的重复启动路径不变；
- 装配完成标记 `provisioning_version=1` 与 console marker 不变。

## 验收

- [ ] smoke 证明从只读设备完成首次装配，且 marker 与今天一致。
- [ ] 篡改/截断 tarball 时在写根盘前失败。
- [ ] embedded-input 兜底仍可完成装配。
- [ ] guest 65 项回归通过；未跑项写 `not-tested`。
- [ ] 不改变 ADR-0007 的私有存储与 Export 边界。

## 测试

- guest smoke：`qemu-official-alpine-embedded-input-smoke.sh` 同类场景 + 只读设备变体。
- guest 回归脚本与 `guest-assets/build.sh` 产物校验。

## Comments

- 状态：`deferred`（未启动，未创建 `perf/t6-input-disk-guest` 分支）。
- 理由：阻塞于 T5；T5 因缺少设备数据按 spec `deferred`，因此本票不启动。
- 未修改 `guest-assets/initramfs/*`、未改宿主 Java/QEMU 参数、未跑 guest 65 项回归。
- 后续：只有 T5 实现并合入后才按 prompt 启动；届时覆盖只读设备正常装配、篡改/截断 fail-before-write、embedded-input 兜底与 65 项回归。
- 报告：`docs/reports/tickets/runtime-performance-06/implementation-report.zh-CN.md`。

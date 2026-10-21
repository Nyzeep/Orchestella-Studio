# P08: QEMU TCG 调优矩阵扩展（零设备）

Type: implementation
Status: implemented-zero-device（默认 argv 不变；真机 A/B 待 DV-03）
Priority: P1
Owner: QEMU/performance owner
Blocked by: None（零设备）；真机 A/B 由 DV-03 执行
External gates: 无

## 目标

在现有 `QemuTuning` 基础上增加可单变量验证的候选参数：

- `-cpu max,sve=off,pauth-impdef=on`；
- `tb-size`（MiB；修正现有 `tbSizeKb` 命名/单位混淆）；
- 每盘 iothread、`num-queues=vCPU`；
- `aio=threads`；
- `discard=unmap`、`detect-zeroes=unmap`；
- `-object rng-random` + `virtio-rng-pci`；
- `-overcommit mem-lock=off`；
- guest `mitigations=off`、`elevator=mq-deadline`（单独变量）。

默认值不变；`cache=unsafe` 永不接受。

## 零设备范围

- 纯 Java argv 构造/快照测试；
- 默认路径逐字节等价；
- 不要求设备；不把参数构造成功当性能结果。

## 行为验收

- [x] `QemuTuning.DEFAULTS` 输出与当前默认逐字节一致。
- [x] 每个候选是独立开关；非法值拒绝；不出现重复 `-cpu`/`-accel`。
- [x] `cache=unsafe` 被测试禁止。
- [x] `tb-size` 单位/上限明确（MiB）；文档不再写 Kb。
- [x] 矩阵脚本能生成 n≥10 样本表（设备列 `not-tested`）。
- [x] 与 passt/gvproxy/VirGL 现有参数兼容；不改变网络/GPU 默认。
- [x] 回归测试：旧调用签名委托到默认值。

## 联网调研

- Podroid `QemuEngine.buildCommand()`/`SettingsRepository`；
- QEMU 11.1.1 文档：`-cpu max`、`tb-size`、iothread、`aio`、`discard`、`detect-zeroes`；
- Termux `qemu-system-aarch64-headless` 构建参数；
- 本仓 T7 矩阵/报告。

## 交付

- 扩展 `QemuTuning`/`QemuLaunchSpec` + tests；
- 矩阵脚本新增变体；
- `not-tested` 报告模板；
- 默认不变的证据。

## 停止条件

- 参数在 QEMU 11.1.1 不可用：移除候选，记录失败。
- 没有设备数据：保留 `not-tested`，不改默认。

## Comments

- 2026-10-21 / Agent D：零设备实现完成于 `feature/fastlane-qemu-observability`。`QemuTuning` 新增 builder 与 iothread/num-queues/rng/overcommit 开关；`tbSizeKb` 更正为 `tbSizeMib`；`aio=native` 因 QEMU 要求 `cache.direct=on` 而拒绝；RNG 用 MMIO `virtio-rng-device` 而非票面 PCI，理由已写入报告。默认 argv 用旧/新两次编译 + `diff -u` 验证 47 项逐字节一致；`QemuTuningTest` 16 场景 OK；矩阵 11 Python tests OK，dry-run 18 变体 × 6 workload = 108 行全部 `not-tested`。
- 未验证：无真机、无 QEMU smoke boot、无 n≥10 样本；iothread/num-queues/RNG/CPU max 的行为只到源码/文档级。DV-03 必跑。
- 明确未实现：guest `mitigations=off`/`elevator=mq-deadline`（本会话 brief 未列；`mitigations=off` 是安全削弱项，需单独安全评审，不混入性能矩阵）；RNG 票面 `virtio-rng-pci` 改为 MMIO `virtio-rng-device`（与现有设备模型一致）。
- 报告：`docs/reports/tickets/fastlane-P08/implementation-report.zh-CN.md`；矩阵模板：`docs/reports/tickets/fastlane-P08/matrix-dry-run.tsv`。

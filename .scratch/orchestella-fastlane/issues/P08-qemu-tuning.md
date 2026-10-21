# P08: QEMU TCG 调优矩阵扩展（零设备）

Type: implementation
Status: ready-for-agent（zero-device）
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

- [ ] `QemuTuning.DEFAULTS` 输出与当前默认逐字节一致。
- [ ] 每个候选是独立开关；非法值拒绝；不出现重复 `-cpu`/`-accel`。
- [ ] `cache=unsafe` 被测试禁止。
- [ ] `tb-size` 单位/上限明确（MiB）；文档不再写 Kb。
- [ ] 矩阵脚本能生成 n≥10 样本表（设备列 `not-tested`）。
- [ ] 与 passt/gvproxy/VirGL 现有参数兼容；不改变网络/GPU 默认。
- [ ] 回归测试：旧调用签名委托到默认值。

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

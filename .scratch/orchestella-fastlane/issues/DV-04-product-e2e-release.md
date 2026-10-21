# DV-04: 产品端到端、ACR-03 与发布门（串行 4）

Type: device-verification / release-gate
Status: blocked-by-DV-01/02/03
Priority: P0（产品化）
Owner: 主协调/产品
Blocked by: DV-01, DV-02, DV-03, ACR-03, P07, P10, P11
External gates: ACR-03 用户裁定；P0-1 生产签名；发布负责人

## 目标

在所有零设备/真机模块通过后，决定 Fast Lane 是否进入实验开关/产品：

- ACR-03 通过（第三 Runtime 类别/能力矩阵/存储/网络/回退）；
- UI/向导/错误文案/徽章真实；
- Lifecycle/stop/cold restore/export/import 端到端；
- P0 发布门（签名、CI、输入、GPL）不被降低；
- 失败/回退可见，不静默。

## 步骤

1. 端到端：创建 Fast Lane 实例 → 终端 → 工具 → 数据 → 停止 → 冷恢复。
2. 选道：Full/Fast/Auto 在 protected-only、无 AVF、权限拒绝、能力不足下的行为。
3. 回退：Fast Lane 不可用 → Full Lane + 原因；不静默。
4. 数据：导出/导入/删除/升级；host 无明文。
5. 安全：debug NONE、无 token/按键/源码日志；encrypted storage 不可读。
6. 发布门：非 Debug 签名、16KB、NOTICE/对应源码、CI 绿。
7. 文档/发布说明：能力矩阵、限制、实验开关。

## 验收

- [ ] ACR-03 已裁定；UI/文档与能力矩阵一致。
- [ ] 端到端无阻断；stop/cold restore/export/import 可恢复。
- [ ] Fast Lane 不可用时显式 Full Lane；无静默降级。
- [ ] 数据删除/导出语义与 UI 一致；host 无明文。
- [ ] P0 发布门全部满足；Fast Lane 不成为跳过签名的理由。
- [ ] 真实 AVF Guest 成功样本、性能样本、失败样本可复查。
- [ ] 未通过项标 `not-tested`，不写成“支持”。

## 证据

`protected-fastlane/20261021/dv04/`；含：
- ACR-03 裁定记录；
- 端到端步骤/截图（无敏感数据）；
- 选道/回退矩阵；
- 数据导出/导入/删除记录；
- P0 签名/CI/16KB/NOTICE 证据；
- 发布说明草稿。

## 停止条件

- ACR-03 拒绝第三类：Fast Lane 停止，QEMU-only；non-protected 未来线保留。
- 端到端需要违反 Full Linux/数据/无 Root 边界：停止。
- P0 未满足：不发布；Fast Lane 不例外。
- 任何能力与真机不符：标 UNSUPPORTED，不静默。

# 设备窗第三轮规格 · ACR 收口（2026-11-04）

- 基线：main `777827e`，工作树干净；设备 device-mey（HONOR MEY_AN00 别名口径，Android 16/SDK36，4K 页，55% 电量 35°C 充电中）
- 任务来源：上一轮建议（W-FAB-03 + ACR 议程定稿 + 设备窗项）+ 记忆排队项「下窗 t9-matrix.sh 10 1 补 smp4/8」
- 纪律：设备道串行（排程互斥）；联网取证带引用与四级置信；tracker 对账必须 gh/git 实证

## 0. 开工改判（本轮侦查结论）

1. **PVM-09 已实现**：`7704203`+`c034f35` 在 main，报告在库，session 18/19 在其上构建——票却停在 `ready-for-agent`。按票面把它选为「下一开发任务」的计划作废。
2. **PVM-01/02/04/05/07/08 六票状态全部过期**：对应 PR #19/#20/#21/#22/#23/#24 均 MERGED（gh 实测），PVM-08 连真实 .so 复验都已全绿。整线 tracker 债与 Q5「状态没回写」同型。
3. **PVM 线零设备开发票实际清空**：本轮回锚为 **ACR 收口轮**。

## 1. 车道

| 道 | 形态 | 内容 |
|---|---|---|
| T9 设备道 | 后台代理，串行占设备 | F-MTTCG-C200 smp4/8 各 n≥10（smoke 先行、digest=`5f94de57…` 硬判据、温度/电量熔断）；证据 `evidence/uml-fabric-20261104/t9-smp4-8/` |
| R4 联网道 | 后台代理 | W-FAB-03 OEM 普查 → `docs/research/2026-11-04-wfab03-oem-survey.zh-CN.md` |
| 主会话 | 对账 | 七张 pvm 票 Status+Comments 回填（gh 实证）；ACR §8 就绪度对齐现实；轮报告 |

## 2. 边界

- 不写产品代码（ACR 未投票）；不动 UML fabric 的 Out-of-scope 约束。
- 设备只跑 T9（不装 app、不动 orch.apk 以外资产、推送须 md5 对账）。
- 普查自有设备一律 device-mey/device-myron 别名口径，禁止序列号/真实型号。

## 3. 验收

1. 七票回填每条带 gh 合入时间戳；ACR §8.2 每项状态有来源
2. W-FAB-03 矩阵覆盖 ≥8 OEM 行、引用带访问日期、无敏感泄漏（grep 序列号=0）
3. 敏感门区间扫描 + `git diff --check` 过；T9 结果到达后二次提交（证据 + ACR §8.1 行 + 轮报告）

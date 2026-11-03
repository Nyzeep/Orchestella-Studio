# 设备窗第三轮（device-mey）· ACR 收口——tracker 对账、W-FAB-03 普查、F-MTTCG smp4/8 定案

- 日期：2026-11-04（设备执行窗口 01:00–07:42 设备本地时间，单会话串行）
- 基线：main `777827e` → 本轮两个推送 `803f097`（对账半）+ `b39e8e6`（敏感门）→ 本文随 T9 定案提交
- 设备：device-mey（HONOR MEY_AN00，MagicOS，Android 16/SDK36，4K 页，8 核，USB 供电全程）；电量 55→60%，电池温度 34–38°C（43°C 熔断门未触发），Battery Saver OFF
- 输入：上一轮建议（W-FAB-03 + ACR 议程定稿 + 设备窗项）、记忆排队项「下窗 t9-matrix.sh 10 1 补 smp4/8」、规格 `.scratch/round-20261104-device-window/spec.md`
- 纪律：设备道串行排程互斥；联网取证带引用与四级置信；tracker 对账必须 gh/git 实证；本轮无产品代码改动（ACR 未投票）

## 0. 判定总表

| 道 | 缺口 | 落地 | 验证 |
|---|---|---|---|
| 对账道 | pvm-compiler 七票状态过期（PR 早已 MERGED）；PVM-09 被票面误判为「下一开发票」 | 七票 Status+Comments 回填（gh 实证）；issues 全目录 14 票 + 线 spec/决策地图入库 | `gh pr view` state=MERGED 逐票留痕；报告在库核验 |
| 联网道 | ACR §8.2 缺项 5（W-FAB-03 OEM 普查） | 9 OEM × AVF/16K 判定矩阵（21 组引用）→ `2026-11-04-wfab03-oem-survey.zh-CN.md` | 轨道 A 判词无反例；T13 获公开互证；序列号/真实型号 grep=0 |
| T9 设备道 | ACR §8.2 缺项 7（F-MTTCG smp4/8 n≥10） | smp4/smp8 各 n=10，23/23 digest 逐字节一致 | **「最优宽度=2 vCPU」升格定案**（§7.5 追记）；S(8)<1 手机宿主首个宽原子反噬实证 |
| 门禁 | 普查引用触发 token_shape 误报 ×2 | 敏感门增 URL 段豁免 + 三自检用例 | `--self-test` 与区间扫描复绿 |

## 1. 开工改判

按上轮计划把 PVM-09 选为下一开发任务，开工侦查发现其**早已实现并合入 main**（`7704203`+`c034f35`，报告 `docs/reports/tickets/pvm-09/`，session 18/19 在其上构建双包拆分）。扩大对账：PVM-01/02/04/05/07/08 六票同样过期——PR #19/#20/#21/#22/#23/#24 于 2026-10-24 全部 MERGED，PVM-08 连真实 .so 复验（140.7MB APK、6 `.so` 全 STORED+16KB 对齐、PAYLOAD_APK_OK）都已绿。**pvm 编译线零设备开发票实际清空**，本轮重锚为 ACR 收口轮。此为「状态没回写」债的第二次整线爆发（第一次见 2026-11-03 质量收敛报告 Q5）。

## 2. tracker 对账（已随 `803f097` 入库）

| 票 | 旧 Status | 新 Status（依据） |
|---|---|---|
| PVM-01 | ready-for-human（PR #21 待审核） | implemented（#21 MERGED 10-24T09:23:53Z） |
| PVM-02 | ready-for-human | implemented（#19 MERGED 10-24T07:49:43Z） |
| PVM-05 | ready-for-human | implemented（#20 MERGED 10-24T07:54:16Z） |
| PVM-07 | ready-for-human（PR #22 待审核） | implemented（#22 MERGED 10-24T09:24:21Z） |
| PVM-04 | ready-for-review | implemented（#23 MERGED 10-24T13:14:25Z） |
| PVM-08 | ready-for-review（复验在收尾） | implemented（#24 MERGED 10-24T13:14:54Z；复验早已全绿见原 Comments） |
| PVM-09 | ready-for-agent | implemented（`7704203`+`c034f35` 在 main；真机项归 DV-PVM-02b） |

另：issues 目录全量 14 票 + `spec.md` + `决策地图.md` 首次入库（此前仅 4 张票在版本库）。

## 3. W-FAB-03 · OEM 可用性普查（联网道）

`docs/research/2026-11-04-wfab03-oem-survey.zh-CN.md`（134 行，9 OEM 行 + qvirt/Gunyah/GenieZone 替换形态专节，21 组来源带访问日期与四级置信；github/authority/荣耀论坛等直连全文，source.android.com 等经缓存片段取证并就低标注置信）：

1. **轨道 A 判词不修订**：「Play 渠道第三方无 pVM 生产通道」复核无反例（Termux 官方仓 2026-02 issue + koiTerminal/U-AVF/Podroid 全走 `adb pm grant` 开发通道）；覆盖面上修——AVF 管理面在 Pixel/三星 One UI 8/小米 HyperOS 3 的 2025–26 旗舰在位（QTI 机型 protected-only），**HONOR MagicOS 10 是唯一公开可复现的「AOSP AVF 整体删除、vendor qvirt 替代」F1 形态**。
2. **轨道 B 收敛三条芯片线**：Tensor G1+ / Exynos 2500+ / Dimensity 9400+（Qualcomm 发言人原话确认无 non-protected 计划）——UML「不依赖厂商放行」定位反向加强。
3. **16K 平台级判词**：截至 Android 17 beta 无出货设备 16K 默认；开发者选项仅 Pixel 8/8a/9 Pro XL(beta)/10；非 Pixel OEM 零公告——双设备 4K「测不了」升级为平台事实。
4. 置信最低三格：vivo OriginOS 6（转述未复现）、Motorola（零证据）、Samsung QTI 机型 service 在位性（Terminal 报错反推）。
5. T13 负样本获荣耀官方论坛公开互证（Magic7 Pro/MagicOS 10，2025-09-27 帖）。

## 4. T9 · F-MTTCG-C200 smp4/smp8 补列（PASS）

**协议还原**：`qrun.sh` 列模式逐字还原（设备副本 md5=仓库副本；`qemu-system-aarch64` md5 与打包 `libdroidlayer_qemu.so` 同源比对通过；`-machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57 -smp N -m 6371M`，guest 内 `make -j N` + `date +%s` 计时）。smoke 轮 digest 与上轮 `5f94de57…` 逐字节一致后放行。

**数据**（主指标 guest 内墙钟，200 文件 × clang -c -O1）：

| 列 | n | 中位 (s) | IQR (s) | 备注 |
|---|---|---|---|---|
| ctl smp1（同窗对照） | 1 | 1010 | — | 与 round-2 中位 1014.5 差 0.4%，跨窗口径有效 |
| smoke smp4（协议门） | 1 | 977 | — | 热起始受扰上界，不计入正式 n |
| **smp4** | **10** | **802.5** | 354（Q1=627, Q3=981） | 三簇分布（快 593–627 / 中 764–846 / 慢 981–1019） |
| **smp8** | **10** | **1258.5** | 109（Q1=1168, Q3=1277） | r9 超 1300s 触发重试规则，重试 1254s 计入、原始轮存档不计 |

**派生判据**（04 文档 §5.4 阈值）：S(4)=**1.26**（E=0.31）——「4 列有效扩展」未达标；S(8)=**0.80**（E=0.10）——**反噬**（<1，慢于 smp1）；S(4)/S(2)=**0.79**——回退讨论条款命中。smp4↔smp8 完全分离（精确 Mann-Whitney p≈1.1e-5）。23/23 列次 `.o` digest 一致（含 smoke 与对照），零样本作废。

**跨设备形态**：myron S(4)=1.42/S(8)=1.12@n=3 vs mey 1.26/0.80@n=10——同向衰减、mey 更陡。**§5.4 回退条款两台设备独立命中 → 「QEMU 车道 vCPU 记账最优档=2」从工作假设升格定案**（ACR §7.5 已追记）；S(8)<1 为手机宿主首个宽原子反噬实证（宽原子回退串行化的预测方向被真机证实）。

**事故与边界（如实）**：v1 驱动解析器 CRLF 缺陷把有效 smoke 误判为 digest 漂移并中止——console 逐字节复核相等后 v2 修复续跑，两版脚本与判定均存档标注；smp1 对照 n=1 故 smp4/8 未做配对 MWU（仅 smp4↔smp8）；翻译税/C-nat 列、三簇分布的调度成因（平台频率档位假设）标注 not-tested。原始证据（仓库外）：`evidence/uml-fabric-20261104/t9-smp4-8/`（SUMMARY、raw、23 console logs、驱动脚本）。

## 5. 门禁证据

| 门 | 结果 |
|---|---|
| 敏感门自检（含新 URL 豁免三用例） | `SENSITIVE_DATA_OK` |
| 敏感门区间扫描 origin/main..HEAD | 首扫 `token_shape ×2`（普查推文 URL 编号误报）→ 门禁增 URL 段豁免（`b39e8e6`）→ 复扫 OK |
| 普查文档敏感 grep（序列号/真实型号） | 0 命中，自有设备一律 device-mey/device-myron 别名 |
| `git diff --check` | 通过 |
| T9 温度/电量熔断 | 未触发（电池 34–38°C，z1 稳态 39–45.6°C，开局一次 70°C 瞬态记录在案） |

## 6. 未验证项与下窗入口

1. **16K 宿主**：唯一需要设备的技术缺项——且普查证明公开通道仅 Pixel 系开发者选项；下窗若得 Pixel 8/10 级设备则走 G2（含 stub_exe-16k 行为等价）。
2. **distcc COMPLETED@36**：blocked by DV-01 SELinux `execute_no_trans`（外部）。
3. **W-FAB-03 设备实测扩样半边**：普查低置信三格（vivo/Motorola/Samsung QTI）留真机窗口实机 `service list` 取证。
4. **同夹具四列（G3）**：proot/pVM/QEMU/UML 同轮数据，§6 自限项。
5. smp4 三簇分布与 smp8 反噬的调度级归因（频率档位/宽原子）：标注 not-tested，不影响记账定案。

## 7. 回填

- 工单：pvm 七票回填 + issues 全目录入库（`803f097`）；uml-fabric map 本轮增量已由本文承载，T9/W-FAB-03 行随下次 map 回填并入
- ACR：§8.1 增两行（对账半/T9 定案）、§8.2 就绪度刷新、§7.5 定案追记
- 门禁：`tools/ci/check_sensitive_data.py` URL 豁免 + 自检（`b39e8e6`）
- 报告：本文 + `2026-11-04-wfab03-oem-survey.zh-CN.md`
- 本地不入库：侦查与驱动笔记（含本机路径）、`evidence/uml-fabric-20261104/`（仓库外证据目录）

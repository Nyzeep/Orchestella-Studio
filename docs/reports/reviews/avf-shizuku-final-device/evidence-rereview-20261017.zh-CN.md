# 最终证据比较窗口定点复核

结论：**approve（限定批准）**。EVIDENCE-01 已由主协调的文档更正闭合。当前12份文档的 bytes/SHA-256 全部匹配新的 final-evidence-correction seal；每份 before→after 均只有指定比较窗口的一次替换，before/after 及 manifest/patch 的 preservation 孪生均字节一致。没有其他必须更正项。

## 背景与范围

首审指出：两份实例ID/根盘引用 hash 记录都在 force 停止之后，不能表述为“force前后”对照。主协调已更正12份文档，并要求另出本次复核报告和 checks，保留首审 request-changes 历史。

开发根为 `PROJECT_ROOT`。S 为该根的 `Orchestella_dev`；R 为 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/resume-01`。本次依据为 R/final-evidence-correction/delivery-manifest.json，SHA-256 为 `60bfdf3f08dacebf2532b605ddd4a6c21acb4290665e184157f6d29161ec98b0`，以及其固定 before/after、evidence-window.patch 与 preservation。

本次仅读取上述 seal 指定的12份当前文档和更正快照，核对大小/hash、精确替换及保全副本；没有重读治理、19原件全套、其他审查轴或生产代码。12份文档包括 scope/map/spec、issues 11/12/17/18、13/17/18实施报告、研究文档及正式编排报告。此次对新增六份文档也只核同一句更正及 seal，未扩大为全文审查。

## 定点核对与发现闭合

| 检查 | 结果 |
| --- | --- |
| 当前12份文档与新 seal 的 bytes/SHA-256 | 12/12一致 |
| before/after 快照与新 seal 的 bytes/SHA-256 | 12/12一致 |
| 当前文档与 after 快照 | 12/12字节一致 |
| 只做一次指定比较窗口替换 | 12/12成立 |
| before/after preservation 孪生 | 24/24字节一致 |
| manifest 与 patch preservation 孪生 | 2/2字节一致 |

唯一替换为将“force前后实例ID及根盘引用hash”收窄为“force后停机记录与重启后最终停机记录的实例ID及根盘引用hash”。保留其后的“一致/相同”用词没有改变比较对象。

该语义与首审已读的 force-stop-state-selected.json 和 final-terminal-state-audit.json 相符：前者为 force 后停机记录，后者为重启并最终正常关机后的记录，两者的实例ID和根盘引用 hash 相同。更正不再声称已采 force 前 hash，也没有新增实测主张；正常/force 重启后的持久标记结论继续沿首审原件。**EVIDENCE-01/P2：closed。**

更正仅改变上述一句话，因此矩阵和第九节原有未验门未受更动。旧 tracker-qemu-update/closeout 与 source seals 继续作为历史封板保留，新 seal 承载本次文字更正。本次没有重写任何旧封板，也不要求补测试或设备操作。

## 批准边界与保留限制

批准仅覆盖固定物理证据和更正后报告真实性，不是新代码审查、整阶段完成、AVF闭环或性能批准。精确成功 stop owner exitCode 未独立记录；force UI 复用正常关机文字只能支持 intentional stop；routine console 全程计数、逐 event exactly-once、native failed/recovery 未验；重复 repository-refresh 阶段名不是 event ID。QEMU结果不证明AVF/性能，GPU标签不是GPU证据。其余竞态、通知完整矩阵和VM冷恢复门继续保留。

未完成内容为上述原有未验门，本次不追加无关测试。分支/基线沿首审关联，未重新核 Git；本次无 commit、branch/ref 操作、模型切换或额外委派。没有设备/adb、Gradle、签名、测试重跑、网络、生产/工单/正式编排报告修改。无新增权限或暴露面，不触发 Architecture Change Policy。

## 输出与保全

本次仅新增 R/final-evidence-review/rereview.zh-CN.md、rereview-checks.json，及 R/preservation/final-evidence-review/同名孪生；正式报告为 S/docs/reports/reviews/avf-shizuku-final-device/evidence-rereview-20261017.zh-CN.md，与本报告同字节。

现有 review.zh-CN.md 中首审 request-changes 原因及历史结论、checks.json 中的 first_review 对象和既有正式 evidence-review 均保持本次开始时字节，不因独立复核改写。新 checks 登记既有文件的前后 hash 及新输出指针。保全归最终证据 reviewer，Class B 至主协调/维护者验收归档，不自动清理，无 C: 或系统资源写入。后续动作仅为主协调在 final-delivery 审计引用本结论。

报告人：最终证据 Reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-17（本批报告日期） ｜ 耗时：未可靠计量

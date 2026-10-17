# 22: 有界离线 typed 阶段 recorder 与合成行为回归

Type: implementation
Status: completed-offline-reviewed（仅本票有界离线工具）
Owner: Worker E / 主协调
Parent: 04；为09准备，不解除07/09/10真实设备门
Blocked by: None（离线范围已完成）；生产Android时钟/阶段接线/真实receipt由原owner和07/09另验

## 先证据后建票

2026-10-16 Worker E 按现scope与主协调确认读取现04工具及B阶段设计。现有validator有16门与原件校验，缺独立typed采集seam。首个公开接口 `StageRecorder.mark/finish/snapshot` 的 red-disabled 实际exit1/ModuleNotFoundError；最小实现后 green-disabled exit0。下一片 red-marker 实际exit1（ACTIVE不等于RECORDED），green-marker两项exit0。原件位于本批 `benchmark/evidence/{red,green}-{disabled,marker}.*`，即时preservation。它们是新工具接口缺失的证据，不是Android/AVF故障。

## 用户价值与允许范围

在 `tools/device-validation/avf_stage_recorder.py` 和独立测试内建立有界、固定字段、无secret载荷的离线采集状态机，复用 `avf_benchmark.CHAIN/SOURCES` 与 `avf_evidence_io.EvidenceStore`，不重造preflight/validator/统计器。Python本地monotonic与注入合成clock如实标记，输出仅offline_stage_trace，永久不生成benchmark receipt、manifest或成功device sample。

不写launcher/MainActivity/Guest/输入/版本/VMowner；不adb/UI/Gradle/设备启停/安装/grant/commit/ref/worktree/reset/clean；不网络发送。生产插桩符号与动作包交原owner/主协调。现单reader完成认证的固定路由才可提供agent/QA/services标记；组件只验证typed标记顺序，不能认证原物理事实。缺QA通路保持缺门，不解析终端正文或新开reader。

## 行为验收

- [x] 关闭时不读clock、不调用sink、不保留identity/事件，不建队列/文件；默认Python clock不冒充Android elapsedRealtimeNanos。
- [x] 严格typed stage/身份、owner/session/generation/VM、单调非负整数时间、固定字段、事件/字节限额；无正文、path、token或任意exception message。
- [x] 错代/错owner不采集；取消/失败/回退/终态迟到无效，无自动重试或后台队列。
- [x] 认证/QA/services只接受固定单reader认证路由标签；普通owner标记拒收；kernel not_observed、payload not_applicable。
- [x] 缺门/乱序/错clock/stop未确认不产完整trace；正常stop需同一身份正常关机确认、owned停止与资源回收独立typed事实。即使完整也只有COMPLETE_OFFLINE。
- [x] sink失败即停、不假完整、不泄露错误正文；现EvidenceStore保全和不覆盖输出复用；所有输出双份且不新.log。
- [x] 原100项保留并实际回归；新增用例每片red→green，最终来源/命令/exit与中文报告齐全，独立review由主协调另派。

## Seam、测试与剩余门

沿用scope§测试默认、spec验证工具公开接缝和本次授权，无需常规seam再访谈。测试经StageRecorder公开接口和显式EvidenceStore sink，clock/sink仅在系统边界注入；所有fixture为合成。Python3.12.9，运行入口与实际命令保存在本批 `benchmark/run-recorder-checks.py` 和 `evidence/<label>.json`。测试临时根均本批benchmark/test-runs，不默认C:temp，禁止bytecode源码产物。

真实Android clock源、launcher/bootstrap/manager/run/vsock/Bridge/QA/services/stop接线、FD/reader实际收束、07闭环、09同资源基线均未运行；真实样本0。10无数据不挑优化。工具通过不能关闭这些门。

最终收口：原100项+新增27项，127 tests/17.021s/exit0，0失败/错误/跳过；原件benchmark/evidence/final-tools-clock-fix.*及preservation。早期26/126项回归保留，后续补falsey callable clock独立red→green，默认clock改为仅None时选择，不累计覆盖。核心16片有red/green，其余既定边界扩展如实另述。八个offline场景的事件/终态在benchmark/fixtures/，全部synthetic、无真实receipt；最终11份文件清单/保全核验在benchmark/closeout/。组件要求单调用者串行和sink及时返回，不承诺阻塞sink总deadline；生产Android适配与独立review未运行。

## 输出、回滚、许可与架构

正式报告：`docs/reports/tickets/avf-shizuku-22/implementation-report.zh-CN.md`；原始证据与before bytes/hash：外部 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/benchmark/`，每项即时preservation。Class B长期保全至维护者验收归档，无自动清理。无外发或新第三方依赖，自研GPL-3.0-or-later/标准库。回滚仅本票工具与文档，保留所有历史失败/用户数据/Class A；不触发Architecture Change Policy，不批准新owner/IPC。

## 2026-10-16 resume-01 最终证据收口

最终基准为E/benchmark/closeout/delivery-manifest.json及evidence/final-tools-clock-fix：127=原100+新增27项，exit0，0失败/错误/跳过，早期126与falsey-clock red/green保留不累加。22/spec-review-20261016和standards-review-20261016均approve（离线冻结范围），本票独立审查不再未运行，离线范围可以完成。

组件仅offline_stage_trace/synthetic_seam，COMPLETE_OFFLINE、Python或注入monotonic、real_receipt_count0；不认证物理事实，不承诺阻塞sink总deadline/并发安全。生产Android clock/launch/Bridge/QA/services/stop/FD/reader真实接线仍未实施；现真实偏序和固定QA来源缺口沿owner动作包，未补造receipt/时间/第二reader。07/09/10门不变，AVF样本0，无优化收益。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

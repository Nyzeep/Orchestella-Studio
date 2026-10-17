# 22 独立 Spec 审查：离线 typed 阶段 recorder

结论：**approve（仅本票离线工具范围），无新增 P0/P1/P2 发现**。最终 127 项结果来自原100项与新增27项，不累计早期126项。该结论不关闭07真实AVF、09性能样本、10瓶颈测量或生产recorder接线门。

## 审查对象与比较点

独立 Spec reviewer / Codex GPT-6.1-sol；2026-10-16。读取活动22原票、04/09/10补记、22实施报告、冻结源码/测试/README及20261016 Worker E要求；沿用已完整读取的项目policy、scope/spec和Reviewer要求。不读另一轴，不复用实施者结论。

固定比较点是用户指定dirty0.5.0/code500工作区起点及本批before；冻结终点为 `batch-01/benchmark/closeout/delivery-manifest.json`，其中11份源/票/报告快照全部匹配各自bytes/SHA256。重点逐行读取 `closeout/source/tools/device-validation/avf_stage_recorder.py`、`test_avf_recorder.py`，不以HEAD三点diff遗漏新增文件。branch/HEAD为用户给定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，无commit、ref或版本操作。

## 验收核对

| 22原验收 | 代码/证据核对 | 结论 |
| --- | --- | --- |
| 关闭零采集 | DISABLED不保留identity/clock/sink；所有操作在读clock/sink前返回；无队列/线程/文件创建 | 符合 |
| typed身份、时钟、固定字段与限额 | CaptureIdentity类型/有限alias/int64代际；Stage/Route/StopFact/Failure固定；16门序列、非负不倒退int64时间、累计32KiB限额 | 符合；falsey callable clock使用显式None判断 |
| 错代、失败、取消、回退与迟到 | 身份不匹配先拒绝，不破坏当前代；终态不复活；失败无重试/后台队列 | 符合 |
| 原认证路由 | agent/QA/services仅接受AUTHENTICATED_SINGLE_READER标签，普通owner标签拒绝 | 符合接线检查；枚举不认证物理事实 |
| 正常停止独立事实 | STOP_REQUESTED后要求同身份guest关机ack、owned VM stopped与owned resources released，缺一即拒STOP_COMPLETED | 符合离线状态机；不把标签当设备关机 |
| 缺门不完整、明确非真实性 | finish仅16门齐全返回COMPLETE_OFFLINE；kind=offline_stage_trace、provenance=synthetic_seam、real_receipt_count=0、device_verdict=NOT_DETERMINED | 符合；无benchmark receipt/manifest/device sample导出 |
| sink失败与保全 | 抛错/显式False使当前trace退役，无自动重试；异常正文不出状态；文件sink复用EvidenceStore | 符合；串行调用与及时返回sink约束已披露 |
| 原100项与新回归、报告 | 冻结测试有27项公开行为用例，原100项源码hash对应旧基线；最终原始输出127项/17.021s/OK | 符合已有执行证据，不是独立重跑 |

没有任意path/exec/payload接口、控制reader或网络操作；未把Python monotonic重新命名为Android elapsedRealtimeNanos。限额约束事件/字节及内存，不承诺阻塞sink总deadline或并发安全；这个限制在原票、README和实施报告中明确，属于本票允许的单调用者离线接缝，不作为缺失生产实现发现。

04仍completed-local，09 blocked-real-avf、10 blocked-measurement；对应补记没有把新增工具测试当AVF实测或收益。实施报告也明确当前生产事件偏序与固定CHAIN不完全相同、缺QA来源时必须保持缺门，不能回填时间或解析用户终端正文补齐。

## 测试真实性、实际完成与限制

只读 `evidence/final-tools-clock-fix.json`、stderr/stdout原件：127=100+27，exit0，0失败/错误/跳过；新增recorder项与原工具项分别出现。JSON总runner耗时与unittest自身17.021s不混用。falsey clock修复的red/green与早期26/126保留为历史，不累计。最终源hash为 recorder `7316750749039ae8d24decb0dd22a3037bb1d4b9d58126f647aa43e9d4f9fbe0`、test `7be39218e25143122a4f9d513d813b3986906e1cac7c02ba8f3515fd66e8778c`。

本reviewer未运行任何套件、设备、Gradle、签名、网络或Git命令。真实Android时钟、生产launcher/Bridge/Guest插桩、实际FD/reader收束、VM/Guest/服务/正常停止仍未运行；真实AVF样本与receipt均0。未发现不等于认证这些物理门。

## 交付、风险与边界

实际完成：独立源码/验收/原件关联审查、本正式报告、`batch-01/review-spec/22/checks.json`与报告副本，以及 `preservation/review-spec/22/` 同字节保全。修改仅本轴review报告和外部审查证据，无生产或tracker状态修改。未完成：生产接线、真实设备/AVF/性能门，由原owner和主协调后续执行。

风险是离线完整trace被误当真实成功；固定类型和文案已明确限制。后续只能在07真实闭环后，以批准的owner/session和单reader路由采集真实样本，不能由本报告放行。无scope扩大、无新权限/IPC/owner/用户数据路径或Architecture Change Policy触发；ACR-01不被本票代答。Class B，保留至维护者验收归档，不自动清理。

报告人：独立 Spec reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

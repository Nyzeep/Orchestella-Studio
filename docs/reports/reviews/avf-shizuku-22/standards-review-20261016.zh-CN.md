# 22 独立 Standards 审查：冻结离线 typed recorder

审查者：独立 Standards / Codex GPT-6.1-sol，2026-10-16。结论：**approve，仅限所列离线工具冻结范围；无确认的 P1/P2 或其他必须修改发现**。不认证设备执行，不关闭07/09/10/ACR门。

## 背景、基线与增量授权

唯一活动 effort 为 `.scratch/orchestella-avf-shizuku/`；沿用户指定 `main@428860ad9a1cb335f8be78c157b2ff57faf2a6da` 的 dirty 0.5.0/code500 工作区起点，原36文件保全。22由主协调在A/B首审期间明确追加冻结审查，不使用HEAD三点空diff，不读取另一轴结论。

外部 E 为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/benchmark/`。早期 `delivery-manifest.json` 与 `evidence/final-tools.*` 为126项版本；最终以 **closeout/delivery-manifest.json** 的精确 snapshot 和 `evidence/final-tools-clock-fix.*` 为准：127=原100+新27。最终多出的是falsey callable clock回归，不把两轮计数累加。本次11份最终snapshot及canonical均与seal匹配。

已读最终 avf_stage_recorder.py、test_avf_recorder.py、README增补、22原票和中文实施报告，定点读既有CHAIN/alias/json_bytes/EvidenceStore依赖；核最终测试source hashes、早期/最终输出与保全孪生。没有重复全套测试、真实adb/UI/安装/Gradle、网络、版本或生产修改。审查核验在 batch-01/review-standards/22/frozen-input-verification.json 及即时preservation；正式报告另有同目录保全副本。

## 硬规范逐项检查

| 规范与实际检查 | 结论 |
| --- | --- |
| DEFENSIVE内容边界、Prompt E/22的离线范围 | 源码没有设备/网络/任意path/command/用户正文接口、后台线程或队列；sink是调用者提供的同步边界。固定JSON不导出receipt/manifest/samples，未接生产。 |
| 关闭零采集 | disabled不保留identity/clock/sink，不读clock、不调用sink；所有操作返回DISABLED，测试核外部目标不存在。 |
| 身份与错代 | frozen CaptureIdentity有限alias和正int64 generation，错owner/session/generation/VM在clock/sink前拒绝，不破坏当前代。此处是合成关联，未把alias当Android caller真实性。 |
| 时间与失败即停 | 默认python_monotonic_ns、注入injected_monotonic_ns，非负int64且不倒退；仅None选择默认时钟。未知stage、乱序、坏clock、sink抛错/显式False、超限都会退役；无重试或错误原文。 |
| readiness/停止真实性 | 固定16门，认证/QA/services须认证路由枚举；STOP_REQUESTED后分别要求guest ack、owned stop、resources released三个typed事实。文档说明这些是调用者输入，不证明真实认证、正常停止或FD关闭。COMPLETE_OFFLINE、not_observed、not_applicable、real_receipt_count=0、NOT_DETERMINED均固定。 |
| 资源和保全 | 最多16事件、总32KiB，仅有限计数/状态，无事件正文缓存；EvidenceStore输出复用、独立preservation、不覆盖原件。报告准确说明单调用者串行、sink及时返回的前提，不承诺阻塞sink总wall deadline、并发安全或跨文件事务。 |
| REPORTING真实性 | 最终原始stderr为Ran 127 tests in 17.021s/OK、exit0，与seal一致；27个test方法。原126/15.510s保留。falsey-clock red exit1/AssertionError及green exit0原件保留，与最小None修复相符。所有所核raw和preservation逐字节相等。没有将mock或重复运行当物理样本。 |
| 生产/版本/许可/资产 | 原benchmark/evidence_io/test_avf_tools SHA与既有基线相同；新源码GPL-3.0-or-later/标准库，无新增分发依赖、生产owner/Guest暴露面、版本/APK变化或Class A清理。 |

文档还明确两个生产来源缺口：当前真实bootstrap/preflight偏序与CHAIN不完全相同，现Bridge没有固定QA命令入口。它们被保留为原owner后续门，没有回填时间、解析任意终端正文、新增reader或在本工具伪造来源，符合20261016 Prompt与ADR-0009/0010的边界。

## 启发式 smell：判断性，非硬违规

核过技能列出的Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest。身份字段已用domain type合并；stage/route/failure/stop facts为固定枚举，repo的离线小组件边界优先。几个公开方法重复身份/终态前置检查尚不构成有足够收益的必需抽取；不为泛化重构列发现。跳过编译/格式/工具已约束项。

## 完成内容、限制与后续

完成冻结源码、报告边界与原始测试真实性的规范轴审查；未运行Android/真实AVF、生产时钟/插桩、Guest鉴权/命令/服务/stop/FD或性能。真实样本和真实receipt均0。approve仅覆盖22离线工具，不把ready-for-review自动改completed，不批准新owner/IPC，不代Spec轴验收。

本review只写指定22正式报告、review-standards/22核验摘要和preservation；未修改tracker、生产、版本、依赖或既有工具。Commit无，branch沿用户提供main，无ref/worktree操作。资产保全无清理、无宿主系统修改/数据出站；新.log证据0。Architecture Change Policy：本工具无产品契约/暴露面变化，不触发新变更；ACR-01仍开。

残余风险为离线枚举被误当真实性信任根、sink阻塞或单调用者前提不成立。当前文档已披露，后续原owner接线与设备工单须另证，不需在本票扩大为生产框架。无必要源码修复或生产回滚。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

# 20 独立 Standards 增量复审：code502 系统重建证据签收

结论：**approve，仅追加 code502/probe3 的七项真实 Android 框架向导场景；无新增规范发现**。保留旧首审原件与源码批准，新增结论不覆盖旧失败。物理 rotation/uiMode、OEM 行为和真实创建事务仍未验，不据此关闭完整软件验收。

## 背景、scope 与固定点

唯一源码为 Orchestella_dev，沿用户给定 main@428860ad9a1cb335f8be78c157b2ff57faf2a6da 的 dirty 工作区；外部 batch 为 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`。已恢复治理、两份20261016交接/reviewer 协议、resume dispatch 和旧同轴报告；没有读取需求轴结果。

本次 D 的五份 canonical/frozen-source 与 `android-input-recreate/final-freeze.json` 全部 bytes/SHA 相符，SetupWizard/model/test 没有新生产差异。只复核新增 code502 设备证据与精确 instrumentation probe 来源，既有源码审查和本地全套不重做。核验摘要在 `batch-01/resume-01/review-standards/d502-framework-verification.json` 及即时 preservation。

## 新证据签收范围

独立逐读 `device/recreate-502-step3-acceptance.json`、`integration-502/framework-acceptance.json` 及后者七条引用原件。总体为 **8 场景 = 7 项向导 + 1 项输入**；输入由19同轴 rereview 签收，本报告只签七项向导。八条均是 user0 固定 instrumentation 原件，adb exit0 且内部 stream PASS，不从外层 exit0 或作者 all_pass 字段单独推通过。

| 向导原件 | 本次可以签收的行为 |
| --- | --- |
| recreate-502-step3-acceptance | 实际 Activity.recreate 前后均 STEP_HARDWARE，Debian/16GB/2CPU/本次RAM/控件/摘要相同。第3步身份仍为默认 root/orchestella，不将该场景描述为非默认身份恢复。 |
| recreate-502-step4-acceptance | 实际 recreate 前后 STEP_STARTUP_TASKS、Debian/16GB/2CPU/RAM、qauser/qahost、autoUpdate及控件/模型/摘要相同。 |
| recreate-502-invalid-schema | probe 在 onActivitySaveInstanceState 将 schema 改999，重建后拒绝草稿、回 STEP_STORAGE/creating=false，未触发创建。 |
| recreate-502-invalid-distro | probe 注入已移除清单ID，重建后拒绝并回 STEP_STORAGE/creating=false。 |
| recreate-502-invalid-resource | probe 注入CPU999，重建后拒绝并回 STEP_STORAGE/creating=false。此处不是运行期间物理资源变化的试验。 |
| recreate-502-creating | probe 受控设置 creating=true，重建后保留有效草稿和marker，创建按钮隐藏且 tryBeginCreation 拒绝。 |
| recreate-502-invalid-schema-creating | 坏schema和creating marker组合，重建后字段回安全默认、保持creating=true及按钮隐藏/重复admission拒绝。 |

已逐读 `framework-build/recreate-input-v3-final/source/.../RecreateProbe.java`：用真实 Activity.recreate 和生命周期回调观察新 Activity，snapshot 比较模型、控件和摘要；无按 Create、下载、VM 或真实根盘操作。creating 场景只是明确的内存 marker 注入，**没有真实在途下载/create 事务，更没有证明跨重建/进程死亡后旧任务完成、可重连或不会重复执行副作用**。这仍是20原首审保留的独立门。

probe3 两个精确输入与构建记录匹配，包内 Manifest/dex 与 unsigned 相同，signed hash 与主协调记录一致。目标隔离包 `io.github.nyzeep.orchestella.phase5` code502/0.5.2-phase5-local，SHA256 `3bd497b1d1031bfe9a86e2728a46fe16b99af8714a6289d29828845baa9f0735`；probe3 为独立包 code3。定点 ZIP 核对唯一 guest_init 和notice的既有来源 hash，不输出或解析载荷。证书/实装/采集链沿主协调既有原件，本 reviewer 未执行签名或设备工具。

全部八项原件有相同字节的保全孪生。旧501 step3/4 red、旧冻结和首审继续保留；旧报告的“502待确认”是当时事实，新原件现在支持上述隔离502框架范围。未用新副本倒填历史。

## 完成、限制与治理

完成 code502 新框架证据的独立规范签收。未完成物理旋转/uiMode、OEM系统恢复、真实创建事务跨重建/进程死亡、原包安装/完整软件UX；19的物理键盘/OEM IME也继续独立。真实AVF样本仍0，本报告无性能或VM成功主张。integration-502的746 tests/98 suites和lint0error/0fatal/748warning是主协调已运行的集成记录，本审查未重跑，不与本次八场景混算。

本次没有新增生产差异、硬违规或判断性smell，旧19/20源码approve不变。修改仅本正式 rereview 与 resume-01/review-standards 的摘要、报告副本、即时preservation。无生产、设备/adb/UI、Gradle、版本、签名、网络、ref/worktree/commit 操作，无用户凭据/用户盘访问或资产清理。branch 沿用户提供 main，Commit 无；Class A、旧APK/源码/red和用户数据保全，未需生产回滚。

后续真实事务/OEM门由主协调按独立窗口验收。Architecture Change Policy：本次只追加既有契约内验证，无新owner/IPC/暴露面，不触发新ACR；AVF ACR-01仍开，不由框架PASS关闭。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

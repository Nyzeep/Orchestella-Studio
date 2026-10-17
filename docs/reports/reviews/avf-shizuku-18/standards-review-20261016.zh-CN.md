# 18 独立 Standards 审查：自有装配日志单路输出与失败保留

审查者：独立 Standards / Codex GPT-6.1-sol，2026-10-16。结论：**approve，仅限C final-freeze中三份Guest输入的规范轴；无确认的P1/P2或其他必须修改发现**。真实Guest/PID1/journald/屏显与最终APK资源门仍须独立验收，不由受控sink计数关闭。

## 背景、固定点与范围

唯一活动effort为 `.scratch/orchestella-avf-shizuku/`，用户指定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da、dirty0.5.0/code500已保全起点。主协调随后追加C冻结包；本审查未用HEAD三点空diff、未读取另一轴结果。

外部C根为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/`，固定点 **C/final-freeze.json**、frozen-source/及before/。17份App/Guest输入均与seal、canonical和preservation孪生匹配；18仅含：

- guest-assets/scripts/orchestella-guest-provision.sh；
- guest-assets/initramfs/runtime-functions.sh；
- guest-assets/scripts/test-guest-runtime-provision.py。

已查20261016 Worker C Prompt、原12/18票、12原型报告与18实施报告、三份冻结源及有界before差异、真实report/run_step/write_state/fail_stage子进程的固定red/green和项目限定入口输出。共同规范为AGENTS/CONTEXT、DEFENSIVE全部policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING及项目code-review技能。核验摘要/原件hash位于 batch-01/review-standards/17-18/frozen-input-verification.json 及即时preservation。

## 硬规范与证据真实性

| 检查 | 结论与边界 |
| --- | --- |
| 自有来源限定 | report仍写owned log和stdout；仅owned journal模式且status=failed、真实Guest ROOT为空时额外一次直接console摘要。owned firstboot unit改为Environment=ORCHESTELLA_PROVISION_REPORT=journal、StandardOutput/StandardError=journal。没有App正则吞正文、全局quiet、关闭audit/kernel/journal或新增reader/数据面。 |
| recovery/interactive | 默认stdout单路，保留无journal时运行/失败可见反馈；未修改recovery supervisor/getty/PATH或其他发行版unit。模式不是新授权或安全开关。 |
| 失败保留 | 未改原fail_stage/write_state/run_step语义：原操作码、failed state、owned child输出、sync请求与无done marker保持。可见摘要仅固定status/stage/reason等字段，未新增token/口令/正文出口。 |
| 可维护测试边界 | 新--owned-report-only只绕过与该限定子进程检查无关的canonical准备；其他原模式仍保留prepare/check门。fixture必须显式外部根和ROOT sentinel；唯一console路径映射与append语义、controlled journal fanout均披露，不冒充实际PID1。 |
| red与green真实性 | 原普通文件覆盖语义不足已保留并勘误；有效red为red-owned-report-stream的五场景。green-owned-report summary为cases5/failures=[]；green-project-owned-report为exit0、OWNED_REPORT_TEST_OK checks=5 guestValidated=false。十份red/green case与摘要、相关原件孪生字节一致。 |
| 数字与层级 | native正常console0/0，native失败0/1；recovery正常/失败和interactive正常均1/1；每报告owned文件一份。failed子进程exit1、原操作码23、sync一次、无done。不把相同五场景第二入口相加为十个产品场景，不从静态双写推断所有设备屏显次数。 |
| canonical/构建职责 | Worker未生成init/guest_init；交主协调冻结后同步与APK唯一guest_init原bytes检查。没有改变kernel/rootfs/Guest凭据/单reader契约或公共版本/签名。 |
| 许可与资产 | 自研局部脚本/测试，无新库或参考实现复制、无下载/外发；旧dirty/并行改动、失败原件、Class A工具链/底包/旧APK和用户盘保全。 |

规范轴范围内未发现硬规范违规。当前合成运行检查的是生产report代码和受控sink关系，真实journald可用性、持久化耐久性、屏显及编辑体验不在此证据能力内；报告已明确该限制。

## 启发式 smell：判断性，非硬违规

核过技能全部12项：Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest。repo要求在owned来源端局部修复，现小范围模式判断与测试子进程不构成有证据的泛化重构需求。未提出命名/抽取/迁移偏好；工具已约束项不重复列违规。

## 完成内容、限制、修改与后续

完成三份冻结源码/before、原12/18报告与实际子进程证据的独立规范核验；本报告及外部摘要即时孪生。未重复Guest全套或执行设备/adb/UI/安装/Gradle/网络。主协调最新提供runtime/App/lint与Guest运行结果属于其集成链，本review不冒充重新运行；后续sync/依赖/APK资源门按实际原件记录。真实新QA盘native/recovery/failed、PID1/journal/console与用户编辑体验仍开。

`.phase5` code502仍待机主OEM“继续”确认，实际installed501，不能当新包设备green；不能卸载清数据绕签名。本审查无生产/tracker/版本/系统写入、commit/ref/worktree或资产清理，branch沿用户提供main，Commit无，新.log证据0。只写指定17/18正式报告、review-standards/17-18与preservation。若回滚生产，应只由原owner恢复本票限定hunk并同步canonical，不覆盖旧dirty/并行实现。

残余风险是实际journald故障或其他发行版日志仍刷console；本票不承诺终端绝无异步消息。Architecture Change Policy：只改既有owned路由和回归，无新权限/IPC/owner/暴露面，不触发新ACR，不影响AVF ACR-01未决状态。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

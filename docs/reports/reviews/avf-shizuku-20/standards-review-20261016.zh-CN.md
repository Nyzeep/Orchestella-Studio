# 20 独立 Standards 审查：冻结创建向导 saved draft 与 creating 门

审查者：独立 Standards / Codex GPT-6.1-sol，2026-10-16。结论：**approve，仅限 D final-freeze 的规范轴源码审查；无确认的 P1/P2 或其他必须修改发现**。修复后 code502+ 的真正 Activity.recreate、真实创建事务和统一构建仍未由本审查签收，20不能因此改为全票验收完成。

## 背景、scope与固定点

唯一活动 effort 为 `.scratch/orchestella-avf-shizuku/`，沿用户指定 main@428860ad9a1cb335f8be78c157b2ff57faf2a6da 的 dirty0.5.0/code500 工作区起点，旧36文件保全。主协调在A/B首审后明确追加D增量冻结审查；未用HEAD三点空diff，不读取另一轴结论。

批次根为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`，D为android-input-recreate。固定点 **D/final-freeze.json**：20拥有的 SetupWizardActivity.kt、InstanceCreationWizardModel.kt、新 WizardDraftCandidateTest.kt，以及同包19的两份source/test；五份 frozen-source 与canonical/seal相符，四份已有文件beforeSHA相符，原字节孪生相符。

已读20261016 D Prompt、15/20原票、20实施报告、D有界patch与冻结Activity/model/test，定点读现HostHardwareProfile/DistroResourceRequirements/VmHardwareConfig以核资源恢复；共同规范为AGENTS/CONTEXT、DEFENSIVE全部policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING、项目code-review技能。核验摘要在 batch/review-standards/19-20/frozen-input-verification.json 及即时preservation。此为Standards，不代Spec轴逐项验收。

## 硬规范检查与可追溯证据

| 检查 | 实际实现与边界 |
| --- | --- |
| 版本化schema与字段 | Model.saveDraft仅输出16个明确字符串字段；restoreDraft要求exact key set、schema1，严格数字/布尔/enum；当前catalog必须包含同distro/version。未知字段、缺项或过期清单被拒。 |
| 资源重验 | 当前HostHardwareProfile重验CPU/RAM/2GB宿主reserve，再按manifest重算boot minimum；磁盘2–64GB，对应现硬件范围；现StatFs/创建前空间检查仍在。恢复不自动获得更高资源预算。 |
| 秘密排除 | 白名单不保存整个userConfig、password/token、Context/Future/Executor或执行对象；不读取既存实例凭据/用户盘。新模型的未创建随机候选凭据可重新生成，不冒充已有实例凭据恢复。模型回归明确验证map无secret及候选password不同。 |
| 模型/控件/摘要 | 先恢复并校验model，再创建/绑定控件；Android自动View恢复期间applyingDraftViews阻止owned监听器回写，之后重新从model绑定。用户名/host/sudo/profile、磁盘、硬件、mirror、autoUpdate同步摘要。第1步前进前同步spinner选择，避免只把选择动作当model已切换。 |
| creating防重发 | Activity入口isCreating先拒绝、tryBeginCreation只入一次；有效saved creating恢复保持true；其他字段拒绝而原marker=true时保守保留creating并显示确认提示，不自动调用下载/create，不恢复旧Future或新建任务owner。未知旧任务结局不在此票假称续跑或已完成。 |
| 错误与数据边界 | 坏草稿仅给固定重确认提示，不打印Bundle/原始editable值；没有新增网络/归档/诊断出口、权限/IPC/FD/owner，未改Manifest/configChanges掩盖系统重建。 |
| 实验/生产分离 | 真实系统red后才提升局部生产修复；原生instrumentation probe、runner和临时输出留D根，不混入生产sourceSet；D没有设备/Gradle/版本/签名写权限。 |

原始证据逐项区分：

- `device/recreate-501-step3-red.json`、step4-red.json：adb进程exit0，但stream都是 **FAIL AssertionError: draft differs after real Activity.recreate**。第3/4步重建后回到第1步与CPU/RAM/身份漂移；报告没有把spinner操作当before model已是Debian，也没有声称全部有ID控件必丢。两份原件与preservation一致。
- `D/draft-runs/production-draft-green/junit.log.txt`：**OK (14 tests)**；仅生产model+Java依赖及新旧模型测试。包含schema/字段/清单/资源/身份/secret/creating等行为，不替代Android生命周期。
- `D/activity-compile/production-activity-final/result.json`：定点Kotlin编译exit0。初次runner缺classpath的失败被保留，未当产品red；未运行Gradle，不签完整App构建通过。
- `device/input-framework-501.json`是19框架输入PASS，不能充当20修复后的重建green。最终freeze和报告均准确把code502+、invalid-state/in-flight真实框架、物理旋转/uiMode及实际下载事务列为open。

以上所核raw与保全字节一致，没有重复全套测试、运行设备或把自洽JSON当独立物理认证。

## 启发式 smell：判断性，非硬违规

核过Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest。repo要求局部轻量schema，当前有限map/Bundle是明确序列化边界，不据此要求新全局draft框架或domain层重构。监听器guard与单向bind有现生命周期理由，未发现本轮必须抽取/移动/拆分的可靠smell。跳过工具已约束项。

## 完成内容、限制、修改与后续

完成冻结source/before/patch、资源边界、秘密排除与实际证据的独立规范审查；本报告和外部摘要即时孪生。未完成修复后code502+真正Activity.recreate/invalid-state/in-flight、真实创建事务跨重建/进程死亡、统一App test/assemble/lint与最终APK验收。creating保守门只阻止恢复当场重发，不承诺旧Activity任务进度重连、跨进程续跑或原事务已经结束；此限制实施报告已经明确。

本review只写指定19/20正式报告、review-standards/19-20与preservation，未修改生产、tracker、版本、许可、系统设置或资产。无设备/adb/UI/安装/Gradle/网络、commit/ref/worktree；Commit无，branch沿用户提供main，未提交工作区保留。没有生产回滚或资产清理，新.log证据0。Class A、旧APK/源码、原red和用户数据保全。

残余风险为Android/OEM自动View恢复顺序与真实旧任务存活，须由上述设备门关闭；规范轴approve不解除它们。Architecture Change Policy：契约内bug修复与测试扩展，无新owner/暴露面，不触发新ACR；AVF ACR-01仍独立未决。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

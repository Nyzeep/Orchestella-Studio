# 原型报告：15 创建向导真实系统重建草稿一致性

- 日期：2026-10-16；执行：Worker D / Codex / GPT-6.1；设备写与Gradle唯一执行者：主协调。
- 背景：原SetupWizardActivity每次onCreate重建InstanceCreationWizardModel；Android有ID控件可能自动恢复，不能仅凭静态代码推断全部输入丢失。按本票先取得真正Activity.recreate反馈，再由20承接局部修复。
- Scope：第3/4步、非默认磁盘/CPU/RAM、发行版选择、username/host和summary的model/view一致性；坏草稿、catalog/资源变化及creating不重放。无MainActivity/Guest/AVFowner/版本/签名变更，不增加configChanges。
- 分支/基线：main@428860ad9a1cb335f8be78c157b2ff57faf2a6da；接入时dirty 0.5.0/code500，旧36文件与并行改动保留；无commit/ref/worktree/reset/clean。
- 原始证据根：`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`（下称batch）；D所有输出在其`android-input-recreate/`。Class B保留至维护者验收归档，raw证据即时preservation，不自动清理。

## 假设清单与环境核查

1. 系统重建导致currentStep、CPU/RAM及动态身份输入回到默认。
2. 部分有ID控件自动恢复，与新建模型/summary不同步。
3. 修复应版本化保存轻量草稿，创建中不能因重建重新下载/create，秘密和执行对象不能进入Bundle。

先检查现有App测试依赖、两套preserved Maven缓存和旧构建目录，无Robolectric/android-all/ActivityScenario runtime，未为此下载新依赖。D用已有SDK的aapt/javac/d8组装独立原生Instrumentation，主协调签名安装执行；没有以纯模型测试代替Android事件。

原产品500包安装被旧签名记录阻断，历史用户数据保留。主协调改用既有`.phase5`独立debug变体code501；没有改生产applicationId或卸载旧包。设备为device-01/user0，系统安装结果由主协调只读复核，D未执行设备动作。

## 实际实验步骤与观测数据

原生probe实际启动生产SetupWizardActivity，用View操作选择磁盘/CPU/RAM、前进步骤、填写合成qauser/qahost，并用Activity.recreate触发真正Android重建。新Activity由Application生命周期回调获取，比较model安全标量、控件与summary；不读password/token、不点击创建/测速/授权。

| 项目 | 原件 | 真正观察 |
| --- | --- | --- |
| 第3步red | `device/recreate-501-step3-red.json` | before STEP_HARDWARE、CPU2、RAM3820、disk16；after STEP_STORAGE、CPU4、RAM3875、disk16。stream明确FAIL draft differs after real Activity.recreate |
| 第3步原始不一致 | 同上 | before model distro=ubuntu，测试已对spinner作Debian选择动作；summary仍Ubuntu/8GB/4CPU/3819MB。不能把选择动作称为模型已Debian，也不能把旧summary称为当前配置 |
| 第4步red | `device/recreate-501-step4-red.json` | before STEP_STARTUP_TASKS、CPU2、RAM3886，username/host控件=qauser/qahost但model=root/orchestella；after STEP_STORAGE、CPU4、RAM3934，控件回root/orchestella |
| 自动View恢复 | 同两原件 | disk16与部分选择保留，但步骤/CPU/RAM/身份/summary漂移；不是“所有输入均丢失” |
| 返回码解释 | 同两原件 | adb子进程均exit0，但instrument stream为FAIL；产品red以stream和前后数据判定 |

这些原件由主协调实际采集，均有batch/preservation对应副本。D已完整读取原件后才获准解除SetupWizard/model冻结并提升20实现；旧静态研究不冒充本轮系统red。

## 结论与触发器判定

- 假设1：第3/4步真实系统重建red成立；身份动态控件确有重置，CPU/RAM跟随新的host建议值漂移。
- 假设2：成立；自动控件恢复与新模型的差异已在原始数据中可见。
- 假设3：由20实施。外部候选模型`draft-creating-red` exit1 → `draft-creating-green` exit0，6项；提升后production模型含既有回归14项通过，最终Activity/model定点Kotlin编译exit0。随后code502/probe3已实装并取得真正Android重建与受控防重放PASS，详见下节；模型/编译证据与系统运行证据分开记录。
- 未触发Architecture Change Policy：既有四步向导契约内bug修复和回归，不增权限/IPC/owner/数据面/网络。无能力矩阵变更建议。
- 本票不涉及benchmark，不编造操作耗时或性能改善。

## 实验产物与后续实施

实验源码在D根`framework/`和独立版本化`framework-v3/`，不进入生产sourceSet；正式生产修复归[20实施报告](../../tickets/avf-shizuku-20/implementation-report.zh-CN.md)。probe02主协调统一为2/0.1.1；probe03源码/Manifest明确3/0.1.2，已javac exit0，由主协调打包/签名/安装。不同bytes禁止复用旧版本。

probe03增加green前置检查，要求model确为Debian/16GB/2CPU、summary同步，step4身份model与view一致；避免把spinner动作当成功。另提供invalid-schema、invalid-distro、invalid-resource、creating、invalid-schema-creating模式；在真实saved Bundle回调中受控修改合成字段，creating只设模型标记，不触发下载/create。

生产源码已由D冻结，精确5个输入及写前SHA在D根`final-freeze.json`、`worker-d.patch`、`frozen-source/`，均有preservation；ModernTerminalView未改。下面补记code502实际签收，旧501 red、原冻结seal及独立首审原文保持不变。

## 2026-10-16 code502 Android签收增补

主协调已实装独立`.phase5` code502 App及probe3，在device-01/user0执行；D实际读取以下原件，并核对每件与batch/preservation对应文件字节一致。判定依据为instrument stream PASS及前后字段，非仅adb exit0。

| 原件（相对batch） | 实际签收范围 |
| --- | --- |
| `device/recreate-502-step3-acceptance.json` | 真正Activity.recreate前后均为STEP_HARDWARE、Debian、16GB、2CPU、3791MB；model/view/summary及其余记录标量逐项一致，stream PASS |
| `device/recreate-502-step4-acceptance.json` | 前后均为STEP_STARTUP_TASKS、Debian、16GB、2CPU、3829MB；model与控件均为qauser/qahost，autoUpdate=true、sudo=true及mirror/profile等记录值、摘要逐项一致，stream PASS |
| `device/recreate-502-invalid-schema.json`、`recreate-502-invalid-distro.json`、`recreate-502-invalid-resource.json` | 三项均PASS；无效草稿被拒绝，恢复至需重新确认的STEP_STORAGE和默认配置，model/view/summary一致，不接受旧View状态反写 |
| `device/recreate-502-creating.json` | 受控creating=true标记在真正recreate后保持；记录字段前后一致，创建按钮隐藏，重复admission拒绝，stream PASS |
| `device/recreate-502-invalid-schema-creating.json` | 无效schema使配置回到默认，但creating=true仍保留、创建入口隐藏且重复admission拒绝，stream PASS；此负例不要求无效草稿的原配置继续保留 |
| `device/input-framework-502-acceptance.json` | 19框架输入复验PASS；左Alt/CtrlAlt、受控AltGr、真正getDeadChar及ModernTerminalView重绑字节均匹配 |
| `integration-502/framework-acceptance.json` | 汇总step4/input/三项invalid/两项creating共七场景all_pass=true；step3单独原件另列，不重复计入该七项 |

creating两项只设置受控模型marker，不调用真实下载/create；这里签收的是Android恢复后的防重放门，不是实际下载/创建事务跨重建、进度重连或跨进程续跑。

`integration-502/test-apk-audit.json`记录App746项、98 suites、failures/errors/skipped均0、lint Error/Fatal均0、Warning748及D源码匹配冻结。D另定点读取`build/prod502/app/test-results/testDebugUnitTest/TEST-*.xml`、`reports/lint-results-debug.xml`核得同样746项与748Warning；正式原包配置命令原件`evidence/gradle-app-502-original-package-check.log.json` exit0。QA包身份由`integration-502/qa-apk-identity.json`固定为code502/0.5.2-phase5-local、106519708B、SHA256 `3bd497b1d1031bfe9a86e2728a46fe16b99af8714a6289d29828845baa9f0735`。完整App746项是一次集成套件，不与D模型14项或输入30项累加。

20源码的独立Spec/Standards两轴均approve，限冻结源码与当时本地证据：`docs/reports/reviews/avf-shizuku-20/spec-review-20261016.zh-CN.md`、`standards-review-20261016.zh-CN.md`。本次设备签收是后续新增原件，不改写审查者当时的待验结论。19框架与双轴approve见14/19报告。

15当前状态更新为`reproduced-and-android-fix-verified`：原现象已真实复现，派生20的Android recreate、非法草稿拒绝和受控防重放已验证。此次只修改报告/票和新seal，D冻结5源码/测试不变；主协调C三项P2修复后的503再构建仍是后续集成批次，不将502结果外推为503通过。新增文档seal归D根`acceptance-docs-502/seal.json`，保留原seal及旧报告字节。

## 未完成、风险与设备验收门

仍未运行：真实物理旋转/uiMode；真实下载/创建事务跨重建、进程死亡与进度恢复；实际OEM IME和实体键盘交互（归14/19）。这些与已通过的Activity.recreate及受控marker门分开列出；无用户盘/已有实例操作。

creating恢复采取保留in-flight并隐藏创建入口、要求返回实例列表确认的保守行为，不自动恢复执行任务，不重放create；不是跨进程任务恢复框架。saved state排除密码，未创建的随机候选凭据在重建模型中重新生成；既存实例凭据/根盘完全不读取、不修改。

越界检查：D未自行adb/UI/安装/grant/启停/Gradle/版本/签名操作，无新下载/依赖/许可证引入、C:项目临时文件、用户数据或对外传输；无新.log。原APK、SDK/JDK/缓存与Class A资产均保留。rollback只20有界hunk，保留原red与并行改动，无commit。

报告人：Worker D / Codex / GPT-6.1 ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

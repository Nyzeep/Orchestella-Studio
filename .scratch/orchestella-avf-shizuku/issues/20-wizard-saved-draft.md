# 20: 创建向导系统重建草稿恢复与防重复创建

Type: implementation
Status: implemented-integrated-android-reviewed; external-acceptance-gates-open
Parent: 15-wizard-recreation-reproduction
Owner: Worker D
日期: 2026-10-16

## 已证问题与实施范围

主协调已在 device-01/user0 的独立 `.phase5` code501 上实际运行 Android Instrumentation，调用真实 Activity.recreate：

- `batch-01/device/recreate-501-step3-red.json`：before STEP_HARDWARE/CPU2/RAM3820；after STEP_STORAGE/CPU4/RAM3875。before model distro=ubuntu，控件选项与摘要不一致，不把测试选择动作伪称模型已是Debian。
- `recreate-501-step4-red.json`：before STEP_STARTUP_TASKS，控件 qauser/qahost，model 仍 root/orchestella；after STEP_STORAGE，控件也回到 root/orchestella。
- 两条 adb 进程 exit0，但 instrumentation stream 明确 FAIL；不是测试通过。

按原15与用户20261016授权，限定修改 SetupWizardActivity、InstanceCreationWizardModel 及对应测试：版本化白名单草稿、当前 catalog/资源重校验、模型/控件/实时摘要一致、creating恢复禁止重放。SetupWizard独占D；不写MainActivity、Guest、AVFowner、公共版本/签名/Manifest，不以configChanges掩盖。

## 验收

1. 真正Activity.recreate前后第3/4步、非默认发行版/磁盘/CPU/RAM/镜像/用户名/host/sudo/可选功能模型、控件与摘要一致。
2. schema、字段、distro/version、资源预算变化失败即拒绝，显示需重新确认；Android自动View恢复不能覆盖已校验模型。
3. saved Bundle只白名单字符串/布尔/数字；无password/token/Context/Future/Executor/真实用户输入证据。未创建的随机候选凭据不序列化，实例已有凭据不读取、不改。
4. creating恢复不启动下载/create；重复点击只进入一次，创建中草稿保留明确阻止重发状态。无新增全局任务框架或VMowner。
5. JDK/Kotlin模型正负回归与既有向导套件；主协调统一Gradle/App test/assemble/lint及code502+真实框架绿验。物理旋转/uiMode、真实下载创建事务最终门独立列出。

## 测试、证据与状态

外部 `android-input-recreate/run-draft.py <新唯一标签>`；候选6项模型回归 `draft-creating-red` exit1 → `draft-creating-green` exit0（6 tests），不冒充系统测试。框架真实red见上列原件及preservation。

写前bytes/SHA见 `android-input-recreate/scope-and-before.json` 与 `before/`、`preservation/before/`。真实系统red后已提升生产：SetupWizardActivity、InstanceCreationWizardModel及新WizardDraftCandidateTest；生产新旧14项JUnit全绿，最终Activity/model定点Kotlin编译exit0，证据为 `draft-runs/production-draft-green/` 和 `activity-compile/production-activity-final/`。首次外部runner缺LifecycleOwner classpath的失败原件也保留，不冒充产品red。

最终5个D生产/test输入已冻结，精确bytes/SHA/beforeSHA、局部patch及源码副本在 `android-input-recreate/final-freeze.json`、`worker-d.patch`、`frozen-source/` 及preservation。主协调可从冻结输入构建code502+并跑系统green，D只写报告/外部probe后续。修复后真正Android重建尚未返回时保持awaiting-android-green，不以模型/编译代系统验收。

原15原型报告 `docs/reports/prototypes/avf-shizuku-15/prototype-report.zh-CN.md`；本票实施报告 `docs/reports/tickets/avf-shizuku-20/implementation-report.zh-CN.md`已落盘。新probe03源码/Manifest在本批D根`framework-v3/`，固定版本3/0.1.2，javac exit0；主协调独占打包/签名/安装/设备/Gradle/版本操作。step3/4先核model/view/summary真实前置，再真正recreate；另提供invalid-schema、invalid-distro、invalid-resource、creating、invalid-schema-creating模式。creating仅合成marker，不触发真实下载/create；实际事务跨重建/物理旋转/uiMode仍另列验收门。

回滚仅本票有界hunk，逐项保留旧/并行改动；无commit/ref/worktree/reset/clean。Class B证据保留至维护者验收归档，原始证据即时preservation、禁新.log、不外发。未新增权限/IPC/FD/数据面，不触发Architecture Change Policy。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

当前503/probe3七项向导真正Activity.recreate/非法state/受控creating全部PASS，D五份源码/test与既有final-freeze逐项匹配，已有14模型回归和独立Spec/Standards源码/502框架复审approve保持。不再以修复后系统green未返回、待code502+或待安装作为blocker。

creating与invalid-schema-creating只受控marker，无下载/create，不证明真实事务防重放/跨进程续跑；物理rotation/uiMode、OEM恢复时序、真实事务跨Activity重建/进程死亡及旧任务结果重接仍未验。保持白名单无secret草稿、当前catalog/资源复核、不新增configChanges/任务owner/热恢复。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

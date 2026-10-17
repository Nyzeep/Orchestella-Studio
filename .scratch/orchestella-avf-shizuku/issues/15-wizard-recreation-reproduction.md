# 15: 创建向导系统重建草稿一致性复现

Type: research
Status: reproduced-and-android-verified; physical-and-transaction-gates-open
Owner: Worker D / Android生命周期验证代理
Blocked by: 物理rotation/uiMode/OEM；真实下载/create事务跨重建与进程死亡；20代码/框架/503集成/双轴已完成
Remaining gates: physical-rotation/uiMode；real-download-create-transaction-recreation/process-death；OEM-IME/physical-keyboard见14/19

用户价值：系统重建后步骤/模型/摘要与控件一致且不重复创建。来源研究§8、旧terminal-runtime §5、C当前SetupWizard无模型保存事实。允许ActivityScenario/Robolectric/实际系统recreate合成草稿，禁止增加configChanges掩盖、持久化原始密码/token、重放下载/create或先重构整个向导。

验收：第3/4步、非默认distro/磁盘/RAM/用户名/host的model/view/summary都比对；坏schema/过期distro/资源门变化有明确失败；creating状态不重发；有ID控件自动恢复与模型恢复分开，不声称所有输入必丢。red命令采用现SetupWizardStepFlow/InstanceCreationWizardModel与可实际recreate的框架入口，纯模型测试不足为系统通过；运行环境未具备不编造。物理设备旋转/uiMode最终签收单列，性能不适用。

无新增IPC/FD/VMowner，草稿无秘密/出站。回滚只原型/草稿处理，不动实例或下载输入。依赖需先检查已有框架测试环境再增，许可沿用。报告 `docs/reports/prototypes/avf-shizuku-15/`；原仅静态缺口/未运行系统的状态保留为20261015历史。

## 2026-10-16 实测增补

既有两套缓存无Robolectric/android-all/ActivityScenario runtime。D构建原生Instrumentation unsigned QA probe，主协调签名安装独立.phase5 code501/probe02并执行真正Activity.recreate。`batch-01/device/recreate-501-step3-red.json`与`recreate-501-step4-red.json`均stream明确FAIL（adb进程exit0不等测试通过）：第3/4步回第1步，CPU/RAM漂移；第4步控件qauser/qahost而model=root/orchestella，重建后控件也重置。第3步before model=ubuntu，不能将测试对spinner的选择动作当模型已Debian，summary也有真实旧值。Android有ID的disk等部分自动恢复，不说全部输入必丢。

真实red后主协调解除D向导/model写锁，由派生20承接versioned白名单草稿、恢复重验、model/view/summary一致与creating防重放。生产模型新旧14项JUnit通过、Activity/model定点Kotlin编译exit0。随后主协调实装code502 App/probe3并取得真正Android系统green，见下列签收补记；纯模型不替代系统，原501 red保持原样。

正式原型报告 `docs/reports/prototypes/avf-shizuku-15/prototype-report.zh-CN.md`；派生票 `20-wizard-saved-draft.md`及20实施报告。本批证据根 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/android-input-recreate/`，即时preservation；写前局部bytes、最终冻结SHA与worker-d.patch齐备。probe03输入在framework-v3（3/0.1.2），D仅javac，主协调打包签装/Gradle/设备绿验。物理旋转/uiMode、真实创建事务跨重建继续独立列门；不增加configChanges、不保存秘密/执行对象、不重放create。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

501真正Activity.recreate第3/4步red保持，20修复后502与503七向导场景（第3/4步、invalid schema/distro/resource、creating、invalid-schema-creating）均实际PASS；20独立双轴源码及502框架复审approve，D五输入503匹配。不再标awaiting Android green或待安装。两creating只受控marker，未按Create、未下载/新建实例，物理rotation/uiMode/OEM、真实事务跨重建/进程死亡/旧任务结果重接仍未验，不承诺续跑。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

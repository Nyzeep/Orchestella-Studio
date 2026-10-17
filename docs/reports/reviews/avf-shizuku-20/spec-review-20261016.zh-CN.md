# 20 独立 Spec 审查：创建向导草稿恢复与防重放

结论：**approve（冻结源码与本地证据范围），无新增 P0/P1/P2 发现**。修复后 code502+ 真正 Activity.recreate、物理旋转/uiMode 与真实创建事务跨重建仍待设备签收，本报告不以模型/编译代替这些门。

## 对象与固定比较点

独立 Spec reviewer / Codex GPT-6.1-sol；2026-10-16。已读15/20原票、20实施报告、20261016 Worker D/Reviewer要求及项目policy。比较本批 `android-input-recreate/before/` 与 `final-freeze.json` 所指 `frozen-source/`：SetupWizardActivity、InstanceCreationWizardModel、WizardDraftCandidateTest，以及同批19的输入与测试，共5项源码及beforeSHA均匹配。冻结SetupWizard SHA256 `6e9d4759afa9b21fa5d6452f6e4817fc5250712ba77ea2b1024d146f5360b7eb`，model `e91dae03864739142ed8c826dfe109a927d8565930b7425f88df7d624d6e8b37`。

基线是用户给定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da的dirty0.5.0/code500工作区，不是HEAD三点diff。无commit/ref/版本操作。未读另一轴，不用实施者结论代替源码和原件。

## 逐项验收与实际行为

| 20原验收 | 独立核对 | 边界 |
| --- | --- | --- |
| 步骤和非默认配置一致 | model保存step/distro/version/disk/CPU/RAM/mirror/autoUpdate/身份草稿/可选profile；Activity创建控件后及系统自动恢复后从model回绑；身份与summary监听同步 | 静态路径及本地roundtrip成立，真正系统green待验 |
| schema/catalog/resource重验 | 精确字段集合、schema、数字/布尔/enum校验；当前官方distro/version、CPU/RAM宿主reserve与manifest boot minimum重新校验；不接受未知清单 | 非法草稿给固定重新确认提示；创建前仍重验硬件/StatFs |
| Android自动View恢复不能反写未经校验状态 | `applyingDraftViews`包围super.onRestoreInstanceState及控件回绑；编辑、spinner、checkbox监听受门控制；随后刷新模型对应摘要和硬件标签 | OEM时序仍需code502框架原件 |
| 秘密排除 | saveDraft只白名单标量，不序列化userConfig/password/token/Context/Future/Executor；未完成身份文本保留但不构造非法用户配置 | 新草稿重建候选随机密码不等既有实例凭据迁移 |
| creating防重放 | `tryBeginCreation`首次接纳；Activity入口isCreating即返回；恢复creating只显示确认任务结果状态，不调下载/create；坏schema但原creating=true仍保留门 | 不承诺跨进程续跑、旧Activity任务重连或真实事务已验 |
| 不扩大scope | 无configChanges、全局任务框架、VMowner或权限变化；旧Shizuku与两权限预检保留 | 契约内局部修复 |

不完整身份保存的是可编辑草稿，创建动作仍走username/hostname验证。可选profile按当前发行版支持收敛。创建中恢复保守阻止重发，报告没有把它表述为持续任务owner或自动恢复进度。未发现此次冻结实现超出20验收的行为或以未完成设备门伪装完成。

## 原件与测试真实性

独立读取 `device/recreate-501-step3-red.json`、step4原件：adb进程exit0，但instrumentation正文均为FAIL draft differs after real Activity.recreate。step3前model是ubuntu，不能把spinner选择动作当debian；step3/4步骤、资源和身份不一致真实保留。原件与preservation相同。没有把501 red改称502 green。

`draft-runs/production-draft-green`的现存javac/kotlinc/JUnit全部exit0，原始JUnit为OK (14 tests)，涵盖roundtrip、秘密排除、schema/字段/enum、更新/移除distro、资源变化、部分身份、profile与creating。`activity-compile/production-activity-final/result.json`明确仅定点编译两个Kotlin输入，exit0；不等完整Gradle或系统运行。输入的30项与向导14项分别计，不累加重复阶段。

本reviewer只读原件、定点源码/diff/hash，未运行重复套件、设备、Gradle、签名或网络。code502+系统green由主协调运行；实际事务、进程死亡、物理配置变化仍列未验。当前implemented-local-awaiting-android-green状态诚实。

## 交付、风险与后续

实际完成本报告、`review-spec/19-20/checks.json`与两票报告副本，均即时保全到 `preservation/review-spec/19-20/`。修改只review和本轴外部证据，生产/tracker/版本/ref保持只读。首审与原red不改写。

未完成：主协调code502+真实step3/4、非法schema/distro/resource、creating与invalid-schema-creating模式；实际创建事务和物理旋转/uiMode最终门。风险主要是Android/OEM自动恢复时序及旧Activity真实事务，需这些门解决；静态无发现不代替其运行结论。无设备/Gradle/网络/签名/commit或清理，无另一轴读取。Architecture Change Policy：本审查无架构实施；Class B保全至维护者归档，不自动清理。

报告人：独立 Spec reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

# 20 独立 Spec 追加签收：code502 / probe3 真正 Activity.recreate

结论：**approve（冻结源码及本次新增真实 Android 框架门），无新增 P0/P1/P2**。修复后step3/4、坏schema、移除distro、不合资源、creating及invalid-schema-creating共七项向导场景均有实际PASS原件；加19的输入一项，D共八场景。物理rotation/uiMode、OEM恢复时序与真实创建事务跨重建仍open。原 `spec-review-20261016.zh-CN.md` 及501 red保持，不再把已通过的502框架门写为待安装或待机主。

## 背景、对象与独立范围

审查者：恢复后的独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol；日期2026-10-16。已完整读取项目指定治理文件、两份20261016交接/Reviewer协议、resume-01/dispatch.md、原Spec closeout与08/17/18/19/20/22同轴报告。20源码需求审查沿首审，本次只增量核设备原件、固定探针行为、精确输入/包关联与未覆盖范围，不重复本地14项或App/Guest全套。未读取另一轴报告。

唯一源码 `Orchestella_dev`，沿用户给定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da；原dirty实现及本批502完整保留，无Git/ref/版本命令、无commit。外部根 E 为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`。

本次定点hash确认 `android-input-recreate/final-freeze.json` 全五项源/测试在frozen-source及当前源码仍一致。SetupWizard SHA256仍 `6e9d4759afa9b21fa5d6452f6e4817fc5250712ba77ea2b1024d146f5360b7eb`、model仍 `e91dae03864739142ed8c826dfe109a927d8565930b7425f88df7d624d6e8b37`，没有重做D源码审查或把C17/503变化纳本结论。

## 七项向导设备门签收

实际执行由主协调完成，device-01/user0，phase5 code502 + framework probe3。读取每份原件的固定命令、exit、正文与probe源码断言，均为正文PASS且无FAIL；adb exit0单独不作通过依据。

| 原件（E/device） | 本次独立核对的实际结果 | 判定 |
| --- | --- | --- |
| `recreate-502-step3-acceptance.json` | 真正Activity.recreate前后均STEP_HARDWARE；Debian/16GB/2CPU/3791MB、model/view/summary、步骤及身份一致 | **PASS**；独立于七项汇总，不漏记 |
| `recreate-502-step4-acceptance.json` | 前后STEP_STARTUP_TASKS；Debian/16GB/2CPU/3829MB、qauser/qahost、autoUpdate=true及model/view/summary一致 | **PASS**；修复后系统green，保留501 red |
| `recreate-502-invalid-schema.json` | 保存草稿schema注入999；重建后回STEP_STORAGE、creating=false、当前默认合法草稿，旧非法View状态未重新接纳 | **PASS**；实际框架save/restore |
| `recreate-502-invalid-distro.json` | 保存草稿distro注入移除项；重建后回STEP_STORAGE、合法默认distro/配置 | **PASS** |
| `recreate-502-invalid-resource.json` | 保存草稿CPU注入999；重建后回STEP_STORAGE，重新合法资源与摘要 | **PASS** |
| `recreate-502-creating.json` | 注入model creating marker后recreate，creating仍true；创建按钮隐藏、tryBeginCreation返回false，草稿其余内容一致 | **PASS（marker门）**；未运行真正创建事务 |
| `recreate-502-invalid-schema-creating.json` | 坏schema导致草稿回合法默认，但creating=true与最终步骤保守保留；按钮隐藏且重复admission拒绝 | **PASS（marker门）**；不承诺事务续跑或旧任务重连 |

固定instrumentation组件为 `io.github.nyzeep.orchestella.qa.recreate/io.github.nyzeep.orchestella.qa.RecreateProbe`。每项命令形态为 `shell am instrument --user 0 -w`，step3/4分别传 `-e step 3/4`；非法与creating模式传 `-e mode <固定模式> -e step 4`。本reviewer仅读这些已存argv，没有调用adb或UI。

`integration-502/framework-acceptance.json` 汇总七项为step4、input及五项负例/creating，all_pass=true；另加step3共八项。向导七项与19输入一项分别签收，不重复累计。`device/device-acceptance-current-02.json`已明确phase5 code502和probe code3；较早安装途中code501/系统确认记录保留为历史，不据此否认后续八项成功。

## 探针实际行为及证据关联

逐行读取probe3精确snapshot `android-input-recreate/framework-build/recreate-input-v3-final/source/src/io/github/nyzeep/orchestella/qa/RecreateProbe.java`。它在真实Activity上操作View，先断言当前步骤、非默认distro/磁盘/CPU以及summary，step4另验model身份；调用first.recreate，等待另一个resumed Activity，再比对model、实际控件值和summary。非法模式在真实onActivitySaveInstanceState修改固定字段；不是只运行model roundtrip或自行构造before/after绿。

creating模式只调用setCreating(true)，探针从未按Create，也没有下载或真正调用create；它仅证明SavedState下防重复接纳门保持。两种creating原件的marker retained/duplicate admission rejected与该源码一致，不能签真实创建事务不中断、不重放、旧Activity后台任务已重连或进程死亡恢复。

probe源码17192B / SHA256 `f929e2d017c9c6a1c0f1c026ced2818ed33d726f42608cc5860361bfca347dc4`，Manifest596B / `1fea05e22f3a170df88bf0c63d1922518206b160c107d7c7f0da025b83666e44`，与已有构建receipt一致；aapt/javac/D8记录全0。本次仅定点hash既有包：目标phase5 APK106519708B / `3bd497b1d1031bfe9a86e2728a46fe16b99af8714a6289d29828845baa9f0735`，probe3 signed APK16727B / `33deffe9092299a74aa03de5697288784dfd1426655fcec10fd25f2d1f326b02`。未重新构建/签名/验签或读签名材料。

八份设备原件及汇总与各preservation均同字节。独立核验详 `E/resume-01/review-spec/frozen-evidence-checks.json` 的d19_20部分，孪生在 `E/resume-01/preservation/review-spec/`。本reviewer没有重新跑14项model、30项输入、App/Guest、Gradle或设备套件，旧数与新八场景不累计。

## 实际完成、剩余、风险与边界

实际完成：本报告、外部同路径副本、指定原件/源码/包hash核验索引与即时preservation。只写本轴正式spec-rereview及 `resume-01/review-spec`、`resume-01/preservation/review-spec`；没有改首审、原red/green、生产、tracker或其他owner文件。

剩余：物理rotation/uiMode、OEM恢复时序、真正下载/create事务跨Activity重建、进程死亡与旧任务结果重接。实体键盘/OEM IME为D19仍open。主协调503整体构建/必要回归按其新原件独立签收；本报告签502/probe3框架范围，不沿旧502签C17后续修复已集成。

风险是把真实Activity.recreate等同一切配置变化，或把creating marker等同真实创建事务；上述两层均明确保留。没有AVF/Guest/性能主张。Architecture Change Policy：无本次架构实施，本修复沿既有向导草稿/防重放契约，不批准新任务owner或热恢复。

无生产/设备/adb/UI/Gradle/版本/ref/签名/commit/网络/系统操作，无另一轴报告读取、凭据读取、整仓归档、外发或清理。Class B保留至维护者验收归档，不自动清理。

报告人：独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

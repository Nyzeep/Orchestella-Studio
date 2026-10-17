# Ticket 19 实施报告：AltGr、死键与退役输入连接

- 票号/标题：19 terminal-layout-encoding，Parent=14。
- 执行session/模型/thinking：Worker D / Codex / GPT-6.1；thinking未单独记录。
- 分支/基线：main@428860ad9a1cb335f8be78c157b2ff57faf2a6da；接入时dirty 0.5.0/code500，原36份未提交交付与并行改动保留。无commit/ref/worktree/reset/clean。
- 外部证据：`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/android-input-recreate/`（下称D根）；设备原件在同batch的`device/`，主协调执行。

## 1. 任务目标

从14已运行的布局→精确UTF-8 sink red出发，局部修复右Alt布局语义和Android死键组合；保留左Alt/CtrlAlt终端快捷键、中文候选/emoji/paste、多字节删除、close不提交和旧connection退役。

## 2. 实际完成与验收

| 验收 | 实际结果/证据 |
| --- | --- |
| 先red再fix | `runs/input-altgr-red/`真实JUnit exit1，预期`40`实际ESC+q；`input-deadkey-red/`真实exit1，COMBINING_ACCENT被拒。每项随后最小fix并green，未拿静态推论代red |
| 右Alt布局 | sendKeyEvent保持右Alt布局meta且不附shell ESC；保留Shift/Caps。生产KeyEvent委托同一个受控resolver边界；没有执行stub恒零字符查询来伪造布局 |
| 左Alt/CtrlAlt | 左Alt+B=`1b62`、显式CtrlAlt+C=`1b03`；真实Android KeyEvent执行已匹配 |
| 死键 | 只保留单个pending accent；Android KeyCharacterMap.getDeadChar组合base，space输出spacing accent，不可组合保留accent+base；keyUp不提交，close清空 |
| composition/retirement | 既有composition→commit→finish一次，中文emoji/paste/多字节删除回归通过；真正ModernTerminalView重绑旧connection后commit无效，close未提交零发送 |
| 本地回归 | `run-input.py input-regression-green` exit0，30项JUnit通过；每次完整命令/exit/stdout原件与preservation保留 |
| 真实框架 | `batch-01/device/input-framework-501.json`及后续`device/input-framework-502-acceptance.json`的stream均明确PASS；code502/probe3实装复验的四组expected/actual字节全部匹配，真正KeyCharacterMap与ModernTerminalView运行，不是纯模型 |
| 实体/OEM | NOT_TESTED。手机连接和external=false标志不足以证明有/无实体键盘，不能把框架通过标物理布局验收完成 |

## 3. 未完成与偏差

实体键盘国际布局、真实OEM IME候选/开合/焦点/前后台/锁屏交互仍待主协调QA设备窗口。默认IME只读原件为`device/input-environment-readonly.json`，component=`com.baidu.input_hihonor/com.baidu.input_honor.ImeService`，physical inventory可信度不足，保持unknown。

D未运行Gradle。主协调已完成code502构建及完整App746项测试（98 suites，failures/errors/skipped均0）、lint0Error/748Warning；D实际读取`integration-502/test-apk-audit.json`，并定点解析原包配置的JUnit/lint XML核得同样结果。code501及实装code502/probe3的真实框架输入均PASS，19冻结源码与既有框架证据的独立Spec/Standards两轴均approve。此处完整App746项与19本地30项分开记录，不将旧批数或重复运行累计为本票覆盖数；实体/OEM未验门保持开放。

## 4. 修改模块与写锁核对

- `app/src/main/java/io/github/nyzeep/orchestella/TerminalInputConnection.java`：同一路径解析右Alt/终端modifiers、死键状态与Android组合边界，close清理。
- `app/src/test/java/io/github/nyzeep/orchestella/TerminalInputConnectionTest.java`：新增受控布局与精确字节、死键/左右Alt/显式CtrlAlt/Shift/Caps/旧回调负例，沿用既有测试。
- ModernTerminalView没有生产修改，其既有重绑行为由真实框架验证。
- 外部原型/runner属D根，不混入生产module；无MainActivity、Guest、AVFowner、版本、签名、Manifest/configChanges或inputType更改。

## 5. 重要设计决策

将Android字符解析作为系统边界：生产调用KeyEvent.getUnicodeChar，测试给固定meta→字符resolver，二者共用实际编码器和sink。显式Ctrl+Alt作为终端快捷键；单独右Alt作为布局选择，左Alt保留shell meta。不存在“所有CtrlAlt都等AltGr”的推断。

死键默认由Android KeyCharacterMap.getDeadChar解析，测试注入仅固定独立例子的组合结果；真实设备框架又验证默认Android实现。没有复制Termux/GPL实现，不引依赖，不增加全局框架、reader、pacing或按键记录。

## 6. 测试与真实性

命令：`python -B <D根>/run-input.py <新唯一标签>`。JDK17 javac直接编译4个生产输入和现有Test，JUnit4.13.2运行。AndroidSDK只用于编译接口/常量；本地布局测试不调用stub方法。

| 唯一阶段 | 真正结果 |
| --- | --- |
| input-altgr-red → input-altgr-green | 24 tests，1 fail→0 fail |
| input-deadkey-red → input-deadkey-green | 25 tests，1 fail→0 fail |
| input-regression-green | 30 tests，0 fail |

以上为增量阶段，不能累加称79项独立测试。最终独立用例数30。

主协调真正设备命令为`am instrument --user 0 -w -e mode input io.github.nyzeep.orchestella.qa.recreate/io.github.nyzeep.orchestella.qa.RecreateProbe`。精确bytes分别为`1b621b03`、`40`、`c3a9c2b4c2b478`、`e5a5bdf09f9880`，expected/actual均一致。501与实装code502/probe3复验原件分别为`device/input-framework-501.json`、`device/input-framework-502-acceptance.json`；D已实际读取，502原件与`preservation/device/`对应副本字节一致。受控AltGr与真正Android KeyMap/重绑分开标注，不外推实体键盘。

独立双轴原文为`docs/reports/reviews/avf-shizuku-19/spec-review-20261016.zh-CN.md`与`standards-review-20261016.zh-CN.md`，两者对冻结源码与既有框架证据均approve，无新增必须修改发现；D已读取，未改写审查原文或其实体/OEM范围限定。完整App/lint集成原件为`integration-502/test-apk-audit.json`、`evidence/gradle-app-502-original-package-check.log.json`和原包配置`build/prod502/app/test-results/testDebugUnitTest/`、`reports/lint-results-debug.xml`，D仅只读核对，不重新运行Gradle。QA502包SHA256由`integration-502/qa-apk-identity.json`固定为`3bd497b1d1031bfe9a86e2728a46fe16b99af8714a6289d29828845baa9f0735`。

## 7. Benchmark / 实测数据

本票不涉及benchmark，没有吞吐、ANR、丢字率或性能收益主张。

## 8. 风险与后续动作

OEM可能将实体AltGr表现为不同meta组合，当前明确保留显式CtrlAlt终端语义，需真实布局补验。真实IME的候选关闭、前后台重绑是额外设备门。后续对不同布局作改动必须继续用精确sink与正常shortcut对照，不记录用户正文。

生产冻结清单为D根`final-freeze.json`，逐文件bytes/SHA、beforeSHA与`worker-d.patch`齐备；回滚只19有界hunk，保留旧36变更和其他owner代码。原始red/green、写前局部副本、源码与probe都Class B长期保全，不清理Class A。

## 9. 新发现与对后续票影响

14完成了框架/受控边界反馈，实体门继续开；不升级能力矩阵，不影响AVF/Guest/ACR。20向导修复与19独立，二者最终构建按主协调统一版本处理。

## 10. Commit与状态

无commit；main保留未提交交付。状态保持`implemented-local-and-framework-physical-gate-open`：本地实现、code501/502真实框架、冻结源码独立双轴及502 App/lint集成均已通过；实体键盘/OEM IME仍NOT_TESTED。此次只更新文档/票并生成D根`acceptance-docs-502/seal.json`，冻结5源码/test、原seal与probe输入不变。主协调C三项P2修复将503再构建，D源码不变，502签收不外推为503已通过。

## 11. 越界、许可与架构检查

无新权限/IPC/FD/VMowner、Guest数据面或主机出站；无下载、第三方代码复制/新许可证。D无adb/UI/安装/grant/启停/Gradle/版本/签名/Git写操作，无C:项目临时输出或用户按键/口令/剪贴板正文；无新.log。符合DEFENSIVE/Host/Artifact/Asset政策；不触发Architecture Change Policy（契约内bug修复/测试扩展）。独立review由主协调另派，实施者报告不充当自审通过。

报告人：Worker D / Codex / GPT-6.1 ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

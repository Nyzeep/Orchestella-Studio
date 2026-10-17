# 19: AltGr 与死键到终端字节的局部修复

Type: implementation
Status: implemented-integrated-framework-reviewed; physical-oem-gates-open
Parent: 14-physical-keyboard-reproduction
Owner: Worker D
日期: 2026-10-16

## 证据与目标

14 的受控布局 resolver → 当前 TerminalInputConnection → 精确 UTF-8 sink 已运行两项 red：右 Alt+Q 预期 `40`，实际 `1b 71`；死键 accent 事件被拒绝。证据位于本批 `android-input-recreate/runs/input-altgr-red/` 与 `input-deadkey-red/`，真实 JUnit exit=1，未把 Android stub 的布局返回当作证据。

使右 Alt（无显式 Ctrl、无左 Alt）保留布局 meta 并不附加 shell ESC；左 Alt、显式 Ctrl+Alt 继续终端快捷键。使用 Android KeyCharacterMap.getDeadChar 边界处理死键+base/space/不可组合。保留中文候选、emoji、paste、close 零提交与旧 connection 退役。

## 范围与写锁

- 仅 `TerminalInputConnection.java` 及现有 `TerminalInputConnectionTest.java`；必要时 ModernTerminalView 输入绑定 hunk，当前未需修改。
- 不改 MainActivity、Guest、AVF owner、版本、签名、Manifest/configChanges、inputType、网络或权限。
- 不复制 GPL 参考实现，不新增依赖；resolver 系本项目局部公共系统边界。

## 验收与真实结果

1. 右 Alt 布局字符直达精确 UTF-8 sink，Shift/Caps 保留；左 Alt+B=`1b 62`，Ctrl+Alt+C=`1b 03`。
2. dead acute+e=`c3 a9`，+space=`c2 b4`，不可组合+x=`c2 b4 78`；keyUp/close 不提交。
3. composing→commit→finish 仅一次；关闭与旧 connection 迟到的文字、paste、resolver 请求无效。现有多字节删除、中文/emoji、paste 回归保持。
4. direct JDK 当前30项JUnit通过。主协调已在device-01/user0独立.phase5 code501/probe02实际执行真正KeyEvent/KeyCharacterMap、ModernTerminalView重绑，`batch-01/device/input-framework-501.json` stream明确PASS且所有bytes匹配；D已读取原件。真实物理键盘/OEM IME仍NOT_TESTED，必须另外签收，手机连接/external=false不证明实体键盘存在或不存在。
5. 正式原14 prototype及19实施报告已落canonical路径；D最终5输入精确SHA/bytes见本批`android-input-recreate/final-freeze.json`，源与test保持冻结。无Gradle/设备/版本/签名/Git写操作由D发起。

## 命令与设备门

`python -B <本批>/android-input-recreate/run-input.py <新唯一标签>`。
已运行：`input-altgr-red` exit1 → `input-altgr-green` exit0；`input-deadkey-red` exit1 → `input-deadkey-green` exit0；`input-regression-green` exit0（30 tests）。

Gradle、签名/安装/设备动作仅主协调执行；D 不运行 adb/UI。先源码冻结，再由主协调跑 App test/assemble/lint。不得把纯 resolver 测试当成物理键盘/OEM 实测。

## 回滚与交付

写前精确 bytes/SHA 与局部副本位于本批 `android-input-recreate/before/` 和 `preservation/before/`；回滚只本票有界 hunk，禁止覆盖他人并行变更、reset/clean。原14报告归 `docs/reports/prototypes/avf-shizuku-14/`；本票实施报告归 `docs/reports/tickets/avf-shizuku-19/`。所有原始证据即时 preservation，Class B 保留至维护者归档，无新 .log、无按键/口令/剪贴板用户正文或外发。

不新增出站/IPC/FD/owner，不触发 Architecture Change Policy。main@428860a 的未提交0.5.0实现保留；无 commit/ref/worktree 操作。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

原30项本地回归、501/502输入框架和独立Spec/Standards源码/502框架复审approve保留；当前503/probe3 input同样正文PASS、D五输入全部匹配final-freeze。真实KeyEvent/getDeadChar/ModernTerminalView及受控AltGr精确sink可签，物理国际布局键盘/OEM IME未测，不能由8框架PASS填绿。仅局部实现/框架/集成/双轴已完成，外设最终验收保持开放。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

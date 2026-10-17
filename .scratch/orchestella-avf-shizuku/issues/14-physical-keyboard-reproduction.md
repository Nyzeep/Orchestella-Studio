# 14: 右Alt/死键与IME重绑反馈回路

Type: research
Status: reproduced-and-framework-verified; physical-oem-gates-open
Owner: Worker D / 输入研究代理 / 硬件验证者
Blocked by: 实体国际布局键盘、OEM IME/焦点/前后台/锁屏；19代码/框架/503集成/双轴已完成

用户价值：国际布局、中文候选和前后台输入字节正确。来源研究§8、旧terminal-runtime §3、C静态核查。允许真实框架KeyCharacterMap/受控resolver及既有InputConnection public seam验证AltGr、leftAlt、CtrlAlt、deadkey+base/space/不可组合与旧connection迟到；禁止复制Termux代码、全局改inputType、固定pacing、失焦自动发送未选候选或记录按键正文。

验收：先用精确UTF8 sink证明red，composing commit/finish只一次，close未提交零发送；物理键盘布局与真实OEM IME重绑分别记录，保留已修键盘点击/多字节删除/候选。red入口TerminalInputConnectionTest及真实KeyCharacterMap测试，不让恒零Android stub当布局成功；设备后shell/nano固定合成文本，IME开合/重绑/锁屏分列。性能不适用，丢字/ANR未复现不宣称发生。

无新权限/FD/VMowner或出站，合成数据不含口令。回滚只原型/局部编码变化，保留旧行为证据；依赖现框架测试，GPL参考仅研究不复制。报告 `docs/reports/prototypes/avf-shizuku-14/`；原先仅静态缺口、未跑框架/硬件的状态保留为20261015历史。

## 2026-10-16 实测增补

受控布局resolver→实际生产TerminalInputConnection→精确UTF8 sink已运行AltGr与deadkey两项red（JUnit exit1）并由派生19局部修复，最终30项JUnit通过。主协调实际安装独立.phase5 code501/probe02，在device-01/user0运行真正Android KeyEvent、KeyCharacterMap.getDeadChar、ModernTerminalView重绑/旧connection退役，`batch-01/device/input-framework-501.json` stream明确PASS，精确bytes匹配；adb进程exit0不单独作为通过依据。

正式原型报告 `docs/reports/prototypes/avf-shizuku-14/prototype-report.zh-CN.md`，派生票 `19-terminal-layout-encoding.md` 与19实施报告；本批canonical外部证据 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/android-input-recreate/`，即时preservation。原E路径不再作为本批写入根。

`device/input-environment-readonly.json`默认IME为com.baidu.input_hihonor/com.baidu.input_honor.ImeService，external标志仅观察到false，缺可信typed inventory，实体键盘连接状态unknown。实际物理布局/OEM IME候选、前后台/锁屏与焦点仍NOT_TESTED，不由框架绿替代；不声称丢字/ANR已复现。D未设备/Gradle/版本/签名/Git写操作，原旧实现与并行改动保留。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

原AltGr/死键到精确UTF8 sink真实red保留，19局部修复后30项本地回归、501/502与503 Android框架输入已PASS，独立Spec/Standards源码及502框架复审approve；D五份最终输入在503审计匹配。真实KeyEvent/getDeadChar/ModernTerminalView不等所有实体布局，右Alt resolver仍有受控部分；物理键盘/OEM IME/实际焦点及候选/前后台/锁屏未验。手机连接/external=false不证明实体设备存在或完成。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

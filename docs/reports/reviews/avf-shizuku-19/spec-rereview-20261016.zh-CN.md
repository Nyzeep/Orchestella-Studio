# 19 独立 Spec 追加签收：code502 / probe3 真实 Android 输入框架

结论：**approve（冻结源码与本次追加的真实 Android 框架证据），无新增 P0/P1/P2**。code502/probe3 输入原件正文为 PASS，关闭本票修复后502框架签收缺口；实体国际布局键盘、OEM IME、前后台/锁屏和实际焦点体验继续 open。原 `spec-review-20261016.zh-CN.md` 首审与501框架历史不改写。

## 对象、基线与范围

审查者：恢复后的独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol；日期2026-10-16。已完整读取项目指定治理、两份20261016交接/Reviewer协议、resume-01/dispatch.md及原Spec closeout与08/17/18/19/20/22同轴报告。本票源码需求审查沿19首审，不重复全套；只对新增502/probe3物理执行原件、固定探针源码、内容关联和仍开放边界追加签收。未读另一轴报告。

唯一源码 `Orchestella_dev`，沿用户给定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da；原dirty实现及当前502保留，无Git/ref/版本操作、无commit。外部根 E 为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`。

D的 `android-input-recreate/final-freeze.json` 五份源/测试均定点匹配 `frozen-source/` 和当前源码，未发生D源码变化。输入编码器SHA256仍 `72b89aaeb3a7a93e897920ff8313eceb2e02d415bf3f2ef7b1607a7f5e6d19dd`；不把C17后续修复或503整体集成混入本票502签收。

## 原验收与追加设备事实

19要求AltGr保留右Alt布局、左Alt/CtrlAlt终端快捷键、正确死键组合，以及composition一次提交、退役连接迟到无效。新原件 `E/device/input-framework-502-acceptance.json` 由主协调在device-01/user0执行固定instrumentation命令：

```text
shell am instrument --user 0 -w -e mode input io.github.nyzeep.orchestella.qa.recreate/io.github.nyzeep.orchestella.qa.RecreateProbe
```

原件exit0，正文以 `PASS real Android framework input` 开头，无FAIL；不是只依据adb返回码。逐行输出与probe3固定源码断言相符：

| 检查 | 原始expected / actual | 验收层级 |
| --- | --- | --- |
| 真实KeyEvent左Alt/CtrlAlt | 1b621b03 / 1b621b03 | 设备上真实Android KeyEvent与生产InputConnection |
| 右Alt布局 | 40 / 40 | 设备上真实InputConnection，字符resolver受控；不称实体AltGr布局已验 |
| 死键base/space/不可组合 | c3a9c2b4c2b478 / c3a9c2b4c2b478 | 真正KeyCharacterMap.getDeadChar路径 |
| ModernTerminalView重绑/close | e5a5bdf09f9880 / e5a5bdf09f9880 | 真实View/InputConnection边界；旧connection关闭且拒绝commit，新composition只提交一次，close不发送pending |

该原件末行明确physical keyboard/OEM IME NOT_TESTED。探针建立的是固定合成字节sink及受控输入，未记录用户按键、口令或剪贴板。未新增第二终端reader或向真实实例发送测试正文。

## 原件、源码与包关联

本次独立读取 `framework-build/recreate-input-v3-final/source/src/io/github/nyzeep/orchestella/qa/RecreateProbe.java`：input分支实际加载目标App的生产类，分别使用真实KeyEvent、受控AltGr resolver、KeyCharacterMap及ModernTerminalView重绑，按精确UTF8 sink比较；不是Android stub恒0测试。该探针源码17192B / SHA256 `f929e2d017c9c6a1c0f1c026ced2818ed33d726f42608cc5860361bfca347dc4`，与构建receipt一致，aapt/javac/D8原记录全0。

`device/device-acceptance-current-02.json`明确读回phase5 code502及instrumentation code3，旧安装途中code501记录是历史，不再列502安装待确认。已有APK元数据与定点hash相符：目标 `Orchestella-v0.5.2-phase5-validation.apk` 106519708B / SHA256 `3bd497b1d1031bfe9a86e2728a46fe16b99af8714a6289d29828845baa9f0735`；probe3 signed包16727B / `33deffe9092299a74aa03de5697288784dfd1426655fcec10fd25f2d1f326b02`。只核既存内容/元数据，未执行签名检查或读取签名材料。

输入原件和preservation同字节。`integration-502/framework-acceptance.json` 七场景all_pass=true，加独立step3共八场景，本票只签其中输入一项；其余七项向导见20复审。不把八场景累计成19新增八项，也不累加旧30项JUnit或501框架执行次数。

## 交付、未完成与边界

实际完成本报告、`E/resume-01/review-spec/frozen-evidence-checks.json` 的d19_20核验与同路径报告副本，均即时保全于 `E/resume-01/preservation/review-spec/`。只写本轴正式spec-rereview和指定外部目录；首审、源码、tracker、设备原件不改。

剩余实体键盘国际布局、OEM输入法、前后台/锁屏/焦点，以及必要真实用户体验门。物理rotation/uiMode和真实创建事务属于D整体仍开放范围，详20复审。502输入框架已通过，不再将其描述为待机主或待安装；主协调503整体回归另行依据实际原件签收。

风险是把受控resolver和框架事件误当全部实体布局/OEM行为。测试：reviewer未重跑任何套件、设备或构建，仅只读原件和定点hash；无性能或AVF结论。无生产/adb/UI/Gradle/版本/ref/签名/commit/网络/系统操作，无另一轴报告读取、整仓归档、凭据读取、外发或清理。Architecture Change Policy：无本次架构实施；本修复沿既有输入契约。Class B保留至维护者验收归档，不自动清理。

报告人：独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

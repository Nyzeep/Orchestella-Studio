# 19 独立 Spec 审查：AltGr、死键与退役输入连接

结论：**approve（冻结源码与既有框架证据范围），无新增 P0/P1/P2 发现**。实体键盘国际布局、OEM IME、前后台/锁屏和焦点门仍未验，不由本报告签完成。

## 对象与基线

审查者：独立 Spec reviewer / Codex GPT-6.1-sol；日期2026-10-16。已读14/19原票、19实施报告、20261016 Worker D/Reviewer要求和项目硬约束。固定终点为 `batch-01/android-input-recreate/final-freeze.json`，按 `before/` 到 `frozen-source/` 的精确字节比较，包含 `TerminalInputConnection.java` 与现有测试；五份D输入及写前hash全部匹配。基线沿用户给定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da、dirty0.5.0/code500，不用HEAD三点diff，无commit/ref/版本操作。向导部分另见20同主题报告。

## 原验收核对

19要求右Alt保留布局meta且不加ESC，左Alt和显式CtrlAlt保留终端快捷键，dead accent+base/space/不可组合输出准确；composition只提交一次，关闭和旧connection迟到无效。

- `sendKeyEvent` 在右Alt、无左Alt且无显式Ctrl时保留Alt布局meta；其他Alt从字符查询中移除后由终端编码器加ESC，Shift/Caps不丢失。生产KeyEvent和受控resolver共用同一编码路径，未用恒零stub查询冒充布局。
- 死键只暂存一个accent，按Android KeyCharacterMap.getDeadChar边界合成；space/不可组合、重复accent均有对应有限行为。ACTION_UP不提交；close清空composition和pending accent并阻止旧连接后续写入。
- 原commit清空composition，随后finish不会重复发；中文/emoji/paste、删除与既有sticky modifiers保持。没有额外reader、逐字pacing、按键日志或新权限。
- 新增测试通过公开InputConnection行为与精确UTF8 sink，覆盖AltGr、Shift/Caps、左Alt/CtrlAlt、dead组合、keyUp/close、旧连接迟到；未要求全面重构输入模型。

冻结编码器SHA256 `72b89aaeb3a7a93e897920ff8313eceb2e02d415bf3f2ef7b1607a7f5e6d19dd`，测试SHA256 `1cfd0d06203d0783fd506344e8ae459c8cf1de2ded4d58fa869c39899ac5f918`。未发现本次有界变更偏离原验收。

## 证据真实性与限制

只读本批 `runs/input-regression-green/result.json` 与 `tests.log.txt`：javac/JUnit均exit0，最终30 tests，不能把24→25→30各轮累计。两项red的预期差异和修复路径与冻结测试相符。

独立读取 `batch-01/device/input-framework-501.json`：instrumentation正文以PASS real Android framework input开头，左Alt/CtrlAlt、受控AltGr、真正KeyCharacterMap deadchar与ModernTerminalView重绑的合成字节匹配。adb进程exit0本身不作通过依据；原件与preservation同字节。该证据不能推断实体键盘连接、OEM布局或真实用户IME已验。

本reviewer未重跑测试、设备、Gradle或签名。主协调后续502+集成、App完整套件、实体/OEM最终门独立存在；本票状态“implemented-local-and-framework-physical-gate-open”诚实。

## 交付与边界

实际完成本报告、`review-spec/19-20/checks.json`相关冻结/原件核验及报告副本，均即时保全到 `preservation/review-spec/19-20/`。修改仅指定正式review与本轴外部证据，未改生产、tracker、版本、签名、ref或设备。无另一轴读取、网络、清理或新依赖。Class B，维护者验收归档前不自动清理。

未完成为上述设备/集成门，无性能或AVF收益结论。风险是将框架meta组合等同全部实体键盘/OEM行为；下一步由主协调按真实布局补验。Architecture Change Policy：本审查不实施架构变更，原修复属于既有契约内输入bugfix。

报告人：独立 Spec reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

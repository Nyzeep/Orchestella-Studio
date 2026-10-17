# 19 独立 Standards 增量复审：code502 框架输入签收

结论：**approve，仅追加 code502/probe3 的真实 Android 框架输入证据签收；无新增规范发现**。旧首审批准与原件保持，不重做已完成源码审查。实体键盘、OEM IME 与实际用户终端体验仍未验。

## 背景、scope 与来源

沿唯一源码 Orchestella_dev、main@428860ad9a1cb335f8be78c157b2ff57faf2a6da dirty 工作区；外部 batch 为 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`。已恢复根治理 policy、两份20261016交接及独立 reviewer 协议、resume dispatch、旧同轴 index/报告；未读取需求轴。

本次仅读取 D final-freeze 的 hash、精确 probe3 构建输入及新增设备原件；五项 D source/test 的 canonical 和 frozen-source 均与旧 seal 匹配，无新 D 生产差异。源码规范批准沿原 `standards-review-20261016.zh-CN.md`，本报告不把另一轮代码当旧已审字节。核验摘要为 `batch-01/resume-01/review-standards/d502-framework-verification.json`，有即时 preservation。

## 本次签收与证据等级

`device/input-framework-502-acceptance.json` 原件为固定 user0/probe3 的 instrumentation，adb exit0 且内部 stream 为 **PASS real Android framework input**。独立检查精确 `framework-build/recreate-input-v3-final/source/.../RecreateProbe.java`，确认它调用目标包实际 TerminalInputConnection/ModernTerminalView/KeyCharacterMap，并对结果字节作断言：

- 真实 KeyEvent 左 Alt/CtrlAlt 为 `1b621b03`，expected/actual 相同。
- 受控右 Alt 布局 resolver 经真实框架 connection 为 `40`；它没有生成物理国际布局事件。
- 真实 KeyCharacterMap 的死键组合/space/不可组合输出 `c3a9c2b4c2b478`。
- 真实 ModernTerminalView 重绑退役旧 connection，拒绝其迟到 commit；新 connection 的合成中文/emoji输入只提交一次，close 不发送 pending 候选，输出 `e5a5bdf09f9880`。

这些字节均为 probe 写入的合成 fixture，不是用户输入采集。probe 不按 Create，无 VM/下载事务、任意正文/剪贴板读取或额外 reader。原件明确 `physical keyboard/OEM IME remains NOT_TESTED`，与实现边界一致。

本次 D 总体八场景是独立 step3 原件加 framework-acceptance 内七项，**19 只签其中输入一项**；其余七项属20，不将旧501、本轮重复运行或本地30项累加成更多设备场景。probe3 精确两输入 hash、unsigned→signed Manifest/dex 相同、签名记录 hash 与包相同。目标是隔离 `io.github.nyzeep.orchestella.phase5` code502；APK SHA256 `3bd497b1d1031bfe9a86e2728a46fe16b99af8714a6289d29828845baa9f0735`，probe code3 SHA256 `33deffe9092299a74aa03de5697288784dfd1426655fcec10fd25f2d1f326b02`。只读 ZIP 确认包身份和主协调既有资源摘要；未调用签名工具或解析 embedded 载荷。证书与安装/采集真实性沿既有主协调原件，不宣称本 reviewer 再次实机执行。

## 完成、限制与治理

完成新增框架证据的同轴签收与来源核对；旧源审查无需重做。未完成实体键盘布局/设备 meta、OEM IME 候选/焦点/前后台/锁屏、真实用户终端交互、原包完整软件 UX；不得由此称全票或产品全部验收通过。旧 code502 安装待机主状态是首审当时事实，新增原件现在支持隔离 code502/probe3 已运行，不回改首审历史。

未重复本地套件或 App/Gradle 检查；integration-502 的746 tests/98 suites、lint0error/0fatal/748warning是主协调集成记录，本报告未新运行，也不改写失败历史。没有新 smell 或硬违规；本次仅扩大明确的框架证据范围。

修改仅本正式 rereview 及 resume-01/review-standards 核验摘要/报告副本与 preservation。无生产、设备/adb/UI、Gradle、版本、签名、网络、ref/worktree/commit 操作，无资产清理、用户数据或凭据访问。branch 沿用户提供 main，Commit 无；Class A、旧APK/源码/失败原件保全。后续物理/OEM门由主协调安排；本次无生产回滚。Architecture Change Policy：无新owner/IPC/暴露面或契约变更，不触发新ACR，AVF ACR-01仍独立。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

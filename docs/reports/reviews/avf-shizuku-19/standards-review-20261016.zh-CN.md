# 19 独立 Standards 审查：冻结 AltGr、死键与退役输入连接

审查者：独立 Standards / Codex GPT-6.1-sol，2026-10-16。结论：**approve，仅限 D final-freeze 所列源码与已提供证据的规范轴；无确认的 P1/P2 或其他必须修改发现**。实体键盘、OEM IME 和统一构建仍为独立门，不因本报告关闭。

## 背景、scope与固定点

唯一 effort 为 `.scratch/orchestella-avf-shizuku/`；用户指定 main@428860ad9a1cb335f8be78c157b2ff57faf2a6da、dirty0.5.0/code500 已保全起点。主协调随后追加 D 冻结包审查；未用 HEAD 三点空 diff、不读取另一轴结果。

批次根为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`，D为其 android-input-recreate。固定清单 **D/final-freeze.json**；19包括 frozen-source 下 TerminalInputConnection.java 与 TerminalInputConnectionTest.java。五份 D 冻结source/test均与canonical及seal匹配，四份已有文件beforeSHA匹配，frozen-source/preservation孪生匹配。未修改的 ModernTerminalView 只用于理解既有重绑边界，不把它记为本票新增实现。

已读20261016 D Prompt、14/19原票、19实施报告、冻结输入源码/测试、D补丁及固定原件；共同规范为 AGENTS/CONTEXT、DEFENSIVE全部policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING、项目code-review技能。核验摘要为 batch/review-standards/19-20/frozen-input-verification.json 及即时preservation。报告为规范轴，Spec逐票验收另行独立。

## 硬规范检查与证据

- 输入布局边界：实际 KeyEvent 委托相同meta→unicode解析路径；右Alt仅在无显式Ctrl/左Alt时保留布局meta，不追加shell ESC；左Alt/显式CtrlAlt仍走终端modifiers。Shift/Caps未被抹掉，没有降低target/inputType或引入全局输入框架。
- 死键与UTF8：COMBINING_ACCENT先存单个pending accent，经真实KeyCharacterMap.getDeadChar边界组合base/space；不可组合保留accent+base，Unicode scalar校验避免surrogate写出。keyUp不提交，close清空accent/composition；closed路径在调用resolver/sink前拒绝。没有记录真实按键/剪贴板/用户正文。
- 既有IME语义：commit清composition、finish仅提交已有composition一次；close不自动发送未选候选；paste/UTF8/控制键/退役旧connection保持现有接缝。新增resolver只在系统边界注入，未复制第三方终端实现或引入依赖。
- 原始本地输出：D/runs/input-regression-green/tests.log.txt为 **OK (30 tests)**，对应result的javac/java均exit0，原件与preservation逐字节相同。阶段24/25/30不能加总作独立覆盖数；没有重复运行全套。
- 主协调设备原件：`device/input-framework-501.json`的stream为 **PASS real Android framework input**，leftAlt/CtrlAlt、受控AltGr、真实KeyCharacterMap死键、ModernTerminalView重绑的expected/actual bytes相符。原件明确区分受控布局与真实框架，不由adb进程exit0单独推导通过。它不是实体键盘国际布局或OEM IME体验的证据。本review没有运行设备，也没有独立重跑instrumentation。
- 实验/生产/版本：新增生产只在获派输入hunk及对应测试；外部probe和runner留D根，未新增reader、权限/IPC/FD/owner/网络面。D无版本/签名/Gradle/Manifest修改，公共版本及APK由主协调负责。新增实现自研，无第三方拷贝或许可引入。

上述范围内未发现硬规范违规。框架原件的物理真实性以既有主协调采集链为依据，本审查核固定原件/hash/孪生及源码相符性，不把自洽JSON当独立真机认证。

## 启发式 smell：判断性，非硬违规

检查了技能全部12项：Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest。repo的现终端编码器边界优先；deadCharacterResolver是实际Android系统边界，无足够理由视为投机抽象。未为命名/抽取/移动提出无触发证据的重构偏好，跳过工具已约束的格式/编译项。

## 实际完成、未完成、风险与后续

完成冻结source/before/patch与输入证据的独立规范核验，本报告及外部摘要/孪生。未完成实体键盘/OEM IME候选/焦点/前后台/锁屏验收、统一App test/assemble/lint和后续APK验收；这些不能由受控resolver/框架PASS代替。输入真实布局若产生不同CtrlAlt meta，须后续定点补证，不改变本次明确的终端快捷键口径。

本review只写指定19/20正式报告、review-standards/19-20和preservation，未改生产、tracker、版本、许可、资产或系统设置。无设备/adb/UI/安装/Gradle/网络、commit/ref/worktree；Commit无，branch沿用户提供main，工作区未提交状态保留。没有清理或生产回滚需求，Class A及旧原件保全；新.log证据0。Architecture Change Policy：本票属于契约内bug修复/测试扩展，无新暴露面/VMowner，不触发新ACR；AVF ACR-01不被本审查代答。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

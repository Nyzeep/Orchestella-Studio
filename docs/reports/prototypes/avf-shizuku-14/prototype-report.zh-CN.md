# 原型报告：14 右 Alt、死键与 IME connection 退役

- 日期：2026-10-16；执行：Worker D / Codex / GPT-6.1。
- 背景与范围：执行20261016 Worker D及共用协议；先运行布局事件到精确 UTF-8 sink 的 red，再由19承接契约内修复。原14的物理键盘与 OEM IME 验收门保留。
- 分支/基线：main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，接入时 dirty 0.5.0/code500；旧36份交付与其他 worker 并行改动保留。无 commit、ref、worktree、reset、clean。
- 外部 canonical 根：`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/android-input-recreate/`（下称D根）；Class B长期保全至维护者验收归档，不自动清理，原始证据即时复制至 preservation。

## 假设与实验环境

1. 原硬件键路径清除全部 Alt meta 后再查 Unicode，会把右 Alt 布局字符误编码为 shell ESC+base。
2. 原路径不能处理 Android 的 COMBINING_ACCENT 编码；应由 KeyCharacterMap.getDeadChar 组合。
3. 已有中文候选、emoji、close零提交、退役 connection 迟到无效应保留。

先检查现有 Gradle配置、两套 preserved Maven缓存、旧构建目录：没有发现 Robolectric、android-all、ActivityScenario运行环境；不下载新测试依赖。布局边界采用固定 resolver，不执行恒零的 Android stub 字符查询；实际生产 TerminalInputConnection 与精确字节 sink 由 JDK17/JUnit4.13.2 运行。Android SDK只提供接口与常量。

之后主协调使用独立 `io.github.nyzeep.orchestella.phase5` code501 和原生 Instrumentation probe02 在 device-01/user0 实际运行 Android KeyEvent、KeyCharacterMap与ModernTerminalView重绑；Worker D未操作设备、签名、安装或Gradle。原产品包因签名不兼容停止安装，历史数据保持，不以卸载/清数据解决。

默认 IME 的只读原件为 `batch-01/device/input-environment-readonly.json`：`com.baidu.input_hihonor/com.baidu.input_honor.ImeService`；9项 external 标志为false，但缺可信typed inventory，`physical_keyboard_connected=unknown_no_trusted_typed_inventory`。不据这些标志断言无实体键盘。

## 实际步骤与原始观测

| 阶段 | 入口/原件（相对D根，设备原件相对batch-01） | 结果 |
| --- | --- | --- |
| AltGr red | `run-input.py input-altgr-red`；`runs/input-altgr-red/result.json` | javac=0，JUnit=1；24项中1失败。右Alt+Q预期`40`，实际长度2、首字节`1b`，旧路径为ESC+q |
| AltGr green | `run-input.py input-altgr-green` | exit0，24项通过 |
| deadkey red | `run-input.py input-deadkey-red` | JUnit=1；25项中1失败，dead accent事件被拒绝 |
| deadkey green | `run-input.py input-deadkey-green` | exit0，25项通过 |
| 完整输入回归 | `run-input.py input-regression-green` | exit0，30项通过；左Alt/CtrlAlt、Shift/Caps、死键space/不可组合、keyUp/close、中文emoji、paste、多字节删除与旧connection负例 |
| 真机Android框架 | `device/input-framework-501.json` | 主协调实际执行，stream为PASS real Android framework input；不能只看adb进程exit0 |

真机框架精确sink原件：

| 路径 | expected / actual |
| --- | --- |
| 真正KeyEvent左Alt+B、CtrlAlt+C | `1b621b03` / `1b621b03` |
| 受控右Alt布局resolver进入真实connection | `40` / `40` |
| 真正KeyCharacterMap.getDeadChar acute+e/space/x | `c3a9c2b4c2b478` / `c3a9c2b4c2b478` |
| 真正ModernTerminalView重绑、旧connection迟到、中文emoji一次提交、close零提交 | `e5a5bdf09f9880` / `e5a5bdf09f9880` |

上述数据只含固定合成fixture，不采集用户按键、密码或剪贴板正文。所有red/green原始stdout、命令、exit保留于D根runs及preservation；设备原件由主协调保存至batch-01/device与preservation。

## 结论与触发器判定

- 假设1：成立于受控布局边界，已运行red；实际实体键盘布局仍待验。
- 假设2：成立；本地red与真实Android getDeadChar green均已取得。
- 假设3：本地回归与真实ModernTerminalView框架重绑通过；真实OEM IME候选、焦点、前后台和锁屏交互尚未运行。
- 不声称已复现丢字、ANR或性能问题；本票不涉及benchmark。
- 未触发架构重开：无新权限/IPC/FD/VMowner、Guest通道或主机出站。修复沿原terminal sink，属于ARCHITECTURE_CHANGE_POLICY §3的bug修复/回归扩展。
- 能力矩阵无变更建议；框架通过不能升级为物理键盘支持已验收。

## 产物、修改与剩余门

生产修复归 [19实施报告](../../tickets/avf-shizuku-19/implementation-report.zh-CN.md)，限定TerminalInputConnection及现有Test；ModernTerminalView无需修改。原型源码仅D根`framework/`及版本化`framework-v3/`，实验代码不进入生产模块。probe版本由主协调统一，后续probe03为3/0.1.2，不以同版本换bytes。

`final-freeze.json`、`worker-d.patch`、`frozen-source/`记录当前5个D输入的精确SHA/bytes和写前SHA；`before/`与`preservation/before/`保全局部原字节。源main保留未提交，无commit列表。

## 2026-10-16 code502框架复验与审查签收增补

主协调在实装code502 App/probe3上复验输入，D已实际读取`batch-01/device/input-framework-502-acceptance.json`：stream为PASS real Android framework input，左Alt/CtrlAlt=`1b621b03`、受控AltGr=`40`、真正getDeadChar=`c3a9c2b4c2b478`、ModernTerminalView重绑/close=`e5a5bdf09f9880`，expected/actual全部匹配。原件与`batch-01/preservation/device/`对应文件逐字节一致，非仅凭adb exit0判定。

19冻结源码与既有框架证据的独立Spec/Standards两轴均approve，无新增必须修改发现；原文为`docs/reports/reviews/avf-shizuku-19/spec-review-20261016.zh-CN.md`和`standards-review-20261016.zh-CN.md`，D已读取。保留审查当时对实体/OEM门的限定，不改写独立原文。`integration-502/test-apk-audit.json`记录完整App746项全过、lint0Error/748Warning及D源码匹配冻结；D亦只读核对原包配置JUnit/lint XML得到同样结果。App746项不与本票30项累计。

此次仅更新报告/票并新建D根`acceptance-docs-502/seal.json`，冻结5源码/测试、原seal、probe3输入与旧证据不变。后续C三项P2修复由主协调按503重新构建，D源不变，502结果不外推为503已通过。

剩余门：真实实体键盘（布局名称/右Alt/死键）和本机OEM IME（候选、emoji/paste、开合、前后台/锁屏）仍NOT_TESTED；手机连接或external=false不足以判断外设具备。物理旋转/uiMode及真实下载/创建事务跨重建也未验，归15/20单列；不由输入框架或双轴approve关闭。本报告不代替独立review。

越界检查：D无adb/UI/安装/grant/VM启停/Gradle/版本/签名/Git变更操作；无新依赖/下载/GPL参考代码复制、C:项目临时输出或对外传输，无新.log。rollback仅19有界hunk，不覆盖旧实现/并行改动，不清理Class A资产。

报告人：Worker D / Codex / GPT-6.1 ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

# avf-shizuku-02：两权限与诚实预检本地实施报告

- 工单：`.scratch/orchestella-avf-shizuku/issues/02-honest-authorization-preflight.md`。
- Owner：A / 主协调；执行：Codex / new-provider/gpt-6.1-sol；日期：2026-10-15。
- 分支/基线：main / `428860ad9a1cb335f8be78c157b2ff57faf2a6da`；无本票commit、push、worktree。主协调独立管理集成版本0.5.0/code500，A未修改版本条目。
- 来源：`docs/research/2026-10-15-avf-shizuku-upgrade.md`；外部E/research-auth/report.zh-CN.md，其中E=`LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015`。

## 目标与完成状态

本地源码已完成两权限一致性修复，交主协调统一集成测试与独立review；当前报告不签收真机。将“Android权限预检”与“本应用实际AVF/Guest运行”分开，保留现有软件路径与用户硬件选择。

| 验收项 | 本地实现/行为证据 | 尚需验证 |
| --- | --- | --- |
| MANAGE/CUSTOM分别缺失、全缺、全有、读失败 | 两项GRANTED/DENIED/UNKNOWN独立事实；9组合纯模型回归；任一非GRANTED为NEEDS_AUTHORIZATION并指出权限名 | 真实系统permission读取/OEM限制 |
| Main/Setup/Settings与模板一致 | 两条精确ADB命令；确认动作重读两项；三处显示独立状态，Settings/Main恢复时刷新 | Android UI与系统往返实际展示 |
| 不误称授完即AVF成功 | 成功只显示两项权限确认；预检badge标启动待验；统一说明API/nonprotected/Guest/GPU/性能未知 | 本应用真实create/run/Guest闭环由后续设备票负责 |
| 精确1CPU限制且不改配置 | 统一说明当前硬件适配器只准确表达1vCPU；本票未写硬件配置、未改adapter或MATCH_HOST | 多核选择真实回退与设备资源验证 |
| feature不支持/unknown不隐式启动VM | 沿用只读facts/evaluator软件路径；没有新建或启动VM的探测 | 设备capability/API精确版本待验 |

## 修改模块

- `runtime/capability/.../AvfCapabilityEvaluator.java`：增加CUSTOM常量、PermissionState、双权限必要门、逐项原因和诚实预检badge。
- `AvfHostFacts.Builder`：保留旧五参兼容，CUSTOM默认为UNKNOWN；显式六参/两PermissionState构造器供已知双事实使用。旧caller不能通过单项grant隐含授权第二项。
- `AvfAuthorizationWizardModel.java`：两命令、有效包名输入、双权限确认；旧single boolean确认不再能绕过CUSTOM。
- 两项纯Java契约测试：保留原回归，新增MANAGE-only、双确认、两命令、预检badge与两权限九组合。
- `app/.../AvfHostFactsProbe.java`及JUnit测试：分别读取两权限，异常为UNKNOWN；UI共用状态label。
- `MainActivity.kt`、`SetupWizardActivity.kt`、`SettingsActivity.kt`、`strings.xml`：授权确认/展示、真实预检文案及CPU说明。与03共享UI由同owner顺序实施，03client入口另票说明。
- 未改AVF engine/adapter、Guest、设备工具、Instance/根盘/凭据、ADR/policy/refs。

## 重要取舍

READY枚举继续表示声明/权限预检满足，保持现有launch preference与失败回退，不用检测阶段隐式create VM。hardware capability matrix接口沿用；用户可见预检badge不再从它直接推导硬件已运行。真实活动session对应的AVF/QEMU徽章原有逻辑保留。

权限读取异常不会写成DENIED，也不会默认通过。feature/property现有容错仍只读，不能凭芯片或feature宣称custom Linux可运行。本票不执行任何grant/revoke；用户复制的两条命令是显式步骤，不是App执行命令。

原始403实验的两权限+1CPU失败在hiddenAPI，405 QEMU验证不被更名为AVF成功。本票只改善准备状态，不声称解决那个接口门。

## 实际red→green与集成状态

- 主协调Gradle：`:runtime:capability:avfCapabilityEvaluatorTest`真实assert red（旧MANAGE-only误READY）→green。E/evidence/gradle-capability-manage-only-red.log.txt及green.log.txt，根preservation。
- JDK17纯模型、外部classes：`wizard-two-command-red.json`编译0/测试1→green0/0；`wizard-manage-only-confirm-red.json`0/1→`wizard-both-confirm-green.json`0/0。证据均在E/implementation-02及preservation。
- 预检badge：`preflight-badge-red-utf8.json`0/1（actual硬件加速）→`preflight-badge-green.json`0/0。最初默认Windows编码导致stdout解码失败的记录保留，后续UTF8重跑才作为可读证据。
- 两项PermissionState九组合：`both-permission-matrix-final.json`编译0/测试0。只证明纯模型行为。
- App probe：主协调`:app:testDebugUnitTest --tests io.github.nyzeep.orchestella.AvfHostFactsProbeTest`编译成功，5tests/1fail，CUSTOM DENIED被旧读取当UNKNOWN的red。E/evidence/gradle-app-custom-probe-red.log.txt。
- 中途组合green因03进行中FakeTransport.lastRequestCode同sourceSet编译输入不完整而失败，尚未执行02目标测试；已修正并采用完整接线后统一冻结，不能将该编译失败标成02行为green。
- 2026-10-15 15:20 UTC左右：A冻结App/cap完整接线，提交`:runtime:capability:check`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`给主协调统一runner。**本报告写入时这些最终结果待回填，不宣称通过。**

真实UI/权限、设备与AVF/Guest测试均未运行；没有性能样本，不涉及benchmark。无新自建测试框架；原有JavaExec测试继续接check。

## 首次集成结果与审查修复

主协调回报`client-integration-checks`已exit0：capability完整check、App完整test、lint及4个SDK provenance任务完成；此时未assemble，未产生500新APK。具体测试计数由主协调结果追加，A不估算。

Spec审查指出创建向导的静态显示链缺口：授权确认后旧实现只重新绑定硬件区，没有刷新AVF卡片，成功或新的UNKNOWN仍可能显示旧两权限状态。已按主协调明确范围最小修复：`bindWizardAvfCard`接受本次读取的facts；recheck在成功/失败分支前统一刷新badge/details和授权按钮；不再调用重绑定CPU/RAM的`bindStep3Hardware`。硬件选择字段与slider不被此刷新修改。

既有App probe seam新增deny→both granted→CUSTOM读取失败UNKNOWN的重查/label/readiness回归。该seam证明事实刷新模型，UI链修复是静态源码证据；没有真实UI运行时，不声称此单测能证明卡片绘制或设备体验。主协调已获第二次冻结通知，将offline重跑capability/App完整测试/lint，**最终结果尚待回填**。

## 风险、回滚与后续动作

单权限caller语义收紧可能暴露原先误READY的路径，属本票预期；生产probe已迁移双项，旧入口不绕过。共享UI需03集成compile/lint/独立review；没有设备时仍不签收UI体感。

定点撤回本票模型/读取/UI/测试即可回滚，不修改磁盘或Android授权。未引入IPC、FD或owner变化，不触发本地实现ACR；新UserService/VM代理仍保持独立ACR门。未越界、未清理资产或历史证据。

建议状态：ready-for-review（本地源码完成，集成结果与独立review由主协调补齐后裁定；设备项明确待验）。

## 主协调最终验收补记

本地状态：**completed-local**。上文pending是作者封板时状态；最终reviewed-client-offline exit0，App715 tests/96 suites、failures/errors/skipped=0、capability实际入口通过，lint无Error/Fatal但747Warning。唯一500/0.5.0 APK构建/签名/资源字节门已由13完成。独立规范轴approve，Spec轴首审卡片P2修复后approve，当前均0发现；见docs/reports/reviews/avf-shizuku-02/及E/integration/client-reviewed-verification.json。不把模型/静态卡片修复写成真机UI通过，05/06/07设备门保留；无commit/push/发布。

报告人：A / Codex / new-provider/gpt-6.1-sol；主协调最终补记 ｜ 日期：2026-10-15 ｜ 耗时：未测量。

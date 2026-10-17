# 05 / QA3 新增功能独立 Standards 复审

审查者：独立 Standards / Codex GPT-6.1-sol。结论：**request-changes，新增 S-A-03 / P2 一项，无 P1**。旧 S-A-01、S-A-02 及 B 的 S-B-01 已闭合结论保持。此报告追加 QA3 固定权限、capability、ledger 及已提供设备证据的规范审查，不覆盖首审或旧定点复审。

## 背景、scope 与固定点

沿用户指定唯一源码 `Orchestella_dev`、main@428860ad9a1cb335f8be78c157b2ff57faf2a6da 的 dirty 工作区和本批 code502 起点。外部 batch 为 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`。只使用 `research-auth-device/identity-v3-frozen-source.json` 与 `qa3-apk-01/source/` 的精确 19 项输入，不以进行中的 canonical 替代。19 项在 APK 与 compile 两轮 source 及各自 preservation 共四处逐项 bytes/SHA 全匹配；APK 构建记录的输入也匹配。

已完整读取 AGENTS、CONTEXT、DEFENSIVE 全部 policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING、20261016 两份剩余交接及 reviewer 协议、resume dispatch、旧同轴 index 和指定正式报告；本次逐读 QA3 的 Java/AIDL/Manifest/build runner，未读取需求轴。规范依据还包括 ADR-0010 的数据/暴露面边界。既有完成审查和全套测试未重做。

独立核验位于 `batch-01/resume-01/review-standards/qa3-frozen-verification.json` 及即时 preservation；脚本 `verify-qa3-d.py` 只做限定 hash、ZIP 与既有原件解析，不运行编译/设备/测试套件。

## 新硬规范发现

### S-A-03 · P2 · 同一权限 session 的旧调用 deadline 可退役后续调用

- 文件：冻结 `qa3-apk-01/source/src/main/java/io/github/nyzeep/orchestella/researchauth/ProbeActivity.java`，SHA256 `1e8e444552521ff2ce8a621fc03ccd2e33cb431cda53b88c87ff12e388b53e6c`。常规 1 起点行 **144–164** 的 `submit`，以及 **226–240** 的 `permissionAction`。
- 具体触发：打开权限 session 或 readback 的调用 A 在其 5 秒 deadline 前已完成，UI 的 busy 已清。用户在该 deadline 前开始调用 B，仍复用相同 session epoch。A 的 `postDelayed` 未撤销，也没有每次 operation 身份；它只检查 visible、binding==epoch 与当前 busy，因 B 正忙而执行 `++epoch; permissionSession=false`。B 即使在自己的 5 秒内成功，其完成 receipt 也因 epoch 已变而被丢弃。
- 影响：B 若为 grant/restore，Binder 侧权限变更仍可能发生，App 却退役了当前 session 并关闭后续 restore/readback 入口。不能把这个旧 timer 当 B 的真实超时，或从 UI 退役推断底层已结束。本项是冻结源码中可达时序推导，**没有声称真机复现，也不将其归因为既有 MANAGE 操作后的 App/US 退出**。
- 对应规范：20261016 Worker A/reviewer 要求旧 generation 不污染新操作、deadline 与底层取消分开、固定权限恢复及可信结果；REPORTING §0、§6 要求结果与实际操作可追溯；DEFENSIVE README 的阶段事实和失败保留要求。
- 必需最小修复：每次 `submit` 绑定独立 operation token，完成时撤销或失效本次 deadline；旧 deadline 不得修改后续调用的 epoch、busy、permissionSession 或 receipt。真正当前调用截止仍明确底层未取消，不能重试或自动撤权。
- 定点验收：通过 Activity 实际使用的接缝固定 A 完成→同 epoch 的 B 在途→触发 A 旧 deadline→B 完成的顺序；核 B 未被退役、结果保留、恢复入口仍可用。同时核当前 B 真超时、旧 connection/lifecycle、底层仍在途时拒绝新调用。原 QA3 冻结与失败历史保留，修复另 seal 后复核。

## 已核规范与证据签收范围

| 项目 | 结论与边界 |
| --- | --- |
| QA/caller/user | 独立 QA 包固定；服务要求真实 shell UID2000、Context 的 QA owner UID、Binder calling UID、协议与正且递增 epoch。permission IPC 先 require，再 clearCallingIdentity/finally restore。目标限定该 QA 包、user0、MANAGE/CUSTOM 枚举，声明 development、grant/revoke 能力、target UID 与 epoch 在操作边界复核；不接受任意包/path/command/user。 |
| 权限计划/恢复 | UNKNOWN/STALE/REJECTED 不当 granted；逐项 grant 后 readback，失败不继续下一项、不自动重试。只恢复本 live session 确认从初始 DENIED 变 GRANTED 的新增项，初始 GRANTED 不撤；ledger 失联不自动重建。S-A-03 是新增操作时序遗漏，不推翻这部分固定范围设计。 |
| capability | 只固定 class/Context constructor/getCapabilities/两常量，无 create/load/run/delete。receipt 的 declaration-only、provesAvfUsable=false 与源码一致。US 原件真实返回 bits1、protected=true、nonprotected=false；App 停在 constructor/METHOD_UNAVAILABLE。不能推出 App hidden API 可用、VM owner 可用或 Full Linux 成功。当前 nonprotected 路线的 ROM 硬门保持。 |
| ledger/secret | initial identity 的无文件操作陈述限定于 snapshot，Context/fixture/receiver 副作用另列。后失败不抹掉已创建/写入事实，异常仅固定类别/errno。只新 QA 私有 namespace、固定合成32B文件与1B mutation，无用户盘/token/embedded 载荷或任意用户正文读取、日志出口。 |
| FD/mode | 以 opened FD 的 fstat/fcntl 校验 owner/regular/size/exact RO/RW/不同 inode/replay；固定 O_EXCL/O_NOFOLLOW，不 chmod/relabel 私有目录。service finally 关闭 receiver 副本，caller originals 用资源作用域关闭。真实原件支持正向读写及错模式/type/同对象/replay/symlink/stale/closed 边界；stale 拒绝后 caller 保持有效，但没有 receiver receipt，不能把它升级为该负门 receiver close 的独立测量。 |
| 资产/许可/构建来源 | QA3 signed APK 61919B，SHA256 `2f7d67f33e6b8702695a42d0fe1fbf661bdb14b8d7f3ad7ed38940dc6450840f`，compiled Manifest 为独立 QA code3/0.1.2-identity。Manifest/dex/notice 与 unsigned 相同，无重复 ZIP 条目、native/Guest 资产。固定四个 SDK13.1.5，MIT 全文与 snapshot/包内相同。证书仅引用主协调既有记录，本 review 未调用签名工具。build runner 先双份保全后编译 snapshot；本次未执行该 runner。 |

设备原件分层如下，不以命令名或 adb exit0 代替内部结果。

- `device/qa3-context-fd-receipt.json`：App state 新 namespace/sentinel 可写且关闭已观察；US 在 DATA_DIR_LSTAT 得 OS_ERROR_13/EACCES。App→US 的 positive SYNTHETIC_READ_WRITE_OK、RW mutation、receiverCopiesClosed 与 callerCopiesRemainOpen 有真实原件；各拒绝结果分别核对。**只支持 App→US 合成 FD，不证明 Java path、virtmgr/crosvm 消费或 VM state 可写。**
- `device/qa3-permission-session-initial.json` 与 manage-only receipt 的内部 payload 相同，后者仍是旧初态。后续 App 冷读回确认 MANAGE granted/CUSTOM denied，再由明确 ADB revoke 恢复双 denied。退出原因原件明确 cause_inferred=false，未裁定原因；typed grant/restore 的完整软件 UX 仍未签收。
- 新 `resume-01/permission-control-summary.json` 的六条固定 ADB grant/revoke 均与原始命令及六次 App COLD readback 匹配：CUSTOM 单项→恢复；MANAGE 单项→两项 granted→先撤 CUSTOM→再撤 MANAGE。最终双 DENIED，remaining_test_additions=[] **仅属于该 ADB 对照计划**。不把这些对照写成 QA 按钮完成两项 grant/restore 或生产授权向导成功。
- `device/resume-qa3-before-app-death-receipt.json`、`resume-qa3-app-death.json` 与 `resume-01/device/after-qa-app-death.json` 支持：已绑定的隔离 QA 经明确 am force-stop 后 App/US 均未检出、Shizuku server 仍存活、crosvm/virtmgr 均未检出。前置 session 已 END、无 VM/权限变更在途；这不是任意 crash、server death、撤回或持有真实 VM 时的死亡收敛。

72 项 Java 合成检查按 24 session +11 FD metadata +27 permission plan +4 callback +6 ledger 分列，与冻结同源及运行记录相符；没有重跑或当作 Android Binder/SELinux/权限 UX 通过。全部所核 QA3 原件和指定保全孪生匹配。

## 完成、限制与治理

完成 QA3 新增范围首轮独立规范审查、设备对照/死亡原件的限定签收与新 P2 报告。未完成 S-A-03 修复后复审、typed grant/restore 完整软件 UX、首次机主配对/拒绝记忆/撤回/server death/OEM 矩阵、真实 manager/Context/VMowner/Guest/stop/性能。真实 AVF 成功样本仍0，ACR-01 未裁定；新生产 owner/特权 IPC 仍需原架构门。

**QA1 的 ProbeActivity.java、IdentityProbeService.java、IIdentityProbe.aidl 三份历史精确 bytes 缺口保持**，不使用 QA2/QA3、反编译或新保全倒填。原首审与旧 index 保持原字节；本次报告不重开已闭合 A/B 三 P2。12 项 smell 基线按项目例外核过，未新增有证据且需要本轮修改的判断性 smell；S-A-03 属行为问题。

修改仅本正式 rereview、指定 resume-01/review-standards 的核验脚本/摘要/本报告保全。没有生产、设备/adb/UI、Gradle、网络、版本、签名、ref/worktree/commit 操作，无清理或回滚用户数据；Class A、旧包/源码/失败证据保全。branch 沿用户提供 main，Commit 无。修复应由原 owner 在隔离 QA 允许范围完成，新 seal 后定点复审。本审查未批准架构或暴露面变化。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

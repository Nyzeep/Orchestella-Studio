# 05–06 独立 Spec 增量复审：QA3 固定权限、Context/FD 与能力取证

结论：**request-changes，新增 S-06 / P2，P0/P1 为 0**。同一权限 session 内，前次 submit 的旧定时器可能提前退役稍后在途的 grant/readback/restore。S-01 的旧 disconnect 缺陷已在 QA3 冻结源码及公共回调接缝范围关闭；S-02 的后续源码快照机制已修正，但 QA1 缺失三份原源码的历史限制继续保留。本报告不签 05/06 completed、软件内双权限成功或 AVF 可行性通过。

## 背景、对象与独立范围

审查者：恢复后的独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol，2026-10-16。延续本目录 `spec-review-20261016.zh-CN.md` 和 `batch-01/review-spec/first-review-closeout.json`；首审原件不改。已完整读取 AGENTS、CONTEXT、全部防御 policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING、两份 20261016 剩余交接的完整 review/worker 协议、resume-01/dispatch.md，以及 08/17/18/19/20/22 同轴报告。原 B S-03、C18、D19/20、E22 的已审范围沿原结论，不重复全套。未读取另一轴报告或以其结论代替本次判断。

源码根仅 `Orchestella_dev`；branch/HEAD 沿用户给定 main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，dirty 原实现保留，本轮恢复起点当前 code502。未运行 Git/ref/版本命令，无 commit。比较对象是 QA2 首审冻结与 QA3 `research-auth-device/identity-v3-frozen-source.json`、`qa3-apk-01/source/` 精确 19 输入，另将 notice 作为单独构建资产核对。没有以 HEAD diff 漏掉原 dirty 实现，也不纳正在修订的 QA4。

外部根 E 为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`。本次核验文件为 `E/resume-01/review-spec/check-frozen-evidence.py`、`frozen-evidence-checks.json`；即时保全于 `E/resume-01/preservation/review-spec/`。报告和证据属于 Class B，保留至维护者验收归档，不自动清理。

## 新发现 S-06 / P2：旧 submit 超时可退役同 epoch 的后续权限动作

**对应需求**：06 第 9、14–16 行要求用户明确选择、逐权限实际 readback、partial 不报成功、撤回/中断正确恢复；QA3 固定动作包 `identity-v3-actions.zh-CN.md` 第 33–35 行规定同一 live session 内显式 grant/readback/恢复新增项，失联后恢复归属必须保持未知。20261016 Worker A 要求旧代结果无效、逐门错误不丢前序事实。

**冻结位置**：`qa3-apk-01/source/src/main/java/io/github/nyzeep/orchestella/researchauth/ProbeActivity.java`，`submit` 第 143–163 行，权限 session 第 196–201 行，`permissionAction` 第 225–239 行。冻结文件 15489B / SHA256 `1e8e444552521ff2ce8a621fc03ccd2e33cb431cda53b88c87ff12e388b53e6c`。

**具体触发**：按钮 7 的 snapshot 在 t=0 提交并很快完成，`submit` 留下 t=5 秒的 Handler 回调。权限 session 保持同一 epoch。约 t=4 秒用户显式发起 grant 或 restore；新调用设置 busy=true 且尚未返回。t=5 秒先前 snapshot 的回调只判断 `visible && binding == epoch && busy`，没有本次 operation 身份，也没有取消旧回调，于是将 epoch 加一、permissionSession=false，并报告 `CALL_DEADLINE_UNDERLYING_NOT_CANCELLED`。新调用实际只有约 1 秒，却被旧调用的 deadline 提前退役；其结果因为 binding 不再等于 epoch 被丢弃。系统 grant 可能已经发生，原 live-session 软件恢复入口却失去，后续只能由主协调按初态另行处置。readback/restore 之间也可形成同样顺序。

**必需最小修复**：为每次 submit 建独立 operation token 或完成后取消对应 deadline；只有该具体调用仍是当前在途操作时，才允许其超时退役 session。旧完成/旧超时不得修改新调用的 busy、epoch 或恢复入口；继续保留底层单在途，不能把 UI 超时冒充 Binder 已取消。补“snapshot 完成 → 同 epoch 后续 grant/restore 在途 → snapshot 旧 deadline 到达”的受控回调回归，以及当前调用真正超时、离开生命周期、底层迟到的对照。

这是冻结源码的直接时序推导，reviewer 未新增执行或声称设备复现。现 72 项 Java 公共接缝检查不包含 Activity/Handler 上述顺序。该缺陷不能被用来解释当前 MANAGE 后 App/US 退出的具体因果。发现已即时交主协调；其另派后续修复不回写本次 QA3 冻结结论。

## 既有发现的定点复核

| 首审项 | 本次独立核对 | 状态与限制 |
| --- | --- | --- |
| S-01：旧 disconnect 退役新绑定 | Activity 的 `onServiceDisconnected` 调用 `ProbeConnectionEvents.disconnected`；visible、binding epoch、connection 对象三者均匹配才清句柄和递增 epoch。red 编译0/run1 原件断言 late A disconnect retired active B；green 编译0/run0，4 场景包括当前断连、旧代、旧对象和不可见生命周期 | **冻结源码/合成公共回调边界关闭**；不称 Android 异步投递设备复现 |
| S-02：构建仅存 hash 未保原源 | `build_qa.py` 第 82–101 行编译前双份保存 src/manifest/runner/notice 原 bytes；AIDL/javac/aapt2 从该 source snapshot 读取。19 输入在 compile02/APK01 原件与 preservation 全部匹配 seal | **后续 QA3 快照机制已修正**；不签独立重建通过，不抹掉 QA1 三源缺口 |
| QA1 历史缺口 | QA1 Activity/Service/AIDL 原 bytes 缺失事实沿首审及 `qa1-source-preservation-gap.json` 保留 | **历史限制持续**；未用 QA2/QA3、反编译或新 hash 补造旧源码 |

## QA3 完整新增需求核对

| 需求 | 冻结源码与已存原件核对 | 签收边界 |
| --- | --- | --- |
| 固定 QA/user0/两权限 | AIDL 只 expose 固定 typed 操作；MANAGE/CUSTOM enum、常量包名；shell UID2000、Context 包/目标 UID、requested development、GRANT/REVOKE、system/policy fixed flags 在实际 adapter 检查；无任意包/path/command/user 输入 | 源码范围符合，S-06 单列；OEM 系统负例尚未闭合 |
| caller/protocol/epoch | 每 IPC 使用 Binder.getCallingUid 检查 owner，再 clearCallingIdentity；正递增 epoch、当前 active/end/destroy 拒绝。daemon=false、remove=true、固定 destroy id | Java caller gate 和真实 stale/end 有证；跨 UID Binder 负客户端、完整 death/撤回仍未签收 |
| 显式选择/readback/恢复 | 每项选择逐读；第一项失败停止，UNKNOWN/STALE 不签 BOTH_GRANTED，已有 grant 不记新增；restore 只当前内存 session 确认新增，GRANT/REVOKE 资格分开 | 设计方向符合；S-06 影响实际 Activity 调用；软件完整恢复待修复与设备证据 |
| manager capability-only | 固定 manager Context ctor/getCapabilities/两常量，无 create/load/run/stop/delete；结果字段保留 actor 与 generation | 只读声明门，未新增生产 VM owner/IPC；ACR-01 不被代答 |
| 分 stage 与副作用 | Context、fixture、FD IPC 每 step 独立记录类型/errno；初始 identity 的 NO_FILES 仅限定该子快照；mkdir/create/write/dup/symlink/close 已观测 facts 单调保留 | 失败后不抹前门；不把 initial NOT_CREATED/NOT_QUERIED 当整轮无调用 |
| 合成 PFD 来源、角色与关闭 | App 新私有 namespace，0700/0600、O_EXCL/O_NOFOLLOW；fstat regular/owner/size/mode、distinct inode、replay/sentinel；receiver finally 关闭 Binder 副本，caller try-with-resources | 真实 App→US 合成 FD 正负门有证；不是 virtmgr/crosvm 或 Java 路径入口可用性证明 |

未见本冻结包增加生产 grant 入口、任意 exec/path、用户盘拷贝、权限放宽或 VM 调用。产品子票21没有由本报告放行。

## 设备原件与实际签收

本 reviewer 只读已存原件及固定命令，不执行 adb/UI/安装/权限操作。主要原件及 preservation 字节一致，不能仅凭 adb exit0 或 receipt status 签通过。

- `device/qa3-context-fd-receipt.json`：App 新 QA state 可写，写2B、mkdir/sentinel/close effects 保留；US 同 Context 在 DATA_DIR_LSTAT 为 OS_ERROR_13。正 RO/RW 合成32B返回 SYNTHETIC_READ_WRITE_OK，App 实际观察固定1B修改；receiverCopiesClosed=true，caller 原件保持。wrong RO/RW、同 inode、pipe、replay、symlink、stale/end 均有对应拒绝字段。closedFd 只签发送或接收边界拒绝，具体端未知；不是全进程 FD 泄漏审计或迟到 FD 设备收束。
- `device/qa3-manager-capability-receipt.json`：US UID2000 实际 getCapabilities 返回 **bits=1、protected=true、nonprotected=false**；APP_UID 在 manager ctor 为 METHOD_UNAVAILABLE。当前 nonprotected custom Linux adapter 前提不满足。声明不等 VM 运行，不拿 protected/Microdroid 或系统其他 VM 补样本；真实 owned AVF/Guest/性能样本仍 **0**。
- `device/qa3-permission-session-initial.json`：固定 QA/user0/UIDAPP_UID，两项 DENIED、development requested=true、GRANT/REVOKE=true、flags0；顶层 shell 自身两项 granted 与目标 QA 权限分开。
- MANAGE 按钮后的 `qa3-permission-manage-only-receipt.json` 与初始字段/elapsed 完全相同，未落 action 结果。`permission-interruption-readonly-01.json` 证明 user0 MANAGE 已变 true 且 App/US 均无进程。**不能由旧 receipt 推断 grant 未执行；退出具体原因 UNKNOWN。**
- `qa3-after-manage-app-readback.json` 重开后 App 自身 MANAGE granted/CUSTOM denied、client=true，签真实 partial。固定 `qa3-restore-manage-adb.json` 命令为 `shell pm revoke --user 0 io.github.nyzeep.orchestella.researchauth android.permission.MANAGE_VIRTUAL_MACHINE`，exit0；`qa3-after-manage-restore-readback.json` 冷读回两项 DENIED、client 仍 true。

上述只签“初态 → MANAGE 发生 → 中断 → 重开 partial → 固定 ADB 人工恢复 → 两 DENIED”的闭环，**不签 typed callback、同 session 软件 restore、CUSTOM 或软件内双权限成功**。后续主协调的其他控制批次不在本冻结签收范围，不据此声称当前设备权限终态。normal remove 也不替代独立 App/server death、client 撤回或设备重启。

## 测试真实性与内容关联

reviewer 未运行任何重复套件或重建，仅定点 hash/zip。QA3 compile02 AIDL/javac exit0/0，APK01 AIDL/javac/D8/aapt2 全0；19 项 seal/receipt/两轮 source 与各 preservation 一致，notice 单独一致。unsigned APK 52776B / SHA256 `65db20f3d650bf7640cc1f9a66f64d8c2e2d5be8df3a400efab3892ecc00a653`；signed QA3 61919B / SHA256 `2f7d67f33e6b8702695a42d0fe1fbf661bdb14b8d7f3ad7ed38940dc6450840f`，仅核已有元数据与 bytes，未重验签名或读私钥。JAR30条 class 与已有 classes 同字节；APK classes.dex 与 D8 输出一致、唯一 dex、无重复 zip 条目，unsigned 内容与 signed 包对应条目一致。该关联不是独立精确重建证明，也不是 ROM 物理事实认证。

已有 `qa3-regressions-01/result.json` 为 session24 + FD metadata11 + permission27 + disconnect4 + ledger6 = **72**，编译/五 harness 全 exit0；不累加前期重复 green。实际 Java 边界接合成输入，不签 Android/Binder/SELinux 系统验证。QA1/QA2 原失败与首审全部保留。

## 实际完成、剩余与边界

实际完成：本 05–06 合并 canonical Spec 复审、外部同路径副本、定点核验脚本/JSON及孪生保全；新增发现即时反馈主协调。只写 `resume-01/review-spec`、`resume-01/preservation/review-spec` 与本轴正式 spec-rereview；未改首审、生产、原型、tracker、版本或其他 owner 文件。未另建06平行报告目录。

剩余：S-06 新冻结修复定点复核，软件实际 grant/readback/restore，跨 UID Binder、迟到 FD、完整 death/撤回/重启/OEM/首次本机配对、完整跨升级 owner/state 与生产向导。QA1 原三源限制无法由后续报告补回。风险是旧 deadline 在权限变更时丢掉有效结果/恢复入口，必须在产品化前修复，不能把 ADB 恢复包装为软件绿。

无生产/设备/adb/UI/Gradle/版本/ref/签名/commit/网络/系统操作，无整仓或凭据读取，无清理、外发或另一轴报告读取。Architecture Change Policy：本次不实施架构变更，S-06 属实验调用时序局部修复；ACR-01/07/08 的新 owner、真实平台及停止门继续有效。

报告人：独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

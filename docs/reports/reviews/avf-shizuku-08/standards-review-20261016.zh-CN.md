# 08 独立 Standards 首审：B 隔离 JVM fixture、ACR 与 07/08

审查者：独立 Standards / Codex GPT-6.1-sol，2026-10-16。首审结论：**request-changes，1 项 P2，未发现 P1**。新seal定点复审已确认 **S-B-01在合成候选模型范围闭合**，详末节。首审原件保留；本轮未修改生产owner/IPC，模型修复不等真实AVF验收。

## 背景、基线与冻结边界

唯一活动 effort 为 `.scratch/orchestella-avf-shizuku/`；用户指定 `main@428860ad9a1cb335f8be78c157b2ff57faf2a6da` 的 dirty 0.5.0/code500 工作区起点，原36文件以 batch-01/baseline-source.json 为准。没有使用空 HEAD 三点 diff 漏掉未提交文件。

batch 为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`，B 为其 avf-platform-device。先核 B/seals/qa1-independent/delivery-manifest.json、六个原票/报告保全副本、独立 JVM fixture、runner 与实际编译/运行原件；后按主协调增量授权核 B/seals/qa2-failed-accurate 的六份源码文档、output-validation 与 QA2 实际失败原件。不是从进行中 canonical 推定冻结版本通过。

八份运行 raw hash 均与各自 JSON 匹配；初轮8条 PROTOTYPE_PASS、最终9条的记录与报告纠错一致。五份只读生产 engine/API/adapter/launcher 的当前 SHA 与 B before-source/两份 seal 均相同；六份最终文档 snapshot 与 canonical 逐项匹配。固定 AOSP Java shared-mLock、Context data/vm namespace、配置按路径开 FD 与 libavf LLNDK/APEX 引用做过定点源码核验，未认证目标 ROM 与其同 commit。

独立核验摘要见 batch/review-standards/first-pass-verification.json 及即时 preservation。末检时 B 外部 BoundedReadinessProbe.java 和 run-fixtures.py 已进入后续实现，与 qa2-failed-accurate seal 不同；末检明确记录 false，没有把新字节签成旧 seal。以下发现针对此前完整读取且当时 hash 匹配的9组冻结候选模型。新模型另待新 seal 定点复审。

## 硬规范与行为发现

### S-B-01 · P2 · 已观察 owner 停止后候选模型仍可 ready 并提交连接

- 文件/符号：冻结 B/fixture-src/workerb/BoundedReadinessProbe.java，`Session.observeStop` 常规 **177–182** 行；`ready` **164** 行、`connect` **96–108** 行。此处冻结 SHA 为 `ef0797640ec215eeb042a85cba5185f44ac5547f271e9b86168eb84a9fd4845a`，不以正在修改的同名文件替代。
- 实际触发：成功 connect 后依序 `phase(authenticated/command/services)`；随后 `observeStop(currentGeneration, false)`，或者 expectedGuestShutdown=true。该方法只设置 stopObserved/evidence/exitClassification，不退役 session、不清 readiness、不回收已发表资源。`ready()` 仍因 retired=false 和三个阶段位为 true 而返回 true；deadline 内还可接纳新 connect。这是候选模型可达状态冲突，静态明确，未声称目标 ROM 已复现。现有 green 只在先 cancel/requestStop 后 observeStop，未覆盖运行中 owner 自行停止。
- 规范来源：20261016 Worker B Prompt 要求 readiness/stop/death/旧回调分门、未知停止保留 lease；原08票的 EOF/异常/正常停止分类与旧代不得恢复会话；ADR-0011 的异常与生命周期语义；REPORTING_STANDARD 的证据结论真实性。模型明确宣称 ordered readiness/owner-stop，不能在收到停止事实后继续报 ready。
- 必要最小修复：current owner 停止观测立即退役该 session、清 readiness、拒绝后续 connect/阶段回调、按唯一 owner 关闭已发表/迟到资源；保持在途/close失败/stop proof 的独立 gate，不因 ready 退役就假放盘。增加 ready→expected/unexpected owner stopped→ready=false/新连接拒绝，以及旧代 stop 不影响当前代的定点行为回归。修复源与 runner 重新封板后单独复审，旧 red/green 原件保留。

## 已核规范与真实性

| 检查 | 结论与实际边界 |
| --- | --- |
| 实验/生产边界 | 外部 fixture 明确 SYNTHETIC、无 Android/native/IPC；生产五文件 hash 未改；未新建产品 VM owner。REPORTING/ARTIFACT 的 prototype 分离规则得到保留。 |
| caller deadline 与底层完成 | 生产+合成平台锁 lock-red/caller-deadline-red 的 TimeoutException 为真实 JVM red；候选 bounded-red 是错误假设反证，不冒充生产 red。Future done/interrupt 均未被写成平台调用终止。 |
| 平台锁与 stop | PlatformLockProbe 调实际未改 engine/adapter、外部替代 shared-mLock 平台；stop entry/body、waitFor0、迟到 PFD 与 worker 收敛分开。finally 放 latch 后结束自身测试，不称已取消真实 ROM hang。 |
| 在途/FD/lease | 候选总deadline、最多1 connect+1 stop、有限attempt、旧代拒绝、迟到内存 FD 一次 close、close失败不放盘以及 exact lease 释放均有实际断言。SyntheticFd 是计数对象，不能作为 Binder/kernel FD 已关闭证据；S-B-01 仍未覆盖。 |
| 原始运行真实性 | compile/compile-lease exit0；lock-red/caller-deadline-red/bounded-red exit1；lock-observe exit0；bounded-lease-green exit0/9组。旧8组末尾误写9已明确勘误，原件保留，不把两轮累加。未重跑全套。 |
| QA1 身份事实 | 与 device/identity-v1 四份原件相符：App/client/Android两权限与 shell UserService 分离，shape visible≠invoke/manager，remove后 QA pidof未检出≠VM死亡链。QA1三份旧源码缺失限制沿[05首审](../avf-shizuku-05/standards-review-20261016.zh-CN.md)。 |
| QA2 新增失败原件 | 取证exit0，内部 USER_SERVICE_CALL_FAILED / OPERATION_FAILURE；无独立 state/FD结果。B最终六文档没有改绿或推断 EACCES、SELinux拒绝、路线不可行，也没有将前置NO_FILES_OPENED_OR_CREATED解读成整轮零副作用。 |
| Context/FD/G4 | 报告明确实际调用身份、Context namespace、Java path-only、Binder FD消费入口与 native分发尚未闭合；不把改packageName、公共盘、chmod或/proc/self/fd当兼容机制。 |
| ACR/secret | R1/R0只为条件方案，用户未裁定。最终增量明确 assembled initrd RO FD 可能向调用进程暴露本轮token/Guest凭据，RO/禁日志/close不消除已读能力；需用户明确边界。未解析或复制秘密载荷。 |
| Guest与产品面 | 保留单reader/token、默认loopback和私有根盘；create/run/RUNNING/vsock/auth/QA/services/normal stop分门。真实AVF/性能样本0，没有用Microdroid、系统其他crosvm或候选模型冒充Full Linux闭环。 |
| 版本/许可/资产 | 无B生产/版本/签名/APK变更、无新分发依赖；自研fixture不进APK，固定AOSP仅作接口参考。无清理Class A、旧APK、用户盘、历史失败或证据。 |

## 启发式 smell：判断性，非硬违规

按 code-review 的12项基线检查命名、重复、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man 与 Refused Bequest；repo明确的外部小模型和实验替身优先。没有提出需要本轮重构的可靠 smell 发现；候选模型里的 phase 字符串与测试计数不据此升级为硬违规。S-B-01是实际状态语义缺陷，不是风格偏好。跳过工具已覆盖的格式/编译问题。

## 未完成、修改、风险与后续

完成：B旧seal的独立源码/证据审查、QA2准确失败增量核验、P2早期反馈和本报告。未完成：新停止退役模型的重新冻结与复审、真实manager/nonprotected/Context/FD/G4、ACR用户裁定、生产owner/IPC、真实AVF/Guest/网络/正常stop/death和性能。仅批准报告准确性不能把07/08状态转completed。

本审查没有设备/adb/UI/安装/Gradle/网络、版本/生产写入、commit/ref/worktree操作，没有读取另一轴报告、实施者自审或C/D进行中代码；只写指定Standards正式报告和batch/review-standards/preservation。没有修改生产或回滚用户数据；Commit无，branch沿用户提供main。`.phase5`机主确认门不作为已验。

风险：候选green被当已交付、停止后仍ready、caller截止被误作VM结束、live源码混入冻结审查。后续最小动作是按S-B-01修复并冻结定点复核，继续保留盘lease与真实设备门。Architecture Change Policy：新owner/特权IPC提案继续由ACR-01承载，本轮审查未实施或批准架构变更。

## 新seal定点复审：p2-stop-observation-fixed

主协调随后提供 B/seals/p2-stop-observation-fixed。本次核新seal的external精确源码/runner、08报告增补及独立原始red/green，不读取另一轴报告。修复前原bytes现在另保全于 `fixture-history/p2-stop-observation/before/external/`；model SHA为 `ef0797640ec215eeb042a85cba5185f44ac5547f271e9b86168eb84a9fd4845a`，runner SHA为 `e0deec9034d906a9aef5e2fbd3ff0342626241f7f0b89c2ea0346853445feb7d`，与首审固定点匹配，解决了首审末检live漂移的追溯问题。

**S-B-01在候选模型范围闭合。** 新 `Session.observeStop` 在current-generation检查之后调用 `retire(Reason.CLOSED)`（常规178–183行），清ready、拒绝新phase/connect、一次回收published FD，然后保留expected/unexpected分类。没有提前清liveConnects、平台返回或盘lease gate；旧代在退役前拒绝，因此不会损害新owner。

已独立核对原件及raw SHA/孪生：`p2-stop-observation-red.json` exit1，明确ready_after_stop=true/fd_closes=0；`p2-stop-observation-regression-green.json` exit0，新5组覆盖current unexpected/expected、published-close失败、connect仍在途与旧代stop；`p2-existing-nine-green.json` exit0，原9组重新通过。5组与9组分列，不累计重复运行或把内存SyntheticFd变成真实Binder FD。源码检查与原件结果相符；本review没有重新执行JVM/Gradle/设备。

复审结论：**定点复审approve，首审P2闭合；无新增发现**。证据摘要为batch/review-standards/p2-recheck-verification.json及preservation，首审request-changes原件在first-pass-reports/保持不变。生产owner/IPC、真实manager/Context/FD/G4、ACR、Guest/stop/death与性能仍未闭合；`.phase5`安装系统确认仍待机主，实际installed501，不记code502设备green。无生产/版本/ref/资产写入或新架构批准。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

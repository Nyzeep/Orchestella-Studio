# AVF / Shizuku ACR / 07 / 08 独立 Spec 首审（2026-10-16）

结论：**request-changes，1 项 P2，无 P0/P1**。候选模型收到当前 owner 已停止的观测后仍可返回 ready，缺少“ready 后发生 unexpected stop”的行为门。该发现仅针对冻结离线模型，不是生产或 ROM 缺陷已复现的结论。A 的 2 项 P2 及 QA1 源码限制见 [05 首审](../avf-shizuku-05/spec-review-20261016.zh-CN.md)，本报告不重复计数。两报告合计 3 项 P2。

## 1. 背景、基线与独立范围

审查者：独立 Spec reviewer / Codex GPT-6.1-sol。前置 policy、技能、20261016 完整 Worker A/B 与 Reviewer Prompt、活动 scope/spec/05/06/ACR/07/08 已读；完整清单与 A 的范围见 05 报告。

比较基线是本批 dirty 0.5.0/code500 起点，以 `batch-01/baseline-source.json` 与 B `before-source.json` 定位；不采用 HEAD 三点 diff。main / HEAD `428860ad9a1cb335f8be78c157b2ff57faf2a6da` 是用户给定事实，reviewer 未执行 Git/ref/版本命令，无 commit。

B 首审固定在 `avf-platform-device/seals/qa1-independent/delivery-manifest.json` 所列版本。三张原票和三份报告在该 seal 的 `source/` 中，6 项 bytes/hash 独立一致。fixture 原始三份 Java 与 runner 也按该 seal 锁定，后续修改不回写首审结论；其中 `BoundedReadinessProbe.java` 首审 SHA256 为 `ef0797640ec215eeb042a85cba5185f44ac5547f271e9b86168eb84a9fd4845a`。审查期间 B 的工作副本和常规 preservation 随修复更新，原字节仍保存在 B `fixture-history/p2-stop-observation/before/external/fixture-src/workerb/BoundedReadinessProbe.java`，reviewer 已核 seal 并另存到 `batch-01/review-spec/frozen-first-b/` 及其保全副本。runner 原字节在同一 before/external 历史目录，hash 同样匹配首审 seal。首审索引保留当时观察到的版本差异，补充来源见 `review-spec/first-b-byte-provenance.json`。后续修复必须另作增量复审。

范围只含离线 fixtures/runner、ACR/07/08 报告/原票补记与动作包，生产源码不在此次修改审查范围；对生产只按 B before 的五个精确路径做 hash 对应，不审正在实施的 C/D/E。未读取另一轴，未复用其结论；未运行设备、Gradle、签名、重复套件或网络动作。

## 2. 发现

### S-03 · P2 · 当前 owner 停止观测不退役 readiness，也不收回已发布资源

**原验收引用**：08 第 17 行要求“成功、迟到、永久阻塞、取消、错误 FD、stop/death、旧 watcher 晚到均以行为 fixture 验证”；第 19 行要求固定停止意图/进程死亡/错误回调分类；第 20 行要求“取消/失败全部 FD 最终关闭一次”。20261016 Worker B 要求死亡收敛、旧回调及停止确认。候选模型既已提供 `observeStop`，当前代停止必须影响该 session 的对外存活状态。

**冻结位置**：`fixture-src/workerb/BoundedReadinessProbe.java`，`Session.observeStop` 实际第 177–181 行；`ready` 第 164 行，`retire` 第 142–149 行。首审原件 hash 见上节；不以修复后工作副本行号为原始发现证据。

**实际触发**：正常 `connect` 返回后，按顺序调用当前代 `phase("authenticated")`、`phase("command")`、`phase("services")`，此时 `ready()` 为 true。随后在相同 generation 调用 `observeStop(epoch, false)` 模拟 unexpected owner stop，再读 `ready()`。`observeStop` 仅设置 `stopObserved/stopEvidence/exitClassification`，没有设置 `retired`、清空 readiness 或关闭 `published`，所以 `ready()` 仍为 true，已发布 FD 仍被持有；后续 `connect` 也未因停止观测被拒绝。停止证据与仍可用的结果相互矛盾。

现有 9 组覆盖的 `observeStop` 均在 requestStop/cancel/close 已将 session 退役之后触发，不能覆盖上述事件顺序。本发现来自冻结源码的直接事件路径推导，reviewer 没有新增运行或声称设备复现。

**必要最小修复**：收到合法当前代停止观测时退役 readiness、拒绝新操作并通过既有 once-close 路径回收已发布资源；保留 expected/unexpected 分类，保持真实底层在途额度与未确认资源的盘 lease，不能把一次观测自动当全部调用已结束。补一条“已 ready → unexpected owner stop → ready=false/新请求拒绝/FD 一次关闭”的模型行为门，以及迟到结果仍被收回的对照。无需改生产 owner 或重做旧 STOP/FD 修复。

## 3. 逐票验收与状态真实性

| 原票/门 | 独立核对 | 判断 |
| --- | --- | --- |
| ACR：真实身份/Context/权限 | QA1 AppAPP_UID/untrusted_app，US2000/shell，APP_UID；App 两权限 denied，US 两项 granted；成员可见但未 invoke | 报告准确区分 App 身份、进程身份、client 与 Android grant |
| ACR：FD/state/入口可行性 | 方案明确 Java custom 配置按调用进程路径打开输入；Binder FD 读写不等 VM 配置可用；禁止任意 path/exec、/proc/self/fd、目录放权/公共复制替代证据 | G1–G4 未闭合；不算伪装完成 |
| ACR：新 owner/有限 IPC | R1 为待 05/G4 与用户裁定的条件方案；R0 保留现路径；协议/caller/user/package/epoch、FD 来源/模式/dup/关闭、盘 lease 与死亡门有明确提案 | 没有借调试授权签生产 owner 批准；ACR 保持 needs-architecture-decision |
| 07：绑定 owner/输入/资源 | 动作要求新 QA 盘、pinned 输入、1vCPU/nonprotected/合预算 RAM、同 session 实际进程归属 | 仅待执行动作；不是已有 create/run 证据 |
| 07：Guest/终端/服务/正常 stop | run/state/vsock/auth/固定命令/resize/services/认证 power 与正常停止独立；不依赖 custom Linux 无通用 payload-ready callback | 本应用真实 VM/Guest/服务/normal stop 样本均 0，状态诚实 |
| 08：deadline 与底层结束 | caller getter red、错误 Future 结束假设 red 分层；模型超时后保留 liveConnects，晚到结果关闭 | 不以 Future.cancel/interrupt 或 UI 返回签 native 已取消 |
| 08：平台锁与 stop | lock-observe 为替代 shared-mLock；stop 入口1/body0，waitFor0 仍 UNCONFIRMED；最终主动释放 latch 才结束 fixture | 不是当前 ROM 无限 hang/停止成功证明；报告明确 |
| 08：重复/旧代/EOF/盘 lease | 最终 9 组有 singleflight、attempt cap、permanent/null FD、late/close failure、EOF 未知和跨代 lease 门 | S-03 留下当前 owner 停止顺序缺口；其余不外推 Android/Binder/真实 FD |
| 08：真实收敛边界 | 平台未停时保持 STOP_UNCONFIRMED/盘 lease；getStatus 可能共享锁，不另开无限线程；未确认停止不允许同盘 QEMU | 正确保留 blocked-owner-and-device-evidence，不能 mock 绿即完成 |

未发现本批存在生产新 owner/IPC、任意 shell/path、用户盘搬移或把旧 403/QEMU、Microdroid、feature/grant 当本批 AVF 成功的主张。07/08 的明确未实施部分和设备门不计发现，也不因此签完成。

## 4. 原始输出、编译关联与测试限制

独立读取现存 raw JSON/log.txt 与 fixture/runner 后确认：

- lock-red exit1/TimeoutException、caller-deadline-red exit1/TimeoutException，属于生产 engine/adapter 接合成替代平台的 JVM 反证；两次源码版本不同，首轮原件保留，不能拿当前行号解释旧堆栈。
- lock-observe exit0 原始文本明确 `stop_entry=1 stop_body=0 caller_waitFor=0 platform_stopped=UNCONFIRMED in_flight=1`；释放 latch 后 `late_fd_close=1 dup=0 stop_body=1 close_body=1 workers=0`。这只证明本 fixture 的观察与清理。
- bounded-red 是错误设计假设的 AssertionError；不是生产修复 red。初轮 bounded-green 有 8 行 PASS 而尾部硬编码 9，报告已保留并披露；最终 bounded-lease-green 有 9 行 PASS 与动态 cases=9，不将两轮累计。
- SyntheticFd 是内存计数器；clock/tick 是显式合成驱动；独立停止观测是模型输入。真实 Binder/FD、App/server/owner death、ROM 中断与生产调度器均未验证。

精确 hash 核验覆盖 B 首审六项票/报告、三份 fixture/runner、原始输出与对应保全副本，并按 `compile-lease-inputs.json` 对应指定输入。初始索引 `batch-01/review-spec/initial-evidence-checks.json` 如实记录了执行时常规 preservation 中模型/runner 已更新而不匹配首审 seal 的情况；随后以不可变 before/external 历史原件补核，原字节与首审 seal 一致，来源及保全见 `review-spec/first-b-byte-provenance.json`、`review-spec/frozen-first-b/`。不把差异记录改成通过，不拿新源解释旧结果。不扫描或归档整仓，不重新运行旧 20 个 JavaExec、App/Guest/工具或新 fixture 套件。

## 5. 修改、风险、后续与边界

实际完成：本报告、05 报告及外部 review-spec 核验索引/保全；S-03 已即时回报主协调。修改文件仅指定正式两份 Spec 报告与本轴外部路径。首审后续修订、QA3 和 QA2 设备执行不纳本结论，修复需要新冻结包定点复审；首审原件保留。

未完成：S-03 修复复核，ACR 用户裁定/ADR/spec 更新，07/08 生产和设备门。风险为模型在已知停止后仍对外 ready，使该模型不适合作生产设计的已验依据；解决该局部模型缺口不代表生产就绪。

分支/commit：用户给定 main/428860a；无新 commit，无 ref 操作。无设备/Gradle/版本/签名/网络/系统/生产变更，无清理、内容外发或另一轴读取。保全归 Class B，维护者验收归档前不自动清理。Architecture Change Policy：本 reviewer 不实施架构变更；ACR 仍承载新 owner/IPC 的真实决策门。

报告人：独立 Spec reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

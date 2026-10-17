# 05–06 独立 Spec 最终定点复审：QA4 / S-06

结论：**approve（仅 S-06 的 QA4 冻结源码与 JVM 实际 Activity 受控边界），S-06/P2 在此范围关闭，无新增发现。** 旧 timer、旧 finish 不再退役后续调用；真实底层操作返回前仍保留全进程 IN_FLIGHT。原首审及 QA3 复审保持原字节，不改写 QA3 request-changes 的历史结论。QA1 三份原源码缺口、typed grant/restore 未全通及所有设备/AVF边界继续保留，不由本报告签 05/06 completed。

## 对象、依据与限定范围

审查者：独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol；日期2026-10-16。沿前次已完整读取的项目治理、两份20261016交接/Reviewer协议与resume dispatch，按本次用户精确指针只复核 S-06。需求依据是06的显式选择、逐项readback、partial/unknown不报成功，以及QA3固定动作包同live session恢复新增项的约定；具体旧时序缺陷见本目录 `spec-rereview-20261016.zh-CN.md`。不重审B/C/D/E或其他已完成模块，不读取另一轴报告。

唯一源码与基线沿用户给定 `Orchestella_dev`、main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，原dirty实现保留；未执行Git/ref/版本命令，无commit。本次对象固定在外部批次根 E=`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`：

- `resume-01/qa-deadline-fix/identity-v4-frozen-source.json`，8400B，SHA256 **`6f83dcaffb72038776fd8088115aac93a6fdff0e0113a09590e73d36ec25af15`**，与用户给定指针一致。
- 同根 `source/`、`before-qa3/source/`、`qa3-to-qa4.patch`、`qa4-compile-01/`、actual Activity `activity-deadline-red-02/` 与 `activity-deadline-green-02/` 及对应preservation。
- 主协调随后明确交付的 `qa4-apk-01/result.json` 与精确构建快照，仅做已存输入/输出关联。QA4 code4/0.1.3-identity的签装、普通session和真实设备回归不纳本结论。

封板包含19份build-input和3份review-support（README、harness、runner），另有notice。相对保全QA3，19份构建输入只改ProbeActivity和构建runner；runner局部仅增加本轮批准输出根/保全位置。未更改固定QA/user0权限、IPC、FD/manager或生产入口。

## 修复语义独立核对

冻结 `source/src/main/java/io/github/nyzeep/orchestella/researchauth/ProbeActivity.java` 为16058B / SHA256 `61f98bbabe87cd708b7b95ef25d5b67c331e5f6bc8243dbecb6b8bed0617eb91`。本次直接读取完整实际Activity及补丁，核对如下。

| S-06修复门 | 实际代码行为 | 判断 |
| --- | --- | --- |
| 每次submit独立身份 | IN_FLIGHT.compareAndSet接纳后立即new Object作为token，并设置activeOperation；每次创建独立deadline Runnable | 同一session epoch不再被误用为operation身份 |
| 旧timer不能退役新call | deadline除visible/binding/busy外还要求activeOperation==token；正常完成只removeCallbacks自己的deadline | 即使旧timer已经派发、取消未拦住，token仍拒绝它；不取消后续timer |
| 旧finish不能清新busy/session | 完成回调先移除自己的timer，再校activeOperation==token；不匹配立即返回，匹配才清activeOperation/busy并按epoch/可见性发布结果 | 旧结果重放不清后续操作状态，也不覆盖其结果 |
| 底层在途与UI截止分离 | IN_FLIGHT只在operation.run实际返回或抛出后，由worker finally释放；deadline、close、onStop、death与UI finish均不释放全局额度 | 当前deadline仍退役结果，保留busy/真实flight；返回前其他Activity不能接纳新调用，不声称取消Binder |
| 迟到snapshot不得重开session | worker不再直接写permissionSession=true；只有当前token的主线程finish、visible、同epoch及OPEN_FOR_EXPLICIT_QA_PERMISSION_ACTIONS同时成立才开启 | deadline/end/death/旧Activity后迟到snapshot不复活权限session |
| 返回后可继续合法流程 | 当前成功finish保留live session并交付结果；真实返回后其他Activity可接纳新调用，旧Activity回调只清自己的状态/自己的timer | 保持显式readback/restore通道，未扩大并发数 |

全局flight的释放仍位于后台真实操作末尾，而非UI finish；这允许旧Activity结果尚排队时，新Activity在旧底层真实返回后开始下一次调用。新operation的token和busy归其Activity实例；旧finish不再修改全局flight，旧生命周期/epoch也不能把旧snapshot写成有效session。没有新增自动grant、自动restore、重试队列或第二条数据面。

## 同一harness的实际red/green与负例

独立读取最终 `source/tests/ProbeActivityDeadlineHarness.java`、`run_activity_deadline.py` 和两轮原始log.txt。runner原样编译ProbeActivity、ProbeConnectionEvents、ProbeSession、FixedPermissionPlan；按钮、bind回调、submit、权限动作和onStop来自实际Activity。Android Handler/时钟/View、Shizuku与远端Binder依赖为JVM替身，未复制submit模型，不是实际Android系统调度或跨进程Binder验证。

最终red02与green02的harness、runner及23份显式stub字节相同，除ProbeActivity外其余3份真实Java输入也相同。harness SHA256 `cf8c3211df7b42e0ffebf569e45d59da6ba017e830e8640f9d1664b25ec5da33`，runner `cf47cac9c3c4b303b6e70fbb5366fa8dbc51573319ca8725aa52fe96a488191c`；两轮均javac exit0。不是靠更换测试断言取得green。

| 场景 | 原QA3 red02 | QA4 green02 | 核对的负例/对照 |
| --- | --- | --- | --- |
| same-session-grant | exit1，旧A timer退役B | PASS，14断言 | B在途时强制投递已取消的A timer；B结果可见，之后restore回DENIED |
| same-session-readback | exit1，同上 | PASS，15断言 | 同epoch读回不被snapshot旧deadline截断 |
| same-session-restore | exit1，同上 | PASS，15断言 | 显式恢复在途不被旧deadline截断 |
| replayed-finish | exit1，old A result cleared B busy state | PASS，15断言 | B在途重放A finish不清busy，只退役A timer |
| current-deadline | PASS | PASS，11断言 | 当前5秒deadline仍有效；busy/IN_FLIGHT留至真实返回，另一Activity不能提前bind；迟到结果不复活 |
| snapshot-deadline | exit1，late snapshot reopened retired permission session | PASS，9断言 | 迟到snapshot不改退役receipt、不重开session，之后新session不受旧回调影响 |
| end-during-snapshot | exit1，迟到snapshot重开 | PASS，9断言 | 显式remove/end后不重开 |
| death-during-snapshot | exit1，迟到snapshot重开 | PASS，9断言 | Binder death listener后不重开 |
| old-activity | exit1，旧Activity迟到worker重开session | PASS，13断言 | onStop不释放全局flight；真实返回后新Activity可开始，旧finish/timer/death不清新调用，后续grant/restore可用 |

最终既有证据为 **QA3八失败+当前deadline控制通过 → QA4九场景/110断言全部PASS**。每项均核正文与exit，不仅看result字段。110为九场景断言合计，不是110场景；不累加red01/green01或旧72项。reviewer没有重跑九场景、旧72项、javac、AIDL或任何其他套件。

## 源码、补丁、编译与保全关联

本次定点核验索引为 `E/resume-01/review-spec/qa4/qa4-evidence-checks.json`，可审计脚本为同目录 `check-qa4-evidence.py`。其运行只做限定文件hash、只读JSON、内存patch比较和ZIP条目比较，不执行被审runner或编译器。

- seal所列22项source及preservation全部匹配；19份构建输入与compile01、APK01各自source快照、receipt及保全对应一致，notice另行一致。
- `before-qa3`原字节对应原v3 seal及qa3-apk-01/source；旧v3 seal未变。完整五段QA3→QA4补丁在内存应用到before后，与冻结source原bytes一致，无工作树写入或Git操作。patch43767B / SHA256 `5a74771497ef34cf6c95b67d65f3703765be13c659362a48b684b51f4c5a60c8`。
- red02实际Activity原件与被审QA3的15489B / SHA256 `1e8e444552521ff2ce8a621fc03ccd2e33cb431cda53b88c87ff12e388b53e6c`一致；green02实际Activity与本QA4冻结字节一致。两轮明确javac输入、输出及原始场景记录均与保全相同。
- `qa4-compile-01/result.json`为AIDL/javac **0/0**；`qa4-apk-01/result.json`为AIDL/javac/D8/aapt2 **0/0/0/0**，code4/0.1.3-identity；两者只编译保存的精确source快照。
- 既有unsigned APK53178B / SHA256 `95a691a6fa0349b166655c568f619191e97d7ee4da075c80c6974c3ac9e20722`匹配receipt/保全，ZIP无重复条目、唯一classes.dex与既有D8输出同字节、notice匹配。该内容关联不等独立重建、验签或设备运行证明。

## 交付、剩余与边界

实际完成本新增正式报告、`resume-01/review-spec/qa4/`定点核验脚本/索引/报告副本及 `resume-01/preservation/review-spec/qa4/`即时同字节孪生；保留原05首审和QA3复审，不回写旧结论。仅在本轴指定路径写入，无其他模块复审或生产/原型修改。

剩余：主协调QA4签装、真实普通session与必要deadline/远端Binder/权限系统验证；**typed MANAGE/CUSTOM grant/readback/同session restore仍未由本报告签全通**。QA3既有MANAGE中断→partial→ADB人工恢复保持原证据分级。QA1历史三份源码原bytes缺失继续保留，不以QA4或反编译补造精确重建。新owner/ACR、Context/FD后续系统门、真实AVF/Guest/性能结论均不在此次范围。

风险是把实际Activity配合JVM替身的green等同目标ROM端到端通过；因此结论明确仅关闭S-06冻结源码/受控调度缺陷。Architecture Change Policy：本次无架构实施，修复限实验调用身份及结果退役，不批准新owner/权限/IPC。

无设备/adb/UI/Gradle/生产/版本/ref/签名/commit/网络/系统操作，无重复九场景或其他测试，无另一轴读取、凭据读取、整仓归档、外发或清理。Class B保留至维护者验收归档，不自动清理。

报告人：独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

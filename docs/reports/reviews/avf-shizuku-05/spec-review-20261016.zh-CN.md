# AVF / Shizuku 05–06 独立 Spec 首审（2026-10-16）

结论：**request-changes，2 项 P2，无 P0/P1**。QA2 旧绑定断连回调可以退役新代；QA1 三份变更源码未保留原字节，源码复核与重建关联不完整。两项均已即时回报主协调。明确未完成的设备门不计缺陷；本报告不签 05/06 完成或 AVF 可行性通过。B 的独立 Spec 发现另见 [08 首审](../avf-shizuku-08/spec-review-20261016.zh-CN.md)，不重复计数。

## 1. 背景、基线与独立性

审查者：独立 Spec reviewer / Codex GPT-6.1-sol。已读项目 AGENTS、CONTEXT、DEFENSIVE_CONSTRAINTS 内全部 policy，以及 HOST / ARTIFACT / ASSET / ARCHITECTURE / REPORTING；已读项目 `.agents/skills/code-review/SKILL.md`、20261016 剩余 Prompt 的完整 A/B 与 Reviewer 要求、活动 scope/spec/05/06/ACR/07/08。

比较点是本批 **dirty 0.5.0/code500 工作区起点**：`batch-01/baseline-source.json`、A `before-source.json` 和精确局部副本，再到 `identity-v1-frozen-source.json` / `identity-v2-frozen-source.json`。没有采用 HEAD 三点 diff。main / HEAD `428860ad9a1cb335f8be78c157b2ff57faf2a6da` 为用户给定基线，本 reviewer 未执行 Git/ref/版本命令；commit：无。

本审查不读取另一轴，不以实施者结论代替核验。报告中的实现报告只作为待核主张，事实来自冻结源、原始执行记录与独立 hash/zip 定点比较。未运行设备、Gradle、签名操作或重复套件，未联网，未修改生产。

## 2. 冻结范围与原件

- A：`prototypes/avf-shizuku-identity-probe/` 的 QA1/QA2、固定构建 runner、两个动作包、before/seal、编译与 APK 关联。QA2 逐行对象固定在 A `preservation/qa2-source-before-qa3/`，13 个文件全部匹配 v2 seal。
- QA1：只使用 `device/identity-v1-{app-receipt,authorized-app-receipt,user-service-receipt,remove-confirm}.json` 四份原件，以及同批 preflight/构建元数据。QA1 中 3 份异版本源码原字节缺失，限制见 S-02；未用 QA2 或反编译替代。
- B：本次首审的离线 fixture、ACR/07/08 报告与原票补记，详 08 报告。
- C/D/E 生产、QA3、后续设备执行、B 后续修订均不在本次首审中。QA2 的验收事实固定为编译/组装级，不使用审查期间新增的设备文件。

外部批次根为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`。本 reviewer 的精确核验结果为该根 `review-spec/initial-evidence-checks.json`，本报告与核验结果均即时复制到 `preservation/review-spec/`。Class B，保留至维护者验收归档，不自动清理。

## 3. 发现

### S-01 · P2 · 旧绑定的断连回调可破坏新绑定的代际

**原验收**：20261016 Worker A 要求“明确非 daemon、旧 generation、destroy 和 server/App death”；05 要求对解绑与 App/server death 分别取证。QA1 动作包第 33 行明确“迟到/旧 Activity 结果不会写为新 epoch 完成”。这是当前 QA 原型的行为要求。

**冻结位置**：QA2 `ProbeActivity.java` 的 `onServiceDisconnected`，实际第 232–235 行；源码 SHA256 `55f96d14b7b80995153b64eb1929d011ed0168c58cdb062f04f237958f01e016`。可定位的冻结原件是 `research-auth-device/preservation/qa2-source-before-qa3/src/main/java/io/github/nyzeep/orchestella/researchauth/ProbeActivity.java`。

**触发与实际行为**：A 绑定已完成，点击 remove 后建立 B 绑定；A 的迟到断连回调随后到达。`onServiceConnected` 会检查 `visible`、`binding == epoch`、`connection == this`，断连分支却无任何对应检查，直接 `service = null; ++epoch`。因此 A 可以清空 B 的句柄、退役 B 的结果并把当前 receipt 改成断连。固定 Shizuku API 源的连接/死亡回调经主线程异步投递，remove=true 本身不构成“所有旧回调已排空”的保证。

**必要最小修复**：断连分支先校验同一可见生命周期和当前 connection/绑定代际，再改变状态；增加实际 Activity 回调接缝上的“A remove → B bind → A disconnect 晚到”回归，并覆盖正常 B 断连仍生效。无需重构 VM owner。此处为源码事件路径审查，未声称设备已复现。QA1 同版本回调源码缺失，因此该逐行发现仅签 QA2。

### S-02 · P2 · QA1 编译源只保存 hash，三个原文件已无法逐行复核或精确重建

**原验收**：05 第一项要求“同 APK/source/ROM/API/module 绑定 App 和 UserService真实 UID/domain 与 Context 可用性”；QA2 动作包开头要求“QA1 源码与成功/失败 APK/receipt 保持原件”，保全段同样要求保留旧 QA1。仅有 hash 不能代替源码原件。

**冻结位置**：QA1/QA2 相同的 `scripts/build_qa.py`，`main` 第 82–84 行只将 `src` 的 bytes/hash 写入 `evidence["source"]`；第 140–143 行只保全结果 JSON。runner SHA256 `067abb48e509c3a8b3b353c9f8f913d1bc607d710244ae019b821fd32313b08e`。

**触发与实际缺口**：QA2 替换 canonical 后，QA1 `ProbeActivity.java`（11213B，`2cdc28c2…`）、`IdentityProbeService.java`（1862B，`76ece536…`）、`IIdentityProbe.aidl`（354B，`1f7baecc…`）无匹配的源码原字节副本。`before-source/` 是更旧 source-only 版本；`qa2-source-before-qa3/` 是 QA2。主协调对 A 的定点核验同样确认原件缺失。QA1 seal 的 6 项 src 与 build receipt 匹配，classes/JAR/dex/APK 仍在，但不能据此签三份源码逐行审核或精确源码重建。

**必要最小修复**：后续 runner 在编译前保存精确 src 与 runner 自身字节到本轮唯一输出及 preservation，并绑定同一输入清单；使用该固定快照构建，或在构建结束核输入未漂移。保留 QA1 全部现存原件并明确此历史缺口；不得用后续源码或反编译补造。后续一次完整、可复核的冻结构建可恢复新包的可审查性，不能抹去 QA1 限制。

## 4. 原票验收、行为边界与诚实状态

| 验收/重点 | 本次核对结果 | 证据级别及限制 |
| --- | --- | --- |
| 实际 UID/domain 与 Context UID 分离 | QA1 APP_UID/untrusted_app；US UID2000/shell，APP_UID | 原始设备 receipt；不把 Context 包名/UID 元数据当进程身份 |
| 两 Android 权限与 client 分离 | 授权后 App client=true，而 App MANAGE/CUSTOM 均 denied；US 自身两项均 granted | 两权限逐项读，client 不代 Android grant |
| API shape 与调用分离 | App customBuilder 隐藏；US MEMBER_VISIBLE_NOT_INVOKED | manager NOT_CREATED、capability NOT_QUERIED、AVF false；未推导 nonprotected |
| Context/state/FD | QA1 NO_FILES_OPENED_OR_CREATED；QA2 只见编译/组装 | QA2 固定私有目录、0700/0600、O_EXCL/O_NOFOLLOW；真实 Context 写入与 Binder FD 均未在本次验收 |
| FD 模式/关闭与路径边界 | 源码固定 RO/RW 两 PFD，无 path/exec 参数；fstat/owner/size/mode、同 inode/重复对象、哨兵校验，服务 finally 关闭接收副本，App 用 try-with-resources | JDK metadata gate 不能证明真实 FD；closed/wrong caller/death/迟到资源仍需设备门；不由源码 finally 签内核关闭成功 |
| App 代际/重复调用/退出 | ProbeSession 限 caller/protocol/递增 epoch；全 App 单在途，deadline 只退役，不释放底层在途额度；存在 S-01 | QA1 stale/end 门 receipt=true；不是跨 UID Binder 负客户端已验 |
| daemon=false / remove / death | reserved destroy + remove=true 明确；QA1 精确 QA 进程 pidof exit1 | 仅正常移除后的 QA 进程未检出；不外推 App/server death 或 VM stop |
| 06 grant/readback/revoke/partial/撤回与 UX | 本次 QA 包不执行两权限变更，无自动 grant/任意 pm/shell | 首次本机配对、拒绝/记忆拒绝、撤回 force-stop、重启/OEM/生产向导仍开；不计虚报发现 |
| ACR/生产 scope | 本次为隔离 QA，没有 VM 操作或生产 owner | 不签可交付 AVF；新生产 owner/IPC 仍需 ACR-01 |

QA1 初始 `CLIENT_QUERY_FAILED` 原件保留，后续授权 App 快照另存。QA2 已将 remote permission 查询按 client 前提分开并逐字段记录错误；本次只核源码，未用该修复覆写 QA1 失败事实。`SYNTHETIC_GATES_RECORDED` 表示记录结果，不是所有门已通过。

## 5. 核验真实性与限制

独立只读 hash/zip 核对结果：QA2 保全 13/13 匹配 seal；QA1/QA2 build receipt 分别 6/6、11/11 src 项匹配对应 freeze；program JAR 与 classes 分别 10/10、15/15 条目一致，APK classes.dex 与 D8 输出一致；unsigned APK 分别 35447B / 41270B，匹配 receipt，无重复 zip 条目且仅一个 classes.dex，notice hash 匹配。QA1 signed APK 45535B，hash 匹配 signed-apk 记录，unsigned 固定内容在 signed 包中一致。未重验签名，未读取私钥或签名口令。

这些比较验证已有文件的内容关联，不是独立重建证明，也不能认证设备物理运行或补回 QA1 缺失源码。QA2 的 manager/capability 在冻结源码中仍明确未调用；这属于诚实未完成门，不作为“伪装实现”。真实 AVF、Guest、服务和正常 VM stop 本次均无样本。

## 6. 交付、后续与边界

实际完成：本报告、对应外部副本、独立核验索引，以及 P2 即时回报。修改文件仅两份指定正式 Spec 报告与 `batch-01/review-spec/`、`preservation/review-spec/` 内审查证据。未完成：S-01/S-02 修复后增量复核、QA1 缺失源完整审查、QA2 设备验收、05/06 后续门；不自动扩大范围。

测试：本 reviewer 未运行套件；只读现存原始结果和精确 hash/zip。分支/commit：沿用户给定 main/428860a，未改 ref、无 commit。风险：旧回调误退役新实验、丢失旧包源使身份行为不能完整重审。后续最小动作是按发现修复后提交新冻结源/证据，再定点复审，首审原件保持。

越界：无生产、设备、Gradle、版本、签名、网络、系统修改或清理；未读另一轴。Architecture Change Policy：本审查不实施架构变更，不代用户裁定 ACR；新 owner/IPC 门继续有效。

报告人：独立 Spec reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

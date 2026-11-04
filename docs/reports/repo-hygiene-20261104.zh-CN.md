# 仓库卫生对账报告（2026-11-04 · Lane D）

- 分支：`chore/repo-hygiene-20261104`（基于 main `b39e8e6`，纯 ASCII 路径 git worktree）
- commit：`0dd50be`（十二张过期票状态回填）、`56081e0`（issue-tracker.md 对账快照）；本报告所在提交为该分支 HEAD（`docs(reports)` 一笔，见 `git log chore/repo-hygiene-20261104`）

## 背景

`.scratch/` 下 14 个 effort、约 197 张工单自 2026-09 以来经历多轮并行推进；此前已有两次对账（`46ce4e5` 的 Q5 tracker 对账、`803f097` 的 pvm 线七票回填），但均为局部。历史教训（Q5「状态没回写」、pvm 线整线状态过期）表明**票面 `Status:` 不可信**，必须以 git log / PR / 实施报告为准逐票核对。本轮为仓库卫生 lane，任务四项：工单对账、tracker 同步、`release/0.6.0-stabilization` 处置建议、cleanup-plan §3 归档批次完整性核对。

## Scope

- 遍历 `.scratch/*/issues/` 全部工单（重点：quality-round-20261103、round-20261104-device-window、orchestella-pvm-compiler、orchestella-uml-fabric、release-readiness、orchestella-platform），核对票面 Status 与实际状态。
- 过期状态回填；产出仍 open 票清单；同步 `docs/agents/issue-tracker.md`。
- release 分支只写建议，**不实际删除或移动**。
- cleanup-plan §3 三批次 30 目录归档核对，缺则补。
- 纯文档/工单操作：不跑 gradle、不改产品代码。

## 实际完成

### 1. 工单对账（全部 14 个 effort）

对账口径：票面 `Status:` 行 × `git log`（merge commit / PR）× `docs/reports/`（含 archive）实施报告结论 × effort `map.md` 台账。发现并回填 **12 张过期票**（每票附 2026-11-04 对账 `## Comments`，引用证据 commit 与报告路径；不删不改任何历史正文）：

| effort | 票 | 原状态 | 回填为 | 证据 |
| --- | --- | --- | --- | --- |
| quality-phase5-integration | 01/02/03 | `claimed` | `resolved` | PR #2（merge `ec0df2d`，2026-10-14）已合流；终版报告在 `docs/reports/archive/2026-10-phase5-quality/orchestration/` |
| quality-phase5-integration | 04 | `pending` | `resolved` | 同上；双轴审查在 `archive/2026-10-phase5-quality/reviews/quality-phase5-integration/` |
| orchestella-platform | 26 | `ready-for-agent` | `completed` | map「M1（25–37）全量验收合流完成」；下游 27–33 全部 completed；2026-09 治理勘误原文保留 |
| orchestella-platform | 52 | `ready-for-review` | `completed` | `5372ac7` 经 merge `3fc8143`（2026-10-06）入 main；map M4 台账 |
| orchestella-platform | 56 | `ready-for-review` | `completed` | `67ed9bb` 经 merge `dcaa5de`（2026-10-06）入 main，`be17b52` 后续修复；`NativeGlesSurfacePresenter` 在 main |
| orchestella-fastlane | P03 | `ready-for-agent（zero-device）` | `implemented（zero-device）` | 2026-10-21 零设备交付已合入 main；报告归档 `archive/2026-10-zero-device/tickets/fastlane-P03/`；同批兄弟票早已回填，本票漏更 |
| orchestella-fastlane | P09 | 同上 | `implemented（zero-device 宿主桥）` | 报告归档 `…/fastlane-P09/`；payload 侧按 2026-10-24 改道归 PVM-02/03（SELinux `execute_no_trans` 实证） |
| orchestella-fastlane | P11 | 同上 | `implemented（zero-device）` | 报告归档 `…/fastlane-P11/`；CI 门 `fastlane-zero-device.yml` 已接线 |
| orchestella-pvm-compiler | PVM-10 | 无 `Status:` 行（自由文本「状态：」） | 规范为 `Status: implemented（…真机 A/B/C pending）` | tracker 约定「Triage state is recorded as a `Status:` line」 |
| orchestella-pvm-compiler | DV-PVM-03 | 同上 | `Status: resolved（已归因 C4）` | 归因报告 `docs/reports/orchestration/session10-floor-attribution-2026-10-26.zh-CN.md`；修复归 PVM-10 |

**核对无误、未动的重点 effort**：quality-round-20261103（4/4 `resolved`，对应 `46ce4e5` Q1–Q4）、orchestella-uml-fabric（13/13 `resolved`，对应 fabric batch1/2 与零设备产品化 T1–T7 各 commit）、orchestella-hw-virt（10/10）、p0-2-runtime-supervisor（6/6）。round-20261104-device-window 为纯 spec（无票），其 T9 设备道已在 main 以 `1f4e0f4` 定案（本分支基线之后的一笔）；release-readiness 无票（单份交接 prompt，已被 0.6.0/0.6.2 发布线吸收）。avf-shizuku 编号 21 为跳空（无该票文件），非缺票。

### 2. 仍 open 票清单（回填后共 60 张，按 effort 分组）

「open」= 非 completed/resolved/implemented 终态，含「dev 完成、外部设备门挂起」类。

| 票号 | 标题（缩写） | 阻塞原因 / 现状 | 需设备 | 需维护者 |
| --- | --- | --- | --- | --- |
| **orchestella-fastlane（7）** | | | | |
| P13 | accelReservation 账本泄漏修复 | `ready-for-agent`，零设备可做，owner 待分配 | 否 | 否（**当前唯一立即可做的开发票之一**） |
| P04 | 终端多路复用 | `partially implemented`（FLN1 adapter 已落地），余项待 DV-02 | 是 | 否 |
| DV-01A | toolbox exec SELinux | `blocked_external`：protected payload `execute_no_trans`（OEM 侧） | 是 | 是（OEM 证据/裁定） |
| DV-01 | protected payload 设备验证 | 核心通过，terminal 面被 DV-01A 阻塞 | 是 | 是 |
| DV-02 / DV-03 / DV-04 | 数据面 / distcc 性能 / 产品 E2E | 依次 blocked-by DV-01(/02/03) | 是 | 是 |
| **orchestella-avf-shizuku（22）** | | | | |
| 27 | 生产启动阶段接线 | `ready-for-agent`（QEMU/owner 不变范围可先行） | 否 | 否 |
| 24 / 28 | protected 平台探针 / microdroid probe | `ready-for-prototype`，先离线门、真机依设备窗 | 是 | 否 |
| 29 / 30 | sealed toolbox / fast-lane data | blocked-by 28（29）、28+29（30） | 是 | 否 |
| 05 / 06 | identity FD / 权限设备验收 | partial-device-verified；fd-owner / in-app-UX 门未关 | 是 | 否 |
| 11 / 12 / 14 / 15 | 退出会话 / console 日志 / 实体键盘 / 向导重建 | reproduced+fixed；lifecycle / OEM / transaction 门未关 | 是 | 否 |
| 19 / 20 | 终端布局编码 / 向导草稿 | implemented；physical-OEM / external-acceptance 门未关 | 是 | 是 |
| 07 / 08 / 09 / 10 | 真 AVF guest / bounded readiness / 性能基线 / 瓶颈 | blocked-platform-and-architecture / owner / real-avf / measurement | 是 | 是 |
| 25 / 26 | custom boot / private data 可行性 | blocked-by-protected-platform-evidence / device-vm-stage | 是 | 是 |
| ACR-01/02/03 | VM ownership / 保护语义 / runtime class | `needs-architecture-decision` | 否 | **是** |
| **orchestella-pvm-compiler（4）** | | | | |
| PVM-06 | 编译 worker 池 | `implemented`；Gradle 门禁待主会话统一跑（分支 `feat/pvm-06-worker-wiring`） | 否 | 否 |
| PVM-10 / PVM-11 / DV-PVM-02 | DOTI 结束信号 / argv 白名单 / 真机 E2E | 真机验收 A/B/C 或分段项 `not-tested`/partial | 是 | 否 |
| **orchestella-accelerator（10）** | AX-01..08、PL-01/02 | 全部 `ready-for-human`：零设备实现完成（2026-10-23 记录），等人工复核与设备窗 | 是 | 是 |
| **orchestella-phase5（7）** | 05–10 | `implemented-local; device-gates-open`（历史线设备门） | 是 | 否 |
| phase5 ACR-01 | GPU buffer 与 USB 权限架构复核 | `needs-architecture-decision`（CPU 拷贝 fallback 已获授权，正式裁定未做） | 是 | **是** |
| **orchestella-platform（2）** | | | | |
| 17 | AVF 端到端原型（真机） | `open`；票 34 真机验收的形式闸门 | 是 | 是 |
| 18 | QEMU TCG 实机基线原型 | `open`；实质已被 UML fabric 真机轮（`56b25aa`、`1f4e0f4` 等）部分回答，正式收口待裁定 | 是 | **是** |
| **runtime-performance（8）** | 01–08 | `ready-for-human`；报告已归档 `2026-10-zero-device`，判据「已被 Fast Lane 与 PVM 线取代」 | 否 | **是**（裁定 close-as-superseded 或重挂到现线） |

**关键读法**：无需任何外部条件、现在就能做的是 **fastlane P13** 与 **avf-shizuku 27**（加一笔 PVM-06 的 Gradle 门禁）；卡维护者决策的是 4 张 ACR 票 + platform 17/18 + runtime-performance 批次处置；其余全部压在设备窗与 DV-01A 的 OEM SELinux 证据上。

### 3. release/0.6.0-stabilization 处置建议（未动分支）

实测：领先 main **0** 个提交、落后 ~31（以本分支基线 `b39e8e6` 计；对 `main=1f4e0f4` 为 32）；分支 tip `c9717c3` **就是** merge-base，且 PR #57（`b0b0b3a`）已将其并入 main——分支不含任何 main 之外的独有提交。

**建议：删除**（本地与 `origin/release/0.6.0-stabilization`），理由：
1. fast-forward 毫无意义——它只会把标签挪到 main 顶端，不产生任何可发布的新内容；
2. 保留一个落后两周、0 领先的 stabilization 分支，只会诱使后续修复打在陈旧基线上（0.6.0 稳定期使命已由 PR #57 收口，当前发布线是 0.6.2）；
3. 若将来需要 0.6.x 补丁版，届时从 main 重切 `release/0.6.x` 成本为零，且避免 cherry-pick 混基线。

本轮**未删除、未移动**该分支；建议已写入 `docs/agents/issue-tracker.md` 快照，留维护者执行。

### 4. cleanup-plan.md §3 归档批次核对：**已完整**

- `docs/reports/archive/2026-10-zero-device/tickets/` 下 **30/30 目录俱在**（runtime-performance-01..08、fastlane-P01..P12、avf-zero-device-01..10），**39/39 文件**与 §3 表逐批吻合（8 目录 10 文件 / 12 目录 19 文件 / 10 目录 10 文件）。
- `docs/reports/archive/README.md` 已含 `2026-10-zero-device` 批次行及「三批全部无真机、真机口径由 dv-pvm-*/fastlane-DV-01*/avf-shizuku-1x 接管」的说明。
- `docs/reports/tickets/` 现存目录中已无该 30 名（迁移干净，无残留半迁移态）；归档动作见 `267445f`。
- 结论：**无需补齐任何目录或 README 行**。

### 5. tracker 同步

`docs/agents/issue-tracker.md` 末尾新增「Reconciliation snapshot (2026-11-04)」节（沿用原文档英文与列表风格）：回填清单及证据、仍 open 票分组表（阻塞原因 + 需设备/维护者）、五 effort 清零确认、release 分支处置建议。

## 未完成 / 与预期偏差

- 三个「需维护者裁定」面只记录、不代办：runtime-performance 8 票的 superseded 收口、platform 17/18 的正式关闭、release 分支删除。
- avf-shizuku 22 张 open 票、phase5 设备门、accelerator 10 票复核：全部依赖设备窗/维护者，超出本 lane 职权。
- main 在本轮基线后前进了一笔（`1f4e0f4`，T9 定案），本报告对 T9 的表述以「main 已有」口径记录，未把该票级内容并入本分支。

## 修改文件

- `.scratch/quality-phase5-integration/issues/01..04`（4 张，状态回填 + Comments）
- `.scratch/orchestella-platform/issues/26、52、56`（3 张，同上）
- `.scratch/orchestella-fastlane/issues/P03、P09、P11`（3 张，同上）
- `.scratch/orchestella-pvm-compiler/issues/PVM-10、DV-PVM-03`（2 张，Status 行规范化）
- `docs/agents/issue-tracker.md`（对账快照节）
- `docs/reports/repo-hygiene-20261104.zh-CN.md`（本报告，新增）

## 测试

无（本 lane 纯文档/工单操作，按纪律不跑 gradle、不改产品代码，无新增测试对象）。

## 验证结果

- 状态全量扫描：`grep -E "^(Status|- Status):" .scratch/*/issues/*.md` 共 195 票带 Status 行，逐票与本报告结论核对；仅 PVM-10/DV-PVM-03 两票原用自由文本「状态：」，已规范化。
- 回填证据逐条实测：`git merge-base --is-ancestor ec0df2d main` 通过；`3fc8143`/`dcaa5de` 均在 main 且日期 2026-10-06；`release/0.6.0-stabilization` 经 `git rev-list --count` 实测 0 领先 / merge-base=tip `c9717c3`。
- 归档核对：`ls` + `find -type f` 计数 30 目录 / 39 文件，与 cleanup-plan §3 数字一致。
- 敏感门：`python tools/ci/check_sensitive_data.py --range main..HEAD` → `SENSITIVE_DATA_OK`（覆盖前两笔提交；本报告提交后复跑通过，见下）；`git diff main..HEAD --check` 干净。
- 回填后复查：quality-phase5-integration 4 票、platform 26/52/56、fastlane P03/P09/P11 状态行与 Comments 均已落盘（`git status` 仅余本报告新文件）。

## 风险

- platform 26 的回填与该票 2026-09 治理勘误叙事存在张力：已用「反映最终验收合流、不构成对历史 dispatch 的追认授权」的 Comments 措辞 + 保留勘误原文来消解，但若维护者认定治理链未闭合，可整体 revert 单笔提交 `0dd50be`。
- 对账依赖归档报告与 map.md 台账的准确性；若 archive 内某报告本身与 main 代码不符，本轮回填会继承其偏差（未发现此类反例）。
- open 票口径含「dev 完成、设备门挂起」类，读数（60）大于「真正可开工」票数（2 张 + 1 项 Gradle 门禁）；两处文档均已分组标注防止误读。

## 后续动作

1. 维护者裁定并执行：删除 `release/0.6.0-stabilization`（本地+远端）。
2. 维护者裁定：runtime-performance 01–08 close-as-superseded；platform 17/18 正式收口（18 可引 UML fabric 真机轮数据）；4 张 ACR 票排期。
3. 主会话统一跑 PVM-06 的 Gradle 门禁（`./gradlew :app:testDebugUnitTest :runtime:instance:test`），回填后该票即可终态。
4. 下一个开发窗：领 fastlane P13（P1 缺陷，零设备）与 avf-shizuku 27。
5. 设备窗优先级：DV-01A 的 OEM SELinux 证据是整条 DV 链（DV-01→04）的单点。

## 是否越界

否。全部改动限于 `.scratch/` 工单、`docs/agents/issue-tracker.md`、本报告；未动主检出、未动产品代码、未跑 gradle、未删除/移动 release 分支、未操作 docs 归档（§3 已完整，无需补）。

## 是否触发 Architecture Change Policy

否。本轮回填均以已存在的 merge/报告为据，未引入新架构决策；platform 17/18、ACR 类票维持「待决策」原状并显式移交维护者。

# 编排报告：零设备收尾轮（P0-2 / P1-1 / P13 / P0-1 快照）

- **背景 / scope**：上一轮并行优化（`86e9a8c`）收口后，质量积压清单仍留 3 项零设备可做的
  高价值项与 1 项不可逆数据风险。本轮只做这四项，不碰设备门、不碰 ACR、不写新功能：
  P0-2（AGENTS.md 过期）、P1-1（`RfbInputEncoder` 零测试）、P13（`ACCEL_LOST` 预留泄漏）、
  P0-1（9 份未入库证据文件的**风险兜底**，裁定权仍归维护者）。
- **branch / 基线 / commit**：`main` @ `86e9a8c`（领先 `origin/main` 17 个提交），开工时工作树干净。
  本轮改动**未提交**：按仓库既有约束，提交与推送由维护者执行（见 §7 提交清单）。
- **workflow**：票据化 → 三条 lane 并行实现 + 一条资产 lane → 验证串行收口 → 报告。
  实现并行、集成串行，等价于 `/implement-spec` 的 frontier 并行；P13 走 `/diagnosing-bugs`
  的红→绿纪律（先证明失败，再修，再证明通过）。

## 实际完成

### Lane A — P1-1 `RfbInputEncoder` 补测（48 条）

新增 `runtime/contract/src/test/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbInputEncoderTest.java`
（608 行，48 个 `@Test`，与被测类同包——被测类是 package-private）。覆盖：
keysym 映射（数字/字母/功能键/导航/符号/边界/未知键）、按键帧 8 字节逐字节断言、
按下-抬起 keysym 持有语义、合成修饰键（固定序 0xffe1/0xffe3/0xffe9/0xffeb）、文本提交
（ASCII/Tab/换行/`0x01000000|cp`/latch 修饰键）、指针（clamp、按钮位重映射 1→1、2→4、4→2、
按下置位/抬起清位）、滚轮（±Y→8/16、±X→64/32、小数累积 0.4×3）、`supports()` 全部拒绝分支
（能力位、非法修饰位、超 1024 code point、代理项、NaN/∞、scroll>32、按钮越界、外部事件类型）。

### Lane B — P13 `ACCEL_LOST` 预留泄漏

`DualLaneCoordinator.awaitAcceleratorReady()` 在加速器运行期死亡时只置 `LOST` + 发事件，
**未释放 `accelReservation`、未清 `accelLease`**，与 `startAll()` 启动失败路径（`:180-188`）
的契约相反：死掉的加速器会永久占住 `LaneBudgetLedger` 的 CPU/RAM/disk，把
`admission()`/`canAdmitPool()` 的余量吃掉。修复复用既有 `releaseLaneReservation` /
`setLease`，不新增字段与锁；类级 javadoc 补「崩溃路径释放租约与预留」一句。

### Lane C — P0-2 文档时效

`AGENTS.md` 5 处：版本 `0.6.0/code600` → **`0.6.2/code602`**；删掉「公开安装包仍为 v0.4.5」；
把已完成项（发布签名、停止收敛、网关互斥判定、CI 门禁）从「P0/P1 待办」里移出，设备窗项单列；
决策地图从 fastlane 改为当前三线；所有时效声明加「截至 2026-11-04」。
`CONTEXT.md` 4 处只加日期锚点，不改技术结论。
`docs/agents/issue-tracker.md` 追加 PVM-06 对账评论（门禁证据已闭合，剩余监听缺口属 DV-PVM-02b），
**不改票面状态、不回改带日期的历史快照**。

### Lane D — P0-1 风险兜底

主检出 `docs/reports/` 磁盘 186 个文件 vs `git ls-files` 177 个，差集**恰好 9 个**，且被
`.gitignore:54` 的 `/docs/*` 忽略（`git add` 不加 `-f` 无效）。本轮生成快照：
`evidence/p0-1-docs-snapshot-20261104/`（`untracked-list.txt` 9 行、`manifest.sha256`、
`docs-reports-untracked.tar.gz` 33,871 B，含 9 个成员）。**只做兜底，不做裁定**：
入库（`git add -f` + 索引行）还是显式放弃，仍待维护者二选一。

## 未完成内容

1. P0-1 的政策裁定（入库 or 放弃）。快照只挡 `git clean -fdx`，不改变 9 份文件对 git 不可见的事实。
2. `release/0.6.0-stabilization` 处置：复核为 **main 领先 49 / 落后 0，tip `c9717c3` 即 merge-base**
   （全合入、陈旧）。建议删除本地与远端，未执行（维护者动作）。
3. 设备门：DV-01A（OEM SELinux `execute_no_trans`）仍单点阻塞 DV-01→04；16K 宿主；distcc `COMPLETED@36`。
4. P2-3（ktlint/detekt）、P2-4（workflow pin SHA + 依赖锁定）、P2-2（12 个 >800 行文件）不在本轮 scope。
5. 未推送：CI 三条 workflow 的绿转仍需 push 后由 hosted runner 证明。

## 修改文件

- `runtime/contract/src/test/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbInputEncoderTest.java`（新增）
- `app/src/main/java/io/github/nyzeep/orchestella/DualLaneCoordinator.java`（+5/−1）
- `app/src/test/java/io/github/nyzeep/orchestella/DualLaneCoordinatorTest.java`（+36）
- `AGENTS.md`（5 处）、`CONTEXT.md`（4 处）、`docs/agents/issue-tracker.md`（+10）
- `docs/reports/orchestration/zero-device-closeout-20261104.zh-CN.md`（本文件）、`docs/reports/README.md`（索引行）
- 仓库外：`evidence/p0-1-docs-snapshot-20261104/`（快照，不入库）

## 模型、委派、workflow 或 worktree 影响

- 委派：3 个子智能体（测试 / 缺陷 / 文档）各占一条 lane，文件集互不相交；
  编排层负责基线冻结、P0-1 快照、统一验证、报告与提交清单。子智能体一律**禁跑 Gradle、禁 git 提交**，
  避免 Gradle daemon 锁竞争与越权提交。
- worktree：沿用主检出 + `subst X:` 的 ASCII 盘符（见下节），未新建 git worktree。

## 测试与验证结果

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 新增测试（定向） | `:runtime:contract:test --tests "*RfbInputEncoderTest"` | `tests=48 skipped=0 failures=0 errors=0` |
| 缺陷回归（定向） | `:app:testDebugUnitTest --tests "*DualLaneCoordinatorTest"` | `tests=31 failures=0`，含新用例 `accelCrashReleasesReservationAndLease` |
| **反证（修前必红）** | 临时移走两行释放代码后重跑上条 | `31 tests completed, 1 failed`（恰为新用例），恢复后复绿 |
| 回归基线（全量） | `:runtime:contract:test :app:testDebugUnitTest` | `BUILD SUCCESSFUL in 2m 1s`；app `1129/0/0/0`（138 类，较上轮 1128 +1）、contract `137/3 skipped/0` |
| 敏感数据门 | `tools/ci/check_sensitive_data.py --self-test` | `SENSITIVE_DATA_OK` |
| 空白检查 | `git diff --check` | exit 0 |

**环境坑（需入档）**：在含中文与空格的主检出路径下，`gradlew :runtime:contract:test` 会
`ClassNotFoundException: ...RfbInputEncoderTest`（class 文件在盘上，test worker 加载不到）；
同一份源码在 `subst X:` 的 ASCII 路径下 36s 通过。即「中文路径 Gradle 单测坑」在
Gradle 8.13 + JDK 17 上仍成立，`compile*` 任务不受影响，**只有 `Test` 任务受影响**。

## 风险与后续动作

1. **P0-1 仍是唯一不可逆项**：快照能挡住工作区清理，但 9 份文件依旧不受版本控制。建议维护者立刻二选一。
2. **CI 绿转未证**：本轮只证到本地全量单测，`ubuntu-24.04` runner 与 `:app:verifyFastlaneToolboxApk` 仍未验。
3. **测试锁定的可疑行为（未修，待裁定）**：`RfbInputEncoder` 的 SCROLL 会用事件坐标覆盖光标位置
   （`RfbInputEncoder.java:136-139`）；`MOD_CAPS_LOCK`/`MOD_NUM_LOCK` 位一律拒收（`:16`、`:28`），
   若上游透传这两位，整条按键流会被静默丢弃；宽度/高度为 0 时 clamp 返回 (0,0) 而非丢帧（`:175`）。
   三条都已由测试钉住现状，但**当前行为是否正确**需要与 host 端契约核对。
4. **P13 修复的语义边界**：崩溃后清租约意味着后续 `stopAll` 对加速器返回 `ALREADY_STOPPED`；
   这与「UNCONFIRMED 保留租约」互补，不改变停止顺序。

## 越界与 Architecture Change Policy 检查

- 未新增/删除模块依赖，未改 `settings.gradle.kts`、`build.gradle.kts`、CI workflow → 不触发架构变更政策。
- 未改发布门禁与 `verifyRuntimeAssets`；未放松任何 gate。
- 未执行 `git add/commit/push`，未改 `.scratch/` 下任何票面状态字串。

报告人：WorkBuddy（编排会话 + lane-a-tests / lane-b-p13 / lane-c-docs） ｜ 日期：2026-11-04 ｜ 耗时：约 45 分钟

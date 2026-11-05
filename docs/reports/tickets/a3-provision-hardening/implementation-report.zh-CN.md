# Ticket A3（首启 provision 硬化）实施报告

- 票号/标题：性能方案 A3 · 首启 provision 硬化：每操作超时分档 + pacman 锁恢复路径（`docs/research/2026-11-05-performance-optimization-plan.zh-CN.md` `### A3` 节，:79；类型：新开发（小），首启成功率向）
- 执行 session / 模型 / thinking：Lane U provision 硬化智能体 / GLM-5.3-Flash（account:zai-start-plan）/ thinking
- 分支：`feat/a3-provision-hardening`　基线 commit：`966c70e`（main）

## 1. 任务目标

核对 A3 两处产品改动（① `run_step` 超时按操作类型分档；② provision 前置检查/恢复 pacman `db.lck`）在零设备产品化 T1–T7（e130897）中的覆盖现状，只实现残余部分，不重复造轮子；补齐对应的 JVM 单测层（含超时注入、恢复幂等）；顺手落地 R-Q3 的 `LaneBudgetLedger.cpuCores` 语义 javadoc（C1 报告 follow-up 记载的落点）。

## 2. 实际完成（A3 节验收逐条对照）

### 2.1 ① run_step 超时按操作类型分档 —— ✅ 已由先前票覆盖（零设备产品化 T2 / issue 08，e130897）

- 四类档位表已落地：`guest-assets/scripts/orchestella-guest-provision.sh` 的 `provision_timeout_class_for_stage`（显式 case 映射：repository-refresh/certificate-bootstrap-* → download；package-install/package-recovery/init-repair/optional-upgrade → transaction；locale → compute；其余含 package-lock-recovery → verify）与 `provision_timeout_seconds_for_class`（download 900 / transaction 3600 / compute 1800 / verify 600）。
- 覆盖变量 `ORCHESTELLA_PROVISION_TIMEOUT_{DOWNLOAD,TRANSACTION,COMPUTE,VERIFY}` 均可注入，非法值（负数/后缀/前导零/非数字）回落默认并打 `ORCHESTELLA_PROVISION_TIMEOUT_OVERRIDE_IGNORED`（e130897 提交说明与 issue 08 Comments 记录一致）。
- state 追加 `operation_timeout_class` 字段；宿主 `BridgeClient.DEFAULT_STARTUP_TIMEOUT_MS` 3.9e6→5.4e6、guest `TimeoutStartSec` 2h→5400s，两道外墙由跨层一致性单测 `runtime/contract` 的 `ProvisionTimeoutBudgetTest`（4 例）钉住。
- 方案给出的备选「进度心跳 + 总闸」未走：T2 选择了「更高上限」分支（issue 08 Comments：用户裁定「分档 + 默认上调」）；方案风险条款要求的「总闸与心跳日志」以既有形态保留——每 stage 起点 report 行 + `run_step` 全量输出重定向进 guest 操作日志（`>> "$LOG"`），未删除任何心跳/日志路径。

### 2.2 ② provision 前置检查/恢复 db.lck —— ✅ 已由先前票覆盖（零设备产品化 T1 / issue 07，e130897）

- 产品脚本已有 `pacman_lock_holder`（三态：0 持有 / 1 无持有 / 2 无取证手段）与 `recover_pacman_lock`（`/proc/[0-9]*/fd` readlink → `fuser` → 两者不可得即「不可判定禁删」→ 确认无持有者后 `sleep 3` 二次确认 → 删除），调用点在 pacman 分支取写锁之前且仅当 `$ROOT/var/lib/pacman/db.lck` 存在时进入。
- 新 stage `package-lock-recovery`（verify 档）与 reason 词表 `package-lock-held` / `package-lock-recovery-failed` 已落地，锁错误不再被误报为 offline；`-Syu` 事务后锁重现也改判 `package-lock-held`。
- A3 指定的「照 T7-F probe-init.sh 探针侧修法产品化」已完成且更安全：探针侧是无条件 `rm -f`（`research/uml-fabric-probe/t7/probe-init.sh`），产品化换成证据制三态删除；探针副本与产品脚本保持 diff 归零（issue 07 Comments 记录两侧 md5 一致；Lane K 后续 `67d5725`/`0d623b3` 修了探针 fixture 的孤儿进程问题，根因 A 即本恢复路径）。

### 2.3 残余缺口 —— ✅ 本票补齐（JVM 行为单测层）

T1/T2 的行为测试只存在于两个 python 驱动器（`guest-assets/scripts/test-distro-runtime-matrix.py`、`test-guest-runtime-provision.py`，跑在 guest-assets CI），gradle JVM 层此前只有 `ProvisionTimeoutBudgetTest` 的文本解析 4 例；e130897 的「定向验证：抽函数 10 例（900s/0900/0/-60 均被拒并回落）」是手工验证，未入库。本票新增 JVM 行为单测 17 例（见 §6），把分档边界、超时注入、锁恢复幂等钉进 `:runtime:instance:check`。

### 2.4 验证方法 —— ⚠️ 零设备部分完成，真机未验证

- 「锁恢复分支可先零设备单测（镜像状态注入）」：✅ python 驱动器 A/B/C/D 四场景（先前票）+ 本票 JVM 镜像状态注入 5 例。
- 「真机（T7-F 配方复跑全链）」：❌ 本轮无设备窗，无法执行；沿用 e130897 报告 §7 的未验证清单（锁恢复两路径、档位真机生效），入口不变。

### 2.5 依赖与阻塞 —— ✅ 无

「不阻塞 ACR 投票」确认无依赖；「若 92 包事务被拆分（镜像 vintage 策略变更）则本条自动降级」——镜像策略未变更，A3 维持现状记录，无代码动作。

### 2.6 顺手项 R-Q3 —— ✅ 已落地（doc-only）

`runtime/instance/.../duallane/LaneBudgetLedger.java` 类注释补「cpuCores = 车道并发预算，非物理核数」语义（R-Q3，引 `docs/research/2026-11-04-qemu-mttcg-scaling.md` §4.2）：QEMU 面在唯一转换点 clamp 到实测有效 TCG 宽度（`FabricWidthDecision.qemuLaneBudgetCores`）、ACCEL match_host 档填宿主全核（R-Q2）、双 VMM 并存 `usedCpu = smp N + match_host 全核` 属合法超订（R-Q7）、`CPU_EXCEEDED` 是并发队列满而非物理核耗尽；`Reservation.cpuCores`/`Snapshot.cpuCores` 与 `LaneRequest.cpuCores`（C1 follow-up 点名的另一落点）补同语义 param/record 注释。零行为改动。

## 3. 未完成 / 与预期偏差

- 真机验证（T7-F 配方复跑）未执行——本轮无设备窗，与 e130897 未验证清单同口径，无新增未验证面（本票零 shell 行为变更）。
- 无其他偏差。A3 两处产品改动均已存在，本票没有重写 shell 逻辑（任务指示「如实验证并记录，不重复造轮子」）。

## 4. 修改的模块

| 模块 | 文件 | 性质 |
|---|---|---|
| `runtime/instance` main | `duallane/LaneBudgetLedger.java`、`duallane/LaneRequest.java` | javadoc-only（R-Q3） |
| `runtime/instance` test | `ProvisionScriptHarness.java`（新）、`ProvisionTimeoutTierContractTest.java`（新，12 例）、`PacmanLockRecoveryContractTest.java`（新，5 例） | JVM 行为单测 |

- 未触碰 forbidden 清单：编译派发/`FabricWidthDecision`/`AcceleratorLaunchDispatcher`（B2 lane）、Settings UI、`.github/workflows/**`、各 `build.gradle.kts` 均未改动——新增 JUnit 类经 java 插件默认 `test` 任务自动并入 `:runtime:instance:check`，无需改构建文件。
- `guest-assets/**` 零改动（覆盖审计结论：无需改）。

## 5. 重要设计决策

1. **残余界定为「测试层缺口」而非「实现缺口」**：A3 两处产品改动逐条与 e130897 实现（`orchestella-guest-provision.sh` 的四个函数 + 两道外墙）比对后确认已覆盖；残余是 JVM 层无行为单测（分档边界/注入/幂等仅 python 驱动器与手工定向验证覆盖）。测试落 `runtime/instance`（本 lane 独占域，任务指定的 `:runtime:instance:check` 验证面）。
2. **测试执行被测源码而非复刻**：从产品脚本文本抽取真实函数（连同其上方语义注释）eval 进 fixture，在真实 POSIX sh 下执行——python 驱动器同型手法的 JVM 化，杜绝「测试复刻实现、实现漂移测试不知」。
3. **证据注入双轨**：holder 判定依赖 `fuser`/`/proc`，不同宿主可用性不同。确定性路径 = 经 `PATH` 注入假 `fuser`（exit 0/1 两态，测试内 chmod +x 兼容 Linux 与 Git Bash noacl 挂载）；真实性路径 = 宿主真 `/proc` 描述符扫描（Windows 经 `cygpath` 转换路径后 MSYS /proc 亦可用，本机实证跑通），不可用时 `Assume` 跳过并留因。
4. **幂等断言方向**：恢复成功后的第二次调用 rc 保持 0 且文件不再出现（产品调用点有 `[ -e ]` 守卫，该形态等价于无操作）；「锁不存在且无证据源」直接调用返回 2（fail-safe 方向：不可判定即不删），按现状钉住并在测试注释说明产品守卫使其不可达。
5. **script ↔ generated init 漂移护栏上移**：`provision_timeout_class_for_stage`/`provision_timeout_seconds_for_class`/`pacman_lock_holder`/`recover_pacman_lock`/`run_lock_recovery_stage` 五个函数块（含注释）在产品脚本与 `guest-assets/initramfs/init` 间的文本一致性，从 python 生成器测试之外新增 gradle 层断言。
6. **Windows 进程参数陷阱规避**：`sh -c` 内联体中嵌套在 `$( )` 里的双引号会被 Windows 参数引号规则静默截断（本票实证：body 在 `$(dirname "$(command -v sh)")` 处断开，exit 2 空输出）；harness 固定为「场景写 fixture 文件、`sh <file> args...` 执行」，仅保留三处无嵌套引号的一行体。

## 6. 测试

- JVM（本票新增 17 例，均绿）：
  - `ProvisionTimeoutTierContractTest`（12）：18 个 stage 逐个断言 I/O 类；未分类 stage 回落 verify（fail-safe 语义）；`run_step`/`dnf_step`/`zypper_step` 调用点扫描无表外 stage（含注释行排除）；档位表+默认值与生成 init 逐字节一致；四类默认值 900/3600/1800/600；未知类返回 1；四类正整数覆盖注入生效；8 个非法值边界（0、0900、900s、-60、1e3、abc、9 9、+900）回落默认且 IGNORED 行带 class/value；空值=未设置且不打 IGNORED；纯数字任意长度放行；注入预算真到 `timeout` 工具（VERIFY=1 → `timeout 1 sleep 2` → 124）与默认 600s 放行 `true`（rc=0）。
  - `PacmanLockRecoveryContractTest`（5）：陈旧锁删除且二次调用幂等（rc 恒 0、文件不再出现）；fuser 判持有 → rc 2 锁保留、释放后同一调用收敛为删除（rc 0）；无证据源 rc 2 永不删除（锁路径为参数、非硬编码）；真 /proc 描述符扫描识别活持有者（fd 停留 → rc 0 持有）且持有者退出后恢复收敛；五个锁函数块与生成 init 一致。
- `:runtime:instance:check`：BUILD SUCCESSFUL（35s；12 个 JavaExec 契约任务 + JUnit `test`）。JUnit 层合计 69 tests / 0 failures / 0 errors / 0 skipped（基线 52 + 新增 17）。
- `:app:testDebugUnitTest -Porchestella.assets.allowMissingPayload=true`：BUILD SUCCESSFUL（59s），1190 tests / 0 failures / 0 errors / 0 skipped——与 main 基线计数一致。
- 既有 python 驱动器（`test-distro-runtime-matrix.py`、`test-guest-runtime-provision.py`）：本票未运行（shell 零改动，无回归面；guest-assets CI 覆盖）。
- `tools/ci/check_sensitive_data.py --self-test`：SENSITIVE_DATA_OK；提交后按 range 复扫见 §11。

## 7. Benchmark / 实测数据

本票不涉及（A3 收益为「首启成功率向」，依赖真机 T7-F 复跑取数；本票零行为变更，无新的性能数据）。

## 8. 风险

- **JVM 测试的环境依赖**：三处 `Assume`（sh 可用、GNU 式 timeout 可用、/proc fd 可用）在不满足时跳过而非失败——跳过会被如实计入 skipped，不会静默丢失（本次 Windows Git Bash 实跑 17/17 全执行、0 skip）。
- **新增测试对脚本文本形态的耦合**：函数抽取按 `name() {`…`^}` 行形匹配，脚本重排（如缩进/换行风格变化）会让抽取失败并红——这是有意的漂移报警，代价是改脚本时可能需同步改测试（改动面在本 lane 文件域内）。
- **javadoc 引用的外部文档路径**（mttcg 文档）若日后改名会留下失效引用——属文档级风险，无运行时影响。

## 9. 新发现

- `recover_pacman_lock` 对「锁文件不存在且无任何证据源」返回 2（不可判定），而非 0：`pacman_lock_holder` 不先查文件存在性。产品调用点有 `[ -e "$pacman_lock" ]` 守卫故不可达，且 fail-safe 方向（宁报持有不删）正确——已在 JVM 测试注释记录，未改行为；若未来有人在其他路径直接调用该函数需注意此语义。
- Windows JVM 测试进程参数陷阱（嵌套 `$( )` 内引号被截断）为可复用模式：后续任何「JVM 测试驱动 POSIX 脚本」的票都应走 fixture 文件而非 `sh -c` 内联体（`ProvisionScriptHarness` 已封装，可直接复用）。
- 既有 `ProvisionTimeoutBudgetTest`（runtime:contract）与本票新测试存在四类默认值的重复断言（一侧文本解析、一侧函数执行）——双保险是有意的，无需收敛。

## 10. 对后续 ticket 的影响

- B2（fabric 编排 worker）：无影响——本票未触碰派发/桥/账本行为。
- D1（账本语义补丁 non-reclaimable）：R-Q3 javadoc 已落，D1 落地时 `LaneBudgetLedger` 类注释需在现有「并发预算」段落上追加 non-reclaimable 标记语义，勿重写。
- 若 A3 相关 shell 后续被改动：`test-distro-runtime-matrix.py` / `test-guest-runtime-provision.py`（行为）与本票两个 JVM 契约类（边界+漂移）需同场过绿；改 `orchestella-guest-provision.sh` 的函数块时同步 `sync-guest-init.py` 再生成 init，否则新增的 script↔init 漂移护栏会红。
- 真机窗口排期时：e130897 报告 §7 的锁恢复/档位真机条目仍是 A3 完全收口的唯一缺口。

## 11. Commit

分支 `feat/a3-provision-hardening`（基线 966c70e）：

- 实现（R-Q3 javadoc，doc-only）：`docs(instance): R-Q3 账本 cpuCores 并发预算语义 javadoc——C1 follow-up 落点`
- 测试：`test(instance): A3 残余硬化 JVM 单测——超时分档边界与锁恢复路径 17 例`
- 报告：`docs(reports): a3-provision-hardening 实施报告——A3 验收对照与残余界定入库`

（提交后补记 hash：实现 `7a633c3`、测试 `61992b4`、报告即本 commit；`python -I tools/ci/check_sensitive_data.py --range 966c70e..HEAD`（含报告）→ SENSITIVE_DATA_OK，无新增敏感数据。）

## 12. Ticket 状态

建议：**completed**（零设备口径）。理由：A3 验收中可在零设备完成的部分全部落地或确认已覆盖（两处产品改动由 e130897/T1+T2 覆盖并经本票审计实证；残余 JVM 测试层补齐且全绿；R-Q3 顺手项完成）；真机 T7-F 复跑一项受设备窗限制未执行，与先前票未验证清单同口径，不阻塞合并。

报告人：Lane U provision 硬化智能体（GLM-5.3-Flash）｜ 日期：2026-11-06 ｜ 耗时：单 lane 单轮（gradle 三轮定向验证 + 两轮全量）

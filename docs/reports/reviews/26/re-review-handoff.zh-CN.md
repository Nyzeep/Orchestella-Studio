# Ticket 26 复评交接要点（E 门二次运行 · 给新鲜独立 reviewer）

- 用途：把 E 门复评交接给**与实现上下文完全隔离的新会话**。首轮评审由 `docs/reports/reviews/26/review-report.zh-CN.md`（SHA-256 `652608b67d9959f4b02a0f9752f4e72783ab14ff7e90b07e66c3ce734c399f91`，87 行）产出；其后的两批补正增量由承担实现角色的会话执行，**首轮已存在独立性披露，且补正与实现出自同一线程——复评必须由全新会话进行，实现者不自审**。
- 固定 review point（不变）：`8859234a7cd922d4a99385874d1638f31e20c518`（merge-base）。
- 复评范围：**仅首轮（652608b6…）之后的两批补正增量**；首轮发现与其中一项误报纠正在 review-report 内**原样保留**，复评在同文件追加复评节。
- 基线（接手实测，末态）：`WAIVER_ASSERT_OK 101=100+1 m13=13 u76=76 staged=0 mfp=ok`（脚本 `R/evidence/waiver-assert-20260924.ps1`；R=`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\artifacts\26\android-toolchain-build-20260922`）。**本交接文件自身为新增 owned 件，写入后 untracked 应为 77——基线 u76→u77 的 assert 同步属补正，路由回实施会话，复评勿代改**。

---

## 一、被复评增量精确清单（文件路径 + 现 hash）

### 批次 1（第二十八班次，E 后补正：S3 + S1-a + 披露更正；报告行 2237 起）

| 文件 | 状态 | SHA-256 |
| --- | --- | --- |
| `tools/qemu-android/scripts/prepare-qemu-payload.sh` | 新增 | `2237f887105803e3a5a686e62967b640a520f45814f6b28e2bd3772b0b800cc6` |
| `tools/qemu-android/scripts/test-workflow-local-validation.sh` | 新增 | `01b35f258d2b8fc6f882636a630f0868c0811f67b59d26d54c5eb018e1312709` |
| `.github/workflows/qemu-android.yml` | 修改（批 1+2 累积） | `8714ab520230f8e0394190fabcd85e5c25ed3d96482ef05d75598d4f5cb81f27` |
| `tools/qemu-android/qemu-jni.lock` | 修改（S3 补 configure_args 三值；批 2 再 +2 键） | `2d0f3ea66455cd9cd602a11e21c9aa866b757c1fbc6f0243bca964e46bf72b2a`（127 键） |
| `tools/qemu-android/scripts/verify-qemu-jni-lock.sh` | 修改（configure 精确值门 + vendored 字节核验 + 键数 125→127） | `5b375d619aaef64fed817c7c746f6fa5dc6ae577a824ed996faedc2e090696f9` |
| `tools/qemu-android/scripts/test-verify-qemu-jni-lock.sh` | 修改（+incomplete-configure / missing-vendored-path / swapped-vendored-path 三用例） | `0e21ce64524aab02fa1d8fcd04327aa8be8a36059c6c78ba72511727db474248` |

### 批次 2（第二十九班次，S1-b 选项 1：vendored 固化归档；报告行 2264 起）

| 文件 | 状态 | SHA-256 | 大小 |
| --- | --- | --- | --- |
| `tools/qemu-android/vendor/proxy-libintl-0.4.tar` | 新增（冻结件入仓） | `55b5d8f9d6c4cd844ff349ea932fa3a178f3971c7874c843ddc90523adc5264a` | 51,200 B |
| `tools/qemu-android/vendor/dtc-b6910bec.tar` | 新增（冻结件入仓） | `1df504e71aa4704157ec94f37da2aa82d672349f20bd92ca79516a0a56a1a29a` | 1,116,160 B |
| 上表 workflow/lock/verifier/test | 累积修改 | 同上（现行 hash 即上值） | — |

### 报告 append 位置与现 hash

- canonical 实施报告 `docs/reports/tickets/26/implementation-report.zh-CN.md` 现 hash `df281261a320b928f0c013c7943f424e1d0fcfd88bbfe7aeca7b9d1dd7dcac67`（2,284 行）；两批补正的 append 位置：
  1. **第二十八班次**（`### 2026-09-26（第二十八班次·E 后补正）…`，**行 2237**）：S3 RED→GREEN、S1-a 三件、S1-b 选项提交、终表披露更正（AC②/AC⑥ 豁免、C 标题括注、AC③ 加注、settings.gradle.kts 补记）、u71→u74 算术。
  2. **第二十九班次**（`### 2026-09-26（第二十九班次·S1-b 选项 1 执行）…`，**行 2264**）+ **定性注记**（`### 2026-09-26（QEMU-BUILD-INPUTS lock 引用定性注记…`，**行 2280**）：vendored 入仓双 hash、两 job 收敛、lock +2 键 TDD、provenance 链、门重跑、u74→u76；BUILD-INPUTS 两句话定性注记。
- 首轮评审报告 `docs/reports/reviews/26/review-report.zh-CN.md`（`652608b6…`）**未改**，复评节将追加于其后（由复评会话写入）。

---

## 二、首轮发现核销索引（Standards 七项 + Spec 五项）

### Standards（首轮 (a)–(g)，含误报纠正与 smell 三项）

| # | 首轮发现 | 核销所需索引（去哪验） | 补正声称 |
| --- | --- | --- | --- |
| a | `settings.gradle.kts` 不在 dispatch §3.1 字面清单（hard-by-letter）；orchestration/dispatch 文档灰区 | 对照 `docs/reports/tickets/26/full-closeout-dispatch-20260923.zh-CN.md` §3.1 清单；报告行 2237 批 1 (d) 补记条目 | 经裁定补记为 Ticket 26 ownership（m13 封存集，仅此一项，dispatch 不改写） |
| b | 端点：六源主机=误读（Q4 "lock hash-pinned 上游"覆盖）；**git URL curl ↔ `*_git_archive_sha256` 机制错配=实** | workflow 旧 111-112 行（历史）→ 现 `copy_vendored`/vendored 拷贝块；impl-report:1980（git 版本字节差异事实）；lock `*_git_url` 仍存（历史取数出处）vs workflow 零 git 源 curl | S1-b 选项 1：vendored 入仓+字节校验；**CI 运行期端点不含 gitlab/github** |
| c | lock `configure_args` 缺 `--cross-prefix/--cpu/--prefix`；无 APK 输出 sha/DT_NEEDED 键；**断链 `ci/prepare-qemu-payload.sh`** | lock:22 现行 9 项值 vs `build-in-container.sh:294-297` 实际调用；`grep prepare-qemu-payload .github/workflows/qemu-android.yml` + `ls tools/qemu-android/scripts/`；注意"无 APK 输出 sha/DT_NEEDED 键"一项**未声称修复**（复评自行判定是否残余） | S3 补三值（门 13→14）；S1-a 脚本落 `scripts/` 并改指 `/pkgbuild/scripts/…`；apk job 挂载/env 连贯化 |
| d | GPL 四件 + guest-agent NDK 工具链偏离披露=OK | `tools/qemu-android/gpl/*`（注意：其 lock 引用为构建时态，见第三节） | 无改动（复评确认仍 OK） |
| e | 用户裁定执行一致性=OK（许可台账、两例陈旧处置定性） | `R/logs-preserved-20260925/d-stale-state-disposition-20260926.txt`、`…-2-…txt` | 无改动 |
| f | 测试真实性：**Bug #6 GREEN 误报**（已纠正：空输出=静默成功约定，ClassNotFound 件为 no-overwrite 中间轮）；Bug #4 无单元 RED→GREEN（minor） | `R/tdd-launch-fix-20260925/s8-*` 文件族（red/中间失败轮 vs `*-run2/compile2/regression2` 空文件+RC=0）；报告行 2237 (a) 与 2264 (d) 的 TDD 记录；本轮三用例 `s1b-{red,green}-lock-test-20260926.log.txt` | 本轮新增 TDD：S3（RED→GREEN）与 S1-b lock（RED→GREEN） |
| g | Git 治理=OK（2 docs 提交、零 commit/push、无 clean 声明、waiver 排除） | `git log 8859234a..HEAD`；`git status`（waiver 101 D 不在改动集） | 无改动 |
| smells | Duplicated Code（workflow 重复 fetch 块）；Middle Man（`afterGuestExit` 转发）；Primitive Obsession（IOException 消息码） | 各自 hunk（workflow 供给块 ×2、`InstanceLaunchSequence.java`、`QemuRuntimeBootstrapper.java`） | **未修复**（judgement 类，首轮已按 judgement 记录；复评自行判定） |

### Spec（首轮 (a)–(e)）

| # | 首轮发现 | 核销所需索引 | 补正声称 |
| --- | --- | --- | --- |
| a-② | AC② 以网络失败现场冒名校验失败证据；设备级坏 tarball 观测未达成未声明 | 报告行 2237 (d) AC② 更正条目；`R/s7-negatives-20260925/s7-ac2-*.log.txt`；shift-21 "无 UI 注入点"记录（原报告 :2094） | 撤误引（`d-create-*`=DNS 现场）；真实证据=S7 JVM seam + 宿主 harness checksum 回归；**用户豁免设备级观测 + "设备级从未观测"并列** |
| a-⑥ | 模拟器正向路径无实测无豁免 | 报告行 2237 (d) AC⑥ 条目；`DeviceCapabilityEvaluatorTest`；真机负向截图 | **用户豁免正向实测**，证据=真机负向+单测正向，并列 |
| b | C 标题"八项全过"对 pin-blocked/hosted 未执行略过；AC③ 与关机停滞并列张力 | 报告行 2237 (d) C 标题括注 + AC③ 加注；第二十班次原文（行 2051 附近）保留 | 括注：apk pins 前 fail-closed、hosted 属 push 后；AC③ ✅ 限启动链 |
| c | 未运行项与两观察项披露=OK（诚实） | 报告 :2226 观察项节、:2173/:2233 未运行披露 | 无改动 |
| d | 无 scope creep | bridge 面 HELLO/PING/RESIZE；无 host power UI | S1–S3 增量均为 CI/lock/记录类，无产品行为改动（**复评核此论断**） |
| e | B2 边界=OK（无陈旧对账能力声明） | `InstanceLifecycle.java:27` javadoc；两处置记录定性句 | 无改动 |

---

## 三、BUILD-INPUTS 定性复核要点（复评指令第 3 项）

- 论证：`tools/qemu-android/gpl/QEMU-BUILD-INPUTS` **文件不改**，其 lock 引用（第 3-4 行，`125 keys, sha256 d3326352…`）定性为 **payload 构建时态 provenance**；产物同一性=**`63ac0f5f7c28c58f0087a900f9877b23f80307404ec383f1afac1755cc84954f`（60,306,004 B）既是 AC 实测装机件即拟提送件**（沿革 …→ec181467→63ac0f5f；五步门 RC=0）。
- 核销点：(i) 该论证是否成立（构建时 provenance 是否足够，或按 policy §7 应随改+五步门重跑）；(ii) 报告注记（行 2280）语义是否充分；(iii) "未来任何 payload 重建前必须先同步该引用"规则是否明确可执行。
- 注意其 build execution facts 已含实际 configure 全参（`QEMU-GPL-SOURCES.md:29` 9 项），与 S3 后 lock 值一致——复评可交叉验证。

## 四、新会话必读文件清单（按序）

1. `AGENTS.md`（worktree 根）+ `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/` 全部（README / DATA_EGRESS… / INCIDENT_RESPONSE…）；
2. `docs/reports/tickets/26/worker-scope-contract.zh-CN.md`（ownership、B1/B2/B3、§5 验证策略）；
3. `docs/reports/tickets/26/full-closeout-dispatch-20260923.zh-CN.md`（§3.1 ownership 清单、§7 门定义、§8 D 七条、§1 fail-closed）+ `.scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md`（AC 原文）；
4. `docs/agent-handoff/QEMU_JNI_SUPPLY_CHAIN_POLICY.md`（§3 lock 完整性/缺值即失败、§7 更新纪律）+ `GPL_RELEASE_DELIVERY_POLICY.md`；
5. **`docs/reports/reviews/26/review-report.zh-CN.md`（首轮全文，652608b6…——含发现与误报纠正，复评节追加于此）**；
6. **本交接文件**；
7. `docs/reports/tickets/26/implementation-report.zh-CN.md` 行 2237–2284（两批补正 + 定性注记；全文 2,284 行，前文按需回查）；
8. 增量源文件（清单一的 8 件）+ `tools/qemu-android/ci/build-in-container.sh`（S3 configure 实际调用源）+ `tools/qemu-android/gpl/QEMU-BUILD-INPUTS` 3-4 行（定性复核对象）。
9. 证据目录（R 下）：`logs-preserved-20260925/s3-{red,green}-lock-test-20260926.log.txt`、`s1b-{red,green}-lock-test-20260926.log.txt`、`s1-workflow-validation-final-20260926.log.txt`、`s1b-workflow-validation-20260926.log.txt`、`s1-manifest-gate-20260926.log.txt`、`d-bug6-bridge-inversion-diagnostics-20260926.txt`、`d-stale-state-disposition*-20260926.txt`。

## 五、复评边界（与首轮一致）

- 除 `docs/reports/reviews/26/review-report.zh-CN.md` 追加复评节外**零写入**；零构建、零设备、零网络（本地只读 + hash 重算除外）；
- 已披露不等于自动放行（工具链偏离、两例陈旧处置、两观察项、历史 .log 丢失、A 受限 PASS）；
- 双轴各出 approve / request-changes + 依据；任一 request-changes → 停并列残余项；**残余修复一律路由回实施会话，复评不代改**；
- 不 commit/push、不标 completed、不启 27–37。

交接人：原评审会话（转为交接角色，此后不执行复评与补正） ｜ 日期：2026-09-26

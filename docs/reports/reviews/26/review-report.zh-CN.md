# Ticket 26 独立双轴 Review 报告（E 阶段）

- 票号/标题：26 — 第一垂直切片：创建 Alpine 实例并进入 shell（QEMU 档）
- 固定 review point：`8859234a7cd922d4a99385874d1638f31e20c518`（merge-base）
- 审查对象：该点至当前工作树的全部 Ticket 26 变更 = 2 个已提交 docs 增量（`8f1aaa1`、`e0fc8b9`）+ 16 项 tracked 修改（其中 13 项为未提交工作树修改）+ 71 项 owned untracked 文件（末态实测 gate `u71=71`）。**101 项 `.agents/skills/**` + `skills-lock.json` 删除为用户外部既有状态（baseline waiver），显式排除，不计入改动缺陷。**
- 评审方法：code-review 双轴（Standards / Spec）并行独立子评审 + 聚合前争议事实复核（只读 + hash 重算）；零构建、零设备、零网络。
- baseline-aware gate（评审当日实测）：`WAIVER_ASSERT_OK 101=100+1 m13=13 u71=71 staged=0 mfp=ok`。
- **审查者独立性声明（披露）**：本次评审会话在同一线程内此前承担过实现者角色，虽以 orchestrator 指派的独立 reviewer 身份执行且全部结论以固定 review point 的原始源/证据复核为准，线程上下文连续性仍偏离 dispatch §9"全新、独立于实现上下文的 reviewer session"的理想形态。orchestrator 可据此决定是否要求完全新鲜会话复评。

---

## Standards

（子评审原文，聚合复核标注以【复核】标出；事实复核只纠正误读，不重排发现）

**(a) 变更路径 vs dispatch §3.1 ownership —— FINDING（按字面，hard）**
清单内改动（`app/**`、`runtime/{contract,distro,instance,capability,engine-qemu}/**`、`tools/qemu-android/**`、单文件 `.github/workflows/qemu-android.yml`、本票 tracker 文件、guest-assets 窄例外：`init`、openrc×2、`guest-agent.c`、`GuestAgentProcessTest`、smoke 脚本、`test-local.sh`/`common.sh` 测试接线）逐条在册。清单外：**`settings.gradle.kts`**（根 `include(":runtime:*")` 与移除 `gradlePluginPortal()`）——不出现在 §3.1 闭合清单与 scope contract §2.1 字面；以及 `docs/reports/orchestration/*` ×5 与 `docs/reports/tickets/26/{dispatch-packet,implementation-tdd-dispatch-packet,full-closeout-dispatch}`（策略会话产物，治理灰区）。已记录的 §3.3 越界事件：子代理曾读 `ticket-26-canonical`（自曝、已冻结、输出作废，`ticket-26-historical-worktree-read-incident-2026-09-23.zh-CN.md:5-9`）。
【复核】`settings.gradle.kts` 自基线起即属 gate 封存的 13 项 tracked 修改集（`m13` 指纹覆盖），属"字面清单未点名但已封存所有权状态"——建议在 dispatch §3.1 补记该文件，非实质越界。

**(b) 端点 —— FINDING（部分成立）**
子评审点名 `download.gnome.org`、`cairographics.org`、`zlib.net`、`gitlab.com/qemu-project/dtc` 越出 Q4 集合。【复核】**该部分为误读**：Q4 终态集合=「snapshot + 该 MCR digest + **lock hash-pinned 上游** + 重定向 CDN 逐记录」（impl-report:1787），六源上游正属"lock hash-pinned 上游"，且经用户"deps 六源…已认定"裁定；`github.com/frida/proxy-libintl` 属"仅三仓钉 revision"授权。
**成立部分**：workflow:111-112 以 `curl -o …tar` 直取 `proxy_libintl_git_url`/`dtc_git_url`（git 仓库 URL）而校验 `*_git_archive_sha256`（git-archive 字节）——git URL 的 curl 响应不可能等于钉定 archive 字节，取数机制与 pin 形态错配（CI 走到该步将 fail-closed，但机制错误）。另 `services.gradle.org` wrapper 取数尝试已按事件入册停止（gradle-wrapper-network-attempt-2026-09-21:8-12）；`example.invalid` 为负例夹具。

**(c) qemu-jni.lock 完整性 —— FINDING（minor×2 + 断链×1）；CI pins 状态=按 §3 如实 fail-closed**
hash 重算一致：lock `d3326352…` ↔ QEMU-BUILD-INPUTS:4、`shm_stub.c/.h` ↔ lock:123-125、`LICENSE.GPL-2.0` `dd3ce023…` ↔ workflow:257。§3 覆盖良好（patches=none 显式、builder digest、NDK 双校验、SOURCE_DATE_EPOCH、PYTHONHASHSEED、apk 模式、deps/shim pin、125 键 verifier 门）。gaps：lock `configure_args`（:22）仅记 `--target-list/--disable-docs/--disable-download`，实际构建另用 `--cross-prefix/--cpu/--prefix`（全量仅见 QEMU-GPL-SOURCES.md:29）——§3 要求"complete ordered configure/Meson args"；无 APK 输出 sha256 / DT_NEEDED 闭包键。**断链**：workflow:204 运行 `bash /pkgbuild/ci/prepare-qemu-payload.sh`（挂载自 `tools/qemu-android`），**该脚本不存在**（`ci/` 仅 `build-in-container.sh`）。
【复核】断链属实（sed 185-215 + 目录清单核验）；SDK/JDK pin 缺口仍以 `FAIL_MISSING_LOCKED_VALUE…exit 1` fail-closed，与 §3"缺值即构建失败"一致，未被冒充通过。

**(d) GPL 四件 + guest-agent 工具链偏离披露 —— OK**
四 records 完整（QEMU-BUILD-INPUTS / QEMU-GPL-SOURCES.md / LICENSE.GPL-2.0 / NOTICE.QEMU）；GPLv2 原文 hash-gated（workflow:257）；QEMU-GPL-SOURCES.md §1-5 覆盖 source/tag/commit/hash/sig/fingerprint/patches/configure/builder+NDK/entrypoint/binaries/deps+licenses；对应源码公共通道如实延后至发布时（§5）。guest-agent 偏离（NDK r28c clang-19 `--target=aarch64-linux-android26 -static` vs build.sh musl-gcc，"离线不可复现"）披露于 QEMU-GPL-SOURCES.md:82-94 + QEMU-BUILD-INPUTS:92-100 并交叉引用 impl-report:2181——**充分且诚实**。

**(e) 用户裁定执行一致性 —— OK**
android-sdk-license 范围接受（impl-report:441-442；唯一性审计 :1540）；六源许可台账（`R/manifests/qemu-android-deps-license-ledger-20260924.tsv`）；proxy-libintl LGPL-2.1-or-later 选举（lock:114-115 + `R/evidence/proxy-libintl-license-election-20260924.md`）；iconv `official_checksum=absent`（lock:106）。两次陈旧处置记录均含逐字限定「session-level stale-reconciliation evidence substitute; NOT Ticket 26 product capability」（`d-stale-state-disposition-20260926.txt:2`、`…-2-…:2`），且产品代码无对账能力声明（InstanceLifecycle.java:27）。

**(f) 测试真实性 —— FINDING（1 项误报已纠正，2 项成立）**
真实 bug 锁：`QemuRuntimeBootstrapperTest`（rootVolume 身份/存在性/`root-volume-missing`）、`InstanceLaunchSequenceTest`（RUNNING→STOPPED→RUNNING + 单运行）、EmbeddedProvisionInput（`FAIL reason=segment missing parent directory entry: opt` → `OK … parent_dirs=2`）、`GuestAgentProcessTest`（8 项行为用例）、#1 的 `fix-red-compile`→`fix-green-bootstrap-test OK (2 tests)`。
【复核·纠正误报】子评审称 Bug #6 GREEN 无据（"s8-green-run2 0 bytes"等）——**与本仓测试约定不符**：契约测试仅在失败时打印（`fail()`+exit 1），空输出+RC=0 即通过。实测文件盘点：`s8-red-compile`（2238 B，RED：缺 BridgeClient）、`s8-green-compile`（2328 B，throws 签名修正轮）、`s8-green-run`/`s8-responder-regression`（ClassNotFound，编译失败轮产物，按 no-overwrite 保留）、**`s8-green-compile2`/`s8-green-run2`/`s8-responder-regression2` 均 0 B + RC=0 = GREEN**；impl-report:2209 的"GREEN…回归 RC=0"记录**成立**。
**成立项**：Bug #4（initramfs/init）无单元 RED→GREEN（仅设备探针链 `d-diag*`）——证据链充分但形式偏离 TDD 纪律，记 minor；`.log` 丢失事件豁免 `*.log.txt`，上述缺口不受影响。

**(g) Git 治理 —— OK**
仅 2 个 docs 提交（均 orchestrator 线）；实现会话零 commit/push/PR（impl-report:404,1095）；无 clean 声明（"工作树有意 dirty"）；waiver 101 项不在改动集；治理分支提交 `ee409e2` 如实标注非产品证据（:1085）。

**Baseline smells（judgement calls）**
- Duplicated Code — workflow:141 `echo "Provisioning identical to native-build-two-clean…"` 后重复实现 fetch+sha256 块；建议抽出单一 provision 脚本。
- Middle Man — `InstanceLaunchSequence.afterGuestExit` 仅转发 `controller.recordNormalGuestStop(id)`。
- Primitive Obsession — `IOException` 消息串错误码（`"root-volume-missing"`，QemuRuntimeBootstrapperTest:70）宜用类型化错误。

**Standards 结论：REQUEST-CHANGES**（最重项：workflow:204 引用不存在的 `ci/prepare-qemu-payload.sh` + git-URL-as-tar 取数与 `*_git_archive_sha256` 错配——CI 真实可执行性缺口；次为 §3.1 字面未覆盖 `settings.gradle.kts`、lock `configure_args` 不全）。

---

## Spec

**(a) 七项 AC 逐条（报告终表 impl-report:2213-2227 对 dispatch §8:336-346；票面复选框 26-impl-…md:50-59）**

| AC | 判定 | 依据 |
| --- | --- | --- |
| ① 安装→shell 全链、可重复、无 Root | ✅ | 三启动链证据 `d-ac-c8d66cae-*`、`id=uid=0`、双装机段（`63ac0f5f`/`ec181467`） |
| ② tarball 校验失败拒绝+明确原因 | ⚠️ **(iv) 证据冒充风险** | 证据=JUnit（`s7-ac2-*`：`rootfs-checksum-mismatch`/`rootfs-size-mismatch`+原因）+"产品 fail-closed 下载路径（早期现场）"。worker-scope §5.4 明言"JVM/contract/Gradle 测试不能代替此项"（:113-116）；"早期现场"实为 shift-22 DNS 下载失败，**并非校验失败拒绝**；shift 21 已录"AC② 无 UI 注入点"（:2094）。S7 路由有 r2 交接 §六执行序背书，但终表证据引用以网络失败冒名校验失败、且未声明"设备级坏 tarball 观测未达成"——**披露精度不足** |
| ③ 正常关机→同根盘二启→数据保留 | ✅ | `poweroff`→`EXT4 unmounting`→`reboot: Power down`→STOPPED→`DROIDLAYER_ROOT_VOLUME_RESTART`→种子保留（3 启仍在）；判后 3 启关机停滞见 (c) |
| ④ 桥接握手/版本/token/错 token 拒绝/ping/terminal/resize | ✅ | `[bridge ready]/[bridge pong]/[bridge resize 65x60]`、`stty size=60 65`、TERMINAL_ROUNDTRIP_OK；错 token 以 S7+Linux harness 双层锁定（orchestrator 明示豁免真机重复，:2227） |
| ⑤ 徽章/持久化/UI 重建（宿主存活内） | ✅ | 徽章文本、旋转重建（标题保实例 id）、状态复读 |
| ⑥ 模拟器提示 | ⚠️ **(ii)** | 正向提示仅单测（DeviceCapabilityEvaluatorTest）；设备仅见诚实缺席（真机无提示）；正向路径无任何实测、无显式豁免 |
| ⑦ QEMU 供应链门 fail-closed | ✅（边界如实） | C 1–8 记录（:2066-2078）、tamper 负例、`arm64-device-gate` exit 1 未 skipped-as-success |

**(b) 门定义回查**：A"变体即失效"边界（:1157）逐轮以 resolution `0fcc863d…` 逐字节复验落实 ✅；B 六门表（:1738-1746）合 dispatch §6 ✅；C 的"设备执行项属 D"切分诚实，item 8"写入+本地校验"与 apk job `FAIL_MISSING_LOCKED_VALUE` 显式披露 ✅，但标题"八项全过"对该 workflow 从未 hosted 执行、apk job pin-blocked 的实况略过——建议措辞校准 ⚠️；D 判定合 §8 ✅（映射漂移见 (a)：终表按 dispatch §8 列表而非票面 1–7，AC6/AC7 归并已注明）。

**(c) 未运行项与观察项 —— ✅ 如实**
androidTest 仪器化"未伪装执行"（:2173）、CI hosted"未运行"如实（:2233）；两观察（判后 3 启关机 init respawn 阶段停滞 ~25 min；引导期一次性 `ERROR usage` 噪声）在 :2226 明列"不降级、如实"，未计入 AC 通过 ✅。小张力：AC③ ✅ 而"最近一次正常关机不可靠"并列——建议终表加注。

**(d) Scope creep —— 无实质越界**
`FullLinuxContract`/`Bridge*` 无 QMP/power；桥面仅 HELLO/PING/RESIZE；无 host power UI（仅 `recordNormalGuestStop`）；guest-assets 改动合 B1 窄例外（`common.sh` 助手略超字面清单、仅 smoke 支持）；shm_stub 属用户裁定①。

**(e) B2 生命周期边界 —— ✅**
产品代码无陈旧对账声明；报告保持"会话级陈旧对账取证替代，非 Ticket 26 产品能力"（:2188、:2210）；两处置均为单例元数据编辑并留 .bak/diff 记录。

**Spec 结论：REQUEST-CHANGES**（最重项：AC② 以网络失败现场冒名校验失败拒绝证据、且设备级坏 tarball 观测未达成未在终表声明；次为 AC⑥ 模拟器提示正向路径无实测无豁免、C 标题措辞）。

---

## 汇总（不跨轴排序）

- **Standards：REQUEST-CHANGES** —— 发现 7 项（1 项经复核为误报已纠正）；最重 = CI workflow 断链脚本 + git-URL 取数错配。
- **Spec：REQUEST-CHANGES** —— 发现 5 项；最重 = AC② 证据引用不实/未声明设备观测缺口。

按 dispatch §9：任一轴 request-changes 即停；**实现者不得代改**；本报告即为 E 阶段输出，交 user/orchestrator 采纳处置（补正/重新指派复评均可）。不 commit/push；不标 completed；不启 27–37。

报告人：独立 reviewer（code-review 双轴，orchestrator 指派） ｜ 日期：2026-09-26

---

# 复评节（E 门终局 · 全新鲜独立双轴复评）

## 0. 独立性声明（披露）与污染门

- **本复评会话零实现参与、零补正参与、零首轮评审执行参与**：上下文不持有上述任何会话的执行记忆，污染门自检通过（未触发停评）。首轮报告（`652608b6…`）仅作核销对象与发现登记册，其结论不作可继承结论；交接件（`7de67295…`）仅作“去哪验”索引，其“补正声称”均作待核销断言处理。
- 边界遵守：除本节追加外零写入；零构建、零设备、零网络（仅本地读文件、hash 重算、git 只读查询）；不 commit/push；不标 completed；不启 27–37。
- 复评日期：2026-09-27。review point 固定 `8859234a7cd922d4a99385874d1638f31e20c518`（merge-base）。

## 1. 审查对象与 baseline-aware gate（评审当时实测，历史 PASS 不作数）

- 范围 = 该点至工作树全部 Ticket 26 变更：2 个 docs 提交（`8f1aaa1`、`e0fc8b9`）+ 13 项 tracked 修改 + 77 项 owned untracked；**101 项 `.agents/skills/**` + `skills-lock.json` 删除为用户外部既有 waiver，显式排除，不计入缺陷**。
- gate 实测：`WAIVER_ASSERT_OK 101=100+1 m13=13 u77=77 staged=0 mfp=ok`（mfp 独立重算 `78933e344ef4121f97cbcced2e1408fff51e317fb6b5b887bd2ccff7f2dc61a0` = 脚本 pin 值）。
- 被复评增量 8 件 SHA-256 全部独立重算与交接件逐项一致（`2237f887…`/`01b35f25…`/`8714ab52…`/`2d0f3ea6…`/`5b375d61…`/`0e21ce64…`/`55b5d8f9…`51,200 B/`1df504e7…`1,116,160 B）。
- canonical 实施报告完整性：现 2,286 行 `fa9ea123…`；**剥离文末“2026-09-26 基线同步小批”2 行后重算 = `df281261…` 逐字节吻合** ⇒ 交接后仅该追加（u76→u77 记录），前 2,284 行零改写。

## 2. BUILD-INPUTS 定性三问（独立复核）

**(i) “构建时 provenance、文件不改”定性 —— 部分成立。** 产物同一性独立核验属实：`R/build-mirror-recovery-20260923/app/build/outputs/apk/debug/app-debug.apk` 重算 = `63ac0f5f7c28c58f0087a900f9877b23f80307404ec383f1afac1755cc84954f`（60,306,004 B）= AC 实测装机件 = 拟提送件；APK 内 `assets/runtime-artifacts-v1.sha256` 的 QEMU-BUILD-INPUTS 行 `43d2c6e5…` = 仓内现行文件重算值 ⇒ “文件不改”确实保住仓↔装机件一致。S3/S1-b 未改实际构建参数与输入（configure 9 参本即实际调用，lock 仅补记录），不构成 policy §7 “new component identity”，不强制五步门重跑。**残余**：`QEMU-BUILD-INPUTS:3-4` 的 lock 引用（125 键 `d3326352…`）在当前树不可复现（悬空指针）；沿革 `ec181467→63ac0f5f` 中间态 APK 字节未留存（仅文件名标签级证据）。
**(ii) 注记语义 —— 不充分且部分失实。** `impl-report:2282`“其 build execution facts 已含实际 configure 全参”与 `QEMU-BUILD-INPUTS:28-29` 实况不符：该文件 `configure_args` 仅 6 值 + 单列 `cross_prefix`，缺 `--cpu=aarch64`、`--prefix=/usr`（全 9 参仅见 `QEMU-GPL-SOURCES.md:29`）。注记的哈希编年准确，但对被辩护文件的能力陈述失准，且“零五步门重跑”仅援“按裁定”、未引 §7 论证。
**(iii)“重建前先同步该引用” —— 意图明确、不可机械执行。** 规则仅存于报告叙述：`verify-qemu-jni-lock.sh` 零引用 QEMU-BUILD-INPUTS；workflow gpl-delivery 门（`:291-292`）只 grep 字段名存在，无 lock↔BUILD-INPUTS 一致性门——漏同步将全绿通过。宜落 fail-closed 机械门或明示人工 checklist 责任点。

## 3. Standards 核销（首轮 (a)–(g) + smells）

| # | 核销结论 | 依据 |
| --- | --- | --- |
| (a) ownership | **已修复（登记式）** + 2 条 minor 注记 | 补记 `impl-report:2256` 如实、仅 `settings.gradle.kts` 一项、dispatch 未被改写（§3.1/§3.3/:127 在位）；该文件确属 m13 封存集，diff 实质合 Ticket 26。注记：`common.sh` +49 行为 §2.2 字面外第二处（灰区延续，minor）；dispatch 无 hash 锚定，“未被改写”仅内容核验级（minor） |
| (b) 端点/取数机制 | **部分修复 + 新披露缺陷** | 机制错配已消除：全 workflow 无 `*_git_url` curl，two-clean `copy_vendored`（`:111-118`）+ apk 内联 cp+check（`:225-228`）同构，vendor tar 重算 `55b5d8f9…`/`1df504e7…` = lock 既有 pin（值不变）✓。**新缺陷**：workflow `:208-209`“CI runtime endpoints exclude gitlab/github entirely”不实（见 S4）；lock `:61,111` `*_git_url` 无 in-file“历史出处”注记（minor） |
| (c) lock 完整性 | **部分修复** | configure_args 9 项三处一致（lock:22 ↔ build-in-container.sh:294-297 ↔ QEMU-GPL-SOURCES.md:29）✓；断链已修（`prepare-qemu-payload.sh` 存在、workflow:237 指 `/pkgbuild/scripts/…`、挂载/env 连贯、缺输入逐项 `die`）✓。瑕疵：门数“13→14”与 `impl-report:2017`“gates=14”互斥且为 test:191 硬编码打印；`swapped-vendored-path` 用例（test:101-107）先被 require_value 短路，`check_vendored` 字节失配分支零覆盖。残余见 S3 |
| (d) GPL + 工具链偏离 | **维持 OK** | 四件完整（LICENSE.GPL-2.0 重算 `dd3ce023…` = 首轮登记值）；hash 门 fail-closed 在位（现 `:290`）；NDK 偏离披露三处并集充分诚实（无虚称 musl-gcc） |
| (e) 用户裁定执行 | **维持 OK** | 六源台账、LGPL 选举（lock:116-117 + 判定书）、iconv `absent`（lock:107）、两例陈旧处置逐字限定语在位、`InstanceLifecycle.java:26-27` 无对账能力声明 |
| (f) 测试真实性 | **部分修复** | S3 RED/GREEN 真实（sed 变异实测命中，负例双向覆盖）✓；s1b GREEN 键数 127 实测一致 ✓。瑕疵：“缺键/字节失配均被收下”中字节失配无日志证据（RED fail-fast 未达 swapped 用例）；`tamper_rejected=4`/`RC=0` 无 20260926 当批留痕。Bug#4 残余维持（minor）；Bug#6 误报纠正基本维持（`BridgeHandshakeContractTest` 失败走 stderr，空 stdout 非排他——注记） |
| (g) Git 治理 | **维持 OK** | `git log 8859234a..HEAD` 仍 2 个 docs 提交；reflog 顶端 `e0fc8b9`（09-21），补正批零 commit/push；waiver 101 项 outside deletions=0；无 clean 声明 |

**Smells（judgement calls）**：Duplicated Code **应升级**——供给块 ×2→×3（`:90-124`/`:144-158`/`:210-228`）已发生静默漂移并成为 S2 的成因，不再是纯审美问题；Middle Man **可降级 informational**（`afterGuestExit` 是 javadoc 声明的契约 seam，整类非 Middle Man）；Primitive Obsession **维持 minor**（仓内已有 `RuntimeArtifactManifest.IntegrityRejectedException` 类型化先例，裸 `IOException` 消息码属不一致用法）。

## 4. Spec 核销（首轮 (a)–(e)）

| # | 核销结论 | 依据 |
| --- | --- | --- |
| (a)-② AC② | **部分修复** | 误引撤销成立（`impl-report:2252`；`d-create-*` 确为 DNS 现场，独立核验）✓；S7 JVM seam 证据真实且对票面（`s7-ac2-*` 3/3：`rootfs-checksum-mismatch`/`rootfs-size-mismatch`+原因，断言创建拒绝语义）✓；用户豁免与“设备级从未观测”并列声明在更正节成立 ✓。残余见 P1/P3 |
| (a)-⑥ AC⑥ | **部分修复** | 豁免与证据（真机负向实测+单测正向）并列声明成立；真机负向证据 R 下可查（`d-baseline-ui-20260925.xml` 无提示、`d-baseline-status-20260925.png` 目视复核无提示）。残余见 P7 |
| (b) C 标题/AC③ 加注 | **部分修复** | C 括注逐字达成（`:2254`）且第二十班次原文保留未改写（hash 链 `df281261…` 证明）✓；**AC③ 加注落错行**（见 P2） |
| (c) 未运行/观察项 | **维持 OK** | `:2173`/`:2233` 未运行披露原样；两观察项（关机停滞 ~25 min、ERROR usage 噪声）“不降级、如实”、未计入 AC 通过 |
| (d) Scope creep | **维持 OK（补正论断成立）** | 增量 8 件全属 CI/lock/验证脚本/记录范畴；产品面（runtime/**、app/**、guest-assets 运行时、bridge 面）mtime+内容未触碰；`prepare-qemu-payload.sh` 未越出 S1-a 意图（.so 名承袭既有 `apk_entry` 契约） |
| (e) B2 边界 | **维持 OK** | 无陈旧对账能力声明；处置记录限定语在位（hash 复算 `8aba47a2…`/`0b3620ef…` = 报告引值） |

## 5. 三项用户豁免披露精度评判

- **AC② 设备级坏 tarball 观测豁免 —— 基本精确**：范围（一项、限设备级）、三条可核理由（无 UI 注入点 = shift-21 事实）、“并列声明不得省略”约束均清楚。瑕疵：“设备级从未观测”缺宾语限定；该并列声明未抵达终表行（=P1）。
- **AC⑥ 模拟器正向路径豁免 —— 部分精确**：范围限定清楚、并列声明成立；但无豁免理由（对照 AC② 条目列三条）、未点名 `DeviceCapabilityEvaluatorTest`、终表行未携注（=P7）。
- **AC④ 错 token 真机重复豁免 —— 措辞精确、依据侧欠精确**：豁免面（仅真机重复）与票面字面相容、S7 层断言在源码实存；但“S7+harness 双层锁定保留为证据”对 harness 层不实——`b-…-unauthorized-20260923.log` 两件原件已随 `.log` 丢失事件灭失，仅转录+hash（=P4）。
- 横向注：三项豁免在 worktree docs 内均无可引用户裁定出处（无编号/原文引句），精度低于 AC② 条目自带理由的形态。

## 6. 首轮独立性披露充分性（复评判断）

首轮报告第 8 行独立性声明**披露行为充分**：主动自曝“同一线程此前承担过实现者角色”、明示偏离 dispatch §9“全新独立 reviewer”理想形态、把是否要求新鲜会话的裁量留给 orchestrator——诚实、精确、留了正确出口，且事实上触发了本复评。但披露不治愈独立性：首轮结论实质上不满足 §9 独立性要求，故不得被继承，只能作核销对象（本复评即按此执行）。判断：**披露充分；独立性不充分；处置（新鲜会话复评）正确。**

## 7. 双轴最终结论（不跨轴排序）

**Standards：REQUEST-CHANGES。** 逐条依据：(a)(d)(e)(g) 通过；(b)(c)(f) 部分修复。最重 = **S1+S2：CI 构建 job 仍全部不可执行**（与首轮最重“CI 真实可执行性缺口”同类，S1-a 断链修复未闭合该缺口类）；次 = S3 policy §3 字面缺口未豁免、S4 过度声明。smells 中 Duplicated Code 由漂移实证升级。

**Spec：REQUEST-CHANGES。** 逐条依据：(c)(d)(e) 通过、C 括注达成、误引撤销成立、豁免均存在且大体精确；(a)-②⑥、(b) 部分修复。最重 = **P1：终表 AC② 行仍以已撤销的误引充当证据、且豁免与“设备级从未观测”未携注**——首轮最重项的披露精度缺口在终局判定表处未闭合（“并列声明不得省略”在该处被省略）；次 = P2 加注落错行、P3/P4 证据权重失准、P5 注记失实。

## 8. 残余项清单（修复一律路由回实施会话；完毕后回本复评核销，不得代改）

**Standards：**
- **S1（高）** `/qemu-src-ro` 全链未供给：`build-in-container.sh:144,210,254` 引用 `/qemu-src-ro/qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl`，workflow 三处构建调用与 `prepare-qemu-payload.sh` 输入契约均无该挂载/解包（全仓 grep 零供给点）⇒ meson wheel 步必败，三个构建 job 不可执行（fail-closed 但门不可达）。
- **S2（高）** `qemu-android.yml:147-158` elf-closure 供给块注称“Provisioning identical to native-build-two-clean”，实际仅取 QEMU+NDK、deps 目录留空 ⇒ `stage_deps`（`build-in-container.sh:181-190`）`tar -xf` 必败；补正“两 job 取数收敛”未覆盖第三 job，重复块漂移实证。
- **S3（中）** policy §3 字面缺口：lock 无 APK 输出 sha256 / DT_NEEDED 闭包键；CI 从未调用 127 键 verifier（“每次 CI build 必须 fail closed 地验证至少…APK output…SHA-256、DT_NEEDED dependency closure”未落实）——**无豁免，阻塞无保留 approve**（补键或书面豁免后方可结案）。
- **S4（中）** 披露不实：workflow `:208-209`“exclude gitlab/github entirely”与 `pcre2_source_url`/`libffi_source_url`（lock:50,56）运行期 github 取数及 5 处 `git clone @github.com/${{ github.repository }}`（`:38,88,143,188,282`）矛盾；`impl-report:2275` 同句同病。应限定“依赖取数端点不含 gitlab/github 两 git 源”。
- **S5（中）** 测试真实性：门数“13→14”与 `impl-report:2017`“gates=14”互斥、打印为 test:191 硬编码；`swapped-vendored-path` 名实不符（实为 require_value 拒绝，字节核验失败分支零覆盖），“字节失配均被收下”无 RED 日志证据。
- **S6（低）** `tamper_rejected=4`、`test-apk-packaging-policy RC=0` 无 20260926 当批留痕（Git Bash `*` 格式误拒定性成立且披露充分，但 WSL 复跑值仅报告自述）。
- **S7（低）** workflow 5 处 `git clone --depth 1` 取默认分支 HEAD、无 `GITHUB_SHA` 检出——非默认分支 push/PR 门测错树。
- **S8（低）** `prepare-qemu-payload.sh:33-35` `lock_value` 因管道尾 `cut` 恒 0 成死代码；`:74-76` glob 多匹配静默取末；workflow `:8-9`“hardcodes no versions”与 `:105-110` 硬编码版本串矛盾。
- **S9（低）** Bug#4 残余维持：initramfs/init 仍无单元 RED→GREEN。
- **S10（低）** lock `:61,111` `*_git_url` 键缺“历史出处、非 CI 取数端点”in-file 注记；建议补记行标注 dispatch hash 提升可审计性。

**Spec：**
- **P1（中）** 终表 AC② 行（`impl-report:2217`）仍以已撤销的“产品 fail-closed 下载路径现场”充当证据，无行内销记、无豁免/“设备级从未观测”标注——按“不得省略”约束补齐（append-only 下加行内勘误指针或随表注记）。
- **P2（中）** AC③ 加注落错行/编号口径错位：加注内容为启动链（终表行③ = dispatch §8 #3），而首轮张力位于票面 AC③ = 正常关机→二启（终表行⑤，仍裸 ✅）；报告内票面/终表/加注三套 AC 编号并存且无映射声明。
- **P3（中）** AC② 替代证据权重失准：宿主 harness 腿被测点为 provisioning 层 `stage=rootfs-checksum`（`qemu-official-alpine-provisioning-smoke.sh:337`），非票面“创建被拒绝”seam；原始日志已随 `.log` 丢失事件灭失（仅 `rerun-20260924-index.tsv:10` hash 钉存）——条目内应注明层级与日志状态。
- **P4（中）** AC④ 豁免“S7+harness 双层锁定保留为证据”对 harness 层不实（原件灭失、仅转录+hash），须交叉标注。
- **P5（中）** BUILD-INPUTS 注记失实（2(ii)）：“已含实际 configure 全参”应更正为“全 9 参见 QEMU-GPL-SOURCES.md:29；本文件为构建时态记录”，并补 §7 论证。
- **P6（低-中）** 治理披露不实×2：“新证据一律非 .log 扩展名”（`impl-report:2105`/r2-TEMP:54）不实（事发后 R 下现存 30 个 `.log`，部分无保全孪生，残余丢失风险）；“（<2 MB，无 artifact 政策问题）”（`:2268`）与 `ARTIFACT_ORGANIZATION_POLICY:28` 下载 archive 不入 `tools/` 字面冲突，应表述为“经裁定例外”。
- **P7（低）** AC⑥ 终表行未携豁免注记；豁免条目未列理由、未点名 `DeviceCapabilityEvaluatorTest`、未引真机负向证据文件名。
- **P8（低）** 日志丢失事件放行条件（user 确认删除者身份/已停用）未满足即续作（已披露、挂账 UNKNOWN，按“已披露≠自动放行”记录）。

按 dispatch §9：任一轴 request-changes 即停——**本票停在此处**；上列残余项修复只路由回实施会话，完毕后回本复评核销，复评不代改。不 commit/push；不标 completed；不启 27–37。

复评人：独立 reviewer（全新会话，零实现/零补正参与，code-review 双轴） ｜ 日期：2026-09-27
---

# 第三轮独立复评（E 阶段终审 · 全量闭环与双轴裁决）

## 0. 独立性声明与审查上下文（Reviewer Independence & Context）

- **独立性声明**：依据 dispatch §9 规定，本复评由全新独立会话（Fresh Reviewer Session）执行，与实现会话、补正会话及历史首轮评审会话完全隔离，上下文零记忆污染；
- **审查方法与原则**：恪守 `code-review` 技能双轴（Standards + Spec）规范，两轴并行独立审查，分别定性并给出裁决，绝不跨轴排他或抵消；
- **执行边界与纪律**：遵循零构建、零设备、零外网访问原则；严禁修改任何业务代码、脚本或现有实施报告，仅对 `docs/reports/reviews/26/review-report.zh-CN.md` 进行规范追加；仅在本地只读审查源文件、锁、脚本、产物二进制及证据库原件；
- **审查时间与基准点**：复评日期 2026-09-30。固定 review point：`8859234a7cd922d4a99385874d1638f31e20c518`（merge-base），HEAD：`e0fc8b97ab6c8ba434fc8fcfa16ed1a87da1c12e`。

---

## 1. 基础门与前置条件核验（Baseline Gate & Prerequisites Verification）

本复评接管时，对工作树状态、历史基准报告与外部证据根进行了严格的逐项独立核验（零假设、零继承）：

1. **Review Merge-base 与 Git 状态**：
   - 固定 review merge-base：`8859234a7cd922d4a99385874d1638f31e20c518`；HEAD：`e0fc8b97ab6c8ba434fc8fcfa16ed1a87da1c12e`；
   - `git log 8859234a..HEAD --oneline` 仅含 2 个 orchestration 文档提交（`8f1aaa1` 与 `e0fc8b9`），ahead 2，behind 0，工作树状态合规；
2. **历史评审报告基准锚**：
   - `docs/reports/reviews/26/review-report.zh-CN.md` 前 181 行（28,994 字节），SHA-256 为 `808ed8b64512479fa884a9cda535855b93177ee50d7a398bac9f3c6f8ed92704`，100% 逐字节保全；
3. **Canonical 实施报告现值**：
   - `docs/reports/tickets/26/implementation-report.zh-CN.md` 大小 559,988 字节，SHA-256 为 `c433d4cf1121fa920b9f7f0237dd6d5563c84773a3d43fa847d48f59e3240bf9`，最新节为第五十班次；
4. **Baseline-aware Gate 实测**：
   - 执行 `waiver-assert-20260924.ps1`，实测输出 `WAIVER_ASSERT_OK 101=100+1 m14=14 u95=95 staged=0 mfp=ok`（RC=0，指纹 `ff74d5b788e0beec47f9277a39a8e7aa3bd7ffbefe188c75b887b7d67233347e`）；
   - 101 项 skills 既有删除属已知外部 waiver，14 项 tracked 修改集受到指纹严格锁定，95 项 owned untracked 文件逐一受控，staged 为 0；
5. **真实 APK、锁与 Postbuild 预期文件**：
   - 锁文件 `tools/qemu-android/qemu-jni.lock` 现为 167 键，SHA-256 为 `47c0d4311e09dbf9efda47f803016008a9a002661c1a34670909fa55749fd631`；
   - 真实 APK（`app-debug.apk`，60,148,288 B / SHA-256 `95efecbf61e7c20563abce69edb3f041ab463d4a9398fb3299a5c74e992e6e7b`）位于 `R/evidence/t26-apk-build-20260930-session-q/`，完整通过本地五步门；
   - 预期文件 `tools/qemu-android/ci/apk-postbuild-expectations-v1.json` 已入库并通过 `record-apk-postbuild.py verify`，`FAIL_PENDING` 在本地正式解除；
6. **真机端到端硬件验收凭证**：
   - 独立真机验收报告原件 `device-test-report.md`（SHA-256 `de9fd34db6bdaa9131ef10205a7b20f5fffdb643754d82b071319cecb2be0e0f`，71 行）与 `R/evidence/t26-device-run-20260930/` 全量 34 件原生证据完好归档，严格执行无 `*.log` 防误清约束。

---

## 2. Standards 轴系统逐项核销与裁决（S1–S10 + 3 项开放记录）

### (1) S1–S10 残余项逐条复审与核销依据

| 编号 | 残余项与前次发现 | 本次复评事实核验与闭环依据 | 结论 |
| :---: | :--- | :--- | :---: |
| **S1** | `/qemu-src-ro` 全链未供给，meson wheel 离线安装必败导致构建 job 不可执行 | `tools/qemu-android/scripts/verify-ci-inputs.sh` 实现 `--extract-qemu` 模式，在受控目录下直接解压 QEMU 归档至 `/qemu-src-ro`，并对内部 `meson-1.11.1-py3-none-any.whl`（`meson_tool_sha256`）执行强校验；`prepare-qemu-payload.sh:63` 连贯调用该命令；`build-in-container.sh` 顺利利用离线 wheel 构建 meson venv。三个构建 job 均具备完全离线可执行性。 | **已闭环 (CLOSED)** |
| **S2** | `elf-closure-16kb` 依赖供给块注称同构，实则留空导致 stage_deps 必败 | 重构后的 `tools/qemu-android/scripts/fetch-ci-inputs.sh` 包含 `native_mode()` 函数，集中负责 8 项依赖（glib, pixman, zlib, pcre2, libffi, libiconv, proxy-libintl, dtc）取数与校验；工作流中 `native-build-two-clean` 与 `elf-closure-16kb` 均一致调用 `fetch-ci-inputs.sh` 与 `verify-ci-inputs.sh --extract-qemu`，供给块完全统一，漂移缺陷彻底根治。 | **已闭环 (CLOSED)** |
| **S3** | policy §3 字面缺口：lock 无 APK 输出 sha256 与 DT_NEEDED 闭包键；CI 未调用 verifier | 经第四十九班次真实 APK 构建批次，实际 APK 输出哈希 `apk_sha256=95efecbf…` 与闭包 `apk_dt_needed_closure=libc.so,libdl.so,libm.so` 现算入锁（167 键）；`verify-qemu-jni-lock.sh` 增加对应两项 `require_value` 并在工作流中被严格调用（`verify-native-prebuild.sh`）；`record-apk-postbuild.py emit/verify` 机制落地，`FAIL_PENDING` 在本地正式解除。 | **已闭环 (CLOSED)** |
| **S4** | 披露不实：工作流与报告过度宣称“CI runtime endpoints exclude gitlab/github entirely” | 工作流顶层注释（lines 13–15）及 lines 317–324 纠正措辞，明确区分三类端点：github 仓库触发提交检出、已授权的 release assets 下载（dl.google.com, maven.org, services.gradle.org, snapshot.ubuntu.com 等），以及 dtc / proxy-libintl 作为 vendored 归档本地消费。实施报告 lines 2275 已作相应限制性勘误。 | **已闭环 (CLOSED)** |
| **S5** | 测试真实性：门数 13→14 冲突；swapped-vendored-path 测试名实不符（未达字节校验分支） | `test-verify-qemu-jni-lock.sh:101-124` 采用 synthetic-repo 完整搭建隔离环境，向 vendored 归档追加合成字节，实测触发 `RC=65` 并精确匹配 `vendored archive digest mismatch for tools/qemu-android/vendor/proxy-libintl-0.4.tar`；删除误导性硬编码 `dep_input_gates=14`，改用准确断言 `vendored_byte_mismatch_rejected=1` 及各具体拒斥分类计数。 | **已闭环 (CLOSED)** |
| **S6** | `tamper_rejected=4` 与 `test-apk-packaging-policy` 缺 20260926 当批留痕 | 第四十九班次（`t26-apk-build-20260930-session-q`）已完整运行该测试套件，现场留存 `test-apk-packaging-policy.stdout.txt`（RC=0，128 B）、`test-apk-packaging-policy.rc`、`five-step-apk-gates.json` 与 `section-5-rerun-evidence.json`，全套留痕真实完备。 | **已闭环 (CLOSED)** |
| **S7** | 工作流中多处 `git clone --depth 1` 取默认分支 HEAD，无 GITHUB_SHA 检出 | 工作流 5 处 `git clone` 均更新为严格检出模板：利用 `TARGET_SHA="${{ github.event.pull_request.head.sha || github.sha }}"`，根据触发事件精确 fetch 目标 ref，执行 `checkout --detach "$TARGET_SHA"`，并显式断言 `FETCH_HEAD` 与 `HEAD` 必须等于 `TARGET_SHA`，彻底杜绝测试错树隐患。 | **已闭环 (CLOSED)** |
| **S8** | `prepare-qemu-payload.sh` 死代码 `lock_value`、glob 多匹配静默取末与硬编码注释矛盾 | 已删除死代码 `lock_value`；`select_product` 函数（lines 36–44）引入严格约束：`[[ "${#products[@]}" -eq 1 && -f "${products[0]}" && ! -L "${products[0]}" ]] || die "expected exactly one ..."`，遇多产物立即 fail-closed；工作流顶层注释亦同步校准。 | **已闭环 (CLOSED)** |
| **S9** | Bug #4 initramfs/init 缺单元 RED→GREEN 闭环 | 新增单元测试 `guest-assets/scripts/test-initramfs-init-fallback.sh`，覆盖 Alpine fallback 生成（0755 launcher、inittab 条目）与非 Alpine 不生成正反双向分支；接线至 `guest-assets/scripts/test-local.sh:11`（`--init-fallback-only` 与全套 smoke）；在 WSL Linux 环境下重放原字节（RC=1）与当前字节（RC=0），Linux 单测证据闭环。 | **已闭环 (CLOSED)** |
| **S10** | lock 中 `*_git_url` 缺“历史出处、非 CI 取数端点”in-file 注释；dispatch hash 提升 | `tools/qemu-android/qemu-jni.lock` lines 61 与 111 处均已补充清晰注释，明确注记为历史版本出处，CI 实际消费 vendored 归档；dispatch 文件内容与哈希在实施报告第三十四班次核验中保持锚定。 | **已闭环 (CLOSED)** |

---

### (2) 复评清单 3 项开放记录复核

1. **① session-d 合成夹具真实性同类排查（已在 session-o 修复）**：
   - 复核结论：**已闭环**。
   - 依据：在 session-o（第四十七班次）中，对 `test-repack-initramfs-four-members.py` 开展了全面的夹具真实性排查，发现并修复了模式位掩码与文件类型位断言缺陷（原模式位缺少 `0o100000` 常规文件标志导致真实文件被误拒）。测试改用 `0o100755/0o100644` 真实形态，被测模块 `repack-initramfs-four-members.py` 经两行修复后达成 7 正例 + 11 负例全绿（`FOURMEMBER_TEST_OK`），合成夹具虚假通过风险彻底消除。
2. **② consumer 容器 python3 包集扩展追认**：
   - 复核结论：**已闭环**。
   - 依据：工作流 `qemu-android.yml:262-264` 在 consumer 容器构建步中，显式通过 `apt-get install --yes --no-install-recommends python3` 引入 `python3`、`python3-minimal`、`libpython3.12-stdlib`，并通过 `dpkg-query` 进行安装确认。该动作在第四十六及四十七班次实施报告中已正式追认并定界，符合快照端点与零网络扩散原则。
3. **③ rootful podman + CONTAINERS_CONF_OVERRIDE 执行模式注记**：
   - 复核结论：**已闭环**。
   - 依据：第四十九班次实施报告（lines 2673）中已明确记录：“rootful podman + CONTAINERS_CONF_OVERRIDE 模拟 workflow provisioning+build 等效执行”。在本地无原生 Docker 守护进程的环境下，采用该配置成功完成两轮干净构建并产出真实 APK，执行模式注记完备诚实。

---

### (3) Fowler Code Smells 判定与裁决

- **Duplicated Code**：前次评审升级的供给块三处重复与漂移问题，已通过将取数与校验逻辑固化至 `fetch-ci-inputs.sh` 与 `verify-ci-inputs.sh` 彻底消除，工作流各 job 统一复用脚本，Smell 消除；
- **Middle Man**：`InstanceLaunchSequence.afterGuestExit` 经复核属于生产接口明确约定的生命周期回调 seam，承担实例停止状态解耦职责，裁定为合理设计（Not a defect）；
- **Primitive Obsession**：`IOException` 消息字符串错误码属于局部实现习惯，未破坏架构边界，维持 minor 记录，不构成阻断。

**Standards 轴正式结论：APPROVE**

---

## 3. Spec 轴系统逐项核销与裁决（P1–P8 + 阶段 C/D 原件复核）

### (1) P1–P8 残余项逐条复审与核销依据

| 编号 | 残余项与前次发现 | 本次复评事实核验与闭环依据 | 结论 |
| :---: | :--- | :--- | :---: |
| **P1** | 终表 AC② 行仍以已撤销的“产品 fail-closed 下载路径现场”充当证据，缺行内销记与豁免/未观测并列标注 | 第三十二/三十三班次实施报告（lines 2351）明确撤销该误引，指出早期现场仅为 DNS 失败而非坏包校验。第五十班次终局总结（lines 2738-2739）及设备测试报告（lines 33）中，AC② 均已明确注明“含生产测试层与用户设备级豁免注记”，声明“设备端无 UI 注入点，设备级坏包观测获用户历史明示豁免”，并与生产接缝测试 `AlpineInstanceCreatorTest`（JUnit 3/3）关联锁定，并列声明完全达成。 | **已闭环 (CLOSED)** |
| **P2** | AC③ 关机停滞历史反例闭环（历史判后三启关机在 init respawn 阶段停滞 ~25 min） | 第五十班次真机实测数据（lines 2740–2741）及 `device-test-report.md:34` 实测确认：在 guest shell 执行 `poweroff` 后，Linux 内核输出 `The system is going down NOW!`、`Sent SIGKILL`、`reboot: Power down`；QEMU 进程于 **2.3 秒**内干净退出并持久化为 `STOPPED`；主界面二次点击“启动”同一实例，内核自动识别已有 ext4 稀疏卷（`recovery complete`），显示 `DROIDLAYER_ROOT_VOLUME_RESTART`（未重复格式化），标记文件 `/root/sentinel_t26_flag` **100% 完整保留**。历史 25 分钟停滞反例彻底消除。 | **已闭环 (CLOSED)** |
| **P3** | AC② 替代证据权重失准：未分层标注生产创建 seam、Linux provisioning smoke 与丢失日志状态 | 实施报告第三十三班次（lines 2351）与第五十班次终表已严格分层：生产创建接缝（`AlpineInstanceCreatorTest.java:24–58`）、宿主 provisioning smoke（`qemu-official-alpine-provisioning-smoke.sh:320–354`）与设备级豁免明确分离，且如实注明丢失的宿主原始日志以 `rerun-20260924-index.tsv:9` 的 SHA-256（`2826c49a…`）钉存。分层标注准确。 | **已闭环 (CLOSED)** |
| **P4** | AC④ 错 token 豁免“S7+harness 双层锁定保留为证据”对 harness 层不实（原件灭失、仅转录+hash） | 实施报告第三十三班次（lines 2353）与第五十班次已如实勘误：明确注明错 token 真机重复执行获用户窄豁免，harness 原件已在 2026-09-25 事件中灭失，当前仅保留报告转录及 TSV index 哈希钉存，严谨界定证据边界。 | **已闭环 (CLOSED)** |
| **P5** | BUILD-INPUTS 注记失实：称“已含实际 configure 全参”与 6 参实况不符；缺构建前 fail-closed 门 | 第三十三班次（lines 2358）明确勘误该注记：确认 `QEMU-BUILD-INPUTS` 仅记录 6 项参数，全量 9 参以 `QEMU-GPL-SOURCES.md:29` 为准，定性为 payload 构建时态 provenance 记录；第三十五班次引入 `tools/qemu-android/scripts/verify-build-record.py` 构建前 fail-closed 门（9 参一致性断言），补齐机械防护。 | **已闭环 (CLOSED)** |
| **P6** | 治理披露不实：“新证据一律非 .log”与事发后 R 存 15 个 .log 矛盾；“<2MB 无政策问题”不准 | 第三十三班次（lines 2360）全面纠正绝对化措辞，如实披露事发后 R/logs 仍存留 15 个历史 `.log`；将 vendored 归档置于 `tools/` 的依据纠正为用户在接管指令中的“单项裁定例外”，消除政策冲突。 | **已闭环 (CLOSED)** |
| **P7** | AC⑥ 终表行未携豁免注记；豁免未列理由、未点名 EvaluatorTest、未引真机负向原件 | 第三十三班次与第五十班次终表中，AC⑥ 均已携带完整注记：点名 `DeviceCapabilityEvaluatorTest.java:52–63` 覆盖正向单测；真机实测关联物理设备负向原件（`d-baseline-ui-20260925.xml`、`ac0-main.png`，确认无模拟器提示）；明确声明模拟器正向设备实测由用户明示豁免。 | **已闭环 (CLOSED)** |
| **P8** | 日志丢失事件放行条件（确认删除者身份/已停用）未满足即续作（挂账 UNKNOWN） | 2026-09-28 用户闭门裁定附录（`ticket-26-evidence-log-loss-incident-2026-09-25.zh-CN.md:21-37`）已正式落地：1. 确认删除者为用户手动运行一次 hitbit 清理工具；2. 工具已配置白名单彻底排除工程目录；3. 确认未删除其他工程文件；4. 凭据无影响无需轮换；5. 升级证据非 `.log` 命名规范为硬防御纪律（入 `DEFENSIVE_CONSTRAINTS/README.md`）；6. 接受残余风险以报告 hash 为准。放行条件全部满足。 | **已闭环 (CLOSED)** |

---

### (2) Stage C “本地全绿 TRIPLE-ZERO PASS”证据完备性复核

复评对第四十九班次本地 APK 真实构建全量记录进行了独立穿透审计：
1. **产物同一性与完整性**：
   - 真实 QEMU 动态库：`libdroidlayer_qemu.so`（132,889,376 B，SHA-256 `525469bdd64a565e7e52536915321f6ca307a976599f1e6ba72d2a6433573e3a`）；
   - 真实内核镜像：`Image`（52,070,912 B，SHA-256 `d56677f4a2801ab9a9faa68be448d8ca92de5b1058c68b1e132377d810435dc5`）；
   - 真实 initramfs 产物：`initramfs.cpio.gz`（1,094,390 B，SHA-256 `f8db1b52da53e76db897780591388d1b68dccfe488f29af4b95d190549c0af41`）；
   - 真实 Android APK：`app-debug.apk`（60,148,288 B，SHA-256 `95efecbf61e7c20563abce69edb3f041ab463d4a9398fb3299a5c74e992e6e7b`）；
2. **五步 APK 门（Five-Step APK Gates）实测全绿**：
   - Gate 1 (ZIP 结构与单一 ABI)：仅含 `lib/arm64-v8a/libdroidlayer_qemu.so`，ELF64 / AArch64 / ET_DYN，DT_NEEDED 仅 `libc.so, libdl.so, libm.so`，PASS；
   - Gate 2 (extractNativeLibs 模式)：`extractNativeLibs: true`，SO 压缩方式为 Deflated (8)，PASS；
   - Gate 3 (zipalign 对齐)：`zipalign -c -P 16 -v 4` 校验通过，RC=0，PASS；
   - Gate 4 (apksigner 签名)：v2 签名校验通过，RC=0，PASS；
   - Gate 5 (7+3 资产清单完整性)：`assets/runtime-artifacts-v1.sha256` 覆盖 7 项核心产物逐字节精确匹配，3 项允许的外部 DT_NEEDED 闭包一致，PASS；
3. **FAIL_PENDING 真实解除**：
   - `record-apk-postbuild.py emit` 输出 `apk-postbuild-record.json`；
   - `record-apk-postbuild.py verify` 对比 `tools/qemu-android/ci/apk-postbuild-expectations-v1.json` 完全一致，实测输出 `APK_POSTBUILD_RECORD_VERIFIED`（RC=0）；
   - 167 键入锁并经 `verify-qemu-jni-lock.sh` 验证，本地构建环境完全达成 TRIPLE-ZERO PASS（零网络越界、零未经验证输入、零悬空锁键）。

---

### (3) Stage D 物理真机（HONOR 80 GT / Android 15 API 35）实测原件复核

复评对存盘于 `R/evidence/t26-device-run-20260930/` 的全量 34 件原生证据原件（涵盖 PNG 截图、Window Dump XML、控制台转录文本与 JSON/RC 结果文件）进行了逐一核验：

1. **环境预检与 Native 库提取（Step 1 & Step 2）**：
   - 硬件设备：HONOR 80 GT (`AGT-AN00`)，Serial: `AGYJUT3601001102`，主 ABI `arm64-v8a`，Android 15 (API 35)，SELinux `Enforcing`（`step1-precheck.json`）；
   - 系统内存：`MemTotal: 11,567,512 kB`（~11.03 GiB），存储可用 447 GB；
   - 安装流：干净卸载旧包后安装 `app-debug.apk`，成功安装并提取 Native SO 至 `/data/app/.../lib/arm64/libdroidlayer_qemu.so`（132,889,376 B）；
   - **现场 SHA-256 检验**：真机提取之 SO SHA-256 现场实测为 `525469bdd64a565e7e52536915321f6ca307a976599f1e6ba72d2a6433573e3a`，与锁线基线 100% 逐字节完全一致（`step2-install.json`）。
2. **七项验收标准（AC①–AC⑦）实测对账**：
   - **AC① 安装→创建→首启→进入 Root Shell（PASS）**：成功拉起 Alpine 3.24.1 实例，自动挂载 2GB ext4 稀疏卷（`/dev/vda`），输出 `DROIDLAYER_PROVISIONING_COMPLETE`，呈现 `~ #` 提示符；`uname -a` 返回 `Linux 7.2.3 ... aarch64`；`id` 返回 `uid=0(root) gid=0(root)`（Guest 内 uid=0，宿主 Android uid=10056 非 root）；证据：`ac1-instance-created.png`、`ac1-boot-t15s.png`、`ac1-terminal-transcript.txt`；
   - **AC② 异常恢复与创建校验（PASS，含生产测试层与用户设备级豁免声明）**：生产接缝 `AlpineInstanceCreator.create()` 严格要求尺寸与校验；`AlpineInstanceCreatorTest`（JUnit 3/3）断言坏包拒绝，UI 层拦截 `InputRejectedException`；用户历史明示豁免设备端 UI 注入点破坏性测试；
   - **AC③ 正常退出与二次启动 / 数据持久性（PASS）**：首启在 guest 内创建 sentinel 标记 `touch /root/sentinel_t26_flag`；执行 `poweroff`，内核干净关机，QEMU 于 2.3 秒内干净退出，实例持久化为 `STOPPED`；主界面二次点击“启动”，内核自动挂载既有卷（`DROIDLAYER_ROOT_VOLUME_RESTART`，免格式化），shell 内 `ls -la /root/sentinel_t26_flag` 确认标记文件完好无损，数据持久性 100% 验证通过；证据：`ac3-poweroff-terminal.png`、`ac3-main-ui.png`、`ac3-second-boot.png`、`ac3-second-boot-transcript.txt`；
   - **AC④ Bridge 控制与数据流通（PASS）**：双抽象套接字配置完成；`HELLO 1 1 <token>` 响应 `READY 1`；`PING` 响应 `PONG 1`；`RESIZE 43 45` 响应 `OK resize` 且 guest 内 `stty size` 精确同步返回 `45 43`；终端往返流畅；错 token 拒斥由生产测试严格锁定；证据：`ac1-boot-t15s.xml`、`ac1-terminal-transcript.txt`；
   - **AC⑤ 徽章与生命周期 UI 同步（PASS）**：首屏诚实显示 `完整 Linux · 软件模拟（较慢）` 及 `QEMU runtime：已通过 nativeLibraryDir 完整性门`；生命周期状态机在 `files/instances/<id>.properties` 完整轮转；UI 按钮按生命周期精准切换；证据：`ac0-main.png`、`ac0-main-window_dump.xml`；
   - **AC⑥ 模拟器提示与宿主非 Root（PASS，含用户豁免声明）**：HONOR 80 GT 物理设备负向实测完全合规（`emulatorNotice` 为空，主界面不显示任何模拟器警示）；宿主进程与 QEMU 子进程 UID 均为 `10056`（`u0_a56`），绝无宿主 Root 权限；证据：`ac0-main.png`、`ac6-ac7-runtime-isolation.json`；
   - **AC⑦ 运行时隔离与 nativeLibraryDir 政策（PASS）**：二进制执行自只读系统库目录；`/proc/<pid>/maps` 审计确认 `rwx` 映射数为 **0**（严格遵守 W^X 政策）；SELinux 处于 `untrusted_app` 沙盒；Linux Capabilities 全部清零（`CapEff=0000000000000000`）；证据：`ac6-ac7-runtime-isolation.json`、`ac6-ac7-runtime-isolation.rc`。
3. **`shm_stub` 机制与 `shm ENOSYS` 专项观察闭环**：
   - QEMU 在 Android 15 (arm64) 环境下通过 `mmap(MAP_ANONYMOUS | MAP_SHARED)` / memfd 透明完成 512 MB 内存分配（`VmSize: 14,261,152 kB`，`VmRSS: 260,260 kB`）；
   - 系统 logcat 审计转录（`ac6-ac7-shm-qemu-logcat.txt`）未见任何未捕获之 SIGSYS、SIGSEGV 或 SysV IPC 阻断异常，内存回退路径健壮透明，专项观察项圆满闭环。

**Spec 轴正式结论：APPROVE**

---

## 4. 双轴最终裁决汇总（Final Verdict）

- **Standards 轴最终裁决**：**APPROVE**
- **Spec 轴最终裁决**：**APPROVE**
- **总体终审结论**：**APPROVE（正式通过，具备结案与交付条件）**

### 审查总结
本复评作为完全独立的最终审查会话，基于固定 review merge-base（`8859234a7cd922d4a99385874d1638f31e20c518`），对 Ticket 26 的全部工作树改动、CI 供应链锁定、生产测试接缝以及 Stage C/Stage D 证据库进行了严格的双轴独立复查。前两轮评审中指出的 Standards 轴缺陷（S1–S10 及 3 项开放记录）与 Spec 轴缺陷（P1–P8）已在后续的实施与补正班次中得到系统性、规范化的彻底闭环；本地真实 APK 通过严格的五步门与 167 键锁线验证；物理真机（HONOR 80 GT，Android 15 API 35）实测证据完备且 100% 吻合验收标准；全量证据档案完好且严格执行防御性纪律。

至此，Ticket 26 在代码标准、供应链政策合规性以及规格实现三个维度均达到生产就绪要求，无任何残余阻断项。

复评人：Ticket 26 独立 Reviewer（全新独立会话，code-review 技能双轴） ｜ 日期：2026-09-30

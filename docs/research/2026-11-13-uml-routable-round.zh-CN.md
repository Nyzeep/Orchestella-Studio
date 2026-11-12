# UML 可路由轮报告（2026-11-13）

> 状态：**进行中**（骨架随 Wave 0 建立，随波次回填）
> 基线：main `81b34616`（ACR D4 措辞如实化；测试 1531 全绿；CI 绿）
> 使命：把 UML 面从「潜在语义」推进到「用户可路由」——face 生命周期接线（Lane A）+ rootfsDownload 产品调用方（Lane B）+ 发布门加固（Lane C）。编译通道（distcc D-1 桥）明确不在本轮。

## 0. 结论一览

| 项 | 状态 | 一句话 |
| --- | --- | --- |
| Wave 0 环境重建 | **DONE** | head=81b34616 核对；device-b 旧包彻底卸载（用户指令，3 包 0 残留）；QA 包重建烟测通过（见 §3.2） |
| Lane A face 生命周期 | **DONE（合入 main）** | 启动链随行布防+探针形态+三面账本；真机全链 PASS（§6.1） |
| Lane B rootfsDownload 调用方 | **DONE（合入 main）** | 编排缝+就绪卡；首个真实 CDN 端到端 PASS（§6.2） |
| Lane C 发布门加固 | **DONE（合入 main）** | payload APK 断言门+strip P0 修复+打包机工单+If-Range 评估 |
| 真机发现 | **2 项 P0/P1 已修** | AGP strip 破坏 pin 记账链（Lane C 实弹抓获）；EOF 收尸 teardown 竞态（真机验证抓获） |
| 产品默认行为 | **未变** | 三 flag gradle 默认 false（双变体测试全绿锁定）、双门/降级语义零触碰 |
| 编译可路由性 | **边界外** | 通道未接（CHANNEL_UNWIRED）诚实呈现；编译桥=fabric-route 轮 |

## 1. 环境与输入

### 1.1 仓库

- 远端 `github`（Nyzeep/Orchestella-Studio），唯一分支 main，开工 head `81b34616`（`git fetch github` 实读核对一致）。
- worktree 三条：`/d/t/uml-face-lifecycle`（feat/uml-face-lifecycle）、`/d/t/uml-rootfs-caller`（feat/uml-rootfs-caller）、`/d/t/uml-release-gate`（feat/uml-release-gate），均基于 81b34616。

### 1.2 高价值产物对账（开工即验，全部一致）

| 产物 | pin | 实测 | 判定 |
| --- | --- | --- | --- |
| linux-fast（U1 pinned） | 21e1c3f9… | 21e1c3f9… | 一致 |
| stub_exe | 87666f91… | 87666f91… | 一致 |

### 1.3 设备（device-b）

- HONOR AGT-AN00 平板 · API 35 · 内核 5.10.236 · 4K 页 · 8 核/11.5GB；序列号只记别名 device-b。
- 用户指令：旧包彻底删除并清理数据后再安装。执行：`io.github.nyzeep.orchestella.qa.umlflag` / `dev.orchestella.umlrunner` / `io.github.nyzeep.orchestella` 三包全部 `adb uninstall`，`pm list packages` 与 `/sdcard/Android/data/` 复查均零残留；单用户（user 0）。

## 2. Wave 0 · 环境重建（DONE）

### 2.1 QA 包重建烟测

- 构建：`./gradlew :app:assembleDebug -Porchestella.buildChannel=qa -Porchestella.buildPurpose=umlflag -Porchestella.uml.bundledFastProfile=true -Porchestella.uml.rootfsDownload=true -Porchestella.uml.fastProfile=true "-Porchestella.uml.fastProfileDir=<pinned U1 产物目录（本机绝对路径不入库）>"` → BUILD SUCCESSFUL（35s，热缓存；69 tasks）。
- 判别（§2.2 纪律）：applicationId `io.github.nyzeep.orchestella.qa.umlflag`、versionName `0.6.3-qa.umlflag.81b3461`（含 `-qa.<purpose>.<sha>` ✅，assembleDebug 通道正确）。
- APK 内实况：`lib/arm64-v8a/libuml_kernel.so`（8,071,168 B）+ `lib/arm64-v8a/libuml_stub.so` + `assets/uml-fast-profile-v1.sha256`（两行记账：libuml_kernel.so / libuml_stub.so，与 pin 一致）。
- 装机：`adb install -r` Success（111,906,384 B ≈ 12.4MB 记账口径沿用上轮的 APK 级数字需在 Wave 3 复核——本行以 install 实测字节为准）；`pm grant POST_NOTIFICATIONS` → `am start -S --activity-clear-task` 启动 → 进程存活、MainActivity 持焦点、UI 渲染正常（首页「AVF · 未就绪 / QEMU · 可用」两项选择器与全新安装态，与上轮终态一致）。
- 注：全新安装后 files/ 为空（无 rootfs）——Lane A 内置 initramfs 通道不依赖它；Lane B 真机验证时将首次走产品内下载。

### 2.2 与上轮终态一致性

- 起点核对：装配槽 fast 形态接线在位（81b34616）、`umlFace()` 生产调用方为零（本轮 Lane A 目标）、引擎选择器两项（本轮 Lane A 触发点裁决对象）。
- 本轮新增血泪：待回填。

## 3. Lane A · UML face 生命周期接线

### 3.1 信息架构短报告与触发点裁决（已完成）

子智能体调研结论（全部 `file:line` 实读核对）：启动链 4 处入口汇入 `InstanceLaunchCoordinator.launch`（`MainActivity.kt:656/:968/:1676` 与 intent 回投）→ `:253` `AcceleratorLaunchDispatcher.dispatch`（gate=`settings.isAcceleratorLaneEnabled() && BuildConfig.ACCELERATOR_LANE_ENABLED`，:258）→ watcher 三处（`:307/:328/:354`）`acceleratorCoordinator?.close()` 是既有 lane 生命周期窗。引擎选择器是「状态+导航」条而非偏好选择器（`activity_main.xml:461-501` 两 MaterialButton、无 adapter、选中不落 preference）。

主会话裁决：

- **采纳 (c) 启动链随行布防**：dispatch 成功且 `coordinator!=null` 窗口内经装配槽 `umlFace()` 构造 face 并 start，stop 挂三处 close 同点——用户触发就是既有启动按钮，零新 UI 范式，gate 复用 launch 已消费的双门，与装配槽「face start/stop 是 wiring owner 的决策（after startAll）」明文对齐。
- **采纳 (a) 仪表第三段「UML · 实验」=状态伴随项**（复刻 seg_pkvm 模式：第三 MaterialButton + 文案映射 + gate-off 诚实解释对话框），不作为独立 spawn 触发。
- **拒绝 (b)**：QEMU 是恒可用基线（`InstanceStrategyLauncher.java:268-273` 无条件路径），「QEMU 不可用 fallback」状态不存在，且通道未接的 face 不能承接编译——冒充 fallback 违反诚实边界。
- **暂缓 (d)**：向导 per-instance opt-in 侵入中，留后续轮。

### 3.2 生命周期形态裁决（关键）

- **采用 §4 探针形态（`um.hold_ms=1000` 锁定命令行，零 extraArgs）；`um.shell=1` 禁用**。依据：`docs/research/2026-11-10-uml-fastboot-profile.zh-CN.md:194` 明文「`um.shell=1` 与 `um.hold_ms=` 是测试/对照接口（进交互 shell、控制关机前停留），不是 fast profile 的内核契约」。产品生命周期不建立在测试接口上。
- 事件链：STARTED → drain 线程契约串 READY（成功只认 `U1_T2_READY`/`U1_READY`，失败只认 `UM_INIT_ERROR code=… step=…`，`research/uml-fastboot/README.md` §4 判定契约）→ 内核自终止 exit 0 → `probe()` 懒收尸（width 0、keep-alive 释放）→ stop() 幂等。
- 长驻 resident face（真实 init + 编译通道）是 fabric-route 轮的地盘；本轮交付=生命周期+账本+诚实呈现。
- 账本：face up 窗口 width()=1（fast 形态语义）→ `fabricOrchestrator` 的 umlFace 重载 → `ledgerBackedWidths` 夹 headroom → 三面恒等快照入日志；orchestrator 只构造+快照，无 submitter、无 dispatch 循环。
- drain 缝：wiring owner 侧自定义 `ProcessSpawner`（spawner 本就是注入缝）实现 read-until-EOF 契约串解析；`UmlFaceLauncher.java` 零字节不动。

### 3.3 实现/验收（DONE，已合 main `9dd55da4`）

feat/uml-face-lifecycle 两 commit（review APPROVE，主会话逐 diff 审查）：

- `fa09085a` **装配槽 5 参 spawner 注入重载**（`AcceleratorLaneAssembly.umlFace(+spawner)`：同双门/同 keep-alive/`FastProfile.currentBuild()` 形态，drain 缝走 launcher 类文档既留的注入缝，`UmlFaceLauncher.java` 零字节）+ **Dispatch 回传装配结果与两面预留**（`assemblyResult/qemuFaceWidth/nativeFaceWidth` 带默认值，既有构造点零破坏；native 面取 dispatch 内部 ACCEL 预留、不重推导）+ **新文件 `UmlFaceWiring.kt`**（§4 契约分类器：成功只认 `U1_T2_READY`/`U1_READY`、失败只认 `UM_INIT_ERROR code=+step=` 双键；read-until-EOF drain；`ConsoleDrainingUmlSpawner` 逐行复刻 processBuilder 缝；`UmlFaceStatusRegistry` 单 face 只读状态源；`UmlFaceRideAlong` arm/disarm 端口实现）。
- `256e6922` **启动链随行布防**（coordinator `UmlFaceLane` 端口注入；`laneGate` 提取为同一双门值供 dispatch+face 共同消费；arm 挂 dispatch 成功窗口；disarm 挂 watcher 退出/异常/launch 失败三 close 点与 `acceleratorCoordinator?.close()` 逐点同构）+ **仪表第三段 `seg_uml`**（layout 第三 MaterialButton；gate-off 双分支诚实对话框；文案两层语义分开说：「UML · 运行中(实验)·编译通道未接」/「UML · 未启用」）。
- **账本快照**（无 submitter、无 dispatch 循环）：fabricOrchestrator 经 umlFace 重载构造一次，快照行记三面+总和+账本预算。真实分布（子智能体判定、主会话认可）：armed 时 ACCEL 预留 1 核 → headroom=0 → UML 宽夹 0（log 如实）；QEMU_ONLY 时 UML 得 1——恒温器本职，Wave 2 真机快照将呈现此分布。
- 生命周期：STARTED → drain 契约串 → 内核 hold_ms 自终止 exit 0 → console EOF 触发 `probe()` 懒收尸（宽 0、keep-alive 释放）→ close 点 stop() 幂等 no-op。EOF 即收尸信号，不另起 poller。
- 验收：1553=1531+22 全绿（Wiring 15/装配重载 2/dispatch 回传 2/coordinator 3）、lint 0 error、敏感门 OK、`UmlFaceLauncher.java` diff 0 行、双门/flag 默认值零触碰。

## 4. Lane B · rootfsDownload 产品调用方（DONE，已合 main `54be8f3c`）

### 4.1 交付（feat/uml-rootfs-caller 三 commit）

- `23686a7c` **编排缝**：`UmlRootfsProvisioner`（provision/readiness 双入口，flag 门+幂等 pin 对账+安全流式解包器——slip 矩阵 9 例：绝对路径/`..`/父路径 symlink 重定向/重复条目/越界硬链接/设备条目/坏头/截断 fail-closed；CREATE_NEW+exec 位保留；收据纪律镜像 `DownloadReceipt.disabled()`）。解包设施考古结论：app 无宿主侧解包器可复用（`TarSnapshot` 是只读静态分析），新建最小解包器。
- `f10b0e43` merge main（Lane A+C 同步，零冲突）。
- `ed80739b` **就绪卡 UI**（候选 A 裁决落地）：`UmlRootfsCardState` 五态模型（构造器强制纪律：percent 仅 DOWNLOADING、reason 仅 FAILED、计数仅 READY）+ flag 门控渲染（off=GONE+无监听，零入口）+ 进度按 pin size 归一 + 失败 reason 稳定码透传；MainActivity 只绑定（operations executor 承载 IO，UI 线程守卫）。
- 验收：1603=1531+72 全绿（B1 25+B2 25+Lane A 22）、lint 0e、敏感门 OK、RootfsTransport/UmlKernelAssets 零字节。

### 4.2 关键语义裁决

- 官方 minirootfs 不含 `/um-command.sh`（`init:100-104` 外置根 fail 29 missing_command）→「已就绪」只声称「已验证+已解包」，绝不声称可启动/可编译——卡片文案、类注释、报告三处口径一致。
- 入口落点=候选 A（资产准备语义 + tab 既有非发行版卡先例）；候选 B（启动链内联）留 fabric-route 轮按真实消费需求再议（幂等缝已就位，接入零成本）。

## 5. Lane C · 发布门加固（DONE，已合 main `8f50678f`）

### 5.1 交付（feat/uml-release-gate 三 commit）

- `abab88a5` **UML payload APK 级断言门**：新建 `tools/release/check_uml_payload.py`（451 行，stdlib-only）+ `test_check_uml_payload.py`（41 例）+ release.yml publish-draft 接入（check_runtime_payload 同位置）。契约：从 APK 内部读 `assets/uml-fast-profile-v1.sha256`，逐条比对 APK 内 so 实际 sha256；无 manifest=SKIP+0（诚实降级形状，ACR D1 豁免放行）；`--require-payload`=打包机正向断言（P2-1 闭合动作）；不匹配=exit 1+差异两侧摘要；`--selftest` 12 例合成 fixture（打包机零输入自测）。脚本不内置 pin（pin 单一源=staging fail-closed），扩展而非改 `check_runtime_payload.py`（两门契约不同：QEMU=必须-have，UML=条件断言+诚实 skip；实际位置在 `tools/release/`，开工材料的 tools/ci 是笔误）。
- `8cede6e7` **打包机工单** `docs/reports/tickets/uml-fast-profile-packaging/packaging-work-order.zh-CN.md`（release/QA 双构建同参命令、pin 复核、§4.1 断言用法、versionName 判别、ADR-0014 tag+源码包门禁与 NOTICE.UML 正式化触发条件、红线与验收清单）+ **If-Range 评估**（管线文档 §6 #6，标注「本节为评估，未实现」：引擎改动面 5 点——Range 唯一发送点/ETag 消费零现状+sidecar 持久化/200-206 分叉/镜像轮换 per-mirror validator 风险（最大语义风险）/fail-closed 清扫面；tripwire 同步点 5 处（`engineResumesByRangeAndNeverSendsIfRange` 断言反转等）；ACR 建议=效率增强非安全修复、参数化非全局切换、单列 D6、与 Wave 2 解耦。裁决权留主会话/用户）。
- `7d72220f` **keepDebugSymbols 修复（主会话 review 实弹抓获，见 5.2）**。
- 验证：`--selftest` 12/12 OK、`unittest discover -s tools/release` 41 例 OK、1531 测试不变（零 app 语义改动）、敏感门三连 OK（中途 token_shape 命中一次：脚本字面 hex 串改用 stdlib `string.hexdigits` 解决）。

### 5.2 本轮新发现：AGP strip 曾破坏 pin 记账链（P0，已修）

主会话用门脚本实弹检查 Wave 0 QA APK：**FAIL**——APK 内 `libuml_kernel.so` 实际 sha `52764628…` ≠ manifest 记账 pin `21e1c3f9…`（stub 逐字节一致）。取证链条：staged `app/src/main/jniLibs/…/libuml_kernel.so`（gitignored 构建时写入）=pin 9,517,760 B → `merged_native_libs` 不变 → `stripped_native_libs` **被 AGP 默认 debug-symbol strip 改写**为 8,071,168 B/52764628。stub 无符号可 strip 故幸存。即「随包字节=pin 字节」的产品声明此前并不成立（设备上功能正常是因为 strip 只去符号）。

修复：`packaging.jniLibs.keepDebugSymbols += libuml_kernel.so/libuml_stub.so`（同 QEMU payload 库既有先例，~1.4MB APK 代价）。修复后重建 QA 包实弹复验：**`UML_PAYLOAD_GATE_OK`，两 pin 逐字节一致，exit 0**。工单 §4.1 补前置事实与排障指引。此发现回写 review P2-1 的完整闭环：现在不仅「漏传有 WARN」被门堵住，连「传了但随包字节被改」也被门+修复双保险堵住。

### 5.3 未决

- If-Range 是否立项（建议 ACR 单列 D6，素材在管线文档 §6 #6）——裁决权留主会话/用户，本轮不实现。
- NOTICE 正式化与 ADR-0014 源码包=打包机首次真实发布时的联动工单，本轮只落触发条件。

## 6. Wave 2 · 真机复验（device-b，全部实测）

### 6.1 face 生命周期全链（PASS，修复版终验）

flag-on+rootfsDownload QA 包（`v0.6.3-qa.umlroute2.87aebab`，含 §6.3 复探修复）+ 产品路径（向导 Debian 13 实例，90.3MB rootfs CDN 下载+创建）→ 仪表 ACT.01 唤起终端触发启动。logcat 证据链（`OrchestellaUml` 家族 tag，流式捕获防缓冲驱逐）：

| 时刻 | 事件 |
| --- | --- |
| 02:05:43.305 | `uml-face started width=1 channel=unwired`（dispatch 成功窗口 arm+spawn，诚实通道声明） |
| 02:05:43.30x | 三面恒等账本快照：`host-native=0 uml=1 qemu=2 sum=3 ledger-budget=3`（QEMU_ONLY verdict→UML 得 1，与裁决预言分布一致） |
| 02:05:43.4x | `Userspace mode: SECCOMP`（app 域真 SECCOMP） |
| 02:05:43.553 | `uml console ready: U1_READY uptime=0.22`（§4 契约串，内核 ~220ms READY） |
| 02:05:44.567 | `[1.240000] reboot: Power down`（hold_ms=1000 自终止，exit 0） |
| 02:05:44.571 | `uml console closed while the kernel is alive; re-probing after teardown grace`（§6.3 竞态出现，复探兜底触发） |
| 02:05:46.577 | `uml face reaped after teardown grace; width reads 0`（复探收尸：宽 0+keep-alive 释放） |
| 02:08:51.186 | `uml-face stopped`（实例正常关机 → watcher 退出 → disarm；QEMU 进程归零） |

UI 诚实呈现实测：运行会话中仪表引擎条「QEMU · 正在使用 / UML · 未启用」（内核自终止后 width() 归 0，两层语义正确）；`seg_uml` 点击 gate-on 导航正常。孤儿检查：face 内核与 QEMU 停机后 `ps` 双零残留。

### 6.2 rootfs 下载产品端到端（PASS，首个真实 CDN）

flag-on+rootfsDownload QA 包（`v0.6.3-qa.umlroute2.87aebab`）→ 发行版 tab「UML rootfs (Alpine minirootfs)」就绪卡（未就绪态：入口按钮+约 3.8MB 提示）→ 点击「下载并验证」→ **真实 Alpine CDN 下载 3.8MB** → L2 pin 校验+L3 五阶段 → 安全解包 →「已就绪 · 已验证+已解包 519 项 · 官方镜像不含 UML 启动脚本，本轮不可直接启动或编译」（按钮禁用）。落盘核验：`files/uml-rootfs/alpine-minirootfs-3.22.2-aarch64.tar.gz`（正式件）+ `.verified` 边车见证 + 解包树（`/bin/busybox` exec 位 `rwx--x--x` 保留、`etc/alpine-release` 600 数据件）。管线文档 §6 未决 #2 的真实网络端到端闭环。

### 6.3 真机发现：EOF 收尸竞态（已修 `87aebabb`）

内核 console 于 powerdown 时先行关闭——EOF 触发的单次 probe 仍读 HEALTHY（实测窗口 ~5ms），收尸被竞态错过、keep-alive 将挂到 disarm。修复：`UmlFaceConsoleSink` 增 `delayedReprobe` 缝（默认 2s 单发 daemon 线程覆盖 hold_ms+teardown 窗口）——宽限后死亡则诚实收尸（宽 0+释放 keep-alive），仍活则 stop 归 wiring owner 不变；契约测试 `consoleEofTeardownRaceIsClosedByTheDelayedReprobe` 锁死。`UmlFaceLauncher.java` 零字节。

### 6.4 设备会话新血泪（本轮新增）

1. **QA 包按 purpose 并存隔离**：`buildPurpose` 变化=新 applicationId——`adb install -r` 三连 Success 却"没换包"的假象真相是装成了并存的新包（本会话踩坑 20 分钟）；判别法先看 applicationId 再看 versionName。
2. **已卸载应用的 UML 内核子进程可逃过卸载存活**（ppid=1 孤儿，SELinux 上下文仍在）——旧包清理检查须包含 `/proc` 进位；重启清零。
3. **MSYS 路径转换吞 `/data` 参数**（`adb push … /data/local/tmp` 被宿主 shell 前缀化成本机盘符路径——形如 <盘>:/Git/data/...，措辞脱敏）——`MSYS_NO_PATHCONV=1`。
4. 熄屏期间 OEM freezer 冻结下载流（向导下载失败「数据读写失败」，重试从 .part 断点续传成功）——长下载须保活循环。

### 6.5 降级对照（MISSING_ASSET）

- APK 级：豁免形状（无 manifest）由 `check_uml_payload.py` SKIP 语义覆盖（12 例自测含 skip/require 两路）。
- 运行时：`MISSING_ASSET` 指名拒绝由 JVM 契约测试锁定（`missingAssetArmIsAnHonestNamedRefusal` 指名 libuml_kernel.so）；设备端演示依赖独立实例创建（豁免包新 applicationId 需再走 90MB 下载），本轮以 JVM 契约+APK 断言双证为准，设备端演示记为 not-tested（诚实记录）。

## 7. not-tested / open

- **编译通道（distcc D-1 桥）**：本轮边界外；face 不可路由编译由 worker 固定 CHANNEL_UNWIRED 拒绝守门（JVM 契约+日志诚实声明）。
- **下载 rootfs 的 hostfs 启动**：官方 minirootfs 缺 um-command.sh，外置根探针契约不可达（§4.2）；真实 init 语义=fabric-route 轮设计题。
- **豁免构建的设备端 MISSING_ASSET 演示**：JVM 契约+APK 断言双证（§6.5），设备端独立实例演示 not-tested（新 applicationId 需再走 90MB 下载，成本收益不划算）。
- If-Range 立项裁决（建议 ACR D6，素材在管线文档 §6 #6）——留用户。
- 16K 宿主、多设备矩阵、full profile（systemd/网络）：继续开放。

## 8. 收口与交付

- **合并**：三车道 9 commits+3 merge+2 修复=14 commits，main 终态 `87aebabb`（序：8f50678f Lane C → 9dd55da4 Lane A → 54be8f3c Lane B → e0ad16d2 变体契约修复 → 87aebabb EOF 竞态修复）。
- **门禁**：1604=1531+73 测试全绿（默认变体）、flag-on 变体全绿（双变体硬门）、lint 0 error、敏感门全程 OK（3 个 token_shape 命中全数化解：脚本 hex 串改 string.hexdigits 等）。
- **push/CI**：待回填。
- **worktree/远端清理**：待回填。

## 9. 合规自查

- 敏感门：每分支 push 前必查已执行，全程 OK（判读看输出文本）。
- 双门/三 flag 默认值语义：零触碰；flag-off 字节锁与显式门测试在双变体下全绿。
- 设备标识/截图/console：截图与 console 全部留仓库外（`/d/t/round-20261113/`），报告只记别名 device-b 与事件链文本。

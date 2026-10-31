---
title: 下游先例拆解——Haven 运行期/恢复期、许可隔离、lakitu16K、soak 方法学、分发形态、Podroid grant UX（fabric 工单 06）
date: 2026-11-06
batch-actual-date: 批次实际作业日 2026-10-31
status: complete（fabric-batch2 工单 06 交付；零设备批次，全部为联网调研 + 只读源码分析，无真机数据）
scope: 工单 .scratch/orchestella-uml-fabric/issues/06-downstream-teardown.md 的六项任务（按 Comments 2026-10-31 输入更新收窄：Haven 只做运行期/恢复期，构建期三坑引用 checklist 不重复；任务 5 只深挖「分发形态 + 交付完整性」，不重复 2026-11-02 的裁决）。不改 CONTEXT.md/ADR/产品代码；不执行任何 git add/commit/push。
method: GitHub API + raw.githubusercontent 源码实读（Haven / uml-transport / lakitu12 / uml4termux / drolin / strykerapp / Podroid / zalexdev 六组仓库）+ android.googlesource.com 与 torvalds mainline 对照 + extv.github.io 文档站。所有结论可溯源到具体文件与行号或 API 响应。
evidence: [官方源码] = 仓库源码/API 响应实读；[官方文档] = 项目文档站或 README 原文；[社区(项目官方自述)] = release notes / issue 自述（代码实证与文档自述分开标注）；[本项目实测] 不存在（零设备）；[未找到证据] = 检索不到。全部 URL 访问日期 2026-10-31。
---

# 下游先例拆解（2026-11-06）

**对象与角色一览**（访问日期均为 2026-10-31）：

| 项目 | 仓库 | 许可 | 在本票中的角色 |
|---|---|---|---|
| Haven | github.com/GlassHaven/Haven（1392★，pushed 2026-10-30） | AGPL-3.0（LICENSE sha `fe6b9036…`，34524 B） | 任务 1/2：运行期/恢复期架构 + 许可隔离 |
| uml-transport | github.com/GlassOnTin/uml-transport | GPL-2.0（仓库 license 字段） | 任务 2：内核补丁/配方独立仓（Haven 的 GPL 交付仓） |
| lakitu12 | github.com/lakitu12/linux-um-arm64-gki | 补丁 GPL-2.0-only（README 自述） | 任务 3：GKI 6.18 移植栈 + 16K 宿主预案 |
| uml4termux | github.com/michaelamoetoevsky/uml4termux（v0.5.0，2026-10-29） | GPL-2.0（LICENSE 19932 B ≈ GPLv2） | 任务 4：soak 方法学评估 |
| drolin | github.com/fahrulseptiana/drolin（v0.1.0，2026-10-24） | GPL-2.0（根）+ Apache-2.0（android/） | 任务 5：分发形态与交付完整性 |
| strykerapp | github.com/zalexdev/strykerapp（1960★，GPL-3.0） | GPL-3.0 | 任务 5：同上（运行时下载先例） |
| Podroid | github.com/ExTV/Podroid（3685★，GPL-2.0）+ extv.github.io/Podroid | GPL-2.0 | 任务 6：双权限 grant UX + 能力诊断 |

---

## 0. 结论摘要（7 条）

1. **Haven 的运行期可靠性不是靠单个技巧，而是四层叠防**（均有代码实证，[官方源码]）：① fork 固定在单一 immortal daemon 线程（`PR_SET_PDEATHSIG` 的触发者是创建线程而非进程，临时 worker 线程 60 s 空闲消亡会把子进程一起带走，exit 137）；② launcher（`uml_net.c`）对内核与 passt 双双 `prctl(PR_SET_PDEATHSIG, SIGKILL)`，App 死亡则内核/网络全灭，杜绝孤儿持 rootfs 锁；③ `closeGuest()` = 控制台写 `poweroff` → 5 s 宽限 → 硬杀全进程树兜底；④ #686 的教训落为**三处**代码修复（`fail()`、`ensureGuestLocked` 失败路径、`stopDesktop` cancel 路径先 `job.cancel()+join()` 再关 guest），v5.89.24 与 v5.89.37 两轮交付。
2. **#686 的修复口径是「失败必 teardown」，Haven 没有做 pre-start 试锁**——issue 全文、v5.89.24/37 修复说明与当前代码均无 `F_GETLK` 预检。启动前 `F_GETLK` 试锁仍是本仓库 checklist 提出的对策项（research/uml-kernel-build/checklist-haven-three-pits.md 坑②），Haven 侧无先例可抄，需自行设计。
3. **「4K 变体在 16K 宿主被 os_early_checks 拒绝」核实为真，且拿到了精确代码**（[官方源码]，高于工单预期的自述核实等级）：zalexdev/linux-um-arm64（um-arm64 branch）`arch/um/os-Linux/start_up.c:977-996` 的 `check_page_size()`——`host = getpagesize()`，`host > UM_KERN_PAGE_SIZE` 即 `fatal()` 打印「Rebuild with CONFIG_PAGE_SIZE_%luKB」；挂在 `os_early_checks()`（:998）的第一项。mainline 的 `os_early_checks`（arch/um/os-Linux/start_up.c:463）**没有**这项检查——该检查是 um-arm64 下游树新增。
4. **对 G2 的核心好消息**：本项目现用内核（Raymer v0.3.0）IKCONFIG 实测 `CONFIG_PAGE_SHIFT=14`（16K guest 页，批次 1 文档 §1），按「guest 页 ≥ 宿主页、16K guest 在 4K 宿主用 4 host pages/guest page」的机制（[官方源码] check_page_size + lakitu 0021/0025 补丁注释），**同一个 16K 内核二进制天然覆盖 4K 与 16K 两类宿主，无需为 16K 宿主重编内核**；G2 的真实工作量在启动器侧（stub 对齐、装载基址、诊断）而非内核移植。
5. **uml4termux 的 soak 证据强于其宣传口径，但也比宣传窄**：105 轮 B1 证据 verdict 实为 **"partial"**（4 轮启动失败 + 4 轮未到 tick，docker 101/105），60 min soak（3606 s，43 容器循环，0 BUG/Oops/WARNING）verdict 为 pass；「RSS 无上升」的官方判定只对 **daemon** RSS 成立（首 10 样本中位 vs 末 10 中位，−0.6%，判线 |Δ|<10%），kernel RSS 只记录 range 无趋势判定；且 RSS 是**每循环一次**的瞬时采样（受「guest poweroff 带走 state file」限制）——方法可复用，数字口径必须如实转写。
6. **内核分发形态的两个成熟先例**（任务 5，衔接 2026-11-02 矩阵只深挖不重裁）：drolin = **jniLibs 随包**（`liblinux.so`/`libpasst.so`/`libstub.so` + rootfs 进 APK assets），对应源码主张 = 「pin 文件 + 补丁 + 构建脚本」同仓（LICENSING.md 逐路径表）；Stryker = **GitHub release 资产 + manifest 运行时下载**（rootless-650 tag：linux-uml 21.5 MB + Image.config + stub_exe + rootfs 321 MB），源码树公开但仓库 license 字段 NOASSERTION——两者分别对应矩阵 ①（低风险）与 ②（Play 渠道禁止、GitHub 渠道无碍）的活样本。
7. **Podroid 的 grant UX 是轨道 B（non-protected 开发者通道）的完整可用模板**（[官方源码] + [官方文档]）：检测（feature 探针 + 五点诊断 + smoke test）→ 引导（文档给 adb 与 Shizuku rish 两条 grant 路径 + 逐条命令）→ 验证（诊断入口在 Settings → About）→ 失败分支（protected-only 被明确写成「expected, not a failure」并自动回落 QEMU）。四条件 Auto 选路（feature + 双权限 + non-protected capability）与「权限跨重启/就地更新保留、卸载即失」的口径均有代码与文档双重支撑。

---

## 1. Haven 架构拆解（只做运行期/恢复期）

> 构建期三坑（rseq/set_robust_list neuter、ubda F_SETLK 内核侧机制、16KB ELF 对齐）见 research/uml-kernel-build/checklist-haven-three-pits.md，本节不重复。所有源码行号以 2026-10-31 拉取的 main 分支为准。

### 1.1 启动链（UmlGuestManager.buildLaunchCommand → uml_net.c）

[官方源码] core/local/src/main/kotlin/sh/haven/core/local/uml/UmlGuestManager.kt:266-300 + core/local/src/main/cpp/uml_net.c（全文 213 行）：

- exec 链：Kotlin `LocalSession` → `PtyBridge.nativeForkPty` → **launcher `libuml-net.so`**（Haven 自有 C 代码，AGPL 侧）→ `execv` passt（子进程）+ `execv` 内核（launcher 本身）。
- argv：`libvmlinux.so` + `mem=2048M ubd0=<rootfs> root=/dev/ubda rw stub_exe=<libuml-stub.so> hostfs=<shareDir>` + 会话级 extraKernelArgs（如 `haven_nbd_host/port`、`haven.desktop=1`）。**无 con0/con 参数**——stdio console 直接落在 PtyBridge fork 的 pty 上；**无 init=**——rootfs busybox init 接管。
- 网络：launcher 建 `SOCK_SEQPACKET` socketpair，passt 在 fd 100、内核拿 fd 101 + `vec0:transport=fd,fd=101`；`PASST_RAW_L2=1`（本地 patched passt 发裸以太网帧）；`PASST_NO_SANDBOX=1`（注释：app 沙箱内 passt 的 privilege drop 会死于 SIGSYS）；端口转发 `PASST_TFWD` 强制 `127.0.0.1/` 前缀（bare ports-only spec 会 bind `::` + `IPV6_V6ONLY=0`——loopback 姿态是显式构造的）；畸形端口列表 `_exit(2)` 大声失败而不是静默起一个挂死的 guest。
- cwd/chdir：**chdir 到 TMPDIR**——UML 需要可写 cwd 放 `.uml/<umid>/` 控制目录（Haven 进程 cwd 是只读 `/`）。这是 uml-runner 类启动器的必要环境项。

### 1.2 生命周期状态机

[官方源码] UmlGuestManager.kt:60-244, 302-362：

- 状态：`SetupState`（rootfs staging：NotStaged/Unpacking/Ready/Error）与 `SessionState.Status`（CONNECTING/CONNECTED/DISCONNECTED/ERROR）两条独立轴。
- 进程退出感知：`LocalSession` reader 线程读到 pty EOF → `PtyBridge.nativeWaitPid` 拿 exit code → `onExited` 回调（UmlGuestManager.kt:328-353）→ 置 DISCONNECTED + 把 scrollback tail 写进 ConnectionLog（「the scrollback tail usually carries the kernel panic or the passt error that killed the guest」——诊断面与状态机绑定，与 05 文档 D-5 的「错误带证据」同型）。
- 会话表清理：`registerSession` 先 `reapDeadSessionsForProfile` 清 DISCONNECTED 旧会话（:222, 237-244）。

### 1.3 rootfs staging 与版本化（恢复期的另一半）

[官方源码] UmlGuestManager.kt:110-182, 512-545：

- asset（`uml/rootfs-aarch64.ext4`，**2 GiB raw ext4**，ROOTFS_SIZE_BYTES=2,147,483,648）→ 写 `rootfs.ext4.unpack` 临时文件 → 校验字节数 → `rename` 到位 → **最后**写 `rootfs.version` marker（注释：written only after the rename so a crash mid-unpack leaves no version marker and the next ensureRootfs() re-stages）——三级幂等：size + version + rename 原子性。
- 版本演进史（v1→v11）本身就记录了一串运行期坑：1 GiB 图填满 96%（v11 扩 2 GiB）、`/dev/pts` 与 `/dev/shm` 必须在 sysinit 挂载（xterm "not enough ptys"、wlroots `shm_open` 失败→sway 有帧进程但渲染空白）、`/sbin/haven-net` 在 rebase 中丢失导致整批 guest 无路由、hostfs sysinit 挂载行多一个 `2>/dev/null` 造成静默失败——详见 GlassOnTin/uml-transport README 与 docs/plans/uml-dm.md Stage-1 节 [官方文档(项目自述)]。
- `deleteRootfs()` 是显式 reset（注释明言「not something any close path may call」）——数据销毁与生命周期关闭严格分离。

### 1.4 关闭、崩溃恢复与锁清理（#686 的完整代码落点）

[官方源码] 三处关闭路径 + [社区(项目官方自述)] issue #686（2026-10-10 opened / 10-11 closed，含 10-16 v5.89.37 追评）：

1. **优雅关 + 硬杀兜底**：`closeGuest()`（UmlGuestManager.kt:455-476）= 控制台写 `poweroff\n` → 100 ms 步进轮询 5 s → 超时走 `LocalSession.close()` 硬杀。`disconnectAll()`（:505-508）是前台服务 teardown 的同步版本（注释：异步 poweroff 可能被进程死亡截断）。
2. **硬杀的完整语义**：LocalSession.close()（LocalSession.kt:193-221）= 关流 → **先快照 `descendantPidsOf(childPid)` 再逐个 kill**（#409/#411 proot 孤儿教训：只杀 childPid 会留整棵树）。
3. **#686 三处修复**（当前代码均有注释锚点）：
   - `fail()`（UmlDesktopManager.kt:771-786）：失败路径必调 `closeSharedGuest(sessionId)`（v5.89.24，对应 issue 正文「fail() …never calls closeGuest」）；
   - `runStart` 的 `CancellationException` catch（:617-624）：「stopDesktop during STARTING cancels this job … Close here so the rootfs-lock holder is always released (#686 on the cancel path)」（v5.89.37）；
   - `stopDesktop` 的 STARTING/INSTALLING 分支（:161-176）：先 `job.cancel()` + `job.join()` 再读 `sharedSessionId`——注释：「Reading sharedSessionId before the job settles races ensureGuestLocked's write and can miss the lock holder entirely」。
   - 另有 `ensureGuestLocked` 的 catch（:552-555）与 `runInstall` 的 finally（:664-666）两条固有 teardown 路径。
4. **共享 guest + 互斥**：`guestMutex` 串行化全部 guest 交互；warm reuse（「<1s startup, no F_SETLK rootfs lock conflict」注释）——锁冲突的预防手段之一是**不重复 boot 同一 rootfs**。
5. **pre-start 试锁**：Haven 代码与 #686 修复链中**不存在** F_GETLK 预检 [官方源码 + 未找到证据]。「pre-start F_GETLK 试锁」出自本仓库 checklist 坑②检查项，是本项目对策而非 Haven 先例——启动器验收项需自行设计（见 §1.6 表）。

### 1.5 运行期坑清单（构建期三坑之外）

| # | 坑 | 来源与等级 | 机制 |
|---|---|---|---|
| R1 | fork 线程必须是 immortal：PR_SET_PDEATHSIG 触发者是**创建线程**（task->real_parent），Dispatchers.IO 临时线程 60 s 空闲消亡 → 子进程被 SIGKILL（exit 137） | [官方源码] LocalSession.kt:70-82 注释（#208 finding） | bionic 语义：PDEATHSIG 绑定线程不绑定进程 |
| R2 | 失败路径泄漏 launcher → rootfs F_SETLK 残留 → 下次启动内核 panic | [官方源码] #686 修复链（§1.4） | checklist 坑②的启动器侧完整案例 |
| R3 | `personality()` 被 app seccomp **SECCOMP_RET_KILL 直接杀（无 SIGSYS）**——内核补丁改为 `__ANDROID__` 下跳过 re-exec-on-ASLR-change | [官方源码] uml-transport android-app-compat.patch arch/um/os-Linux/main.c hunk | 与坑①（SIGSYS/SECCOMP_RET_TRAP 家族）不同处理方式：kill 不 trap，装 handler 也没用 |
| R4 | memfd exec 被拒：stub 必须经 `stub_exe=` 从真实文件 exec（`execveat(fd,"",AT_EMPTY_PATH)` 失败后 fallback `execve(path)`） | [官方源码] uml-transport stub-execve-fallback.patch | PRoot 拦截器拒 fd-exec + Android 拒 memfd 双因 |
| R5 | passt 在 app 域必须 `PASST_NO_SANDBOX=1`（userns/mountns/seccomp drop 全不可用）+ netlink bind 被 SELinux 拒 → local mode 169.254.2.1/16 | [官方源码] uml_net.c:18-20 + uml-transport passt-uml.patch README 节 | passt 上游默认姿态在 app 域全不可用 |
| R6 | UML 需要可写 cwd（`.uml/<umid>/`），Haven 进程 cwd 只读 → launcher chdir TMPDIR | [官方源码] uml_net.c:190-196 | 启动器环境项 |
| R7 | guest 内 `/dev/pts`、`/dev/shm` 未挂 → xterm/wlroots 半死（sway 有帧但渲染空白）；rootfs 填满 96% 同型 | [官方文档(项目自述)] uml-transport README uml-guest-8/9 节 + uml-dm.md Stage-0/1 | guest 侧 sysinit 检查项 |
| R8 | napi budget bug：`vector_poll` 在 `work_done <= budget` 时 `napi_complete_done` → 丢「还有活」信号 → TCP 停摆（未修时 ~700 B/s + DNS 静默丢失） | [官方源码] uml-transport vector-napi-budget.patch + README（production-shaped load 验证自述） | 内核侧网络补丁（um-arm64 树已有，本项目 v0.3.0 同源应含） |
| R9 | 守护进程 SIGKILL 后 gvproxy 孤儿持端口 → bind: address already in use；unix socket `sun_path` 107 B 截断静默错绑 | [社区(项目官方自述)] uml4termux evidence/20261030-soak-b3-restart.json（已修：PDEATHSIG + sockpath_too_long 大声拒绝） | 非 Haven 但同域，启动器设计输入 |

### 1.6 本项目对策建议（对接 uml-runner / 未来启动器，只设计不写代码）

| # | Haven/先例做法 | 对 Orchestella 的落地建议 |
|---|---|---|
| 1 | 单一 immortal fork 线程（LocalSession.forkExecutor） | uml-runner 的进程孵化收敛到常驻单线程；验收项=「从任意协程上下文发起启动，观察 65 s 后子进程存活」 |
| 2 | launcher 双 PDEATHSIG（内核 + passt 各自） | uml-runner 若有 shell 包一层，内核/gvproxy/passt 全部 PDEATHSIG（SIGKILL）；停止链 = 控制台 poweroff → 计时 → SIGTERM → SIGKILL |
| 3 | closeGuest 三段式 + 全树快照再杀 | 启动器铁律进验收清单（checklist 坑②已列）；补「先枚举子孙再杀」次序（杀父前快照，防 pid 复用/树断裂） |
| 4 | #686 三处修复形态 | 未来启动器的**所有**失败路径（banner 超时/console 异常/init 失败/用户取消/上游协程取消）统一收敛到一个 teardown owner；cancel 路径必须先 join 再关，防读半初始化状态 |
| 5 | （Haven 无）pre-start 试锁 | 保留 checklist 建议：boot 前 `F_GETLK` 探 rootfs，持锁即报「旧 guest 未退（pid=X）」；pid 打进错误串（#686 实证该 pid 是诊断关键） |
| 6 | rootfs staging 三级幂等（size+version+rename 次序） | uml-runner rootfs 布署照抄次序；marker 写在 rename 之后；版本 bump 语义 = 一次性重 staging + share 侧数据保全（Haven 用 hostfs share 保 endpoint.env 的做法可借鉴思路） |
| 7 | chdir TMPDIR / PASST_NO_SANDBOX / loopback 前缀转发 | 进启动器环境清单；端口转发 spec 永远带 `127.0.0.1/` 前缀，LAN 暴露只允许显式 knob |
| 8 | console marker 协议（行首匹配 HDESKTOP:/HCAGE:，防回显伪造）+ scrollback tail 随错误态携带 | G-长驻门与未来启动器的状态判定用「行首锚定 marker」而非裸 grep；错误对象必须带 console tail（对齐 05 文档 D-5「错误带证据」） |
| 9 | （um4）sockpath 107 B 拒绝 + daemon 孤儿 PDEATHSIG | uml-runner 的 runtime 目录深度在创建时校验；SIGKILL 后的端口持有者（gvproxy）必须有 PDEATHSIG |

---

## 2. 许可隔离清单

### 2.1 仓库许可矩阵（全部 [官方源码] GitHub license 字段/LICENSE 文件实读，2026-10-31）

| 仓库/路径 | 许可 | 证据 |
|---|---|---|
| GlassHaven/Haven（全部应用层 Kotlin/C） | **AGPL-3.0**（LICENSE = GNU AGPL v3, 2007-11-19, 34524 B） | API `/repos/GlassHaven/Haven/license` + LICENSE 内容 |
| GlassOnTin/uml-transport | **GPL-2.0**（license 字段）；README License 节：kernel/stub = GPL-2.0（Linux COPYING 含 syscall note），passt = GPL-2.0-or-later，patches 同条款回授 | API + README.md:233-240 |
| lakitu12/linux-um-arm64-gki | 补丁 **GPL-2.0-only**（README Credits 节自述；仓库 license 字段 NOASSERTION） | README.md:168 |
| fahrulseptiana/drolin | 根 **GPL-2.0-only**（rust/kernel/images/net/docs）+ `android/` **Apache-2.0**（vendored Termux）+ passt patches **GPL-2.0-or-later**（LICENSING.md 逐路径表） | LICENSING.md:1-42 |
| zalexdev/strykerapp | **GPL-3.0**（license 字段） | API |
| zalexdev/linux-um-arm64 | **NOASSERTION**（字段「Other」）；内核树按 GPL-2.0 释出属常识但**仓库级声明缺失**（2026-11-02 文档 §4.2 已记录，本轮复核一致） | API |
| ExTV/Podroid | **GPL-2.0**（license 字段） | API |

### 2.2 uml-transport 的角色核实（任务 2 专项）

- uml-guest.md「Source availability」节 [官方文档(项目自述)]：内核与 passt 的**完整对应源码、两个内核补丁、passt 补丁与构建配方**发布于 GlassOnTin/**uml-transport**（注意：在 **GlassOnTin 个人账号**下，不在 GlassHaven org 下——org 的 10 个公开仓库中没有 uml-transport [官方源码]）；Haven APK 内的二进制 = pin `uml-guest-4`（当前 fetch-uml.sh 已升 pin `uml-guest-10`）。
- uml-transport 目录（[官方源码] trees API，21 文件）：`android-app-compat.patch`、`stub-execve-fallback.patch`、`vector-napi-budget.patch`（内核补丁×3）、`passt/passt-uml.patch` + `passt/build.sh`、`rootfs-overlay/`（inittab、agent-launcher.sh、haven-net）、`tools/um-arm64/harness/`（build-bionic.sh、check-app-seccomp.sh、patch-glibc-seccomp.py）、`usb-haven.config`。
- 工单假设「若其 UML 内核补丁在独立仓库且为 GPL-2.0」——**成立**，且比预期完整：补丁、配方、pin sha256、seccomp 门脚本同仓同许可。

### 2.3 可借鉴 / 不可搬清单

**不可搬代码（AGPL-3.0，Haven 主仓全部）**：

| 项 | 内容 | 合法替代 |
|---|---|---|
| UmlGuestManager.kt / UmlDesktopManager.kt / UmlRecoveryManager.kt / LocalSession.kt | 生命周期状态机、共享 guest、teardown 链、rootfs staging、USB 恢复路线 | 只借鉴思路（§1.6 表），全部自行实现于本项目自有代码 |
| uml_net.c | socketpair launcher、PDEATHSIG 编排、passt argv 组装 | 思路可鉴（R1/R5/R6 表），实现重写；该文件属 Haven 主仓（AGPL）而非 uml-transport |
| PtyBridge / pty_bridge.c | forkpty + raw termios | 同上 |

**可引用代码（GPL-2.0，须以 GPL-2.0 条款处理且保持独立边界）**：

| 项 | 内容 | 引用边界 |
|---|---|---|
| uml-transport 三个内核补丁 | 运行期兼容（personality/SIGSYS 诊断、stub exec fallback、NAPI budget） | 内核补丁按 GPL-2.0 随内核源码树走；与本仓库 AGPL/专有应用代码之间保持「独立程序」边界（ADR-0014 议题，法务口径不在本票裁决） |
| uml-transport seccomp 工具（patch-glibc-seccomp.py / check-app-seccomp.sh） | 坑① neuter + 扫描门 | 若采纳 Haven 路线可直接引用（GPL-2.0）；GPL-2.0 脚本进本项目 CI 需记录来源与许可 |
| lakitu 补丁栈（GPL-2.0-only） | §3 的 GKI 移植栈 | 同内核侧边界 |
| drolin kernel/patches（GPL-2.0-only） | 16 补丁（含 bionic symbols/vdso/signal） | 同上 |

**只借鉴思路（任何许可下都不搬实现）**：

- soak 方法学（§4，GPL-2.0 脚本但本项目只取字段设计）、Podroid 五点诊断与反射访问 @SystemApi 的手法（GPL-2.0，实现不搬，探针清单照抄语义）、Passt 端口转发 loopback 前缀语义、console marker 协议形态。

---

## 3. lakitu12/linux-um-arm64-gki 拆解

### 3.1 补丁栈范围（逐类归组）

**补丁数事实**：`patches/` 目录实际 **75 笔**（0001–0075，[官方源码] trees API 全量 listing）；README 自述「the 71-patch stack」「applies 71/71」[社区(项目官方自述)]——**过时数字**（docs/PORT-STATUS.md 显示演进：初版 60 笔 → +qcow2/0064/umfb/VT 件 → 75；README 同时列出了 0070/0071 却仍写 71，自相矛盾）。EXCLUDED-SMP.md 的「未包含 5 笔（0047–0051）」指**上游 um-arm64 栈的编号段**，与本仓库文件号不对应，勿混淆。

75 笔按类归组（[官方源码] 逐文件名 + fixlog/README）：

| 类 | 补丁 | 内容 |
|---|---|---|
| 16K 页（G2 核心） | 0021, 0025, 0036, 0037, 0042, 0053/0054 | `select HAVE_PAGE_SIZE_16KB`（arm64 subarch + arch/um Kconfig `if UML_ARM64`）、defconfig `CONFIG_PAGE_SIZE_16KB=y`、`PTRS_PER_PTE derive from PAGE_SHIFT`、fault-around 按页大小缩放、移除 `__access_ok_vsyscall`（×2，6.18 回移） |
| 地址空间布局 | 0052, 0064, 0071 | topdown mmap（mmap_rnd_bits Android init 语义）、**地址空间顶部改由 `uml_host_top` 参数给定**、该参数接受 `0x` 前缀（拒绝前缀=静默回退栈猜测→bionic_tls 死） |
| vDSO/signal ABI | 0006, 0008, 0009, 0015, 0018, 0020, 0023 | signal frame ABI offsets pin、HWCAP sanitise、NT_ARM_SYSTEM_CALL regset（不写+实现）、signal frame 尺寸拒绝、AT_MINSIGSTKSZ 读取、rt_sigreturn 清正确字段 |
| stub | 0016, 0019, 0030, 0031, 0038, 0047, 0049, 0057 | x86 32-bit 件、stub 栈 reserve、handoff wake elision、cycle counter 探针、**stub 内禁用 pointer authentication**、死 stub 交 reaper、exec 失败报因、text-patching 叠加头 |
| bionic/NDK 构建 | 0027, 0043, 0044, 0029, 0013 | bionic 大补丁（53 KB）、bionic 符号改名表链接期生成、NDK 自动发现、seccomp 探针不走 libc wrapper、seccomp probe 合法 signal stack（arm64 MINSIGSTKSZ 5120 vs 1 页 4096 → ENOMEM → 卡死在「Checking seccomp…」） |
| 进程/调度 | 0002-0005, 0011, 0014, 0024, 0032 | syscall arg1 aliasing、TLS 不被 context switch 清、FPCR/FPSR 保留、x8 恢复、syscall stop 脱步、线程指针回读、ENOSYS 不入首个 syscall、匿名 fault-around |
| 存储 | 0061, 0062, 0063 | **ubd 内建 qcow2 读写引擎**（压缩描述符读 + 簇分配写 + refcount；拒收 backing/快照/加密/zstd/扩展 L2）+ 宿主 selftest |
| 显示 | 0065-0067, 0069, 0070, 0072, 0073, 0074, 0075 | **umfb**（fb0 脏 tile 15 Hz 差分泵到宿主文件）、umview X11 呈现、platform device 注册（Xorg fbdev 探针要求）、stdio console 保活（CONFIG_VT 抢 major 4 时容忍失败）、**stdio console 回答 VT ioctl（guest 跑真 Xorg 的正解）**、virtio-gpu damage 修正、virtio-snd fragment |
| Docker/容器 | 0046 + config fragments | drop BPF-less docker config；默认合并 docker/snap-flatpak/android-guest(binder)/umfb 四个 fragment |
| 杂项 | 0001, 0007, 0010, 0012, 0017, 0022, 0026, 0028, 0033, 0034, 0035, 0039, 0040, 0041, 0045, 0048, 0050, 0051, 0055, 0056, 0058-0060, 0068 | 初始 subarch（152 KB）、CLONE_BACKWARDS、hw_random misc 节点 Android 用户名/世界可读、uml_dir 未建不 panic、qemu-img 口径修正等 |

验证状态（[社区(项目官方自述)]，含 CI run 编号与 sha256，未独立复核）：GKI `android17-6.18` @ 6.18.32、NDK r28c 构建、GitHub arm64 runner boot gate 绿（run 38875955444，2026-10-16）；真机 marble（经其 app 仓库 loader）跑通 docker 26.1.5 hello-world/nginx:8080/overlay2/cgroup2（2026-10-17）。**UP-only**（SMP 5 笔暂缓；上游 UML-SMP = Tiwei Bie v2 00/10，合入 6.19，42 文件 +827/−112——与批次 1 §4.2 的「mainline arm64 无 select」结论兼容：generic SMP 6.19 进主线，arm64 subarch select 是下游自加）。

### 3.2 「4K 变体在 16K 宿主被 os_early_checks 拒绝」核实

**结论：自述为真，且证据等级可升到 [官方源码]**。

- 检查代码（zalexdev/linux-um-arm64，branch um-arm64，`arch/um/os-Linux/start_up.c`，2026-10-31 实读）：

```c
/* :977-996 */
static void __init check_page_size(void)
{
	unsigned long host = getpagesize();

	if (host <= UM_KERN_PAGE_SIZE)
		return;

	fatal("Host page size is %lu bytes but this kernel was built for %lu.\n"
	      "A guest page is a host mmap(), so it cannot be smaller than the\n"
	      "host's page size. Rebuild with CONFIG_PAGE_SIZE_%luKB.\n",
	      host, (unsigned long)UM_KERN_PAGE_SIZE, host / 1024);
}

/* :998-1003 */
void __init os_early_checks(void)
{
	int pid;

	/* Nothing below can work if this is wrong, so check it before anything */
	check_page_size();
	...
```

- mainline 对照（torvalds master，2026-10-31）：`arch/um/os-Linux/start_up.c:463` 的 `os_early_checks()` 只有 check_coredump_limit + check_tmpexec + init_seccomp，**无页大小检查**——该项是 um-arm64 下游新增；mainline `arch/um/kernel/um_arch.c:427` 调用点同在。
- 机制自述（0025 补丁注释 [官方源码]）：加检查之前的行为是 **silent hang**（「stops dead just after the ptrace capability checks, with no message」——第一件事是 map stub，mmap 长度/offset 非宿主粒度整数倍即拒绝）。这解释了文档自述演进：0025（08-15）说「fails by hanging」，0037（08-16）说「os_early_checks refuses」——**不同时点的行为，不是矛盾**。
- lakitu README 的补充实测 [社区(项目官方自述)]：`-z max-page-size=4096` 链出的 bionic-static probe 在 16K guest 上 SIGSEGV 139（PT_LOAD `(p_vaddr - p_offset) % PAGE_SIZE != 0` 的段映射到未链接地址）；**glibc 发行版 rootfs 不需要 4K 变体**（glibc 运行时查页大小；Debian 12.15/13.7 在 16K 与 4K 内核上都 boot 到 shell，实测 2026-10-17）。

### 3.3 G2（16K 宿主门）技术预案（只设计不实施）

**本项目内核现状**（引用批次 1 文档，不重测）：Raymer v0.3.0 = Linux 7.2-rc4 um-arm64 树 + 38 commits；IKCONFIG 实测 `CONFIG_PAGE_SHIFT=14`（16K guest 页）；UP 与 SMP 两 profile；stub 2,000 B 与 release 逐字节一致。

**关键判断**：本项目内核已是 16K guest 页 → `check_page_size()` 在 4K 宿主（4096 ≤ 16384）与 16K 宿主（相等）都通过——**同一内核二进制覆盖两类宿主**。G2 的工作量集中在启动器与打包侧：

| # | 预案项 | 依据 | 验证内容（设备窗口） |
|---|---|---|---|
| G2-1 | 内核侧零改动确认：在目标 16K 宿主上直接跑现有 `libuml_kernel.so` | check_page_size 通过条件 `host <= UM_KERN_PAGE_SIZE`（[官方源码]） | 16K 宿主首启存活 + `getconf PAGESIZE`=16384 + dmesg 无 `check_page_size` fatal（not-tested） |
| G2-2 | stub 的 16KB 语义双重性：坑③的 ELF PT_LOAD 对齐（构建期 gate）≠ 本节的 guest 页语义。stub exec 是**宿主进程内** mmap/exec——宿主 16K 时 stub 的 PT_LOAD 若 4K 对齐，与 APK 16K 提交门冲突；exec 装载是否要求 16K 对齐属设备侧事实 | checklist 坑③ + lakitu probe SIGSEGV 139 实测（bionic-static 在 16K guest 的映射语义） | 4K 对齐 stub 在 16K 宿主 exec 存活（`-z max-page-size=16384` 重链版对照） |
| G2-3 | 启动器地址空间参数：um-arm64 树支持 `uml_host_top`（0064/0071，含 0x 前缀坑） | 0064 patch [官方源码] | 目标宿主上 uml-runner 是否需要传 `uml_host_top`；默认栈猜测值与 16K 宿主布局的相容性 |
| G2-4 | 4K 宿主上的内存放大口径：16K guest 页在 4K 宿主 = 4 host pages/guest page，内部碎片「a few percent」（0025 自述） | [官方源码] 0025 注释 | 4K 宿主设备 mem=2G guest 的实际 RSS 对照（一次采样，不跨夹具相除） |
| G2-5 | bionic-static 载荷约束：guest 内**静态 bionic** 二进制对 16K guest 页敏感（lakitu 实测唯一受害者=4K 对齐 ctty）；glibc rootfs 不敏感 | lakitu README [社区(项目官方自述)] | guest 内工具链选型登记：glibc 发行版优先；若引入 bionic-static 工具须 16K 对齐链 |
| G2-6 | 失败诊断串入库：`Host page size is ... Rebuild with CONFIG_PAGE_SIZE_` 与 SIGSEGV 139 静默死作为 16K 门日志监控特征 | check_page_size fatal 串 [官方源码] | 日志监控规则（纯设计可定） |
| G2-7 | 若未来需要 4K guest 变体（仅「构建期写死 4096 的载荷」或与 4K 宿主逐页对齐场景）：走 CONFIG_PAGE_SIZE_4KB 重编 + CI 断言，参考 lakitu pages4k CI 输入模式 | [官方源码] page4k.config + workflow_dispatch | 不立项，仅登记触发条件 |

---

## 4. uml4termux soak 脚本可信度评估与 G-长驻门方案

### 4.1 证据盘点（代码实证 vs 文档自述）

| 证据 | 状态 | verdict | 关键数字 | 等级 |
|---|---|---|---|---|
| 20261026-soak（60 min 驻留） | repo 文件 evidence/20261026-soak-20261026.json | **pass** | 3606 s（请求 60 min）；**43** container cycles；87 guest 样本 + 121 phone 侧样本（每 30 s）；dockerd 全程存活（2 个 cosmetic getCPUInfo 报错）；**0** BUG/Oops/WARNING；guest MemAvailable 尾值 932 MB；kernel `7.2.0-rc4-opt` seccomp ncpus=4；设备 2406APNFAG / Android 16 / Termux 域 / unprivileged | [官方源码]（JSON 证据 + 原始日志 sha256 清单） |
| 20261030-soak-b1（105 轮循环） | evidence/20261030-soak-b1.json | **partial**（非全过） | 105 cycles / 167.4 min wall / 96.6 s per cycle；**4 cycle 启动失败 + 4 cycle 未到 tick**；docker 就绪+容器跑完 **101/105**；leftover 0；image sha+mtime 全程不变；daemon RSS 4608–5468 KB，首 10 中位 4786 vs 末 10 中位 4758（**−0.6%**）；kernel RSS range 60964–78892 KB；kernel `7.2.0-rc4-g169b17ff3f66-dirty`（**与本项目 pin 同一 commit**） | [官方源码] |
| 20261030-soak-b2（双实例） | evidence/20261030-soak-b2.json | pass | 80 cycles / 73.2 min / 55.6 s per cycle / 2 labels 并行 | [官方源码] |
| 20261030-soak-b3（重启+暴露重放） | evidence/20261030-soak-b3-restart.json | partial | SIGKILL daemon → 重启 → 暴露重放成功；发现并修复 3 个运行期 bug（gvproxy 孤儿、sun_path 截断、C daemon 不认 SSH port env） | [官方源码] |

**宣传口径对照**（纪律要求逐条）：

- WorkBuddy 基线（2026-10-31 调研 §2.2）写「3606s 连续 / 105 轮 / 10047s / 60min dockerd / RSS 无上升」。核实：3606 s 与 60 min dockerd = 20261026 证据（pass）；105 轮 = b1，其 wall-clock 167.4 min = 10044 s——**「10047s」在当前仓库任何文件中找不到**（README/CHANGELOG/全部 evidence JSON 检索无果）[未找到证据]，与 10044 s 差 3 s，推断为引用 b1 cycles.jsonl 时间戳差的中间口径，**引用时应改用证据 JSON 原值**；「RSS 无上升」只对 daemon RSS 的固定窗口中位数差成立（b1 −0.6%），且 **b1 verdict 是 partial**（boot 4 失败、docker 101/105）——「105 轮全绿」的读法不成立。
- v0.5.0 release notes 与 README 的能力表述（COW boot sha 不变、干净 poweroff 1.0-1.5 s、多实例、字节可复现内核）均为项目自述+证据文件背书，本轮未独立复现 [社区(项目官方自述)]。

### 4.2 方法学拆解（全部 [官方源码] soak/soak.sh 229 行 + summarise.py 87 行 + guest/tick.sh）

- **B1 单循环**：`up`（boot timeout 240 s）→ 读 daemon pid（instances/<name>.env）与 kernel pid（daemon.sock.state 的 child_pid——注释：CLI 无 --json，曾因此全记 0 KB）→ RSS 各采**一次**（VmRSS from /proc/<pid>/status）→ 等 console 出现 `SOAK_TICK_DONE`（5 s 步进，上限 420 s）→ `down` → sleep 3 → leftover 检查 → image sha256+mtime 前后对比 → 追加一行 JSONL。
- **失败判定线**（summarise.py pass 块）：`no_cycle_failures`（up_rc≠0 计数=0）、`no_leftovers`（**per-instance** 按记录 pid 查 /proc 存在性——device-wide 会把并行兄弟实例算进去，b2 实测教训；收尾另做一次 device-wide `count_all_uml` 按 /proc/<pid>/exe 匹配 linux-bionic/uml4termuxd/gvproxy——注释：按 exe 解析不按模式，模式会匹配到调用 shell 自身）、`images_untouched`、`rss_no_upward_trend`（|daemon RSS 首 10 样本中位 vs 末 10 样本中位 差| < 10%）、`restart_checked`。
- **RSS 统计口径**：固定窗口（首/末各 10 样本的中位数）——注释明言 thirds 有 artifact（66 cycles +4% vs 73 cycles +12%）；**kernel RSS 只记 max/range，不进 pass 判定**。
- **guest 侧 tick**：cgroup2 挂载 + tmpfs /run → dockerd 起（`docker info` 探活 60×2 s）→ `SOAK_DOCKERD ready=… ms=… server=…` → 优先用缓存镜像跑容器（busybox/alpine/hello-world），无缓存才 pull（150 s 上限）→ `SOAK_RUN … out=SOAK_RUN_OK`（60 s 上限）→ poweroff。「This script never lies about a failure」。
- **image 隔离**：`cp -f` 全量拷贝而非 hardlink——注释：hardlink 会共享 inode，UML 对 backing image 拿 F_SETLK 独占锁，第二个 boot 会**静默 diskless**（NO_DISK trap）丢全部写（#686 锁语义在同一批人的 soak 工具里的再次出现）。
- **已知采样限制**（b1 known_limits 自述）：RSS 每 cycle 一次、且在就绪后采样——「guest poweroff 会带走 daemon 的 state file」导致无法做连续高频采样；60 min 驻留证据（20261026）另有 30 s 间隔采样（guest 内 + phone 侧双列）。

### 4.3 可信度评估

| 维度 | 评估 |
|---|---|
| 数据真实性 | **高**。JSONL/summary/原始日志三者同仓、脚本同仓且带 sha256 清单；判定脚本对「采样窗口 artifact」的自我校正（fixed window 替代 thirds）与失败不粉饰（tick.sh 注释）表明流程严肃；b1/b3 的 partial verdict 与自曝 bug（B3 三修复）反向增强可信度 |
| 覆盖面 | 中。cycle 级 boot/dockerd/容器循环证据充分；**单次长驻（>60 min）内存趋势证据只有一份**（20261026，30 s 采样），且 kernel RSS 无趋势判定 |
| 与本项目的可比性 | **低——这是最大限制**。全部证据在 **Termux 域**（无 app 沙箱 seccomp、无 zygote 内存账本、无前台服务/LifecycleManager 约束、shell 可用 /proc 全量遍历）；kernel commit 与本项目相同（169b17ff）是唯一强同源点。Termux 域结论**不能**平移为 app 域结论（纪律：不同夹具禁止相除/平移） |
| 可复用性 | 方法可复用（字段设计见 §4.4）；脚本本体 GPL-2.0，可直接引用或改写 |

### 4.4 G-长驻门建议脚本方案（只设计；不进主构建）

**设计原则**：取 uml4termux 方法学骨架（循环结构/固定窗口中位/JSONL+summary 双层/leftover 按 exe 判定/image sha 对账），替换为本项目域的判定对象与差异项。

1. **采集字段**（每轮 JSONL 一行）：
   - 进程：`kernel_rss_kb`、`launcher_rss_kb`（若 uml-runner 有常驻 wrapper）、`gvproxy_rss_kb`；pid 来源仿 um4（状态文件原子读，不解析 stdout）；
   - 采样节奏：**循环模式**每轮 2 次（就绪后 + 关停前）；**驻留模式**每 30 s 一次 × N min（对齐 20261026 证据的强度）；均记 `t_epoch`；
   - 功能：console 行首锚定 marker（`GSOAK_DOCKERD ready=…`、`GSOAK_RUN out=…`，防回显伪造——Haven ConsoleLog.firstMarker 语义）；dockerd 探活、一次容器 run 的 rc 与耗时；
   - 完整性：rootfs sha256+mtime 前后对比（COW/快照模式必须不变；直写模式记录差量）、leftover 计数（按 /proc/<pid>/exe，device-wide 收尾一次）；
   - 环境：`getconf PAGESIZE`（宿主页）、`ro.build.version.release_or_codename`、targetSdk 域标签、OEM 版本——设备窗口样本分档用。
2. **轮次**：门 1 = 循环 20 轮（boot→dockerd→run→clean stop；um4 的 105 轮是加强档不设为门槛）；门 2 = 单驻留 60 min（dockerd 常活 + 30 s 采样）；门 3 = kill -9 中断后重启 + 端口/锁重获（对齐 b3 场景与 Haven #686 教训：重启前 F_GETLK 试锁必须报告旧持锁者）。
3. **判定线**（对齐 um4 既有口径，全部写进 summary.json 的 pass 块）：`no_cycle_failures`、`no_leftovers`（=0）、`rootfs_untouched`（COW 模式）、`|Δ median(首10, 末10)| < 10%`（**仅对 kernel_rss_kb**——本项目 daemon 侧若有常驻进程同理；修正 um4 只判 daemon 的口径：本项目 guest 内核进程才是长驻主体）、kernel 日志 `BUG|Oops|WARNING` 计数=0（dmesg/console 双源）、门 3 的重启成功 + 无 bind 冲突。
4. **产出物**：`cycles.jsonl`（原始）、`summary.json`（pass 判定+中位数）、`run-meta.json`（设备/内核 sha/脚本 sha256——对齐 um4 的 artifacts 清单习惯）、console 全量日志。验收人只读 summary.json 的 pass 块。
5. **Termux 域 vs App 域环境差异项**（脚本的 preflight 清单，任何一项不满足即拒绝出证据）：
   - seccomp/SELinux 域不同（App=untrusted_app 域 + zygote seccomp；Termux 无）→ App 域证据才有准入价值；Termux 域数据只能作旁证；
   - /proc 遍历权限不同（App 域按 uid 隐藏他人进程——leftover 判定自动收窄为本 App 进程，device-wide 口径在 App 域**不可用**，必须改为按记录 pid + 本 App exe 前缀）；
   - 内存账本不同（App 域受 lmkd/MemoryLimiter 约束，2026-11-02 文档 §2.1——长驻门观察项应加 `ApplicationExitInfo` 采集）；
   - 后台约束不同（App 域前台服务/电池优化影响驻留——测试进程须持前台服务并记录状态）；
   - 网络栈不同（Termux=gvproxy 直跑；App 域同 gvproxy 但受 loopback/本地网络权限约束）。
6. **脚本归属**：零设备批次只交付设计文本；脚本实体归设备窗口工单（G4/G-长驻门实施时），GPL-2.0 参照 um4 soak.sh/summarise.py 结构改写并保留许可头。

---

## 5. drolin / Stryker 内核分发形态与 GPL 交付完整性

> 衔接 2026-11-02-platform-policy-matrix.md §3 合规矩阵与 §4.2 GPL 先例记录；本节只深挖「分发形态 + 交付完整性」，Play 政策裁决不复述不重裁。

### 5.1 drolin（fahrulseptiana/drolin，GPL-2.0，v0.1.0 2026-10-24）

**分发形态**（[官方源码] android/build-apk.sh 全文）：

| 产物 | 载体 | 证据 |
|---|---|---|
| UML 内核 | **jniLibs 随包**：`kernel/dist/linux-um-$ARCH-android` → `lib/arm64-v8a/liblinux.so` | build-apk.sh:97-105 |
| stub | jniLibs：`libstub.so`（缺省时 WARNING「Android may refuse to execute the embedded one」——内嵌 stub 是 fallback） | :116-123 |
| passt | jniLibs：`libpasst.so`（musl static + bionic patch） | :107-114 |
| Rust 引擎 | jniLibs：`libdrolin.so`（实为可执行 ELF） | :69-72 |
| rootfs | **APK assets**：`assets/rootfs.ext4.gz`（gzip -n -9 可复现压缩），首启解压到 data dir；release 另附同 hash 的 `ubuntu-26.04.1-arm64.ext4.gz` 供独立校验（release notes：「The attached image is the exact Ubuntu arm64 rootfs the APK boots」） | :80-95 + v0.1.0 release body |
| initramfs | assets：`initramfs.cpio.gz` | :74-78 |

**GPL 对应源码交付完整性逐项核对**（[官方源码] LICENSING.md + kernel/ 目录）：

| 义务项 | 状态 | 证据 |
|---|---|---|
| 内核源码可得 | ✅ | kernel/fetch.sh pin `linux-7.2.12.tar.xz`（kernel.org CDN）+ latest-stable.env sha256 `bb18468a…`；「Bump this file in one commit; nothing else spells the version out」 |
| 内核补丁 | ✅ | kernel/patches/0001–0016（16 笔，GPL-2.0-only，含 arm64-um 全套：kbuild/asm/sysdep/bionic symbols/ptrace/syscalls/signal/vdso） |
| 构建脚本 | ✅ | kernel/build.sh + build-android.sh + config/minimal.config |
| passt 源码 | ✅ | net/build-passt.sh pin commit + net/patches/passt-bionic.patch（GPL-2.0-or-later） |
| rootfs 包源码 | ✅（口径=上游满足） | LICENSING.md：Ubuntu base rootfs 「per package, as shipped in the image」；images/build-ubuntu.sh |
| 许可边界声明 | ✅（本批次最佳样本） | LICENSING.md 逐路径许可表 + 「What a distributed APK contains」节：separate works over a process boundary and a socket，app 保持 Apache-2.0、捆绑二进制保持 GPL——与 ADR-0014「独立程序」讨论高度相关，但**法律结论不在本票裁** |
| 完整性缺口 | ⚠️ | ① debug APK 用一次性 throwaway key（升级需卸装，release notes 自述）；② 仓库无真机 gate（CI 只 build，2026-11-02 已记录）；③ 内核二进制无独立 release 资产（只在 APK 内），「APK↔源码」对应关系靠构建脚本而非产物清单（无 per-APK 内核 sha256 清单） |

### 5.2 Stryker（zalexdev/strykerapp，GPL-3.0，1960★）

**分发形态**（[官方源码] releases API）：

- **App**：GitHub releases 直接发 APK（6.5.1 = 96,617,041 B，2026-10-17）。v6.5（2026-10-03）release notes：「A second Linux engine: UML. It is lighter and starts faster than the virtual machine. Stryker checks your phone and picks the engine for you.」
- **引擎资产 = release 资产 + manifest 运行时引用**：`rootless-650` tag（2026-09-05，body 自述「Test build for 650+ … **Not referenced by the manifest yet**」）assets：`linux-uml` 21,538,600 B（UML 内核二进制）、`Image` 28,676,104 B（QEMU 用内核镜像）、`Image.config` 145,516 B（**defconfig 随资产发布**）、`initrd.img` 16,308,339 B、`rootfs.imgz` 321,664,844 B、`stub_exe` 1,968 B。kernel `7.2.0-rc4-g8897487c5223`（= uml-transport README 引用的同一 commit 8897487c5）。`rootless-main` tag 同构（QEMU 件 + rootfs 428 MB）。
- 形态判定：内核二进制**不在 APK 内**，走「App 内下载 release 资产」通道（manifest 机制控制版本指向）——对应 2026-11-02 矩阵 §3.1 **② 运行时下载内核**形态的活样本；Stryker 仅 GitHub 渠道分发（pentest 定位无 Play 上架），与矩阵「② 在 Play 渠道禁止、GitHub 渠道无此政策」的判定一致（判定本身见 02 文档，本节只补实证）。
- **GPL 交付完整性**（[官方源码] + [未找到证据] 部分）：源码树 zalexdev/linux-um-arm64 公开（branch um-arm64，107★）；`Image.config` 提供配置；但 ① linux-um-arm64 仓库 license 字段 **NOASSERTION**、无仓库级 GPL 声明文件（02 文档已记录，本轮复核一致）；② 未找到「APK 内引擎二进制 ↔ 源码树 commit」的公开 pin 清单（只有 rootless-650 body 的 g8897487c5223 一处）；③ rootfs（Debian）包源码走上游口径无专门声明；④ strykerapp 的 GPL-3.0 与内核 GPL-2.0 二进制的边界无 LICENSING 文件。**交付完整性评级：低于 drolin**（有源码树有 config，缺 pin 声明与许可声明）。

### 5.3 对本项目的直接含义（只登记，不裁决）

- drolin 证明「**jniLibs 随包 + 对应源码 = pin+补丁+脚本同仓 + 逐路径 LICENSING 表**」是当前社区可达到的最完整开源交付形态，与本项目 uml-runner 的 `libuml_kernel.so` 打包路线同构，其 LICENSING.md 结构可作为本项目发布工程（docs/standards/VERSIONING.md 边界内）的参照模板（思路借鉴）。
- Stryker 证明运行时下载形态在 GitHub 渠道的技术可行性（manifest+资产），但 2026-11-02 矩阵的 Play 渠道禁止判定不受影响；本项目内核分发若走 Play 必须为 ① 形态（该裁决权在 02 文档与 ACR）。

---

## 6. Podroid 双权限 grant UX 全流程与五点能力诊断

### 6.1 用户视角完整流程（[官方文档] extv.github.io/Podroid/guide/backends.html + [官方源码] AppPermissions/AvfDiagnostics）

| 阶段 | 内容 | 实现/文案 |
|---|---|---|
| ① 检测 | `adb shell pm list features \| grep virtualization_framework`（文档 Step 1）；App 内 `pm.hasSystemFeature("android.software.virtualization_framework")` | AvfDiagnostics.probe:111 |
| ② 引导 grant | 文档 Step 2 给出两条命令原文（`adb shell pm grant com.excp.podroid android.permission.MANAGE_VIRTUAL_MACHINE` / `…USE_CUSTOM_VIRTUAL_MACHINE`）；**无 PC 路径 = Shizuku**：rish shell 内直接跑 `pm grant …`（去掉 adb shell 前缀） | backends.html Step 2 |
| ③ 验证 | 文档 Step 3：Settings → About →「AVF (pKVM) diagnostic」跑 probe + smoke test；smoke test 的 skip 文案**自带命令行修复指引**（「skipped: MANAGE_VIRTUAL_MACHINE not granted (run: adb shell pm grant …)」） | AvfDiagnostics.runSmokeTest:157-222 |
| ④ 运行时选路 | Auto 后端四条件：feature + 双权限 granted + `getCapabilities()` 含 NON_PROTECTED（bit 2）；任一失败回落 QEMU，回落以**三处**告知（Home notice / Status 后缀 / Settings chip caption） | AvfCapabilities.choose:35-43 + backends.html「How Auto-selection works」 |
| ⑤ 失败分支 | protected-only（caps 仅 bit 1）：诊断文案「**This is expected, not a failure**: Podroid automatically uses the QEMU backend here」；若 caps=0 而 AVF 在 vm.run 时抛 UnsupportedOperationException(protected)：同文案分支；smoke test 在创建后 1.5 s 即 stop+delete（「proving the framework accepts us, not running a workload」） | runSmokeTest:169-174, 209-218 |
| ⑥ 权限生命周期 | 「persist across reboots and in-place app updates … lost on uninstall」——跨重启/就地更新保留，卸载即失（与 05 文档 §2.4 的 development 通道语义互证） | backends.html「Permission lifetime」 |

### 6.2 五点诊断的具体实现（[官方源码] AvfDiagnostics.kt 305 行全文实读）

| 探针点 | 实现 | 失败行为 |
|---|---|---|
| 1. feature | `pm.hasSystemFeature("android.software.virtualization_framework")` | false → smoke 直接 skip |
| 2. 双权限 | `pm.checkPermission(PERM, packageName) == PERMISSION_GRANTED`（MANAGE 与 USE_CUSTOM 分列） | 缺哪条报哪条，skip 文案带修复命令 |
| 3. `/apex/com.android.virt` | `File("/apex/com.android.virt/bin").exists()` | 只影响报告，不拦截 |
| 4. service 可达 | 反射 `Class.forName("android.system.virtualmachine.VirtualMachineManager")` → `getSystemService(Class)` | 类不在 boot classpath（system stub 缺失）→「FAILED: … not on the boot classpath」 |
| 5. capabilities | 反射 `getCapabilities()` → 位解码（1=PROTECTED、2=NON_PROTECTED，`AvfCapabilities.decode` 输出 `PROTECTED`/`NON_PROTECTED`/`none/unknown`/裸 hex） | caps=0 → `Unknown`：try non-protected 让异常说话 |

附加三点（超出工单「五点」的部分，一并记录）：**custom-VM builder API 探测**（`VirtualMachineCustomImageConfig$Builder` 的 `setKernelPath/setInitrdPath` + `setCustomImageConfig` 三方法存在性——「A vendor build that ships AVF for system use but omits the custom-image config will return false, so EngineHolder.pick() can fall back to QEMU at selection time instead of erroring at VM start」，runCatching 不抛）；**GPU config API 存在性 + 上次调用结果四态**（ATTACHED/UNSUPPORTED benign/FAILED）；**真实 smoke test**（Alpine kernel+initrd，gzip vmlinuz 先解 raw——crosvm 要 `ARM\x64` magic，「invalid magic number」坑已写进注释；256 MB；getOrCreate→create 兜底；protected-only 抛异常被转译为预期分支）。

**反射工程手法**（轨道 B 直接可鉴）：@SystemApi 不在公开 SDK——全部 `android.system.virtualmachine.*` 访问走反射（Class.forName + getDeclaredMethod + setAccessible），普通 Gradle 构建可编译，无 pKVM 设备上探测降级为「not available」不崩（AvfDiagnostics.kt:5-18 头注）。

### 6.3 产品语言对照（对齐 05 文档 §2.5 三词纪律）

Podroid 文档的可取处：把「非保护 VM 支持」写成可证伪条件（「pKVM can operate in two modes … Podroid's guest image … does not meet the signing and measurement requirements for protected VMs」）；把失败写为预期分支而非故障；权限生命周期三态（保留/失效/重授）逐条明说。需避开处：其「near-native speed」（对比表）属不可证伪措辞，本项目按 ACR §4.3 禁用。

---

## 7. 对 2026-11-05 缺口清单的回填建议

> 本节是**增补建议**，不改动 docs/research/2026-11-05-pvm-fabric-route.md 本身；按 05 文档 §4 格式给出可直接粘贴条目。**标注：待协调会话/用户裁定后并入**。引用证据均出自本文 §6 及其来源（访问日期 2026-10-31）。

**并入 D-5（轨道 B 五点能力诊断与权限引导文案）——增补实现细节**：

> D-5（增补，来源：Podroid AvfDiagnostics.kt / backends.html，[官方源码]+[官方文档]）：五点探针的最小实现面已核实——① `pm.hasSystemFeature("android.software.virtualization_framework")`；② 两条权限分别 `checkPermission`（MANAGE_VIRTUAL_MACHINE 与 USE_CUSTOM_VIRTUAL_MACHINE 分列显示）；③ `File("/apex/com.android.virt/bin").exists()`；④ 反射加载 VirtualMachineManager 并 `getSystemService`；⑤ 反射 `getCapabilities()` 位解码（1=PROTECTED / 2=NON_PROTECTED）。**建议追加第 6 点**：`VirtualMachineCustomImageConfig$Builder` 三方法存在性探测（setKernelPath/setInitrdPath + setCustomImageConfig）——厂商 ROM 可能「有 AVF 服务但无 custom config」，该探针决定选路降级发生在 backend 选择时而非 VM 启动时报错。失败文案要求：每条 skip 信息自带修复命令（`adb shell pm grant <pkg> <perm>`），protected-only 判定为「expected, not a failure」并自动回落，禁止写成故障。@SystemApi 全反射访问，普通 Gradle 构建可编译、无 AVF 设备探测降级不崩。

**并入 D-6（轨道 B 自定义 VM 配置模板与 grant 流程说明）——增补可用模板与流程**：

> D-6（增补，来源：Podroid runSmokeTest/buildCustomImageConfig，[官方源码]）：custom config 最小模板已实证可工作：`VirtualMachineCustomImageConfig.Builder.setName(<n>).setKernelPath(<raw Image>).setInitrdPath(<initrd>)` + 可选 `addParams("console=hvc0 panic=1")`；外层 `VirtualMachineConfig.Builder(context).setCustomImageConfig(cfg).setProtectedVm(false).setMemoryBytes(256MB)`；获取实例 `getOrCreate(name, cfg)` 失败回退 `create(name, cfg)`；冒烟验收 = run 后 1.5 s 内 stop+delete。**内核格式坑**：crosvm 只吃 raw ARM64 Image（0x38 偏移 `ARM\x64` magic），gzip vmlinuz 必须先解压（缓存 .raw 按 mtime 失效）。grant 流程文案双通道：adb 两条 `pm grant` + 无 PC 的 Shizuku rish 等价命令；权限生命周期三态入文案（跨重启/就地更新保留、卸载即失）。诊断入口建议放 Settings 深层并命名为「诊断」（Podroid 放 About → AVF diagnostic）。

**新发现缺口（建议新增 D-7，未在 05 文档 §4 中）**：

> D-7（新增建议）：轨道 B 的启动器地址空间参数面——um-arm64 树支持 `uml_host_top` 启动参数（lakitu 0064/0071 补丁：地址空间顶部由参数给定、接受 `0x` 前缀；**拒绝 0x 前缀会静默回退到栈猜测并导致 bionic_tls 死**），且 lakitu 实测「kernel image 增长越过 app-side loader base」需 rebase（GUEST-FEATURES.md Maintenance 节）。若轨道 B 的自有内核走 UML 内核形态（而非 pVM），自定义内核的装载基址/`uml_host_top` 需进启动器设计面；纯设计可定，验证需设备窗口。来源：lakitu12/linux-um-arm64-gki patches/0064、0071 + docs/GUEST-FEATURES.md，[官方源码]。

---

## 8. 设备窗口清单增量（本批次无设备，全部 not-tested）

| 编号 | 窗口项 | 方法要点 | 关联 |
|---|---|---|---|
| W-061 | 16K 宿主上现有 16K guest 内核（Raymer v0.3.0 UP/SMP）首启存活 | `getconf PAGESIZE`=16384、console 无 `check_page_size` fatal、mem 对照采样（单夹具） | G2（§3.3 G2-1/G2-4） |
| W-062 | 4K 对齐 stub 在 16K 宿主 exec 行为；`-z max-page-size=16384` 重链版对照 | exec 存活 + 功能等价（对齐 checklist 坑③窗口项） | G2-2 |
| W-063 | `uml_host_top` 在目标宿主的必要性与默认栈猜测相容性 | 不传参启动 → 观察 bionic_tls 症状；传参对照 | G2-3 / D-7 |
| W-064 | G-长驻门脚本实跑（App 域）：门 1 循环 20 轮 + 门 2 驻留 60 min（30 s 采样）+ 门 3 kill 重启 | §4.4 设计的三门；App 域 preflight 清单全过才出证据 | G-长驻门 |
| W-065 | 启动器失败注入矩阵（对齐 §1.6 对策 4）：banner 超时/取消/上游协程取消 → teardown → 立即重启 + F_GETLK 试锁报告 | checklist 坑②窗口项的扩展（取消路径新增） | 启动器验收 |

（既有窗口项 W-1～W-4、W-FAB-01～06 不变，本表仅增量。）

---

## 9. 来源清单（全部访问日期 2026-10-31；取证方式除注明外均为 GitHub API / raw.githubusercontent 直连实读）

**Haven / uml-transport**
- https://api.github.com/repos/GlassHaven/Haven （元数据、AGPL-3.0 license 字段）
- https://raw.githubusercontent.com/GlassHaven/Haven/main/core/local/src/main/kotlin/sh/haven/core/local/uml/UmlGuestManager.kt （548 行全文）
- https://raw.githubusercontent.com/GlassHaven/Haven/main/core/local/src/main/kotlin/sh/haven/core/local/uml/UmlDesktopManager.kt （1164 行全文）
- https://raw.githubusercontent.com/GlassHaven/Haven/main/app/src/main/kotlin/sh/haven/app/usb/UmlRecoveryManager.kt
- https://raw.githubusercontent.com/GlassHaven/Haven/main/core/local/src/main/kotlin/sh/haven/core/local/LocalSession.kt
- https://raw.githubusercontent.com/GlassHaven/Haven/main/core/local/src/main/cpp/uml_net.c
- https://raw.githubusercontent.com/GlassHaven/Haven/main/docs/features/uml-guest.md + docs/plans/uml-dm.md
- https://api.github.com/repos/GlassHaven/Haven/issues/686 （+ /comments；v5.89.24 / v5.89.37 修复追评）
- https://api.github.com/repos/GlassHaven/Haven/releases/tags/v5.89.8 、/tags/v5.91.3
- https://api.github.com/repos/GlassHaven/Haven/releases?per_page=100 （版本轨迹 5.89.x→5.91.3）
- https://api.github.com/users/GlassHaven/repos （org 仓库清单——uml-transport 不在 org 下）
- https://api.github.com/repos/GlassOnTin/uml-transport + /git/trees/main?recursive=1 + /license （GPL-2.0）
- https://raw.githubusercontent.com/GlassOnTin/uml-transport/main/README.md （239 行全文：pin sha256、坑①机制、三补丁、passt patch、GPL 节）
- https://raw.githubusercontent.com/GlassOnTin/uml-transport/main/tools/um-arm64/harness/check-app-seccomp.sh （103 行全文）
- https://raw.githubusercontent.com/GlassOnTin/uml-transport/main/android-app-compat.patch + stub-execve-fallback.patch
- https://raw.githubusercontent.com/GlassHaven/Haven/main/core/local/fetch-uml.sh （110 行全文：pin/sha256/strip/F-Droid skip）

**lakitu12**
- https://api.github.com/repos/lakitu12/linux-um-arm64-gki + /git/trees/main?recursive=1 （75 patch 全列表）
- https://raw.githubusercontent.com/lakitu12/linux-um-arm64-gki/main/README.md + docs/PORT-STATUS.md + docs/fixlog.md + docs/EXCLUDED-SMP.md
- patches/0021、0022、0025、0037、0064（raw 单文件）
- https://raw.githubusercontent.com/zalexdev/linux-um-arm64/um-arm64/arch/um/os-Linux/start_up.c （check_page_size :977-996、os_early_checks :998）
- https://raw.githubusercontent.com/torvalds/linux/master/arch/um/os-Linux/start_up.c （mainline 对照 :463）+ arch/um/kernel/um_arch.c:427

**uml4termux**
- https://api.github.com/repos/michaelamoetoevsky/uml4termux/git/trees/HEAD?recursive=1
- https://raw.githubusercontent.com/michaelamoetoevsky/uml4termux/HEAD/soak/soak.sh （229 行全文）+ soak/summarise.py（87 行全文）+ soak/guest/tick.sh
- evidence/20261026-soak-20261026.json + evidence/20261030-soak-b1.json + evidence/20261030-soak-b2.json + evidence/20261030-soak-b3-restart.json + evidence/README.md
- https://raw.githubusercontent.com/michaelamoetoevsky/uml4termux/HEAD/README.md + CHANGELOG.md + docs/KNOWN-LIMITS.md（10047s 检索无果）
- https://api.github.com/repos/michaelamoetoevsky/uml4termux/releases/tags/v0.5.0

**drolin / Stryker**
- https://api.github.com/repos/fahrulseptiana/drolin/git/trees/HEAD?recursive=1 （kernel/patches 16 笔、LICENSING.md、android/）
- https://raw.githubusercontent.com/fahrulseptiana/drolin/HEAD/LICENSING.md + android/build-apk.sh（183 行全文）+ kernel/latest-stable.env + kernel/fetch.sh
- https://api.github.com/repos/fahrulseptiana/drolin/releases （v0.1.0 assets：APK 113,726,671 B / rootfs 47,950,488 B / SHA256SUMS）
- https://api.github.com/repos/zalexdev/strykerapp + /releases?per_page=10 （6.5、6.5.1、rootless-650、rootless-main 资产清单）
- https://api.github.com/repos/Raymer8639/linux-um-arm64 + /users/Raymer8639/repos （fork 链：parent=zalexdev/linux-um-arm64）

**Podroid**
- https://api.github.com/repos/ExTV/Podroid （GPL-2.0，3685★）+ /git/trees/main?recursive=1
- https://raw.githubusercontent.com/ExTV/Podroid/main/app/src/main/java/com/excp/podroid/engine/avf/AvfDiagnostics.kt （305 行全文）+ AvfCapabilities.kt + util/AppPermissions.kt + engine/avf/AvfReasonCodes.kt + ui/components/PermissionRows.kt
- https://extv.github.io/Podroid/ + https://extv.github.io/Podroid/guide/backends.html （grant 三步、Auto 四条件、权限生命周期、protected-only 边界）

**批次 1 交付（引用不重做）**
- docs/research/2026-11-01-uml-kernel-reproducible-build.md；research/uml-kernel-build/checklist-haven-three-pits.md
- docs/research/2026-11-02-platform-policy-matrix.md（§3/§4 裁决引用）；docs/research/2026-11-05-pvm-fabric-route.md（§2.4/§4 缺口引用）；docs/research/2026-10-31-uml-full-spectrum-research.zh-CN.md §2.2（基线对照）

---

## 10. 未验证项与限制

- **本批次无设备**：§3.3 G2 预案、§4.4 G-长驻门设计、§1.6 启动器对策全部为设计文本；真机项入 §8，一律 not-tested。
- lakitu CI run 编号与 sha256、真机 marble docker 记录均为其 README/PORT-STATUS 自述 [社区(项目官方自述)]，未独立复核 CI 日志。
- uml4termux 全部证据为项目自产证据链（JSON+日志+脚本同仓），未独立复现；其 Termux 域结论不可平移至 App 域。
- 「10047s」出处未找到（§4.1）[未找到证据]；推断为 b1 wall-clock（10044 s）的引用口径漂移，建议引用时改用证据 JSON 原值。
- Haven 的坑①机制表述（zygote seccomp、force_sig_info、seccomp 先于 syscall-entry stop、personality 走 SECCOMP_RET_KILL）来自其补丁注释与工具 docstring [官方源码(注释自述)]——内核 seccomp 评估顺序的独立源码核实归工单 03（seccomp/SELinux 归因），本票不重复。
- PORT-STATUS 内部矛盾一处如实记录：4K 变体节称 stub「与 16K 版逐字节相同」但其自列 sha（4972b0e3…）≠ 16K 版（83f51f7c…）——以 sha 数字（[官方源码]）为准，「逐字节相同」句不采信。
- 停止条件：**未命中**。全部必读文件在位，全部目标仓库可达，未出现「仓库不可达或关键文件缺失」。
- 本文档由 fabric-batch2-C-downstream 产出；不执行任何 git add/commit/push，入库由用户/协调会话处理。

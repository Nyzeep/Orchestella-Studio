# U3 调研：API 35 bionic seccomp 策略、Linux 5.10 宿主 syscall 面与 UML 上游动向（R1）

- 日期：2026-11-12
- 分支：`feat/uml-research-r1`（base：`8fa035e2`）
- 性质：联网调研报告，为 U3 seccomp 设备矩阵与 API 35 profile 生成提供输入；不改产品代码、不合并 main、不改版本号/签名
- 敏感口径：全文只用设备别名（device-a = API 36/内核 6.6 真机，device-b = API 35/内核 5.10.236/arm64/4K 页真机）；无序列号/IMEI/真实 IP/本地用户路径
- 证据口径：每条结论标注来源与置信度（**高** / **中** / **待验证**）。AOSP googlesource 域名在本调研环境不可直连，bionic 内容经 `aosp-mirror` GitHub 官方镜像 + 仓库内 vendored 副本（2026-11-10 拉取、逐文件 sha256 入库）双通道核实；canonical 链接仍以 googlesource 为准。

## 0. 结论一览

| # | 结论 | 置信度 | 关键依据 |
| --- | --- | --- | --- |
| C1 | **API 36 相比 API 35 的 app seccomp 面只增不减：新增 `landlock_add_rule`/`landlock_create_ruleset`/`landlock_restrict_self`（ALLOWLIST_COMMON）与 `mseal`（SYSCALLS.TXT，lp64）共 4 个，arm64/x86_64 完全一致；无任何移除** | 高 | 本地 vendored 双分支逐行 diff + 生成 profile 集合差（见 §2.3） |
| C2 | **API 35 该锁 `android15-release` 分支**；`refs.json` 已含 `{"id":"android15","bionic_ref":"android15-release","api":35}`，`aosp-app-android15-arm64.json` 已生成（216 allowed / 0 unmapped）。任务书"无 35"前提与仓库实际不符，**35 profile 已存在且经本次复核** | 高 | `tools/research/uml-seccomp/profile/refs.json` + `profile/generated/`（§2.5） |
| C3 | API 35 filter 是 API 36 filter 的**子集**（36 = 35 ∪ {landlock×3, mseal}）→ device-a（36 域）的启动级 SECCOMP 证据（零 SIGSYS）**可单调迁移**到 device-b（35 域）；U3 12-case 仍须 device-b 实测 | 高 | §2.3 集合差 + §5 矩阵预期 |
| C4 | **主线 UML 的 `seccomp=<on/auto/off>` 与 `ncpus=`（SMP）已是上游特性**：seccomp 模式 v6.16（2025-06-02 合入）、SMP **v6.19**（commit `1e4ee5135d`，2025-10-27 落树；GitHub compare 实证 v6.19 contains、v6.18 不含）。本地 harness README"upstream 6.12 uniprocessor、无 seccomp="的说法已过时 | 高 | torvalds/linux commit 与 tag 包含关系（§4.1） |
| C5 | 主线 UML seccomp 快路径的宿主依赖面 = `seccomp(2)`（≥3.17）+ `close_range`（**≥5.9**）+ SIGSYS/sigaltstack/CLONE_VM\|CLONE_VFORK —— device-b 5.10.236 **全部满足**，预期 `seccomp=on/auto` 落 SECCOMP；`ncpus>1` 强依赖 seccomp 模式（ptrace 模式不支持 SMP） | 高 | 主线 `arch/um/os-Linux/start_up.c` 一手代码（§3.2、§4.1） |
| C6 | 5.10 相对 6.6 的真实缺口：`epoll_pwait2`(5.11)、`landlock`(5.13)、`futex_waitv`(5.16)、`mseal`(6.10) 缺失；但 API 35 profile 不含这些（bionic 不暴露 futex_waitv/io_uring/rseq；mseal/landlock 是 36+ 面），bionic 构建的 UML 内核不调用 → 预期无影响，标待实测 | 高（版本号）/ 待实测（影响） | man7 手页 + bionic SYSCALLS.TXT（§3.1） |
| C7 | **Android 15 起 app 域被 SELinux 禁 io_uring**（anon_inode/iouring 仅系统域经 `io_uring_use()` 宏显式放行）；主线 UML `arch/um/` 零引用 io_uring → 对 U3 矩阵无影响 | 高 | sepolicy 一手文本 + torvalds 代码搜索（§3.4） |
| C8 | bionic 运行时陷阱：API 35 的 `epoll_wait()` 走旧 `__epoll_pwait`（5.10 可用），但公开 API `epoll_pwait2()` **无 ENOSYS 回退**（5.11+ 才有该 syscall）→ UML 内核/launcher 侧禁止调用 `epoll_pwait2` | 高 | bionic `libc/bionic/sys_epoll.cpp`（android15-release）一手代码（§2.4） |
| C9 | arm64 UML **不在主线**（`arch/arm64/um` 不存在，defconfig 仅 i386/x86_64）；下游 fork `Raymer8639/linux-um-arm64` v0.3.0 = 7.2-rc4 基线（含 seccomp+ SMP 上游特性 + arm64 移植），**v0.4.0 已于 2026-11-07 升到稳定版 v7.3.1**（pin 变更决策留给 U5） | 高 | torvalds 树 + fork GitHub（§4.2/§4.3） |
| C10 | 矩阵 12-case 在 device-b 的预期：boot 类/clone/sigchld/ncpus/seccomp-fastpath 预期 PASS（证据链见 §5）；vector-net/mconsole/mount/personality/setrlimit 标"预期 PASS、待实测"；**不得用 device-a 旁路数据冒充 device-b PASS** | 高（方法论）/ 待实测（结果） | §5 |

## 1. 调研方法与来源通道

| 通道 | 用途 | 可达性 |
| --- | --- | --- |
| 本地 vendored AOSP 副本（`profile/vendor/<ref>/`，sha256 入库） | API 34/35/36/37/main 策略文件逐行 diff、生成 profile 集合差 | 直接 |
| `aosp-mirror/platform_bionic`（GitHub 官方镜像）raw 文件 + commits API | 核实 vendored 副本与上游一致、定位 mseal/landlock/clone3/epoll_pwait2 的 commit 轨迹 | 可达 |
| `torvalds/linux` GitHub 镜像（contents/commits/compare API） | 主线 UML 代码、commit/tag 包含关系、arm64 UML 存在性 | 可达 |
| `man7.org` man-pages、kernelnewbies.org | Linux syscall 版本面一手出处 | 可达 |
| `aosp-mirror/platform_system_sepolicy` 不存在 → 用 GrapheneOS 官方 AOSP 镜像（branch `17`）读 sepolicy 一手文本 | io_uring 的 app 域 SELinux 管制 | 可达（分支仅 17） |
| `android.googlesource.com` / `cs.android.com` / `source.android.com` | canonical 引用 | **本环境不可直连**（超时），文中链接仍按 canonical 给出 |

## 2. 章一：Android 15 (API 35) 与 Android 16 (API 36) bionic seccomp 策略差异

### 2.1 生成机制（一手代码核实）

- 组合公式：`(SYSCALLS.TXT − SECCOMP_BLOCKLIST_COMMON − SECCOMP_BLOCKLIST_APP) ∪ SECCOMP_ALLOWLIST_COMMON ∪ SECCOMP_ALLOWLIST_APP`，按架构展开（`all`/`lp64`/`lp32`）。`gen_profile.py` 的 `build_profile()` 与 bionic `libc/tools/genseccomp.py` 的 `merge_names()`（`(base_names - blocklist_names) | allowlist_names`）逐条对应。来源：genseccomp.py（android16-release）+ 本地 `tools/research/uml-seccomp/gen_profile.py`。置信度：**高**。
- 默认动作：未命中 → `Disallow()` = `BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_TRAP)`（SIGSYS），**不是 KILL**；安装用 `prctl(PR_SET_NO_NEW_PRIVS)` + `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER)`。来源：`libc/seccomp/seccomp_policy.cpp`（android16-release）一手引文。置信度：**高**。
- 无参数过滤：生成器只比较 syscall 号，`(args)` 文本被忽略；`priority.txt` 只是把高频号放到 BPF 树前部加速。来源：genseccomp.py。置信度：**高**。

### 2.2 关键文件与 commit 轨迹（API 35→36 差异来源）

| 变化 | 文件 | commit（aosp-mirror 提交号） | 日期 | 动机/备注 |
| --- | --- | --- | --- | --- |
| `mseal(void*, size_t, unsigned long) lp64` 进入 SYSCALLS.TXT（base，自动入 filter） | `libc/SYSCALLS.TXT` | `b3904ee5b9` "Add mseal(2) wrapper." | 2024-08-13 | mseal 是 Linux 6.10 新 syscall（§3.1）；bionic 加 wrapper 后进入 API 36 面 |
| `landlock_add_rule` / `landlock_create_ruleset` / `landlock_restrict_self` 加入 ALLOWLIST_COMMON | `libc/SECCOMP_ALLOWLIST_COMMON.TXT` | `ba03012434` "Allow Landlock Syscalls" | 2025-03-24 | 提交说明：为 Chrome（Clank）GPU 进程在更强沙箱下运行；"all they allow a caller to do is to further restrict its capabilities"；b/399173273；**无需 libc wrapper** |
| （对照）`clone3` 放行 | `libc/SECCOMP_ALLOWLIST_COMMON.TXT` | `b65dec5629` "Punch a hole for clone3(2) use." | 2023-09-05 | 落在 android15-release（API 35），android14-release（API 34）没有 —— 本地 14→15 集合差 `+clone3, +epoll_pwait2` 与之吻合 |
| （对照）`epoll_pwait2` wrapper | `libc/SYSCALLS.TXT` | `4c62e59fe6` "Add epoll_pwait2()." | 2023-08-23 | 同上，API 35 新面 |
| （对照）列表格式改造（去返回类型） | SYSCALLS/ALLOWLIST/BLOCKLIST 全部 | `83f08aa3c6`→revert `979e10e409`→reland `6de2a8bf83` | 2025-02 | 15 与 16 的 vendored diff 里大量"+/-"实为**纯格式/注释改写**，语义等价；`gen_profile.py` 的 `parse_spec_line` 两种格式都兼容（已验证 15/16 均 0 unmapped） |

置信度：**高**（vendored sha256 + 镜像 commits API 双确认）。canonical 链接格式：`https://android.googlesource.com/platform/bionic/+/b3904ee5b9`（mseal）、`/+/ba03012434`（landlock）、`/+/b65dec5629`（clone3）、`/+/4c62e59fe6`（epoll_pwait2）。

### 2.3 生成面集合差（本地真值，arm64 与 x86_64 一致）

`profile/generated/` 逐 JSON 比较（`python -I` 脚本，2026-11-12）：

| 相邻 ref（API） | 新增 | 移除 |
| --- | --- | --- |
| android14 (34) → android15 (35) | `clone3`、`epoll_pwait2` | 无 |
| **android15 (35) → android16 (36)** | **`landlock_add_rule`、`landlock_create_ruleset`、`landlock_restrict_self`、`mseal`** | **无** |
| android16 (36) → android17 (37) | `faccessat2`、`fchmodat2`、`getxattrat`、`listxattrat`、`openat2`、`removexattrat`、`setxattrat` | 无 |

counts（arm64）：android15 = base 216 / blocked 24 / added 24 / allowed 216 / mapped 216 / unmapped 0；android16 = base 219 / allowed 220 / unmapped 0。BLOCKLIST_COMMON/APP 在 15→16 间只有 swapon/swapoff 的去返回类型格式改写，**语义零变化**。置信度：**高**。

`main` 分支异常（诚实记录）：本地 vendored main 的 ALLOWLIST_COMMON **不含** landlock 三条（上游后来在 main 上移除了该放行，理由同"Chrome GPU 进程条件性使用"注释），且 SYSCALLS.TXT/ALLOWLIST 里 grep 不到 `openat2`/`faccessat2`/`fchmodat2`/`*xattrat`（android17 面）→ main 生成的 profile 比 android17 还窄（main 相对 android17 少 10 个名字）。**生成任何版本 profile 时不得用 `main` 充当已发布 API**；main 异常的确切成因（后续重构 vs 临时回退）**待验证**，不影响 35/36 结论。

### 2.4 运行时行为（对 device-b 直接要害的一条）

bionic `libc/bionic/sys_epoll.cpp`（android15-release，一手全文已读）：

- `epoll_wait()` → `epoll_pwait64()` → **`__epoll_pwait`**（Linux 2.6.19+，5.10 可用）；
- `epoll_pwait()` / `epoll_pwait64()` 公开 API → `__epoll_pwait2`（Linux 5.11+），**全文件无 ENOSYS 回退、无 API 级守卫**；源码注释自述它是"第一个对 32 位也假设 64-bit time_t 的 syscall"。

结论：5.10 宿主上任何调用 `epoll_pwait2` 的代码得到 `ENOSYS`（filter 允许该编号，不会 SIGSYS，只是内核返回 ENOSYS）。UML fork 内核（bionic 构建）与 guest init 均用 `epoll_wait`/`epoll_create1` 路径 → 无碍；但 P2 若在 device 侧 launcher/helper 里用 `epoll_pwait2` 会踩坑。置信度：**高**（一手代码）。

### 2.5 API 35 profile 锁定与"既有 35"勘误

- 明确答案：**`gen_profile.py` 锁 `refs/heads/android15-release`（bionic_ref=android15-release，api=35）**；syscall 号源用同一 ref 的 `libc/kernel/uapi/asm-generic/unistd_generic.h`（arm64）。`refs.json` 现有条目即为正确配置，无需改代码。
- 勘误：任务书背景"10 个既有 profile 覆盖 API 26/28/30/34/36/android17，无 35"与仓库不符。实际 10 个 = 5 refs（android14/15/16/17/main）× 2 架构，**API 35 已 vendored + 已生成**（`aosp-app-android15-arm64.json` / `aosp-app-android15-x86_64.json`），本次逐项复核 counts 与来源 sha256 无漂移（`--check` 语义等价复核）。仓库内不存在任何 API 26/28/30 profile（全仓 grep 无命中）。P2 直接复用现有 android15 profile；若 U3 文案要"生成 API 35"，实际动作是 **`--generate` + `--check` 复跑并把该 profile 写进 device-b 矩阵 runner**。
- 置信度：**高**（本地文件系统证据）。

## 3. 章二：Linux 5.10 vs 6.6 对 UML userspace 关键 syscall 的差异面

### 3.1 版本面速查（一手 man-pages / kernelnewbies）

| syscall / 特性 | 首版内核 | 5.10.236 | 6.6 | 对 UML 的意义 | 来源 |
| --- | --- | --- | --- | --- | --- |
| `seccomp(2)`/BPF filter | 3.17 | 有 | 有 | UML seccomp 模式前提 | man7 seccomp.2（HISTORY: Linux 3.17） |
| `close_range` | 5.9 | **有** | 有 | 主线 UML seccomp probe 与 stub **硬依赖** | man7 close_range.2（HISTORY: 5.9） |
| `clone3` | 5.3 | 有 | 有 | bionic 不用 clone3（pthread_create 用 `clone`），API 35 起放行 clone3 | man7 clone.2；bionic ALLOWLIST |
| `clone` / `CLONE_VM\|CLONE_VFORK` | 古老 | 有 | 有 | UML probe/线程 | 同上 |
| `statx` | 4.11 | 有 | 有 | bionic 面（35 profile nr 291） | man7 statx.2 |
| `membarrier` | 4.3（expedited 4.16） | 有 | 有 | bionic 面；UML 不直接用 | man7 membarrier.2 |
| `process_madvise` | 5.10 | 有（恰好） | 有 | bionic 面（nr 440） | man7 process_madvise.2 |
| `io_uring` | 5.1 | 有（内核） | 有 | **app 域被 SELinux 禁（C7）**；UML 不用 | man7 io_uring_setup.2 |
| `epoll_pwait2` | **5.11** | **无** | 有 | bionic 无回退（§2.4） | man7 epoll_wait.2（HISTORY: epoll_pwait2 5.11） |
| Landlock LSM | **5.13** | **无** | 有 | API 36 面；35 不含 | man7 landlock.7 |
| `futex_waitv` | **5.16** | **无** | 有 | bionic 不暴露；UML 客体内 futex 自实现，不经宿主 | man7 futex_waitv.2 |
| `mseal` | **6.10** | **无** | 无（6.10+ 才有） | API 36 面；35 不含 | kernelnewbies Linux_6.10 |
| `rseq` | 4.18 | 有 | 有 | bionic 不发 rseq（host-shims 注释）；glibc 才发 | kernelnewbies Linux_4.18 |
| `memfd_create` | 3.17 | 有 | 有 | UML 物理内存映射面 | man-pages |
| `execveat` | 3.19 | 有 | 有 | UML stub 启动（主线 32e8eaf263） | man-pages |

置信度：版本号列**高**（每行一手出处）；"对 UML 的意义"列**高/中**（依据主线代码与 fork README，个别标中）。

### 3.2 主线 UML seccomp 模式的宿主依赖面（一手代码）

`arch/um/os-Linux/start_up.c`（torvalds master，2026-11-12 读取）：

- `seccomp_helper()`：BPF 只放行 `clock_nanosleep`，其余 `SECCOMP_RET_TRAP`；注释明写 **"close_range is needed for the stub"**，`close_range(1, ~0U, 0)` 失败即 `exit(1)` → probe 失败 → 回退 ptrace；
- probe 流程：`mmap(MAP_SHARED|MAP_ANON)` → `clone(CLONE_VFORK|CLONE_VM)` → `sigaltstack` + `sigaction(SIGSYS)` → `prctl(PR_SET_NO_NEW_PRIVS)` → `seccomp(SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC)` → SIGSYS 处理器里 `exit(0)`；
- 启动参数 `seccomp=<on/auto/off>`（`__uml_setup`）：`on`=强制（失败即 fatal）、`auto`=探测回退 ptrace、`off`=ptrace；
- `os_early_checks()`：`ncpus>1` 且 seccomp 不可用时 **fatal："SMP is not supported with PTRACE userspace."**

→ device-b（5.10.236）满足全部依赖（close_range≥5.9 在列），预期与 device-a 一致落 SECCOMP 模式。与本地 README"host 5.4 无 close_range → ptrace"的表述互证（5.4 < 5.9 才回退）。置信度：**高**。

### 3.3 device-b（5.10 宿主）UML arm64 启动预期风险清单

| 级别 | 风险项 | 分析 | 预期 |
| --- | --- | --- | --- |
| P0（预期无碍，但须实测确认） | `epoll_pwait2` ENOSYS（5.10 无） | bionic `epoll_wait` 不走它；fork 内核 os-Linux 用 `epoll_create1/epoll_ctl/epoll_wait`；API 35 filter 放行该号 → 不 SIGSYS | 无碍；**待实测**（boot 全程无 `UM_INIT_ERROR` 即证） |
| P0 | `close_range` 缺失 → seccomp 回退 ptrace | 5.10 ≥ 5.9，**存在** | 无碍；boot console 应见 seccomp 探测 OK（fork 版 `Userspace mode: SECCOMP`） |
| P1 | `futex_waitv`（5.16+）缺失 | bionic 不发；guest futex 是 UML 内部实现不经宿主 futex_waitv；已知消费方是 Wine/Proton 类运行时 | 无碍；若未来 glibc host-proxy 车道上新 glibc 需补 shim（glibc 是否已用 futex_waitv **待验证**，未找到可靠出处） |
| P1 | `landlock`(5.13)/`mseal`(6.10) 内核缺失 | API 35 filter 不含两者；bionic 35 不调用；36 面上的 landlock/mseal 只在 API 36 域有意义 | API 35 无影响；**将来上 API 36+ 设备也不受 5.10 影响**（filter 放行 ≠ 内核有） |
| P1 | io_uring 被 SELinux 禁（Android 15 起 app 域） | 主线 UML `arch/um/` **零引用** io_uring（GitHub 代码搜索 0 命中）；ubd/vector 不依赖 | 无影响；**禁止**把"io_uring 可用"列为 U3 能力项 |
| P2 | `mount/umount2` 不在 API 35 filter | UML 客体内 mount 是 UML 内部 VFS 操作，不落宿主 mount(2)；这正是 U3 `mount` case 的设计（guest mount ok，host mount 被 TRAP） | 按 case 设计走 |
| P2 | `epoll_wait`/`epoll_create`/`set_robust_list`/`rseq`/`access`/`mkdir`/`rmdir`/`unlink`/`poll` | arm64+bionic 下 bionic 不发（或改发 *at/epoll_pwait 变体）；这些名字只在 **x86_64 glibc host-shim** 里需要（`profile/host-shims/glibc-x86_64.txt` 已列） | 仅宿主代理车道相关，device-b 不涉及 |
| P2 | HONOR/5.10 厂商内核补丁（GKI android13/14/15-5.10 分支）对 seccomp/ptrace/anon_inode 的定制收紧 | 无法联网核实厂商配置 | **待实测**；device-a（6.6）已证 SECCOMP 可用，device-b 需自证 |
| P2 | `init_module/delete_module` 被 blocklist 禁 | fork 的"loadable modules"是 **guest 内**模块加载（UML 内部），不落宿主 | 无碍 |

### 3.4 io_uring 管制的一手依据（补充 C7）

- sepolicy 模型（GrapheneOS 官方 AOSP 镜像，branch `17`，一手文本）：`public/te_macros` 定义 `io_uring_use(domain)` 宏——只有显式调用的域（logd/statsd/snapuserd/fastbootd 等）拿到 `anon_inode create/map/read/write` 与 `self:io_uring { sqpoll allowed }`；app 域侧 `private/app_neverallows.te` 有 `neverallow all_untrusted_apps domain:anon_inode *;`（注释点名 userfaultfd/io_uring anon_inode）。
- 背景：Google 报告 2022 年其漏洞赏金里约 60% 内核提权利用 io_uring（Upwind 等二手汇总）；Black Hat USA 2023 "Bad io_uring"（Z. Lin）是公认催化事件；Android 15（2024 年政策落地）起 app 域整体禁用。
- 置信度：**高**（政策面）；60% 数字为二手转述，标**中**。

## 4. 章三：UML 上游 2025-2026 动向

### 4.1 主线（git.kernel.org/uml/linux → torvalds/linux，经 GitHub 镜像核实）

| 事项 | 事实 | commit / 出处 | 置信度 |
| --- | --- | --- | --- |
| **seccomp 模式合入** | 2025-06-02 四连：`dac494bf54`（stub 侧 SECCOMP/futex 进程处理）、`406d17c6c3`（内核侧）、`8420e08fe3`（SECCOMP 模式跟踪子进程死亡）、`beddc9fb1c`（探测与初始化）+ `942349413a`（06-04，32 位 xstate 恢复修复）→ **v6.16 首发**（GitHub compare：v6.16 contains，behind） | torvalds commits API | 高 |
| `seccomp=` 启动参数 | 主线原生 `__uml_setup("seccomp=", ...)`：on/auto/off（§3.2 引文）——**"seccomp=on/auto 是下游专属"的旧结论作废** | arch/um/os-Linux/start_up.c | 高 |
| **SMP 合入** | `1e4ee5135d` "um: Add initial SMP support"（2025-10-27，Tiwei Bie/Ant Group，Johannes Berg 收）→ **v6.19 首发**（compare 实证：v6.17 ahead、v6.18 diverged、**v6.19 behind**；commit 日期在 v6.18-rc1 窗口边界但实际晚一步合入，版本归属以 tag containment 为准）。每 vCPU = 一个宿主线程；含 per-CPU timer（"CPU#%d failed to setup timer"）、IPI（`os_send_ipi`）、`ncpus=` 参数（arch/um/kernel/smp.c，clamp 到 NR_CPUS≤64）；限制：同一进程的用户态线程仍限单 stub；**ptrace 模式下 SMP fatal** | commit 原文 + compare/代码 | 高 |
| v7.4 窗口（2026-10-22，38 commits） | NOMMU（`!CONFIG_MMU`，多为 mm/kunit 测试用）、页权限收紧（__init 不可执行）、**perf-event 支持**、`__NR_close_range` 老宿主兜底定义、virtio_uml 动态根设备、time-travel sched_yield 修复 | merge `7cd80b8168` pull message 一手 | 高 |
| seccomp 模式后续成熟 | `656be28321`（2026-01-13）"um: Handle SIGCHLD in seccomp mode like other IRQ signals" → **v7.1 首发**（compare 实证）—— 与 U3 `sigchld` case 直接相关 | commits API | 高 |
| 当前版本 | **v7.3 已发布；v7.4-rc2 在途**（2026-11-12 tags API）；UML 子树 `uml-for-7.4` 已进 master | tags API | 高 |
| arm64 UML | **不在主线**：`arch/arm64/um` 404，defconfig 仅 i386/x86_64；官方树分支 master/next/fixes，维护者 Richard Weinberger、Anton Ivanov、Johannes Berg；UML maillist 见 patchwork 页脚（lists.infradead.org 上的 linux-um 列表）；patchwork `patchwork.ozlabs.org/project/linux-um` | 树结构 + fork `docs/UPSTREAM.md` 转录 | 高 |
| **主线 UML 不用 io_uring** | `arch/um` 路径下 `io_uring` 代码搜索 0 命中 | 代码搜索 API | 高 |

**纠错记录**：`tools/research/uml-seccomp/README.md` 的"upstream 6.12 UML is uniprocessor and has no `ncpus=`/`seccomp=`"已过时（6.12 是 2024 年版本；seccomp 6.16、SMP 6.18 合入）。建议 U3 收尾时更新该 README 表格；本报告不改它（硬约束：只写本文档）。

### 4.2 下游 fork（U3 实际车道）

- `Raymer8639/linux-um-arm64`（Android/Termux arm64 下游移植；官方树 x86 中心，arm64 是下游工作）：
  - **v0.3.0** = uml/next 7.2-rc4（`1590cf0329`），2026-10-03 起移植——U3/U1 当前 pin（wave2 报告逐字节复核）；
  - **v0.4.0** = 稳定版 **v7.3.1**（`79a907609d` → 合入 `upgrade/kernel-v7.3.1` as `2858ca64afa3`），tag 落于 **2026-11-07**；UPSTREAM.md 记录两基线间 `arch/um/` 上游 churn 仅 10 文件、唯一冲突在 `arch/x86/um/asm/ptrace.h`。
- fork v0.3.0 一手核验：`arch/um/os-Linux/start_up.c`（1023 行）含 `__uml_setup("seccomp=", ...)`；`arch/um/Kconfig` 含 `UML_SUBARCH_SUPPORTS_SMP`/`config SMP`；`arch/arm64/um/Kconfig` **select UML_SUBARCH_SUPPORTS_SMP** 并注释"setjmp_aarch64.S 只保存 AAPCS64 callee-saved…dmb ish 真 barrier…NR_CPUS 只是天花板，实际起多少由 ncpus= 决定，ptrace 宿主自动落 1 CPU 而不是拒启"。
- fork README 状态表（一手转录）：SECCOMP 为默认快路径、ptrace 兜底；PTRACE_SYSEMU + syscall 取消/替换；Alpine/Debian 12、4K/16K guest 页可启；dockerd+overlay2 on cgroup v2 可 `docker run hello-world`；USB/IP 直通 RTL8811AU；**无 32 位 compat**；guest vDSO 时间（免宿主 syscall clock_gettime）已写**未合入**（4K 页放不下 + 需 overflow-protect）；"No cpuset: CONFIG_CPUSETS depends on SMP and UML is uniprocessor"一句与 v0.3.0 已含 SMP 的事实**自相矛盾，判断为 README 陈旧**（arm64 Kconfig 已 select SMP；待验证：fork 默认 defconfig 是否开 CONFIG_SMP）。
- 置信度：**高**（GitHub contents API 一手）；fork 内部矛盾点**待验证**。

### 4.3 对 U3 的上游结论

1. `seccomp=on/auto` 与 `ncpus=2/4` 这两行在 cases.json 里标"downstream-only"已不符合上游现状（v6.16/v6.19 起 upstream 也有）；device 上真正"下游专属"的只剩 **arm64 移植 + bionic 构建 + PTRACE_SYSEMU 取消/替换**。
2. fork v0.4.0（7.3.1）值得下个窗口评估升级（拿到 7.3/7.4 窗口的 seccomp SIGCHLD 修复、NOMMU、perf events）；pin 变更走 U5 的 GPL/复现流程，本调研不动 pin。
3. mconsole/`UML_IPI`/per-CPU timer 均为上游代码，SMP 矩阵的失败模式应优先查 seccomp 模式是否成立（ptrace 下 ncpus>1 直接 fatal）。

## 5. 章四：API 35 profile 生成要点与 device-b 矩阵预期（P2 可执行清单）

### 5.1 生成要点（Top 5）

1. **锁 `android15-release`，不是 main、不是 android16**：`profile/refs.json` 现有 `{"id":"android15","bionic_ref":"android15-release","api":35}` 即正确；`gen_profile.py --generate && --check` 复跑应 0 drift（本次已等价复核：216 allowed / 0 unmapped / sha256 与 vendored 一致）。禁止用 `main` 充当任何已发布 API（§2.3 main 异常）。
2. **API 35 ⊆ API 36 单调性用作 sanity check**：生成后做一次集合差断言 `android16 − android15 == {landlock_add_rule, landlock_create_ruleset, landlock_restrict_self, mseal}` 且 `android15 − android16 == ∅`（arm64/x86_64 都做）。任何偏差 = vendored 副本漂移，先修数据再跑矩阵。
3. **device-b 域即 API 35 filter**：Android 15 设备上 zygote 给 app 域装的就是 35 面（与 APK targetSdk 无关）；runner 起的 UML 内核子进程继承之。矩阵 runner 直接引用 `aosp-app-android15-arm64.json`，无需为 device-b 新造 profile。
4. **bionic 运行时红线**：device 侧任何新代码（launcher/helper/guest init 的宿主侧）禁用 `epoll_pwait2()` API（5.10 ENOSYS 无回退，§2.4）；`epoll_wait`/`epoll_create1`/`__epoll_pwait` 是安全面。API 35 filter 放行了 `epoll_pwait2` 编号不会造成 SIGSYS，只会 ENOSYS——排查时先看 errno 再怀疑 filter。
5. **失败模式判读表**：SIGSYS(159) = filter 命中 TRAP（名字查 profile `syscalls` 反集）；`ENOSYS` = 内核没这个 syscall（5.10 上 `epoll_pwait2/futex_waitv/landlock/mseal` 均属此类，**不是 filter 问题**）；boot 期 fatal `"SECCOMP userspace requested but not functional!"`（主线 on 模式文案）= `close_range`/seccomp probe 失败，先 `uname -r` 确认 ≥5.9 再查厂商 SELinux。

### 5.2 device-b 12-case 矩阵预期（入口 `tools/research/uml-seccomp/fixtures/matrix/cases.json`）

| case | device-b 预期 | 依据 |
| --- | --- | --- |
| hostfs-boot / ubd0-boot | PASS（预期） | device-a 启动级 SECCOMP 全 PASS + C3 单调性；仍须实测 |
| vector-net | PASS（预期，待实测） | fd/socketpair 传输；`socketpair/sendmmsg/recvmmsg` 均在 35 面（本次核对在 allowed 集） |
| ncpus-2 / ncpus-4 | PASS（预期，待实测） | 需 SMP 内核产物；seccomp 模式成立是前置（ptrace 下 fatal）；device-a 已证 ncpus=4 |
| mconsole | PASS（预期，待实测） | AF_UNIX + `epoll_ctl` 在面内；注意 wave2 §6 失败模式 2（文件 fd 不可 poll）不适用于 unix socket |
| sigchld | PASS（预期，待实测） | 上游 656be28321（v7.x）专门处理 seccomp 模式 SIGCHLD；fork v0.3.0（7.2-rc4）已含 |
| clone | PASS（预期，待实测） | `clone`/`clone3` 均在 35 面（clone3 是 35 新增） |
| mount | 按 case 设计：guest 内 mount PASS、宿主 mount 被 TRAP | 35 面**无** mount/umount2（本次核对确认缺失） |
| personality | PASS（预期，待实测） | `personality` 在 35 面（本次核对在 allowed 集） |
| setrlimit | PASS（预期，待实测） | guest 内 probe 走 UML 内部；宿主侧对应名是 `prlimit64`（在面内），arm64 无 `setrlimit` 号 |
| seccomp-fastpath | PASS（预期，待实测） | 5.10.236 ≥ 5.9 满足 close_range 依赖（C5）；cases.json 注释"downstream-only option"建议随本轮更新为"upstream v6.16+" |

硬性纪律（沿用 wave2 §8/§9 口径）：上表全部"预期"在 device-b 实测前一律记 `unknown`/`not-tested`；device-a 数据不得冒充；`unknown` 行必须留 `device_entry`（矩阵契约）。

### 5.3 移交 P2 的注意事项汇总

- `tools/research/uml-seccomp/README.md` 两处陈述过时，建议 P2 顺手更新（"upstream 6.12 uniprocessor/no seccomp="、"seccomp-fastpath 需 host close_range 5.9+"仍对但表述可指向 v6.16/v6.18）——本报告受硬约束不改。
- fork v0.4.0（7.3.1）升级评估、`CONFIG_SMP` 在 fork 默认 defconfig 的实际状态（README 矛盾点）列为 open 项。
- glibc 是否启用 futex_waitv（影响 host-proxy 车道 shim）未找到可靠出处，**待验证**。

## 6. 证据分级

- **事实（一手）**：§2.1 生成机制代码引文；§2.2 commit 轨迹；§2.3 本地 vendored diff 与生成集合差；§2.4 sys_epoll.cpp 全文；§3.2 start_up.c 引文；§4.1 commit/tag/compare 结果；§4.2 fork contents API 内容；§5.1 本地 counts。
- **推断**：C3 单调迁移论证（集合差 + boot 级证据）；C6 影响面（基于 bionic 不调用这些 syscall）；§4.3.1 "下游专属范围收窄"的表述；fork README 矛盾的"陈旧"判断。
- **二手**：io_uring 60% 漏洞占比数字（Upwind/媒体转述）；Black Hat 2023 议题背景。
- **待验证**：main 分支 allowlist 收窄成因；glibc futex_waitv 采用状态；fork 默认 defconfig 的 CONFIG_SMP；厂商 5.10 内核对 seccomp/anon_inode 的定制；§5.2 全部"预期 PASS"。

## 7. 合规自查

- 无序列号/IMEI/Android ID/设备指纹；设备一律 device-a/device-b + 粗粒度口径（API/内核大版本/页大小/品牌代际）。
- 无真实 IP/MAC；无截图/录屏/完整 console 入库。
- 无 Windows 本地用户路径字面量；仓库内路径一律仓库相对。
- 本报告只新增本文档一个文件；不改动 `tools/`、`research/`、`app/` 等任何其他文件（README 过时项仅在此建议，未改）。

## 8. 来源列表（全部一手 URL，检索日 2026-11-12）

**AOSP bionic（canonical 在 googlesource，本环境经 aosp-mirror GitHub 核实）**

- SYSCALLS.TXT（android16-release）：https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/libc/SYSCALLS.TXT
- SECCOMP_ALLOWLIST_COMMON.TXT（android16-release / android15-release）：https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/libc/SECCOMP_ALLOWLIST_COMMON.TXT 、https://android.googlesource.com/platform/bionic/+/refs/heads/android15-release/libc/SECCOMP_ALLOWLIST_COMMON.TXT
- seccomp_policy.cpp（Disallow→TRAP、prctl 安装）：https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/libc/seccomp/seccomp_policy.cpp
- genseccomp.py（组合公式/无参数过滤/priority）：https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/libc/tools/genseccomp.py
- sys_epoll.cpp（epoll_wait→__epoll_pwait；epoll_pwait2 无回退）：https://android.googlesource.com/platform/bionic/+/refs/heads/android15-release/libc/bionic/sys_epoll.cpp
- commit：mseal wrapper `b3904ee5b9`（2024-08-13）https://android.googlesource.com/platform/bionic/+/b3904ee5b9 ；landlock allowlist `ba03012434`（2025-03-24）https://android.googlesource.com/platform/bionic/+/ba03012434 ；clone3 hole `b65dec5629`（2023-09-05）https://android.googlesource.com/platform/bionic/+/b65dec5629 ；epoll_pwait2 `4c62e59fe6`（2023-08-23）https://android.googlesource.com/platform/bionic/+/4c62e59fe6

**Linux syscall 版本（man-pages/kernelnewbies/kernel 文档）**

- close_range.2：https://man7.org/linux/man-pages/man2/close_range.2.html
- clone.2（clone3 5.3）：https://man7.org/linux/man-pages/man2/clone.2.html
- epoll_wait.2（epoll_pwait2 5.11）：https://man7.org/linux/man-pages/man2/epoll_wait.2.html
- futex_waitv.2（5.16）：https://man7.org/linux/man-pages/man2/futex_waitv.2.html
- statx.2（4.11）：https://man7.org/linux/man-pages/man2/statx.2.html
- membarrier.2（4.3/4.16）：https://man7.org/linux/man-pages/man2/membarrier.2.html
- process_madvise.2（5.10）：https://man7.org/linux/man-pages/man2/process_madvise.2.html
- seccomp.2（3.17；RET_USER_NOTIF/NEW_LISTENER 5.0）：https://man7.org/linux/man-pages/man2/seccomp.2.html
- landlock.7（5.13）：https://man7.org/linux/man-pages/man7/landlock.7.html
- io_uring_setup.2：https://man7.org/linux/man-pages/man2/io_uring_setup.2.html
- mseal（v6.10）：https://kernelnewbies.org/Linux_6.10 、https://raw.githubusercontent.com/torvalds/linux/master/Documentation/userspace-api/mseal.rst
- rseq（v6.10 前的 4.18 起源）：https://kernelnewbies.org/Linux_4.18
- io_uring（5.1 起源）：https://kernelnewbies.org/Linux_5.1

**Android 15 io_uring 管制（sepolicy 一手文本经 GrapheneOS 官方镜像）**

- te_macros（`io_uring_use` 宏）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/public/te_macros （镜像读法：https://github.com/GrapheneOS/platform_system_sepolicy 的 `public/te_macros`）
- app_neverallows.te（untrusted apps anon_inode neverallow）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te
- 背景（二手）：https://www.8ksec.io/android-selinux-internals-part-iv ；Black Hat USA 2023 "Bad io_uring: A New Era of Rooting for Android"（Zeyu Lin）

**UML 上游（经 torvalds/linux GitHub 镜像核实，canonical 为 kernel.org）**

- 官方树：https://git.kernel.org/pub/scm/linux/kernel/git/uml/linux.git （master/next/fixes；maillist 与 patchwork 见上）
- seccomp 系列：`dac494bf54`/`406d17c6c3`/`8420e08fe3`/`beddc9fb1c`（2025-06-02，v6.16）：https://github.com/torvalds/linux/commit/406d17c6c3
- SMP：`1e4ee5135d`（2025-10-27 落树，v6.19 首发）：https://github.com/torvalds/linux/commit/1e4ee5135d
- seccomp= 参数与 probe：master `arch/um/os-Linux/start_up.c`：https://github.com/torvalds/linux/blob/master/arch/um/os-Linux/start_up.c
- ncpus=/SMP 实现：master `arch/um/kernel/smp.c`：https://github.com/torvalds/linux/blob/master/arch/um/kernel/smp.c
- v7.4 pull（NOMMU/perf/close_range 兜底等）：merge `7cd80b8168`：https://github.com/torvalds/linux/commit/7cd80b8168
- SIGCHLD in seccomp mode：`656be28321`（2026-01-13）：https://github.com/torvalds/linux/commit/656be28321
- UML patchwork：https://patchwork.ozlabs.org/project/linux-um/list/

**下游 fork（U3 现行车道）**

- 仓库：https://github.com/Raymer8639/linux-um-arm64 （tag `um-arm64-v0.3.0` = 7.2-rc4 基线；`um-arm64-v0.4.0` = v7.3.1，2026-11-07）
- 上游跟踪文档：https://github.com/Raymer8639/linux-um-arm64/blob/um-arm64-v0.4.0/docs/UPSTREAM.md

**本地（first-hand，仓库内）**

- `tools/research/uml-seccomp/gen_profile.py`、`profile/refs.json`、`profile/vendor/android{14,15,16,17}-release/`、`profile/generated/aosp-app-android15-arm64.json`（216 allowed，sha256 入库）、`profile/host-shims/glibc-x86_64.txt`、`fixtures/matrix/cases.json`
- `docs/research/2026-11-11-uml-arm64-device-window-wave2.zh-CN.md`（device-a 基线：API 36/6.6、SECCOMP、ncpus=4 PASS）
- `research/uml-fastboot/README.md` §4（设备窗启动参数与判定串）

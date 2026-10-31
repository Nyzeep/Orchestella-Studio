# SELinux/seccomp 源码闭环：execute allow 链、W^X 三边界、rseq/robust_list 归因（工单 03）

日期：2026-11-03 · 批次实际作业日 2026-10-31 · 执行：fabric-batch2-A-policy · 状态：完成（一处基线前提被源码推翻，见 §3）· 本批次无设备，真机项一律 `not-tested`

工单：`.scratch/orchestella-uml-fabric/issues/03-selinux-seccomp-closure.md`。输入：R3 基线（`2026-10-31-uml-direction-research.zh-CN.md` §2.3）、批次 1（`2026-11-01-uml-kernel-reproducible-build.md` §5 + `checklist-haven-three-pits.md` 坑①）、批次 2 工单 02（`2026-11-02-platform-policy-matrix.md`，execmod 豁免面/seapp 域未变直接引用，未重查）。

**取证方式声明**：AOSP 源码经 android.googlesource.com `?format=TEXT` 转储解码取证（访问 2026-10-31）；torvalds/linux 与 Raymer8639/linux-um-arm64 经 GitHub raw/部分克隆取证（访问 2026-10-31）；sepolicy 另有浅克隆（HEAD 4571ddd9，2025-03-27）用于宏与属性交叉核对，四个核心文件与当日转储逐字节一致（diff 实证），说明五条规则自 2025-03 起未变。全部来源 URL 见 §7。

---

## 0. 结论速览（5 条）

1. **五问闭环表全部命中**：五处 sepolicy main 源码逐条核对完成，其中 ①③⑤ 行号与基线引用完全一致，② 漂移 1 行（块整体在 :68-73，基线引 :69-80），④ 无实质漂移（宏体 :242-260）。**注意**：main 的 seapp_contexts 已把 targetApi 29–33 拆为 `untrusted_app_29/_30/_32` 三个域（`untrusted_app` 现为 targetApi≥34），但 neverallow 豁免面仍是 `-25/-27`，「targetApi≥29 全禁」的结论不变（§1）。
2. **W^X 机制精确化（对 R3 基线的一处收窄修正）**：内核源码实证——SELinux `execmod` 只在「对已发生 COW 的非 exec 文件 VMA 做 mprotect(PROT_EXEC)」时触发；**mmap 阶段（含 MAP_SHARED 可写 fd + PROT_EXEC）只要求 `execute`**（`file_map_prot_to_av`，hooks.c:4051-4061）。UML 启动链全路径只需 execute/execmem/execute_no_trans(apk) 三个已允许通道，**无任何节点落在 execmod 触发面内**（§2）。
3. **rseq/set_robust_list 归因（工单前提被推翻）**：AOSP 标准 app seccomp 允许表（main/android16/android17 三分支的 ALLOWLIST_APP+ALLOWLIST_COMMON）**均不含**这两个调用，默认动作 `SECCOMP_RET_TRAP`→SIGSYS(31)；但 bionic（main+11/12/16/17-release 全查）**从不发起**这两个调用（无 SYSCALLS.TXT 声明、全树无调用点）。两事实自洽：Google 不放行正因为 bionic 不用。工单背景中「标准允许表通常放行、普通 app 的 bionic 都会调」**与源码相反**（§3.1–3.2）。
4. **Haven 机制裁决**：死亡信号 31 与 stock AOSP app 过滤器的 TRAP 动作精确吻合 [官方源码]；能发起这两个调用的主流 libc 是 **glibc ≥2.35**（主线程启动路径 `dl-tls_init_tp.c:95/:111` 实证注册两者）→ 推断 Haven 内核为 glibc 系构建（其仓库无内核构建线公开，[未找到证据]，唯一推测环节）。本项目内核 = NDK r29 bionic → **不会发起** → 与会话 21「未 neuter 的 release 内核三探针全过且正常启动」互证，两现象是同一机制的两个分支（§3.4）。
5. **三选一：无需处理（保留，不阉割）**。构建链保持 NDK bionic 即无 Haven 风险；Haven 教训转化为**条件性**构建期检查项（仅当未来换非 bionic libc 时 neuter+scan gate 成对启用）。checkpolicy/sesearch：容器可行且已做合成策略编译级冒烟（五条规则 verbatim 编译通过 + 控制实验证明 neverallow 检查生效），sesearch 无法查 neverallow（不进二进制）→ 完整树编译性价比不支持，保持源码级闭环（§4）。工单 01 遗留「树内 defconfig 干净对照」判 **wontfix**（§5）。

---

## 1. sepolicy 源码闭环表（五问）

基准：`https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main`（转储解码，访问 2026-10-31）。

| # | 文件 | main 当前行号 | 基线引用 | 漂移 | 规则原文（逐字） |
|---|---|---|---|---|---|
| ① | `private/untrusted_app_all.te` | **:26-28**（:26 privapp allow、:27 app_data_file allow、:28 auditallow） | :27-28 | 无（新增 :26 privapp 行在基线范围外，:27/:28 与基线一致） | `allow untrusted_app_all app_data_file:file     { r_file_perms execute };` / `auditallow untrusted_app_all app_data_file:file execute;`（另有 :26 `allow untrusted_app_all privapp_data_file:file { r_file_perms execute };`、:32 `allow untrusted_app_all system_linker_exec:file execute_no_trans;`） |
| ② | `private/app_neverallows.te` | **:68-73**；b/237289679 注释现位于 :79（RELEASE_UNLOCKED_STORAGE_API 块内） | :69-80 | **漂移 1 行**（execute_no_trans 块整体上移 1 行；b/237289679 注释仍存在但位置变化），如实记录不强行解释 | `neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 -runas_app } { app_data_file privapp_data_file }:file execute_no_trans;`，块前注释：「Block calling execve() on files in an apps home directory. This is a W^X violation … For compatibility, allow for targetApi <= 28. b/112357170」 |
| ③ | `private/domain.te` | **:1344-1351**（另 :1324-1337 有第一条 execmod neverallow，豁免 `-apk_data_file -app_data_file -asec_public_file`） | :1344-1351 | 无 | `neverallow { domain -untrusted_app_25 -untrusted_app_27 userdebug_or_eng(\`-overlay_remounter') } file_type:file execmod;` |
| ④ | `public/te_macros` | 宏定义 **:242-260**（:243 typeattribute appdomain、:245 `type_transition $1 tmpfs:file appdomain_tmpfs`、**:247 `allow $1 appdomain_tmpfs:file { execute getattr map read write ioctl }`**、:248 allowxperm ashmem、:259 neverallow ptrace） | :240-263 | 无实质漂移（:239-241 为注释头） | app_domain 宏体如左；execute 通道即 :247 |
| ⑤ | `microdroid/system/private/domain.te` | **:271-272** | :271-272 | 无 | `neverallow * self:process { execmem execheap execstack };` / `neverallow * *:file execmod;`（块前注释：「No executable memory unless backed by an unmodified file」） |

**② 的「targetApi ≥29」语义支撑（main 当前域映射，`private/seapp_contexts:205-211`）**：

```
user=_app minTargetSdkVersion=34 domain=untrusted_app      type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=32 domain=untrusted_app_32   type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=30 domain=untrusted_app_30   type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=29 domain=untrusted_app_29   type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=28 domain=untrusted_app_27   type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=26 domain=untrusted_app_27   type=app_data_file levelFrom=user
user=_app domain=untrusted_app_25                          type=app_data_file levelFrom=user
```

- `all_untrusted_apps` 是 **m4 宏**（非 attribute，`app_neverallows.te:5-18`）：展开为 `{ ephemeral_app isolated_app isolated_app_all isolated_compute_app mediaprovider mediaprovider_app untrusted_app untrusted_app_25 untrusted_app_27 untrusted_app_29 untrusted_app_30 untrusted_app_all }`，其中 `untrusted_app_all` 为 attribute（`public/attributes:207`），覆盖全部 app 域（含 `_32`、isolated、sdk_sandbox 等，经 `untrusted_app_domain()`/`app_domain()` 宏挂接）。
- 结论：**main 已把 29–33 拆为三个独立域**（`untrusted_app` 本体现在只收 targetApi≥34），但 ② 的豁免面仍是 `-untrusted_app_25 -untrusted_app_27`（=targetApi≤28）→ 「targetApi≥29 一律禁 execute_no_trans」结论不变。本项目 36 域（主包 targetSdk 36 → `untrusted_app`）落在禁令内。
- 与工单 02 交叉：execmod 豁免面（25/27）与 seapp 域映射未变的结论（`2026-11-02-platform-policy-matrix.md` §6.2，行号 :1344-1351 一致）直接引用，未重查。

---

## 2. W^X 三边界 → UML 启动链映射表

### 2.1 内核侧语义精确化（mainline torvalds/linux master，36aad21d50d1，访问 2026-10-31）[官方源码]

R3 基线的三条边界（execute 允许 / execute_no_trans 禁 / execmod 禁）全部与源码一致，但 **execmod 的实际触发面比「可写后执行」的直觉说法窄**，这对 UML 映射至关重要：

1. **mmap 阶段**（`security/selinux/hooks.c:4051-4061` `file_map_prot_to_av`）：`av = FILE__READ`；`shared && PROT_WRITE` → 加 `FILE__WRITE`；`PROT_EXEC` → 加 `FILE__EXECUTE`。**mmap 阶段永不检查 EXECMOD**——即使是 MAP_SHARED + 可写 fd + RWX。
2. **execmem 触发面**（`__file_map_prot_check`，hooks.c:4106-4120）：`default_noexec && PROT_EXEC && (!file || IS_PRIVATE(inode) || (!shared && prot_write))` → 匿名映射、或 **MAP_PRIVATE 且可写**的文件映射 → 要求 `process execmem`。
3. **execmod 触发面**（`selinux_file_mprotect`，hooks.c:4266-4275）：`PROT_EXEC && !(vma->vm_flags & VM_EXEC)`（把非 exec VMA 改成 exec）**且** `file && vma->anon_vma`（该文件映射已发生 COW 写）→ `FILE__EXECMOD`。即典型 text-relocation 场景；「先写（COW）后改 exec」才叫 execmod。

sepolicy 侧三条通道（main）：

| 通道 | 允许规则 | 适用对象 |
|---|---|---|
| `execute`（文件 mmap PROT_EXEC，含 MAP_SHARED RWX） | `app_data_file` → `private/untrusted_app_all.te:27`；`appdomain_tmpfs` → `public/te_macros:247`；`apk_data_file` → `private/app.te:443`（`x_file_perms` = `{ execute execute_no_trans map }`，global_macros） | 全部 app 域（含 36 域） |
| `execmem`（匿名 / MAP_PRIVATE 可写 exec 映射） | `allow appdomain self:process execmem` → `private/app.te:189` | 全部 app 域（Microdroid 反向 neverallow，见 §1⑤） |
| `execute_no_trans`（execve 不换域） | `apk_data_file` 允许（app.te:443）；`system_linker_exec` 允许（untrusted_app_all.te:32）；`app_data_file/privapp_data_file` 对 targetApi≥29 **禁**（app_neverallows.te:68-73） | nativeLibraryDir 合法，数据目录非法 |
| `execmod`（COW 后 mprotect-to-exec） | neverallow（现代域全体，仅豁免 25/27）→ `private/domain.te:1344-1351` | 本 UML 链路无触发点（下表） |

### 2.2 UML 启动链逐节点映射

| # | 启动链节点 | 宿主侧动作（源码定位） | SELinux 权限需求 | 域内判定 | 会话 21/22 实证 |
|---|---|---|---|---|---|
| 1 | **内核 ELF exec**（`libuml_kernel.so` 从 nativeLibraryDir 启动） | `execve` | `apk_data_file:file { execute execute_no_trans map }`（app.te:443） | **合法**（nativeLibraryDir 由安装器部署，无写权限，天然只读） | QEMU 车道同通道 AVC=0（会话 22 §4） |
| 2 | **check_tmpexec**（mainline 与 v0.3.0 同：`arch/um/os-Linux/mem.c:226-247`） | 1 页 tmpfile + `mmap(RWX, MAP_PRIVATE)`（:232） | MAP_PRIVATE + 可写 + PROT_EXEC → **execmem**（hooks.c:4106）→ `appdomain self:process execmem`（app.te:189） | **合法**（noexec tmpfs 才在此失败，EPERM→os_warn） | 三探针之一（同型 RWX mmap）全过 |
| 3 | **physmem guest RAM**（`create_mem_file` mem.c:212-221，`O_TMPFILE`+`O_RDWR` :146；`os_map_memory` process.c:87-99 `MAP_SHARED\|MAP_FIXED`；guest 页映射 stub `arch/um/kernel/skas/stub.c:51-54` 同 `MAP_SHARED\|MAP_FIXED`；两树一致，v0.3.0 process.c:87/stub.c:52 实读） | 内核进程与 stub 进程对 RAM 后备文件 mmap | 数据页 RW → read/write；**代码页 PROT_EXEC → 只需 `execute`**（hooks.c:4051，mmap 阶段不查 execmod）→ `app_data_file:execute`（untrusted_app_all.te:27）或 `appdomain_tmpfs:execute`（te_macros:247） | **合法**。guest RAM exec 页映射不落 execmod 触发面 | UML 启动成功即实证（会话 21 §5） |
| 4 | **stub 装载与 exec**（mainline：内嵌 stub → `memfd_create(MFD_EXEC)`，失败回退 mkstemp，`os-Linux/skas/process.c:364-390`，`execveat` :347；v0.3.0：`stub_exe=` 启动参数 :442-452 + `execveat(stub_exe_fd)` :425，memfd 被拒时给出可诊断 panic 提示 :308-316） | memfd create + write + execveat / 文件 read + execveat | memfd 路径 → `appdomain_tmpfs:file execute`（te_macros:247，memfd 标签经 type_transition :245）；`stub_exe=` 路径（nativeLibraryDir）→ `apk_data_file`（app.te:443） | **合法**。反例：stub 若落数据目录并 execve → execute_no_trans 禁（app_neverallows.te:68-73）——现产物形态不触发 | `stub_exe=` 通道已实证（会话 21） |
| 5 | **guest RAM 缺页映射**（f42c887 `um: Tighten exec permissions on page mappings`，2026-05，引入 `_PAGE_EXEC` 按页收紧；**v0.3.0 不含该补丁**——pgtable.h grep `_PAGE_EXEC` 0 hit 实测） | 同 #3 的 stub mmap 路径 | 同 #3 → `execute` | **合法**；rebase 到 7.4+ 时 f42c887 收紧的是「无必要也给 exec」的旧行为，权限类别不变（execute 通道） | 不适用（v0.3.0 无此补丁） |
| 6 | **execmod 触发点**（对照行） | 需「已 COW 的非 exec 文件 VMA mprotect(PROT_EXEC)」 | `execmod` → neverallow | UML 全链路**无此模式**：RAM 后备 exec 页走 mmap 通道（#3），UML 不做 text-reloc 型 mprotect | — |

**对 R3 基线的收窄修正（证据等级：[官方源码]，修正而非推翻）**：

- 基线原文：「内核 .so 与 rootfs 在运行期不可『先写后执行』——ext4 只读镜像通道与产品形态一致」。
- 精确化后：
  - **宿主 SELinux 强制面**是两条：① 数据目录文件 **execve**（execute_no_trans neverallow，≥29 域）；② COW 文件映射 **mprotect-to-exec**（execmod neverallow）。文件 **mmap PROT_EXEC（含 MAP_SHARED 可写 fd）不在此列**——只查 `execute`，而 execute 对 app_data_file/appdomain_tmpfs 是显式允许的（untrusted_app_all.te:27 的存在本身就是这个通道的官方背书，b/237289679 注释可证其兼容动机）。
  - **UML guest 内部的 W^X 完全由 guest 内核管理**：guest 进程的 execve/mprotect 都由 UML 内核实现（§3.5 无透传），宿主 SELinux 只看到 UML 对 rootfs/physmem 文件的普通 read/write 与对 physmem 的 exec 映射。因此「rootfs 先写后执行」**不存在宿主 SELinux 检查点**——ext4 只读镜像的正确论证是产品卫生决策（guest 数据完整性、多实例安全、坑②锁语义），不是宿主 neverallow 的直接推论。
  - ACR 引用建议：把「不可先写后执行」改述为「宿主 neverallow 拦 execve-数据目录与 COW-mprotect-exec 两类操作；UML 链路全部走 execute/execmem 已允许通道；rootfs 只读镜像为产品决策而非平台强制」。

---

## 3. rseq/`set_robust_list` 归因（批次 1 转来的核心缺口）

### 3.1 bionic 三分支源码核对表

仓库 `platform/bionic`，转储解码（访问 2026-10-31）。分支名核对：`refs/heads/android16-release` 与 `refs/heads/android17-release` 均真实存在（`+refs?format=JSON` 列表核实），工单给的 ref 名可直接使用。

| 文件（main 实际路径） | 三分支行数与一致性（diff 实证） | rseq | set_robust_list | ptrace | process_vm_readv/writev |
|---|---|---|---|---|---|
| `libc/SYSCALLS.TXT` | main 392 / 16 392 / 17 391 行（差异为 getuid/getgid 命名等琐项） | **未声明**（三分支一致） | **未声明**（三分支一致） | **已声明**（:76 `__ptrace:ptrace(int request, int pid, void* addr, void* data) all`） | **已声明**（:354-355，三分支一致） |
| `libc/SECCOMP_ALLOWLIST_APP.TXT` | main 62 / 16 62（与 main 逐字节一致）/ 17 61 行（仅少 1 行 getdents lp32,x86_64） | **不含** | **不含** | 不含 | 不含 |
| `libc/SECCOMP_ALLOWLIST_COMMON.TXT` | main 81 / 16 86 / 17 113 行（内容有演进，行数不同） | **不含** | **不含** | 不含 | 不含 |
| `libc/SECCOMP_BLOCKLIST_APP.TXT` | **三分支逐字节一致**（main==16、main==17，diff 为空；50 行） | 不含 | 不含 | **不含**（与 R3 基线「blocklist 逐字节一致、不含 ptrace/process_vm_*」互证，本轮 diff 实证） | 不含 |
| `libc/SECCOMP_PRIORITY.TXT` | 仅核 main（9 行：futex/ioctl，优先级排序用，不引入新调用） | 不含 | 不含 | 不含 | 不含 |

注：SYSCALLS.TXT 中与目标相关的事实——`__set_tid_address:set_tid_address(int*) all`（main :359）；**ptrace（:76）与 process_vm_readv/writev（:354-355）三分支均有声明（bionic 有 wrapper），但均不在 app 允许表** → app 进程实际不可用（缺省 TRAP 被杀）。R3 基线「blocklist 不含 ptrace/process_vm_*」与之不矛盾：app 侧拦截来自**允许表缺席**（default-deny），不是 blocklist 明文条目。**补充核对**：android11-release/android12-release 的 SYSCALLS.TXT 同样 0 命中——bionic 自 API 30 以来从未有过 rseq/set_robust_list stub。

- **路径修正**：工单给的 `libc/seccomp/SECCOMP_*.TXT` 路径 404——seccomp 目录下只有生成器代码（`gen_syscall_nrs*.cpp`/`seccomp_policy.cpp`），策略 TXT 在 `libc/` 根目录（历史改名：WHITELIST/BLACKLIST → ALLOWLIST/BLOCKLIST，commit dbb8670dfd）。属**路径漂移**，按实际路径取证。
- **bionic 全树调用点**（main 浅克隆 grep）：`libc/`+`linker/` 全部 .c/.cpp/.h/.S 中 `rseq`/`robust` 命中仅 uapi 常量头（`unistd_64.h:108 __NR_set_robust_list 99`、`:286 __NR_rseq 293`——**工单给的 arm64 编号核实正确**）与 `eorseq`（arm32 汇编假阳性）。**结论：bionic 无任何代码路径发起 set_robust_list/rseq** [官方源码]。
- **过滤器组装与默认动作** [官方源码]：`libc/Android.bp:2415-2428` app 过滤器 = ALLOWLIST_COMMON+ALLOWLIST_APP → 生成 `arm64_app_policy.cpp` 等 → `libseccomp_policy`（:2657）；ALLOWLIST_COMMON 头注自述「applied only to zygote spawned processes」；默认动作 `seccomp_policy.cpp:101-104` `Disallow()` = `SECCOMP_RET_TRAP` → **SIGSYS(31)**。允许表是 default-deny 白名单：**不在表内 = 被杀**。

### 3.2 矛盾点裁决

工单背景与坑①机制假设都基于「AOSP 标准 app seccomp 允许表通常放行这两个调用（普通 app 的 bionic 都会调）」。**源码证据相反**：

1. 三分支允许表均不放行 rseq/set_robust_list；
2. bionic 自身从不调用它们（无 stub、无调用点）；
3. 两事实自洽——平台允许表与自家 libc 的 syscall 用法严格对齐（ALLOWLIST_COMMON 的「Syscalls used internally by bionic, but not exposed directly」小节恰好只列 gettid/futex/clone/sigreturn/rt_sigreturn，无 rseq/set_robust_list）。

推论：**「被 app 沙箱 seccomp 杀死 rseq/set_robust_list」的必要前提是内核 ELF 内的 libc 在启动路径发起这两个调用——而这不是 bionic 的行为**。能发起两者的主流 libc 是 glibc（见 3.3）。

### 3.3 glibc 证据（bminor/glibc master 镜像，访问 2026-10-31）[官方源码]

- `sysdeps/nptl/dl-tls_init_tp.c`（主线程启动与每线程 TCB 初始化路径，经 `csu/libc-start.c:296-297` 的 `__pthread_initialize_minimal` 调用）：
  - :95 `INTERNAL_SYSCALL_CALL (set_robust_list, &pd->robust_head, sizeof(struct robust_list_head))`——主线程启动即发起；
  - :111-116 `rseq_register_current_thread(pd, do_rseq)`——启动即注册；注释明确「If the registration fails or is disabled by tunable, the public '__rseq_size' will be set to '0'」——**失败优雅降级，不致命**；
- `nptl/pthread_create.c:390-396`：每个新线程再次发起 set_robust_list；
- `NEWS`：rseq 集成自 2.35（2022-02）起，当前 master 支持 6.3 扩展 ABI。

### 3.4 Haven「signal 31 静默死亡（约 100ms 后）」候选机制评估

| 候选 | 评估 | 证据等级 |
|---|---|---|
| **宿主 seccomp SIGSYS(31)** | **支持**。三分支允许表无此二调用 + 默认动作 TRAP=31 + 死亡信号 31 精确吻合 + seccomp 过滤器跨 fork/exec 继承（app 子进程即 UML 内核进程受同一过滤器约束）。触发前提=内核 ELF 的 libc 发起调用 → bionic 不发起 → **Haven 内核应为 glibc 系构建**（「relinked the guest kernel」在 Linux runner 上以宿主 glibc 链接是 UML 构建常态；glibc 静态启动在 TLS 初始化早期即发这两个调用，其后 100ms 内死——时间量级合理，非实测） | 机制本身 [官方源码]；Haven 内核 libc 身份 [未找到证据]（GlassHaven 仓库列表已核，无内核构建线公开），**本表唯一推测环节** |
| SELinux avc denied | **排除**。avc denied 返回 EACCES（-13），不产生任何信号；signal 31=SIGSYS 只对应 seccomp TRAP（或显式 raise）。rseq/set_robust_list 亦无对应 SELinux 检查点 | [官方源码]（hooks.c 无相关 hook 点） |
| 内核版本差异 | **排除**。set_robust_list（2.6.16+）/rseq（4.18+）在 Android 14–17 的内核均存在；编号 #99/#293 为 arm64 通用 ABI | [官方源码]（uapi） |
| OEM 定制 seccomp filter | **不需要**。stock AOSP 过滤器已足以复现 Haven 现象；不排除部分 OEM 叠加更严 filter，但对本归因无证据必要 | 推断（无需引用） |
| bionic 版本差异 | **排除**。API 30（NDK r29 目标）至 main 的 11/12/16/17-release 四代 SYSCALLS.TXT 均无此二调用，从未有过 | [官方源码] |

**与会话 21 的整合**：会话 21 用**未 neuter 的 release 原生二进制**（NDK bionic 内核，zalexdev 系 v0.3.0）三探针全过且正常启动——与本归因完全一致：bionic 内核不发起这两个调用，stock 允许表杀不到它。Haven 现象与本项目证据并存且互不矛盾，是「同一过滤器 × 不同构建 libc」的两个分支。

### 3.5 UML guest 内 rseq 注册的真实宿主 syscall 路径

1. **UML 无透传**：guest 进程 syscall 陷入后由 UML 内核在自身地址空间实现——派发点 `handle_syscall(regs)`（`arch/um/os-Linux/skas/process.c:275`，mainline 实读；Raymer v0.3.0 `arch/arm64/um/sys_call_table.c` 头注亦明确「handle_syscall() … unpacks the six arguments itself and calls the syscall with them directly」）。guest 的 rseq/set_robust_list 调用**不会成为宿主 syscall**，只会打到 UML 自己的 `sys_call_table`。
2. **表来源**：v0.3.0 arm64 表由通用 arm64 表生成（`arch/arm64/tools/syscall_64.tbl` → `<asm/syscall_table_64.h>`，`[nr] = sym`，未实现符号经 cond_syscall 解析为 `sys_ni_syscall`）。
3. **set_robust_list（#99）**：`kernel/futex/syscalls.c` 随 `CONFIG_FUTEX` 编译——pinned config `CONFIG_FUTEX=y`（`research/uml-kernel-build/defconfigs/linux-bionic.config:173`，SMP :188）→ **guest 内 glibc/musl 的 robust list 注册由 UML 内核自己完成，成功**。
4. **rseq（#293）**：pinned config **无 `CONFIG_RSEQ`**（UP/SMP 两 profile 均无，grep 实证）→ `sys_call_table[293]` → `sys_ni_syscall` → **guest 内 rseq 注册得 -ENOSYS** → glibc 优雅降级（`__rseq_size=0`，继续运行，见 3.3）；rseq 是纯优化（per-cpu 快路径），缺它无功能损失。
5. **内核进程自身**（Haven 问题的真正现场）：NDK bionic 启动路径发起的 syscall 全部 ⊂ app 允许表（平台允许表即按 bionic 用法生成，见 3.2）→ 不被杀。

### 3.6 三选一结论

**无需处理（保留，不阉割）**。理由一句话：本项目构建链（NDK r29 bionic）在源码层面不会发起 set_robust_list/rseq 宿主调用，Haven 的 neuter+scan gate 解决的是一个本项目不存在的触发前提，而真正的风险转移为一条条件性构建纪律（下）。

**坑①的遗留价值转化**（与工单 01 checklist 的关系）：
- checklist 构建期检查项 1-2（静态扫描 + neuter/scan 成对）改为**条件性**：仅当未来内核构建改用非 bionic libc（如 glibc/musl 静态、或改用其他 rootfs 引导 libc 链）时必须启用；bionic 路线下保持不 neuter（neuter 本身是对产物 ELF 的字节改动，会破坏与 release 资产的 sha256 比对链）。
- 设备窗口复验保留（§6.1）：单设备样本不能外推全部 OEM；若复验意外失败，排查方向按本表改为「OEM 定制 filter 杀其他 syscall」并抓 `logcat -b events`/avc。

---

## 4. checkpolicy/sesearch 编译级求值：可行性与裁定

**可行性实证**（2026-10-31，宿主 docker 29.6.2 可用）：

1. `debian:stable` 容器内 `apt-get install checkpolicy m4 setools` 成功，工具可运行。
2. **合成最小策略编译级冒烟**（五条规则 verbatim + 真实宏）：m4 输入 = `public/ioctl_defines`+`public/ioctl_macros`（verbatim）+ 合成样板（类/sid/AV/MLS 最小集）+ 五条规则逐字引用 + 真实宏定义（`r_file_perms`/`no_rw_file_perms`/`all_untrusted_apps`/`app_domain`/`untrusted_app_domain`/`userfaultfd_use` 全部 verbatim 摘自 sepolicy main）→ `checkpolicy -M -c 30`（MLS 开、policy 版本 30，同 AOSP 口径）→ **COMPILE_OK**。
3. **控制实验**：在合成策略中注入一条故意冲突的 `allow untrusted_app app_data_file:file execute_no_trans;` → checkpolicy 立即报 `neverallow on line … violated by allow untrusted_app app_data_file:file { execute_no_trans }` → **neverallow 检查机制真实生效**，冒烟不是空转。
4. **sesearch 能力边界**（实证）：对编译产物 `sesearch --allow -s untrusted_app_all -t app_data_file -c file -p execute` 正常展开显示 allow 规则；**sesearch 无 `--neverallow` 选项**——neverallow 是编译期断言，不进二进制策略，事后查询在原理上不可行。

**完整树编译的成本与裁定**：AOSP 的 neverallow 编译级求值机制 = `se_neverallow_test` soong 模块（`build/soong/sepolicy_neverallow.go`，checkpolicy 二进制编译 + sepolicy-analyze，`SELINUX_IGNORE_NEVERALLOWS` 可跳过），其输入 policy.conf 由 soong genrules 装配（m4 宏组合、release flag 展开 `is_flag_enabled`、treble 版本化 prebuilts、reqd_mask 过滤、platform/vendor 切分）。容器内复刻该装配 = 重新实现构建系统切片，成本高且**结论不会超出本文已给的源码级闭环 + 合成冒烟**（五条规则 verbatim 编译通过 + 检查机制已被控制实验证明生效）。**裁定：不做完整树编译，保持源码级闭环 + 合成冒烟。**合成策略文件留在会话临时目录（不入库，遵守工单文件边界）。

---

## 5. 工单 01 遗留「树内 defconfig 干净对照」：wontfix

- 该项需要重新构建内核产物（UP/SMP 两 profile），与本票的源码归因**不共享构建产物**——rseq/robust_list 归因已在源码级完成（§3），不依赖任何重建内核。
- 批次 1 已记录：树内 defconfig+片段重推受宿主工具链探针污染，价值有限（`2026-11-01` 文档 §8）；pinned config 路线（IKCONFIG 提取）已提供更强保证。
- 判定：**wontfix**（性价比不足；若 ACR 后需建立「从 defconfig 出发的全新构建线」，届时随构建流水线一并做）。

---

## 6. 设备窗口清单增量（本批次无设备，全部 not-tested）

| # | 项 | 关联 |
|---|---|---|
| 1 | 未 neuter 的 **bionic** 内核（UP/SMP）在目标设备沙箱首启存活复验——本票归因后**预期=过**；若意外失败，排查方向：OEM 定制 filter 杀其他 syscall（抓 logcat/avc），而非本票已闭环的两调用 | checklist 坑① 复验项 |
| 2 | 若未来内核构建改用**非 bionic libc**：首启存活必测（Haven 现象适用，glibc 启动即发 #99/#293） | §3.4 |
| 3 | guest 内 rseq 实际行为冒烟：确认 -ENOSYS 优雅降级（glibc 正常启动、无回溯报错）即可，可并入 UML 时间盒第二轮的既有冒烟，无需专项 | §3.5 |

---

## 7. 来源清单（访问日期均 2026-10-31）

| 来源 | URL | 等级 |
|---|---|---|
| sepolicy main：untrusted_app_all.te / app_neverallows.te / domain.te / te_macros / microdroid system private domain.te / seapp_contexts / attributes / app.te / untrusted_app.te / global_macros / neverallow_macros / ioctl_defines / ioctl_macros | https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/<path>（?format=TEXT 转储解码） | [官方源码] |
| sepolicy 浅克隆 HEAD 4571ddd9（2025-03-27）用于宏/属性交叉核对；四个核心文件与当日转储 diff 逐字节一致 | https://android.googlesource.com/platform/system/sepolicy（git） | [官方源码] |
| bionic main HEAD 731631f3 + android16-release + android17-release：SYSCALLS.TXT / SECCOMP_ALLOWLIST_APP / SECCOMP_ALLOWLIST_COMMON / SECCOMP_BLOCKLIST_APP / SECCOMP_PRIORITY（libc/ 根路径） | https://android.googlesource.com/platform/bionic/+/refs/heads/<branch>/<path>（?format=TEXT 转储解码） | [官方源码] |
| bionic main 浅克隆：全树调用点 grep、libc/Android.bp、libc/seccomp/seccomp_policy.cpp、libc/kernel/uapi/asm-arm64/asm/unistd_64.h、libc/tools/genseccomp.py | https://github.com/aosp-mirror/platform_bionic | [官方源码] |
| glibc master（bminor 镜像）：sysdeps/nptl/dl-tls_init_tp.c、nptl/pthread_create.c、csu/libc-start.c、sysdeps/unix/sysv/linux/rseq-internal.h、NEWS | https://raw.githubusercontent.com/bminor/glibc/master/<path> | [官方源码] |
| torvalds/linux master 36aad21d50d1（2026-10-30）：arch/um/os-Linux/{mem,process,file}.c、arch/um/os-Linux/skas/{process,mem}.c、arch/um/kernel/{physmem,process}.c、arch/um/kernel/skas/stub.c、arch/x86/um/sys_call_table_64.c、security/selinux/hooks.c、commit f42c8879ebb6（GitHub commits API 取证） | https://raw.githubusercontent.com/torvalds/linux/master/<path> | [官方源码] |
| Raymer8639/linux-um-arm64 v0.3.0（tag um-arm64-v0.3.0）：arch/arm64/um/sys_call_table.c、arch/um/os-Linux/{process,mem}.c、arch/um/os-Linux/skas/process.c、arch/um/kernel/skas/stub.c、arch/um/include/asm/pgtable.h | https://raw.githubusercontent.com/Raymer8639/linux-um-arm64/um-arm64-v0.3.0/<path> | [官方源码] |
| GlassHaven 仓库清单（无内核构建线公开） | https://api.github.com/orgs/GlassHaven/repos | [未找到证据]（内核 libc 身份） |
| Haven v5.89.8 release notes（批次 1 已引，本票复核机制语境） | https://github.com/GlassHaven/Haven/releases/tag/v5.89.8 | [社区(项目官方自述)] |
| 本仓库 pinned config（CONFIG_RSEQ/CONFIG_FUTEX/CONFIG_SECCOMP 核对，只读） | research/uml-kernel-build/defconfigs/linux-bionic{,-smp}.config | [官方源码]（v0.3.0 树 IKCONFIG 提取件） |

---

## 8. 未验证项与停止条件记录

- **未验证**：Haven 内核二进制的 libc 身份（其 GitHub 组织无内核构建仓库，[未找到证据]——机制链中唯一推测环节）；会话 21 三探针的 mmap flag 细节（MAP_SHARED/PRIVATE 未逐项复核——不影响结论：两条路径的 SELinux 权限需求均已源码闭环）；OEM 定制 seccomp filter 现状（设备窗口）；`b/237289679` 正文（需登录，沿用 R3 基线 [未核实] 标注）；树内 defconfig 干净对照（wontfix，§5）。
- **停止条件**：**命中一次并已按协议处理**——工单前提「AOSP 标准 app seccomp 允许表通常放行这两个调用」与三分支源码相反（§3.2）。按「停下来记录矛盾，不强行解释」执行：如实记录反转证据，归因结论改基于新证据链（bionic 不发起 → glibc 系才触发），未对原前提做任何圆场。
- 本文档由 fabric-batch2-A-policy 产出；不执行任何 git add/commit/push，入库由用户处理。

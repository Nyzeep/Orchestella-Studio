# 用户态内核 + 原生执行的可行性研究：UML / gVisor / protected pVM 与「proot 速度 + QEMU 内核 + pVM 加速」假设

> 状态：研究结论，不是产品承诺；不修改 `CONTEXT.md`、ADR、工单或代码。
> 日期：2026-10-28。证据等级：**[官方事实]** 官方文档/源码/策略；**[社区经验]** issue、基准、第三方仓库自述，不能当受控实验；**[本项目未验证]** 本仓库推断或尚未在真机执行。
> 本环境未运行 adb、Android 构建或真机测试；本文所有「本项目」性能与设备结论均为 `not-tested`。kernel.org 在本环境被反爬拦截，内核源码经 GitHub 官方镜像 `torvalds/linux` 读取，行号以 master（2026-10-24 提交 `9f2516f4`）为准，canonical URL 仍列在 §9。

---

## 0. 结论摘要

**直接回答：在「protected-only + 无 Root + 普通 App」三条件下，(a) 原生 ARM64 用户态速度、(b) 真实 guest 内核、(c) 可用 pVM 硬件 vCPU 三者不能同时成立。** 这不是性能问题，而是三个彼此独立的硬门：

1. **(a)+(b) 的唯一现实载体是 UML**（User-Mode Linux，`ARCH=um`）。UML 的 guest 指令由宿主 CPU 直接执行，guest 内核是真 Linux 内核，且 guest 二进制不经宿主 `execve`（§2.2）。2026-08-15 的 LKML RFC 已证明 arm64 子架构可跑（Alpine/Debian、4K/16K、Docker/systemd 均有真机自述），但代价是：① **不在 mainline，且作者明确不长期维护**（§2.1/§2.7）；② **UML 启动时必须对 guest RAM 后备文件做 `PROT_WRITE|PROT_EXEC` 映射**（`check_tmpexec()`），该映射要求 `file:file execute` + `process execmem`，而 AOSP 对现代 App 的 `app_data_file` 是否授予 `execute` **在源码上未能闭环**（§2.3.2，必须真机探针）；③ **16 KB 宿主上 4 KB guest 会因 `mmap` 偏移不对齐失败**（§2.3.3）；④ 无 GPU、单进程 stub 使多线程用户态串行、arm64 移植 **UP only**。→ UML 最多是**研究线**，不是可承诺的架构。
2. **(b)+(c) 的唯一载体是「跑自己内核的 protected pVM」**。这需要 pvmfw 的 AVB 信任键、virtmgr 对 kernel/initrd/disk 文件标签的接受、以及（AOSP 文档所写的）`adb root` 启动流程；普通 App 无法交付。且 protected 模式的数据面只有 vsock，没有原生网络，GPU 需 OEM 预置的 device assignment。
3. **(a)+(b)+(c) 同体（把 UML 放进 protected Microdroid）被政策明文禁止**：Microdroid 的 SELinux 策略含 `neverallow * self:process { execmem execheap execstack };` 与 `neverallow * *:file execmod;`（`microdroid/system/private/domain.te:271-272`），且 encryptedstore 是 noexec、没有 `appdomain_tmpfs` 通道。UML 需要的可执行 guest RAM 在这里一条路都没有。

**最接近的三个组合（各自必须放弃一项）**：

| 组合 | 得到 | 必须放弃 |
| --- | --- | --- |
| UML（legacy 域 / Termux 类） | 真内核 + 原生指令 + 无 Root + 单机 systemd/Docker | 现代 targetSdk、protected 隔离、16 KB 宿主的 4 KB guest、GPU |
| Protected Microdroid（现有 Fast Lane） | 硬件 vCPU + 原生速度 + 真内核（Google 的）+ 无 Root | 自有内核、systemd/Docker、原生网络、GPU、可写可执行目录 |
| Protected 自定义 pVM（需平台信任） | 真内核 + 硬件 vCPU + 完整发行版 | 无 Root/仅 App、原生网络、GPU、可交付性 |

**对产品的含义**：`proot 速度 + QEMU 内核 + pVM 加速` 的三合一不存在；可交付的最优解仍是「QEMU Full Lane 保完整语义 + protected Fast Lane 做硬件编译/工具卸载（distcc）」，即现状路线。UML 值得作为**研究线**（它能把「真内核 + 原生速度」带进 Userspace Compatibility 之上的一档），但不构成 Full Linux 契约，也不应进入发布承诺。

**决定可行性的最关键事实（单条）**：**硬件 vCPU 与「自有内核」在无 Root 的第三方 App 路径上互斥。** vCPU 只能由 EL2 的 pKVM 提供，而 pKVM 只执行被平台信任链（pvmfw/AVB/文件标签）接受的镜像；UML 这类「内核即宿主进程」的架构拿不到 vCPU；两者无法叠加。因此三合一不是「还没找到实现」，而是**需要平台把镜像纳入信任根**——这正是第三方 App 无 Root 得不到的东西。UML 侧的 SELinux 门（`execute`/`execmem`）只是第二层不确定性，不改变这一结论。

### 0.1 主协调补遗（2026-10-28）：arm64 UML 已有可复现的下游工程，但仍不是现代 App 的交付物

> 本节在 agent 报告完成后补入；不改变上面的「三合一不可能」结论，但把「arm64 UML 是否只是纸面」从「未核实」推进到「有下游工程、有 Termux 类域实测、仍缺现代 targetSdk 证据」。完整的产品方案（三条车道、ACR 提案、D1 go/no-go、时间盒）见 [`2026-10-28-native-kernel-three-lane-exploration.zh-CN.md`](./2026-10-28-native-kernel-three-lane-exploration.zh-CN.md)。

1. **上游仍未接收 arm64。** LKML 2026-08-15 RFC（Oleksii）明确写：`ARCH=um SUBARCH=arm64` 是 33 patches on `uml/next`；能启动 Alpine/Debian、4K/16K、SECCOMP 与 ptrace；已知 no 32-bit compat、UP only、RSS-counter BUG；作者明确「I cannot commit to maintaining this long-term」。`arch/um/Makefile` 仍只映射 x86/x86_64/i386。→ **arm64 UML 是下游移植，不是可 pin 的上游能力**；这与 §2.1 的结论一致，但现在有了一手 RFC 证据（[LKML RFC](https://lkml.iu.edu/hypermail/linux/kernel/2608.1/14307.html)）。
2. **下游工程 `zalexdev/linux-um-arm64`（104 stars，base 7.2-rc4，38 commits，GPL-2.0，作者声明不再维护）与 Android/Termux fork `Raymer8639/linux-um-arm64`** 自述并给出可复现 harness：
   - Alpine 与 Debian 12 启动，4K 与 16K guest pages；
   - `PTRACE_SYSEMU` + syscall cancellation + **seccomp 过滤器下的 syscall substitution**（arm64 的 ptrace stop 早于 seccomp，直接写 `-1` 会被 App 沙箱杀掉；下游改用 `getppid(2)` 代执行）；
   - `SECCOMP`（默认快路径）与 `ptrace` 两种 userspace 模式；`nosysemu`/`nocancel` 可强制回退；
   - bionic NDK r29、API 30、静态链接；**`stub_exe` 必须从路径执行**（Android 16 拒绝 `execve` 一个 memfd；Galaxy S26 实测必需）；
   - passt over vector fd：DHCP/DNS/TCP；`dockerd` + overlay2 + cgroup v2，`docker run hello-world` 通过；
   - 实测设备：Poco F3（Android 15，host 4.19，4K）与 Galaxy S26 Ultra（Android 16，host 6.12.30，4K，Armv9 PAC/SVE）；
   - 性能（Poco F3，seccomp 模式，512K fault 窗口，median of 7）：native/proot/UML 的 `syscall` 0.079/0.166/1.999 µs，`openat` 2.503/28.005/4.466 µs，`forkexec` 3196/516/1177 µs；`compute` 为原生校验项。**作者另称同一内核在 Android app 内**（非 Termux）约 1.9 µs/syscall、compute 慢约 18%；但仓库内没有对应 App 代码，targetSdk 未核实（§2.7.4），不能把这句话当作现代 App 的证据；
   - 限制：UP only（无 cpuset，Docker 会警告）、no 32-bit compat、guest vDSO time 未合并（长时间 `clock_gettime` 会 wrap）、4K 的 RSS-counter BUG 未复测。
   - 来源：[README](https://github.com/zalexdev/linux-um-arm64)、[TESTING](https://raw.githubusercontent.com/Raymer8639/linux-um-arm64/um-arm64/docs/TESTING.md)、[SECURITY](https://raw.githubusercontent.com/Raymer8639/linux-um-arm64/um-arm64/docs/SECURITY.md)、[UPSTREAM](https://raw.githubusercontent.com/Raymer8639/linux-um-arm64/um-arm64/docs/UPSTREAM.md)、[harness](https://raw.githubusercontent.com/Raymer8639/linux-um-arm64/um-arm64/harness/README.md)、[RELEASE](https://raw.githubusercontent.com/Raymer8639/linux-um-arm64/um-arm64/docs/RELEASE.md)。均为 **[社区经验/项目自述]**，不是本项目复现。
3. **它证明了什么、没证明什么。** 证明：「真内核 + 原生 ARM64 用户态 + Docker + passt + 无 Root」在 **Termux/API 30 类域**已经工程化跑通，不是纯理论；`guest 二进制不经宿主 execve` 的加载模型成立，rootfs 放 noexec 也不影响 guest 执行。没证明：**现代 targetSdk 36 的普通 App** 能跑——下游 harness 自己写的是「Run from the Termux host」，而 `check_tmpexec()` 仍要求对 guest RAM 后备文件做 `PROT_EXEC` 映射（`file:file execute` + `execmem`），`app_data_file` 的 `execute` 在 AOSP 源码上仍有 §2.3.2 的矛盾。**D1 仍是 go/no-go。**
4. **产品含义：三合一不成立，但「三条车道」成立。** UML 不拿 pVM vCPU，pVM 也不接受 UML 内核；但两者可以作为**并列的执行策略**，共享同一 rootfs/发行版清单：
   - **Full Linux / QEMU**：GPU + 完整契约，CPU 慢；
   - **Full Linux / UML**（研究线）：CPU 原生 + 真内核 + Docker/passt，无 GPU、无 pVM、进程级隔离；
   - **Fast Lane / protected pVM**：硬件 vCPU + 密封工具链，无自有内核/原生网络/GPU。
   Lane Router 按设备与工作负载选道；UML 在 D1、16 KB 宿主、systemd/Docker、`stub_exe` 四道门闭合前不得进入产品承诺。
5. **下一步不变**：Z1（arm64 Linux 容器构建下游 UML + Alpine）、D1（现代 App 三组 `mmap` 探针）、D3（Termux 跑下游 UML）、D7（`stub_exe`/memfd）、D8（16 KB 宿主）、D9（systemd/Docker）、D10（同设备性能 A/B）。D1 通过之前，不写代码、不改 `CONTEXT.md`/ADR。

---

## 1. 问题与概念框架

### 1.1 五种执行模型

| # | 模型 | 执行 guest 指令 | guest 内核 | 代表 | 在 Android 无 Root 下的形态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 全系统模拟 | 软件翻译（TCG） | 有（被翻译） | QEMU system | 现有 Full Lane |
| 2 | 硬件虚拟化 | CPU 硬件 vCPU | 有（硬件执行） | pKVM/AVF、Gunyah | protected pVM（受限） |
| 3 | 宿主内核兼容 | 宿主 CPU 原生 | 无 | proot、Termux、glibc-runner | Userspace Compatibility |
| 4 | 用户态内核 | 宿主 CPU 原生 | **有（内核跑在用户态）** | UML、gVisor（ABI 级）、LKL（库级） | 本文重点 |
| 5 | 混合 | 视组合 | 视组合 | QEMU+pVM distcc、UML+pVM | 现状 Fast Lane 交叉优化 |

用户假设命中的是模型 4 与模型 2 的交集：**「模型 4 的内核 + 模型 2 的 vCPU」**。这两个模型的资源模型根本不同——模型 4 的内核本身就是宿主进程，模型 2 的内核在 EL1 由 EL2 托管——所以「同时拿到」不是调参问题，而是要把一个内核套进另一个内核（嵌套），或者让 hypervisor 直接执行 UML 的内核代码（不存在这种 ABI）。

### 1.2 为什么「proot 速度」是范畴错误

- **[官方事实]** PRoot 不模拟 CPU：guest 指令由宿主 CPU 原生执行，PRoot 只用 `ptrace` 翻译路径/系统资源请求，"the guest kernel is never involved"（[proot-me.github.io](https://proot-me.github.io/)）。
- 因此 proot 的「快」= **没有指令翻译**，与「硬件加速」无关；它的开销在 syscall 陷入与路径重写，I/O 与元数据密集负载明显变慢（[社区经验] proot-distro issue #634 单机样本：CPU 单核几乎相同，内存带宽约 2.6× 慢、glmark2 约 2.2× 慢）。
- 同理，UML 的「快」也来自「不翻译指令」，但它与 proot 有本质区别：UML 有**自己的内核**，systemd/Docker/namespace 由 guest 内核实现，不依赖宿主内核的宽容度。

**这一节的产品含义**：性能徽章不能写「硬件加速」来描述 proot/UML；只能说「原生指令执行」。`CONTEXT.md` 已区分「硬件虚拟化 / 软件虚拟化」，UML 属于第三类，需要新词（见 §8 的 ACR 提示）。

### 1.3 「缺失象限」的精确定义

把三个维度做成笛卡尔空间：

- 维度 A：guest 指令是否翻译（原生 / 翻译）
- 维度 B：是否有 guest 内核（有 / 无）
- 维度 C：guest 是否在硬件 vCPU 上（是 / 否）

现有能力占据三个角：
- QEMU TCG = 翻译 + 有内核 + 非 vCPU；
- proot/Termux = 原生 + 无内核 + 非 vCPU；
- Microdroid = 原生 + 有内核 + vCPU（但内核与用户态都不是我们的，且无网络/GPU）。

**「原生 + 有内核 + 非 vCPU」这一格由 UML 占据**——它是唯一不需要 hypervisor 就能同时满足 A、B 的架构。用户假设要求的「原生 + 有内核 + vCPU」这一格，在无 Root 的 protected-only 设备上**没有任何实现**：要么内核在 EL1 由 pKVM 托管（则镜像必须被平台信任），要么内核是宿主进程（则拿不到 vCPU）。

---

## 2. UML（ARCH=um）在 arm64 上的真实状态

### 2.1 上游状态：**arm64 不在 mainline**

- **[官方事实-源码核对]** `torvalds/linux` master（2026-10-24，`9f2516f4`）的 `arch/um/` 下只有 `configs drivers include kernel os-Linux scripts`，**不存在 `arch/arm64/um/`**（GitHub Contents API 返回 404）。
- **[官方事实-源码核对]** `arch/um/Makefile` 只对 `x86 x86_64 i386` 做特殊处理：
  ```make
  HEADER_ARCH := $(SUBARCH)
  ifneq ($(filter $(SUBARCH),x86 x86_64 i386),)
      HEADER_ARCH := x86
  endif
  HOST_DIR := arch/$(HEADER_ARCH)
  include $(srctree)/$(HOST_DIR)/Makefile.um
  ```
  `SUBARCH=arm64` 会要求 `arch/arm64/Makefile.um` 与 `arch/arm64/um/`，二者在 mainline 均不存在 → **`make ARCH=um SUBARCH=arm64` 在 mainline 上无法构建**。
- **[官方事实]** UML 的架构支持文档仍以 x86 为中心（[Porting UML to a new architecture](https://user-mode-linux.sourceforge.net/old/arch-port.html)），需要 `sys-$(SUBARCH)` 的 signal/syscall/checksum 等一组文件。
- **[一手 RFC，作者自述]** 2026-08-15 的 LKML RFC 确认了这条路径的存在与边界：33 patches on uml/next、4K/16K guest、SECCOMP 与 ptrace 两种模式、Alpine/Debian 可启动；同时明确「**I cannot commit to maintaining this long-term**」、UP only、无 32-bit compat。详见 §2.7。

**结论**：arm64 UML 目前是**下游/第三方移植**，不是可直接 pin 的上游能力。对「单一 pinned 内核 + CI 可复现构建」的 Guest Kernel 契约而言，这意味着我们要自己维护一个架构移植——不是小成本。

### 2.2 执行模型：guest 二进制如何被加载

这是整个假设的技术核心，值得逐层拆开：

1. **guest 进程的创建**：guest 内的 `fork/exec` 由 guest 内核处理；每个 guest 进程对应一个宿主进程（SKAS/stub 模型）。UML **不会**对 guest ELF 调用宿主 `execve`。
2. **guest ELF 的加载**：走标准 `fs/binfmt_elf.c`，读文件通过 guest VFS（ubd 块设备、hostfs、initramfs），页表建立在 guest mm 里。
3. **guest 页的宿主落地**：guest 物理内存是宿主上的一个临时文件（`setup_physmem()` → `create_mem_file()` → `os_map_memory(..., 1, 1, 1)`，即 `PROT_READ|PROT_WRITE|PROT_EXEC, MAP_SHARED|MAP_FIXED`）。stub 在缺页时按 guest PTE 的权限把该物理页映射进 guest 进程地址空间：
   ```c
   /* arch/um/os-Linux/process.c */
   prot = (r ? PROT_READ : 0) | (w ? PROT_WRITE : 0) | (x ? PROT_EXEC : 0);
   loc = mmap64((void *) virt, len, prot, MAP_SHARED | MAP_FIXED, fd, off);
   ```
   其中 `off` 来自 `phys_mapping(phys, &offset)`，**`offset = guest 物理地址`**（`arch/um/kernel/physmem.c`）。

**推论（关键）**：
- 宿主**永远看不到 guest 二进制作为可执行文件**，所以 rootfs 放在 noexec 挂载点上不影响 guest 执行（数据而已）。这一点与「APK 内 noexec」直觉相反，是 UML 相对 proot 的真正优势。
- 但**宿主必须要能对 guest RAM 后备文件做 W+X 映射**。这是 UML 唯一的、但致命的宿主侧执行权限要求。

### 2.3 Android 侧的硬门（本节是全文的决定性证据）

#### 2.3.1 UML 自己的启动自检

```c
/* arch/um/os-Linux/mem.c:226 */
void __init check_tmpexec(void)
{
    fd = create_tmp_file(UM_KERN_PAGE_SIZE);
    addr = mmap(NULL, UM_KERN_PAGE_SIZE,
                PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE, fd, 0);
    if (addr == MAP_FAILED) {
        if (err == EPERM)
            os_warn("%s must be not mounted noexec\n", tempdir);
        exit(1);
    }
}
```
`os_early_checks()`（`arch/um/os-Linux/start_up.c:463`）在**任何模式**下都会先调用它，注释写明 "Need to check this early because mmapping happens before the kernel is running."。也就是说：**权限不满足时 UML 连内核都起不来**，不是「能启动但程序跑不动」。

#### 2.3.2 SELinux：真正被检查的是 `execute`，不是 `execmod`

先看内核到底查什么（这一点常被写错）：

```c
/* security/selinux/hooks.c:4051 */
static u32 file_map_prot_to_av(unsigned long prot, bool shared)
{
    u32 av = FILE__READ;
    if (shared && (prot & PROT_WRITE)) av |= FILE__WRITE;
    if (prot & PROT_EXEC)             av |= FILE__EXECUTE;   /* ← mmap(PROT_EXEC) 查 execute */
    return av;
}
/* hooks.c:4114 */
if (!mounter_check && default_noexec && prot_exec &&
    (!file || IS_PRIVATE(inode) || (!shared && prot_write)))
        avc_has_perm(..., PROCESS__EXECMEM, ...);            /* ← 私有可写可执行再查 execmem */
```

- `mmap(PROT_EXEC)` 对一个文件 → 需要 **`file:file execute`**；
- `check_tmpexec()` 是 `MAP_PRIVATE|PROT_WRITE|PROT_EXEC` → 额外需要 **`process execmem`**（`allow appdomain self:process execmem;`，`private/app.te:189`，**现代 App 有**）；
- `execmod` 只在「文件映射发生过 COW、页可能被改写」时检查（text relocation，`hooks.c:4268` 附近），**不是** UML 启动的主门。

所以决定性的是 `execute`。AOSP 的实际授予情况：

| 对象 | 现代 App（targetSdk ≥ 29） | 依据 |
| --- | --- | --- |
| `app_data_file`（files/cache 目录） | 显式 allow 只有 `{ getattr map read write }` + `create_file_perms`，**未见 `execute`**（但见下方矛盾） | `private/app.te:268-277` |
| 自家目录 execve | **neverallow `execute_no_trans`**（targetSdk ≤ 28 除外） | `private/app_neverallows.te:68-73` |
| tmpfs 对象（`appdomain_tmpfs`） | **`{ execute getattr map read write ioctl }`** —— 所有 App 都有 | `public/te_macros:242-248`：`type_transition $1 tmpfs:file appdomain_tmpfs;` + `allow $1 appdomain_tmpfs:file { execute ... }` |
| 匿名可执行内存 | `execmem` 允许 | `private/app.te:189` |
| `untrusted_app_27`（25 < targetSdk ≤ 28） | 额外有 `execmod` 与 `execute_no_trans` | `private/untrusted_app_27.te` |

**这里有一处 AOSP 源码上的自相矛盾，必须如实记录**：

- `app_neverallows.te:76-80` 的注释写：「this is a stronger and more desirable guarantee than blocking execute_no_trans, but **execute cannot be blocked on all of app_data_file** without causing backwards compatibility issues (see b/237289679)」——「无法全量禁止」意味着 `execute` 在 `app_data_file` 上**存在某种允许**。
- 但本环境在 `private/app.te`、`private/app_neverallows.te`、`public/app.te`、`private/domain.te`、`private/file.te` 中**没有定位到** `app_data_file:file execute` 的显式 allow；`untrusted_app_27.te` 里只有 `execmod`、`execute_no_trans`，以及对 `{ execute execute_no_trans }` 的 `auditallow`（auditallow 只记录、不授予）。
- 可能的解释：① 存在本文未读到的 allow 或宏；② 该注释描述历史状态/其它分支；③ 经 `data_file_type` 的通用规则授予。

**因此本文不把「现代 App 一定跑不了 UML」写成已证结论**，而把它列为 **D1 第一优先级真机探针**（§7.2）。两种结果的含义：

- **若 `execute` 可得（D1① 通过）**：UML 在现代 App 里可启动，剩下的门是 16 KB 页、单进程 stub、GPL、arm64 移植成本、无 GPU（见 §2.4–2.8）。它仍不是 Full Linux（无 vCPU、无 protected）。
- **若 `execute` 不可得（D1① 被拒）**：则只剩两条缝隙：
  1. **tmpfs 对象**（`appdomain_tmpfs`）——`app_domain()` 给所有 App `execute`，但 stock Android 没有 App 可写的 tmpfs 目录；
  2. **memfd**——inode 在 shmem 上，按 `type_transition` 应得 `appdomain_tmpfs`，但 mainline UML 用 `O_TMPFILE`/`mkstemp`（`mem.c:127-190`）而非 memfd；改造它还要过 `vm.memfd_noexec`（Android 是否设为 2，**未核实**）。
  旧域（Termux，targetSdk 28）之所以稳，是因为 `execmod` + `execute_no_trans` 覆盖 /data 文件——这正是所有 UML-on-Android 项目都长在 Termux 里的结构性原因。

#### 2.3.3 其余门逐项核对

| 门 | 结论 | 依据 |
| --- | --- | --- |
| APK 原生库可执行 | 可以，`lib/arm64-v8a/lib*.so` 是 `apk_data_file`，可 map/execute | app.te `apk_data_file` 规则；[本项目未验证] 直接 `execve` 需实测 |
| 自家 data 目录 execve | `targetSdk ≥ 29` 禁止 | 上文 neverallow |
| 自家 data 目录 `mmap(PROT_EXEC)` | `targetSdk ≥ 29` 禁止（无 `execute`） | `app.te:268-277` |
| tmpfs 对象 `mmap(PROT_EXEC)` | **允许**（`appdomain_tmpfs`）；但 stock Android 无 App 可写 tmpfs 路径 | `public/te_macros:242-248` |
| memfd | 理论允许（同上标签），mainline UML 不用；`vm.memfd_noexec` 未核实 | `mem.c:127-190` |
| 匿名可执行内存（`execmem`） | **允许**：`allow appdomain self:process execmem;`（app.te:189），ART JIT 依赖它 | 官方事实 |
| ptrace 自己的子进程 | **允许**：`allow untrusted_app_all self:process ptrace;`（untrusted_app_all.te:103） | 官方事实 |
| Android seccomp 过滤器 | 允许列表制，`mount/umount2/chroot/setuid/setgid/sethostname/...` 被 `SECCOMP_RET_TRAP`（进程收 SIGSYS）；其余在 `SYSCALLS.TXT` 中的系统调用放行 | bionic `SECCOMP_BLOCKLIST_APP.TXT`、`seccomp_policy.cpp`（`Disallow` = `RET_TRAP`） |
| UML 是否需要上述被 block 的调用 | 基本不需要：guest 的 mount/chroot/setuid 由 **guest 内核**实现，不下沉到宿主；宿主侧只需 read/write/mmap/clone/futex/rt_sig*/seccomp | [本项目推断，源码] |
| 16 KB 宿主页 | **4 KB guest 在 16 KB 宿主上会失败**：stub 以 guest 物理地址（4 KB 粒度）作 `mmap` 偏移，而 `mmap` 要求 offset 按宿主页大小对齐 → `EINVAL` | `os_map_memory()` + `phys_mapping()`；[本项目推断] |
| guest 页大小 | mainline UML 只声明 `select HAVE_PAGE_SIZE_4KB`（`arch/um/Kconfig:29`），不支持 16 KB guest | 官方事实 |

**16 KB 的直接影响**：我们的目标设备如果跑 16 KB 内核（Android 15+ 的新硬件方向），mainline UML 的 4 KB guest 在第一次缺页映射非 16 KB 对齐的物理页时就会 `panic("map_memory ... failed")`。要支持 16 KB 设备，需要「guest 页大小 ≥ 宿主页大小」的 out-of-tree 改造（社区项目声称做了 "16 KB guest pages on a 4 KB host"，那是反方向，不解决 16 KB 宿主）。

### 2.4 内存、SMP 与调度

- **[官方事实]** SMP 已在 mainline：`arch/um/kernel/smp.c` 存在，Kconfig 有 `config SMP depends on UML_SUBARCH_SUPPORTS_SMP`，`NR_CPUS` 默认 2、上限 64；**每个 vCPU 是一个宿主线程**（commit `1e4ee5135d81`，2025-10-27）。**但 arm64 子架构没有 select 该选项**，下游移植自述「UP only」（§2.7.1）——所以手机上的 UML 实际是单核。
- **[官方事实]** 但 Kconfig 文本明确限制：「kthreads and normal threads (when running in kernel mode) can be scheduled... However, the userspace code of normal threads still runs within their respective single-threaded stubs.」→ **同一进程的多线程在用户态串行**。这对 JVM/Node/Rust 多线程是硬伤；`make -j`（多进程）不受影响。
- **[官方事实]** SMP **必须**用 `seccomp=on`：`if (uml_ncpus > 1) fatal("SMP is not supported with PTRACE userspace.")`（start_up.c:485）。
- **[官方事实]** seccomp 模式自身不安全：「the SECCOMP filter is not (yet) restrictive enough to prevent userspace from reading and writing all physical memory... This is insecure and should only be used with a trusted userspace.」→ **UML 不是安全边界**，guest root ≈ 可读写 UML 进程的全部内存（也就等于能攻击宿主内核的该进程权限面）。

**内存现实性 [本项目未验证]**：guest RAM 是一个宿主文件/映射，受 App 的 cgroup 内存上限、`RLIMIT_AS`、`vm.max_map_count` 约束。`map_memory()` 失败时内核自己会打印 "try increasing the host's /proc/sys/vm/max_map_count"——普通 App 改不了该 sysctl。1–2 GB guest 在 8 GB 手机上理论可行，但需要真机测 `max_map_count` 与后台杀进程行为。

### 2.5 网络与显示

- **网络**：UML 支持 `vec`（vector fd）传输、`slirp`、`vde`、`tap`。无 Root 可用的是 `vec` + 宿主用户态 TCP/IP（passt / gvproxy），即 guest 的 `eth0` 是 virtio 设备，宿主侧由普通 socket 承载。→ **guest 可以访问宿主 127.0.0.1（distcc）和公网**，不需要 TAP/CAP_NET_ADMIN。
- **显示**：UML 没有 GPU 直通，也没有 virtio-gpu/DRM 硬件加速路径。可行的是 guest 内 llvmpipe/Weston headless + VNC/RFB，经 vec 网络或 hostfs 暴露给宿主。**明确结论：没有硬件 GPU，桌面只能用软件渲染。**（[本项目未验证] 具体帧率）
- **文件共享**：`hostfs` 把宿主目录映射进 guest；在无 Root 下 hostfs 的语义受宿主 UID 限制，且会绕过 guest 的权限模型，产品上不宜作为主存储，只适合只读共享。

### 2.6 性能：可信的方向与不可信的数字

| 证据 | 内容 | 等级 |
| --- | --- | --- |
| Igalia 2007 对照实验（同一台机器编译 Linux 2.6.21.1） | 原生 `-j2` 4m49s / 原生 8m38s / KVM 11m12s / **UML skas3 12m47s / UML skas0 14m30s** / QEMU+KQEMU 17m55s / **QEMU 2h13m** | [社区经验-第三方基准，2007，x86，老版本] |
| 由该表可得的比例 | UML ≈ 0.60–0.67× 原生（编译负载）；约为纯 TCG 的 9× | 同上，**不可外推到 2026 手机** |
| 架构推断 | CPU-bound 循环接近原生；syscall/fork/exec/缺页路径每次都要进 UML 内核（seccomp 模式是一次信号+共享内存往返，ptrace 模式是两次上下文切换） | [本项目推断] |

**写法约束**：不得引用单一百分比；不得把 2007 x86 数字写成我们的收益。真机必须同设备、同 workload、n≥10。

### 2.7 下游 arm64 移植：LKML RFC 与源码核对（本节为新证据，优先级最高）

#### 2.7.1 上游状态（已核实，不再是「未核实」）

- **[官方事实]** LKML RFC 原文：`[RFC] um: an arm64 subarch for User Mode Linux`，2026-08-15，作者署名 Oleksii（邮件 From 显示为 `contact`）。原文：「I have ARCH=um SUBARCH=arm64 working -- **33 patches on uml/next**」「Boots Alpine and Debian at 4K and 16K pages, SECCOMP and ptrace userspace, loadable modules.」（[lkml.iu.edu 归档](https://lkml.iu.edu/hypermail/linux/kernel/2608.1/14307.html)）
- **[官方事实]** 作者明确不承诺维护：「**I cannot commit to maintaining this long-term.** Happy to respin or split it however you want」。且「About a third of the patches are not arm64 ... they sit on top of the subarch commit and **do not apply alone**」。
- **[官方事实-源码核对]** 代码确实存在，不是 README 空话：`zalexdev/linux-um-arm64` 默认分支 `um-arm64` 的 `arch/arm64/um/` 下有 `Kconfig`、`Makefile`、`asm/`、`os-Linux/`、`syscalls.c`、`ptrace.c`、`stub_segv.c`、`signal.c`、`tls.c`、`vdso/` 等；base 为 Linux 7.2-rc4（uml tree `origin/next`），当前 38 commits（RFC 时 33）。
- **[官方事实-源码核对]** 上游 UML SMP 已合入：commit `1e4ee5135d81`「um: Add initial SMP support」（Tiwei Bie，2025-10-27，已在 `torvalds/linux`）。但 **arm64 子架构没有 select `UML_SUBARCH_SUPPORTS_SMP`**，移植自述「**UP only**」「`CONFIG_CPUSETS` depends on `SMP` and UML is uniprocessor」。→ 与 protected Fast Lane 的多 vCPU 形成鲜明对比。
- **[官方事实]** 未合入 mainline 的直接证据仍是 `arch/um/Makefile` 只映射 x86（§2.1）；RFC 只是「问要不要」，没有 review 结论。

**结论更新**：arm64 UML 从「未核实的下游传闻」升级为「**有源码、有 RFC、有真机数据，但作者不维护、未进 mainline** 的下游移植」。这改变了它的**可用性评估**，没有改变它的**可交付性评估**（仍不能作为我们 pinned 内核的基线）。

#### 2.7.2 Android 特有的 syscall 拦截问题（一手细节，直接决定可行性）

这是 RFC 里最有价值的一段，也是我们此前只能推测的部分：

1. **arm64 直到 5.3 才有 `PTRACE_SYSEMU`**，而手机常见 4.19（作者的 Poco F3 就是 `4.19.246`）→ 必须有回退路径。
2. **回退一：syscall cancellation**——在 `PTRACE_SYSCALL` 停点把 `-1` 写入 `NT_ARM_SYSTEM_CALL`，让 guest 调用不执行。
3. **该回退在 seccomp 过滤器下失效**（原文）：「That fails under a seccomp filter, though: **arm64 reports the ptrace stop before running seccomp**, so the filter sees the -1 and **an Android app sandbox kills the tracee**.」
4. **回退二：syscall substitution**——「the third path **substitutes getppid(2)** for the guest's call -- the call still does not run, but **a syscall does execute on the guest's behalf**. Is that acceptable?」作者自己也在向社区征询这个语义妥协是否可接受。

**含义**：Android 上 UML 的真实代价不是「能不能跑」，而是**每个 guest syscall 都要一次宿主 syscall + 一次信号/停点往返，并且取消路径与 Android seccomp 正面冲突，只能改用替代系统调用**。这会影响语义（`getppid` 的返回值、审计、依赖 seccomp 的沙箱策略），也是 Fast Lane 之外 UML 无法透明替代 QEMU 的原因之一。

另一个 Android 细节：**stub 默认从 memfd exec**（`memfd_create(..., MFD_EXEC)`），失败时内核会打印「host refused to exec it from a memfd; pass stub_exe=...」，移植因此提供 `stub_exe=<path>` 用文件替代。→ 这从侧面证明 **Android 上 memfd 可执行性并不可靠**（`vm.memfd_noexec` 或 SELinux），与 §2.3.2 的「memfd 缝隙」判断一致，而且移植已经为它准备了回退。

#### 2.7.3 实测数据（Poco F3，`adb shell`，7 轮交错，取中位数，µs/op，seccomp 模式）

| | native | proot | UML（128K fault 窗口） | UML（512K 窗口） |
| --- | --- | --- | --- | --- |
| syscall（`getppid`） | 0.079 | 0.166 | 2.049 | **1.999** |
| openat | 2.503 | 28.005 | 4.494 | **4.466** |
| fault（每页） | 1.652 | 1.496 | 11.812 | **10.884** |
| forkexec | 3196 | 516 | 1187 | **1177** |

来源：[README Benchmarks 节](https://github.com/zalexdev/linux-um-arm64/blob/um-arm64/README.md)。**[社区经验-单机单 SoC]**，但方法学罕见地严谨：条件在同一 run 内交错、取中位数、`compute` 作为 validator（>5% 偏差整表作废）。

可读出的结论：

- **syscall 约为 proot 的 12×、native 的 25×**，但绝对值为 ~2 µs——远好于 TCG 的毫秒级，符合「原生指令 + 拦截开销」的预期。
- **openat 比 proot 快约 6×**（proot 的路径重写在元数据密集负载上代价极高），说明 UML 在真实文件操作上反而占优。
- **`forkexec` 一行反直觉（native 3196 > UML 1187 > proot 516）**，作者未解释；不应据此下结论。
- `fault` 行有单位陷阱（16K guest vs 4K host），作者已自行纠正为「每字节 1.77×」，本文只引用纠正后的说法。
- 作者另称「**同一个内核跑在 Android App 里**（非 adb shell）约 1.9 µs/syscall、`compute` 慢约 18%」；`compute` 是纯用户态校验项，说明**纯计算接近原生**——这正是 (a) 的定义。

#### 2.7.4 源码核对：它为什么能跑，以及不能证明什么

- **[官方事实-源码核对]** 该移植的 `arch/um/os-Linux/mem.c` **仍然保留原版 `check_tmpexec()`**，`create_mem_file()` 仍然 `create_tmp_file()` 落盘（**没有改成 memfd**）。→ 它能在某个 Android 域里跑，说明**该域允许对 TMPDIR 文件做 `execute`**。
- 这与「Termux（targetSdk 28）域」完全一致；作者说的「inside the Android app」在仓库里**没有对应 App 代码**，APK 的 targetSdk **未核实**。因此它**不能**用来推翻 §2.3.2「现代 App 的 `execute` 未闭环」的判断；恰恰相反，它说明这条门是真实存在、且当前只有 legacy 域能稳定通过。
- 另外，移植的 `stub_exe` 回退路径说明：即使 memfd 被拒，只要有**可执行的 `stub_exe` 路径**（如 nativeLibraryDir）仍可启动——这为「现代 App 能否至少启动内核」留下一条可测通道，但不解决 guest RAM 的 `execute`。

### 2.8 集成门：把 UML 装进现代 Android App

| 门 | 要求/事实 | 当前判定 |
| --- | --- | --- |
| 可执行位置 | App 只能 exec `nativeLibraryDir`；移植 README：bionic 构建必须**静态链接**（"Android has no `/lib/ld-linux-aarch64.so.1` and an app may only execute from its own native library directory"） | ✅ 可满足（lib*.so 即内核二进制） |
| guest RAM 后备 | `TMPDIR` 必须支持 `execute`；`check_tmpexec()` 不改 | ⚠️ 现代 App 未闭环（D1） |
| seccomp | Android 过滤器 + `-1` 取消冲突 → 必须走 substitution；语义妥协 | ⚠️ 可用但非透明 |
| ptrace | `allow untrusted_app_all self:process ptrace` | ✅ |
| 16 KB 宿主 | 移植支持 16 KB guest；mainline 只 4 KB；guest 页 ≥ host 页是硬约束 | ⚠️ 依赖下游补丁 |
| bionic / glibc | 内核可用 bionic 静态链接；guest 内是 glibc（Debian）或 musl（Alpine） | ✅ |
| rootfs / ubd | ext4 镜像经 ubd 块设备；镜像放 app data 不受 noexec 影响（是数据，不是 exec） | ✅ |
| 网络 | passt/vec，用户态 TCP/IP；无 TAP、无 CAP_NET_ADMIN | ✅（性能待测） |
| Docker | 需 guest `OVERLAY_FS/VETH/MEMCG`；**UP + 无 CPUSETS** → `docker info` 警告、无法 pin | ⚠️ 受限 |
| systemd | 需要 guest init；移植自述 Debian 可起（社区项目称 PID 1） | ⚠️ 未独立验证 |
| 显示 | 无 GPU；llvmpipe/Weston + VNC/RFB | ⚠️ 软件渲染 |
| 许可 | GPL-2.0；分发内核二进制触发对应源码义务 | ⚠️ 需法务 |
| APK 体积 | 内核 + Debian 镜像数百 MB；只能下载/首次装配 | ⚠️ 分发设计 |
| 内核基线 | 移植基于 7.2-rc4（uml/next），与项目 pinned 内核/Guest Kernel 契约不一致 | ❌ 需重定基线 |
| 维护 | 作者明确「不维护、不 shepherd review」 | ❌ 自担 |

### 2.9 社区先例（全部为 [社区经验]，自述未独立复现）

| 项目 | 自述能力 | 关键前提 |
| --- | --- | --- |
| [Raymer8639/linux-um-arm64](https://github.com/Raymer8639/linux-um-arm64)（fork，README 明说 "not the official UML upstream"） | Alpine/Debian 12 启动、4 KB 与 16 KB guest、PTRACE_SYSEMU、SECCOMP 模式、`dockerd` + overlay2、USB/IP 直通 | Termux/bionic 宿主 |
| [michaelamoetoevsky/uml4termux](https://github.com/michaelamoetoevsky/uml4termux) | 无 root、无 KVM、Docker in guest、gvproxy/BESS 网络、端口回环转发 | **Termux**（即 targetSdk 28 域） |
| [fahrulseptiana/drolin](https://github.com/fahrulseptiana/drolin) | Ubuntu 26.04 + systemd PID 1、passt 网络、打包 APK | 自述 debug APK；**targetSdk 未核实**（仓库无 app/build.gradle） |
| [zalexdev/linux-um-arm64](https://github.com/zalexdev/linux-um-arm64)（104 stars，默认分支 `um-arm64`） | `ARCH=um SUBARCH=arm64`；Alpine/Debian 12、4K/16K guest、SECCOMP/ptrace、dockerd overlay2、USB/IP 直通；含完整 benchmark harness | 见 §2.7；作者明确不维护 |

这些仓库**共同证明**：arm64 UML 在 Termux 类环境里被工程化跑通过（至少自述如此），Docker/systemd 在 guest 内可用。它们**共同不证明**：现代 targetSdk 的 App 能做到——因为这正是 SELinux 域的分界线。`drolin` 的 APK 若 targetSdk ≤ 28，它属于同一兼容类；若 ≥ 29，则其自述与 AOSP 策略冲突，需要真机 AVC 日志才能解释（可能其 ROM 放宽了策略）。

### 2.10 许可

- **[官方事实]** Linux 内核 GPL-2.0-only；UML 内核二进制是内核的衍生作品。随 APK 分发 UML 内核 = 分发 GPL 对象码，触发对应源码义务（`CONTEXT.md` 的「对应源码发布」定义已覆盖）。
- 下游 arm64 补丁若来自第三方仓库，需逐文件核许可与来源，不得默认整仓可搬（与本项目「自研立场」一致）。

---

## 3. gVisor：内核语义的「另一条用户态路线」，但在 Android 上更远

### 3.1 它是什么（与 UML 的区别）

- **[官方事实]** gVisor 是「application kernel」：Sentry 用 Go 实现 Linux ABI，拦截应用 syscall，不翻译指令；官方明确把它与 UML 对比：「gVisor's approach is similar to User Mode Linux (UML), although UML virtualizes hardware internally and thus provides a fixed resource footprint.」（[gVisor 文档](https://gvisor.dev/docs)）
- **关键差异**：gVisor 没有「真内核」——它是 ABI 兼容层，`/proc`、`/sys`、netfilter、cgroup、内核模块、eBPF 都是模拟的。对本项目而言，**它不满足 Full Linux 契约里的「guest 内核」承诺**（systemd 可能跑，Docker 可能跑，但内核语义是模拟的，且大量 ioctl/文件系统特性缺失）。

### 3.2 平台与 rootless 要求

- **[官方事实]** 三个平台：`ptrace`（PTRACE_SYSEMU，开销最高）、`systrap`（`SECCOMP_RET_TRAP` + SIGSYS，2023 年中起的默认）、`KVM`（需 `/dev/kvm`，性能最好）。systrap 不需要特殊设备，但需要内核 5.3+。
- **[官方事实]** rootless 的前提是 **user namespace**：「Rootless containers rely heavily on user namespaces (user_namespaces(7)) to emulate the fake privileges (like CAP_SYS_ADMIN and CAP_SETUID)」；`runsc --rootless` 限制包括不支持 `create`、不支持 netstack、忽略 cgroup 配置。
- **[社区经验]** Termux 包请求 issue 明确写：「Rootless containerization possible only with custom kernels where user namespaces are enabled.」（[termux-packages#4901](https://github.com/termux/termux-packages/issues/4901)）

### 3.3 Android 上的判定

| 门 | 判定 | 说明 |
| --- | --- | --- |
| user namespace | **大概率不可用** | 依赖内核 `CONFIG_USER_NS` 与 ROM 策略；[本项目未验证]，需真机 `unshare -U` 探针 |
| mount / chroot | **SIGSYS** | `mount`/`umount2`/`chroot` 在 App seccomp blocklist；即便在 userns 内也不需要宿主 mount（Sentry 自实现 VFS），但 runsc 的 sandbox 初始化仍依赖若干被 block 的路径 |
| ptrace | 允许（self） | 但 ptrace 平台是 legacy，性能最差 |
| KVM 平台 | 不可用 | 无 `/dev/kvm` |
| 结论 | **不作为候选** | 即使能跑，也没有真内核；收益（比 proot 更强的隔离）与我们的契约不匹配 |

**价值定位**：gVisor 是「Userspace Compatibility 的强化版」参考，不是 Full Linux 的替代。它的存在反而澄清了一件事：**「用户态内核」有两条路——UML 走真内核，gVisor 走 ABI 重实现；只有前者能满足 (b)。**

---

## 4. AVF / protected-only 的准确语义

### 4.1 「protected-only」是平台能力声明，不是权限

- **[官方事实]** `VirtualMachineManager.getCapabilities()`：
  ```java
  if (HypervisorProperties.hypervisor_protected_vm_supported().orElse(false)) result |= CAPABILITY_PROTECTED_VM;   // 1
  if (HypervisorProperties.hypervisor_vm_supported().orElse(false))          result |= CAPABILITY_NON_PROTECTED_VM; // 2
  ```
  （`libs/framework-virtualization/.../VirtualMachineManager.java`，AOSP main `175a51b`）
- **[官方事实]** `setProtectedVm(false)` 在能力位缺失时直接抛：
  ```java
  throw new UnsupportedOperationException("Non-protected VMs are not supported on this device.");
  ```
  （`VirtualMachineConfig.java:1211-1222`）
- → **这不是 Shizuku/ADB 能授予的权限**，是 bootloader/hypervisor 通过系统属性声明的能力。protected-only 设备上，任何身份（App、shell、root 以外）都无法创建 non-protected VM；root 也只能改属性/绕过服务，属于本项目禁止范围。

### 4.2 自定义 protected VM 的四道门

| 门 | 事实 | 依据 |
| --- | --- | --- |
| 启动身份 | AOSP `custom_vm.md` 的自定义 VM 流程以 `adb root` 为前提（`adb root` → push kernel/initrd → `vm run`） | [官方事实] `docs/custom_vm.md` |
| 镜像信任 | pvmfw 用 AVB 验证 kernel/initrd，信任键是**设备固件内嵌**的（AOSP 默认 `avb_testkey_rsa4096`，OEM 可替换；Qualcomm QTVM 路径可能在 TrustZone 完成验证） | [官方事实] `guest/pvmfw/README.md`、本地 2026-10-17 研究 |
| 文件标签 | virtmgr 对 kernel/initrd/disk 的 FD 做标签检查，明确排除 `app_data_file`/`privapp_data_file` | [官方事实] A16 `virtmgr/src/aidl.rs`（本地研究已固定） |
| 设备/网络 | protected 分支拒绝 platform network；GPU 需 device assignment（VM DTBO 在 dtbo 分区、IOMMU token 由 pvmfw 预置） | [官方事实] `docs/device_assignment.md`、本地研究 |

**结论**：第三应用（无 Root、无 OEM 合作）**无法**在 protected-only 设备上交付「自己的内核 + 自己的发行版」。能交付的只有 Google 签名的 Microdroid + 应用 payload。

### 4.3 Microdroid payload 的硬边界（源码级）

| 能力 | 判定 | 依据 |
| --- | --- | --- |
| 从 APK `bin/` 执行 | 允许 | zipfuse `bin/` 可执行；payload 从 `/mnt/apk` 加载 |
| encrypted storage 执行 | 禁止 | 挂载带 `MS_NOEXEC`；`encryptedstore_file` 只有 `create_file_perms`（无 execute） |
| chroot/mount | 禁止 | payload caps 清空、无 `sys_admin` |
| ptrace | **无 allow**（SELinux 默认拒绝），非 neverallow | `microdroid_payload.te` |
| vsock 出站 | neverallow | `neverallow microdroid_payload self:vsock_socket connect;` |
| TCP | 无通用 allow | `microdroid_app` 无 `tcp_socket` 规则 |
| **execmem / execmod** | **neverallow（全 VM）** | `microdroid/system/private/domain.te:271-272`：`neverallow * self:process { execmem execheap execstack };` `neverallow * *:file execmod;` |
| GPU | 无 graphics HAL | Microdroid 定位无 UI/无图形 |

**第 7 行是本文对「UML 放进 protected pVM」的终局判决**：Microdroid 的策略在编译期就禁止了任何域使用 `execmem` 与 `execmod`；同时 Microdroid 里没有 `app_domain()` 宏带来的 `appdomain_tmpfs:execute` 通道，唯一可写目录 encryptedstore 又是 noexec。UML 需要的「可执行 guest RAM 后备」在这里一条路都没有。即使忽略 ptrace/noexec，这一条也无法在应用侧绕过（改策略 = 改系统镜像 = Root/签名）。

顺带：这也解释了为什么 Fast Lane 只能做 AOT 工具链——**任何 JIT（Node/V8/JVM）在 Microdroid 里都会被 `execmem` neverallow 拒绝**，这是产品能力矩阵必须写死的项。

### 4.4 pVM 的性能特征（可用的部分）

- **[官方事实]** 硬件 vCPU：CPU-bound 接近原生（无指令翻译），这是 Fast Lane 的真实价值。
- **[官方事实]** stage-2 映射与 THP：Android 15 起 pKVM 支持透明大页，但 `/sys/kernel/mm/transparent_hugepages/shmem_enabled` 默认 `never`，需要宿主改 `advise` 并在 `vm_config.json` 开 `"hugepages": true`；普通 App 改不了 sysfs。**LWN 6.16 的表述限定为「non-protected guest」**（"64-Bit Arm now supports transparent huge pages on non-protected guests when protected KVM is enabled"），而 AVF `hugepages.md` 未区分 protected/non-protected——两者是否冲突**未核实**，因此本文不把「protected 无 THP」当作已证事实，只记为风险（[LWN 6.16 合并窗口](https://lwn.net/Articles/1023075/)，[AVF hugepages.md](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/docs/hugepages.md)）。
- **推论**：protected pVM 的 CPU 接近原生，但 stage-2 缺页与 TLB 压力高于 non-protected；对编译这类大内存工作负载，收益会低于裸机比例。具体数字 `not-tested`。
- **嵌套虚拟化**：guest 运行在 EL1、pKVM 在 EL2（[AVF 架构文档](https://source.android.google.cn/docs/core/virtualization/architecture)），guest 不可能再拥有 EL2 → **pVM 内不可能再跑 KVM/QEMU 加速**。这直接否掉了「在 pVM 里开 QEMU 用硬件加速」的想法（既有研究 §11 已列为不建议路线，此处给出架构理由）。

---

## 5. 混合架构与先例

| 混合 | 是否成立 | 说明 |
| --- | --- | --- |
| H1 UML guest + pVM distcc | 技术成立，价值低 | UML guest 已是原生指令；再挂 pVM 只是增加并行核数。但 UML 的进程内多线程串行，`make -j` 仍可受益。复杂度高于直接用 QEMU+pVM |
| H2 UML inside protected Microdroid | **不成立** | `neverallow execmem/execmod` + encrypted store noexec + 无 ptrace allow + 无 TCP + caps 清空 |
| H3 pVM + virtio-fs + 软件渲染 + VNC over vsock | 部分成立 | vsock 可承载 RFB；guest 内 llvmpipe/Weston 可出画面。但 protected 下 virtio-fs/sharedPath 的可用性**未核实**（AOSP 的 GPU/sharedPath 样例都是 `protected:false`），且无 GPU |
| H4 「内核服务」拆分（原生用户态 + pVM 做内核相关操作） | 语义上成立，工程上是 RPC | 例如「格式化 ext4 / 挂载 loop / 跑 Docker」交给 pVM，宿主用户态只做 I/O。每次跨边界都是一次协议设计，不能透明迁移 syscall |
| H5 LKL（Linux Kernel Library） | 不适用 | 内核编译成库、单地址空间、无独立进程模型，跑不了 systemd/发行版；[本项目未核实 arm64 Android 支持] |
| H6 WSL1 式（宿主内核 + 翻译层） | 不适用 | 需要宿主内核驱动/内核模块（LXCore），无 Root 不可能 |
| H7 QEMU user-mode | 不适用 | 无 guest 内核，只是跨架构 syscall 转发 |

**先例的价值**：H2 是用户假设最自然的落地形式（「pVM 里跑一个用户态内核，既有硬件 vCPU 又能跑完整发行版」），也是**最干净地被源码否掉**的一条。把这条写进 ADR/ACR 的「已评估并否决」清单，可以避免后续重复投入。

---

## 6. 能力矩阵与候选排序

### 6.1 矩阵

| 候选 | 真 guest 内核 | 原生用户态执行 | 硬件 vCPU | 免 Root | protected-only 可用 | GPU | systemd/Docker | 原生网络 | 文件共享 | 成熟度 | 许可 | 主要风险 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| QEMU TCG（现状 Full Lane） | ✅ | ❌（翻译） | ❌ | ✅ | ✅ | VirGL（未验收） | ✅ | ✅ | 9p | 高 | GPLv2 | 慢 |
| proot / proot-distro | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅（宿主） | bind | 高 | GPLv2/3 | 弱隔离、syscall 开销 |
| Termux / glibc-runner | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ | 前缀 | 高 | GPLv3 等 | 无内核语义 |
| **UML（legacy 域）** | ✅ | ✅ | ❌ | ✅ | ✅ | ❌（软件渲染） | ✅（guest 内） | ✅（vec/passt） | hostfs/9p | 中低（RFC+真机，无维护） | GPLv2 | targetSdk≤28、16 KB 宿主、UP、非安全边界 |
| UML（现代 App） | ✅ | ✅ | ❌ | ✅ | ⚠️（取决于 D1） | — | — | — | — | — | — | **未定：`execute` 门（D1）** |
| Microdroid payload（现状 Fast Lane） | ✅（Google 的） | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌（仅 vsock） | ❌ | 中 | Apache-2.0 + GKI | execmem neverallow、noexec、无网络 |
| Protected 自定义 pVM | ✅（自有） | ✅ | ✅ | ❌（需 adb root） | ⚠️（信任键/标签） | ❌ | ✅（若镜像自洽） | ❌ | ❌ | 低 | GPLv2 + Apache | pvmfw 信任、标签门、不可交付 |
| gVisor | ❌（ABI 模拟） | ✅ | ❌（KVM 平台不可用） | ⚠️（需 userns） | ⚠️ | ❌ | 部分 | netstack | gofer | 中 | Apache-2.0 | userns/mount 门 |
| LKL | ✅（库） | ✅ | ❌ | ⚠️ | ⚠️ | ❌ | ❌ | ❌ | ❌ | 低 | GPLv2 | 无进程模型 |

### 6.2 排序（针对「protected-only 无 Root」的产品目标）

**第 1：QEMU Full Lane + protected Fast Lane distcc（现状）**
- 买什么：完整契约（内核/systemd/Docker/网络/GPU 路线）不变；编译等 CPU 密集环节拿到硬件 vCPU。
- 牺牲什么：不是单一执行环境；distcc 需编译器 ABI 一致；Fast Lane 无网络/GPU。
- 硬门：P09 协议/编译器一致性；DV-03 真机收益。
- 停止条件：`distcc` 端到端 < 1.2× 或失败率高 → 退回 QEMU 调优。

**第 2：Protected 自定义 pVM（研究线，时间盒）**
- 买什么：真内核 + 硬件 vCPU + 完整发行版（若信任链允许），是唯一同时满足 (a)(b)(c) 的形态。
- 牺牲什么：无 Root 承诺、无原生网络、无 GPU、依赖 OEM 信任键与文件标签。
- 硬门：pvmfw 是否信任 AOSP test key；virtmgr 是否接受可用标签；`vm run` 是否必须 root。
- 零设备探针：读 AOSP `pvmfw`/`virtmgr` 源码 + 目标 ROM 的 `vm info`/属性（shell）；真机探针：`vm run --protected --custom` 最小 initramfs。
- 停止条件：任一门要求改 pvmfw/SELinux/解锁 → 停止，不产品化。

**第 3：UML（legacy 域 / Termux 类，研究线）**
- 现状：LKML RFC（2026-08-15）与下游源码已证明 arm64 可跑（Alpine/Debian、4K/16K、Docker/systemd 自述、~2 µs/syscall），但作者明确不维护、UP only、无 32-bit compat（§2.7）。
- 买什么：真内核 + 原生指令 + 单机 systemd/Docker，不需要 hypervisor；可作为「Userspace Compatibility 之上、Full Linux 之下」的新档位探索。
- 牺牲什么：现代 targetSdk、protected 隔离、16 KB 宿主、GPU、多线程用户态并行、GPL 义务、arm64 上游缺失。
- 硬门：guest RAM 后备文件的 `execute`（现代 App 的已知可执行路径只有 tmpfs 对象；`app_data_file` 是否也允许仍待 D1 判定）、`check_tmpexec`、宿主页大小、下游补丁可维护性。
- 零设备探针：在 Linux arm64 主机用下游补丁构建 + 启动 Alpine（验证补丁可构建、可跑）；静态核对 `check_tmpexec`/`phys_mapping`/`file_map_prot_to_av`。
- 真机探针：Termux 控制组（targetSdk 28）跑 UML；现代 App 组分别对 filesDir 文件与 memfd 做 `mmap(PROT_EXEC)` 探针，记录 errno + AVC。
- 停止条件：需要 targetSdk ≤ 28 或改 SELinux 才能跑 → **不作为产品架构**，只留研究结论。

### 6.3 方案：三条车道（UML 作为 Full Linux 的第三种执行策略）

> 这一节是产品方案，不是已批准的架构变更；需要 ACR。它把 §6.2 的排序落成一个可执行的产品结构，并回答「protected-only 设备到底怎么同时拿到原生速度、真内核和 pVM」。

**定位澄清**：UML 有真实 guest 内核，按 `CONTEXT.md` 的类别定义应归入 **Full Linux Runtime**，而不是 Userspace Compatibility；它缺的是 GPU、硬件 vCPU、protected 隔离，这些可以作为「策略专属增强」写进能力矩阵（ADR-0002 允许）。因此推荐的是 **Full Linux 的第三种执行策略**，不是「第三 Runtime 类别」。§6.2 里「Userspace Compatibility 之上、Full Linux 之下」只是产品直觉，不是类别定义；若 ACR 认为 UML 无法满足 Full Linux 契约，再考虑新类别，但不要在未裁定前改 `CONTEXT.md`。

**三条车道**：

| 车道 | 执行策略 | 得到 | 放弃 | 适用 |
| --- | --- | --- | --- | --- |
| **QEMU Full** | QEMU TCG + VirGL | 完整契约、GPU、全设备、成熟 | CPU 慢 | 桌面、GPU、兼容性、默认 |
| **UML Native**（研究线） | `ARCH=um`：真内核作为 App 进程，guest 指令原生执行 | 真内核、原生 CPU、Docker/passt、无 Root、protected-only 可用（不经过 AVF） | GPU、pVM、protected 隔离、UP only、现代 App 待 D1、16 KB 宿主待验 | CPU 密集、终端、开发、Docker |
| **pVM Fast** | protected Microdroid | 硬件 vCPU、protected 隔离、密封工具链 | 自有内核、systemd/Docker、原生网络、GPU | 编译、工具、受保护任务 |

**共享面**（Full Linux 契约，策略中立）：

- 同一 **rootfs / DistroManifest**：QEMU 用 virtio-blk，UML 用 `ubd`，读同一个 raw ext4；Provisioning 与导出/导入共用。
- 同一 **guest agent / bridge 协议**：QEMU 走 vsock，UML 走 passt/vec 上的 TCP；协议本身不感知传输。
- 同一 **终端 / 端口转发 / 共享目录**语义：UML 的端口转发由宿主侧 passt/代理实现；共享目录用 hostfs/9p，且保持只读 Downloads 白名单。
- 同一 **生命周期**：UML 进程组由 `RuntimeProcessSupervisor` 类组件持有；停止确认、根盘 lease、冷恢复沿用现有模型；UML 不引入第二个 stop owner。
- 同一 **能力矩阵**：UML 行显式写 `GPU=UNSUPPORTED`、`HARDWARE_VIRTUALIZATION=UNSUPPORTED`、`PROTECTED_VM=UNSUPPORTED`、`systemd/Docker=待验证`；UI 徽章只消费矩阵。

**策略专属机制**（不进契约）：UML 的 `ubd`、passt/vec、hostfs、`stub_exe`、`check_tmpexec` 都是实现细节；QEMU 的 virtio/gvproxy、pVM 的 vsock 代理同理。

**Lane Router**：需要 GPU/桌面/最大兼容 → QEMU；需要 CPU 原生 + Docker/完整内核 → UML（D1 通过后）；需要 protected 硬件 vCPU/密封工具 → pVM Fast；不需要内核 → proot/Termux（Userspace Compatibility，独立类别）。Auto 默认 QEMU，保守。

**隔离表述**：UML 是进程级（guest 内核是 App 进程，guest 用户进程在 App 沙箱内），按 ADR-0010 的 L1 措辞描述；**不得写 protected / 硬件隔离 / 硬件加速**。pVM 才是 protected；QEMU 是 L1 软件虚拟化；三者不能混写。

**为什么不把 pVM 当 UML/QEMU 的加速器**：pVM 的 vCPU 只属于 pVM 内的 guest；它不能把 vCPU 借给宿主进程，也不能给 QEMU 提供 KVM；UML 是宿主进程，更拿不到。三合一不是「再写一个桥」，而是需要平台把内核纳入信任根。把这条写进 ACR 的「已评估并否决」清单，避免后续重复投入。

**产品语言**：UML 车道建议叫「原生内核（实验）」/“Native Kernel (Experimental)”；性能徽章写「原生指令，syscall 有开销」，不写「硬件加速」；在 D1、16 KB 宿主、systemd/Docker、`stub_exe` 四门闭合前，不进入发布说明、不上默认 UI。

**当前产品路线不变**：QEMU Full Lane 继续交付；protected Fast Lane 继续做 distcc/工具卸载；UML 只作为时间盒研究线，不阻塞 P0/P1 发布门。

---

## 7. 可执行的验证计划

### 7.1 零设备（本环境可做，不需要 adb）

| ID | 内容 | 通过判据 |
| --- | --- | --- |
| Z1 | 用下游 arm64 UML 补丁在 arm64 Linux 主机（容器）构建 `ARCH=um SUBARCH=arm64`，启动 Alpine initramfs | 能构建 + 能到 shell（只证明补丁可用，不证明 Android） |
| Z2 | 源码核对：`check_tmpexec` 调用点、`phys_mapping` 偏移语义、`os_map_memory` 的 `MAP_SHARED|MAP_FIXED`、`file_map_prot_to_av` 的 `execute`/`execmem` 语义；输出「4 KB guest 在 16 KB 宿主」的对齐反例清单 | 能在源码行号上闭环 |
| Z3 | 用 `checkpolicy`/`sesearch` 对 AOSP `system/sepolicy` 求值：现代 `untrusted_app`/`untrusted_app_27` 对 `app_data_file`、`appdomain_tmpfs` 的 `execute`；Microdroid 的 `execmem`/`execmod` neverallow | 与 §2.3、§4.3 结论一致 |
| Z4 | 检索 Android 是否设置 `vm.memfd_noexec`（init.rc / sysctl 配置 / GKI config），以及 `memfd_create(MFD_EXEC)` 在 App 域的行为 | 给出「memfd 缝隙」是否存在的结论 |
| Z5 | 读目标 ROM 的 `ro.boot.hypervisor.*`（shell，只读）与 `vm info` 输出 | 确认 protected-only 声明（已知） |

### 7.2 真机（需设备窗口，串行）

| ID | 内容 | 预期/停止 |
| --- | --- | --- |
| D1 | 现代 App（targetSdk 36）三组探针：① filesDir 临时文件 `mmap(PROT_READ\|PROT_WRITE\|PROT_EXEC, MAP_PRIVATE)`；② 匿名 `mmap(PROT_EXEC)`；③ `memfd_create` + `mmap(PROT_EXEC)`。记录 errno + AVC | ① 预期 `EACCES`（`execute`）；② 预期成功（`execmem`）；③ 决定「memfd 缝隙」是否存在。若 ① 或 ③ 通过 → 结论翻转（见 §8） |
| D2 | 同一组探针在 Termux（targetSdk 28）里做 | 预期全过（对照组） |
| D3 | Termux 内跑下游 UML 内核（1 vCPU、256–512 MB、Alpine） | 预期可启动；记录 16 KB 页行为（若设备是 16 KB 内核，预期失败） |
| D4 | Microdroid payload 内做 `execmem` 探针（匿名 `mmap(PROT_EXEC)` + memfd 变体） | 预期拒绝；确认 JIT 不可用 |
| D5 | `getCapabilities()` + `setProtectedVm(false)` 抛异常 + `vm info`（shell） | 预期 protected-only 确认 |
| D6 | 性能基线：QEMU TCG 内 `make -j` / `openssl speed` vs 宿主原生 vs（若 D3 成功）UML，n≥10、控温 | 只记录，不外推 |

**D1 是最高优先级**：它一条就能判定 UML 路线在我们产品里是否还有任何可能。

### 7.3 若 D1 意外通过（结论翻转的条件）

- 若 ① 通过：说明现代 App 域对 `app_data_file` 的 `execute` 可得（无论 AOSP 本就有一条未定位的 allow，还是 OEM 放宽）→ UML 可直接启动，但仍受 16 KB 宿主、单进程 stub、GPL、arm64 下游补丁、无 GPU 约束。
- 若 ③ 通过而已 ② 失败：说明存在「memfd 缝隙」，可以把 UML 的 physmem 改成 memfd（下游 patch，非上游），但需同时确认 `vm.memfd_noexec` 与 16 KB 页。
- 任一情形成立，都应开 ACR 讨论新 Runtime 类别，而不是直接改 `CONTEXT.md`。

---

## 8. 未知与停止条件

### 8.1 未核实（不得用训练知识填补）

1. arm64 UML 是否有人接手维护、能否合入 mainline：LKML RFC（2026-08-15）已确认现状（33 patches、作者不维护、UP only、无 32-bit compat），但没有 review 结论或后续版本。
2. 目标 ROM（protected-only 设备）的 `pvmfw` 实际信任键；AOSP 默认 test key 是否仍有效。
3. protected pVM 下 `virtio-fs`/`sharedPath` 的可用性（AOSP 样例均为 `protected:false`）。
4. 现代 App 在目标 ROM 上对 `app_data_file` 的实际 `execute` 结果——AOSP 源码自相矛盾（§2.3.2），只能靠 D1 真机判定。
5. Android 是否设置 `vm.memfd_noexec`，以及 `memfd_create` 在 App 域得到的实际标签。
6. Android GKI 在目标设备上是否启用 `CONFIG_USER_NS`（影响 gVisor，不影响 UML）。
7. UML 在 16 KB 宿主上的实际失败点（本文为源码推断，未实测）。
8. 任何 UML / pVM 的本项目性能数字。

### 8.2 停止条件

- 需要 Root / 解锁 / 改 SELinux / 改 pvmfw / 改 targetSdk 才能继续 → **停止**（记录为研究结论）。
- 需要把 guest 盘或用户数据放到 `/data/local/tmp` 等公共可写位置才能跑 → **停止**（违反 ADR-0007 与隐私边界）。
- 需要引用「UML 快」但拿不出同设备 n≥10 数据 → **不得写入产品文案**。
- 把 protected pVM 描述成「完整 Linux」或把 Microdroid payload 描述成「发行版」 → **停止**（能力矩阵必须逐项 UNSUPPORTED）。

### 8.3 对现有文档/决策的提示（只记录，不修改）

- `CONTEXT.md` 现有三类执行策略（硬件虚拟化 / 软件虚拟化 / Userspace Compatibility）无法安放 UML：它「有 guest 内核但不隔离、原生执行但不硬件虚拟化」。若要产品化，需新 ACR，而不是塞进现有类别。
- `ACR-03` 的推荐（第三类 ALW）与本文结论一致：protected Fast Lane 不是 Full Linux。本文补充一条硬事实：**Microdroid 的 `execmem` neverallow 使该类别永久无法支持 JIT**，能力矩阵应显式写死。
- 既有研究「protected 内不能跑完整发行版」的结论，本文给出第二组独立理由（`execmem`/`execmod` neverallow + encryptedstore noexec + 无 tmpfs 可执行通道），可与 label/网络门并列引用。

---

## 9. 来源

访问日期均为 2026-10-28；内核源码经 GitHub `torvalds/linux` 镜像读取（kernel.org 本环境被 Anubis 拦截）。

### 9.1 内核 / UML

1. `arch/um/Kconfig`（`select HAVE_PAGE_SIZE_4KB` 第 29 行；SMP 第 94–116 行）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/arch/um/Kconfig
2. `arch/um/Makefile`（`HEADER_ARCH` x86-only）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/arch/um/Makefile
3. `arch/um/os-Linux/mem.c`（`check_tmpexec()`，`create_mem_file()`）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/arch/um/os-Linux/mem.c
4. `arch/um/os-Linux/start_up.c`（`os_early_checks()`，`seccomp=` 帮助文本，SMP 需 seccomp）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/arch/um/os-Linux/start_up.c
5. `arch/um/kernel/physmem.c`（`setup_physmem()` W+X 映射，`phys_mapping()` offset=phys）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/arch/um/kernel/physmem.c
6. `arch/um/os-Linux/process.c`（`os_map_memory()` `MAP_SHARED|MAP_FIXED`）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/arch/um/os-Linux/process.c
7. `arch/um/kernel/exec.c`（`start_thread()`）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/arch/um/kernel/exec.c
8. `security/selinux/hooks.c`（`file_map_prot_to_av()` 第 4051–4061 行；`__file_map_prot_check()` 第 4098–4138 行；`execmod` COW 检查第 4268 行附近）：https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/security/selinux/hooks.c
9. Porting UML to a new architecture：https://user-mode-linux.sourceforge.net/old/arch-port.html

### 9.2 Android 策略 / seccomp / AVF

10. `system/sepolicy/private/untrusted_app_27.te`（`execmod`、`execute_no_trans`）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_27.te
11. `system/sepolicy/public/te_macros`（`app_domain()` 第 242–248 行：`type_transition $1 tmpfs:file appdomain_tmpfs` + `allow $1 appdomain_tmpfs:file { execute ... }`）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/public/te_macros
12. `system/sepolicy/private/app_neverallows.te`（`execute_no_trans` neverallow，第 68–73 行）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te
13. `system/sepolicy/private/app.te`（`allow appdomain self:process execmem` 第 189 行；`app_data_file` 权限第 268–277 行）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te
14. `system/sepolicy/private/untrusted_app_all.te`（`allow untrusted_app_all self:process ptrace` 第 103 行）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_all.te
15. `system/sepolicy/microdroid/system/private/domain.te`（`neverallow * self:process { execmem execheap execstack }`、`neverallow * *:file execmod` 第 271–272 行）：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/microdroid/system/private/domain.te
16. `system/sepolicy/microdroid/system/private/microdroid_payload.te`：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/microdroid/system/private/microdroid_payload.te
17. bionic `SECCOMP_BLOCKLIST_APP.TXT` / `SECCOMP_ALLOWLIST_COMMON.TXT` / `seccomp_policy.cpp`：https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/
18. `VirtualMachineManager.java`（`getCapabilities()`）：https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineManager.java
19. `VirtualMachineConfig.java`（`setProtectedVm()` 抛 `UnsupportedOperationException`，第 1211–1222 行）：https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java
20. AVF `docs/custom_vm.md`（`adb root` 流程）：https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/docs/custom_vm.md
21. AVF `docs/hugepages.md`（THP 默认关闭、`"hugepages": true`）：https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/docs/hugepages.md
22. AVF `docs/device_assignment.md`（VM DTBO、IOMMU token、pvmfw 预置）：https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/docs/device_assignment.md
23. AVF 架构（EL0/EL1/EL2 与 pKVM）：https://source.android.google.cn/docs/core/virtualization/architecture

### 9.3 gVisor / 其他

24. gVisor 概览（与 UML 的对比、"reduced application compatibility and higher per-system call overhead"）：https://gvisor.dev/docs
25. gVisor 平台指南（ptrace/systrap/KVM）：https://gvisor.dev/docs/architecture_guide/platforms/
26. gVisor rootless（user namespace 前提与限制）：https://gvisor.dev/docs/user_guide/rootless/
27. termux-packages#4901（rootless 需 user namespace，[社区经验]）：https://github.com/termux/termux-packages/issues/4901
28. PRoot 官方站（ptrace 路径重写、无 guest kernel）：https://proot-me.github.io/
29. Igalia 2007 对比基准（UML vs KVM vs QEMU）[社区经验]：https://blogs.igalia.com/berto/2007/06/27/comparing-virtualization-software-
30. LWN 6.16 合并窗口（arm64 pKVM THP 表述）：https://lwn.net/Articles/1023075/
31. Podroid 限制页（AVF 仅 Pixel、protected-only 回退 QEMU、TCG 5–20× 慢）[社区经验]：https://extv.github.io/Podroid/guide/limitations.html

### 9.4 社区 UML-on-Android 项目（自述，未独立复现）

32. https://github.com/Raymer8639/linux-um-arm64
33. https://github.com/michaelamoetoevsky/uml4termux
34. https://github.com/fahrulseptiana/drolin
35. https://github.com/zalexdev/linux-um-arm64
36. https://github.com/quangdz-comeback/uml-kernel-build/blob/main/docs/technical_changelogs.md

### 9.5 下游 arm64 UML 移植（一手 RFC + 源码）

37. LKML RFC `[RFC] um: an arm64 subarch for User Mode Linux`（2026-08-15）：https://lkml.iu.edu/hypermail/linux/kernel/2608.1/14307.html
38. 上游 UML SMP 提交 `1e4ee5135d81`（Tiwei Bie，2025-10-27）：https://github.com/torvalds/linux/commit/1e4ee5135d81
39. `zalexdev/linux-um-arm64` 分支 `um-arm64` README（状态、Benchmarks、Build、Upstream 节）：https://github.com/zalexdev/linux-um-arm64/blob/um-arm64/README.md
40. 同仓 `arch/arm64/um/` 源码树（Kconfig、syscalls.c、ptrace.c、stub_segv.c 等）：https://github.com/zalexdev/linux-um-arm64/tree/um-arm64/arch/arm64/um
41. 同仓 `arch/um/os-Linux/skas/process.c`（stub 经 `memfd_create(..., MFD_EXEC)` exec；`stub_exe=` 回退）：https://github.com/zalexdev/linux-um-arm64/blob/um-arm64/arch/um/os-Linux/skas/process.c
42. 同仓 `arch/um/os-Linux/mem.c`（`check_tmpexec()` 未被修改）：https://github.com/zalexdev/linux-um-arm64/blob/um-arm64/arch/um/os-Linux/mem.c

---

报告人：CodeBuddy / DeepSeek-V4.1-Flash ｜ 日期：2026-10-28 ｜ 本文未运行设备/构建；所有「本项目」性能与真机结论为 `not-tested`。

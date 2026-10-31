# 03: SELinux/seccomp 源码闭环——execute allow 链、execmod 边界、rseq/robust_list 机制归因

Type: research
Status: resolved（fabric-batch2，2026-10-31）
Priority: P1
Owner: fabric-batch2
Blocked by: None
External gates: 无（源码/联网，零设备）

## 背景

会话 21 真机三探针全过 + `untrusted_app_all.te:27` 的 `execute` allow（CodeBuddy 调研 R3）已把 10-28 文档的矛盾闭环，但闭环证据尚未形成正式对照表进入 ACR 材料。另外 Haven 的「signal 31 静默死亡（set_robust_list/rseq 被杀）」机制未归因——本项目内核若不处理同类问题，会在真机上复现。

## 必须回答

- [ ] 把以下结论表格化（文件 + 行号 + 内容，以 sepolicy main 当前版本核对）：① `untrusted_app_all.te:27-28` execute allow + auditallow；② `execute_no_trans` neverallow（targetApi ≥29，`app_neverallows.te`）；③ `execmod` neverallow 与 25/27 豁免（`private/domain.te:1344-1351`）；④ `appdomain_tmpfs:file execute` 通道（`te_macros` app_domain）；⑤ Microdroid `domain.te:271-272` neverallow execmem/execmod。
- [ ] **W^X 三边界对 UML 的操作含义**：哪些动作合法（文件 mmap PROT_EXEC）、哪些被禁（execve 数据目录、可写后执行），逐条映射到 UML 启动链（`check_tmpexec` → physmem → stub_exe → guest RAM 缺页映射）。
- [ ] **rseq/`set_robust_list` 归因**：核对 bionic `SYSCALLS.TXT` / `SECCOMP_ALLOWLIST_COMMON.TXT` / `SECCOMP_BLOCKLIST_APP.TXT`（main + android16-release + android17-release）中这两个调用与 `process_vm_*`、`ptrace` 的现状；解释 Haven signal 31 的可能机制（seccomp SIGSYS vs SELinux vs 内核版本差异），给出本项目内核构建的对策建议（阉割 vs 保留），与工单 01 的 S3 checklist 互相引用。
- [ ] 评估 `checkpolicy`/`sesearch` 是否在本环境可行；可行则对上述规则做编译级求值，不可行则保持源码级闭环并说明。

## 交付

- `docs/research/2026-11-XX-selinux-seccomp-closure.md`（正式对照表，可直接引用进 ACR 材料）。

## 停止条件

- 源码结构与已知结论矛盾 → 停下来记录矛盾，不强行解释。

## Comments

- 2026-10-31 / CodeBuddy（ask-matt 轮）：建票。参考：CodeBuddy 调研 §2.3；会话 21 §4；WorkBuddy 报告 §2.2 教训 3。
- 2026-10-31 / CodeBuddy（批次 1 收口后追加输入更新）：
  1. **工单 01 转来的归因缺口**：S3 checklist 只落了机制假设，本票负责源码级归因——rseq/`set_robust_list` 在 bionic `SYSCALLS.TXT`/`SECCOMP_ALLOWLIST_COMMON.TXT`/`SECCOMP_BLOCKLIST_APP.TXT`（main + android16/17-release）的现状；UML guest 内 rseq 注册真实触发的宿主 syscall 路径；结论三选一：内核阉割 / 启动器规避 / 无需处理，并回写 01 的 `checklist-haven-three-pits.md`。
  2. **工单 02 缩小了排查面**：AOSP main 的 execmod 豁免面/seapp 域未变（见 `docs/research/2026-11-02-platform-policy-matrix.md`），直接引用，不重查。
  3. **环境事实**：宿主无 gcc/clang；`checkpolicy`/`sesearch` 编译级求值走 docker 容器（如 debian + setools），不可行则保持源码级闭环并说明。
  4. 01 遗留的「树内 defconfig 干净对照」可选并入本票（仅当与 rseq 归因共享构建产物时才划算），否则标 wontfix——执行会话按性价比裁定。
  5. 基线调研 B 已补写入库：`docs/research/2026-10-31-uml-full-spectrum-research.zh-CN.md`（此前工单引用缺失，现已可读）。
- 2026-10-31 / fabric-batch2（批次 2 收口）：**resolved**。交付 `docs/research/2026-11-03-selinux-seccomp-closure.md`（协调会话复核通过）；checklist 坑① 已回写（原假设文字保留，追加闭环段）。关键结论：
  1. 五问闭环表全部命中（①③⑤ 行号与基线一致，② 漂移 1 行 :68-73，④ 无实质漂移；main 已把 29–33 拆为 `_29/_30/_32` 域但豁免面仍 -25/-27，结论不变）。
  2. **工单前提被源码反转**：bionic（main+11/12/16/17-release）从不发起 rseq/`set_robust_list`（无 SYSCALLS.TXT 声明、全树无调用点），app seccomp 允许表三分支均**不含**这两个调用、默认动作 TRAP=SIGSYS(31)——两事实自洽。Haven signal 31 机制 = stock seccomp TRAP × **glibc≥2.35** 构建的内核（glibc 启动即发起两调用；Haven 内核 libc 身份 [未找到证据]，唯一推测环节），**不适用本项目 NDK bionic 内核**，与会话 21 未 neuter 正常启动互证。
  3. 三选一 = **无需处理（保留，不阉割）**；checklist 检查项 1-2 转条件性（仅当换非 bionic libc 时启用）。
  4. W^X 收窄修正（非推翻）：execmod 仅「COW 后 mprotect-to-exec」触发（hooks.c:4266-4275），mmap 阶段（含 MAP_SHARED 可写 fd+PROT_EXEC）只查 `execute`；UML 启动链全路径走 execute/execmem 已允许通道；「rootfs 只读镜像」应改述为产品决策而非宿主 neverallow 直接推论。
  5. checkpolicy/sesearch：docker 容器可行，合成策略编译冒烟 + 控制实验（neverallow 检查真实生效）通过；完整树编译需复刻 soong 装配、性价比不支持 → 保持源码级闭环。sesearch 原理上无法查 neverallow（编译期断言不进二进制）。
  6. 工单 01 遗留「树内 defconfig 干净对照」判 **wontfix**（不共享构建产物、批次 1 已记探针污染）。
  未验证项：Haven 内核 libc 身份、OEM 定制 seccomp filter、会话 21 三探针 mmap flag 细节、b/237289679 正文。设备窗口增量 3 项见交付 §6。停止条件命中一次（前提反转），已按「记录矛盾不强行解释」协议处理。

# 04: Android-like seccomp / host compatibility harness

Type: implementation
Status: resolved
Priority: P1（决定 UML 在 36 域 App 内哪些能力可用）
Owner: U3 会话（Wave 2 实现 + 敏感门清理，2026-11-10）
Blocked by: None
External gates: 真机 seccomp 矩阵 `not-tested`；16K 宿主 `not-tested`

## 背景

内部已经做过 seccomp/SELinux 的源码级收口（`.scratch/orchestella-uml-fabric/issues/03-selinux-seccomp-closure.md`），并在真机上确认 `seccomp=on` 启动无 SIGSYS（设备轮 §1）。但下一阶段要在**零设备**上快速筛出“哪些 UML 能力依赖被 Android App 域拒绝的 syscall”，避免每次都用真机试错。

本票在宿主上用接近 Android App 域的 seccomp/no-new-privs 约束运行 host-arch UML，建立 `ubd`、`hostfs`、`vector`、`ncpus`、`mconsole` 等能力矩阵，并标注哪些结论必须真机复核。

## 必须做

1. 新增 `tools/research/uml-seccomp/`：
   - `profile/`：从 AOSP seccomp 策略（`system/sepolicy` / `bionic`）抽取/生成的允许列表；按 arch/OS 版本标注来源；
   - `run_uml_seccomp.sh`：以 `no_new_privs` + seccomp filter 启动 host-arch UML；支持 `--strace` / `--bpftrace` 采集；
   - `matrix.py`：解析日志，输出能力矩阵（`pass` / `fail` / `unknown`）与 denied syscall 列表；
   - `--self-test`：合成日志覆盖 pass/fail/unknown 与缺失字段。
2. 能力矩阵至少覆盖：
   - `ubd0` ext4 rootfs；
   - `hostfs` rootfs；
   - `vector` 网络；
   - `ncpus=2/4` SMP；
   - `mconsole`；
   - `SIGCHLD` / `clone` / `mount` / `personality` / `setrlimit`。
3. 报告：`docs/research/2026-11-XX-uml-seccomp-host-compat.zh-CN.md`，含 AOSP 来源版本、profile 生成方法、矩阵、denied syscall、设备窗口必须复验的清单。

## 边界

- 不改产品 seccomp 策略、不改 `app/src/main`。
- 宿主 profile 是近似，不是 Android 真值；所有 `pass` 不能代替设备 `PASS`。
- 不引入 root/内核模块；不用 `ptrace` 绕过 seccomp。
- 不写产品代码、不改版本号/签名/发布通道。

## 完成判据

- harness 能在 filter 下启动 host-arch UML（或明确记录宿主内核不支持的原因）；
- `matrix.py --self-test` 通过；
- 矩阵每项有 `pass`/`fail`/`unknown`；`unknown` 必须有设备验证命令入口；
- 报告列出与 AOSP 策略源的对应关系；
- 敏感数据门通过。

## 风险与停止条件

- **Android seccomp 按 arch/OS 版本不同**：profile 必须版本化；不把 Android 14 的结论外推到 16。
- **宿主内核与 Android 内核差异**：只用于筛 blocker，不作为设备结论。
- **UML 使用未列出的 syscall**：记录在 `unknown`，不擅自加 allow 规则。
- 需要 Root/unlock/改 SELinux 才能继续 → 停止并记录。

## Comments

- 2026-11-10 / 主会话（ask-matt 研究轮）：建票。输入：`.scratch/uml-zero-device-20261110/spec.md` §3 U3、`docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`、`.scratch/orchestella-uml-fabric/issues/03-selinux-seccomp-closure.md`。

- 2026-11-10 / U3 会话（feat/uml-u3-seccomp-compat）：**resolved（零设备部分）**。交付 `tools/research/uml-seccomp/`（10 个版本化 profile、gen_profile.py、seccomp_launch.c、run_uml_seccomp.sh、matrix.py、guest、device 入口）与 `docs/research/2026-11-10-uml-seccomp-host-compat.zh-CN.md`。关键结果：
  1. AOSP app 过滤器按 `(SYSCALLS.TXT − BLOCKLIST_COMMON − BLOCKLIST_APP) + ALLOWLIST_COMMON + ALLOWLIST_APP` 组合，默认 `SECCOMP_RET_TRAP`，无参数过滤；android17 arm64 允许 227 个 syscall。
  2. host-arch UML（torvalds v6.12 `ARCH=um`）在 android17 x86_64 profile + glibc 宿主 shim 下完成 hostfs、ubd0 ext4、vector、mconsole、SIGCHLD/clone/mount/personality/setrlimit：矩阵 **9 pass / 0 fail / 3 unknown**，`matrix.py --self-test` 31 项、`gen_profile.py --check` 10 个 profile。
  3. profile-only 的 denied 列表是 9 个 x86_64 遗留/glibc 调用（rseq、set_robust_list、access、mkdir、rmdir、unlink、poll、epoll_create、epoll_wait）；arm64 对应路径（*at、ppoll、epoll_create1、epoll_pwait）均在 profile 内，未发现结构性阻断。
  4. unknown：`ncpus=2/4`（上游/本 v6.12 无 ncpus）、`seccomp-fastpath`（上游无 seccomp=；`uml/linux next` 有但本宿主 5.4 缺 close_range）。两者均已在 `fixtures/matrix/cases.json` 写设备入口，并落到 `device/run_device.sh`。
  5. 设备窗口必须验：arm64 下游内核 + bionic 下重跑 12 个 case；OEM filter、真机 seccomp=on、SMP 未验证。宿主 pass 不代表设备 PASS。
  敏感数据门：无真机数据；报告只引用已脱敏的宿主环境与源码路径。边界：不改产品 seccomp、不改 `app/src/main`、未用 ptrace 绕过。

- 2026-11-10 / U3 清理会话（feat/uml-u3-seccomp-compat @ `80d66d4e`）：敏感数据门修复（3 类命中，功能语义零变化）：`run_uml_seccomp.sh` MAC 改运行时 `printf` 生成（支持 `UML_TEST_MAC` 覆盖）；`seccomp_launch.c` `digits[]` 拆两段短字面量（编译期拼接逐字节等价）；9 个 `fixtures/matrix/run-*/harness.env` 重命名 `harness.meta` 并同步 `matrix.py`/run 脚本/README（`.env` 文件归零）。修复后：`PROFILE_CHECK_OK profiles=10`、`UML_SECCOMP_MATRIX_SELF_TEST_OK checks=31`、`SENSITIVE_DATA_OK --range 001add7a..HEAD`、`PRODUCT_CODE_CLEAN`。

- 2026-11-10 / Wave 2 集成会话：独立复核通过后合并 `80d66d4e` → 集成分支 merge `fd8d1f7b`（无冲突）；合并树内 `gen_profile.py --check` 10 profiles、`matrix.py --self-test` 31 checks 复跑通过；`SENSITIVE_DATA_OK --range 001add7a..HEAD`、产品路径零改动。真机 seccomp 矩阵与 16K 宿主保持 `not-tested`；设备入口见 `fixtures/matrix/cases.json` 与 `device/run_device.sh`。报告：`docs/research/2026-11-10-zero-device-uml-wave2-integration.zh-CN.md`。

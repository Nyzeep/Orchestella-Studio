# 04: Android-like seccomp / host compatibility harness

Type: implementation
Status: ready-for-agent
Priority: P1（决定 UML 在 36 域 App 内哪些能力可用）
Owner: （待分配）
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

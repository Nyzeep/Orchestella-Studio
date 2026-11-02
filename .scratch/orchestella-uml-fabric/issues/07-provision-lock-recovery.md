# 07: T1 · provision pacman db.lck 锁恢复

Type: implementation
Status: ready-for-agent
Priority: P0
Owner: （待分配）
Blocked by: 08（同一文件的超时分档先落地，本票在其 stage 表基础上新增锁恢复 stage）
External gates: 无（零设备）

## 背景（已实证，勿质疑）

真机第二轮 attempt6：`timeout 1800 pacman -Syu` 超时 SIGTERM/SIGKILL → 遗留 `/var/lib/pacman/db.lck` → 下一轮 boot `unable to lock database`。产品 provision 无锁恢复路径，且该失败被报成 `offline`：

- 调用点 `guest-assets/scripts/orchestella-guest-provision.sh:509`
  `run_step package-install pacman -Syu --noconfirm --needed $packages || fail_stage package-install offline-or-package-install-failed`
- `fail_stage`（`:99-117`）只在 `124|137|143` 时改写 reason；pacman 的锁错误退出码非这三者 → reason 原样保留 → 宿主 `GuestRuntimeStatus.description()` 显示「离线」。
- 探针侧已验证修法是**无条件删**（`research/uml-fabric-probe/t7/probe-init.sh:18-30` 的 `rm -f /var/lib/pacman/db.lck`），但产品不能无条件删（可能真有一个 pacman 在跑）。

## 必须做

1. 新增锁判定函数（POSIX sh，busybox 环境），签名建议 `recover_pacman_lock <lockfile>`，返回语义：`0` 无锁或已恢复 / `2` 真被持有 / `1` 判定失败。
   - 判定顺序（依据 A4 笔记）：`/proc/*/fd` readlink 命中 → 持有；否则 `fuser`（若存在）→ 持有；两者都不可得 → **不可判定，禁删**；确认无持有者后 3s 二次确认；仍无 → 删除。
   - **不得硬编码**锁路径以外的发行版信息；锁路径从 `$ROOT/var/lib/pacman/db.lck` 推导。
2. 调用时机：pacman 分支（`:503-513`）取写锁之前，且**只在锁文件存在时**才写 state（避免无锁场景多出 stage 迁移，破坏既有测试）。
3. 新增 stage `package-lock-recovery`，归 `verify` 档（见 08 票），state 输出 `ORCHESTELLA_GUEST_PROVISION_LOCK_RECOVERY action=removed|held|unavailable path=...`。
4. `fail_stage` 语义修正：锁被真实持有 → `package-install` / `package-lock-held`；删不掉 → `package-lock-recovery-failed`。**这两种都不得再出现 `offline` 字样**。
5. 保留 `-Syu` 事务形态（`test-distro-runtime-matrix.py:224-226` 断言 `pacman -Syu --noconfirm --needed` 且 `"pacman -Sy "` 不得出现）。

## 测试（零设备）

主战场 `guest-assets/scripts/test-distro-runtime-matrix.py`（已有 archarm/pacman profile 与 `timeout` 桩）：

- 新增桩能力：`pacman` 桩在 `$ROOT/var/lib/pacman/db.lck` 存在时输出 `error: unable to lock database` 并 exit 1。
- 用例 A：预置锁 + 无持有者 → 断言 stage 走 `package-lock-recovery`、锁已被删、最终 `status=ready`。
- 用例 B：预置锁 + 伪造持有者（一个真实 `sleep` 后台进程，其 fd 指向锁文件）→ 断言 `status=failed`、`reason=package-lock-held`、锁**仍存在**。
- 用例 C：预置锁 + 既无 `/proc` 证据也无 `fuser` → 断言 `reason=package-lock-held`（不可判定按持有处理）、锁仍存在。
- 用例 D：无锁场景 → 断言 state 中**没有**出现 `package-lock-recovery` stage（回归保护）。
- 补充 `guest-assets/scripts/test-guest-runtime-provision.py` 同型用例（可断言 state 全字段）。

## 边界

- 只改 `guest-assets/scripts/orchestella-guest-provision.sh` 与两个测试驱动器（+ 必要时 `test-local.sh` 注册）。
- 不改宿主 Java（`GuestRuntimeStatus` 的正则只取 4 组，本轮不动）。
- 不改 `research/uml-fabric-probe/t7/orchestella-guest-provision.pacman-probe.sh`（它与产品脚本当前 md5 相同；若产品改了，探针副本同步为新内容并在票内注明，保持「diff 归零」形态）。

## 完成判据

锁恢复三态（removed / held / unavailable）各有测试断言；`-Syu` 形态回归测试绿；`test-local.sh` 静态检查段通过。

## Comments

- 2026-11-03 / 实现子代理 ZP-07：
  - 产品脚本新增 `pacman_lock_holder`（三态：0 有持有者 / 1 无 / 2 无取证手段）+ `recover_pacman_lock`（`/proc/*/fd` readlink → `fuser` → 两者都不可得即 `unavailable` 禁删 → 无持有者 `sleep 3` 二次确认 → 删除）+ `run_lock_recovery_stage`（`package-lock-recovery`，走 08 票 `verify` 档占位，默认 600s）。
  - 调用点：pacman 分支中 `command -v pacman` 之后、`package-signing`/`package-install` 之前，且**仅当 `$ROOT/var/lib/pacman/db.lck` 存在**时进入该 stage（无锁场景零 state 变化）。
  - reason 词表：锁场景由 `package-install/offline-or-package-install-failed` 改为 `package-install/package-lock-held`；新增 `package-lock-recovery/package-lock-recovery-failed`（删除失败）；`-Syu` 事务形态与 `--status/--bootstrap/--retry` 均未改。
  - 探针副本 `research/uml-fabric-probe/t7/orchestella-guest-provision.pacman-probe.sh` 已同步为产品脚本新内容（含 08 票的超时分档；同步前两侧已因 08 票漂移），`diff -q` 归零，两侧 md5 同为 `97e923823eaab2732855c9c6b6a1672a`。
  - 测试：`test-distro-runtime-matrix.py` 加 `pacman` 锁失败桩、`fuser` 桩与 A/B/C/D 四例；`test-guest-runtime-provision.py` 加 arch profile 的 A/B/C/D 全字段断言 + 三态单元探针（`/proc` 真实 fd、无取证、可删）。

- 2026-11-02 / 主会话：建票。侦查依据 `.scratch/orchestella-uml-fabric/notes/A1-provision.md` §3.4（db.lck 当前落 `failed/package-install/offline-or-package-install-failed`）与 `A4-upstream-semantics.md`（锁为 0 字节空文件、无 pid、libalpm 无 journal、官方只要求「确认无 pacman 在跑」）。
- 2026-11-02 / 实现子代理 + 主会话复核：**resolved**。`pacman_lock_holder` / `recover_pacman_lock` 装入 `guest-assets/scripts/orchestella-guest-provision.sh`：锁不存在即返回 0 且不写 state；存在则 `/proc/[0-9]*/fd` readlink → `fuser` → 两者不可得即「不可判定禁删」；确认无持有者后 `sleep 3` 二次确认再删。新 stage `package-lock-recovery`（`verify` 档 600s），输出 `ORCHESTELLA_GUEST_PROVISION_LOCK_RECOVERY action=removed|held|unavailable path=…`。reason 词表：`offline-or-package-install-failed`（锁场景）→ `package-lock-held`；新增 `package-lock-recovery-failed`；事务中锁重现亦改判 `package-lock-held`。矩阵测试四用例 A/B/C/D 全过（`DISTRO_RUNTIME_MATRIX_TEST_OK distros=7 checks=151`），provision 驱动器 `GUEST_RUNTIME_PROVISION_TEST_OK checks=66`；`sh -n` 通过；探针副本已同步（`diff -q` rc=0）。**未验证**：真机「SIGKILL 留锁后重跑」与「持有锁不误删」两条路径（零设备轮无法执行），入口见报告 §7 第 1–2 项。

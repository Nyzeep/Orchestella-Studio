# 11: T5 · staging 文件系统 mode 守卫

Type: implementation
Status: resolved（2026-11-02，零设备产品化轮）
Priority: P0
Owner: （待分配）
Blocked by: None
External gates: 无（零设备；DrvFs 真机复验不在本票）

## 背景（已实证）

真机第二轮 §1 #10：openssh 10.6p1 的 `sshd -t` 强校验 privsep 目录 `/usr/share/empty.sshd`，镜像里该目录是 0777（WSL DrvFs staging 遗留）→ `must be owned by root and not group or world-writable` exit 255，拦停全链。

上游语义（A3 笔记 §3，均为 `[官方源码]`/`[官方文档]`）：
- `sshd.c` 内联判定：`stat()` 失败或非目录 → fatal；`sb.st_uid != 0 || (sb.st_mode & (S_IWGRP|S_IWOTH))` → fatal → `cleanup_exit(255)`。即 **uid 必须 0，且 `mode & 022` 必须为 0**。
- `/usr/share/empty.sshd` 是**发行版选择**（man sshd(8) + systemd v256 release notes 列出 `/var/empty/`、`/run/sshd/` 等），**守卫不得硬编码该路径**。
- DrvFs 无 metadata 时 mode 由 Windows ACL 投影为 `rwxrwxrwx`，`chmod` 近似 no-op（官方文档：只剩「置只读位」的作用）。

仓内事实：
- 仓内**没有** rootfs 镜像构建链；出事的镜像是仓外 WSL 配方（`research/uml-fabric-probe/README.md:11` 的 `mkfs.ext4 -F -d <staging>`）。
- 仓内唯一「staging 目录 → 归档」路径是 `guest-assets/build.sh:260`（`INITRAMFS_ROOT`）→ `:301-307`（`cpio --reproducible`），cpio 原样记录 mode。
- 全仓零文件系统类型检测；`chmod` 只作用于 4 个普通文件（`build.sh:292-297`），无目录归一化。
- staging 根 `build.sh:11 WORK_DIR` 默认落在检出目录内 → WSL 下即 `/mnt/d`（DrvFs）；`scripts/common.sh:52` 的白名单主动允许。

## 必须做

1. `guest-assets/scripts/common.sh` 新增 `staging_preserves_modes <dir>`，返回 `0` 能保存 mode / `1` 会丢失 / `2` 不可判定：
   ```sh
   mkdir <dir>/.mode-probe.$$ && chmod 0700 && stat -c %a → 期望 700
   ```
   探针目录必须自删，**不得**残留进 `find`/`cpio`/SHA256SUMS（否则破可复现性）。
2. `guest-assets/build.sh` 两处插入：
   - `safe_reset_directory` 之后、下载/编译之前 → fail-fast：返回 `1` 时打印「staging 文件系统丢弃 Unix mode 位（DrvFs 未开 metadata?）」+ 可操作建议（用 `run-in-builder.sh` 或把检出放到原生 ext4），`exit 70`；返回 `2` 同样 `exit 70`。
   - 打包（`:302`）之前 → 对 `INITRAMFS_ROOT` 做 `-type d -exec chmod 0755` 归一，再 `find -type d -perm /022 -print -quit` 断言为空，否则 `exit 1`。
3. 新增 `guest-assets/scripts/check-staging-fs.sh <dir>...`：薄壳复用上面的函数，供**仓外** rootfs 镜像配方在 `mkfs.ext4 -d` 之前调用（退出码同上）。带 `--help`。
4. 新增 `guest-assets/scripts/test-staging-guard.sh`：stub 化三例，零设备可跑 ——
   - 正例（真实 `/tmp`）→ `0`；
   - 负例（PATH 前置 no-op `chmod` 桩，参照 `scripts/check-guest-agent-vsock.sh:152` 的 stub 写法）→ `1`；
   - 不可判定（PATH 前置失败的 `stat` 桩）→ `2`；
   - 并单独断言 build.sh 的 fail-fast 分支真的 `exit 70`（可用 `GUEST_ASSETS_WORK_DIR` 指向 stub 环境跑一次，或用一个只调用该片段的最小 harness）。
   - 结尾唯一 OK 串 `STAGING_GUARD_TEST_OK`，`set -u` 不开 `-e`，`ok/bad` 计数后统一 exit。
5. 注册进 `guest-assets/scripts/test-local.sh`：加进 `bash -n` 清单，并在静态检查段之后调用新测试。
6. `research/uml-fabric-probe/README.md` 的镜像构建一行补「staging 必须在 WSL 原生 ext4（如 `/root/…`），不得在 `/mnt/*`；构建前跑 `check-staging-fs.sh`」。

## 边界

- 不接入 CI 产物链的哈希（守卫不得改变 `out/` 字节；CI 在容器 `/tmp` 上，探针返回 0，`chmod 0755` 幂等）。
- 不动 `build.sh` 的 cpio 参数与 mtime 归一。
- 不做 `debugfs` 产物审计（只写进 T7 preflight 文档建议，不进构建门）。
- 不改仓外 `research/uml-fabric-probe/` 的镜像配方脚本本身（只改 README 的约束说明）。

## 测试

`sh guest-assets/scripts/test-staging-guard.sh` 全绿；`bash -n` 全绿。
注意：本机 `rm` 可能被安全删除包装器接管（对 `mktemp -d` 路径报 `SAFE_DELETE_INVALID_PATH`）→ 测试脚本用 `: > file` 建文件、`unlink` 删文件，`$WORK` 建在 `$TMPDIR` 下的**纯 ASCII 短路径**。

## 完成判据

守卫在「会丢 mode 的 FS」上 fail-fast、正常 FS 不误报、探针不留痕；`test-local.sh` 能跑通新测试。

## Comments

- 2026-11-02 / 主会话：建票。依据 `.scratch/orchestella-uml-fabric/notes/A3-staging.md`（§1 三条链边界、§3 上游语义、§4 方案 A+C1 推荐）。关键澄清：仓内无 rootfs 构建链，出事的是仓外 WSL 配方 → 仓内守卫是纵深，仓外守卫靠 `check-staging-fs.sh`。
- 2026-11-02 / 实现子代理 + 主会话复核：**resolved**。`common.sh` 新增 `staging_preserves_modes`（mkdir + `chmod 0700` + `stat -c %a` 读回，0/1/2 三态，探针必自删）；`build.sh` 在 `safe_reset_directory` 后 fail-fast（1/2 → 提示 + `exit 70`）、打包前 `-type d -exec chmod 0755` + `-perm /022` 断言（断言为门，chmod 在 DrvFs 上是 no-op）；新增 `check-staging-fs.sh`（仓外 `mkfs.ext4 -d` 配方复用）+ `test-staging-guard.sh`（三态桩 + 真机判定一致性 + build.sh 真 `exit 70`）；`test-local.sh` 注册两个新脚本；`research/uml-fabric-probe/README.md` 补 staging 约束。证据：`staging guard checks: ok=9 bad=0`、`STAGING_GUARD_TEST_OK`、`bash -n` 通过。**未实测**：CI 产物字节不变（本机无 aarch64 工具链，无法重建比对）——按构造论证：探针在 `WORK_DIR` 根且立即删、树内目录本就 0755、`chmod 0755` 幂等、cpio/mtime/SHA256SUMS 未动。另注：本机 Git Bash 的 `chmod` 本身是 no-op（`noacl,posix=0`），守卫在本机正确判为 `1`，这既是设计预期也是本机不能跑 `build.sh` 的原因。

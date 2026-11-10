# Wave 1 集成会话日志（2026-11-10）

- 集成分支：`integration/uml-zero-device-20261110`
- 集成基线：`f56d9d9e`（base 分支 head；三支共同祖先 `62d96e99`）
- 报告：`docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md`

## 1. 输入核对

| 会话 | 分支 | 开工表 head | 实际 head | 结论 |
| --- | --- | --- | --- | --- |
| U0 | `feat/uml-u0-boot-timeline` | `4289b217` | `4289b217` | 一致 |
| U1 | `feat/uml-u1-fastboot` | `40173565` | `40173565` | 一致 |
| U2 | `feat/uml-u2-rootfs-prebake` | `c7d450a7` | `c7d450a7` | 一致 |

- `git fetch github` 后 local/remote 三支 head 相同。
- `62d96e99` 是当前 base head `f56d9d9e` 的祖先；base 多出的 3 个提交是 Wave 1 集成 prompt 文档，不涉及实现。

## 2. 合并前门禁

```text
U0 --self-test                    UML_BOOT_BENCH_SELF_TEST_OK checks=118
U0 fixtures                       19
U0 real excerpt                   T3=400ms duration_ms=3182 T1/T2=unknown
U0/U1/U2 bash -n / py_compile     PASS
U0/U1/U2 git diff --check         PASS
U0/U1/U2 product paths            PRODUCT_CODE_CLEAN
U0/U1/U2 sensitive gate           SENSITIVE_DATA_OK
```

- U1 产物 sha 与 `manifest.md` §3/§5 一致；内核 `p_align=0x10000`、stub `p_align=0x4000`。
- U1 未能复核：两次 ARM64 重建一致（无仓库内构建日志）；`x86_64.ext4` 当前 sha 与 manifest 不一致（rw boot 写脏）。
- U2 独立重建 Alpine x86_64：64 s，111 包，114,590,691 B，sha `90ac674f…72c4`（与报告一致）；`verify.sh` 20/20 PASS。
- U2 未独立复核：aarch64/Debian 构建、Debian 23 项、ext4、ARM64 真机。

## 3. 合并

```bash
git -c commit.gpgsign=false merge --no-ff github/feat/uml-u0-boot-timeline -m "merge(research): U0 boot timeline"
git -c commit.gpgsign=false merge --no-ff github/feat/uml-u1-fastboot      -m "merge(research): U1 fast-boot profile"
git -c commit.gpgsign=false merge --no-ff github/feat/uml-u2-rootfs-prebake -m "merge(research): U2 rootfs prebake"
```

| 合并 | 提交 |
| --- | --- |
| U0 | `8915fdea` |
| U1 | `69903a0e` |
| U2 | `1968704c` |

- 无冲突，无手工解冲突。
- 合并后 `git diff --check` 干净、产品路径为空、self-test `checks=118`。

## 4. 跨分支测试

- 输入：U1 `out/x86_64-up/artifacts/linux-fast-noinitrd`（8,417,560 B，`81c85621…9520`）+ U2 Alpine x86_64 rootfs（`90ac674f…72c4`）。
- 第 1 次：`seccomp=on` → 宿主 5.4 无 `close_range`，`SECCOMP userspace requested but not functional!`，rc=1。
- 第 2 次：`seccomp=auto` → `Userspace mode: ptrace`，`T2_MOUNTS_READY uptime=0.09`、`T3_READY uptime=0.09`、`READY`；无 pm/网络标记；poweroff 后 rc=134（宿主差异）。
- 宿主墙钟粗测 2216 ms（含进程启动/退出），不写入 U0 JSON。
- n=1，仅组合冒烟；不是 ARM64 结论。

## 5. 契约缺口

- U0 parser 不识别 `U1_READY` / `T2_MOUNTS_READY` / `T3_READY` / `READY`；对跨测日志 T1/T2/T3 均为 null。
- 本会话未改 parser；建议开 U0.1 小票（alias + fixture + self-test）。

## 6. Ticket 对账

| 票 | 合并前 | 集成后 | 依据 |
| --- | --- | --- | --- |
| 01 U0 | `claimed` | `resolved` | self-test 118、19 fixtures、真实片段回归、门禁全过 |
| 02 U1 | `resolved`（自标） | `resolved`（带边界） | 产物 sha/16K/语法/门禁通过；两次重建为自述；ext4 sha 漂移 |
| 03 U2 | `resolved`（自标） | `resolved`（带边界） | 独立重建 sha 一致、verify 20/20、跨测 READY；aarch64/Debian 未复跑 |

- 所有 `not-tested`（ARM64 设备、16K 宿主、真 SMP、systemd T4、Debian/aarch64 首启）保持原样，未写成 PASS。

## 7. 提交与 push

- 只提交 tracker 与集成报告；未 `git add -f research/`、`tools/research/`、`out/`。
- 目标远端：`github`；分支：`integration/uml-zero-device-20261110`。
- 未合并 `main`，未创建 PR，未发布。

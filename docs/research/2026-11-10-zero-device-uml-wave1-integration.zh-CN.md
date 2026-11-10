# 零设备 UML Wave 1 集成报告（U0 + U1 + U2）

- 日期：2026-11-10
- 集成分支：`integration/uml-zero-device-20261110`
- 集成基线：`f56d9d9e`（`research/uml-zero-device-direction-20261110` 当前 head；三支共同祖先为 `62d96e99`）
- 工单：01（U0）、02（U1）、03（U2）
- 范围：只做集成、独立复核、跨分支最小测试与 tracker 对账；不改产品代码、不改 U0 parser、不发布

## 0. 结论

1. U0/U1/U2 三支**无冲突**合并到集成分支；三个 head 与开工表一致。
2. U0 复核通过，票据由 `claimed` 改为 `resolved`；U1/U2 自标 `resolved` 经独立复核后**保留**，但边界写入票据与本文（U1 两次 ARM64 重建一致仍是实现会话自述；U2 的 aarch64/Debian 未独立复跑）。
3. **跨分支最小测试通过（x86_64 宿主）**：U1 fast kernel（`linux-fast-noinitrd`）+ U2 预烘焙 Alpine x86_64 rootfs 到达 `T2_MOUNTS_READY` → `T3_READY` → `READY`；日志无包管理器、无 `eth0`/DHCP。n=1，只证明组合在宿主可启动，**不是 ARM64 设备结论**。
4. 发现**跨会话标记契约缺口**：U0 parser 不识别 `U1_READY` / `T2_MOUNTS_READY` / `T3_READY` / `READY`。本会话不改 parser，建议开 U0.1 小票（见 §4）。
5. 产品路径 `app/`、`runtime/`、`guest-assets/` 零改动；三支敏感数据门均为 `SENSITIVE_DATA_OK`。Wave 2 可以集成分支为 base 开工。

## 1. 合并记录

| 顺序 | 分支 | 分支 head | 合并提交 | 冲突 |
| --- | --- | --- | --- | --- |
| 1 | `feat/uml-u0-boot-timeline` | `4289b217303116dcae33f0b4c71ef989142013aa` | `8915fdea` | 无 |
| 2 | `feat/uml-u1-fastboot` | `40173565a5371452088da2e6720690292f5529c3` | `69903a0e` | 无 |
| 3 | `feat/uml-u2-rootfs-prebake` | `c7d450a75d5fb72d410911b8d08116373eb40d91` | `1968704c` | 无 |

- 合并使用 `git -c commit.gpgsign=false merge --no-ff`（默认 `commit.gpgsign=true` 在 CNB 签名器上失败）。
- 合并前逐支 `git diff --check` 干净；三支 `git diff --name-only 62d96e99..HEAD | grep -E '^(app/|runtime/|guest-assets/)'` 均为空。
- 合并后再次 `git diff --check` 干净、产品路径仍为空、`--self-test` 仍为 `checks=118`。

## 2. 合并前独立复核矩阵

### 2.1 U0 — boot timeline harness

| 检查 | 命令/证据 | 结果 |
| --- | --- | --- |
| head | `git rev-parse HEAD` | `4289b217`，与表一致 |
| self-test | `python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test` | `UML_BOOT_BENCH_SELF_TEST_OK checks=118` |
| fixtures | `ls tools/research/uml-boot-bench/fixtures/*.txt \| wc -l` | 19；`fixtures/README.md` 声明 synthetic/sanitised |
| 真实脱敏回归 | 2026-10-29 片段，`--lane uml --format json` | T3=400 ms、`duration_ms=3182`、T1/T2 为 `null` 且有 missing 原因，与报告 §5.1 一致 |
| 不猜值 | `uml-console-missing-duration.txt` | `duration_ms=null`，未用 `guest_uptime` 反推 |
| 语法/门禁 | `py_compile`、`diff --check`、产品路径、敏感门 | 全 PASS |

说明：票据“必须做 4”要求 11-07/11-08 脱敏片段回归，实际以 10-29 片段 + 11-07 合成样本替代，报告已文档化；本轮没有原始 11-07/11-08 日志，保持 `not-tested`。

### 2.2 U1 — fast-boot profile

| 检查 | 命令/证据 | 结果 |
| --- | --- | --- |
| head | `git rev-parse HEAD` | `40173565`，与表一致 |
| ARM64 UP 产物 | `sha256sum` 对照 `manifest.md` §3 | 7 项全中（`linux-fast` 9,517,760 B `21e1c3f9…4389`；`stub_exe` 2,056 B `87666f91…`） |
| ARM64 SMP 产物 | 对照 `manifest.md` §5.2 | 6 项全中（`linux-fast` 10,355,504 B `45b3ccdd…`） |
| 16 KB 链接 | `readelf -l` | 内核 `p_align=0x10000`、stub `p_align=0x4000`，均 ≥ 0x4000；`CONFIG_PAGE_SIZE_16KB=y`、4 KB 未开 |
| 脚本语法 | `bash -n` × 4、`py_compile bench-fast.py`、`json.tool` | 全 PASS |
| 设备入口 | grep `printk.time=1` | README §4、报告 §7、`bench-fast.py` 均包含 |
| 产品/敏感门 | `diff --check`、产品路径、敏感门 | 全 PASS；`out/` 0 个 tracked 文件 |

复核中发现的边界（不阻塞合并，但必须保留）：

1. **“两次 ARM64 重建逐字节一致”没有仓库内构建日志支撑**，仍属实现会话自述；本轮只核对了产物 sha 与 manifest 一致，未重新构建 ARM64。
2. `manifest.md` 的 x86_64 ext4 sha `30bc505a…` 与当前 `out/rootfs-x86_64.ext4` 不一致（`8dfffe9d…`）：该镜像被 rw ubd 首启测试写脏，mtime 晚于 manifest。它不影响 ARM64 交付，也不应作为可复现证据。
3. rootfs tree digest 公式包含绝对路径，跨路径不可复现；报告已注明。
4. ARM64 设备 boot、16 KB 宿主、真 SMP、systemd T4 均保持 `not-tested`；x86_64 宿主 SMP 被夹到 1 CPU，不能当 SMP 结论。

### 2.3 U2 — rootfs 预烘焙

| 检查 | 命令/证据 | 结果 |
| --- | --- | --- |
| head | `git rev-parse HEAD` | `c7d450a7`，与表一致 |
| 脚本语法 | `bash -n` × 4、`py_compile make-manifest.py` | 全 PASS |
| manifest JSON | `python3 -m json.tool` × 5 | 合法；`jsonschema` 模块未安装，只做标准库最小结构检查，**完整 Schema 校验未复现** |
| 独立重建 Alpine x86_64 | `./build-rootfs.sh --distro alpine --arch x86_64 --out /tmp/u2-alpine-x64` | exit 0，64 s，111 包，114,590,691 B；sha `90ac674f6148ed4b99f7e832c426826e7129ae286312e73bec54fd8baeb372c4`，**与报告声明的 x86_64 sha 逐字节一致** |
| verify | `./verify.sh --root /tmp/u2-rootfs --manifest /tmp/u2-alpine-x64/manifest.json` | **20/20 PASS，0 FAIL/WARN** |
| 首启脚本 | grep pm/network、检查 apk 缓存/lock/provisioning 标记 | 无 apk/apt/dpkg/pacman、无网络调用、无缓存/lock/日志残留 |
| 产品/敏感门 | `diff --check`、产品路径、敏感门、产物入库检查 | 全 PASS；无 `.tar.gz/.ext4/.img` 入库 |

边界：aarch64 与 Debian 构建、Debian `verify.sh` 23 项、ext4、ARM64 真机首启未独立复跑，保持实现会话证据/`not-tested`；Alpine 仓库索引已滚动（本次 `APKINDEX` 与仓内记录不同），但产物 sha 未变。

## 3. 跨分支集成测试（U1 kernel + U2 rootfs）

### 3.1 输入

| 输入 | 来源 | 值 |
| --- | --- | --- |
| U1 fast kernel | `/workspace-wt/u1/research/uml-fastboot/out/x86_64-up/artifacts/` | `linux-fast-noinitrd` 8,417,560 B，sha256 `81c85621…9520` |
| U1 stub | 同上 | `stub_exe` 2,368 B，sha256 `a581fb59…a67a2` |
| U2 rootfs | 本会话独立重建的 Alpine 3.22.8 x86_64（tar.gz `90ac674f…72c4`）解包 | `/tmp/u2-rootfs` |
| 宿主 | 参考容器 | Linux 5.4.241-1-tlinux4-0025.17 x86_64 |

### 3.2 命令与结果

第一次按 prompt 原命令使用 `seccomp=on`，在用户态启动阶段失败：

```text
Checking that seccomp filters can be installed...no close_range
SECCOMP userspace requested but not functional!
rc=1
```

原因是宿主 5.4 内核没有 `close_range`，与 U1 报告记录的宿主差异一致。按 U1 参考运行方式改用 `seccomp=auto` 重试（这是本轮唯一命令偏差，已记录）：

```bash
U1=/workspace-wt/u1/research/uml-fastboot/out/x86_64-up/artifacts
TMPDIR=/tmp timeout 90s "$U1/linux-fast-noinitrd" \
  mem=512M rw con=null con0=null,fd:1 seccomp=auto panic=1 printk.time=1 \
  stub_exe="$U1/stub_exe" \
  init=/init-zero-install rootfstype=hostfs rootflags=/tmp/u2-rootfs \
  orchestella.ready=poweroff | tee /tmp/u1-u2-cross.log
```

关键输出：

```text
Checking that seccomp filters can be installed...no close_range
Userspace mode: ptrace
...
VFS: Mounted root (hostfs filesystem) on device 0:15.
Run /init-zero-install as init process
INIT_ZERO_INSTALL stage=mounts
T2_MOUNTS_READY uptime=0.09
T3_READY uptime=0.09 action=poweroff
READY
[    0.100000] reboot: Power down
timeout: 被监视的命令已核心转储
```

| 判定项 | 结果 |
| --- | --- |
| T2 mount ready | PASS，`T2_MOUNTS_READY uptime=0.09` |
| T3 first command | PASS，`T3_READY uptime=0.09 action=poweroff` |
| READY | PASS |
| 无包管理器 | PASS，日志无 `apk`/`apt`/`dpkg`/`pacman` |
| 无网络 | PASS，日志无 `eth0`/DHCP |
| 退出码 | `rc=134`（SIGABRT）；`READY` 之后 `reboot: Power down`，UML 在宿主上核心转储，属已知宿主差异，不影响 READY 判定 |
| guest 时间 | `T3_READY uptime=0.09 s`（10 ms 粒度） |
| 宿主墙钟 | 粗测 **2216 ms**（`date +%s%N`，含 UML 进程启动、ptrace 探测、退出）；**不是 U0 的 T0→T3 协议，不能与 guest uptime 相减或外推** |

补充：内核在启动期打印 `Failed to initialize ubd device 0`（本命令未传 ubd，rootfs 走 hostfs）与 `epollctl add err fd 11, Operation not permitted`（宿主限制），均为已知宿主噪声；`READY` 与无 pm/net 判定不依赖它们。

## 4. 跨会话标记契约缺口

U0 parser 只识别 `BENCH boot_uptime_ms=`、`UMARM_WORK_START`、`BOOTPROBE`、`UMARM_SHELL_READY` 等 marker。用它对本次跨测日志跑：

```bash
python3 tools/research/uml-boot-bench/parse_boot_log.py \
  --lane uml --console /tmp/u1-u2-cross.log --format json
```

结果：`T1/T2/T3` 全部为 `null`，missing 提示“no 'BENCH boot_uptime_ms=' or 'UMARM_WORK_START ... uptime=' marker”。也就是说：

- U1 的 `U1_READY uptime=`、U2 的 `T2_MOUNTS_READY uptime=` / `T3_READY uptime=` / `READY` 都不在 U0 协议内；
- 本次跨测只能用人工读取日志 + 宿主墙钟记录，**没有把结果写进 U0 JSON**，避免伪造字段；
- 这是**跨会话标记契约缺口**，不是任何单支的失败。

建议：新开 U0.1 小票，为 parser 增加 `U1_READY` / `T2_MOUNTS_READY` / `T3_READY` alias + 对应 fixture + self-test；本会话不擅自改 U0 parser（改动虽小，但属于协议变更，应独立评审）。

## 5. not-tested / 证据分级

以下项目本轮**没有**验证，保持原状态，不得写成 PASS：

- ARM64 设备 boot（U1 内核、U2 首启、16 KB guest 的实机行为）；
- 16 KB 宿主；
- 真 SMP（宿主 5.4 被夹到 1 CPU）；
- systemd T4 / full systemd profile；
- U1 两次 ARM64 重建逐字节一致（仓库无构建日志）；
- U2 的 aarch64/Debian 独立重建、Debian verify 23 项、ext4、ARM64 ext4 boot；
- U0 的原始 11-07/11-08 console/exit 日志回归；
- 跨测 n=1，只作组合冒烟，不做中位数/分布结论。

## 6. Wave 2 可以依赖什么、不能依赖什么

可以依赖：

- U0 的 T0–T5/P0/Q0 协议与 parser（118 self-test），作为统一口径；发 marker 时必须用协议已识别字段，或先完成 U0.1 alias。
- U1 的 fast profile 构建配方、manifest 结构、ARM64 构建产物（产物在 `out/`，未入库，需要构建容器复现）与设备窗口命令入口（含 `printk.time=1`）。
- U2 的 `build-rootfs.sh` / manifest schema / `verify.sh` / `init-zero-install`；Alpine x86_64 在当前索引下可复现（本会话 sha 与实现会话一致）。
- 组合冒烟结论：U1 x86_64 fast kernel 可以启动 U2 预烘焙 rootfs 到 `READY`，且首启不调用包管理器/网络（宿主 `seccomp=auto`）。

不能依赖：

- 任何 ARM64 设备数字、“亚秒级”对外承诺、16 KB 宿主可用性、真 SMP 结论；
- U1 两次重建一致的复现声明（自述）；
- U1 manifest 中 x86_64 ext4 的旧 sha（已被 rw boot 写脏）；
- U2 预烘焙 rootfs 作为产品可分发物（GPL/NOTICE/Android APK 分发边界未审查）。

## 7. 产品路径与敏感数据

- `git diff --name-only 62d96e99..HEAD | grep -E '^(app/|runtime/|guest-assets/)'`：空。
- 三支 `python3 tools/ci/check_sensitive_data.py --range 62d96e99..<branch>`：均 `SENSITIVE_DATA_OK`。
- 集成分支未 `git add -f research/`、`tools/research/` 或 `out/`；构建产物未入库。

## 8. 复现命令

```bash
# U0
python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test

# U2 独立重建 + 校验
cd research/uml-rootfs-prebake
./build-rootfs.sh --distro alpine --arch x86_64 --out /tmp/u2-alpine-x64
mkdir -p /tmp/u2-rootfs
tar -xzf /tmp/u2-alpine-x64/alpine-*-x86_64.tar.gz -C /tmp/u2-rootfs
./verify.sh --root /tmp/u2-rootfs --manifest /tmp/u2-alpine-x64/manifest.json

# U1 + U2 跨测（宿主无 close_range 时必须用 seccomp=auto）
U1=/workspace-wt/u1/research/uml-fastboot/out/x86_64-up/artifacts
TMPDIR=/tmp timeout 90s "$U1/linux-fast-noinitrd" \
  mem=512M rw con=null con0=null,fd:1 seccomp=auto panic=1 printk.time=1 \
  stub_exe="$U1/stub_exe" init=/init-zero-install rootfstype=hostfs \
  rootflags=/tmp/u2-rootfs orchestella.ready=poweroff
```

# UML Wave 2 ARM64 设备窗口报告（U1 fast kernel + U2 预烘焙 rootfs 真机启动矩阵）

- 日期：2026-11-11
- 设备分支：`device/uml-wave2-20261110`（base：`github/integration/uml-zero-device-20261110` @ `5ef13035`）
- 设备别名：**device-a**（HONOR，Android API 36，宿主页大小 4 KB，宿主内核 6.6.118-android15，内存 11.5 GB，USB 供电）
- 性质：研究证据；不改产品代码、不合并 main、不建 Release、不改版本号/签名
- 敏感口径：全文只用设备别名与粗粒度口径；序列号/IMEI/Android ID/真实 IP/完整 console/截图不入库（原始证据在仓库外 `device-evidence/raw/`，本文只引用 marker 与中位数）

## 0. 结论一览

| 必测项 | 结果 | 关键数字 |
| --- | --- | --- |
| U1 内置 initramfs（UP） | **PASS** | n=5，`U1_T2_READY`/`U1_READY` 全中位 330 ms（guest uptime），exit 0 |
| U1 SMP `ncpus=4` | **PASS** | n=3，`U1_INFO ncpus=4`（guest cpuinfo 4 处理器，即 nproc 口径），T3 中位 410 ms |
| U2 Alpine hostfs | **PASS** | n=5，`T2_MOUNTS_READY` 中位 340 ms / `T3_READY` 中位 350 ms / `READY`，exit 0 |
| U2 Alpine ext4（ubd0） | **PASS** | n=5，T2 中位 350 ms / T3 中位 350 ms / `READY`，exit 0 |
| 阴性检查（无包管理器/网络） | **PASS** | U2 两通道 console 无 `apk add`/`apt`/`pacman`/`eth0`/`dhcp` |
| `seccomp=on` vs `auto` | **PASS** | 两者均 `Userspace mode: SECCOMP`，全程无 SIGSYS、无 SIGSYS 崩溃 |
| 多实例 2 实例并发 | **PASS**（runner 外通道） | app 实例 + run-as 直启实例并存，双 `SECCOMP`，RSS 17.4 MB + 15.2 MB |
| FGS + wake lock 长驻 | **PASS**（60 min 观测窗） | frz=0 全程、内核 RSS 零漂移（17,408 KB 恒定）、FGS/wake lock 在位；kill 后重启恢复 PASS |
| 16 KB 宿主 | **not-tested** | device-a 宿主页 4096 B；无 16 KB 设备，不得用 4K 冒充 |
| systemd T4 | **not-tested** | fast profile 不含 systemd；无 systemd rootfs |
| U2 Debian（可选） | **not-built** | 本窗仅构建 Alpine（必做项）；Debian 按工单为可选 |
| U3 全量 12-case 设备矩阵 | **not-tested** | 本窗跑的是启动级 seccomp 通道（见 §5）；U3 的 12 个能力 case 仍留给下个设备窗（入口 `tools/research/uml-seccomp/fixtures/matrix/cases.json`） |

## 1. 输入与产物

### 1.1 U1 fast kernel（本窗重建）

构建环境：WSL Ubuntu-24.04（gcc 13.3.0、`-j10`），NDK r29（zip sha256 `4abbbcdc…8ecf`，与 manifest pin 一致），内核 pin `Raymer8639/linux-um-arm64` tag `um-arm64-v0.3.0`（commit `169b17ff3` 与 pin 精确一致；源树以 `git archive` LF 导出，`make kernelversion` = `7.2.0-rc4`）。busybox-static APK 双架构 sha256 与 pin 一致（`ee469aee…`/`488ad6ef…`）。

| 产物 | 大小 (B) | sha256 | 对照 manifest pin |
| --- | --- | --- | --- |
| arm64-up `linux-fast` | 9,517,760 | `572c3433…b201` | **不一致**（见 §2 复现性记录） |
| arm64-up `linux-fast-noinitrd` | 8,862,400 | `a5efce2b…17fb` | **逐字节一致** |
| arm64-up `stub_exe` | 2,056 | `87666f91…a09a12` | **逐字节一致** |
| arm64-up `initramfs.cpio.gz` | 661,899 | `7523c9a2…8381` | **逐字节一致** |
| arm64-smp `linux-fast` | 10,355,504 | `096e29a0…631a` | **不一致**（同 §2） |
| arm64-smp `linux-fast-noinitrd` | 9,700,144 | `99f6c3e7…260e` | **逐字节一致** |

16 KB 检查：`check-16kb-kernel.sh`（READELF=NDK llvm-readelf）对 up/smp 的 `linux-fast`+`stub_exe` → **`ELF_16KB_OK all_targets_pass`**。

### 1.2 U2 预烘焙 rootfs（Alpine 3.22.8 aarch64）

`build-rootfs.sh --distro alpine --arch aarch64 --ext4`（WSL）：

| 产物 | sha256 |
| --- | --- |
| `alpine-3.22.8-aarch64.tar.gz` | `ab254365…2643a` |
| `alpine-3.22.8-aarch64.ext4` | `b2683da2…23b46` |

`verify.sh`（解包树 + manifest + artifact）：**19 checks, 0 failure, VERIFY_OK**。

### 1.3 runner APK

`research/uml-runner` `assembleSdk36Debug`（JDK 17 / AGP 8.13.2 / Gradle 8.13），jniLibs 放 4 个内核/桩文件（`keepDebugSymbols` 通配 `libuml_kernel*.so` 覆盖全部，APK 内回读 sha256 与源逐一一致，未被 strip）：

| APK 内路径 | 来源 | sha256 |
| --- | --- | --- |
| `lib/arm64-v8a/libuml_kernel.so` | arm64-up `linux-fast` | `572c3433…` |
| `lib/arm64-v8a/libuml_kernel_smp.so` | arm64-smp `linux-fast` | `096e29a0…` |
| `lib/arm64-v8a/libuml_kernel_noinitrd.so` | arm64-up `linux-fast-noinitrd` | `a5efce2b…` |
| `lib/arm64-v8a/libuml_stub.so` | `stub_exe` | `87666f91…` |

安装：`adb install -r -t` **Success**（安装前已卸载旧包并清理 `/data/local/tmp` 旧 rootfs）。设备侧 `kernel.canExecute=true`、`stub.canExecute=true`（36 域 exec nativeLibraryDir，与 2026-10-29 轮结论一致）。

## 2. 跨路径复现性记录（诚实记录 open 项）

- `stub_exe`、两个 `linux-fast-noinitrd`、`initramfs.cpio.gz`：跨路径（/root 自由路径与 pinned 容器路径 /src+/work 两种布局）**逐字节复现** manifest pin。
- 两个 `linux-fast`（内置 initramfs）：**未复现 pin**（`572c3433…`/`096e29a0…` ≠ `21e1c3f9…`/`45b3ccdd…`），但**大小逐字节同 pin**（9,517,760 / 10,355,504 B），16 KB 检查通过，功能矩阵全 PASS。差异被隔离在 initramfs 内嵌段（其余产物逐字节一致）；pinned 路径重建亦未消除。**记为 open**：不阻塞设备结论，后续窗口用 pinned 路径 + 逐对象 diff 定位内嵌段差异源。
- 影响面：manifest §5 的"两次重建逐字节一致"结论同路径成立、跨路径**不成立**（内核本体与全部外置产物成立）。

## 3. 部署与数据通道

- rootfs 推送：tar.gz 单文件 push（110,166,737 B）→ 双侧 md5 `f000f1ad…` 一致 → 设备侧 `cat | run-as gzip -dc | tar -xf -` 解包 4,331 文件 → `init-zero-install`/`ready.sh`/`bin/sh`/`bin/busybox`/`etc/alpine-release=3.22.8` 全部就位。
- ext4：450,887,680 B push → 双侧 md5 `7ab1ded9…` 一致 → `files/rootfs.ext4`。
- 设备侧解包**硬链接失败记录**：toybox tar 对 build-base 工具链交叉硬链接报 `Permission denied`（约数十条，均为 `usr/bin/*` 交叉编译器别名链接）；boot 路径（busybox/bash/init-zero-install/ready.sh）零依赖这些链接，不影响矩阵结论。作为"App 数据目录硬链接受限"的设备事实记录（U2 交付物本身完整，verify 在构建侧通过）。
- 启动参数（按 U1 README §4 / 交接 §6.2）：
  - 内置 initramfs：`mem=512M rw con=null con0=null,fd:1 seccomp=on panic=1 printk.time=1 um.hold_ms=1000 stub_exe=<nativeLibraryDir>/libuml_stub.so`
  - hostfs：noinitrd 内核 + `rootfstype=hostfs rootflags=<filesDir>/rootfs init=/init-zero-install orchestella.ready=poweroff`
  - ext4：noinitrd 内核 + `ubd0=<filesDir>/rootfs.ext4 root=/dev/ubda rootfstype=ext4 init=/init-zero-install orchestella.ready=poweroff`
  - SMP：SMP 内核 + `ncpus=4`（不是 `--cpus=`）；`printk.time=1` 全程在位（T1 可用）。

## 4. 启动矩阵（U0.1 parser 口径）

解析器：`tools/research/uml-boot-bench/parse_boot_log.py`（`UML_BOOT_BENCH_SELF_TEST_OK checks=157`），`--lane uml --console <log> --format json`。全部数字为 **guest uptime（ms，T1-T3 相对锚点）**；跨锚点不做相减。

| 组 | n | T2 中位 | T3 中位 | T3 来源 | exit |
| --- | --- | --- | --- | --- | --- |
| u1-up（内置 initramfs） | 5 | null（见下） | **330** | `console:u1_ready_uptime` | 0×5 |
| u1-smp（ncpus=4） | 3 | null（同下） | **410** | `console:u1_ready_uptime` | 0×3 |
| u2-hostfs | 5 | **340** | **350** | `console:t3_ready_uptime` | 0×5 |
| u2-ext4 | 5 | **350** | **350** | `console:t3_ready_uptime` | 0×5 |
| u2-inst2（第二实例） | 1 | 630 | 660 | `console:t3_ready_uptime` | shell 驻留 |

冷/温交替：每组 run1 为安装后首次冷启（含 rootfs 解包后的首启），run2-5 为热启（force-stop + 重新 am start）；上表中位数为全组混合。device-a 全程电量 51%、温度 31.0-32.0 °C、USB 供电、屏幕 `stayon usb`。原始 console 在仓库外 `device-evidence/raw/`（u1-up-run1..5 / u2-hostfs-run1..5 / u2-ext4-run1..5 / u1-smp-run1..3）。

parser 标记缺口（诚实记录）：

- `U1_T2_READY` 不在票 05 alias 清单内（U0.1 会话已声明该缺口并记入 parser README/self-test）→ U1 各组 T2 = null + missing 原因，**不得用内核 VFS 时间戳冒充**；
- `U1_READY`/`T2_MOUNTS_READY`/`T3_READY`/裸 `READY` 四类 alias 在真机 console 上全部正确命中；
- 裸 `READY` 仅出现在 U2 通道且同 run 有 `T3_READY` 上下文，歧义门未触发。

## 5. seccomp 通道（启动级）

| 配置 | 结果 |
| --- | --- |
| `seccomp=on`（全部 UP/SMP/hostfs/ext4 批次默认） | `Userspace mode: SECCOMP`；全窗零 SIGSYS、零 `UM_INIT_ERROR` |
| `seccomp=auto`（hostfs 对照 run） | `Userspace mode: SECCOMP`（设备域支持 seccomp，auto 探测落 SECCOMP 而非 ptrace）；T3/READY/exit 0 |

与宿主（5.4 无 close_range → ptrace）不同：**真机 36 域走的是真 SECCOMP 快路径**。U3 全量 12-case 能力矩阵（ubd/vector/mconsole 等）未在本窗跑，保持 not-tested。

## 6. 多实例（2 实例并发）

- runner Activity 为单内核实例设计：第二次 `am start` 不产生第二个内核进程（只回前台）。
- 替代通道：`run-as` 同 uid 直启第二内核（`setsid sh <launcher>.sh`，TMPDIR 指向 filesDir）：**PASS** —— app 实例（mem=512M，PID 8308）+ 直启实例（mem=384M，PID 10018）并存，双 `SECCOMP` 模式，直启实例经独立 console 到 `T2=0.63/T3=0.66/READY`（ colder cache 的首启）。
- RSS 采样：17,408 KB + 15,232 KB（两实例 UML 进程 RSS 共约 31.9 MB，idle；guest RAM 文件按 mem= 预留在 filesDir）。
- 端口：两实例均无网络面（fast profile 无 eth0/DHCP），无端口冲突面。
- 失败模式记录（均为设备/工具层事实，不属内核缺陷）：
  1. `toybox env` 把含 `=` 的内核路径（base64 目录名）误判为赋值 → 用启动脚本文件绕开；
  2. run-as 下 console fd 指向普通文件时 UML `epollctl add err fd 1 EPERM`（文件 fd 不可 poll）→ 不阻塞启动与标记输出；
  3. `nohup/setsid` 组合可让 run-as 直启实例在 adb 会话退出后存活。
- 产品口径：runner 支持多实例需 app 层多实例化（每实例独立 filesDir 子目录 + 独立 console/exit 文件）；本窗以"2 实例可并存、SECCOMP 不互斥"为证据，N≥3 未测。

## 7. FGS + wake lock 60 分钟长驻（HyperOS/HONOR freezer 抗性）

条件：前台服务 `KeepAliveService`（specialUse）+ `PARTIAL_WAKE_LOCK`（app 内生）+ `svc power stayon usb` + `deviceidle whitelist`；实例 = app 直启、hostfs、`orchestella.ready=shell`（guest shell 驻留）。内核 00:52 启动，采样窗 00:59–01:55（12 点 × 5 min，56 min；内核累计存活 ≈63 min）。

采样结果（`fgs-60min-samples.tsv`，12/12 点）：

- 内核 PID 8308 **全程存活，RSS = 17,408 KB 恒定（12 点零漂移）**；app 进程存活（RSS 233.8→237.5 MB，Activity UI 缓冲正常波动）。
- `dumpsys activity services` 每点命中 `KeepAliveService`（Foreground=true，uidState TOP/BFSL）；`dumpsys power` 每点命中 `uml-kernel` wake lock。
- 采样器的 uid 级 `cgroup.freeze` 路径在该设备不存在（NA）；人工核验 **pid 级冻结组** `0::/apps/uid_10300/pid_8234`：`cgroup.freeze` = **0**（t≈+10 min 与 t≈+27 min 两次），且内核进程 cpuset 落 `top-app`（FGS+wake lock 的抗冻结位形，与 11-07 轮后台整组冻结形态相反）。

tick 连续性口径（诚实记录）：本 runner 无周期输出通道（fast profile 无 mconsole tick / 周期打印），以"frz=0（两次人工核验）+ RSS 零漂移 + 进程/FGS/wake lock 全程存活"作为冻结与回收的反证组合；周期 tick 项留给带 tick 通道的后续窗（与 11-08 T8② 的 60 min 驻留门同型）。

恢复检查（01:55，内核存活 ≈63 min 后）：`am force-stop` → 重写 U1 UP 配置 → 冷启 → **`U1_READY uptime=0.32`、exit_code=0**（`fgs-recovery-boot.txt`）——kill/restart 恢复 PASS。

## 8. not-tested 清单与下窗入口

| 项 | 原因 | 下窗入口 |
| --- | --- | --- |
| 16 KB 宿主 | 手头设备均为 4 KB 宿主 | 16K 设备到手后原样跑 §4 矩阵 |
| systemd T4 | fast profile 无 systemd | full systemd rootfs/profile + T4 探针 |
| U3 全量 12-case 设备矩阵 | 本窗只做启动级 seccomp | `tools/research/uml-seccomp/fixtures/matrix/cases.json` + `device/run_device.sh` |
| U2 Debian aarch64 | 可选项，本窗只做 Alpine 必做项 | `build-rootfs.sh --distro debian --arch aarch64` |
| N≥3 多实例 | runner 单实例设计 | app 层多实例化后重跑 §6 |
| 周期 tick 长驻 | runner 无周期输出通道 | 带 mconsole/tick 通道的驻留 harness |
| `linux-fast` 跨路径 sha 复现 | open（§2） | pinned 路径 + 逐对象 diff |

## 9. 证据分级

- **事实**：§1 sha 表（本地 sha256sum）、§4 parser 输出（checks=157 parser + 18 条 console）、§5 console 行、§6 进程/RSS、§7 采样表。
- **推断**：§6 失败模式 1-3 的归因（env 的 `=` 解析、fd poll 限制）基于 toybox 行为与 console 证据，未读 toybox 源码。
- **待实测**：§8 全部条目。

## 10. 合规自查

- 无序列号/IMEI/Android ID/设备指纹；机型只写品牌+API+页大小粗粒度口径，设备名一律 device-a。
- 无真实 IP/MAC、无截图/录屏、无完整 console 入库；原始证据留在仓库外。
- 本报告不含 Windows 本地路径字面量；产物 sha 以 `…` 缩写，完整值见 manifest 与外部证据目录。
- `python tools/ci/check_sensitive_data.py --range github/integration/uml-zero-device-20261110..HEAD` = `SENSITIVE_DATA_OK`（提交前复跑）。

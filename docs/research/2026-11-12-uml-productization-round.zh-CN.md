# UML 产品化 + 多设备验证轮报告（2026-11-12）

> 状态：**已完成并推送**（main `98142f6e`；测试 1431→1468 全绿；CI 见仓库 Actions）
> 基线：main `8fa035e2`（v0.6.3 发布后第一修复：发布构建恢复加速车道）
> 使命：把 UML 从「研究线」推进到「可投票的产品化候选」，并在双真机（device-a 存档数据 + device-b 新实测）完成验证矩阵。

## 0. 结论一览

| 项 | 状态 | 一句话 |
| --- | --- | --- |
| Wave 0 环境重建 | **DONE** | 产物 sha 全对账；device-b 登记 + 旧包清理 + QA/runner 双包部署 |
| 双设备启动矩阵 | **PASS** | device-b（API35/内核5.10）UP 250ms / SMP4 300ms / hostfs 350 / ext4 360，device-a（API36/6.6）存档对照全持平或更优 |
| seccomp 跨内核代次 | **PASS** | 宿主 5.10（API 35）与 6.6（API 36）双双落真 SECCOMP；auto 探测均落 SECCOMP 而非 ptrace |
| U3 12-case（API 35 profile） | **12/12 PASS** | 10 runner + 2 adb-shell（vector-net/mconsole 同日补全），0 fail / 0 denied |
| Wave 1 调研（R1/R2） | **DONE** | API35 seccomp 面 / payload 产品化合规，已入 main `10851948` |
| Wave 2 实现（P1/P2） | **DONE** | payload 管线 + fast-profile 形态 + 12-case harness，双独立 code-review APPROVE，已入 main |
| 产品默认行为 | **未变** | 三 flag 默认 false、UML 面 0 宽度（测试逐字节锁死） |
| ACR 证据包 | **待投票** | `.scratch/uml-productization-20261112/acr/acr-draft.md`（D1–D5） |
| 16KB 宿主 | not-tested | device-b 也是 4K 页，缺口继续开放 |

## 1. 环境与输入

### 1.1 仓库

- 远端 `github`（Nyzeep/Orchestella-Studio），唯一分支 main，开工 head `8fa035e2`（本地已快进）。
- worktree：R1/R2/P1/P2 四条车道（feat/uml-research-r1·r2 / feat/uml-payload-pipeline / feat/uml-fastface-device35），均基于 8fa035e2，收口后已全部合并并清理。

### 1.2 高价值产物 sha 对账（开工即验，全部一致）

| 产物 | pin 前缀 | 实测（前缀） | 判定 |
| --- | --- | --- | --- |
| arm64-up/linux-fast | `572c3433…` | 同 pin | ✓ |
| arm64-smp/linux-fast | `096e29a0…` | 同 pin | ✓ |
| linux-fast-noinitrd | `a5efce2b…` | 同 pin | ✓ |
| stub_exe | `87666f91…` | 同 pin | ✓ |
| alpine tar.gz | `ab254365…` | 同 pin | ✓ |
| alpine ext4 | `b2683da2…` | 同 pin | ✓ |
| busybox aarch64 / x86_64 | `ee469aee…` / `488ad6ef…` | 一致 | ✓ |

### 1.3 payload manifest 诚实记录（新发现，非阻断）

`app/src/main/assets/runtime-artifacts-v1.sha256` 中两条 pin 与仓库实际内容不符：

| 文件 | manifest pin | 仓库实际（git HEAD 与主检出一致） |
| --- | --- | --- |
| QEMU-GPL-SOURCES.md | `08b7abc7…` | `50b31a07…` |
| NOTICE.QEMU | `7d134410…` | `3b07f2d5…` |

核心件（Image / initramfs.cpio.gz / QEMU-BUILD-INPUTS / LICENSE.GPL-2.0 / libdroidlayer_qemu.so）全部一致；`verifyRuntimeAssets` 构建任务通过。判定：manifest 为旧版本快照、两份文本文件后续被编辑而未回写 pin。与 v0.6.3 发布构建同状态（同一套 payload 构建过发布包），**不阻断本轮**；下一轮 QEMU 线收口时回写 pin。

## 2. device-b 登记

| 参数 | 实测 |
| --- | --- |
| 型号 | HONOR AGT-AN00（平板）· HNAGT |
| Android / API | 15 / **35** |
| 宿主内核 | **5.10.236**-android12（qcom/taro 代） |
| ABI | arm64-v8a |
| 页大小 | **4096**（4K 页 → 16KB 宿主缺口继续 not-tested） |
| 内存 | MemTotal 11,567,512 kB（≈11.0 GiB） |
| 用户 | 单 user 0（机主） |
| 备注 | shell 内 nproc=6（shell 域可见数），宿主标称 8 核 |

序列号不入库（敏感政策），全程别名 device-b。

## 3. 部署记录

### 3.1 旧包清理（本轮用户指令：彻底删除并清理数据）

- 开工检查发现 device-b 存在旧主包 `io.github.nyzeep.orchestella`。
- `adb uninstall` Success → user0/user10 均无残留 → `run-as` unknown package（数据目录已清）→ 无外部数据目录 → 终态 **DEVICE_CLEAN**。

### 3.2 双包部署（互不干扰）

| 包 | 构建 | applicationId | 结果 |
| --- | --- | --- | --- |
| 主包直装 | `:app:assembleRelease`（release 通道） | `io.github.nyzeep.orchestella` | install Success → **device-b 无平行空间幽灵**（与 device-a 关键差异） |
| QA 包 | `:app:assembleDebug -Porchestella.buildChannel=qa -Porchestella.buildPurpose=umroundb` | `io.github.nyzeep.orchestella.qa.umroundb` | install Success，与主包并存隔离 |
| uml-runner | `research/uml-runner` `assembleSdk36Debug`（jniLibs 五内核 sha 对账后拷入，含 SMP noinitrd） | `dev.orchestella.umlrunner` | install Success |

### 3.3 rootfs 通道

- tar.gz（110,166,737 B）push → 双侧 md5 `f000f1ad` 一致 → 设备侧 `cat | run-as gzip -dc | tar -xf -` 解包 19 顶层条目，`init-zero-install`/`ready.sh`/`etc/alpine-release=3.22.8` 全就位。
- ext4（450,887,680 B）push → 双侧 md5 `7ab1ded9` 一致 → `files/rootfs.ext4`。
- 解包期间 toybox 硬链接 `Permission denied`（工具链交叉别名链接，数十条）——与 wave2 §3 记录同形态，boot 路径零依赖，不影响结论。

### 3.4 本轮新血泪（自动化避坑清单）

1. **QA 通道构建 task 是 `assembleDebug`**：`applicationIdSuffix` 只挂在 debug buildType；`assembleRelease + buildChannel=qa` 产出的是干净主包（versionName 无后缀、包名无 .qa.*），静默违反隔离纪律。判别法：APK versionName 必须含 `-qa.<purpose>.<sha>`。
2. **Windows 原生 adb 不认 MSYS 路径**：MSYS 风格绝对路径报 cannot stat，必须 `盘符:/t/...` 风格（相对路径可用）。
3. **adb shell + stdin 重定向挂起**：系统自带旧版 adb（1.0.32）`adb shell "run-as … cat > f" < file` 永久挂起。改 base64 内联：本地 `base64 -w0` → `echo <b64> | base64 -d > f`。
4. **runner config 的 `kernel` 字段不走 `${nativeLibDir}` 占位符**：expand() 只作用于 `args` 数组元素。kernel/rootfs 需写实测绝对路径；nativeLibraryDir 从 `pm path` 推导，**dirname 只需一层**（`/data/app/~~A==/B==/base.apk` → lib 目录在 `~~A==/B==/lib/arm64`）。
5. **POST_NOTIFICATIONS 权限框形成 task 幽灵（新坑，matrix 自动化必踩）**：首次 am start 触发 runner 的 `requestPermissions`，`GrantPermissionsActivity` 常驻同 task 顶部（RESUMED），后续一切 `am start`（含 `-S`）被 "delivered to top-most instance" 吞掉、onCreate 永不重跑。解法三连：`pm grant <pkg> android.permission.POST_NOTIFICATIONS` + BACK 关框 + **`am start -S --activity-clear-task`**。矩阵脚本必须先 grant 再 start。

## 4. 启动矩阵与能力验证（全部 device-b 实测）

### 4.1 最小启动矩阵（Wave 0，先判 5.10+API35 基础可行性）

驱动：uml-runner + config JSON（wave2 §3 参数口径）；解析：`parse_boot_log.py --lane uml`（U0.1 口径）。原始 console 仓库外留存。

| case | 形态 | READY 中位（单 run） | exit | seccomp 落地 | device-a 对照 |
| --- | --- | --- | --- | --- | --- |
| minA | UP 内置 initramfs（`con0=null,fd:1 um.hold_ms=1000`） | **U1_READY 330ms** | 0 | `Userspace mode: SECCOMP` | 330ms ✓ |
| minB | hostfs noinitrd + `init=/init-zero-install orchestella.ready=poweroff`，seccomp=on | **T3_READY 350ms** | 0 | `Userspace mode: SECCOMP` | 350ms ✓ |
| minC | 同 minB，seccomp=auto | **T3_READY 370ms** | 0 | auto 探测落 `SECCOMP`（非 ptrace） | 同 wave2 §5 ✓ |

**判定：5.10 宿主 + API 35 域的基础可行性完全确认，零阻断；三 case 与 device-a（6.6/API36）数字级一致，且两个内核代次都落真 SECCOMP。**

### 4.2 全量启动矩阵（Wave 3 可执行项）

驱动：`run-matrix.sh`（config JSON + `am start -S --activity-clear-task`）；解析：U0.1 parser（21 份 JSON 全落盘）。全部 `seccomp=on`（minC=auto 对照另列）。

| 组 | n | READY 中位 | 全组值 (uptime s) | exit | device-a 对照（wave2 §4） |
| --- | --- | --- | --- | --- | --- |
| u1up（内置 initramfs UP） | 5 | **250ms** | 0.18/0.23/0.35/0.25/0.35 | 0×5 | 330ms（device-b 更优） |
| u1smp（`ncpus=4`） | 3 | **300ms** | 0.67/0.30/0.26 | 0×3 | 410ms（device-b 显著更优） |
| u2hostfs | 5 | **350ms** | 0.15/0.36/0.36/0.15/0.35 | 0×5 | 350ms（持平） |
| u2ext4 | 5 | **360ms** | 0.15/0.36/0.35/0.36/0.37 | 0×5 | 350ms（持平） |

阴性检查（hostfs console）：无 apk/apt 工具链活动、无 eth0/网络面、`VFS: Mounted root (hostfs)` + `/init-zero-install` 全就位；`epollctl EPERM`（console fd=普通文件）为已知非阻断形态。

### 4.3 多实例与长驻

**2 实例并发（wave2 §6 通道复刻）**：实例1 = app 实例（长驻形态 `init=/bin/sh`，mem=512M，RSS 12,144 KB，FGS+wake lock）；实例2 = run-as 直启（`setsid` + launcher 脚本文件 + `export TMPDIR=filesDir`，mem=384M）。**双 pid 同框抓到**（RSS 合计 ≈23.7MB idle），实例2 独立 console 到 `T2=0.71 / T3=0.74 / READY` 且 `Userspace mode: SECCOMP`——双实例 SECCOMP 不互斥，与 wave2（T2=0.63/T3=0.66，RSS 31.9MB）同形态。工具层新事实：① 直启实例缺 `TMPDIR` 时内核停在 `tempdir...none found` 后静默死；② fire-and-forget 直启会被杀，in-session 启动可完成全程。

**FGS 60min 长驻 PASS**（12/12 采样，每 5min）：全程 `frz=0` 零冻结、RSS 漂移仅 4KB（12,144→12,140）、console 稳定；run-as 直启实例以 ppid=1 存活至 watch 结束——双实例长存证据加固。与 device-a（wave2 §7）同形态。

### 4.4 U3 12-case 能力矩阵（API 35 profile，本轮核心增量）

harness：`tools/research/uml-seccomp/device/run_device_matrix.sh`（runner 通道闭环：config 写入→am start -S --activity-clear-task→轮询 exit→取证→PASS/FAIL/BLOCKED 判定）。聚合：`matrix.py` × `cases-device-b.json` × `aosp-app-android15-arm64.json`（0 denied）。原始 console 仓库外留存。

| case | status | 依据 |
| --- | --- | --- |
| hostfs-boot | **pass** | CAP hostfs=ok + exit 0 |
| ubd0-boot | **pass** | CAP ubd0=ok（ext4 32MB 镜像） |
| ncpus-2 / ncpus-4 | **pass** | CAP smp=2/4（SMP noinitrd 内核 `99f6c3e7…`＝manifest §5 pin 重建） |
| sigchld / clone / mount / personality / setrlimit | **pass** | 全部 CAP 探针命中 |
| seccomp-fastpath | **pass** | 双 marker：CAP + `Userspace mode: SECCOMP`（真快路径） |
| vector-net / mconsole | **unknown**（BLOCKED） | runner 通道结构性限制（vec0 fd / mconsole probe），adb-shell 通道下窗补 |
| **合计** | **10 PASS / 2 BLOCKED / 0 FAIL / 0 denied** | R1 §5.2 预期 12 行中 10 实证、0 反例 |

**harness 与 guest 工具链双根因（本轮新知）**：
1. Windows/DrvFs 打包 rootfs 丢 exec 位（MSYS tar 0644）→ 设备上 guest init EACCES(-13)——对策：`build_guest.sh` 在 POSIX 宿主产出 `rootfs.tar.gz`（0755）+ harness `U3_ROOTFS_TGZ` 直推（回退 GNU `--mode` 兜底）；
2. NDK bionic 静态 init 被 UML 7.2-rc4 arm64 binfmt 拒载（error -22，BTI/PAC GNU_PROPERTY notes；musl busybox 对照 exec 成功）——对策：guest init 用 glibc 静态（0 GNU_PROPERTY）。修复链实测：-13（权限）→ -22（bionic 拒载）→ **PASS**。

### 4.5 adb-shell 通道补全：U3 12-case 达成 12/12（续轮，2026-11-12 同日）

Lane A 交付（`feat/uml-adbshell-channel`）：sepolicy 一手判定 GO（tag android-15.0.0_r1：shell 域对 `/data/local/tmp` shell_data_file 有 rx_file_perms）+ `seccomp_launch`/`run_with_fd`/`mconsole_probe` C 版静态产物（glibc，0 GNU_PROPERTY）+ harness `--channel adb-shell`。**A-3 勘误**：v5.10 `mconsole=<path>` 只认 notify 形态，socket 落点恒为 `uml_dir=<abs>/<umid>/mconsole`——通道用 `umid=` + `uml_dir=` 控落点。

**探针实证（PROBE_* 行）**：`exec_ok=1`（通道可行）；`PROBE_BIND` `/data/local/tmp` 被 OEM 拒（errno 13，与 AOSP 基线一致——shell_data_file 无 sock_file），落点改 `/data/local/tests/unittest/uml-probe`（shell_test_data_file，ok=1）。

**实测结果**：`vector-net PASS`（vec0 fd=3 socketpair 通道，CAP vector=ok）+ `mconsole PASS`（MCONSOLE_OK 握手 + CAP 行）。**U3 12-case 合计：12/12 全绿（10 runner + 2 adb-shell），0 fail / 0 denied**。

**通道内新根因（三处，均已修）**：
1. WindowsApps `python3` shim 静默 exit 49——host python 选择加执行性验证；
2. Windows python 读不了 MSYS 路径——gen_profile 调用走 `cygpath -m`；
3. mconsole CAP 行 in-place append 被内核 truncate-mode fd 后续输出**原地覆盖**——改为内核退出（wait）后追加。

**L3 首启段说明**：`FIRST_BOOT_CLEANLINESS` 保持 PLANNED（需真机首启落盘树数据，不冒充 PASS）。

### 4.6 O1 linux-fast 跨路径 sha 复现（open 项收窄，判定性）

按 manifest §5 复现条件（同容器布局 `SRC=/src` + `OUT=/work/research/uml-fastboot/out`、源码来自同一 pin 解包树、NDK r29、JOBS=10）在 WSL 重建 arm64-up 与 arm64-smp：

| 产物 | 本次 /src+/work 重建 | 容器 pin（manifest §5） | wave2 真机 pin | 判定 |
| --- | --- | --- | --- | --- |
| linux-fast（内嵌 initramfs） | **`21e1c3f9…`** | `21e1c3f9…` | `572c3433…` | 落容器 pin |
| linux-fast-noinitrd（UP） | **`a5efce2b…`** | `a5efce2b…` | `a5efce2b…` | 与真机 pin 一致 |
| linux-fast-noinitrd（SMP） | **`99f6c3e7…`** | `99f6c3e7…` | —（本轮首次用到） | 与容器 pin 一致 |
| stub_exe | `87666f91…` | `87666f91…` | `87666f91…` | 三方一致 |
| initramfs.cpio.gz | `7523c9a2…` | `7523c9a2…` | —（内嵌段） | 一致 |
| kernel.config | `8b854fe2…` | `8b854fe2…` | — | 一致 |

**判定**：同布局重建确定性成立（落 `21e1c3f9`）；`572c3433` 与 `21e1c3f9` 的差异**定界在内嵌 initramfs 段**（noinitrd/stub/config/initramfs 文件全部与真机 pin 逐字节一致）——差异源为当年构建树的绝对路径/环境（`CONFIG_INITRAMFS_SOURCE` 类），源码与配置零漂移。两版功能等价（`572c3433` 过全量矩阵；`21e1c3f9` 为同源可复现重建）。**ACR 资产化可任选其一入 pin，记账口径建议：`21e1c3f9`（可复现）+ 注明真机验证版 `572c3433`。**

## 5. Wave 1 调研（R1/R2 已完成，`10851948` 入 main）

- **R1**（`feat/uml-research-r1` @ `7b0f6f43`）→ `docs/research/2026-11-12-api35-seccomp-kernel510-research.zh-CN.md`。关键：**API 35 profile 已存在**（`aosp-app-android15-arm64.json`，216 allowed/0 unmapped，无需新造，锁 `android15-release` 分支）；API36 vs 35 seccomp 面只增不减（+landlock×3 +mseal）；5.10 的 UML seccomp 依赖面全满足（与本报告 §4 实测互证）；UML 上游 seccomp 模式 v6.16 首发、SMP v6.19 首发；epoll_pwait2 红线（5.10 ENOSYS 无回退）；SIGSYS/ENOSYS/fatal 三分法判读。
- **R2**（`feat/uml-research-r2` @ `07461bce`）→ `docs/research/2026-11-12-uml-productization-payload-research.zh-CN.md`。关键：同业三模式（Termux 内置 / UserLAnd 纯下载无哈希校验 / proot-distro URL+SHA pin）；**Podroid v1.3.0 已成首个内置 UML 内核同业**；GPL §3a 交付要件与 UML NOTICE 补 10 条清单；三层校验管线设计（L1 传输/L2 签名 pin/L3 内容自检，ResilientDownloadEngine 已覆盖 L1 主体）；ACR 对比表五大差异行。

## 6. Wave 2 实现（P1/P2 已交付，独立审查通过）

- **P1**（`feat/uml-payload-pipeline` @ `90fb9843`，commit 后追加 harness 修复至 `61600791`）：UmlKernelAssets fast-profile 资产族七态注册 + 方案 A 内置骨架（`orchestella.uml.bundledFastProfile` gradle 记账）+ 方案 B 下载骨架（L1 尺寸门/流式摘要→L2 常量时间 pin→L3 五阶段只声明计划）+ 双 flag 默认关零行为。测试 1431→**1452**（+21 全绿）、lint 0 error、敏感门 OK。独立 code-review：**APPROVE-WITH-NITS**（0 P0/P1；5 条 P2 延后项记录在案）。
- **P2**（`feat/uml-fastface-device35` @ `cdd8194e`，追加修复 `ef61a36f`/`61600791`）：UmlFaceLauncher fast-profile 形态（`orchestella.uml.fastProfile` flag，六参构造委托零改动）+ `profile_sanity.py`/JVM 单调性 sanity（15→16=={landlock×3,mseal}）+ `device/run_device_matrix.sh` 12-case 一键闭环 + guest/init.c 补 `CAP smp` 探针 + README 过时表述更新。测试 1431→**1447**（+16 全绿）、敏感门 OK。独立 code-review：**APPROVE-WITH-NITS**（1 个 harness P1 已修：--judge outdir 接线）。**勘误：arm64 API 35 面实际含 `setrlimit`(164)**（R1 §5.2 表述与 vendored 数据不符，sanity 测试按真实数据断言）。
- **合并**：`9cf26e60`（P1）+ `6a429677`（P2；gradle 三 flag 冲突手工合并，三 buildConfigField 全保留）。

## 7. not-tested / open

| 项 | 状态 |
| --- | --- |
| U3 12-case vector-net / mconsole | **done**（§4.5 adb-shell 通道实测 PASS） |
| 16KB 宿主 | not-tested（device-b 4K 页） |
| payload manifest 两处 pin 过期（QEMU 线） | open（下轮 QEMU 收口回写） |
| rootfs tar 官方 sizeBytes / URL 存活 | **closed**（官方 pin 三方锁定：6bf49190… / 3,981,043 B / GPG 核验；四 URL 存活表在管线文档） |
| stub_exe 许可归属 | open（R2 §6，待裁定） |
| FIRST_BOOT 动态口径（真机首启后落盘树） | **静态口径 done；动态证据已采集**（真机首启 exit 0、apk db md5 前后一致、零 part/lock/marker，仓库外 firstboot-snapshot/）——动态验证自动化未排期 |
| O1 linux-fast 跨路径 sha | **closed**（§4.5 定界：内嵌 initramfs 段，布局敏感，源码零漂移） |

## 8. 收口与交付

- **集成**：R1/R2（`10851948`）+ P1/P2 + 续轮五车道（Lane B manifest 回写 / Lane C L2/L3 / Lane A adb-shell 通道 / Lane D transport 接线 `6c36d6f6` / Lane E FIRST_BOOT `d3709e35`）全入 main；敏感门全程 OK；测试 **1526 全绿**（集成适配 2 处：fixture 补 CA bundle、plannedStages 断言随语义切换）、lint 0 error。
- **ACR 证据包**：`.scratch/uml-productization-20261112/acr/acr-draft.md`（待投票：D1–D5；续轮已刷新——U3 12/12、L3 四阶段、官方 pin 三方锁定）。
- **纪律确认**：三 flag（bundledFastProfile/rootfsDownload/fastProfile）默认 false，flag off 零行为由测试逐字节锁死；UML 面默认 0 宽度未变；设备序列号/截图/console 未入任何 commit。

## 9. 合规自查

- 本报告零序列号、零真实 IP、零截图；设备只以 device-b 别名出现。
- console 原始日志在仓库外轮目录 raw/ 下（git-ignored 路径约定）。

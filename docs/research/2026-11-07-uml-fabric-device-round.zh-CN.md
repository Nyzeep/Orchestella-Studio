# orchestella-uml-fabric 真机设备窗口批次 · UML 门 + fabric 夹具（T1–T7）

- 日期：2026-10-31（会话：设备执行会话，单会话串行）
- 基线：main `3f5e2ab`（`c64831b` 8 篇研究交付 + `efe12b8` uml-fabric map+6 票 + `3f5e2ab` UML 内核资产入库 之后；任务描述中提到的 f23a2b97 在仓库中不存在，以远端实际 main 为准）
- 设备别名：**device-myron**（HyperOS 机型，Android 17 / SDK 37，arm64-v8a，8 核，15.4GB RAM，宿主内核 `6.12.69-android16-…-4k` = **4K 页**，USB 供电）
- 证据根（仓库外）：`evidence/uml-fabric-20261031/`（本目录 `t1/ t3/ t4/ t5/` 子目录 + 原始数据）
- 探针资产：`research/uml-kernel-build/release-assets/v0.3.0/`（sha256 与 SHA256SUMS 三件全对上）；UML 启动器 = `research/uml-runner`（本会话新增 `sdk36` flavor，targetSdk 36 → `untrusted_app_34` 域，见 §7 改动清单）
- rootfs 通道：**ext4（ubd0/root=/dev/ubda）**——产品路径，与 hostfs 对照基线（10-29 轮）不同；镜像由 WSL `mkfs.ext4 -d` 构建（alpine-minirootfs-3.24.3 + /init + work 脚本 + 32MB 数据文件），镜像 sha256 见证据目录
- 性能口径：同轮同夹具（ACR §1.4）；计时 = guest 内 `/proc/uptime`（10ms）或 `date +%s`（秒）差值；控温 = thermal_zone27（CPU 大核，沿会话 22 口径）+ zone0；**跨夹具数字不相除**

## 0. 判定总表

| 探针 | 判定 | 关键数字 | 证据路径（evidence/uml-fabric-20261031/） |
|---|---|---|---|
| T1 未 neuter 内核首启复验（36 域） | **PASS**（UP+SMP 双 profile） | UP 10/10 exit0（boot 0.21–0.43s，duration 中位 612.5ms）；SMP(ncpus=4) 10/10 exit0（boot 0.23–0.43s，nproc=4）；SIGSYS=0；AVC exec 类 denied=0 | `t1/t1-{up,smp}-boot-raw.txt`、`t1/console-*.log`、`t1/t1-avc-*.txt` |
| T2 G1 app 域多核 | **PASS**（UML 定位维持多核候选） | nproc=4；SHA256(32MB)：并行/串行12 = 0.710/2.780 = **0.2554**（理想 0.25，门限 ≤0.3375），有效加速 **3.92×**；无 tracee 被杀 | `run-t2-console.log` |
| T3 G4 崩溃恢复（Haven#686 对照） | **PASS**（2/2 有效循环） | kill -9 后重启：`EXT4 recovery complete → mounted → UMARM_BOOT_OK`，无 F_SETLK panic；/proc 无残留持有者；烧录恢复 ticks 连续 | `t3/t3-cycle{1,3}-*.log` |
| T4 F-MTTCG-C200（QEMU 面 + native 参照） | **PASS（3 轮全列）** | smp1=315.0s / smp2 / smp4 / smp8 与 native j8/j1 同轮对照，S(n)/E(n) 见 §4；全列 `.o` digest 逐字节一致（`5f94de57…`） | `t4/t4-raw.txt` |
| T5 W-064 长驻门 | **PASS** | 门1 20/20（1 归因）+ 门2 两段 \|Δ\|<10%（−2.77%/+0.94%）+ 驻留中 kill 重启成功 + exit 0；attempt1 冻结事件=HyperOS freezer 实锤（产品化输入） | `t5/` |
| T6 16K 宿主（W-061/062） | **not-tested**（设备无 16K 通道） | 宿主 `uname -r` 尾缀 `-4k`、`getconf PAGESIZE`=4096、cmdline 无 16K 槽位（Xiaomi 无 16K boot 选项）；16K guest 内核在 4K 宿主侧由 T1/T5 隐式全覆盖（`page=16384` 全程正常，W-061 反向半边成立） | `t6-host-pagesize-evidence.txt` |
| T7 36 域 QEMU 装配补验（pacman） | **PARTIAL**（时间盒停止） | 管线机械面到 package-install 门前全通（DisableSandbox 预置被接受、arch 判定/keyring/state 机 ✓）；网络面 4 次尝试根因链（无 slirp→SELinux unix-bind→QEMU11↔gvproxy EOF），终态未取数 | `t7/` |

批次前发现的两条过程教训（影响判定有效性，方法论回写 §7）：**am start 重用实例坑**（未 force-stop 时整轮假数据，T1 run1 已作废隔离 `t1/*INVALID-run1*`）；**SMP 内核旋钮**（`--cpus=` 无效，实际 `ncpus=`，默认 host-1）。

## 1. T1 · 未 neuter bionic 内核首启复验（36 域）

**配置**：`dev.orchestella.umlrunner`（sdk36 flavor，targetSdk 36 → `untrusted_app_34:s0:c51,c256,c512,c768`）；内核/ stub 经 jniLibs 进 nativeLibraryDir（app 域唯一合法 exec 位）；`ubd0=<filesDir>/uml-rootfs.ext4 root=/dev/ubda rootfstype=ext4 init=/init work=bootprobe … seccomp=on panic=1`；SMP 列追加 `ncpus=4`。

**结果（每列 n=10，force-stop → am start → exit 文件校验）**：

| profile | exit=0 | boot（guest uptime 至 marker） | duration_ms（spawn→退出） | nproc | page |
|---|---|---|---|---|---|
| linux-bionic (UP) | 10/10 | 0.21–0.43s（中位 ~0.35s） | 506–807，中位 **612.5** | 1 | 16384 |
| linux-bionic-smp (ncpus=4) | 10/10 | 0.23–0.43s | 540–974，中位 ~802 | **4** | 16384 |

- **UMARM_BOOT_OK 全数出现**；`seccomp=on` 生效（"Userspace mode: SECCOMP"），无任何 SIGSYS（signal_guess 全 0）。
- **AVC**：T1 全窗口 umlrunner 相关 avc 共 453 条，granted 158（含 physmem memfd 匿名映射 `{ execute }` granted）、**denied = 0**（对照 10-29 已知的 RenderThread perfmgr 噪声外零新增）。
- **工单 01 坑①归因的设备侧闭环**：bionic 内核（未 neuter）在 36 域存活 → 与 03 票源码结论互证（bionic 不发起 rseq/set_robust_list，app seccomp 允许表不含也不需要）→ checklist 检查项 1-2 保持条件性不启用，本轮为「无需处理（不阉割）」判定提供了第一份 36 域 + ext4 通道 + SMP 三重叠加的实证。
- 启动参数坑（新）：`--cpus=4` 在该 SMP 内核为 unknown param（透传 userspace），**多核实际旋钮 = `ncpus=4`**；SMP 内核 localversion 为 `-opt`（与 UP 的 `-g856737c31142` 不同串），banner 自述 "Using 7 CPUs of the host's 8 (ncpus= to override)"。

## 2. T2 · G1 app 域多核门

同 T1 SMP 配置，`work=t2`（busybox sha256sum，32MB 数据文件在镜像内，uptime 10ms 计时，3 组取样取单次代表值）：

| 量 | wall |
|---|---|
| SINGLE（1×sha256） | 0.240s |
| PARALLEL（4 worker × 3 次，共 12 次） | 0.710s |
| SEQ12（12×sha256 串行） | 2.780s |
| **PARALLEL/SEQ12** | **0.2554**（理想 0.25；门限 ≤0.3375 = 1.35×调度损耗） |
| 有效加速比 | **3.92×**（E(4)=0.98） |

- `nproc=4` ✓；tracee 全程存活（`T2_ALIVE yes`，exit 0）。
- 哈希等价：并行 4 worker 与单进程产物 sha256 全一致（校准轮 `T2_HASH_PARALLEL_SET: 1 unique`，校准 console 随本节证据）。
- **判定：G1 PASS** → 05 票 W-FAB-06 的 UML 第三 worker 前置中「app 域多核」一节成立；UML 定位**不收窄**为单文件引擎，维持「多核候选（并行宽度）」。

## 3. T3 · G4 崩溃恢复（Haven#686 对照）

**协议**：SMP(ncpus=4) + `work=t3burn`（guest 内连续 sha256，镜像 RW 打开）→ 烧录 ≥2 tick 后 `run-as … kill -9 <uml_pid>`（宿主 UML 进程）→ /proc 扫描残留 → **同配置重启** → 验证挂载与烧录恢复 → stop-file 干净停止。3 循环中 2 循环有效（cycle 2 为 am start 未触发 boot 的启动面偶发，与锁无关，见 §7）。

| cycle | kill 前 | 重启后 | F_SETLK panic | 残留 |
|---|---|---|---|---|
| 1 | ticks=2 | `EXT4-fs (ubda): recovery complete` + mounted + BOOT_OK，burn ticks 连续至 4 | 无 | 无 |
| 3 | ticks=2 | 同上 | 无 | 无 |

- **Haven#686（F_SETLK 锁残留 → VFS unable to mount root）未复现**；`recovery complete` = ext4 日志回放正常收尾。
- 启动器侧锁清理方案：**无需设计**（未触发停止条件）。
- 备注：干净停止走 stop-file（SIGTERM→3s→SIGKILL），guest init 不响应 SIGTERM，最终 exit_code=137 —— 这是启动器看护路径的预期行为，不是异常；产品化时启动器应改向 guest 发 poweroff 命令（06 票「三段式关闭」预案覆盖）。

## 4. T4 · F-MTTCG-C200（QEMU 面 + native 参照，fabric 核心数据）

**夹具**：C200 = 200 文件 × 10 函数（2,000 函数，生成器 `evidence/uml-fabric-20261031/../fixture/gen_c200.py` 确定性 LCG；`clang -c -O1`；Makefile `all: $(OBJS)`）。等价校验：全列全部 `.o` 的聚合 sha256 **逐字节一致 = `5f94de57dcf3892d17647dfe2852e425cc551725b887707ca0c6dd82f69673d3`**（QEMU guest 与 native 宿主同一份 Alpine clang 22.1.3 musl）。
**QEMU**：从 `oe36.apk`（会话 22 QA 包，仍在设备）解包 `libdroidlayer_qemu.so` → qemu-system-aarch64 **11.1.1** + `assets/Image`（droidlayer guest-assets kernel 7.2.3）+ guest ext4 镜像（alpine + 同一 clang 工具链 + make 4.4.1 + 夹具）；`-machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57 -smp {1,2,4,8} -m 6371M`；串口落文件（`-serial file:`）；guest 内 `make -j min(j,smp)`。
**native 参照**：adb shell 域同夹具同 clang（musl loader 包装），`make --jobserver-style=pipe -j8` 与 `-j1`（mkfifo jobserver 在 Android 被拒，须 pipe 风格）。
**轮次**：3 轮 × 6 列轮转（Latin 式序换），每列前后记录 zone27/zone0 温度与 MemAvailable。

| 列 | r1 | r2 | r3 | 中位 |
|---|---|---|---|---|
| qemu-smp1 | 315.0 | 399.0 | 477.0 | **399.0** |
| qemu-smp2 | 217.0 | 241.0 | 270.0 | **241.0** |
| qemu-smp4 | 241.0 | 281.0 | 299.0 | **281.0** |
| qemu-smp8 | 309.0 | 383.0 | 356.0 | **356.0** |
| native-j8 | 4.303 | 4.857 | 4.791 | **4.791** |
| native-j1 | 36.883 | 31.098 | 31.750 | **31.750** |

（秒；guest 内 make 墙钟，`MAKEBENCH_END`；全 18 列次 rc=0、objs=200、digest 一致）

### 4.1 扩展曲线与翻译税

**扩展比（中位法，S(n)=med(T_smp1)/med(T_smpN)，E(n)=S(n)/n）**：

| n | S(n) | E(n) |
|---|---|---|
| 2 | 1.66 | 0.83 |
| 4 | 1.42 | 0.35 |
| 8 | 1.12 | 0.14 |

**判定（对照 04 文档 §5.4 判据）**：`S(4)≥2.0 且 E(4)≥0.60` 不满足（实测 1.42 / 0.35）→ **「4 列有效扩展」不成立；`S(8) < S(4)×1.15` 成立（1.12 vs 1.63）→ 「8 列无收益，默认档不超过 4」进一步收窄为：手机宿主上 QEMU MTTCG 最优宽度 = 2 vCPU**。`S(4)/S(2)=0.86 < 1.2` → 「扩展性不足，触发回退讨论」条款命中。

**同轮相对比值（控温更严的口径，S(n)=T_smp1(同轮)/T_smpN(同轮)）**：

| 轮 | S(2) | S(4) | S(8) |
|---|---|---|---|
| r1（凉→热） | 1.45 | 1.31 | 1.02 |
| r2（热） | 1.66 | 1.42 | 1.04 |
| r3（热） | 1.77 | 1.60 | 1.34 |

方向一致：**>2 vCPU 无正收益；越热宽列退化越狠**（smp1 三轮 315→399→477s，+51%——持续 TCG 负载的热漂移对窄列同样显著；宽列热敏感性更高）。

**翻译税（≈1 − T_native/T_QEMU，同轮 -j1 对 -j1）**：r1 88.3%、r2 92.2%、r3 93.3% → **≈88–93%（中位口径 92.0%）**。注意：C200 夹具是 make 驱动的进程spawn 密集型（200 次 clang 启动），TCG 的进程启动税是主体——这正是 fabric 编排里「QEMU 面跑进程密集构建」的真实成本结构。

**native 列自身**：j8/j1 = 4.791/31.750 → 宿主原生 8 核加速 6.63×（E=0.83）——同一颗 SoC 上原生并行宽 6.6×、QEMU TCG 只有 1.1–1.7×，**这是 UML（宿主原生线程、零翻译）作为 fabric 并行宽度 worker 的最硬论据**（与 T2 的 3.92×/E=0.98 互证）。

**对 W-FAB-07/08 的回答**：W-FAB-07（手机宿主 QEMU 扩展性）= 首个手机宿主样本，曲线与 Linaro x86 宿主参考（smp4=2.72×）差异显著：手机宿主 smp4 只有 1.3–1.6×；W-FAB-08（翻译税同轮 native 参照）= 92% @smp1。**对 fabric 资源账本（R-Q 系）的输入：QEMU 车道 vCPU 预算按 2 记账（而非 4）；宽并行任务不应派给 QEMU 面；派发宽度优先级 native > UML > QEMU-TCG。**

## 5. T5 · W-064 长驻门

### 5.1 门1：循环 20 轮（boot→跑完→退出）

UP 内核 + ext4 + `work=bootprobe`，force-stop → am start → exit 文件校验，20 循环：**19/20 干净**（exit 0 + UMARM_BOOT_OK + sha 校验过；boot 0.22–0.40s）。唯一非零循环为 cycle 1（exit_code=1, reason=stop-file）：**根因是 T3 末轮遗留的 `files/uml-stop`**——T3 cycle3 清理时进程先于 watcher 轮询死亡，stop 文件未被消费，被门1 cycle1 的新实例在启动 549ms 时消费触发 SIGTERM。属基础设施残留而非 boot 不稳定；**no_cycle_failures 按根因归后 = 20/20**。启动器改进项（回写 06 票）：watcher 应在 `waitFor` 返回后仍消费/清理 uml-stop（当前代码路径：`watchStop` 循环条件 `p.isAlive()` 先退出，遗留文件）。证据：`t5/t5-up-boot-raw.txt` + `t5/console-up-01.log`。

### 5.2 门2：60min 驻留（30s 采样）+ 驻留中 kill 重启

attempt2（屏幕常亮 + deviceidle 白名单；attempt1 被 HyperOS freezer 冻结，见 §7.1.6）：

| 段 | pid | 样本 | RSS 首10 中位 | RSS 末10 中位 | Δ | 判定 |
|---|---|---|---|---|---|---|
| A（36min，72 ticks） | 12112 | 72 | 23564 kB | 22912 kB | **−2.77%**（回收方向） | ✓ <10% 无上升 |
| kill -9 @tick72 | — | GONE 确认 | — | — | — | 进程干净消失 |
| B（24min，48 ticks） | 26636 | 47 | 23296 kB | 23516 kB | **+0.94%** | ✓ <10% 无上升 |

- 重启（B 段）：`EXT4-fs (ubda): recovery complete → mounted → UMARM_BOOT_OK`，无 F_SETLK panic（与 T3 同型）→ 门3「kill 重启 + 端口/锁重获」在驻留中断面成立。
- 退出：RESIDENT_DONE → guest `poweroff -f` → **exit_code=0**（duration_ms=1442886，B 段实例）——干净退出路径实测可用（对照 T3 stop-file 路径的 137）。
- 工作负载口径：idle tick 循环（非 dockerd），RSS 稳定即泄漏门的证据形态；对齐 um4 b1 的口径时注意 06 文档记录的「RSS 判定仅 daemon」差异——本项目判 guest 内核进程 RSS（06 文档 §4.4 修正口径）。
- **门2 判定：PASS**（两段 |Δ| 均 <10%，无时间回绕——attempt1 冻结-唤醒数据点中 UML 时钟以宿主墙钟追赶 1710s 无回绕，一并佐证）。
- 证据：`t5/t5-residence-raw.txt`（attempt2 全采样）、`t5/t5-residence-attempt1-console.log`（冻结事件）、`t5/t5-residence-exit.txt`、`t5/t5-residence-console-final.log`。

**T5 总判定：PASS**（门1 20/20 归因后 + 门2 两段 Δ<10% + 驻留中 kill 重启成功）。

## 6. T7 · 36 域 QEMU 装配补验（pacman DisableSandbox + `-Sy --needed`）

**判定：PARTIAL（时间盒停止）——装配管线机械面验证到 package-install 门前，网络面直驱受阻，package-install 终态未取数。**

**直驱方案**（等效产品 36 域 QEMU 通道，产物入 `research/uml-fabric-probe/t7/`）：ArchLinuxARM aarch64 rootfs（TUNA 镜像，829MB）+ `etc/pacman.conf` 预置 `DisableSandbox`（[options] 下 1 行）+ 产品 `orchestella-guest-provision.sh` 探针副本（唯一 diff：`:509` `-Syu` → `-Sy`，理由：无同步库时 `-S` 无法解析包，10-29 超时元凶是 `-u` 全量升级）+ `probe-init.sh`（kernel cmdline 静态寻址，与产品 `orchestella.guest_ip/gw/mask/dns` 通道等价；DNS=产品 `GUEST_DNS` 223.5.5.5）。

**验证到的链路段**（attempt=4 累计 4 次 boot，state 持久于镜像）：
- guest 启动（QEMU 11.1.1 + droidlayer kernel 7.2.3，ext4 on vda）✓；distro 判定 `archarm→arch` ✓；keyring pubring 检查（rootfs 自带，跳过 init）✓；`DisableSandbox` 预置被 pacman 接受（无 unknown-option 报错）✓；provision stage 状态机推进到 `stage=package-install` 并按产品格式报失败 ✓（`ORCHESTELLA_GUEST_PROVISION status=failed stage=package-install reason=offline-or-package-install-failed`——与会话 22 记录的失败串逐字同型）。

**网络面 4 次尝试的根因链**（每步都有日志证据，`evidence/uml-fabric-20261031/t7/`）：
1. 打包 QEMU 11.1.1 **无 slirp**（`network backend 'user' is not compiled into this binary`）——产品裁掉 user 网络靠 gvproxy/passt 面；
2. passt 在 Android shell 域被 SELinux 阻断（产品注释明示 "passt is blocked by Android SELinux"，QemuInstanceLauncher.java:634）；
3. gvproxy unix socket bind 被 shell 域 SELinux 拒（`listen unix …: bind: permission denied`——产品把 api.sock 放 app 私有目录规避，shell 域无此豁免）；TCP 45654 可用；
4. QEMU 11.1.1 ↔ gvproxy v0.9.0 协议不匹配：`-netdev stream` 与 `-netdev socket,connect=` 两种语法均以 `cannot read size from socket: EOF` 告终（gvproxy 期望 4 字节长度前缀帧）。产品的 QEMU↔gvproxy 对接 spec 不在 app Java 源内（在 DroidLayer 层），直驱重建到此受阻。

**含义**：10-29 的 `package-install reason=offline` 根因（guest 出网未通）在 shell 域直驱下**复现并定位到协议层**；要真正打完 package-install，要么用产品 App 全链路（QA 构建 + 完整装配流），要么把 QEMU↔gvproxy 的对接 spec 从 DroidLayer 层取出来复刻。留给下一设备窗口，与 W-FAB-03（AVF/网络面普查）同窗执行。

**对 36 域收敛方向的输入**：pacman 侧的预设修法（DisableSandbox + `-Sy --needed`）未发现问题点——失败点始终在传输层而非包管理器；配合 02 票「运行时下载内核=禁止、rootfs 下载=中风险」的渠道结论，**包安装失败不是 36 域收敛的结构性阻塞**。

## 7. 过程记录与方法论

### 7.1 本轮新坑（回写候选：36domain 文档 §6.1 增量）

1. **am start 重用实例**：runner/probe 类研究 App 若上次 Activity 仍为 top-most，`am start` 只派发 intent 不新建实例（无 onCreate → 无 boot）。修法：每次启动前 `am force-stop`。**后果严重性**：轮询脚本会把陈旧 exit 数据当成本轮结果——T1 run1 全部 20 轮因此作废（每轮 iteration 时长 ≈ poll 超时 52s 是识别特征），已隔离为 `*INVALID-run1*`。
2. **UML SMP 多核旋钮**：`ncpus=`（非 `--cpus=`），默认 host-1。
3. **adb shell 后台 QEMU 存活**：须 `setsid … & < /dev/null` + 启动命令内 sleep + pidof 校验；清理用精确 pid kill；`pkill -f` 会自匹配自杀（comm 匹配的 `pkill qemu-system` 安全）。
4. **Alpine make 4.4.1 在 Android shell**：`--jobserver-style=pipe`（否则 mkfifo 被拒、并行静默退化）；musl 二进制经 `tc/lib/ld-musl-aarch64.so.1 --library-path` 包装；busybox `date` 无 `%N`，guest 内计时用 /proc/uptime。
5. **启动面偶发**：T3 cycle 2 出现一次 am start 后 40s 内无 boot（烧录未开始），复现率 1/6 左右（T1/T3/T5 合计观测），与锁/SELinux 无关，疑似 HyperOS 后台启动限流——T5 门 1 的 20 循环会给出更稳的频率估计。
6. **HyperOS cgroup freezer 冻结后台 App 连带 UML（T5 门2 attempt1 实锤，重要产品化输入）**：屏幕 Dozing 后数分钟内，runner App 进程与其 UML 子进程同入 `0::/apps/uid_10051/pid_6642` 且 `cgroup.freeze=1`（cpuset 也落 `background`）——guest uptime 冻结在 0.32s、console 不再 tick、RSS 恒定 23932kB，**冻结 33 分钟零内存漂移**；唤醒屏幕（WAKEUP + `svc power stayon usb` + deviceidle whitelist）后 `cgroup.freeze→0`，guest uptime 从 0.32 跳至 1710s（UML 时钟以宿主墙钟追赶，无回绕）、内存仅 +32kB → **无泄漏方向的强信号**，但也证明：**App 域长驻（W-064 门2 形态）在 HyperOS 上必须持前台服务/wake lock，否则进程整个被冻结**（06 票 §4.4 preflight「后台约束：须持前台服务并记录状态」从设计建议升级为设备级硬证据）。attempt1 原始数据隔离于 `t5/t5-residence-raw.INVALID-attempt1-hyperos-frozen.txt`。

### 7.2 本会话仓库改动（research/ 下，均未提交）

- `research/uml-runner/app/build.gradle`：targetSdk 拆 flavor（sdk36=36 / sdk28=28），`keepDebugSymbols += '**/libuml_kernel*.so'`（覆盖新增 SMP 内核），versionCode 2。
- `research/uml-runner/app/src/main/jniLibs/arm64-v8a/`（gitignore 内）：libuml_kernel.so / libuml_kernel_smp.so / libuml_stub.so ← release-assets v0.3.0（sha256 校验过，APK 解包复核逐字节一致）。
- 设备侧构建产物（768MB C200 QEMU 镜像等）全部在 `/data/local/tmp` 与仓库外 evidence，未入库。

### 7.3 温度与环境

- T4 矩阵（3 轮）：zone27/zone0 从 37.0/36.6°C 起步，单列最大升幅 +14.3°C（smp2 r1 后 51.3°C）；r2/r3 起点已热化（40–50°C），smp1 三轮 315→399→477s 的漂移与热化同步——跨轮绝对值比较需配对同轮列比值（§4.1 已按此口径）。
- native 列同样热敏感：j1 冷态 23.95s（v2 会话，36°C）→ 热态 31.75s（50°C）≈ +33%。
- T5 驻留：屏幕常亮（`svc power stayon usb`）+ USB 供电下 RSS 判定不受热漂移影响（RSS 门非性能门）。
- 全程 USB 供电、电量 36%→≥20%，未触发 thermal shutdown/降频事件记录。

## 8. 探针资产与可复现入口

- 仓库内（本会话新增，均未提交）：`research/uml-fabric-probe/`（gen_c200.py、guest-overlay-uml/、guest-overlay-qemu/（含 qrun.sh）、t7/{probe-init.sh, orchestella-guest-provision.pacman-probe.sh}、README.md）；`research/uml-runner/app/build.gradle`（sdk36/sdk28 flavor + keepDebugSymbols 模式）。
- 仓库外证据：`evidence/uml-fabric-20261031/{t1,t3,t4,t5,t6-host-pagesize-evidence.txt,t7}/` + 设备侧 `/data/local/tmp/{qemu-bin,c200-qemu.ext4,t7-arch.ext4,qrun.sh,t7-net.yaml}`。
- 镜像构建：WSL `mkfs.ext4 -F -d <staging>`（UML 96MB：alpine+init+work+data32m；C200 QEMU 768MB：+clang22 工具链+make+c200；T7 3GB：ArchLinuxARM+DisableSandbox+probe）。
- 会话内已作废/隔离数据：`t1/*INVALID-run1*`（am start 重用实例）、`t5/t5-residence-raw.INVALID-attempt1-hyperos-frozen.txt`（freezer）。

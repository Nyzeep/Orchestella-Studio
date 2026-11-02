# orchestella-uml-fabric 真机设备窗口第二轮 · ACR 取数收口（T7-F/T8/T9/T10–T13）

- 日期：2026-11-08（设备执行会话，单会话串行；批次 3 T7 配方的实机验证轮）
- 基线：main `24ab52ca`（fabric 批次 3 收口后）
- 设备别名：**device-mey**（MagicOS，Android 16 / SDK 36，高通 8 核平台，11.5GB RAM，宿主内核 `6.6.118-android15-8-…-4k` = **4K 页**，USB 供电）——注意与上轮 device-myron（Android 17/SDK 37/6.12 内核）**不是同一台设备**
- 证据根（仓库外）：`evidence/uml-fabric-20261101/`（`t7/ t8/ t10/ t11/ t13/` 子目录）
- 探针资产：`research/uml-fabric-probe/`（qrun.sh net 模式经本轮三处实修）、`research/uml-runner/`（批次 3 KeepAliveService + stop-file watcher 双修在本轮完成设备端到端验证）
- 性能口径：同轮同夹具；计时 = guest 内 `/proc/uptime`；控温 = thermal_zone1（cpu-2-0-0，大核簇）+ zone17（cpuss-0-0）；**跨设备、跨夹具数字不相除**

## 0. 判定总表

| 探针 | 判定 | 关键数字 | 证据路径（evidence/uml-fabric-20261101/） |
|---|---|---|---|
| T7-F · T7 终态（36 域 QEMU 装配全链） | **PASS（awaiting-init/native-handoff 设计终态）** | 全 stage 通过 exit 0：92 包事务（systemd 262.1 全家族）→ machine-identity → credentials → ssh-configuration → service-enablement → native-handoff；分层诊断 L1 ARP REACHABLE / L2 ping 0% loss / repo-refresh ✓；13 次 attempt 九层根因链见 §1 | `t7/t7f-raw.txt`、`t7f-attempt13-FINAL-console.log/-markers.txt`、`t7f-attempt{1..12}-*.log` |
| T8① · stop-file watcher 设备端到端 | **PASS**（3 路径） | P0 自然退出 exit=0 无残留；P1 预埋 stop 被新实例启动期消费后正常跑完（批次 3 启动期清理修复的真机实证）；P2b 运行中植入 → `reason=stop-file` + 消费无残留 | `t8/t8-stopfile-raw.txt`、`t8-p2-exit.txt`、`t8-p2-console.log` |
| T8② · 前台服务+wake lock 60min 无人工驻留 | **PASS** | 12×5min 采样 frz=0 全程；RSS 中位漂移 **0.0%**（22592→22600kB）；tick 0→120 推进、guest uptime 3618s；**RESIDENT_DONE → exit_code=0 reason=exited（按设计满 tick 自主关机）**；S12 的 alive=DEAD 即该设计终态而非冻结 | `t8/t8-residence-raw.txt`、`t8-residence-console.log` |
| T9 · F-MTTCG-C200 补轮 n≥10 | **PASS（S(2) 复核成立）** | smp1 中位 **1014.5s**（IQR=1.0）/ smp2 中位 **632.5s**（IQR=1.0）→ **S(2)=1.60、E(2)=0.80；配对同轮 S(2) 中位 1.61（IQR≈0，n=9 对）**；20 列次 `.o` digest 全一致（5f94de57…）+ n=200；环境注记：宿主平台大核频率受限（max 3.2GHz、持续负载 ~1.5GHz，Battery Saver OFF 亦然）→ 绝对墙钟不可跨设备比较，列间比值口径有效；上轮 device-myron S(2)=1.66@n=3 → 本轮 device-mey 1.60@n=10 **跨设备一致** | `t9/t9-raw.txt`、`t9-raw.attempt1-partial-timeout.txt`（超时部分数据）、`t9-r*-smp*-console.log` |
| T10 · D9 Docker（UML 内 dockerd） | **PASS** | cgroup2 控制器 cpu/io/memory/pids；dockerd(overlay2, userland-proxy=false, bridge=none) 存活；`docker load` + `docker run` 输出 `Hello-from-Docker-in-UML-T10` RC=0；dockerd RSS 76128kB | `t10/d10-console.log` |
| T11 · 16K guest 链接阶段 | **PASS** | 16K guest（page=16384）内 clang 22.1.3 `-c` → `ld.lld` 动态+静态两路链接成功并执行（`T11_LINK_EXEC_OK`）；shebang hostfs 约束的 ext4 解法实证（`T11_SHEBANG_EXT4_OK`）；ld.bfd 127（依赖缺口，备查） | `t11/t11-console.log` |
| T12 · 16K 通道（宿主面） | **not-testable** | device-mey 4K 页（`uname -r` 尾缀 `-4k`、`getconf PAGESIZE`=4096、无 16K boot 属性/开关痕迹）；16K guest 内核面由 T10/T11 再次覆盖 | `t13/…`（host pagesize 记录） |
| T13 · distcc COMPLETED@36 | **设备面不可用** | `service list` 无 AOSP `virtualizationservice`；仅 `vendor.qti.qvirt.IVirtualizationService/default`（高通 vendor 栈，非 AVF 管理面）→ 按 ACR 规则跳过不重试；本身构成 W-FAB-03 OEM 普查新样本 | `t13/virtualization-service-list.txt` |

## 1. T7-F · T7 终态：13 次 attempt 的完整根因链（本轮主戏）

**配方**：`qrun.sh net`（端口 24117）+ `t7/gvproxy-t7.yaml` + 产品 provision 探针副本 + `probe-init.sh`（Arch guest，静态寻址 192.168.127.15，DNS 223.5.5.5）。QEMU/gvproxy/Image 从设备上 `orch.apk` 解包（QEMU 11.1.1，与 `artifacts/qa-jniLibs-4k-arm64` 逐字节同源）；Arch rootfs 镜像沿用上轮 WSL 构建（3,221,225,472 B，e2fsck 干净）。

**分层诊断配方首次实机验证成功**（attempt2/3）：`T7_ARP: REACHABLE`（L1 链路层✓，gvproxy qemu 监听活着且回 ARP）→ `T7_PING_GW: 0% loss rtt 3-8ms`（L2 网关面✓）→ repo-refresh `pacman -Sy` 成功（L3 DNS/上游✓）→ 失败点始终在 package-install 一层内。11-08 闭环文档的「一次定位到层」设计目标达成。

**attempt→根因链**（每步有日志证据）：

| # | 失败层 | 根因 | 修复 |
|---|---|---|---|
| 1（配方） | boot 前 | `init=/init`：t7-arch.ext4 **无 /init**（Arch /sbin/init=systemd 链接），必 panic | `init=/probe-init.sh`（qrun.sh 实修） |
| 2（会话） | SPAWN_FAIL | **注释插入 exec 反斜杠续行链**：`\` 续行把注释拼入命令行，`#` 后参数全被吞，qemu 无 -kernel 裸起即退 | 注释移出续行链（教训入库 qrun.sh 注释） |
| 3（配方） | SPAWN_FAIL | **LISTEN 检查列错位**：`/proc/net/tcp` 列序 `sl local rem st`，脚本读 3 变量把 rem 当 st，`0A` 永不命中 → 15s 后 exit 5 静默退出 | `read -r _ local _ st _`（qrun.sh 实修） |
| 4（镜像） | DNS 面 | `/etc/resolv.conf` 为**悬空符号链接**（指向 systemd-resolved stub），printf 写入 ENOENT，guest 全程无 DNS → pacman 镜像解析失败 | debugfs 换实文件（镜像手术） |
| 5（探针 diff） | 依赖墙 | `-Sy` 撞 **systemd 精确 pin**：`systemd-resolvconf/systemd-sysvcompat` 要求 `systemd=261.2`、现仓 262.1 → `could not satisfy dependencies`（10-31「-Sy 预设修法未发现问题点」判词需增补此边界） | **恢复产品同形 `-Syu`**（探针与产品唯一 diff 归零） |
| 6（产品参数） | 事务墙 | `-Syu` = **92 包事务**（含全量 linux-firmware-* 拆分包，实测下载 ~1.36GB @1.9MB/s），TCG 下载+解包超出产品 `run_step` **1800s/操作超时** → `operation-timeout`(124) | boot-retry 收敛（产品状态机原生形态） |
| 7（产品缺口） | 锁遗留 | 超时 SIGKILL 的 pacman 遗留 `/var/lib/pacman/db.lck` → 下一次 boot `unable to lock database`（报成 offline）——**产品 provision 无锁恢复路径**（回填建议） | probe-init 增加锁清理（探针侧） |
| 8（镜像 vintage） | machine-identity | `/etc/machine-id` 0 字节（等 systemd 首启填充），`dbus-uuidgen --ensure` 拒绝空文件 | probe-init 删空文件重建 |
| 9（环境互斥） | 网络停滞 | attempt7 与 T8② 熄屏 doze 窗口并行 → `Operation too slow. Less than 1 bytes/sec`（doze 下 WiFi 休眠掐断 gvproxy 上游） | 严格串行：T7-F 只在亮屏窗口跑 |
| 10（产品缺口） | ssh 校验 | openssh 10.6p1 `sshd -t` 强校验 privsep 目录 `/usr/share/empty.sshd`，而镜像目录层 0777（10-31 在 WSL DrvFs 上 staging 的遗留）→ `must be owned by root and not group or world-writable` exit 255 | probe-init `chmod 755`（探针侧）；**产品构建建议：staging 必须在 WSL 原生 ext4 组装** |
| 11（会话） | init ENOENT | adb push 与 QEMU 启动竞速（尺寸触发误判）+ pull→debugfs→push 循环把 `poweroff -f` 脏日志态带进推送镜像（EUCLEAN）+ debugfs rm+write 的 dentry 断链（ENENTRY 指向已删 inode，`e2fsck -fn` 不拦） | **推送后必须设备侧 md5 对账才准启动**；注入改用 loop-rw 正规文件操作 |

**终态（attempt13，md5 对账后）**：

```
ORCHESTELLA_GUEST_PROVISION status=awaiting-init stage=native-handoff
  reason=services-not-yet-verified attempt=1 operation_exit_code=0
  last_operation_stage=service-enablement last_operation_exit_code=0
```

- **全 stage 通过（exit 0）**：92 包 `-Syu` 事务（下载 ~520MB 缓存命中后实下载、TCG 解包全程 <1800s）→ machine-identity（dbus-uuidgen 重建）→ package-verification → init-verification → credentials → locale → ssh-host-keys → ssh-configuration（empty.sshd 修复后 `sshd -t` 过）→ service-enablement → **native-handoff**。
- **`awaiting-init/native-handoff` = 本探针形态的设计正确终态**（guest PID1=probe-init 非 systemd，服务验证按产品两阶段设计移交原生 init 交接，与产品 `--bootstrap` 模式收尾同型）。`T7_PROVISION_RC=0`、`T7_STATE status=awaiting-init`。
- 11-07 轮的「协议不匹配」误诊至此彻底翻案：**产品语法直驱（qrun.sh net + gvproxy-t7.yaml + 产品 provision 同形脚本）在 shell 域全链可用**；10-29 的 offline 失败与 11-07 的 EOF 失败的真实根因分别是 DNS/accept 槽位与上述九层新因素，无一在协议层。

## 2. T8 · watcher/前台服务 Java 侧回归（批次 3 修复的设备端到端）

### 2.1 stop-file 三路径（P0/P1/P2b 全 PASS）

- **P0**：干净 boot → bootprobe 自退 → exit_code=0 + 无 stop 残留。
- **P1（启动期清理——批次 3 修复①）**：预埋 `files/uml-stop` → force-stop → am start → **新实例启动期消费遗留文件并正常跑完**（console 出现 "清理上一实例遗留" 路径生效），exit 0 无残留。11-07 轮 T5 门1 cycle1 的假失败（stop-file 遗留误杀）自此在真机上闭环。
- **P2b（运行中停止）**：work=resident 长驻负载下植入 stop → watcher SIGTERM 消费 → `reason=stop-file`、stop 文件被消费无残留。注：首版 P2 用 bootprobe 负载出现夹具竞态（1.7s 自退快于 2s 轮询，touch 落在实例死后），非 watcher 缺陷——方法论教训：**中停测试必须用长驻负载**。

### 2.2 60min 无人工驻留（前台服务 + PARTIAL_WAKE_LOCK，无 stayon/白名单）——PASS

- 前置实证：`KeepAliveService isForeground=true`（specialUse）、`PARTIAL_WAKE_LOCK 'UmlRunner:uml-kernel'` 持有（acquire 起点即 boot 前）、熄屏后 app 落 `foreground` cpu cgroup（top-app↔foreground 弹跳，全程未进 background/frozen）。
- **12×5min 采样（S1–S11 + S12 设计终态）**：
  - **G1 冻结门**：`cgroup.freeze=0` 12/12 采样全程（freezer 从未介入）；
  - **G2 RSS 门**：首 10 采样中位 22592kB → 末 10 采样中位 22600kB，**Δ = +0.04%（≈0）**（S1–S3 的 24264→22592 为启动后正常回收方向）；
  - **G3 时钟门**：`RESIDENT_TICK` 每 30s 一跳无缺失，guest uptime 0→3618s 单调（无回绕、无冻结平台期）。
- **终态**：`RESIDENT_DONE uptime=3618.48` → `UMARM_WORK_DONE rc=0` → **exit_code=0 reason=exited duration_ms=3618674**——work=resident ticks=120 的按设计满 tick 自主关机（S12 采样的 alive=DEAD 即此，非冻结死亡）。
- 对照：11-07 轮 T5 门2 attempt1（无前台服务）熄屏数分钟内整组冻结 33 分钟；本轮仅凭批次 3 的 KeepAliveService(specialUse)+PARTIAL_WAKE_LOCK 在 device-mey（MagicOS）上 60 分钟零冻结且干净退出——**「App 域长驻必须持前台服务」从设计建议升级为双设备证据链（HyperOS 冻结实锤 + MagicOS 前台服务豁免实证）**。

## 3. T10 · D9 Docker（UML SMP 内 dockerd）——ACR D9 缺项闭合

**判定 PASS**（无网络形态：镜像预置 load + bridge=none；guest 无外网是 UML 无网通道的既定形态，不影响 dockerd/runc 执行链判定）。

- 内核先决全绿（`extracted-linux-bionic-smp.config`）：CGROUPS 全家（cpu/io/memory/pids，v2 挂载实测控制器一致）、NAMESPACES 全套、OVERLAY_FS、VETH、BRIDGE+BRIDGE_NETFILTER、NF_NAT、KEYS、SECCOMP、IO_URING。
- 执行链：`dockerd --iptables=false --bridge=none --userland-proxy=false --storage-driver=overlay2`（PID 存活）→ `docker load`（docker-archive 平铺布局）→ **`docker run` 输出 `Hello-from-Docker-in-UML-T10`，RC=0**。
- 过程坑（记录）：① alpine 静态 busybox 自建最小镜像（registry 令牌流在 CN 网络不可用）；② docker 29 静态包 `userland-proxy-path` 校验需 `--userland-proxy=false`；③ buildkit 按 PATH 找 runc（工具目录须入 PATH）；④ ubd1 挂载点须先 `mkdir -p`（与 t11 同型）。
- 资源账本输入：dockerd RSS 76128kB（UML 内，mem=768M guest）——D9 lane 的内存预算锚点。
- cgroup freezer 对 dockerd 长驻的影响：与 T8② 同窗不同负载，本轮以 T8② 的 app 域冻结观测为代理（dockerd 长驻冻结测试留待产品化后）。

## 4. T11 · 16K guest 链接阶段——ACR「链接阶段」缺项闭合

**判定 PASS**。

- 环境：UML 16K guest（page=16384）+ ubd1 工具盘（alpine clang22 22.1.3 全套 + binutils 2.45.1 + lld22 22.1.3 + musl dev：crt1.o/libc.a/ld-musl loader）。
- 链路：`clang --sysroot -c -O1`（hello.o 1376B）→ `ld.lld -dynamic-linker …/ld-musl-aarch64.so.1 crt1.o … -lc`（RC=0）→ **执行输出 `T11_LINK_EXEC_OK`**；`ld.lld -static`（libc.a）同样 RC=0 + 执行成功。
- **shebang hostfs 约束的 ext4 解法实证**：10-29 轮 hostfs rootfs 下 `#!/bin/sh` 不可执行（须 `#!/bin/busybox sh`）；本轮 ext4 rootfs 通道 `#!/bin/sh` 直接生效（`T11_SHEBANG_EXT4_OK`）——ext4 通道（T1–T5/T10/T11 既定形态）自愈该约束。
- 备查：`ld.bfd` 调用 127（LD_LIBRARY_PATH 指向 tc 库后仍未解，疑 binutils apk 依赖闭包缺口）；lld（与 clang 同版 22.1.3）为主判定路径已双路通过，不影响判定。

## 5. T12 / T13 · 门检定案

- **T12 not-testable**：device-mey 为 4K 页宿主（kernel 尾缀、PAGESIZE、无 16K boot 通道证据三条独立）。16K guest 内核面（W-061 反向半边）由 T10/T11 在 4K 宿主上再次覆盖；stub_exe-16k（W-062）仍等 16K 宿主窗口。
- **T13 设备面不可用**：`service list` 无 AOSP `virtualizationservice`；注册的是 `vendor.qti.qvirt.IVirtualizationService/default`（高通自用 VM 栈）+ 无关的 vmtethering/virtualdevice。distcc COMPLETED@36 需 AVF 管理面 → 本设备不可行，按规则跳过。**该负样本本身构成 W-FAB-03 OEM 普查数据点**：HONOR 设备（MagicOS/Android 16/first_api_level 36）= vendor-qvirt-only。

## 6. 过程记录与方法论（本轮新坑，回写候选）

1. **qrun.sh 三处实修**（§1 表 #1/#2/#3）：init 路径、`\` 续行链注释吞噬、`/proc/net/tcp` LISTEN 列错位——「配方未经设备验证」的三个典型缺口，全部已在仓库 research/ 内修正。
2. **镜像手术工作流**：WSL `debugfs -w` 注入脚本/修文件 + `e2fsck -fn` + 重推（3.2GB ≈ 13 min）——比整镜像重建便宜一个数量级，本轮用了 4 次。
3. **熄屏 doze 掐 QEMU 网络**（§1 #9）：T7-F（shell 域 QEMU 网络面）与 T8②（熄屏 doze 窗口）**不可并行**——探针排程约束。
4. **runner 进程探测口径**：app 域 UML 的进程名是 argv[0] 基名 `libuml_kernel.so`（comm 截断 `libuml_kernel.s`），`pidof libuml_kernel.so` 可用；`ps -o NAME` 口径不可靠。
5. **force-stop → am start 竞速 + 权限弹窗幽灵记录**：无间隔时 START 落在死 Activity 仍 top-most 的瞬间（intent 投给死记录，无实例）；首启 FGS 的运行时权限弹窗未处理时同样卡住。修法：force-stop 后 sleep 3 + `am start -W` 观察 LaunchState。
6. **docker-load tar 必须平铺**（manifest.json + blob 同层，tar 套 tar 不被识别）。
7. **驱动证据命名**：按时间戳命名 attempt 产物，防覆盖（本轮 t7f 驱动已改）。

## 7. 对 ACR 就绪度清单（§8.2）的闭合情况

| 缺项 | 状态 |
|---|---|
| 1. T7 终态 | **已闭合**（attempt13 awaiting-init/native-handoff exit 0；11-08 配方经三修后一次跑通全链） |
| 2. 16K 宿主 | 未闭合（无 16K 设备，T12 not-testable 两次确认） |
| 3. D9 Docker | **已闭合**（T10 PASS） |
| 4. 链接阶段 | **已闭合**（T11 PASS） |
| 5. W-FAB-03 OEM 普查 | 未闭合（+1 负样本：HONOR 设备 vendor-qvirt-only） |
| 6. distcc COMPLETED@36 | 本设备面不可用（无 AVF 管理面；需 AVF 设备窗口） |

（T9 F-MTTCG 补轮为数据增强项非缺项，见 §0/§8。）

## 8. ACR 议程建议（最终）

**就绪度结论**：六缺项中 **T7 终态 / D9 Docker / 链接阶段三项本轮闭合**；F-MTTCG S(2) 复核成立（n=10 统计支撑「手机宿主 QEMU MTTCG 最优宽度=2 vCPU」，跨设备 1.66→1.60 一致）。剩余三项全部是**设备获取依赖而非工作依赖**：
- **16K 宿主**（T12 第二次 not-testable；W-062 stub_exe-16k 行为等价仍等 16K 设备）；
- **W-FAB-03 OEM 普查**（+1 负样本：HONOR 设备 vendor-qvirt-only；AOSP AVF 样本仍 0——需非 protected-only 设备）；
- **distcc COMPLETED@36**（本机无 AOSP virtualizationservice → 设备面不可用，需 AVF 设备窗口）。

**议程建议**：
1. **头号议题不变**：开发通道产品形态裁定（05 票双轨模型 + 本轮 T7-F 终态/D9/链接补数后，四车道能力矩阵的 UML/QEMU 两侧证据已齐；protected 侧仍依赖 W-FAB-03 扩样）。
2. **建议按「四项已闭合 + 三项设备获取依赖显式标注 not-available-on-hand」开投票**，不再等设备：16K/stub-16k、W-FAB-03 扩样、distcc@36 列为投票后条件项（判词若依赖它们需注明「以新设备到位为前提」）。
3. **产品化缺口清单（本轮新增，非阻塞投票）**：provision 无 pacman db.lck 锁恢复路径；1800s/操作超时在 TCG+大版本差场景不足（boot-retry 可收敛但首启 >30min）；rootfs 构建必须脱离 DrvFs（0777 遗留被 openssh10 强校验拦截）；T8② 已证前台服务+wake lock 为长驻硬前提（启动器产品化时照 `research/uml-runner` 同型落地）。
4. T9 附带输入：QEMU 车道 vCPU 预算按 2 记账的判词获 n=10 复核；频率受限宿主档位上 TCG 绝对性能再降 ~2.5×（1014.5s vs 旧机 399s 同列），fabric 派发的 QEMU 面成本模型需按宿主档位分档。

## 9. 探针资产与可复现入口

- 仓库内（本轮实修，随本报告一并入库）：`research/uml-fabric-probe/guest-overlay-qemu/qrun.sh`（net 模式三修）、`t7/probe-init.sh`（分层诊断 + 锁清理 + machine-id 修复）、`t7/orchestella-guest-provision.pacman-probe.sh`（恢复 -Syu 产品同形，diff 归零）。
- 设备侧布局：`/data/local/tmp/{qemu-bin/{qemu-system-aarch64,gvproxy,assets/Image}, t7-arch.ext4, t7-net.yaml, qrun.sh, uml-bin/{linux-bionic-smp,stub_exe}, uml-rootfs.ext4（+work/d10.sh,t11.sh）, tools.ext4（docker/tc/miniimg）, c200-qemu.ext4}`。
- 证据根：`evidence/uml-fabric-20261101/`。

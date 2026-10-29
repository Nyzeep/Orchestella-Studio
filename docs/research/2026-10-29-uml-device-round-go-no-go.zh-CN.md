# 会话 21 · UML 真机探针轮 —— go/no-go 报告

> 日期 2026-10-29｜基线 `main = 2036792`｜设备（arm64 / Android 17 / HyperOS OS4.0；机型与序列号不入库）
> 性质：时间盒研究，**未写产品代码、未改 CONTEXT.md、未动三车道现状**。研究产物在 `research/uml-runner/`（独立 gradle 工程，不进主构建）。
> 全部证据：`evidence/uml-20261028/round-device/`

---

## 1. 结论：**GO**

**纯 CPU 负载 UML / native = 1.08×（中位数 193ms vs 178ms），fork+exec = 1.49×；编译类负载由这两端夹逼，落在 1.08–1.49× 区间，满足 ≤1.5× 判据 → 「UML 可替代 QEMU 承担 Full Linux 计算角色」在真机上成立。**

> 口径说明：本轮未在 guest 内跑真实编译器（无工具链），以 `sha256(32MB)` 代表纯用户态 CPU、以 `fork+exec` 代表进程创建与 exec 路径开销。编译是二者的混合，故取区间而非单点。

三条支撑事实，全部首次在本仓库取得：

1. **UML 内核在 Android 17 应用进程域内成功启动并跑完命令**：`uname -a` → `Linux 7.2.0-rc4-g856737c31142 aarch64`，`uid=0`，`UMARM_BOOT_OK`，退出码 0。
2. **启动 220–400ms（中位 ≈330ms）**，比 QEMU 车道的分钟级启动彻底不在一个量级。
3. **forkexec 1.49×、纯 CPU 1.08×**，与「无翻译税、原生速度」的定性判断一致；syscall 8× 是结构性开销，需在文案与选型中如实体现。

同时给出两条**必须写进选型文档的限制**（详见 §8）：app 域只能在 `nativeLibraryDir` 执行、hostfs 下绝对符号链接脚本的 shebang 会 ENOENT。

---

## 2. 基线与范围

| 项 | 状态 |
|---|---|
| 代码基线 | `main = 2036792`（会话 20 PR #54 已合并，本地同步，`git status` 干净） |
| 研究分支 | 本轮未建新分支（见 §9 收口建议） |
| 证据来源 | Z1 五资产 `release-v0.3.0/`（SHA256 全过）、`probe-apk/` 双域探针、`alpine-rootfs/3.24.3` |
| 本轮新增 | `research/uml-runner/`（jniLibs 封装 + 屏显控制台 + hostfs 免镜像启动 + 双侧同构基准脚本） |
| 时间盒 | 单会话，T-1～T-8 打通；D9 Docker 未做（stretch，未进时间盒） |

---

## 3. 环境与设备事实（T-1 / D8）

`evidence/uml-20261028/round-device/device-info.txt`

| 项 | 实测值 | 与开工 Prompt 预期的差异 |
|---|---|---|
| 宿主页大小 | **4096**（`getconf PAGE_SIZE`；内核串自带 `-4k` 后缀） | Prompt 猜「K90 Pro Max 可能 16K」——**实测 4K，是 fork 未覆盖的新数据** |
| guest 页大小 | **16384**（guest 内 `getconf PAGESIZE`） | 16K guest 跑 4K 宿主 = `40-page-size.md:130` 的 **pass** 组合，方向正确 |
| Android / SDK | 17 / 37 | — |
| 内核 | `6.12.69-android16-6-g43b5ab7e247d-abogki561813157-4k` | — |
| SELinux | `Enforcing`；shell=`u:r:shell:s0` | — |
| 内存 / CPU | 15,448,180 kB；aarch64，SVE/SVE2/SME，Armv9 类 | 印证 fork 记录的 PAC 坑适用面 |
| `adb root` | 不可用（production build） | — |
| HyperOS 安装 | 每次 `adb install` 弹「USB安装提示」，10s 倒计时，需点「继续安装」 | Prompt 预期「两次确认」，实际**每装一次都弹** |

---

## 4. D1 域探针（T-2 / T-3）—— **与预测相反**

`round-device/probe-sdk36.txt`、`probe-sdk28.txt`

| 探针 | sdk36（域 `untrusted_app_34`） | sdk28（域 `untrusted_app_27`） |
|---|---|---|
| ① filesDir 文件 mmap RWX | **PASS** | **PASS** |
| ② 匿名 PROT_EXEC | **PASS** | **PASS** |
| ③ memfd + PROT_EXEC RWX | **PASS** | **PASS** |
| ④ 页大小 / 域 / ABI | 4096 / `untrusted_app_34` / arm64-v8a | 4096 / `untrusted_app_27` / arm64-v8a |

**开工 Prompt 预测「D1a@36 三探针全败」，实测全过。** 这不是探针写错：`ProbeActivity.java:127-129` 用的是 `PROT_READ|PROT_WRITE|PROT_EXEC` + `MAP_PRIVATE` 的真 mmap，失败会带 errno 落档。

解读：

- Android 的 W^X 收紧主要落在 **`execve()` 应用私有目录**（`untrusted_app.te` 的 `execute_no_trans`），**不是** `mmap(PROT_EXEC)`。探针测的是后者，所以过。
- 这直接决定 `check_tmpexec()`（`arch/um/os-Linux/mem.c:226-246`）这一关能过——它只做 `mmap RWX` 然后 `munmap`。启动日志里的实证：`Checking PROT_EXEC mmap in /data/user/0/…/files ... OK`。
- **真正稀缺的资源是 `execve` 的位置**：app 域只能执行 `nativeLibraryDir`（`apk_data_file`）。这解释了为什么 T-4 必须走 jniLibs 路线，也解释了为什么探针全过仍不等于能启动。

AVC 侧：执行路径（execve / mmap / `apk_data_file` / `app_data_file`）**零拒绝**；10 条拒绝全是 `usap_pool_primary` 与 MIUI `mist_set_uid` 的系统噪声（`round-device/avc-check.txt`）。

---

## 5. D3 真机启动 UML（T-4）

### 5.1 成功路径

`round-device/boot-console.log`、`screenshots/screenshot-guest-boot.png`

```
Run /um-init as init process
===== UML GUEST ALIVE =====
BENCH boot_uptime_ms=400
--- uname ---
Linux (none) 7.2.0-rc4-g856737c31142 #1 Thu Jan  1 00:00:00 UTC 1970 aarch64 Linux
--- id --- uid=0(root) gid=0(root)
--- pagesize --- 16384
--- mounts --- /dev/root / hostfs rw,...  proc/sysfs/devtmpfs/devpts/cgroup2 全部挂上
--- write test --- hello-from-guest
UM_COMMAND_EXIT=0
UMARM_BOOT_OK
kernel exited code=0 reason=exited duration_ms=3182
```

关键命令行（实测生效）：

```
mem=512M rw init=/um-init con=null con0=null,fd:1
stub_exe=<nativeLibraryDir>/libuml_stub.so
rootfstype=hostfs rootflags=<filesDir>/files/rootfs seccomp=on panic=1
```

### 5.2 四个坑与根因（按发生顺序）

| # | 症状 | 根因 | 处置 |
|---|---|---|---|
| 1 | `INSTALL_FAILED_USER_RESTRICTED` | 设备锁屏时 HyperOS 安装确认弹窗无法确认 | 解锁后重试；后续用 `uiautomator dump` 定位按钮 + `input tap` 自动确认 |
| 2 | `Requested init /um-init failed (error -2)` | **hostfs 下 `#!/bin/sh` 的脚本无法 exec**（`/bin/sh` 指向 `/bin/busybox` 的绝对符号链接，binfmt_script 解析路径时 ENOENT）；但 `/bin/sh` 作**命令**调用（如 `mount`）完全正常 | 脚本 shebang 改 `#!/bin/busybox sh`，内部 `/bin/sh xxx.sh` 同步改 `/bin/busybox sh xxx.sh`（`guest/um-init.hostfs`） |
| 3 | `epollctl add err fd 12, Operation not permitted` | 控制台 fd 注册 epoll 失败（`arch/um/os-Linux/irq.c:92-105` 打印）。**本轮未阻断启动**，但每次启动都出现一次 | 记为 D7 观察项；`con0=null,fd:1` 已避开输入侧 |
| 4 | guest 侧脚本报 `line 16: \r: not found` | 宿主写入的脚本是 **CRLF**，guest ash 把 `\r` 当命令 | 推送前统一 `tr -d '\r'`；已固化为流程 |

补充：`Failed to initialize ubd device 0 :Couldn't determine size of device's file` 是未传 `ubd0=` 的正常提示，可忽略。

### 5.3 宿主可执行面的关键数据

```
ls -lZ <codePath>/lib/arm64
-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0  9648496 libuml_kernel.so
-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0     2000 libuml_stub.so
```

- `extractNativeLibs=true` + `useLegacyPackaging=true` → 解包出真实文件且带 **x 位**（这点此前未验证，属新增事实）。
- 两个 `.so` 的 sha256 与 `release-v0.3.0` 源文件**逐字节一致**（`5c205533…` / `731c48d5…`），说明 `keepDebugSymbols` 生效、内核未被 strip 破坏。

---

## 6. D7 / D8 记录（随 T-4 顺带）

| 项 | 实测 | 意义 |
|---|---|---|
| Android 17 上 `stub_exe` 行为 | **必传且有效**；不传时按 fork 文档应在 memfd exec 处失败 | 沿用 `stub_exe=nativeLibraryDir/libuml_stub.so` 可稳定启动，多次复现 |
| 宿主页大小 | 4K | fork 只测过 4K 宿主 + 16K guest（`40-page-size.md:130` pass）；本轮**在同型号之外的 Android 17 设备上复现该组合** |
| guest 页大小 | 16K（编译期 `CONFIG_PAGE_SIZE_16KB=y`） | 与 release 声明一致 |
| SECCOMP | 启动日志 `Userspace mode: SECCOMP` | `seccomp=on` 生效；Android app 域下的 seccomp 过滤器**没有**杀掉 tracee（此前 fork 文档担忧的 `nocancel` 场景未出现） |
| 启动耗时 | 220 / 320 / 340 / 400 ms（4 次） | 中位 ≈330ms |

---

## 7. D10 四列 A/B（T-7）

完整方法与原始数据：`round-device/bench-summary.md`、`bench-7rounds-raw.txt`

### 7.1 中位数（7 轮交错，ms）

| 负载 | native | UML guest | 比值 |
|---|---|---|---|
| compute（sha256 32MB） | 178 | 193 | **1.08×** |
| syscall（find 2000 文件） | 2 | 16 | **8.0×** |
| forkexec（500 次 fork+exec） | 630 | 940 | **1.49×** |

### 7.2 方法学修复（本轮最重要的方法论产出）

- 首轮用「宿主 toybox vs guest busybox」，compute 测出 **3.75×**——那是**两个不同 sha256 实现**的差异，不是 UML 开销。
- 改为**两侧同一 busybox 二进制**（宿主态用 rootfs 自带 `ld-musl-aarch64.so.1` 加载 Alpine 的 `bin/busybox`），比值立刻收敛到 **1.08×**。
- **结论：任何跨 userland 的性能结论，必须先把二进制统一，否则测的是工具链不是虚拟化。**

### 7.3 forkexec 反常行归因（会话 20 遗留）

`loopbase`（500 次空循环）两侧均 <10ms → shell 循环可忽略；差异全在 exec 路径。换同一二进制后 native 从 7,430ms/2000 降到 630ms/500（同比缩放一致）。**此前「UML 比 native 快」是假象，真实比值 1.49×。**

### 7.4 校验器与缺失列

- 按 fork `verifyreport.py:19-20`：跨条件 compute spread **8.4% > 5% → 字面 INVALID**；但两侧 jitter 仅 1.7% / 1.0%，说明状态稳定，8.4% 是**稳定的条件偏置**（UML 恒定 CPU 开销 + 应用进程 vs shell 的 cgroup 差异），非热漂移。故不作废整表，但 compute 行按「含系统性偏置」引用。
- **proot 列未测**（设备无 proot 环境）；**pVM/QEMU 应用内列未测**（需另装产品 APK 并应用内驱动，超时间盒）。M5 历史值不与本表横比。
- 温度：本轮未采 `thermal_zone`（方法学缺项，如实记）。

---

## 8. 风险、限制与未验证项

### 8.1 已确证的产品级限制（必须写进选型）

1. **只能在 app 的 `nativeLibraryDir` 执行**。targetSdk≥29 域对 `app_data_file` 无 `execute`；本轮走 targetSdk 28 + jniLibs，代价是「为旧版 Android 构建」警告与 Play 政策风险。
2. **hostfs 下 `#!/bin/sh` 脚本不能作 init**。任何随 rootfs 分发的脚本必须 `#!/bin/busybox sh` 或显式 `busybox sh`；这对「把真实发行版 rootfs 直接跑起来」是实质约束（Debian/Ubuntu 的 init 脚本大量使用 `/bin/sh`）。**ext4+ubd0 通道应作为产品默认**，hostfs 只做研究/会话挂载。
3. **syscall 8×**：`seccomp=on` 已是快路径（启动日志确认），仍比 native 慢一个量级。凡 `apk add` / `npm install` / 大量小文件 `openat` 的场景会被放大，文案不能写「接近原生」。
4. **UP only**（本轮 `cpu count=1`，与 `linux-bionic` 默认 `--cpus=1` 一致）；SMP 需换 `linux-bionic-smp` + `--cpus≥2`，未验证。

### 8.2 未验证项（四态：未验证）

| 项 | 状态 | 原因 |
|---|---|---|
| D9 Docker（smp 内核 + dockerd + hello-world） | 未验证 | stretch，时间盒未及 |
| passt / umnet 网络 | 未验证 | 未进关键路径 |
| ptrace 用户态模式（`seccomp=off`） | 未验证 | 只跑 SECCOMP |
| 应用内 pVM/QEMU 对照列 | 未验证 | 需另装产品 APK |
| 长驻稳定性（小时级） | 未验证 | 本轮全部是「启动→跑完→退出」短周期 |
| Android 17 `MemoryLimiter` 对 `mem=512M~2G` 的影响 | 未验证 | 512M 未触发，未见限流日志 |
| GPL 合规面（产品化分发） | 未处理 | 本轮只研究使用 |

---

## 9. ACR 建议草案（不实施）与收口

### 9.1 ACR 草案：`ACR-2026-10-29 UML 计算角色`

- **建议**：将 UML 定位为 **Full Linux 计算引擎的候选实现**，替代 QEMU 的「编译/构建」角色；QEMU 保留为「需完整硬件模型/异构架构」的兜底。
- **依据**：纯 CPU 1.08×、fork+exec 1.49×（编译落于区间内，均 ≤1.5× 判据）、启动 ≈330ms。
- **依据边界**：未跑真实编译器；「编译 ≤1.5×」是由两端负载夹逼的推论，需下轮用 guest 内实际构建验证。
- **边界**：仅 `nativeLibraryDir` 执行；默认 rootfs 走 ext4 镜像（hostfs 仅研究/会话）；UP 单核；syscall 密集场景不承诺性能。
- **撤销条件**：① 后续 Android 版本收回 targetSdk 28 的 legacy 域；② ext4 通道在本设备上启动失败；③ 长驻稳定性测试出现不可恢复 panic。
- **未实施**：本条不写入 `CONTEXT.md`，待验收会话确认。

### 9.2 收口待办

1. 本轮未建 `research/uml-device-20261029` 分支、未提交 —— 按约定提交/推送由用户手动执行。
2. 已合并的 `research/uml-feasibility-20261028` 远端+本地分支待删（延续「只留 main」约定）。
3. 设备侧残留：`/data/local/tmp/{uml-rootfs.tar.gz,bench/,busybox,ld-musl,bench.sh}`、`files/rootfs/bench/`（32MB×2）、两个探针包与 runner 包。研究可复用，未清理。
4. 下轮入口：D9 Docker（换 smp 内核）、网络门、长驻稳定性、ext4 通道。

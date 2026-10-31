# uml-runner — 在 App 自己的 `nativeLibraryDir` 里跑 UML 内核（arm64）

研究用独立 Gradle 工程。目标：让一个 **targetSdk 28** 的普通 Android App 用
`ProcessBuilder` 直接 `exec` 自己的 `nativeLibraryDir/libuml_kernel.so`（UML 内核），
`stub_exe=` 指向同目录的 `libuml_stub.so`，rootfs 走 **hostfs 目录**通道，
输出落到 `filesDir`，便于 `adb` 取证。

- 包名：`dev.orchestella.umlrunner`
- compileSdk 36 / **minSdk 28** / 仅 `arm64-v8a`；targetSdk 拆双 flavor：`sdk36`（36 域判定）+ `sdk28`（10-29 轮对照基线），见 `app/build.gradle`
- 长驻保活：前台服务 `KeepAliveService`（specialUse）+ PARTIAL_WAKE_LOCK，见第 9 节
- 不进主 app 构建流水，不改 `research/uml-probe`

---

## 1. 目录结构

```
research/uml-runner/
├── app/
│   ├── build.gradle                     # packaging.jniLibs: useLegacyPackaging + keepDebugSymbols
│   └── src/main/
│       ├── AndroidManifest.xml          # extractNativeLibs=true, targetSdk 28, 透明主题
│       ├── java/dev/orchestella/umlrunner/RunnerActivity.java
│       └── jniLibs/arm64-v8a/
│           ├── libuml_kernel.so         # = release-v0.3.0/linux-bionic （9,648,496 B）
│           └── libuml_stub.so           # = release-v0.3.0/stub_exe     （2,000 B）
├── gradle/wrapper/, gradlew, gradlew.bat, settings.gradle, build.gradle, gradle.properties
├── prepare.sh                           # 宿主侧：安装 / push rootfs / 写 config / 起停 / 取日志
└── README.md
```

### 1.1 二进制来源与校验（未 strip，逐字节一致）

| APK 内路径 | 源文件 | 字节 | sha256 |
| --- | --- | --- | --- |
| `lib/arm64-v8a/libuml_kernel.so` | `evidence/uml-20261028/release-v0.3.0/linux-bionic` | 9,648,496 | `5c205533a5b02d5b22683475807aea7d6c2f4df8aec6be4d6034f8185ec59c18` |
| `lib/arm64-v8a/libuml_stub.so` | `evidence/uml-20261028/release-v0.3.0/stub_exe` | 2,000 | `731c48d5bf42ef25e26625af6a02627a5d658bf9f67d6f7fc443d6d644d6e52e` |

两者均为 `ELF 64-bit LSB executable, ARM aarch64, statically linked`（`e_type=ET_EXEC`）。

> **二进制不入库**：`app/src/main/jniLibs/` 已在 `.gitignore` 中排除（与主 app 的
> `/app/src/main/jniLibs/` 一致）。从干净检出构建前，按上表把两个文件从
> `evidence/uml-20261028/release-v0.3.0/` 拷入并核对 sha256。

> 关键：改名为 `lib*.so` 只为让 AGP 把文件放进 `jniLibs` → 安装时解包到
> `nativeLibraryDir`。**必须** `keepDebugSymbols += '**/libuml_kernel.so'`（AGP 默认会
> strip `.so`，strip 会破坏内核镜像），并 `useLegacyPackaging = true` +
> `android:extractNativeLibs="true"`（否则 `nativeLibraryDir` 里没有实体文件，无法 exec）。

---

## 2. 构建

```bash
cd "research/uml-runner"
MSYS_NO_PATHCONV=1 ./gradlew assembleDebug --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

构建后自检（应看到与第 1.1 节相同的两个 sha256）：

```bash
MSYS_NO_PATHCONV=1 unzip -o -j app/build/outputs/apk/debug/app-debug.apk 'lib/arm64-v8a/*' -d /tmp/apkx
sha256sum /tmp/apkx/libuml_kernel.so /tmp/apkx/libuml_stub.so
MSYS_NO_PATHCONV=1 "$ANDROID_SDK_ROOT/build-tools/36.1.0/aapt2.exe" dump badging \
  app/build/outputs/apk/debug/app-debug.apk | grep -E 'targetSdk|native-code'
```

---

## 3. 安装（Windows Git Bash，务必带 `MSYS_NO_PATHCONV=1`）

```bash
export ADB="$ANDROID_SDK_ROOT/platform-tools/adb.exe"
cd "research/uml-runner"

MSYS_NO_PATHCONV=1 "$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk
# 或
MSYS_NO_PATHCONV=1 ./prepare.sh install
```

**HyperOS / MIUI 常见拒绝**：`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`
不是 APK 问题。需要在手机上：设置 → 更多设置 → 开发者选项 → 打开「USB 安装」
（需登录小米账号），然后在安装弹窗点「继续安装」。详见第 7 节。

---

## 4. 准备 rootfs（hostfs 通道，单次 push）

```bash
MSYS_NO_PATHCONV=1 ./prepare.sh push
```

脚本做的三件事（等价命令）：

```bash
# 1) 整包 push 一次（不逐文件 push）
MSYS_NO_PATHCONV=1 adb push \
  "../evidence/uml-20261028/alpine-rootfs/alpine-minirootfs-3.24.3-aarch64.tar.gz" \
  /data/local/tmp/uml-rootfs.tar.gz

# 2) 设备侧解包：cat 由 shell 读（可读 /data/local/tmp），管道交给 run-as 后的 app 落盘
MSYS_NO_PATHCONV=1 adb shell "run-as dev.orchestella.umlrunner mkdir -p files/rootfs"
MSYS_NO_PATHCONV=1 adb shell \
  "cat /data/local/tmp/uml-rootfs.tar.gz | run-as dev.orchestella.umlrunner sh -c 'gzip -dc | tar -xf - -C files/rootfs'"

# 3) 校验
MSYS_NO_PATHCONV=1 adb shell "run-as dev.orchestella.umlrunner sh -c 'ls files/rootfs; cat files/rootfs/etc/os-release'"
```

- toybox `tar` **不支持 `-z`**，所以解压由 `gzip -dc` 负责，`tar -xf -` 从 stdin 读。
- app 自身读不到 `/data/local/tmp`（`shell_data_file` + DAC 权限），因此必须由 shell 侧
  `cat` 后经管道喂给 `run-as`——这也是为什么不能写成 `run-as ... tar -xzf /data/local/tmp/...`。

---

## 5. 配置 `filesDir/uml-config.json`

写入（`run-as` 通道）：

```bash
MSYS_NO_PATHCONV=1 ./prepare.sh config
# 等价：
#  printf '%s' '<json>' | adb shell "run-as dev.orchestella.umlrunner sh -c 'cat > files/uml-config.json'"
```

文件不存在时 Activity 使用内置默认值；字段全部可选：

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `memMb` | int | `512` | 展开成 `mem=<N>M` |
| `ncpus` | int | 省略 | **SMP 内核多核旋钮**：≥2 时追加 `ncpus=<N>`；省略 = 默认 **host-1**（单核）。写作 `ncpus=N`，**不是** `--cpus=N`（SMP 内核不识别 `--cpus=`，unknown param 会透传 userspace；见第 10 节）。`args` 覆盖时此字段不生效，需在 args 里自行写 `ncpus=N` |
| `rootfs` | string | `filesDir/rootfs` | 展开成 `rootflags=<dir>` |
| `kernel` | string | `nativeLibraryDir/libuml_kernel.so` | 可换内核（如 smp 版） |
| `stubExe` | string | `nativeLibraryDir/libuml_stub.so` | 展开成 `stub_exe=<path>` |
| `tmpDir` | string | `filesDir` | 子进程 `TMPDIR`（UML guest RAM 后备文件落这里，需可 exec） |
| `stdin` | `"pipe"` \| `"null"` | `"pipe"` | `pipe` 保活 fd0 可 poll；`null` 接 `/dev/null` |
| `args` | string[] | 见下 | **整体覆盖**默认参数；支持 `${stubExe}` `${rootfs}` `${nativeLibDir}` 占位 |

默认参数数组（顺序即 argv；`ncpus` 仅在配置 ≥2 时追加到末尾）：

```
mem=512M  rw  init=/um-init  con=null  con0=fd:0,fd:1
stub_exe=<nativeLibraryDir>/libuml_stub.so
rootfstype=hostfs  rootflags=<filesDir/rootfs>
seccomp=on  panic=1  [ncpus=<N>]
```

示例（换 1G 内存 + SMP 内核 4 核 + 自定义 rootfs）：

```json
{
  "memMb": 1024,
  "ncpus": 4,
  "kernel": "/data/user/0/dev.orchestella.umlrunner/files/linux-smp",
  "rootfs": "/data/user/0/dev.orchestella.umlrunner/files/rootfs",
  "args": [
    "mem=1024M", "rw", "init=/um-init", "con=null", "con0=fd:0,fd:1",
    "stub_exe=${stubExe}", "rootfstype=hostfs", "rootflags=${rootfs}",
    "seccomp=on", "panic=1", "ncpus=4"
  ]
}
```

> SMP 多核旋钮是 `ncpus=N`（默认 host-1，即省略 = 单核）。`--cpus=N` 不被 SMP
> 内核识别（unknown param 透传 userspace），不要使用——2026-11-07 设备轮
> §7.1.2 实测结论，全目录已按 `--cpus` 清零口径复核。

> `con0=fd:0,fd:1` 要求 fd0 可 poll；若内核报 `epoll_ctl: EPERM`，把 `con0` 改成
> `null,fd:1`（用 `args` 覆盖）。

---

## 6. 运行与取日志

```bash
export ADB="$ANDROID_SDK_ROOT/platform-tools/adb.exe"

# 启动（脚本会先清 logcat 再 tail）
MSYS_NO_PATHCONV=1 ./prepare.sh start

# 或手动
MSYS_NO_PATHCONV=1 "$ADB" shell am start -n dev.orchestella.umlrunner/.RunnerActivity
MSYS_NO_PATHCONV=1 "$ADB" logcat -s UmlRunner:I

# 另开一窗：看已落盘的内容
MSYS_NO_PATHCONV=1 "$ADB" shell "run-as dev.orchestella.umlrunner cat files/uml-console.log"
MSYS_NO_PATHCONV=1 "$ADB" shell "run-as dev.orchestella.umlrunner cat files/uml-exit.txt"

# 取回到本地 out/
MSYS_NO_PATHCONV=1 ./prepare.sh pull

# 收尾（内核收到 SIGTERM，3s 不退则 SIGKILL；stop 文件会被自动删除以便重跑）
MSYS_NO_PATHCONV=1 ./prepare.sh stop
# 等价：adb shell "run-as dev.orchestella.umlrunner sh -c 'touch files/uml-stop'"
```

`filesDir` 下的产物：

| 文件 | 内容 |
| --- | --- |
| `uml-console.log` | 覆盖写。头部是设备/内核/stub/rootfs 元信息 + argv，其后是内核 stdout+stderr 逐行镜像 |
| `uml-exit.txt` | `exit_code`、`exit_code_hex`、`signal_guess`（>128 时 −128）、`reason`（`exited`/`stop-file`/`spawn-error`）、`ended_at`、`duration_ms`、argv |
| `uml-stop` | 存在即停止（由 App 消费后删除） |
| `uml-config.json` | 可选配置 |

logcat 每条同时打 `UmlRunner`（超 3000 字符自动分片，规避 4KB 单条上限）。

启动前 App 会做**非阻断**预检，把 `kernel.canExecute`、`stub.canExecute`、`kernel.len`、
rootfs 是否存在一行行写进 `uml-console.log` 头部（`precheck:` 前缀进 logcat），
用来区分「exec 被拒」与「路径/权限配置错」。

---

## 7. 已知风险 / 未验证项

1. **真机安装被系统策略拦截（本轮实测）**：
   `adb install` 返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`
   （HyperOS `OS4.0.5.0.XPMCNXM` / `V816` / Android 17 SDK 37）。这是设备的「USB 安装」
   开关未开，需要手动在手机上授权，无法用 adb 绕过（无 root）。**因此本轮的
   install → run → 取日志链路未在真机跑通，只有构建与 APK 内部校验是实测的。**
2. **`nativeLibraryDir` 里文件的 x 位**：`PackageManager` 解包 native lib 的模式在不同
   版本上可能是 `0644` 或 `0755`。若为 `0644`，`execve` 会 `EACCES`。App 头部会打印
   `kernel.canExecute`；装好后一条命令即可确认：
   `adb shell "run-as dev.orchestella.umlrunner ls -l $(adb shell pm path dev.orchestella.umlrunner | sed 's|package:||;s|base.apk|lib/arm64|')"`
3. **`untrusted_app_27` 域是否能 exec `apk_data_file`**：targetSdk 28 按
   `seapp_contexts` 的 `minTargetSdkVersion=28 → untrusted_app_27` 落域（27 只会落到更老的
   规则），这是本工程选 28 而非更低/更高的原因；但该域对 nativeLibraryDir 的 `execute`
   授权仍未在真机证实。
4. **guest RAM 的 `execute`（`check_tmpexec()`）**：UML 对 TMPDIR 落盘的 guest 内存文件做
   `PROT_EXEC` 映射，取决于 `app_data_file` 的 `execute` 权限；`TMPDIR` 已默认设为
   `filesDir`，但未实测。
5. **16K guest / 4K 宿主**：release 说明该内核为 16KB pages（可跑在 4K 宿主上，反之不行），
   本轮宿主实测 `getconf PAGESIZE=4096`，未实测启动。
6. **seccomp**：默认带 `seccomp=on`；Android app 自带 seccomp 过滤器与本内核的 syscall
   取消路径可能冲突，届时用 `args` 覆盖为 `seccomp=off` 或 `nosysemu` 再试。

---

## 8. 真机轮实测补注（2026-10-29，设备 arm64 / Android 17）

上面 §7 列的 6 个未验证项，本轮已全部在真机落实，结论如下（详见
`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md`）：

1. **安装链路可通**：`INSTALL_FAILED_USER_RESTRICTED` 是 HyperOS 的解锁态问题，不是
   targetSdk 问题。设备解锁后 `adb install -r -t` 成功；若要免手动，可
   `uiautomator dump` 取「继续安装」按钮坐标后 `input tap`。
2. **`nativeLibraryDir` 带 x 位**：`useLegacyPackaging=true` + `extractNativeLibs=true` 下
   解包出 `-rwxr-xr-x u:object_r:apk_data_file:s0`，可直接 exec。
3. **`untrusted_app_27` 可 exec `apk_data_file`**：内核实际启动成功，执行路径 AVC 零拒绝。
4. **`check_tmpexec()` 通过**：启动日志 `Checking PROT_EXEC mmap in ... OK`。
5. **16K guest / 4K 宿主 = pass**：guest `getconf PAGESIZE=16384`，宿主 4096，启动 220–400ms。
6. **`seccomp=on` 可用**：日志 `Userspace mode: SECCOMP`，未被 app 的 seccomp 过滤器杀掉。

### 新增的两个硬约束（务必遵守）

- **hostfs 下脚本 shebang 必须是 `#!/bin/busybox sh`**。`#!/bin/sh` 会在 exec 时 ENOENT
  （`/bin/sh` 是指向 `/bin/busybox` 的绝对符号链接，binfmt_script 解析失败），表现为
  `Kernel panic - not syncing: Requested init /um-init failed (error -2)`。
  已提供修补版 `guest/um-init.hostfs`。
- **推送到 guest 的脚本必须是 LF**。CRLF 会让 guest ash 报 `line N: \r: not found`。
  `push_file.sh` 之前先 `tr -d '\r'`。

### 本次新增文件

| 文件 | 作用 |
|---|---|
| `tools/push_file.sh` | 规避本机 adb 的 stdin 失效与 `adb shell` ~1KB 命令长度上限（gzip+base64 分块） |
| `tools/run_bench.sh` | D10 交错基准驱动（native → UML 相邻、7 轮、中位数） |
| `guest/bench.sh` | 双侧同构负载（compute/syscall/loopbase/forkexec），支持 `BB_LOADER` 让宿主态跑同一 busybox |
| `guest/um-command.sh` | guest 自证 + 基准入口（含 `BENCH boot_uptime_ms`） |
| `guest/um-init.hostfs` | `um-init` 的 hostfs 兼容版（shebang 修补） |
| `guest/probe-init.sh` | hostfs 符号链接/文件可见性诊断 init |

---

## 9. 长驻抗冻结（HyperOS freezer）与设备窗口操作指引

**背景（设备级实锤）**：2026-11-07 设备轮 T5 门2 attempt1（`docs/research/
2026-11-07-uml-fabric-device-round.zh-CN.md` §7.1.6）——屏幕 Dozing 后数分钟内，
runner App 进程与其 UML 子进程同入 `0::/apps/uid_10051/pid_6642` 且
`cgroup.freeze=1`（cpuset 落 `background`），guest uptime 冻结、console 停 tick、
**冻结 33 分钟零内存漂移**。结论：**App 域长驻（W-064 门2 形态）在 HyperOS 上
必须持前台服务 + wake lock，否则进程整组被冻结**——06 票 §4.4 preflight 的
「后台约束：须持前台服务」由设计建议升级为设备级硬证据。

**本轮已内置的保活（产品化前提，不是可选优化）**：

| 机制 | 实现 | 生命周期 |
|---|---|---|
| 前台服务 | `KeepAliveService`（`foregroundServiceType="specialUse"`，`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 已声明用途；targetSdk 34+ 要求显式 type） | UML 启动时 `startForegroundService`，退出 `stopService`（`run()` 的 finally） |
| 最低配通知 | `IMPORTANCE_LOW` channel，`setOngoing(true)`，无声音无横幅（targetSdk 33+ 需 `POST_NOTIFICATIONS` 运行时权限，onCreate 已申请） | 同服务 |
| PARTIAL_WAKE_LOCK | `UmlRunner:uml-kernel`，`acquire(timeout=2h)` 防泄漏上限 | UML 存活期持有，`stopKeepAlive()` 在 finally 提前释放。**注意：超 2h 的驻留窗口 wake lock 会被系统超时回收（无续期），抗冻结能力静默失效——超长驻留须在指引脚本里分段重启会话或记录该时点** |

**设备窗口操作指引（设备轮已验证的缓解组合，与前台服务叠加使用）**：

```bash
# 1) 屏幕常亮（USB 供电下不影响 RSS 判定，报告 §7.3）
adb shell svc power stayon usb

# 2) 电池优化白名单（避开 deviceidle Dozing 的调度压制）
adb shell dumpsys deviceidle whitelist +dev.orchestella.umlrunner

# 3) 验证冻结状态（冻结时 cgroup.freeze=1、uptime 停走）
adb shell "cat /sys/fs/cgroup/uid_$(adb shell dumpsys package dev.orchestella.umlrunner | grep -m1 userId= | grep -o '[0-9]*')/cgroup.freeze" 2>/dev/null || \
adb shell "ps -A | grep uml"
```

> 前台服务解决「cgroup freezer 整组冻结」，`stayon` + 白名单解决「Dozing 调度
> 压制」；attempt2（仅手动缓解组合）已跑通 60min 驻留门，本轮把手动组合升级为
> App 内生机制，下一设备窗口验证前台服务形态下的等价驻留表现。

---

## 10. 修缮包改动记录（2026-10-31，fabric 批次 3 / research 修缮包）

### 10.1 stop-file watcher 修复（T5 门1 cycle1 根因）

- **根因**：`watchStop` 循环条件 `while (p.isAlive())` 先退出，进程死后遗留的
  `files/uml-stop` 不被消费/清理；下一实例启动 549ms 就把它当停止信号消费，
  产生非正常退出（exit_code=1, reason=stop-file）。T5 门1 唯一非零循环即此
  （报告 §5.1，根因归后 20/20）。
- **修法（两方案都做，互补关系）**：
  1. **启动期清理（权威修法）**：`run()` 开头 `stopFile.delete()`——App 被杀时
     watcher 线程随之消亡，死后兜底够不到，只有下一实例能清陈旧文件；
  2. **进程死后兜底**：`waitFor` 返回后 `stopFile.delete()`——关闭「本轮生成、
     watcher 消费窗口已关」的竞态遗留；正常 stop-file 路径下该 delete 返回
     false，无副作用。
  两处均有代码注释说明理由；不变式 = **任何一轮结束时 filesDir 不留 uml-stop**。
- **回归验证（本机无 JVM，脚本级模拟）**：`tools/stop_watcher_sim.sh` 把 watcher
  状态机抽成 shell 镜像（startup sweep / watch 循环 / post-exit sweep），4 场景
  断言（S1 消费路径不回归、S2 死后竞态——FIX=0 复现遗留 + FIX=1 证明修复、
  S3 陈旧文件不误杀新实例、S4 自然退出无残留）。本机实跑 **6/6 PASS**。
  设备窗口的端到端回归（T5 门1 20 循环原样重跑，期待 20/20 无归因）留待下窗：
  `./prepare.sh all` + 门1 循环脚本，判定串 `exit_code=0 && reason=exited`。

### 10.2 ncpus= 旋钮修正

- SMP 内核多核旋钮是 `ncpus=N`（默认 **host-1**），`--cpus=N` 不被识别
  （unknown param 透传 userspace）——报告 §7.1.2。
- 落点：`Config.defaultArgs`（`ncpus` 配置字段 ≥2 时追加，UP 内核不加、避免
  argv 污染）、`prepare.sh config`（`NCPUS=4 ./prepare.sh config`）、本 README
  §5 表格与示例。
- **清零复核**：`grep -rn '\-\-cpus' research/uml-runner/` = 0 处（含 guest/ 与
  tools/，本轮前后各查一次）。

### 10.3 长驻保活接线（详见第 9 节）

- `AndroidManifest.xml`：`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` /
  `WAKE_LOCK` / `POST_NOTIFICATIONS` 四权限 + `<service android:foregroundServiceType="specialUse">`；
- 新增 `KeepAliveService.java`；`RunnerActivity` 启动 UML 时 `startForegroundService`
  + wake lock `acquire(2h 上限)`，退出 finally 里 `stopKeepAlive()`；
- 兼容性：minSdk 28 上 `foregroundServiceType`/specialUse 常量被旧平台忽略，
  `Build.VERSION.SDK_INT >= 34` 才走三参 `startForeground`；sdk28 flavor 行为
  不受影响（API 28 无 FGS type 强制）。

### 10.4 构建判据记录（2026-10-31 实测）

- **理想判据达成**：本机原无 Java/Android SDK，按协议装 openjdk-21-headless +
  cmdline-tools + platform-36 + build-tools 36.0.0 后，`sh gradlew
  assembleSdk36Debug assembleSdk28Debug` **双 flavor BUILD SUCCESSFUL**（68 tasks）。
  产物：`app/build/outputs/apk/sdk{36,28}/debug/app-sdk{36,28}-debug.apk`；
  aapt2 复核 sdk36 targetSdk=36 / sdk28 targetSdk=28，包内 manifest 含 4 权限 +
  `<service>` `foregroundServiceType=0x40000000`(specialUse) + PROPERTY_SPECIAL_USE_FGS_SUBTYPE。
- 附加验证：`javac`（classpath=android-36）对两个 .java 真实编译通过（JAVAC_OK）。
- 环境注记：Maven Central 对本机出口 IP 429 限流，构建经命令行 init script 切
  aliyun 镜像完成（未改动仓库 settings/build 文件）；`gradlew` 无执行位，用
  `sh gradlew` 调用。

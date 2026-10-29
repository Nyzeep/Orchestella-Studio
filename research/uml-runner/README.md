# uml-runner — 在 App 自己的 `nativeLibraryDir` 里跑 UML 内核（arm64）

研究用独立 Gradle 工程。目标：让一个 **targetSdk 28** 的普通 Android App 用
`ProcessBuilder` 直接 `exec` 自己的 `nativeLibraryDir/libuml_kernel.so`（UML 内核），
`stub_exe=` 指向同目录的 `libuml_stub.so`，rootfs 走 **hostfs 目录**通道，
输出落到 `filesDir`，便于 `adb` 取证。

- 包名：`dev.orchestella.umlrunner`
- compileSdk 36 / **minSdk 28 / targetSdk 28** / 仅 `arm64-v8a`
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
| `rootfs` | string | `filesDir/rootfs` | 展开成 `rootflags=<dir>` |
| `kernel` | string | `nativeLibraryDir/libuml_kernel.so` | 可换内核（如 smp 版） |
| `stubExe` | string | `nativeLibraryDir/libuml_stub.so` | 展开成 `stub_exe=<path>` |
| `tmpDir` | string | `filesDir` | 子进程 `TMPDIR`（UML guest RAM 后备文件落这里，需可 exec） |
| `stdin` | `"pipe"` \| `"null"` | `"pipe"` | `pipe` 保活 fd0 可 poll；`null` 接 `/dev/null` |
| `args` | string[] | 见下 | **整体覆盖**默认参数；支持 `${stubExe}` `${rootfs}` `${nativeLibDir}` 占位 |

默认参数数组（顺序即 argv）：

```
mem=512M  rw  init=/um-init  con=null  con0=fd:0,fd:1
stub_exe=<nativeLibraryDir>/libuml_stub.so
rootfstype=hostfs  rootflags=<filesDir/rootfs>
seccomp=on  panic=1
```

示例（换 1G 内存 + SMP 内核 + 自定义 rootfs）：

```json
{
  "memMb": 1024,
  "kernel": "/data/user/0/dev.orchestella.umlrunner/files/linux-smp",
  "rootfs": "/data/user/0/dev.orchestella.umlrunner/files/rootfs",
  "args": [
    "mem=1024M", "rw", "init=/um-init", "con=null", "con0=fd:0,fd:1",
    "stub_exe=${stubExe}", "rootfstype=hostfs", "rootflags=${rootfs}",
    "seccomp=on", "panic=1"
  ]
}
```

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

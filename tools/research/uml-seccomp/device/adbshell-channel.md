# U3 adb-shell 通道：vector-net / mconsole 两 case 的可行性、产物与 SOP

状态：设计定稿（本轮交付，主会话持设备执行实测）。适用 U3 12-case 矩阵中
runner 通道结构性不可跑的两个 case：

| case | marker | runner 通道阻塞原因 | adb-shell 通道等价形态 |
| --- | --- | --- | --- |
| vector-net | `CAP vector=ok` | vec0 需要 host 侧 socketpair fd 传递（`transport=fd,fd=3`） | 静态 C 包装器 `run_with_fd` 在 shell 域 dup2 到 fd 3 |
| mconsole | `CAP mconsole=ok` | probe 需要在运行窗口内从 host 侧连 `.uml/<umid>/mconsole` socket | 静态 C 客户端 `mconsole_probe` 与内核同跑在 shell 域 |

其余 10 case 不在本通道（`--channel` 缺省即 runner 通道，行为零变化）。

---

## 1. A-1 可行性前置判断：shell 域 exec /data/local/tmp 静态 ELF

**结论：AOSP 15（API 35）平台 policy 明确放行，GO。** 以下均为一手来源
（tag `android-15.0.0_r1`，platform/system/sepolicy）：

1. 路径→类型绑定，`private/file_contexts`：
   ```
   /data/local/tmp(/.*)?  u:object_r:shell_data_file:s0
   ```
2. shell 域对该类型有**执行**权限，`private/shell.te`（注释原文 "Access /data/local/tmp."）：
   ```
   allow shell shell_data_file:dir create_dir_perms;
   allow shell shell_data_file:file create_file_perms;
   allow shell shell_data_file:file rx_file_perms;
   allow shell shell_data_file:lnk_file create_file_perms;
   ```
3. 宏展开（`public/global_macros`，同 tag 原文）：
   ```
   define(`x_file_perms', `{ getattr execute execute_no_trans map }')
   define(`r_file_perms', `{ getattr open read ioctl lock map watch watch_reads }')
   define(`rx_file_perms', `{ r_file_perms x_file_perms }')
   ```
   即 `rx_file_perms` ⊇ `execute` + `execute_no_trans` —— shell 域 execve
   /data/local/tmp 下的 ELF **不发生域转移、直接放行**。加载链也闭合：
   推上去的 UML 内核是 bionic 动态 ELF（runner jniLibs 同源），其解释器
   `/system/bin/linker(64)?` 与 `/system/lib64` 均为 system_file 系 label，
   shell.te 对 system_file 有 `x_file_perms`（同文件原文）。
4. 对照（为什么 runner 通道做不到）：app 域（untrusted_app）对
   `shell_data_file:file` 无任何 allow 规则（SELinux 默认拒绝），只能 exec
   自己 `nativeLibraryDir` 的 apk_data_file——这正是 runner 通道的结构。

**待设备实测确认的点（探针即为此设）**：OEM vendor sepolicy 收紧的可能；
`shell` 域对 pathname unix socket 的 `sock_file` 权限（见 §3.2）。

## 2. A-1 五分钟探针（主会话设备操作）

前置：WSL 一次性装好交叉工具链并产出静态二进制（§4），产物在
`device/build/binaries/`（git-ignored）。然后一条命令：

```bash
DEVICE_SERIAL=<serial> tools/research/uml-seccomp/device/run_device_matrix.sh --probe
```

harness 自动 push `probe_shell` 到 `/data/local/tmp/uml-adbshell/bin/` 并执行，
输出 `PROBE_*` 行（`probe_shell.c` 源码即判读表）。也可以手工复现：

```bash
adb push <repo>/tools/research/uml-seccomp/device/build/binaries/probe_shell \
  /data/local/tmp/uml-adbshell/bin/probe_shell
adb shell chmod 0755 /data/local/tmp/uml-adbshell/bin/probe_shell
adb shell /data/local/tmp/uml-adbshell/bin/probe_shell
```

**预期输出与判定**（正常设备应全部命中）：

```
PROBE uid=2000 gid=2000 home=...
PROBE exec_ok=1                    ← 静态 ELF exec 成功 = A-1 GO（本行缺失/Permission denied = NO-GO）
PROBE_SOCKPAIR ok=1 errno=0        ← vector-net 的 fd 通道可用
PROBE_BIND dir=/data/local/tmp ok=1 errno=0      ← mconsole socket 可落 WORKDIR（常见于 OEM 放宽）
PROBE_BIND dir=/data/local/tmp/.uml/u3-probe ok=...
PROBE_BIND dir=/data/local/tests/unittest/uml-probe ok=...
PROBE_SUMMARY exec=1 sockpair=1 bind_ok=<n>
```

判定表：

| 观察 | 含义 | 动作 |
| --- | --- | --- |
| `exec_ok=1` 且 `sockpair=1` | 通道可行（A-1 结论成立） | 继续 §5 SOP |
| `PROBE_BIND` 至少一行 `ok=1` | mconsole socket 落点确定 | 把该目录（带尾斜杠）传 `UML_MCONSOLE_DIR`，缺省即用 `WORKDIR/.uml/` |
| 所有 `PROBE_BIND ok=0 errno=13` | AOSP 基线 policy 的预期形态（§3.2） | mconsole case 本设备不可跑 → 记 FAIL(marker-missing)+探针证据；或试 §3.3 run-as 回退 |
| `exec_ok=1` 缺失 | vendor 收紧或 non-standard shell 域 | 整个 adb-shell 通道 NO-GO，取 avc 证据（userdebug: `dmesg | grep avc`） |

佐证命令：`adb shell ls -Z /data/local/tmp/uml-adbshell/bin`（应为
`u:object_r:shell_data_file:s0`）。

## 3. A-3 两 case 的启动形态（含一手勘误）

### 3.1 vector-net：fd 传递的 shell 侧等价

`vec0:transport=fd,fd=3` 要求 fd 3 在内核 exec 前就是有效的 socketpair 端。
runner 通道用的是 `run_with_fd.py`（host python3）；shell 域无 python3，本轮
交付字节级等价的静态 C 版 `device/run_with_fd.c`（socketpair → dup2 到 fd 3
→ 对端不关不 CLOEXEC → execvp），已在本机过三件套功能测试（fd 3 与对端
exec 后均为 socket）。启动链（与 `run_uml_seccomp.sh` vector-net 分支一致）：

```
run_with_fd --fd 3 -- seccomp_launch --filter filter.txt --mode enforce \
    --log stderr.log -- uml_kernel mem=512M rw init=/init con0=null,fd:1 \
    stub_exe=.../uml_stub rootfstype=hostfs rootflags=.../guest-rootfs panic=1 \
    vec0:transport=fd,fd=3,mac=<synthetic-mac>
```

mac 由 harness 生成合成值（`UML_TEST_MAC` 可覆盖，缺省 printf 形态见
`run_uml_seccomp.sh` vector-net 分支）；socketpair 的
AF_UNIX DGRAM 属 self unix socket（shell 域自持），无文件系统 inode，不经
`sock_file` 权限，探针 `PROBE_SOCKPAIR` 验证的就是它。

### 3.2 mconsole：socket 路径的一手勘误（重要）

**`mconsole=<path>` 不能指定 socket 路径。** v5.10 `arch/um/drivers/mconsole_kern.c`
一手核读：`mconsole_setup` 只认 `mconsole=notify:<socket>`（通知用），真正的
socket 路径恒为 `umid_file_name("mconsole", ...)`，即：

```
<uml_dir>/<umid>/mconsole      # uml_dir 缺省 $HOME/.uml/，可用 uml_dir=<abs> 覆盖
```

`arch/um/os-Linux/umid.c`（同源核读）：`uml_dir=` 接受任意绝对路径并补尾
斜杠；`HOME` 仅在 `~` 前缀时参与展开。因此 adb-shell 通道用
**`umid=<umid> uml_dir=<绝对路径>/`** 完全控制 socket 落点，不依赖 HOME。
runner 通道的旁证形态一致：runner console（仓库外留存）应含
"mconsole (version 2) initialized on …/.uml/<uuid>/mconsole"——runner 把
HOME 指到应用 filesDir，故 socket 在 app 数据目录；adb-shell 通道同理由
`uml_dir` 指到 shell 可写目录。

**约束**：内核进程要在 `<uml_dir>/<umid>/` 里 `bind()` 一个 pathname unix
socket，需要 `sock_file:create`。Android 15 shell.te 对 shell_data_file
（/data/local/tmp 全树）**只给了 dir/file/lnk_file，没给 sock_file**；同一
文件里唯一有 sock_file 的是 shell_test_data_file：

```
allow shell shell_test_data_file:sock_file create_file_perms;   # /data/local/tests(/.*)?
```

这是 AOSP 基线，OEM 可能放宽（所以探针先跑）。harness 行为：
`UML_MCONSOLE_DIR`（尾斜杠绝对路径）覆盖落点，缺省 `WORKDIR/.uml/`；若探针
全 fail，mconsole case 在该设备按 FAIL(marker-missing) 落判并附探针证据
（诚实判定，不假 PASS）。

### 3.3 mconsole 回退形态（可选，不进 harness）

若 shell 域全域无 sock_file 权限而 runner app 已在场（其余 10 case 需要
它），可经 `run-as` 把内核也放进 app 域跑（app_data_file 有 sock_file）。
代价是 ambient 域从 shell 变 runas、与两 case 的"shell 域"口径不再纯净——
只作下窗备选，证据口径需在报告里注明。

### 3.4 mconsole 启动链

```
seccomp_launch --filter filter.txt --mode enforce --log stderr.log -- \
    uml_kernel mem=512M rw init=/init con0=null,fd:1 stub_exe=... \
    rootfstype=hostfs rootflags=.../guest-rootfs panic=1 \
    u3_wait_ms=4000 umid=<umid> uml_dir=<UML_MCONSOLE_DIR> &
mconsole_probe --socket <UML_MCONSOLE_DIR><umid>/mconsole --timeout 8
# MCONSOLE_OK → echo "CAP mconsole=ok" >> console.log（与 run_uml_seccomp.sh 同一判定形态）
```

`u3_wait_ms=4000` 是 guest init（`guest/init.c`）内置的窗口参数：init 睡
4 秒再 poweroff，给 probe 留握手窗。probe 的客户端 socket bind 在
`<socket目录>/mconsole-client-<pid>.sock`（同目录，权限面一致；bind 失败也
可退化为 abstract 地址，UML 会按源地址回包）。

## 4. A-2 静态产物：WSL 交叉编译

来源盘点：`seccomp_launch.c` 独立源码已存在（uml-seccomp 根目录，
`run_uml_seccomp.sh` 运行期用 host cc 现编）——无需 init.c 替代方案；缺的
只是 shell 域可用的预编译形态与两个 C 版助手。

```bash
# WSL（一次性）：sudo apt install gcc-aarch64-linux-gnu
wsl bash tools/research/uml-seccomp/device/build_adbshell_binaries.sh
```

产物（`device/build/` 全局 git-ignored）：

| 产物 | 源 | 作用 |
| --- | --- | --- |
| `build/binaries/seccomp_launch` | `seccomp_launch.c`（不改） | AOSP 过滤器安装 + exec 内核（enforce） |
| `build/binaries/run_with_fd` | `device/run_with_fd.c`（新） | vec0 fd 3 传递（§3.1） |
| `build/binaries/mconsole_probe` | `device/mconsole_probe.c`（新） | mconsole 握手（§3.4） |
| `build/binaries/probe_shell` | `device/probe_shell.c`（新） | §2 探针 |

**glibc 静态 + 0 GNU_PROPERTY 是硬要求**，脚本内嵌 readelf 校验：编译后
`readelf -n` 必须无 `GNU_PROPERTY`（命中则先以 `-mbranch-protection=none`
重编一次，仍命中即失败）。依据上一轮实测根因：NDK/bionic 静态 ELF 带
BTI/PAC notes 被 UML 7.2-rc4 arm64 binfmt 拒载（error -22），glibc 静态
0 GNU_PROPERTY 是本项目唯一实证可用的静态形态（guest/init 修复链
-13 → -22 → PASS）；seccomp_launch 位于同一 exec 链上游，沿用同一口径，
且静态链接免去 /data/local/tmp 下的解释器解析风险。本地验证：四产物
AArch64/静态/0 GNU_PROPERTY，mconsole 线格式往返、fd 传递、探针输出三件
功能测试均过。

filter.txt 不在设备上生成：shell 域无 python3，由 harness 在宿主侧跑
`gen_profile.py --emit-filter`（同一 `aosp-app-android15-arm64.json`）后 push。

## 5. 主会话设备 SOP（adb-shell 通道，两 case）

前置：runner 通道的一次性准备已完成（runner APK 已装、`svc power stayon
usb`、U3 rootfs 的 `rootfs.tar.gz` 由 `guest/build_guest.sh` 在 POSIX 宿主
产出——exec 位真实）。

```bash
# 1) 产物（WSL，一次性）
wsl bash tools/research/uml-seccomp/device/build_adbshell_binaries.sh

# 2) 探针（5 分钟，判定表见 §2；决定 UML_MCONSOLE_DIR 是否需要覆盖）
DEVICE_SERIAL=<serial> tools/research/uml-seccomp/device/run_device_matrix.sh --probe

# 3) 跑两 case（自动 staging：binaries+kernel+stub push、filter.txt 生成、
#    rootfs 解包、/data/local/tmp 布局、adb shell 执行、pull 取证、判定闭环）
DEVICE_SERIAL=<serial> U3_ROOTFS_TGZ=/tmp/uml-seccomp-guest/rootfs.tar.gz \
  tools/research/uml-seccomp/device/run_device_matrix.sh --run --channel adb-shell
#   单 case：追加 --case vector-net 或 --case mconsole
#   socket 落点覆盖：UML_MCONSOLE_DIR=/data/local/tests/unittest/uml-adbshell/.uml/ （尾斜杠必须）

# 4) 判定/聚合与 runner 通道共用（产物同在 device/build/out/<ts>/<case>/）
python tools/research/uml-seccomp/matrix.py \
  --cases tools/research/uml-seccomp/fixtures/matrix/cases-device-b.json \
  --run-dir tools/research/uml-seccomp/device/build/out/<ts> \
  --mode aosp \
  --profile tools/research/uml-seccomp/profile/generated/aosp-app-android15-arm64.json \
  --format markdown
```

设备侧布局（`WORKDIR=/data/local/tmp/uml-adbshell`，全树 shell_data_file）：

```
WORKDIR/bin/{seccomp_launch,run_with_fd,mconsole_probe,probe_shell,uml_stub}
WORKDIR/uml_kernel              # cat 自 runner nativeLibraryDir 的 libuml_kernel_noinitrd.so（与 10 case 同源，sha 双读校验）
WORKDIR/filter.txt              # 宿主 gen_profile.py 产出（aosp/android15/arm64）
WORKDIR/guest-rootfs/init       # U3 rootfs（POSIX 打包 tgz 解开，0755）
WORKDIR/tmp/                    # TMPDIR（guest RAM 后备文件）
WORKDIR/.uml/<umid>/mconsole    # mconsole socket（UML_MCONSOLE_DIR 可覆盖）
WORKDIR/runs/<ts>/<case>/{device-case.sh,console.log,stderr.log,mconsole-probe.log,exit.txt}
```

与 runner 通道的判定闭环差异（其余一致）：console.log 由设备侧脚本直写后
pull（头部自带 case/args/kernel sha 元信息行）；exit.txt 由脚本产出，字段
`exit_code/signal_guess/timed_out/reason/duration_ms` 与 uml-exit.txt 同形，
`judge_run`（SIGSYS/ENOSYS/SECCOMP-fatal/标记）原样复用；harness.meta 落
`CHANNEL=adb-shell`。三分法判读不变：SIGSYS=filter TRAP /
ENOSYS=内核缺 / "SECCOMP…not functional"=probe fatal。

内核参数口径（与 runner 通道 base args 的差异，均有依据）：`mem=512M`
（设备实证值）+ `panic=1`（设备实证）来自 runner 通道；`con0=null,fd:1`、
`init=/init`、hostfs 形态、`stub_exe`（从 nativeLibraryDir 拷出传绝对路径）
与 runner/`run_uml_seccomp.sh` 两方一致；**不带 `seccomp=on`**——该旋钮是
UML 快路径（仅 seccomp-fastpath case 用），两 case 的过滤面由
seccomp_launch 在宿主侧安装（与 `run_uml_seccomp.sh` aosp 模式一致）。

## 6. 未决问题（下窗设备实测确认）

1. `PROBE_BIND` 在 device-b 的实际落点（OEM sepolicy 是否放宽
   shell_data_file:sock_file）——决定 mconsole 是否需要 §3.2 的备选落点。
2. `PROBE exec_ok` 在 device-b 的实测（A-1 一手 policy 判断为 GO，探针为
   实证闭环）。
3. bionic 动态 UML 内核在 shell 域 exec 的端到端（policy 面已闭合，探针+
   首跑验证；内核 sha 与 runner 通道同源，排除资产变量）。
4. `stub_exe` 对 noinitrd 内核在 ptrace 回退路径下是否必需（照传不亏，
   实测后可在报告里定口径）。

# 原生车道测量套件（native-lane）

**这个目录里没有任何性能数字。** 设备在维护者手上，本轮一次都没连；套件的作用是等设备回来时
用一条命令产出四列数据，而不是现在给出结论。

---

## 1. 四条车道是什么

同一份预处理单元（`fixtures/fixture.i`）在四处的编译方式：

| 列 | 怎么跑 | 本轮是否测量 |
|---|---|---|
| `qemu` | distcc 客户端在 QEMU guest 内本地编译（`local_compile_ms`） | 否，复用已有 summary |
| `pvm` | distcc 把编译卸载到 protected pVM payload（`distcc_ms`） | 否，复用已有 summary |
| `proot` | `proot-static -r <rootfs> ... clang-22 -c` | 是 |
| `bare` | 不经 proot：`ld-musl-aarch64.so.1 --library-path ... clang-22 -c` | 是 |

### 为什么必须测 `bare`

只有 `proot` 一列时，"慢" 可能是 ptrace 拦截路径类 syscall 的账，也可能是 CPU 模拟的账，两者混在
一起无法区分。减去 `bare` 之后：

- `proot − bare` = ptrace 的代价（公开基准：编译类 10–20%）；
- `qemu − bare` = 模拟的代价。

`bare` 不是"另一种虚拟化"。它依然是不模拟 CPU、直接执行 arm64 指令，只是把 FHS 根目录换成
`--library-path` 与 musl loader 显式指定。

### `bare` 为什么带 `-fintegrated-cc1`

musl 动态二进制的 `PT_INTERP` 是 `/lib/ld-musl-aarch64.so.1`，Android 上没有这个路径，内核解析
interpreter 会失败。`proot` 能兜住子进程，`bare` 兜不住 —— 除非 driver 不去 exec 自己。clang 的
`-fintegrated-cc1`（"Run cc1 in-process"）正是为此存在，`tools/native-lane` 用它把
"clang 重新执行自身" 这一步消掉。代价是它只对大驱动的常规编译路径成立；若某天设备上返回
`-fintegrated-cc1` 未知，`bare` 列会以 `MISSING_OBJECTS` 失败而不是给出错误数字。

---

## 2. 三条命令（设备回来后）

```bash
cd tools/native-lane

# 1) 宿主下载 + 校验（不入库的第三方件），并打印设备侧完整序列
bash prepare_rootfs.sh fetch
bash prepare_rootfs.sh plan          # 照抄执行；里面有 push / 解包 / apk add / 验版本

# 2) 第一次真机运行：只做体检，不出数
python measure.py --device --adb adb --preflight-only

# 3) 出四列表
python measure.py --device --adb adb --regen-dot-i
```

`--regen-dot-i` 会在设备上用**设备上的 clang** 重新 `-E` 一遍夹具，再拿这一份去跑四列。这样
"四条车道同一个 sha256" 是同一次运行内可验证的事实，而不是一句承诺；宿主那份 `fixtures/fixture.i`
只作为参考基线。

### 宿主机上能做什么

```bash
python gen_fixture.py --verify                 # 夹具可复现（比 sha256）
python measure.py --dry-run                    # 全流程 + fake adb，产物在 results/<ts>/
python -m unittest discover -s tools/native-lane -p 'test_*.py'
```

`--dry-run` 的产物带 `"synthetic": true` 和表头横幅，数字来自 `fake_adb.py` 的手写表，
**不是测量结果**。

---

## 3. rootfs 与第三方件

全部不入库（`bin/ cache/ rootfs/ results/` 均已 gitignore），只入库下载与校验脚本，以及
`tools.lock.json` 里的 sha256 钉子。

| 件 | 版本 | 说明 |
|---|---|---|
| `proot-static` | Alpine v3.24 community，5.4.0-r2 | aarch64 **static-pie**，解出 402 600 B，`file` 显示 `ARM aarch64 ... statically linked` |
| `alpine-minirootfs` | v3.24.3 aarch64 | 4 029 999 B |
| clang（`match22` 档） | edge/main `clang22` **22.1.8-r1** | 与 payload 的 clang 22.1.8 **同主次版本** |
| clang（`stable` 档） | v3.24 community `clang18` | 与 v3.24 仓库一致，落后 payload 四个大版本 |

两条**实测出来、和先验假设不一样**的事实：

1. **Alpine v3.24 stable 没有 clang 22。** v3.24 的 community 只到 `clang18`，`main` 里根本没有
   clang；`clang22` 只在 **edge/main**。所以版本对齐的代价是 "stable 底座 + edge/main 的 clang22"，
   存在 musl/llvm 漂移风险 —— 这正是 `stable` 档存在的原因。
2. **Alpine 不再提供无版本号的 `clang` 包。** `clang22` 只给 `cmd:clang-22`，装完**没有**
   `/usr/bin/clang` 这个符号链接。套件因此显式调用 `/usr/bin/clang-22`（`--clang` 可改），
   不假设 PATH 上有 `clang`。

### 为什么 rootfs 不在 Windows 上解包

minirootfs 里全是符号链接与设备节点。Git Bash 在普通权限下建不了符号链接
（`tar: Cannot create symlink ... Operation not permitted`），`mknod` 更不可能。所以
`prepare_rootfs.sh` 在 Windows 上只做 `fetch`，解包与 `apk add` 都放到设备上做——反正是 aarch64
本机执行，比交叉安装更可信。

解包时 `--exclude='./dev/*'`，运行时用 `proot -b /dev` 把设备的 `/dev` 挂进去，于是
**不需要 `mknod`、不需要 root**，也绕开了 "Alpine 缺 `/dev/null` 会让 clang driver 经
`posix_spawn` 直接失败" 这个老坑。

---

## 4. 结果怎么读

`results/<UTC 时间戳>/native-lane.json` 与 `native-lane.md`。JSON 关键字段：

```jsonc
{
  "synthetic": false,                 // true 表示 dry-run，数字是假的
  "run": {
    "fixture": {"sha256": "...", "bytes": 90114, "regenerated_on_device": true},
    "parameters": {"jobs": [1, 7], "files_per_batch": 7, "clock": "proc_uptime_cs"}
  },
  "env": {                            // 缺任何一项 → 该次样本不许进表
    "nproc": 8, "cpuinfo_max_freq_khz": 3148800, "scaling_governor": "schedutil",
    "thermal_start": {"thermal_zone0:cpu": 38000}, "thermal_end": {"...": 0},
    "battery_temp_dc": 312, "screen": "Awake", "incomplete": []
  },
  "rows": [{"column": "proot", "jobs": 1, "status": "OK", "wall_ms": 3120,
            "files": 7, "files_per_min": 134.62, "objects_ok": 7,
            "command": "..."}],
  "reused": {"qemu": {"status": "NOT_MEASURED", "value_ms": null},
             "pvm":  {"status": "NOT_MEASURED", "value_ms": null}}
}
```

`-j` 不是传给单个编译的参数。每个档位复制 N 份夹具、用 `xargs -P` 并发，报的是**整批墙钟**，
再由 `files / (wall_ms/60000)` 换算吞吐。单文件假装并发是不允许的。

退出码：

| 码 | 含义 |
|---|---|
| 0 | 正常出表 |
| 2 | 前置失败（rootfs 未准备、夹具缺失、proot 没跑起来） |
| 3 | 证词不全（`env.incomplete` 非空）；除非加 `--allow-incomplete-provenance`，否则该次不许引用 |
| 4 | 有列失败（`MISSING_OBJECTS` / `TIMEOUT` / `NO_TIMING`） |

`reused` 永远显式写出 `qemu`/`pvm` 的来源：要么 `--import-crossfire <summary.json>`，
要么 `--qemu-ms/--pvm-ms`，否则就是 `NOT_MEASURED` + 原因。不会静默补零。

时钟是设备 `/proc/uptime`（厘秒精度）：toybox 的 `date` 没有 `%N`，用 `/proc/uptime` 少一个进程、
少一次 fork，代价是 3 秒级别批次的量化误差约 0.3%。批量越大越可忽略。

---

## 5. 已知坑（按踩到概率排序）

1. **`proot` 能不能在这台 ROM 上 `execve`** —— 设备回来后**第一个**要验的事。targetSdk 28 的
   逃生门若已关闭，原生车道当场判死。`measure.py --preflight-only` 与 `--regen-dot-i` 都会在
   正式计时前撞到它（`PROOT_EXEC_FAILED`）。
2. **`/dev/null`**：rootfs 里没有设备节点。`proot -b /dev` 解决；不要试图 `mknod`（需要 root）。
3. **`/usr/bin/clang` 不存在**：Alpine 的 `clang22` 只有 `clang-22`。见上文。
4. **中文路径**：仓库路径含中文时 NDK/ninja/gradle 的 CMake 任务会崩；本套件只跑 Python 与 adb，
   不受影响，但**不要**把 `rootfs/` 建在中文路径下再用 proot 反复 `-b`。
5. **`adb forward` 在本机不投递字节**（历史结论），所以本套件不用 forward，全部走 `adb shell` /
   `adb push` / `adb pull`。
6. **`adb start-server` 会被回收**：长批次前先起 server 保活。
7. **HyperOS 会冻结后台进程**：测量期间保持前台、屏幕常亮，否则墙钟会被冻结时间污染
   （`screen` 字段就是为此记录的）。
8. **温度漂移**：`thermal_start/thermal_end` 两次快照的差值就是漂移的度量。若 `-j7` 明显快于
   `-j1` 但两次温度差很大，优先怀疑降频而不是"并行度好"。

---

## 6. 文件

```
tools/native-lane/
  README.md                 本文件
  gen_fixture.py            夹具生成 + --verify（可复现性）
  fixtures/
    fixture.c               2000 个函数，生成物
    fixture.i               clang -E 输出（参考基线）
    MANIFEST.json           字节数与 sha256、以及产生 .i 的 argv
  tools.lock.json           第三方件 URL + sha256 + 两档 profile
  prepare_rootfs.sh         fetch / extract / populate / plan / status / all
  measure.py                四列 × 两档，--dry-run 可全流程干跑
  fake_adb.py               确定性的 adb 替身（永不联网、永不碰设备）
  test_native_lane.py       14 个零设备用例
  tests/expected_dry_run.json  干跑的手写期望值，diff 用
```

`gen_fixture.py` 的生成器与 `tools/device-validation/dv_pvm_compiler.py:build_fixture` 逐字节一致，
并由 `test_native_lane.py` 断言。这条断言是"qemu/pvm 两列和 proot/bare 两列测的是同一份输入"的
唯一机械保证——改任何一边，测试先红。

---

## 7. 这个套件不能告诉你什么

- 它给不出任何一条车道比另一条快多少。没有设备就没有数字，`--dry-run` 的数字是假的。
- 它不解释 pVM 卸载为什么在某台机器上失败；那是 AXP1 / argv 策略那条线的事。
- 它不覆盖 shell、I/O、包管理器；只测 distcc 型编译。

# terminal-bench — 终端帧性能宿主侧闸门

调试专用 `TerminalBenchActivity` 在真机上跑五组固定夹具，把每组的帧统计以 `OE_BENCH {json}` 写入 logcat。本目录是宿主侧闸门：一条命令完成「安装 APK → 跑夹具 → 收结果 → 对比基线 → 退出码」，只用 `adb` 与 Python 标准库。

当前状态：**PENDING-DEVICE**。仓库内的 `baseline.json` 是空桶占位（`status: pending-device`），没有任何真机数字；在抓到第一份基线之前，本工具只报告、不判定。离线自测已接入 GitHub Actions（`.github/workflows/terminal-bench.yml`），设备基线仍需人工抓取。

## 1. 边界

- 只跑 debug 构建里的 `app/src/debug/.../bench/TerminalBenchActivity`，不碰 release 包。
- 只读 logcat 的 `OE_BENCH` / `OE_BENCH_DONE` / `OE_BENCH_ERROR` 三个 tag；不抓全量日志。
- 不在仓库里写设备序列号、完整日志或截图；`--json-out` 默认关闭、拒绝写入仓库内，路径由调用者给。
- **提交进仓库的基线只用稳定别名**（`--model`），绝不写 `Build.MODEL` / 型号 / 序列号；`device` 字段只存在于本机 logcat。见 `AGENTS.md` 的敏感开发者数据约束与 `tools/accelerator-perf/README.md` §7.1。
- 没有设备也能验收工具本身：`--dry-run` 不碰 adb、不需要 APK。

## 2. 前置条件

| 项 | 要求 |
| --- | --- |
| 宿主 | Windows 或 Linux；Python 3.10+；`adb` 在 `PATH`，或用 `--adb` / `$ANDROID_HOME` / `$ANDROID_SDK_ROOT` 指向 |
| 构建 | `./gradlew :app:assembleDebug` 产出的 debug APK（默认 `app/build/outputs/apk/debug/app-debug.apk`） |
| 设备 | 一台已开启 USB 调试（或无线调试）的 Android 设备，`adb devices` 状态为 `device` |
| 屏幕 | 保持常亮、解锁；关闭省电/自动亮度突变；测量期间不要有通知横幅或分屏变化 |
| 环境 | 同一轮 gate 固定刷新率、字体缩放、显示大小；设备温度回到正常档再开跑 |

多台设备同时在线时必须用 `--serial`（或 `$ANDROID_SERIAL`）指定；否则直接报 `NO_DEVICE`。

**运行期间设备上是什么样子**：全屏深色终端 + 顶部一枚 `terminal-bench · <fixture>` 标签，
内容按夹具自走（`flood_*` 是密集文字、`scroll_2000` 是连续滑动）。这是夹具页本身，
不是应用界面也不是故障；每轮结束它会自己退出，前台回到调用前的页面。
把手机拿给不熟悉这套工具的人看之前，请先说明这一点——没有标签的旧版本看起来像应用坏了。

## 3. 命令

一次性跑全部夹具并判定：

```bash
python3 tools/terminal-bench/run.py --model <稳定别名> --fixture all
```

`--model` 在这里不是 marketing 型号，而是**稳定别名**（如 `bench-a`）：它会被当作 intent extra 传给 activity，结果行里的 `alias` 字段就是它，基线桶也用它。不传时脚本退回用结果行里的 `Build.MODEL` 选桶并打印 `WARN`；那样抓到的桶不要提交。

分夹具跑（排查单个路径时用）：

```bash
python3 tools/terminal-bench/run.py --fixture flood_seq
python3 tools/terminal-bench/run.py --fixture flood_color
python3 tools/terminal-bench/run.py --fixture scroll_2000
python3 tools/terminal-bench/run.py --fixture type_100
python3 tools/terminal-bench/run.py --fixture resize_font
```

常用变体：

```bash
# 指定设备 / 通道包名 / APK
python3 tools/terminal-bench/run.py --serial <serial> --package <applicationId> --apk /tmp/app-debug.apk

# 复用设备上已安装的 APK
python3 tools/terminal-bench/run.py --no-install

# 离线验收：不碰 adb、不需要 APK，只打印计划并校验基线
python3 tools/terminal-bench/run.py --dry-run --fixture all

# 解析一份已保存的 logcat（可与 --dry-run 组合）
python3 tools/terminal-bench/run.py --logcat /tmp/oe-bench.log --dry-run

# 给发布 checklist 留一份结果
python3 tools/terminal-bench/run.py --json-out /tmp/terminal-bench-result.json

# 发布闸门：必须在真实设备上跑；基线未抓取时以 BASELINE_PENDING 退出 2（不再 report-only）
python3 tools/terminal-bench/run.py --require-baseline --model <稳定别名> --fixture all

# 离线回放：synthetic 夹具 → synthetic 基线，只验证判定链，不是设备结果
python3 tools/terminal-bench/run.py --dry-run --fixture all \
  --baseline tools/terminal-bench/fixtures/baseline.ci.json \
  --logcat tools/terminal-bench/fixtures/golden.log
```

`--dry-run` 会打印解析出的 adb、serial、包名、APK 是否存在、基线状态、阈值与超时，然后退出。给了 `--logcat` 时，它还会真正跑对比；否则只校验基线并返回 0。

`--require-baseline` 供发布检查清单使用：只有 `status: "captured"` 且桶非空的基线才能继续；`pending-device`、空桶、synthetic 回放夹具或实测设备缺桶都会以退出码 2（`BASELINE_PENDING`）失败。不加该 flag 时行为不变，仍是 report-only。

## 4. 夹具

| id | 内容 | 目的 |
| --- | --- | --- |
| `flood_seq` | 每 16 ms 把 8×1024 字符的顺序文本块直接追加到终端模型 | 持续输出下的帧稳定性；**绕过 PTY**，结果偏乐观 |
| `flood_color` | 同样节奏，但每块带颜色/样式变化 | 属性变化引发的重绘开销 |
| `scroll_2000` | 建立 2000 行内容后连续滑动 20 次 | 滚动、布局复用与缓冲裁剪 |
| `type_100` | 通过真实输入连接提交 100 次 ASCII 字符与 20 次 CJK 组合输入 | 输入法/回显/光标重绘路径 |
| `resize_font` | 字号 10→20sp 往返 5 次（每次改字号都走真实的 `applyTheme` + 重新布局） | 主题应用与 relayout 成本 |

`--fixture all` 依次跑上面五个；缺任何一个都按 harness 失败处理（退出码 2，`BENCH_INCOMPLETE`），因为「全部在阈值内」的前提是五个都跑完。

注意 `scroll_2000` 的 2000 行受 `TerminalScreenModel` 的 100k 单元格上限约束（`MAX_HISTORY_CELLS`）：屏幕列数越宽，实际能留在回滚区的行越少（42 列约 2000 行，100 列约 1000 行）。结果行的 `historyRows` 是测量结束时的真实回滚行数，用它核对，而不是假定一定是 2000。

## 5. 输出字段

每行结果是一个 JSON 对象，字段如下：

| 字段 | 单位 / 含义 |
| --- | --- |
| `fixture` | 夹具 id |
| `device` | `Build.MODEL`（只在本机 logcat；不进基线） |
| `alias` | `--model` 传入的稳定别名；基线桶只用它 |
| `api` | `Build.VERSION.SDK_INT` |
| `frames` | 测量窗口内呈现的帧数（不含预热阶段） |
| `janky` | 帧间隔超过 **2×刷新间隔** 的帧数 |
| `p50` / `p95` / `p99` / `max` | 帧间隔分位数与最大值，**毫秒** |
| `fps` | `frames * 1000 / durationMs`，帧/秒；上限受 `refreshHz` 限制 |
| `javaKb` / `nativeKb` | 夹具前后 Java / native 堆的**差值**，KiB，可为负 |
| `cols` / `rows` | 终端网格列数 / 行数 |
| `historyRows` | 测量结束时的回滚行数（受 100k 单元格上限约束；`-1` 表示读取失败） |
| `refreshHz` | 屏幕刷新率，Hz |
| `durationMs` | 测量窗口长度，毫秒 |
| `drawP95` / `layoutP95` | 绘制 / 布局阶段的 p95，毫秒 |
| `dropped` | 平台报告的掉帧计数，仅诊断，不参与阈值 |

## 6. 阈值

阈值存在 `baseline.json` 顶层的 `thresholds` 里，`run.py` 运行时读取；字段缺失时回退到代码里的同名常量：

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `p95MaxRegressionRatio` | `0.15` | 实测 p95 超过基线 p95 的 15% 判回归 |
| `jankyMaxRegressionPoints` | `5.0` | janky 比例比基线高 5 个百分点判回归 |
| `fpsMaxDrop` | `3.0` | 实测 fps 比基线低 3.0 判回归 |

判定只在**基线与实测两边都是数字**时进行；基线缺某个夹具或某个指标时，该夹具只报告、不判失败。阈值改动只改 `baseline.json`，不要改脚本常量。

## 7. 基线文件

`tools/terminal-bench/baseline.json` 的结构：

```json
{
  "schema": "terminal-bench-baseline-v1",
  "status": "pending-device",
  "capturedAt": null,
  "thresholds": {
    "p95MaxRegressionRatio": 0.15,
    "jankyMaxRegressionPoints": 5.0,
    "fpsMaxDrop": 3.0
  },
  "buckets": {}
}
```

抓到一个桶之后长这样（数值仅为结构示例，**不是本仓库的测量结果**）：

```json
"<alias>/<api>": {
  "alias": "<stable alias>",
  "api": 36,
  "capturedAt": "2026-01-01T00:00:00Z",
  "fixtures": {
    "flood_seq": {
      "frames": 0,
      "janky": 0,
      "p50": 0.0,
      "p95": 0.0,
      "p99": 0.0,
      "max": 0.0,
      "fps": 0.0,
      "javaKb": 0,
      "nativeKb": 0
    }
  }
}
```

分桶规则：桶键是 `<别名>/<api>`；`--model` 原样写入（其中的 `/` 会替换成 `_`），`api` 必须是正整数。对比时只用实测结果里 `alias`/`api` 对应的那个桶，绝不跨别名或跨 API 比较。捕获时还会一并写入 `cols`/`rows`/`historyRows`/`refreshHz`/`durationMs`/`drawP95`/`layoutP95`/`dropped` 供人工诊断，这些字段不参与回归判定。

### 7.1 离线回放夹具（不是基线）

`fixtures/baseline.ci.json` 与 `fixtures/golden.log` 只服务于 CI 与本地自测：前者 `status: "synthetic"`、`synthetic: true`、桶为 `ci-replay/34`，后者的结果行不带 `device` 字段。它们用来证明判定链既能 PASS 也能 FAIL，**不是任何设备的测量结果**，不得作为发布依据：

- `--require-baseline` 只接受 `status: "captured"` 的基线，synthetic 文件会被 `BASELINE_PENDING` 拒绝；
- `--update-baseline` 指向带 `synthetic: true` 的文件时直接报 `BASELINE_SYNTHETIC_READONLY`，防止把真机数字写进回放夹具。

## 8. 更新基线

```bash
python3 tools/terminal-bench/run.py \
  --update-baseline --model "<稳定别名>" --api 36 --fixture all
```

- 必须同时给 `--model`（稳定别名，不是型号/序列号）和 `--api`，否则退出码 2（`MODEL_REQUIRED` / `API_REQUIRED`）。
- 必须在与 gate 相同的别名、相同的 API、相同的刷新率上抓；换设备就换桶，不要覆盖别的桶。
- 只合并本次实测到的夹具到该桶；桶里原有的其他夹具保留；**其他桶原样不动**。
- 跑完打印逐指标 diff（旧值 → 新值，p95 额外给百分比），并把顶层 `status` 置为 `captured`、写入 UTC 秒级 `capturedAt`。
- 实测 `api` 与 `--api` 不一致 → `API_MISMATCH` 退出 2；activity 回传的 `alias` 与 `--model` 不一致 → `ALIAS_MISMATCH` 退出 2。两者都是为了禁止把 A 设备的数字写进 B 桶。
- 基线是代码的一部分：`baseline.json` 的改动走正常评审，并在 PR 里贴出 diff 与结果 JSON。

注意：`--dry-run --update-baseline` 不会写文件，只打印将要写入的桶；去掉 `--dry-run` 才落盘。

## 9. 无基线时

默认仍是 report-only。以下情况打印 `NO_BASELINE bucket=<key>` 和本次实测指标，然后退出 0（不判定）：

- 基线里没有该 `<别名>/<api>` 桶；
- 桶存在但 `fixtures` 为空；
- `baseline.json` 仍是 `pending-device` 空桶。

这是刻意的：没有基线就没有回归判断的依据。发布闸门必须加 `--require-baseline`：基线不是 `status: "captured"` 或 `buckets` 为空时退出 2，stderr 打印 `ERROR=BASELINE_PENDING`；即使基线整体已捕获，实测设备对应的桶缺失、`fixtures` 为空，或桶里只覆盖部分夹具导致其余被 `SKIP`，也同样按 `BASELINE_PENDING` 处理——`--require-baseline` 要求每个预期夹具都拿到可比较的基线指标，`SKIP` 不再算通过。默认（不加 flag）仍是 report-only。

## 10. 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 全部夹具在阈值内；默认模式下无基线/缺夹具只报告（`--require-baseline` 下为 2） |
| `1` | 至少一个夹具判回归（p95、janky 比例或 fps） |
| `2` | 环境或 harness 失败：无 adb、无设备、安装失败、启动失败、超时、`OE_BENCH_ERROR`、基线/参数错误；`--require-baseline` 下基线未捕获或缺桶（`BASELINE_PENDING`）、试图写入 synthetic 夹具（`BASELINE_SYNTHETIC_READONLY`） |

退出码 2 会在 stderr 打印 ASCII token，例如 `ERROR=ADB_UNAVAILABLE`、`ERROR=NO_DEVICE`、`ERROR=INSTALL_FAILED`、`ERROR=BENCH_TIMEOUT`、`ERROR=BENCH_ERROR`、`ERROR=BENCH_NO_RESULTS`、`ERROR=BENCH_INCOMPLETE`、`ERROR=API_MISMATCH`、`ERROR=ALIAS_MISMATCH`、`ERROR=ARTIFACT_ROOT_REJECTED`、`ERROR=BASELINE_PENDING`、`ERROR=BASELINE_SYNTHETIC_READONLY`，并附上解释失败的原始 adb 输出。

## 11. 结果贴到哪里

- **发版前人工跑一次**：在有代表性的设备上执行 `run.py --require-baseline --model <稳定别名> --fixture all --json-out /tmp/terminal-bench-result.json`，把结果 JSON 贴进 release checklist 对应条目。设备基线本身走 `--update-baseline` 提交。
- 本仓库的 `docs/` 目录默认被 `.gitignore` 忽略；本 README 是 terminal-bench 的唯一入口，结果与基线变更都引用这里。
- `--json-out` 拒绝写入仓库内（结果里有 `Build.MODEL`）；只写外部 artifact 根或 `/tmp`。
- 不要提交完整 logcat、设备序列号、截图或录屏；这些只留在本地或外部 artifact 根。

## 12. 测量陷阱

1. **debug 构建 vs release**：夹具 activity 只在 debug 源集里；debug 包没有 R8 优化、断言更多，绝对数值天然更差。永远不要把 release 包的数字和本基线比。
2. **JIT / 冷启动预热**：脚本或手动跑之前先 `am force-stop <包名>`，activity 在测量窗口前也有预热，但安装后的第一次运行仍会受 dexopt、类加载、着色器初始化影响。gate 前先手动跑一轮，正式结果取预热后的那一轮。
3. **刷新率与 fps 上限**：`fps` 的理论上限是 `refreshHz`；60/90/120 Hz 下同一份代码的 fps 不可比。抓基线和跑 gate 必须同刷新率，并记录 `refreshHz`。
4. **模拟器与真机不可比**：模拟器的 GPU、vsync、调度都与真机不同；基线桶只对同一稳定别名（同一台设备）有效。
5. **flood 夹具偏乐观**：`flood_seq` / `flood_color` 绕过 PTY 直接写屏幕模型，省掉了 guest 输出与传输成本，只应作为渲染路径的下限参考。
6. **真实视图路径**：`type_100` 与 `resize_font` 走真实的输入连接、主题应用与布局路径，更接近用户操作，但仍不等于完整虚拟机负载。
7. **热与后台**：设备发热降频、后台应用、系统更新都会污染结果；每轮开始前静置到正常温度，保持相同后台状态。
8. **字体缩放 / 显示大小**：会改变 `cols`/`rows` 与布局成本；同一桶必须保持同一设置。
9. **日志开销**：采集只过滤三个 tag 并每 2 秒轮询一次，但测量期间不要同时跑全量 `adb logcat` 或其他 adb 大流量操作。
10. **本夹具只测帧**：`type_100`/`resize_font` 输出的是帧分位数，不含「输入到上屏 ≤50ms」「重排 ≤100ms」这类端到端时延；后者需要单独打点，属于后续轮次。别把帧指标当成端到端时延。

## 13. 自测

全部离线，不需要 adb 或设备：

```bash
python3 -m py_compile tools/terminal-bench/run.py

python3 -B -m unittest discover -s tools/terminal-bench -p 'test_*.py'

python3 tools/terminal-bench/run.py --dry-run --fixture all

# 判定链回放：synthetic 基线 + golden 日志必须 PASS
python3 tools/terminal-bench/run.py --dry-run --fixture all \
  --baseline tools/terminal-bench/fixtures/baseline.ci.json \
  --logcat tools/terminal-bench/fixtures/golden.log

# 发布门反向用例：对未捕获基线必须退出 2（当前仓库基线尚未捕获，可直接复现）
python3 tools/terminal-bench/run.py --dry-run --require-baseline
```

测试覆盖：brief 格式解析（含 CRLF）、`OE_BENCH_DONE` 不被当成夹具、p95/janky/fps 三条回归规则、缺桶与缺指标的 report-only 行为、`--update-baseline` 的分桶/保留旧桶/diff、`--dry-run` 无设备退出 0、synthetic 回放 PASS 与 p95 调坏 20% 退出 1、`--require-baseline` 对 pending-device/空桶/缺桶/部分夹具 SKIP/synthetic 的拒绝与对已捕获基线的放行。

这套测试已接进 GitHub Actions：`.github/workflows/terminal-bench.yml` 会在 push / PR 改到 `tools/terminal-bench/**` 或该 workflow 时自动跑单测、synthetic 回放和 `--require-baseline` 反向用例，并把桶与夹具判定写进 job summary。该 job 不接设备、不装 adb、不需要 secrets，因此只验证 harness 与基线文件；真机夹具与 `--update-baseline` 仍是人工步骤（见 §8）。

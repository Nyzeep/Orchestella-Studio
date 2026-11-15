# terminal-bench — 终端帧性能宿主侧闸门

调试专用 `TerminalBenchActivity` 在真机上跑五组固定夹具，把每组的帧统计以 `OE_BENCH {json}` 写入 logcat。本目录是宿主侧闸门：一条命令完成「安装 APK → 跑夹具 → 收结果 → 对比基线 → 退出码」，只用 `adb` 与 Python 标准库。

当前状态：**PENDING-DEVICE**。仓库内的 `baseline.json` 是空桶占位（`status: pending-device`），没有任何真机数字；在抓到第一份基线之前，本工具只报告、不判定。

## 1. 边界

- 只跑 debug 构建里的 `app/src/debug/.../bench/TerminalBenchActivity`，不碰 release 包。
- 只读 logcat 的 `OE_BENCH` / `OE_BENCH_DONE` / `OE_BENCH_ERROR` 三个 tag；不抓全量日志。
- 不在仓库里写设备序列号、完整日志或截图；`--json-out` 默认关闭，路径由调用者给。
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

## 3. 命令

一次性跑全部夹具并判定：

```bash
python3 tools/terminal-bench/run.py --fixture all
```

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
```

`--dry-run` 会打印解析出的 adb、serial、包名、APK 是否存在、基线状态、阈值与超时，然后退出。给了 `--logcat` 时，它还会真正跑对比；否则只校验基线并返回 0。

## 4. 夹具

| id | 内容 | 目的 |
| --- | --- | --- |
| `flood_seq` | 每 16 ms 把 8×1024 字符的顺序文本块直接追加到终端模型 | 持续输出下的帧稳定性；**绕过 PTY**，结果偏乐观 |
| `flood_color` | 同样节奏，但每块带颜色/样式变化 | 属性变化引发的重绘开销 |
| `scroll_2000` | 建立 2000 行内容后连续滑动 20 次 | 滚动、布局复用与缓冲裁剪 |
| `type_100` | 通过真实输入连接提交 100 次 ASCII 字符与 20 次 CJK 组合输入 | 输入法/回显/光标重绘路径 |
| `resize_font` | 字号 10→20sp 往返 5 次（每次改字号都走真实的 `applyTheme` + 重新布局） | 主题应用与 relayout 成本 |

`--fixture all` 依次跑上面五个；结果里缺哪个夹具会打印 `NOTE missing fixtures: ...`，但不因此判失败。

## 5. 输出字段

每行结果是一个 JSON 对象，字段如下：

| 字段 | 单位 / 含义 |
| --- | --- |
| `fixture` | 夹具 id |
| `device` | `Build.MODEL` |
| `api` | `Build.VERSION.SDK_INT` |
| `frames` | 测量窗口内呈现的帧数（不含预热阶段） |
| `janky` | 帧间隔超过 **2×刷新间隔** 的帧数 |
| `p50` / `p95` / `p99` / `max` | 帧间隔分位数与最大值，**毫秒** |
| `fps` | `frames * 1000 / durationMs`，帧/秒；上限受 `refreshHz` 限制 |
| `javaKb` / `nativeKb` | 夹具前后 Java / native 堆的**差值**，KiB，可为负 |
| `cols` / `rows` | 终端网格列数 / 行数 |
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
"<model>/<api>": {
  "device": "<model>",
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

分桶规则：桶键是 `<model>/<api>`；`--model` 原样写入，其中的 `/` 会替换成 `_`；`api` 必须是正整数。对比时只用实测结果里 `device`/`api` 对应的那个桶，绝不跨型号或跨 API 比较。捕获时还会一并写入 `cols`/`rows`/`refreshHz`/`durationMs`/`drawP95`/`layoutP95`/`dropped` 供人工诊断，这些字段不参与回归判定。

## 8. 更新基线

```bash
python3 tools/terminal-bench/run.py \
  --update-baseline --model "<型号>" --api 36 --fixture all
```

- 必须同时给 `--model` 和 `--api`，否则退出码 2（`MODEL_REQUIRED` / `API_REQUIRED`）。
- 必须在与 gate 相同的型号、相同的 API、相同的刷新率上抓；换设备就换桶，不要覆盖别的桶。
- 只合并本次实测到的夹具到该桶；桶里原有的其他夹具保留；**其他桶原样不动**。
- 跑完打印逐指标 diff（旧值 → 新值，p95 额外给百分比），并把顶层 `status` 置为 `captured`、写入 UTC 秒级 `capturedAt`。
- 解析出的 `device`/`api` 与显式参数不一致时打印 `WARN`，以显式参数为准。
- 基线是代码的一部分：`baseline.json` 的改动走正常评审，并在 PR 里贴出 diff 与结果 JSON。

注意：`--dry-run --update-baseline` 不会写文件，只打印将要写入的桶；去掉 `--dry-run` 才落盘。

## 9. 无基线时

以下情况打印 `NO_BASELINE bucket=<key>` 和本次实测指标，然后退出 0（report only，不判定）：

- 基线里没有该 `<model>/<api>` 桶；
- 桶存在但 `fixtures` 为空；
- `baseline.json` 仍是 `pending-device` 空桶。

这是刻意的：没有基线就没有回归判断的依据。要把它当发布闸门用，先在同一设备上抓一份基线。

## 10. 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 全部夹具在阈值内；或无基线 / 缺夹具，只报告 |
| `1` | 至少一个夹具判回归（p95、janky 比例或 fps） |
| `2` | 环境或 harness 失败：无 adb、无设备、安装失败、启动失败、超时、`OE_BENCH_ERROR`、基线/参数错误 |

退出码 2 会在 stderr 打印 ASCII token，例如 `ERROR=ADB_UNAVAILABLE`、`ERROR=NO_DEVICE`、`ERROR=INSTALL_FAILED`、`ERROR=BENCH_TIMEOUT`、`ERROR=BENCH_ERROR`、`ERROR=BENCH_NO_RESULTS`，并附上解释失败的原始 adb 输出。

## 11. 结果贴到哪里

- 发布 checklist：用 `--json-out /tmp/terminal-bench-result.json` 生成 `{"bucket":..., "capturedAt":..., "results":{...}}`，把 JSON 贴进 checklist 对应条目。
- 本仓库没有单独的 `docs/` 入口；本 README 就是 terminal-bench 的唯一入口，结果与基线变更都引用这里。
- 不要提交完整 logcat、设备序列号、截图或录屏；这些只留在本地或外部 artifact 根。

## 12. 测量陷阱

1. **debug 构建 vs release**：夹具 activity 只在 debug 源集里；debug 包没有 R8 优化、断言更多，绝对数值天然更差。永远不要把 release 包的数字和本基线比。
2. **JIT / 冷启动预热**：脚本或手动跑之前先 `am force-stop <包名>`，activity 在测量窗口前也有预热，但安装后的第一次运行仍会受 dexopt、类加载、着色器初始化影响。gate 前先手动跑一轮，正式结果取预热后的那一轮。
3. **刷新率与 fps 上限**：`fps` 的理论上限是 `refreshHz`；60/90/120 Hz 下同一份代码的 fps 不可比。抓基线和跑 gate 必须同刷新率，并记录 `refreshHz`。
4. **模拟器与真机不可比**：模拟器的 GPU、vsync、调度都与真机不同；基线桶只对同型号真机有效。
5. **flood 夹具偏乐观**：`flood_seq` / `flood_color` 绕过 PTY 直接写屏幕模型，省掉了 guest 输出与传输成本，只应作为渲染路径的下限参考。
6. **真实视图路径**：`type_100` 与 `resize_font` 走真实的输入连接、主题应用与布局路径，更接近用户操作，但仍不等于完整虚拟机负载。
7. **热与后台**：设备发热降频、后台应用、系统更新都会污染结果；每轮开始前静置到正常温度，保持相同后台状态。
8. **字体缩放 / 显示大小**：会改变 `cols`/`rows` 与布局成本；同一桶必须保持同一设置。
9. **日志开销**：采集只过滤三个 tag 并每 2 秒轮询一次，但测量期间不要同时跑全量 `adb logcat` 或其他 adb 大流量操作。

## 13. 自测

全部离线，不需要 adb 或设备：

```bash
python3 -m py_compile tools/terminal-bench/run.py

python3 -B -m unittest discover -s tools/terminal-bench -p 'test_*.py'

python3 tools/terminal-bench/run.py --dry-run --fixture all
```

测试覆盖：brief 格式解析（含 CRLF）、`OE_BENCH_DONE` 不被当成夹具、p95/janky/fps 三条回归规则、缺桶与缺指标的 report-only 行为、`--update-baseline` 的分桶/保留旧桶/diff、`--dry-run` 无设备退出 0。

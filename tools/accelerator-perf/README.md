# accelerator-perf — AX-07 跨车道性能测量与验收协议

QEMU 单车道 vs QEMU+ACCEL 交火的端到端测量协议。**协议先注册、阈值后不可改**：`protocol` 块在采集时被写进报告，分析阶段只能读它，不能改它。

## 1. 边界

- 本目录**零设备可自测**：喂固定输入 → 得到固定输出（`--self-test`）。
- 真机采样属于 DV-ACC，不在本目录跑。
- 只记录：**固定 reason code、计数、耗时、峰值内存、构建号、稳定设备别名**。
- 不记录：路径、token、序列号/IMEI/Android ID/设备指纹、真实 IP/MAC、原始 logcat、截图。

## 2. 场景

| scenario | 内容 |
| --- | --- |
| `cold_start` | 冷启动到 ready |
| `small_project` | 单文件 / 10 文件小项目 |
| `medium_project_j1` | 100 文件，`-j1` |
| `medium_project_j2` | 100 文件，`-j2` |
| `medium_project_j4` | 100 文件，`-j4` |

`mode` 只有 `QEMU_ONLY` 与 `QEMU_PLUS_ACCEL`。两种 mode 必须同设备、同镜像、同资源档位。

## 3. 采样格式

一个 TSV，首行必须是表头，字段用制表符分隔：

```text
scenario	mode	status	wall_ms	pre_ms	tx_ms	cc_ms	ld_ms	offload_permille	retries	fallbacks	peak_kb	code
```

PVM-07 起允许在表头尾部按以下**固定顺序的子序列**追加可选分段列：

```text
preprocess_ms	transport_ms	transport_bytes	payload_load_ms	compile_ms	link_ms
```

| 字段 | 约束 |
| --- | --- |
| `scenario` / `mode` / `status` | 固定词表；`status` 为 `ok` 或 `fail` |
| `wall_ms` … `ld_ms` | 非负整数；`pre/tx/cc/ld` 是预处理/传输/编译/链接的拆分 |
| `offload_permille` | 0–1000 的整数，卸载率（千分比） |
| `retries` / `fallbacks` | 非负整数 |
| `peak_kb` | 非负整数，峰值内存 |
| `code` | 固定 reason code；`ok` 样本必须是 `NONE` |
| `preprocess_ms` … `link_ms`（可选） | 非负整数或**空单元格**；空 = 没测到，报告里是 `null`，**绝不允许填 0** |

- 表头**不含**任何分段列 → 产出 `accelerator-perf-v1` 报告（与历史字节级一致）；
- 含**任意**分段列（按上面顺序）→ 产出 `accelerator-perf-v2` 报告，缺列/空单元格对应字段为 `null`；
- 报告值 = 成功样本中**非空**值的 median（`payload_load_ms` 只在首样本记录一次，单值 median 即原值）；
- 分段值的采样方法见 §7.1：**无法测量就留空**，不得猜测。

允许的 `code`：`ACCEL_*`（AX-02 选道码）、`THERMAL_BUDGET`、`ACCELERATOR_UNAVAILABLE`、`LANE_LOST`、`STOP_UNCONFIRMED`、`DISTCC_FALLBACK`、`TIMEOUT`、`NONE`。**不接受自由文本**：写不了"因为感觉慢"。

失败样本**必须保留**并计入 `failures`，只从分布里排除，不删除。

## 4. 命令

```bash
# 采集：固定输入 → 固定输出（--generated-at 固定时间即可复现字节）
python tools/accelerator-perf/collect.py \
  --samples tools/accelerator-perf/fixtures/samples-dryrun.tsv \
  --build-id dryrun-local --device-alias bench-sim \
  --generated-at 2026-10-23T00:00:00Z \
  --out /tmp/report.json

# 校验一个报告
python tools/accelerator-perf/validate.py --report /tmp/report.json

# 零设备自测闸门（CI 跑的就是这一条）
python tools/accelerator-perf/validate.py --self-test

# 单测
python -B -m unittest discover -s tools/accelerator-perf -p 'test_*.py'

# 对比两份报告，套用预注册停止条件（v1↔v2 可互比，同 device_alias + protocol）
python tools/accelerator-perf/compare.py --baseline base.json --candidate cand.json

# v2 采集（带分段列的 TSV）与最小 v2 报告校验
python tools/accelerator-perf/collect.py \
  --samples tools/accelerator-perf/fixtures/samples-v2.tsv \
  --build-id pvm-sample --device-alias bench-sim \
  --generated-at 2026-10-23T00:00:00Z --out /tmp/report-v2.json
python tools/accelerator-perf/validate.py --report tools/accelerator-perf/fixtures/v2-minimal.json

# compare 演示对（v1 golden 基线 + v2 分段候选）
python tools/accelerator-perf/compare.py \
  --baseline tools/accelerator-perf/fixtures/v1.json \
  --candidate tools/accelerator-perf/fixtures/v2.json
```

退出码：`0` 通过，`1` 结论为回归或校验失败，`2` 输入/协议错误。

## 5. 统计口径

- `n ≥ 10`（`min_samples`），不足会在报告里标 `insufficient_samples: true`；
- `median_ms`、`iqr_ms`（Q3−Q1）、`min_ms`、`max_ms`；
- 分位数用**排序后线性插值**（`iqr_method = linear-interpolation-on-sorted-values`），与 numpy 默认一致，报告里写死，读者可手算复核；
- 相位耗时取成功样本的中位数；`retries`/`fallbacks` 是对全部样本求和；`peak_kb` 取最大值。

## 6. 停止条件（预注册，不可事后调）

| 条件 | 结论码 | 动作 |
| --- | --- | --- |
| 每个场景端到端 ≥ **1.5×** | `PRODUCTIZE` | 可以进入产品化评估 |
| 任一场景比基线慢 **>5%** | `REGRESSION` | 回退该变体 |
| 快但不足 1.5× | `BELOW_THRESHOLD` | 记录负结果，不做产品声明 |
| 任一场景 `n = 0` | `INCOMPLETE` | 补齐样本，不下结论 |

两份报告必须同 `device_alias`、同 `protocol`，否则 `compare.py` 直接拒绝比较。

## 7. 设备 runbook（DV-ACC 使用，本轮不执行）

1. **准备**：同一台设备、同一镜像、同一构建号；`build_id` 用版本后缀或 CI run 别名，`device_alias` 用固定别名（如 `bench-a`），不写型号/序列号。
2. **开 flag**：`-Porchestella.acceleratorLane=true`；先确认装配根输出的 `decision` 是 `QEMU_PLUS_ACCEL`，否则记录 reason code 后停止，不要"调到能跑"。
3. **控温**：每轮开始前静置到 `ThermalState.NOMINAL`；每轮之间固定冷却时长；两 mode 交替采样（A/B/A/B），不要先跑完 A 再跑 B。
4. **同资源**：CPU 核数、RAM、磁盘、`-j` 值完全一致；后台进程状态一致。
5. **重跑规则**：单个样本失败按原样记录，不重试；整轮作废最多重来 1 次，且两次都要进证据目录。
6. **证据**：报告 JSON、采样 TSV、两份报告的 SHA-256 存外部 artifact 根；仓库只写别名、构建号、固定 code 与哈希。

### 7.1 分段采样方法（accelerator-perf-v2）

分段字段回答一个问题：**收益不达标时，时间花在哪里？** 采集纪律：

| 字段 | 怎么测 | 归因口径 |
| --- | --- | --- |
| `preprocess_ms` | guest 内 `clang -E` 的墙钟（distcc 客户端计时输出） | **可从日志推导**；必须覆盖每样本 |
| `transport_ms` | 宿主 DistccTcpVsockBridge 对 AXP1 帧的收发间隔（宿主测量） | **必须宿主测量**，不从两端时间戳相减倒推 |
| `transport_bytes` | 同上：每 job 出网字节（压缩后口径） | **必须宿主测量**；deflate 前后差值留在宿主侧证据里，不进报告 |
| `payload_load_ms` | payload 首次加载 `.so` 到首次 DONE（仅该场景首样本记录） | **可从 payload 日志推导**；每场景只记一次，之后留空 |
| `compile_ms` | payload 内 `InProcessCompiler` 从收到 DOTI 到 DOTO 完成 | **可从 payload 日志推导**（payload 侧计时） |
| `link_ms` | guest 内本地链接的墙钟 | **可从日志推导**；必须覆盖每样本 |

规则：

1. **宿主测量优先级最高**的是 `transport_ms` / `transport_bytes`——它们是本轮最可能吃掉收益的开销，必须来自桥接计数，不允许"总时长减去其余分段"倒推。
2. **无法测量就留空**：报告里是 `null`，`compare.py` 的归因块只在字段齐全时输出。`null` 表示"没测到"，**绝不允许填 0**——0 会把"没测"伪装成"测了且为零"，污染归因。
3. 失败样本的分段值不采集也不补：失败只进 `failure_codes`。
4. 分段值与 `wall_ms` 不要求严格相加（计时点不同会有重叠/间隙），归因时按占比读，不做闭合校验。

## 8. 为什么这样设计

- **协议先注册**：阈值写在报告里而不是分析脚本里，避免"跑完再挑一个好看的阈值"。
- **拒绝自由文本**：`code` 走固定词表，报告里不可能夹带路径、token 或原始日志。
- **失败样本不剔除**：只统计成功样本会系统性高估收益。
- **字节级可复现**：`--generated-at` 固定后，同样输入产生同样报告字节，golden 对比才有意义。

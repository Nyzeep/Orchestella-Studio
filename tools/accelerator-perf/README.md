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

| 字段 | 约束 |
| --- | --- |
| `scenario` / `mode` / `status` | 固定词表；`status` 为 `ok` 或 `fail` |
| `wall_ms` … `ld_ms` | 非负整数；`pre/tx/cc/ld` 是预处理/传输/编译/链接的拆分 |
| `offload_permille` | 0–1000 的整数，卸载率（千分比） |
| `retries` / `fallbacks` | 非负整数 |
| `peak_kb` | 非负整数，峰值内存 |
| `code` | 固定 reason code；`ok` 样本必须是 `NONE` |

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

# 对比两份报告，套用预注册停止条件
python tools/accelerator-perf/compare.py --baseline base.json --candidate cand.json
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

## 8. 为什么这样设计

- **协议先注册**：阈值写在报告里而不是分析脚本里，避免"跑完再挑一个好看的阈值"。
- **拒绝自由文本**：`code` 走固定词表，报告里不可能夹带路径、token 或原始日志。
- **失败样本不剔除**：只统计成功样本会系统性高估收益。
- **字节级可复现**：`--generated-at` 固定后，同样输入产生同样报告字节，golden 对比才有意义。

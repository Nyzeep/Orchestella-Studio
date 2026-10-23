# AX-07 跨车道性能测量与验收协议 实施报告

- 日期：2026-10-23
- Base：`main`（四级 accelerator 堆叠已合入）
- Branch：`feat/ax-07-perf-protocol`
- 工单：`.scratch/orchestella-accelerator/issues/AX-07-跨车道性能测量与验收协议.md`
- 零设备：本会话没有运行 adb、没有安装 APK、没有采集任何真机数据。本报告里的所有数字都是**夹具数据**，不是性能结论。

## 1. 结论

零设备范围内**完成**。`tools/accelerator-perf/` 从"只有工单"变成可执行协议：

| 交付 | 文件 |
| --- | --- |
| 采集器 | `collect.py`（TSV → `accelerator-perf-v1` JSON） |
| 统计 | `perfstats.py`（n≥10、median、IQR，排序后线性插值） |
| 校验 + 干跑闸门 | `validate.py`（`--report` / `--self-test`） |
| 对比与停止条件 | `compare.py`（`PRODUCTIZE` / `REGRESSION` / `BELOW_THRESHOLD` / `INCOMPLETE`） |
| 结果 schema | `schema/accelerator-perf-v1.json` |
| 设备 runbook | `README.md` §7 |
| 夹具 | `fixtures/samples-dryrun.tsv`、`fixtures/expected-report.json` |
| CI | `.github/workflows/ci.yml` 新增一步，跑单测 + 自测 + 真实 collect/validate 往返 |

## 2. 为什么这样设计

- **协议先注册**：`min_samples=10`、`speedup_threshold=1.5`、`regression_permille=50` 写进每份报告的 `protocol` 块。`compare.py` 只读不写这些值，两份报告协议不一致直接拒绝比较。避免"跑完再挑阈值"。
- **字节级可复现**：`--generated-at` 固定后，同输入产生同样字节；仓库里存 golden 报告，`--self-test` 做逐字节比对。这是"零设备可自测"的落点。
- **拒绝自由文本**：`code` 是固定词表，`build_id` / `device_alias` 必须匹配 `^[A-Za-z0-9._-]{1,48}$`，且显式拒绝 IPv4/MAC 形状。报告里结构上就写不进路径、token、设备标识或原始日志。
- **失败样本不剔除**：失败只从分布里排除，`failures` 与 `failure_codes` 必须对得上（校验器强制求和一致）。
- **schema 文件是活的**：`test_validate.SchemaFileTest` 断言 schema 的 `required`、场景/模式/失败码词表、协议常量与代码完全一致。漂移会让测试红，不会变成装饰品。

## 3. 统计口径

- `n` = 成功样本数；`n < 10` 时标 `insufficient_samples: true`，不静默接受。
- 分位数：排序后线性插值（numpy 默认口径），`IQR = Q3 − Q1`。
- 相位耗时取成功样本中位数；`retries` / `fallbacks` 对全部样本求和；`peak_kb` 取最大。
- 某场景全部失败时，分布字段为 `null`，不伪造 0。

## 4. 停止条件

| 条件 | 结论码 |
| --- | --- |
| 全部场景 ≥ 1.5× | `PRODUCTIZE` |
| 任一场景慢 >5% | `REGRESSION`（退出码 1） |
| 快但不足 1.5× | `BELOW_THRESHOLD` |
| 某场景 n=0 | `INCOMPLETE` |

`compare.py` 另外拒绝：不同 `device_alias`、不同 `protocol`、无共同场景。

## 5. 测试证据（本次是本地真实执行，不是只编译）

```text
python -B -m unittest discover -s tools/accelerator-perf -p 'test_*.py'
Ran 52 tests in 0.008s
OK

python tools/accelerator-perf/validate.py --self-test
ACCELERATOR_PERF_OK scenarios=10

python tools/accelerator-perf/collect.py ... --generated-at 2026-10-23T00:00:00Z | diff - fixtures/expected-report.json
（无差异，字节一致）
```

`--self-test` 除正向外还断言三种篡改必须被拒：`median` 越界、`schema` 改名、出现 `RAW_LOG` 这类未知 failure code。一个"什么都能过"的校验器不算证据。

CI 侧新增步骤（`ci.yml`）：

```yaml
- name: AX-07 accelerator performance protocol tests and dry-run
  run: |
    python3 -B -m unittest discover -s tools/accelerator-perf -p 'test_*.py'
    python3 -B tools/accelerator-perf/validate.py --self-test
    python3 -B tools/accelerator-perf/collect.py ... --out "$RUNNER_TEMP/..."
    python3 -B tools/accelerator-perf/validate.py --report "$RUNNER_TEMP/..."
```

## 6. 与工单的偏差

1. **验收命令从 pytest 改为 unittest。** 工单写的是 `python -m pytest tools/accelerator-perf -q`，但本环境 PyPI 不可达、CI 依赖里也没装 pytest。协议用标准库即可，因此改成 `unittest discover` 并把这一条写进 README 与 CI。这是本轮唯一一处偏离工单文字的地方，理由是让测试**真的被执行**，而不是留一个跑不了的命令。
2. **schema 的强制执行者是 `validate.py`，不是 `jsonschema`。** 同样因为没有第三方包。schema 文件是对外契约描述，代码里用 `SchemaFileTest` 保证二者不漂移。

## 7. not-tested

- 一切真机数据：没有任何一次真实采样，报告里的 `bench-sim` 是模拟别名，数字是夹具。
- 实测收益是否 ≥1.5×：本工单不产出结论，只产出可复核的协议。
- `compare.py` 与真实 DV-ACC 报告的兼容性：目前只与夹具和内存构造的报告对过。

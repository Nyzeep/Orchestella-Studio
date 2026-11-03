# PVM-07: 性能协议扩展（transport / preprocess / compile / link）

- Type: tooling
- Status: implemented（PR #22 已合并 2026-10-24）
- Priority: P1（不改判定阈值，只加可观测项）
- Owner: tooling owner
- Blocked by: 无
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

AX-07 的 `accelerator-perf-v1` 只记录端到端与卸载率，无法回答本轮最重要的问题：「如果收益不达标，是传输吃掉了，还是编译本身没变快？」`.i` 动辄几 MB，AXP1 又可能加 deflate，这层开销必须单独可见。

## 目标

- schema 升 `accelerator-perf-v2`，新增每场景的分段字段（可选，缺失时按 `unknown` 处理，不伪造 0）：
  - `preprocess_ms`（guest 本地）
  - `transport_ms` / `transport_bytes`（AXP1 帧，含压缩前后）
  - `payload_load_ms`（首次加载，仅首样本）
  - `compile_ms`（payload 内）
  - `link_ms`（guest 本地）
- `compare.py` 判定阈值**不变**（1.5× / 5%），只多打印分段归因。
- 旧 v1 报告仍可读（向后兼容），不重写历史数据。

## 任务

- [ ] `schema/accelerator-perf-v2.json` + `validate.py` 支持 v1/v2。
- [ ] `collect.py` 接受可选分段列（TSV 缺列 → 对应字段 `null`，不填 0）。
- [ ] `compare.py` 输出归因块：`transport_share`、`compile_share`、`link_share`（仅当字段齐全）。
- [ ] `perfstats` 单测：缺列、部分列、全列三组。
- [ ] README §7 runbook 增「分段采样方法」与「哪些值允许从日志推导、哪些必须宿主测量」。
- [ ] 明确：分段值不得来自猜测；无法测量就留空。

## 验收命令

```bash
python -m unittest discover -s tools/accelerator-perf -p 'test_*.py'
python tools/accelerator-perf/validate.py --report fixtures/v2-minimal.json
python tools/accelerator-perf/compare.py --baseline fixtures/v1.json --candidate fixtures/v2.json
```

## 边界

- 不修改 `protocol.min_samples/speedup_threshold/regression_permille` 常量。
- 不引入新依赖；沿用现有 Python 标准库实现。
- 不把 `.i` 内容或源码路径写进报告。

## 失败信号

- 为了让报告好看而给缺失字段填 0 → 直接拒绝（会污染归因）。
- v1 报告解析失败 → 兼容性没做对。

## 证据

- unittest 输出 + 一份 v2 样例报告（fixtures）。

## Comments

- **2026-10-24（会话 2 agent）**：已实现并开 PR #22（`feat/pvm-07-perf-v2`，head `15a1c85`，base main，未合并）。schema v2 六个可选分段字段（缺失一律 null、禁伪 0）；collect 接受 6 列按序子序列（缺列/空单元格→null；无分段列时产出 v1 报告字节级不变）；validate 接受 v1/v2（v1 行为不变，`--self-test` 扩为双 golden round-trip + 新篡改用例）；compare 字段齐全时输出 `transport_share`/`compile_share`/`link_share`（阈值常量未动，归因不改判定）；README §3/§4/§7.1 补采样方法（transport/bytes 必须宿主测量，preprocess/compile/link/payload_load 可从日志推导，无法测量留空）。本机 `python -m unittest discover` 75/75 绿、`validate.py --self-test` OK、票面三条验收命令通过；PR #22 CI 四项全绿。边界：`ALLOWED_CODES` 未纳入 PVM 新失败码（归 PVM-03/06 集成票）；真机采样 not-tested（归 DV-ACC）。

- **2026-11-04（设备窗轮对账）**：`gh pr view 22` 实测 state=MERGED（mergedAt 2026-10-24T09:24:21Z），置 implemented。真机采样项仍 `not-tested`，现归 DV-PVM-02 真机面。

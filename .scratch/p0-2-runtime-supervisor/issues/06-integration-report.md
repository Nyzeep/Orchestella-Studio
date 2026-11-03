# T6 集成回归与中文报告

Status: resolved
Type: task
Blocked by: T4, T5

## 交付

- 全量回归：`:runtime:engine-qemu:check`、`:runtime:instance:check`、`:app:testDebugUnitTest`。
- `git diff --check`。
- 中文报告 `docs/reports/orchestration/p0-2-runtime-supervisor-20261018.zh-CN.md`。
- 更新 `docs/reports/README.md` 与 `docs/README.md` 当前状态。
- 新增 ADR-0020 运行时进程所有权；更新 `CONTEXT.md` 与 `docs/adr/README.md`。
- 记录真机 20 次启停为 `not-tested`（无设备）。

## 验收

- 所有测试通过，记录 tests/suites/failures。
- 报告区分 fake 验证、真实进程验证、真机未测。
- 只对经过审查的具体文档 `git add -f`。

## Comments

2026-11-03 协调：本轮做 tracker 对账。本票此前唯一缺口是「交付已写完后没有留运行态证据」，本轮补齐（来源 `.scratch/quality-round-20261103/notes/B5-tracker.md` §T6）。

2026-11-03 协调：文档类交付项逐条核实，全部已存在。
- 中文报告 `docs/reports/orchestration/p0-2-runtime-supervisor-20261018.zh-CN.md`（`:1-13` 结论、`:157` 历史测试计数、`:186-191` 未运行清单）。
- `docs/reports/README.md:14` 已索引；`docs/README.md:20` 已写当前状态「P0-2 已完成代码与 JVM 回归，真机启停待验证」。
- `docs/adr/0020-runtime-process-ownership.md`（`status: accepted`）；`docs/adr/README.md:26` 已索引；`CONTEXT.md:163-164` 有术语条目。
- `not-tested` 已在报告 `:13`、`:188-190` 标注，且明确区分「纯 JVM fake / 真实宿主 OS 子进程 / 真机未测」。

2026-11-03 协调：运行态证据（本轮在同一条基线上实跑，`X:` ASCII 路径 + `--offline`）。
- `./gradlew :runtime:engine-qemu:check` → `BUILD SUCCESSFUL in 1m 6s`，16 actionable tasks；`QemuProcessHandleSeamTest` 结果 `tests="2" skipped="0" failures="0"`。
- `./gradlew :runtime:instance:check` → `BUILD SUCCESSFUL in 2m 23s`，24 actionable tasks；`INSTANCE_STOP_SIGNAL_TEST_OK` 等全部契约套件打印 OK 标记。
- `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`。
- `git diff --check` → 退出码 0（无空白错误）。

2026-11-03 协调：验收第 3 条「只对经过审查的具体文档 `git add -f`」——本轮未改动上述任何文档，`git add -f` 清单为空，无越界添加。

2026-11-03 协调：真机 20 次启停仍为 `not-tested`（零设备）。本票的 `resolved` 仅覆盖零设备闭环，不代表真机验证。

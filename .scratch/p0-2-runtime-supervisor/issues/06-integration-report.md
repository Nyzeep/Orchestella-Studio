# T6 集成回归与中文报告

Status: ready-for-agent
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

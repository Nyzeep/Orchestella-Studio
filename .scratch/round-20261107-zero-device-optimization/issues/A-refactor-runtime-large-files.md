# A · 代码优化与系统整合：runtime 大文件拆分 + 池抽象统一

## What
1. `InstanceLifecycle.java`（1724 行，runtime/instance）按职责拆分（状态机/协调/收尾边界自定，拆后单文件 ≤800 行为目标）。
2. `TerminalMux.java`（862）与 `BridgeControl.java`（847）同规则评估，有清晰 seam 才拆，无 seam 则记录不动。
3. `ProtectedWorkerPool.java`（1133）与 `ProtectedWarmPool.java`（862）抽出共享池抽象（ acquire/release/容量旋钮/哨兵 ），消除双份实现。
4. 顺带：33 条 lint warning 中机械可修项收敛（不追零，不引新 error）。

## Acceptance
- 行为保持：公共 API 不变语义；既有测试全绿且为新抽取边界补最小锁定测试。
- 受影响模块 `:runtime:instance:check` 等 + `:app:testDebugUnitTest` 全量绿。
- 拆分后 wc -l 达标项列出；未拆项给出一句话理由。

## 边界/坑
- 只碰 spec 表 A 列文件 + 新类；禁碰 MainActivity/strings/res。
- 中文路径不能跑测试：worktree 建在 /d/t/。构建先 clean。

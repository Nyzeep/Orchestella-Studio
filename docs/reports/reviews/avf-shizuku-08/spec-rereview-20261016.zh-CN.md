# 08 独立 Spec 定点复审：S-03 停止观测修复

结论：**approve（仅 S-03 的冻结 JVM 模型修复）**。原首审 S-03 在本次冻结模型范围关闭；08 生产 owner、ROM、真实 FD/VM 停止门继续未验。本文件是追加复审，`spec-review-20261016.zh-CN.md` 原件不变。

## 对象、基线与范围

独立 Spec reviewer / Codex GPT-6.1-sol；日期 2026-10-16。沿用本批 dirty0.5.0/code500 起点及用户给定 main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，无新 commit、无 Git/ref/版本操作。已读项目 policy 与 20261016 Reviewer/Worker B 要求，未读另一轴或复用其结论。

冻结对象：`batch-01/avf-platform-device/seals/p2-stop-observation-fixed/` 中 `external/fixture-src/workerb/BoundedReadinessProbe.java`、`external/run-fixtures.py`、`output-validation.json`、delivery manifest。比较 S-03 首审原件 `fixture-history/p2-stop-observation/before/external/`，以及对应新增 red、5 组 green、旧 9 组 green 原始记录。无生产修改审查范围扩大，不运行套件、设备、Gradle 或签名。

## 对应验收及定点核对

原 08 要求 stop/death、旧 watcher、取消/失败 FD 一次关闭，并区分 caller 截止与底层结束。冻结修复在 `Session.observeStop` 的 generation 检查之后调用 `retire(Reason.CLOSED)`（第 177–182 行）：清空 readiness、拒绝新请求，回收已 published FD；仍分别记录 expected/unexpected 分类。旧代事件先被拒绝，不会动当前代 FD、分类或 lease。

`retire` 不减少 liveConnects，不清除全 App lease；未返回 FD 不作虚假关闭。late connect 真正返回后走 retired 分支 once-close，再减少底层额度。close 抛错保留 closeFailed，`canReleaseDiskLease` 仍要求 stopRequested、platformStopReturned、stopObserved、底层在途归零、published 空且无关闭失败。停止观测本身不能解锁同盘回退。

新增行为门明确覆盖：已 ready 后 unexpected stop；expected stop 分类；published FD 关闭失败；停止时底层 connect 仍在途及迟到 FD；旧代停止不能退役当前 ready。重复 stop/cancel 不再次关闭同一 published FD。未发现本次局部改动引入额外 Spec 问题。

## 原件与测试真实性

已只读核对：

- `p2-stop-observation-red.json`：exit1，原始输出 `ready_after_stop=true fd_closes=0`，精确断言 CURRENT_STOP_MUST_RETIRE_READY_AND_CLOSE_PUBLISHED_ONCE。
- `p2-stop-observation-regression-green.json`：exit0，5 行 PROTOTYPE_PASS，输出 ready_after_stop=false、fd_closes=1，cases=5。
- `p2-existing-nine-green.json`：exit0，9 行 PROTOTYPE_PASS，cases=9。

上述 raw hash 与 receipt 相符，preservation 同字节。冻结模型 26422B / SHA256 `207423b99a2d7898f10f8071477afb6c602a7aeda6481fec42e75cfc4aa758bd`；runner 5533B / SHA256 `9e4ce79b2eaea389dc00f12ee737dd77e212563aa0aa63533ee156484f7393ab`。5 新增与旧9分列，不累计重复 green 或把早期单组 green 算额外覆盖。

这是内存 FD、显式 clock/latch 的 JVM_SYNTHETIC 证据；不证明 Android/Binder FD、目标 ROM mLock 中断、App/server death 或 VM 正常停止。output-validation 是任务 seal，不能代替物理事实。本 reviewer 仅核源码与已有输出/hash，无重复执行。

## 交付、限制与后续

实际完成：本复审、`review-spec/08-rereview/` 核验记录及 `preservation/review-spec/08-rereview/` 孪生。修改仅指定 review 报告和本轴外部证据；首审保持。剩余：08/07 真实 owner、平台停止、资源和设备门；ACR 仍待裁定，不因关闭 S-03 放行生产。

风险：把模型关闭误当真实平台已收敛；报告保留该分级。无生产/设备/Gradle/网络/版本/签名/ref/commit/系统修改，无清理或另一轴读取。Architecture Change Policy：无本次架构实施，现 ACR 继续有效。Class B，保留至维护者验收归档，不自动清理。

报告人：独立 Spec reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

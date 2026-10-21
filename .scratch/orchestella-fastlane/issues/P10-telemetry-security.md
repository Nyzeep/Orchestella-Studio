# P10: Fast Lane 可观测性与安全（零设备）

Type: implementation
Status: implemented-zero-device（46 JVM tests；Android/UI 集成待 DV-01/04）
Priority: P1
Owner: observability/security owner
Blocked by: None（零设备）；集成由 DV-01/04 验证
External gates: 无

## 目标

让 Fast Lane 的启动/停止/失败可诊断，同时不泄露用户数据：

- 阶段事件：REQUESTED/CREATED/PAYLOAD_READY/TERMINAL_READY/PROXY_READY/EXPORT_READY/DISTCC_READY/STOP_*；
- 指标：启动耗时、payload ready、终端/代理字节、distcc job/耗时/失败、CPU/RSS；
- 脱敏：token/口令/按键/源码/完整路径不入日志；
- debug level：生产 NONE；FULL 仅 QA；
- 删除/导出语义与 P06 一致。

## 零设备范围

- 纯 JVM 事件 schema/序列化/脱敏测试；
- 与现有 27 recorder 的接口对齐；
- 不要求设备。

## 行为验收

- [x] 事件 schema 版本化；未知字段忽略；缺事件 `not_observed`，不补造。
- [x] 阶段顺序/并发/重试/取消可表达；不出现时间倒序（或明确标记）。
- [x] 脱敏规则覆盖 token/密码/URL 凭据/按键/源码/绝对路径；测试红/绿。
- [x] 日志大小/队列有界；默认关闭高频采集；无后台无限队列。
- [x] 指标不包含用户内容；只含计数/耗时/大小。
- [x] debug FULL 在日志明确“削弱保护”（`protectionWeakened`）；生产路径默认 NONE；UI 提示待 DV-04。
- [x] 停止未确认保留诊断（stage/pid/grace/exit），不写 STOPPED。

## 联网调研

- 本仓 27 production stage recorder/22 offline recorder；
- Android `ApplicationExitInfo`（API 30+）；
- AOSP Microdroid debug level 语义；
- 隐私/日志脱敏政策（项目 DEFENSIVE_CONSTRAINTS）。

## 交付

- `FastLaneEvent`/`FastLaneMetrics` schema；
- JVM recorder + redaction tests；
- 与 27 的 adapter 接口；
- 日志样本（脱敏）。

## 停止条件

- 无法保证脱敏：关闭该事件/指标，不记录。
- 与 27 schema 冲突：先统一 schema，不双写。

## Comments

- 2026-10-21 / Agent D：零设备实现完成于 `feature/fastlane-qemu-observability`。新增 `FastLaneEvent`（schema v1）、`FastLaneMetrics`（9 项数字指标）、`FastLaneTelemetry`（有界 128/最大 1024、无后台线程、可注入 Clock/LineSink）、`FastLaneEventCodec`（未知字段忽略、未来 schema 拒绝、嵌套上限 16）、`Redactor`（token/密码/Basic/Bearer/URL 凭据/邮箱/路径/IP/长 blob/源码/按键）、`LegacyStageAdapter`（只映射 5 个 27 等价阶段）。
- 测试：JDK 21 + `javac --release 17 -Xlint:all -Werror` + JUnit 4.13.2，`OK (46 tests)`；金样 `fastlane-events-v1.jsonl` 逐字节比较。
- 隐私评审后修复：STOP 无诊断/状态不一致、token 形状 instanceId、`flush` 持锁 I/O、disabled `notObserved` 误报、`cpuPercent` 截断、全 STOP 队列淘汰顺序、嵌套栈溢出等。
- 未验证：Android `ApplicationExitInfo`、UI FULL 提示、27 生产接线、持久 sink、`elapsedRealtime` 时钟；待 DV-01/04。
- 报告：`docs/reports/tickets/fastlane-P10/implementation-report.zh-CN.md`。

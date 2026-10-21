# P10: Fast Lane 可观测性与安全（零设备）

Type: implementation
Status: ready-for-agent（zero-device）
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

- [ ] 事件 schema 版本化；未知字段忽略；缺事件 `not_observed`，不补造。
- [ ] 阶段顺序/并发/重试/取消可表达；不出现时间倒序（或明确标记）。
- [ ] 脱敏规则覆盖 token/密码/URL 凭据/按键/源码/绝对路径；测试红/绿。
- [ ] 日志大小/队列有界；默认关闭高频采集；无后台无限队列。
- [ ] 指标不包含用户内容；只含计数/耗时/大小。
- [ ] debug FULL 在 UI/日志明确“削弱保护”；生产路径默认 NONE。
- [ ] 停止未确认保留诊断（stage/pid/grace/exit），不写 STOPPED。

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

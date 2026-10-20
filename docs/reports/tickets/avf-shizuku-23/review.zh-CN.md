# 票 23 双轴评审：模式事实、调用身份与诚实入口

- Review ID：`avf-shizuku-23`
- 日期：2026-10-20
- 评审对象：`avf/mode-facts` @ `9f7790ef`（含 05 平台访问与 10 的 `managerPresent` 补强）
- 评审者：Agent D（票 10 收尾）
- 结论：**Standards pass；Spec pass（零设备范围）；设备门保留**

## 轴一：Standards（代码与工程规范）

| 检查 | 结论 | 说明 |
| --- | --- | --- |
| 共享文件最小改动 | pass | 23 只动 `AvfHostFactsProbe`、`AvfCapabilityEvaluator` 和三个 UI 文件；没有格式化整文件/重排 import。10 只加 `managerPresent` 与 typed shape 证据。 |
| 不新增第三方依赖 | pass | `HiddenApiBypass 6.1` 的引入属于 05，已固定版本并在许可/notice 记录；23 本身没有新依赖。 |
| 测试与实现同层 | pass | 沿用仓库既有的 `main()` harness 模式（`AvfCapabilityEvaluatorTest`、`AvfPlatformAccessTest`），由 `check` 任务执行；没有为了 JUnit 迁移扩大范围。 |
| 失败语义 | pass | `SecurityException`、缺失方法、缺失类、未知异常分别映射；没有把异常写成 `false` 或 `READY`。 |
| 敏感数据 | pass | `AvfModeFacts.summary()` 只显示“ROM=已绑定（本地，不展示原始值）”；没有 serial/fingerprint/token。 |
| 单一事实源 | pass | `AvfPlatformFacts` 由 `ReflectiveAvfPlatformAccess` 产生；探针与引擎共用，未出现第二套反射 shape。 |
| 文案准确性 | pass（有注意项） | `AvfPlatformFacts.summary()` 用“平台服务=未探测到”表示 `managerPresent=false`；这是观察值，不是“服务不存在”的绝对断言。建议后续 UI 保持“未探测到”而非“没有”。 |
| 测试 seam 收敛 | pass | `AvfSession` 非 `final` 只作为 02 的 close 契约 seam；23/10 没有把它扩展成新的设计依赖。 |

**Standards findings**：无阻断项。一个非阻断注意项：`managerPresent=false` 的文案在旧调用方（没有平台适配器）也会出现，属于“未探测”而不是“缺失”，当前文案已避免绝对化。

## 轴二：Spec（工单验收）

| 验收项 | 结论 | 证据/说明 |
| --- | --- | --- |
| protected-only/nonprotected-only/both/neither/读取失败/部分 actor 分别明确 | pass | 模式枚举 + 六种 `AccessState`；测试覆盖 protected-only、unknown、permission denied、hidden API、reflection unavailable。 |
| App 权限、Shizuku/US 权限与模式不混填 | pass | App 行只写 `ACTOR="app"`；没有 shell/US 值进入 `AvfPlatformFacts`。设备端双 actor 记录由 09 第 2 阶段执行，不在 App 内伪造。 |
| 模式不满足时说明并保留 QEMU | pass | `MODE_UNSUPPORTED` 文案明确“非保护型模式”；MainActivity 仍提示 QEMU 路径。 |
| onResume/显式复查同一代 facts | pass | 同步 `probe(this)` 后整体赋值；没有异步回调覆盖新状态。 |
| 全权限但接口不可达/unknown 不声称成功 | pass | `MODE_UNKNOWN` 先于 READY；`performanceBadge()` 只在 READY 时给出“启动待验证”。 |
| 已有 client 拒绝/death、双权限、1CPU 说明不退化 | pass | 既有 capability/engine-avf 测试全绿；04 的 1 vCPU 提示仍在向导中。 |
| hidden API 受限时不再只能 UNKNOWN | pass（代码）/ not-tested（真机） | 05 的早安装豁免 + typed state；真实 ROM 的豁免效果留设备门。 |
| 报告/评审/状态一致 | pass | 本报告与 `implementation-report.zh-CN.md` 同 id；工单状态改为 completed。 |

**Spec findings**：

1. **非阻断**：工单正文写“当前仅工单建立，尚未实现/设备验收”，这是 2026-10-19 的历史状态；实际实现已在 `avf/mode-facts` 提交。报告已按代码事实更正，未改工单历史描述。
2. **非阻断（设备门）**：App 内没有 shell/UserService actor 的读取路径；这不是遗漏，而是不把两个身份混填。设备验收时由 09 工具分别记录 actor。
3. **非阻断（设备门）**：`HiddenApiBypass 6.1` 在目标 ROM 的真实行为、`getCapabilities` bit2、protected-only 的真实异常时序均未验。

## 结论

票 23 在零设备范围内满足 Spec 与 Standards；没有需要先修正再勾选的代码/报告不一致。真机项保留在 10 的 not-tested 清单，不阻塞本 effort 的代码就绪结论。是否合并 `main` 由用户/发布 owner 决定。

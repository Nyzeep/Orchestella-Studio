# 零设备长任务优化轮总报告：B2 编排接线 + A3 provision 收口（2026-11-06）

- 执行 session / 模型 / thinking：编排会话（2 个并行子智能体 lane）/
  GLM-5.3-Flash / 高
- 分支：2 条 lane 分支合入 `main`（基线 `966c70e`），HEAD 已 push；合并后
  集成冒烟 app **1207 tests / 0 failures**。
- 背景约束：用户确认长期无 non-protected 设备——UML 线为虚拟化性能优化主
  路径（AVF 保持时间盒研究线）。本轮落地性能方案明示的仅存两处"重投入"。

## 1. scope

| Lane | 分支 | 主题 |
| --- | --- | --- |
| T | `feat/b2-fabric-orchestration-wiring` | B2 编排消费 route 契约 |
| U | `feat/a3-provision-hardening` | A3 provision 硬化收口 + R-Q3 javadoc |

## 2. 实际完成

### Lane T — B2：fabric 编排消费 `FabricWidthDecision.route`
- **事实修正**：全仓不存在产品编译派发编排点（bridge→
  `WorkerPoolDistccDispatcher` 是 pVM 面设计意图路径、监听器缺格 =
  DV-PVM-02b 设备边界；UML 面未接入产品启动链）。按 C1 报告 follow-up，B2
  落为**新建编排层**：`FabricOrchestrator` + `AcceleratorLaneAssembly.
  fabricOrchestrator(...)` 接线格（共享协调器账本，与 `workerPool(...)`
  同型）。
- **语义**：账本派生三面宽度——QEMU 面 = C1 记账值消费点再 clamp 2、native
  面 = ACCEL 预留 + 账本余量、UML 面 = 接线方供给默认 0；**三面宽度之和恒
  等账本核数（D-3 由构造保证）**；在途宽度记账（并发派发不超账本）；
  per-worker 失败/掉线剔除（evict/arm + 固定码 `FACE_FAILED`）；既有双
  gate 沿用、关闭零副作用；单任务（≤2 并行）产品路径零改动。
- 测试：`:app` **1207/0**（+17：FabricOrchestratorTest 16 + Assembly 1）；
  `:runtime:instance` 52/0、`:runtime:fastlane-distcc` 77/0 回归绿；lint 0
  errors/33 warnings 零条落改动文件。
- **诚实缺口（follow-up，接入时编排层零改动可挂）**：① 桥 lane 标记通道
  （D-1）——改动面需过 ACR 且 UML 无产品启动链时无消费方；② UML 面产品接
  线（启动器产品化 + 前台服务/wake lock 硬前提）；③ native 面产品 TCP 监
  听起点（DV-PVM-02b 既有 gap）。真机半边验收归设备窗。
- 报告：`docs/reports/tickets/b2-fabric-orchestration-wiring/implementation-report.zh-CN.md`

### Lane U — A3：provision 硬化收口
- **审计结论**：A3 的两处产品改动（run_step 四类超时分档、pacman db.lck 锁
  恢复）经逐条审计**已由 e130897/T1+T2 完整覆盖**（含总闸保留、探针副本
  diff 归零）——不重写 shell（零行为变更）。真残余是 **JVM 行为测试层缺
  口**：分档/注入/幂等此前只有 python 驱动器覆盖，e130897 的超时覆盖边界
  校验是未入库的手工定向验证。
- **实现**：`ProvisionScriptHarness`（从产品脚本抽取真实函数在 POSIX sh 下
  执行，规避 Windows 参数引号陷阱）+ **17 例 JVM 契约测试**——超时分档边
  界 12 例（18 stage 全表分类、8 个非法覆盖值边界回落+IGNORED、注入预算真
  到 `timeout` 工具、script↔生成 init 漂移护栏）+ 锁恢复 5 例（陈旧锁删除
  且二次调用幂等、fuser 持有→释放收敛、无证据源永不删、真 /proc fd 扫描
  识别活持有者）。
- **顺手项**：R-Q3 javadoc（`LaneBudgetLedger.cpuCores`/`LaneRequest.
  cpuCores` 并发预算语义，doc-only）。
- 测试：`:runtime:instance:check` **69/0**（52+17，Windows Git Bash 实跑含
  /proc 例）；`:app` 1190/0 持平；敏感门 OK。
- 报告：`docs/reports/tickets/a3-provision-hardening/implementation-report.zh-CN.md`

## 3. 整合与验证

- 两分支零冲突合入；`SENSITIVE_DATA_OK`；集成冒烟 `:app:testDebugUnitTest`
  + `:runtime:instance:check` + `:runtime:contract:check` BUILD SUCCESSFUL
  1m36s，app **1207/0**（=1190+17 B2，对账吻合）。

## 4. 未完成 / 不适用

- B2 三项 follow-up（D-1 标记通道过 ACR、UML 面产品接线、native 监听起点）
  ——均非零设备可闭合，归设备窗/ACR。
- A3 真机 T7-F 复跑沿用 e130897 未验证清单口径（设备窗）。
- 依赖锁定专项维持 defer（跨平台生成策略待维护者）；v0.6.x 重剪 tag、ACR
  投票、生产 keystore 待用户明确指令。

## 5. 风险

- `FabricOrchestrator` 是新建编排层，gate 关闭时零副作用、默认路径不经过
  它；UML 面宽度默认 0=无行为变化。真机多面并发行为未验证（诚实边界）。
- `ProvisionScriptHarness` 依赖产品脚本函数签名——script↔init 漂移护栏已
  入测试，脚本重构时测试会先行红。

## 6. 后续动作

1. push 后 CI 验证。
2. 性能方案 13 条中零设备可做的已全部落地或给出诚实缺口；剩余项全部依赖
   设备窗（distcc@36、UML 产品接线、DV-PVM-02b）或维护者裁定。
3. 下一轮候选：按用户指令待定；设备窗侧 C1 真机长跑/B2 多面并发验证/
   distcc@36 均可在 device-myron 上做。
- 是否越界：各 lane 均在边界内；未触发 Architecture Change Policy。

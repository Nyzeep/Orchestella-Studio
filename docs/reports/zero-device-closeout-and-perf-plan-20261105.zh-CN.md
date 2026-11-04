# 零设备任务收口与性能方案轮总报告（2026-11-05）

- 执行 session / 模型 / thinking：编排会话（5 个并行子智能体 lane，分三批）/
  GLM-5.3-Flash / 高
- 分支：5 条 lane 分支全部合入 `main`（基线 `9f18c31`），合并后含本报告的
  HEAD 已 push；上一批（`f366bbf..9f18c31`）CI 四条 workflow 已确认全绿。
- 报告规范：`docs/agent-handoff/REPORTING_STANDARD.zh-CN.md`

## 0. 背景

用户指令：完成全部零设备任务并产出性能优化方案（真机窗口已于上一轮完成取
证，本轮全零设备）。零设备任务底盘＝对账后仍 open 的可做票（avf-shizuku 27
agent-ready 规格）、质量积压 P0–P3 可零设备项、Guest assets CI 连红诊断。

## 1. scope

| Lane | 分支 | 主题 |
| --- | --- | --- |
| J | `feat/avf-shizuku-27-stage-recorder` | 票 27 零设备切片实施 |
| K | `fix/guest-assets-ci` | Guest assets CI 双根因诊断与修复 |
| L | `fix/instance-write-locks` | P0-3 写路径目录锁 + P1-5 温池冷启动 |
| M | `chore/small-fixes-batch` | P3-8 / P3-4 / 设备文案别名化 / 依赖校验尝试 |
| N | `docs/perf-plan-20261105` | 性能优化方案（13 提案）+ 空 catch 分诊 |

## 2. 实际完成

### Lane J — 票 27 生产启动阶段记录器（零设备切片）
- `ProductionStageRecorder`（对齐票 22 离线 recorder 的 16 门
  Stage/Route/StopFact/Failure 口径、注入式单调时钟、观察序偏序不伪排序、
  16 事件/32 KiB 上限、SINK_FAILED/CAPTURE_LIMIT 显式退役）+
  `StageTraceFileSink`（App 私有目录版本化 JSONL，schema_version=1，旧
  synthetic 显式拒收并保留，轮转+保留份数）+ observer 挂点（Qemu/Avf
  launcher、RuntimeProcessSupervisor 三停止事实独立、BridgeConnector
  vsock/认证/PING 回执）+ `SettingsManager` 诊断开关**默认关、关闭态零持久
  采集**。
- 六组验收：1/2/3/4/5 ✅；6 ⚠️（本票相关全绿；Python 全量 discover 中
  `test_avf_device_mode` 24 项为 Windows 宿主 adb 环境性失败，工具零改动，
  建议 CI 复核分诊）。
- 测试：`:app:testDebugUnitTest` **1167 tests / 0 fail**（含新增 38）；
  Python validator 测试 15 OK。
- 诚实边界：真实 QEMU 事件原件（正常+受控失败）归设备窗；guest shutdown
  ack 与 services_ready 信号源未闭合（相应 gate 保持 not_observed）；AVF
  事件依赖票 07。
- 报告：`docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`

### Lane K — Guest assets CI 双根因（非同一根因）
- **根因 A**：`53117dc`（10-30）引入 guest-init 模板失同步 →
  v0.6.1/v0.6.2（两次指向）/v0.6.3-preview.1/distro-catalog-v1 全部剪在坏
  commit 上必红；`e130897`（11-03）已修（现 main
  `GUEST_INIT_TEMPLATE_SYNC_OK`）。
- **根因 B**：`e130897` 新增的 pacman 锁探针测试自带缺陷——fixture
  `sh -c 'exec 3<"$1"; sleep 20'` 的孤儿 sleep 继承 fd 3，kill 只杀父进程，
  fuser 命中孤儿误判 held（该测试在 CI 从未绿过）。
- **修复（1 行+3 行注释）**：fixture 改 `exec sleep 20`——Popen 子进程本身
  即持有者，kill+wait 后 fd 必然关闭。WSL Ubuntu 24.04（与 CI 容器同构）
  修复前逐字复现 CI 失败、修复后 `checks=71` 全过；held/unavailable/cleared
  三场景确定性通过；生产探针逻辑经诊断健全未改。
- **交回决策**：v0.6.2/v0.6.3-preview.1 历史红不可追溯，建议 run 全绿后从
  绿 commit 重剪 tag；guest-assets 门禁测试建议先在 feature 分支跑 workflow
  再合 main。
- 报告：`docs/reports/tickets/guest-assets-ci-fix/implementation-report.zh-CN.md`

### Lane L — 并发正确性收口
- **P0-3 残余**：`InstanceLifecycle.Controller` 的 delete、create（外层两
  重载委托四参实现）、exportToArchive 照既有 `xxxLocked` 模式收口到
  `synchronized (store.stateMonitor())` 目录级锁，与上轮 14 个已收口方法同
  构。
- **P1-5**：`ProtectedWarmPool.acquire()` 重构为"锁内决策+锁外执行"（
  ReentrantLock 可重入，锁内调内部 lock/unlock 不放锁——这是原缺陷的结构性
  根源），槽状态 `CREATING` 作排他所有权标记；retry 预算按驱逐原因分流；
  shutdown 增 CREATING 分支且绝不 stop 未发布会话。
- 测试：`:runtime:instance:check` **52/0**（+8 并发回归：holdsLock 契约×3、
  双 Controller barrier 串行化×3、真实 FileStore 竞争压测×2）；
  `:app:testDebugUnitTest` **1133/0**（+4）；**阴性验证**：回退修复各跑新
  测试均失败，证明测试确实捕获原缺陷。
- 报告：`docs/reports/tickets/instance-write-locks/implementation-report.zh-CN.md`

### Lane M — 小修打包
- **P3-8 ✅**：`setTargetInput` 全仓零调用方（含反射/字符串形态交叉核对），
  已删；`:app:compileDebugKotlin` 绿。
- **P3-4 ✅（范围勘误）**：14 个 runtime 模块逐字重复的严格编译参数（-84
  行）收敛到根 `build.gradle.kts` 的 `subprojects { plugins.withId(
  "java-library") }` 钩子，行为逐字等价（执行期 javac 参数 init 脚本实测
  `[-Xlint:all, -Werror]` + rawtypes 探针反证）；积压所记"另 3 处 app/accel
  变体"经全仓核实不存在，已作积压勘误。
- **设备文案 ✅**：qemu-android.yml:531 确为真实机型名；门禁放行原因=机型名
  在 device_identifier 规则族覆盖范围之外（口径外而非白名单）——已改稳定别
  名 `device-a`（API 35 保留），gate 复扫 OK。
- **P2-10 后半 ⚠️ defer（有依据）**：verification-metadata 生成成功（1m05s，
  413 components/726 checksums）但 aapt2 经 AGP detached configuration 缺条
  目，且文件 Windows-only（linux 条目=0）——提交会打破 ubuntu CI；按预案删
  文件记 defer，维护者三方案（CI 跨平台生成/`<trusted-artifacts>`/
  verify-signatures）已写入报告。
- 报告：`docs/reports/tickets/small-fixes-batch/implementation-report.zh-CN.md`

### Lane N — 性能优化方案 + 空 catch 分诊
- 方案：`docs/research/2026-11-05-performance-optimization-plan.zh-CN.md`
  ——13 条提案分 5 组，每条含实测证据+出处、量化收益+置信度（高/中/低）、
  成本风险、验证方法（零设备可自验 vs 需真机）、依赖；26 行证据底座总表
  E1–E26。**三条最高性价比**：
  1. **C1 fabric 派发宽度定案（零代码）**：QEMU-TCG 面按 2 vCPU 记账、宽并
     行任务不派 QEMU（T4 S(4)=1.42 不达、S(8)=1.12 反噬、T9 S(2)=1.60@n=10
     双设备复核、翻译税中位 92%，全部实测在案，只差落进编排决策）。
  2. **B1 编译主路径定为 payload 进程内 libclang**：真机 protected VM 基线
     p50 98 ms/编译 vs guest TCG 单文件 32,750 ms（n=1）；distcc 网络卸载三
     判据至今未验证，只配当可选增强。
  3. **B3 worker 池容量旋钮 0→N**：池与分派器已接线 JVM 全绿，容量出厂 0=
     今天行为（`ProtectedWorkerPool.java:150`），打开只差一个产品监听起点调
     用点与容量决策。
- 空 catch 分诊 Addendum（quality-backlog 报告末尾，不回改历史）：抽样 22
  处（16 文件/6 模块）→ 5 处建议修复（共性"失败不可见"）、15 处建议注释、
  2 处维持现状；约 3/4 存量可按 4 类模板机械闭合。
- 关键发现：P3-1 正文承诺的 78 处附注清单实际未随报告入库（本轮脚本重建并
  验证）；dv-pvm-02b 报告顶部推翻声明使其 distcc 数字作废（方案中明确禁引）。

## 3. 整合与验证

- 五分支顺序合入（零冲突）；敏感门 3 处 worktree 绝对路径 → 修正后
  `SENSITIVE_DATA_OK`。
- **集成冒烟（合并后 main，ASCII worktree）**：`:app:testDebugUnitTest` +
  `:runtime:instance:check` + `:runtime:contract:check` BUILD SUCCESSFUL
  1m19s；app 实测 **1171 tests / 0 failures / 0 errors**（=1129 基线+38 票
  27+4 温池）。

## 4. 未完成 / 不适用

- 依赖校验（P2-10 后半）defer，理由与三方案见 Lane M 报告。
- 12 个 >800 行大文件拆分（P3-2）：**主动不做**——无真机 UI 验证的高风险重
  构，留设备窗并行推进时再做。
- 空 catch 全量 78 处闭合：本轮只完成分诊，机械闭合留后续轮（模板已给出）。
- 票 27 的设备窗尾巴：真实 QEMU 事件原件、AVF 样本（依赖票 07）、诊断开关
  UI 接线开销实测。
- Guest assets 修复的最终验证以 push 后 run 为准；v0.6.2/v0.6.3-preview.1
  重剪 tag 待维护者。
- `test_avf_device_mode` 24 项 Windows 宿主红的 CI 对齐分诊。

## 5. 风险

- P1-5 温池重构改了租约/重试语义的内部结构（外部 API 不变）；并发回归已锁
  行为，真机长跑仍未验证。
- Guest assets fixture 修复依赖 fuser 语义（held 探针不变）；push 后 run 是
  最终裁决。
- 严格编译参数收敛若后续新模块未 apply java-library 钩子将不受 -Werror 管
  辖（已在报告标注防范点）。

## 6. 后续动作

1. push 后观察 CI：重点 Guest assets（fixture 修复后应转绿）；绿后从绿
   commit 重剪 v0.6.x tag 的决策交维护者。
2. 性能方案落工单：C1（零代码、只需编排决策）最先；B3 需设备窗给出产品监
   听起点；B1 需 distcc@36 判据数据。
3. 下一轮：空 catch 机械闭合（4 类模板）、静态检查（P2-3/4）、诊断开关 UI
   接线（票 27 尾巴）。
- 是否越界：各 lane 均在边界内；未触发 Architecture Change Policy。

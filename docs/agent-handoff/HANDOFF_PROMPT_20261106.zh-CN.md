# 交接开工 Prompt（2026-11-06 · 新会话专用）

> 本文件是新会话的开工指令。按 §1→§2→§3 顺序完成 onboarding，再按 §4 生成
> 任务详细指示呈报用户，经确认后按 §5 的工作流执行。全程遵守 §3 硬约束。

## 0. 使命与三段式工作方式

1. **Onboarding**：从 §1 的报告清单建立项目全貌（不要凭记忆，逐份读）。
2. **任务指示**：按 §4 从现状推导候选任务，写出 agent-ready 的详细任务指
   示（What to build / Acceptance criteria / 阻塞边 / 文件边界），**呈报用
   户确认后再动手**；用户直接点名任务时跳过推导直接细化。
3. **执行**：按 §5 选择合适工作流，开启子智能体并行完成；收尾走 §6 固定
   动作链。

## 1. 必读报告清单（按序读，标注每份要提取什么）

| # | 文件 | 提取 |
| --- | --- | --- |
| 1 | `AGENTS.md` | 治理入口、当前源码状态行、当前决策地图三线、文档跟踪规则 |
| 2 | `docs/agents/issue-tracker.md` | tracker 惯例、2026-11-04 对账快照、各带日期 Comments 节 |
| 3 | `docs/reports/quality-backlog-20261104.zh-CN.md` | 21 项 P0–P3 积压现状 + 末尾空 catch 分诊 Addendum（注意其中部分项已落地，落地状态以 §2 快照为准） |
| 4 | `docs/research/2026-11-05-performance-optimization-plan.zh-CN.md` | 13 条提案、26 条证据底座、各节"落地标注"；禁引 dv-pvm-02b 的 distcc 数字（该报告顶部有推翻声明） |
| 5 | 五份轮总报告（按时间序） | `docs/reports/optimization-parallel-20261104.zh-CN.md`、`device-window-and-hardening-20261105.zh-CN.md`、`zero-device-closeout-and-perf-plan-20261105.zh-CN.md`、`c1-and-quality-closeout-20261106.zh-CN.md`、`b2-a3-longtask-optimization-20261106.zh-CN.md`——每份的 §2 实际完成 + §6 后续动作； lane 级细节按需下钻 `docs/reports/tickets/<id>/` |
| 6 | `docs/research/ACR-draft-four-lanes-20261029.md` | ACR §8 现状（16K 宿主负样本已补、distcc@36 是唯一技术缺项） |
| 7 | `.scratch/orchestella-uml-fabric/map.md`、`.scratch/round-20261104-device-window/spec.md` | 当前三线 effort 的台账（fastlane/platform map 仅历史入口） |
| 8 | `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及目录内全部 policy | 硬约束原文 |

## 2. 当前状态快照（截至 2026-11-06，以实读复核为准）

- **基线**：main `e647be1` 已推送（origin 同步）、CI 五条 workflow 状态绿
  （Guest assets 为路径过滤触发）；版本 0.6.2/code602；`:app` 单测
  **1207/0**；敏感门全绿。
- **战略定位**：用户长期无法提供 non-protected 设备 → **UML 线是虚拟化性
  能优化主路径**（已在 protected-only 真机完成 T1–T11 验证）；AVF 保持时
  间盒研究线；QEMU 全设备可靠可用优先。
- **设备**：device-myron（别名；HyperOS OS4.0 / Android 17 API 37 / SM8850
  档 / gunyah / **protected-only** / 4K 宿主）。真实序列号/型号只在仓外
  `evidence/dv01a-20261104/`。
- **已落地（近期五轮）**：CI 连红根因修复（allowMissingPayload 三处豁免）、
  PVM-06 worker 池接线（`WorkerPoolDistccDispatcher`）、P0-3/P1-5 并发收口、
  票 27 生产启动阶段记录器（默认关零采集）+ 设置页诊断开关 UI、Guest
  assets 双根因修复（现绿）、28 处 action pin SHA、C1 宽度定案
  （`FabricWidthDecision` + dispatcher 接线，QEMU 面按 2 记账默认启用）、
  80 处空 catch 归零、lint 收敛 830→33、B2 `FabricOrchestrator` 编排层（三
  面宽度之和恒等账本，UML 面默认 0）、A3 provision 收口（产品层经审计已由
  e130897 覆盖，补 17 例 JVM 契约测试）。
- **性能方案 13 条零设备项已全部出清**；剩余全部依赖设备窗或维护者裁定。
- **待维护者四件**（用户点头即做）：ACR 投票（唯一技术缺项 distcc@36）、
  v0.6.x tag 从绿 commit 重剪（v0.6.2/v0.6.3-preview.1 剪在坏 commit）、
  Gradle 依赖锁定专项（跨平台生成策略）、生产 keystore。
- **设备窗待办（device-myron 可做）**：distcc@36 真机端到端（ACR 最后一项）、
  票 27 真实 QEMU 事件原件采集（正常+受控失败）、C1/B2 真机长跑与多面并发
  验证、P1-5 温池长跑。
- **open 票**：约 58 张（P13/27 已闭环）；仍 open 的关键票与分组见 tracker
  对账快照。

## 3. 硬约束（违反即返工）

**敏感红线（最高优先）**：真机序列号/IMEI/Android ID/设备指纹及其哈希、真
机截图/录屏/完整设备日志、真实 IP/MAC/个人邮箱/本机绝对路径、凭据永不入
库；设备一律稳定别名（device-a/b、device-myron），型号用粗粒度口径；原始
证据放仓外 `evidence/<topic>-<date>/`。敏感门 `python tools/ci/
check_sensitive_data.py --range origin/main..HEAD` 是提交前的强制门——**它
连 commit message 都扫**（worktree 绝对路径进消息就会红），报告正文同理写
"ASCII 路径 worktree"。

**构建环境（Windows Git Bash）**：
- 中文路径主检出**不能跑单测**（ClassNotFoundException）：单测一律在纯
  ASCII 路径 git worktree 跑（`git worktree add /d/t/orch-<tag> main` +
  复制 `local.properties`，跑完 remove）；未提交改动用 cp 覆盖进 worktree
  验证；subst 盘符也可。
- `:app` 相关 gradle 任务带
  `-Porchestella.assets.allowMissingPayload=true`（干净 checkout payload 缺
  件的既定豁免，发布门未放松）；单命令 ≤5 分钟；cd 用绝对路径；python 不
  是 python3；git show 带路径前缀 `MSYS_NO_PATHCONV=1`。
- `docs/` 被 .gitignore：动 docs 下文件必须 `git add -f`；docs 下**未跟踪
  被 ignore 的文件**普通 status 看不见，盘点用 `git status --ignored`。
- `commit.gpgsign` 已在仓库层关闭；commit 风格参照 git log（`类型(scope):
  中文描述`）。

**流程**：
- 子智能体并发上限 ≈2——**每批最多派 2 个**，超派会 "user concurrency
  limit exceeded"。
- 并行 lane 必须**预分文件边界**（各 lane 独占文件集写进任务书），全部在
  各自 ASCII worktree + 独立分支上工作，编排层统一合并。
- 跑 gradle 时编译冻结输入：不要在长构建中途改源。
- 报告规范 `docs/agent-handoff/REPORTING_STANDARD.zh-CN.md` 必填项全写（背
  景/scope/完成/未完成/修改文件/测试/验证结果/commit/branch/风险/后续/越
  界/ACP）；**禁止虚报**——没跑的测试写 not run。
- 工单惯例：Status 用 `Status:` 行；追加记录用带日期 `## Comments` 节；
  **不回改历史快照**；票面 Status 不可信，以 git log + 实施报告为准。
- 工作区清理：**核查（diff/引用检查）必须独立成步并经确认，严禁与 rm 串
  联**（曾误删两个会话记忆变体）；`2026-10-30-20-52-32/` 内含用户 Word 文
  档永远不清理；`build/llvm-android`、`app/build` QA APK、payload 构建树不
  删。
- 设备 quirks（device-myron）：am start 前 force-stop；**HyperOS freezer 在
  VM 创建后 ~5s 冻 QA app 整 uid，deviceidle 白名单不豁免，亮屏+前台
  （TOP_APP）可绕行**；doze 掐网；推送文件 md5 对账；禁止用卸载绕过空包查
  询残留。

## 4. 任务指示生成要求

从 §1/§2 推导候选任务时，按三档标注并排序：
1. **零设备可做**：当前已出清；若新出现（如用户提出），按既有 lane 范式。
2. **设备窗可做（device-myron）**：§2 设备窗待办四项是现成候选，优先推荐。
3. **需维护者裁定**：§2 四件 + ACR 投票 + 12 个 >800 行大文件拆分（主动
   defer：无真机 UI 验证的高风险重构）。

每个任务产出 agent-ready 工单（写进 `.scratch/<effort>/issues/` 或直接在
呈报中给出）：What to build / Acceptance criteria（可验证）/ 阻塞边 / 文件
边界 / 验证方式（零设备 JVM 或真机步骤）。**呈报用户确认后再执行。**

## 5. 工作流选择

- **默认范式（已验证）**：任务工单化 → 分 lane（≤2 子智能体/批）→ 各自
  ASCII worktree+分支 → 编排层合并（冲突手工解+YAML 校验）→ 敏感门 →
  ASCII worktree 集成冒烟 → push → `gh run list` 确认 CI → 轮总报告入库
  （`docs/reports/<topic>-<date>.zh-CN.md`）→ 更新项目记忆。
- 用户明确说"工作流/workflow"时：用 dynamic-workflows 技能走
  CreateWorkflow（编排多子智能体）。
- 设备任务：先 `adb devices` 清点并重建别名映射；证据入仓外 evidence/；
  工单与报告只写脱敏摘要；用完的设备侧临时授权（stayon/deviceidle 白名
  单）必须解除。

## 6. 收尾固定动作链（每次执行完）

1. 合并全部分支 → 敏感门（含对 lane 报告里 worktree 路径的修正）。
2. ASCII worktree 集成冒烟（app 全量单测 + 受影响模块 check），测试数与各
   lane 报告对账。
3. push → `gh run list` 确认相关 workflow 绿（注意路径过滤型 workflow 不
   触发属正常）。
4. 轮总报告（REPORTING_STANDARD 全必填项）+ `.scratch` 工单回填 +
   `docs/reports/README.md` 索引行。
5. 更新项目记忆（MEMORY.md 索引 + 轮记忆文件）。

## 7. 默认建议

用户未点名任务时，建议默认任务 = **设备窗（device-myron）**：distcc@36 真
机端到端 + 票 27 真实 QEMU 事件原件采集 + C1/B2 真机长跑——零设备侧已无
可独立推进的优化项，这三件分别闭合 ACR 最后缺项、票 27 设备尾巴、UML 主
路径编排验证。

# 开工 Prompt：纯 JVM 加深重构轮（2026-11-10 · 新会话专用）

定位：承接 2026-11-09 大优化轮（六 lane 全合并，main 1339 绿）与 2026-11-10 敏感
审计轮，按架构走查报告
（Windows 临时目录下 `architecture-review-20261110-055212.html`，
若已被清理则按 §2 状态快照重建）执行**纯 JVM 加深重构轮**：不依赖设备、不改
UI 行为、以深模块化 + 契约测试为目的。

## 0. 使命

把架构走查的三个 Strong/Worth 候选落成深模块（候选 2 → 1 → 6 顺序），
MainActivity 从 2227 行显著收缩，反射测试消除，KeepAlive 执行序获得全 JVM
覆盖。全程不碰 res/、不碰 UI 行为、不碰设备。

## 1. 前置阅读（按序）

1. `docs/agent-handoff/HANDOFF_PROMPT_20261106.zh-CN.md`——硬约束/工作流/收尾链以此为准。
2. 架构走查报告（路径见上；candidates 1/2/6 全文）——候选细节、行号证据、
   放弃项判词以它为准。
3. `docs/reports/grand-optimization-20261109.zh-CN.md` 与
   `docs/reports/optimization-closeout-20261108.zh-CN.md`——基线与既有判词。
4. `docs/adr/`（ADR-0020 进程所有权与锁序、ADR-0011 前台可靠承诺——候选 1/6
   的硬边界）与根目录 `CONTEXT.md`（领域词汇）。
5. 项目记忆：orchestella-grand-optimization-20261109、orchestella-public-repo-privacy-leaks。

## 2. 状态快照（2026-11-10 收口时点，新会话必须重建核对）

- main @ 1355e08c+（敏感清洗 2f3bb555 与消息重写两次 force push 后的基线；
  **SHA 已全变**——引用旧 SHA 一律查
  `evidence/rewrite-backup-20261110/KEY-SHA-MAP.md`），:app **1339/0/0**、
  lint 0e、CI @ d56cbdb5 全绿。以实读 git log / gh run list 为准。
- 已知偏差：ec387e05 前后数个 CI run 因 GitHub runner 供给瞬断出现
  startup_failure 缺 job（三 job 已分别验证通过；startup_failure 不可 rerun，
  用空提交触发新 run 收口）。
- 设备 device-nova 可能在线（HONOR/API36/protected-only；pVM 面勿碰）。
  本轮为纯 JVM 轮，设备不必要。

## 3. 任务清单（T1–T4；每步完成即回填）

T1 · InstanceLaunchCoordinator（候选 2，Strong）
  What：把 MainActivity.launchInstance（约 254 行）的编排——注册表检查、
  InstanceLaunchGuard、前台服务、mirror/firstboot 文件 IO、provision 装配、
  策略选择——拔出为深模块（输入 instance+资源句柄，输出固定 LaunchOutcome）；
  MainActivity 收缩为 UI 状态机；失败文案直接吸收 ErrorCopy 单源。
  Acceptance：launchInstance 相关路径全 JVM 契约测试（对齐
  InstanceLaunchSequenceTest 范式）；MainActivity 只减不改（UI 行为零变化）；
  :app 全量 ≥1339/0/0 只增不减；lint 0e。

T2 · ControlChannelLifecycle（候选 1，Strong；依赖 T1 的注入通道）
  What：把 Session 的 connect/recover/retire 两阶段协议（锁序=ADR-0020 第 7 条）
  抬为具名协作对象；Session 留壳保 API；**重开「Session 反射字段锁定」判词的
  理由=接口化≠拆类**（ADR-0020 锁序显性化而非违反）。
  Acceptance：ControlChannelRecoveryTest 改 fake lifecycle 驱动，删除
  getDeclaredField/setAccessible 与外部持锁；BridgeClient.authenticationToken
  经测试 seam 注入；锁序成为接口契约测试。

T3 · UmlKeepAliveSession（候选 6，Worth）
  What：UmlFaceKeepAliveService 的匿名闭包（ticker 生命周期/grantStartedMs/
  幂等/STOP 先通知后停服/tick 异常吞掉）提为具名类（注入 clock+wakelock 接口），
  service 只留 start/stop 两行 glue。
  Acceptance：fake WakeLock+手动 clock 全 JVM 覆盖 HOLD/RENEW/STOP 三分支
  与异常路径；ADR-0011 判词背书注释保留。

T4 · 收尾
  What：轮报告（REPORTING_STANDARD 全必填）+ 工单回填
  （`.scratch/round-20261110-deepening/issues/`）+ README 索引 + 记忆更新 +
  worktree 清理（核查与 rm 严禁串联）。build 前清旧包残留。

## 4. 硬约束增量（其余全按 HANDOFF §3）

- 禁碰区：res/ 全部、startup/ 既有收口件（RuntimeVerification/DashboardSnapshot
  结构不动）、TerminalMux（不拆判词）、engine-avf/（ACR）、ErrorCopy 族（只消费）、
  ModernTerminalView 内部渲染、ControlChannelRecoveryTest 的**断言语义**（可改
  驱动方式，断言不减）。
- 范式纪律：壳层保 API（InstanceFileStore/MetadataCodec 范式）；每个新模块
  deletion test 论证写进工单；「视图接线归 UI 辖区」判词继续有效（本轮不做
  候选 3/4——它们归 UI 重构轮）。
- 文件边界预分：T1 MainActivity+startup 相邻件；T2 QemuInstanceLauncher+
  contract 测试；T3 UmlFaceKeepAliveService+WakeLockPolicy（只消费）。三任务
  串行或分 lane 时 MainActivity 归 T1 独占。

## 5. 工作流

默认范式：任务工单化 → 分 lane（≤2 子智能体/批；T1 单独一批先行，T2/T3
第二批可并行——T2 依赖 T1 的注入通道）→ ASCII worktree+独立分支 → 编排层
合并 → 敏感门 → ASCII worktree 集成冒烟（:app 全量）→ push → gh run list
确认 CI → 轮报告 → 记忆。每批构建前清旧包残留。

## 6. 交付定义

1. 轮总报告 docs/reports/deepening-20261110.zh-CN.md（全必填项）。
2. 三个新深模块的文件清单+契约测试数+MainActivity 收缩行数对比。
3. 反射测试消除证明（getDeclaredField in test 归零或仅存合理项）。
4. 工单回填 + 索引行 + 记忆更新。

## 7. 待维护者决策项（本轮不动，仅跟踪）

- 历史 blob purge（型号 SKU token 仍在历史 blob 中；HEAD 已清洗+门禁已加固；
  purge=第三次 filter-repo+force push+GitHub Support，决策权在维护者）。
- GitHub Support 请求（10-19 遗留的旧 ref/缓存清理草稿在
  `Orchestella/artifacts/rewrite-backup-20261019/`）。
- DV-01B @ 消息重写后基线未重跑（push 带 paths 过滤；下次代码 push 自然重验）。
- UI 现代化重构轮（候选 3/4 + B/F token 深化）排期。

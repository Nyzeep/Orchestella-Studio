# Artifact & Repository Organization Policy

> 状态：项目级 active policy ｜ 版本：1.0 ｜ 生效日期：2026-09-07
>
> 本文是 DroidLayer 后续 Agent Session 的 artifact 与 repository organization 唯一规范。
> 它只约束未来新增文件和产物；不要求本次或未来会话大规模移动、重命名或重写已有历史文件。

## 1. 权威边界

本策略只定义“文件应该放在哪里、如何命名、是否进入 Git”。它不能覆盖：

- `AGENTS.md` 的项目工作约束；
- `ENGINEERING_HANDOFF.md` 的事实优先级；
- `HOST_ENVIRONMENT_POLICY.md` 的宿主机路径、写入和清理规则；
- tracker、ADR、spec、CONTEXT 或产品代码的事实与权限边界。

报告类型和必填字段仍以 `REPORTING_STANDARD.zh-CN.md` 为准；本文件只补充报告之外的
目录、证据和生成产物组织规则。任何同类事实只能有一个 canonical document，其他位置只
能引用它。

## 2. 当前仓库的职责地图

以下是当前仓库已经存在或明确预留的职责边界。预留目录在真正需要前不必创建。

| 内容类型 | Canonical home | 规则 |
| --- | --- | --- |
| Android / guest-assets / runtime production source | `app/`、`guest-assets/`、未来明确的 source module | 生产源代码和与其同目录的测试留在 source tree；不要把报告、日志或实验代码放进 source module。 |
| Tool source、lock、构建定义 | `tools/<tool-name>/`，例如 `tools/qemu-android/` | 只放可审计的源码入口、lock、manifest、脚本和许可证记录；下载的 archive、toolchain、binary 和 cache 不放这里。 |
| Accepted architecture / product decision | `docs/adr/`；领域词汇为根目录 `CONTEXT.md` | ADR 是正式决策，不是随手笔记、研究草稿或实现报告。 |
| External factual research | `docs/research/` | 官方文档、source analysis、compatibility investigation、benchmark research 等归档在此；不要另建 `research/` 或 `docs/investigation/`。 |
| Ticket implementation / verification | `docs/reports/tickets/<ticket-id>/` | 实施报告、scope contract、dispatch packet 和 ticket-specific verification 资料按 ticket 归档。 |
| Review | `docs/reports/reviews/<ticket-id>/` | review report 与 review-only 结论归档在此，不复制到 ticket report。 |
| Prototype / experiment report | `docs/reports/prototypes/<ticket-id>/` | 原型结论、实验环境、原始观测和触发器判定归档在此；不把原型报告当作 implementation report。 |
| Prototype / experiment source | `prototypes/<prototype-name>/` | 实验代码必须带有明显的 prototype 名称，可包含 `README.md`、`src/`、`scripts/`；不得混入 `app/`、`guest-assets/` 或未来 production module。 |
| Orchestration / Agent governance | `docs/agent-handoff/` | handoff、model routing、worker contract、execution、parallelization、host policy 和本文件均归档在此。 |
| Orchestration report | `docs/reports/orchestration/` | 编排审计、readiness、治理更新和 delegation 报告归档在此；不要放到 handoff policy 目录中。 |
| Tracker / spec / decision map | `.scratch/orchestella-platform/` | 这里只放本地 tracker、spec、map 和 issue history；不要用报告或 research 文件替代 tracker 状态。 |
| Existing development guide | `docs/development/` | 保留已有 developer guidance；它不是 research、ADR、正式报告或 Agent policy 的替代目录。 |
| Small source-controlled evidence | `artifacts/<ticket-id>/` | 只提交小型、可审查、可重现、已脱敏的 manifest、checksum、raw benchmark JSON 或必要日志片段；目录按 ticket 归属。 |

`docs/architecture/`、`docs/reference/`、根目录 `research/` 和根目录临时脚本目前不是
canonical home。不要为某个 session 临时创建它们；确有新职责时先升级本策略或明确引用
已有 canonical home。

## 3. Source-controlled evidence 与 ephemeral output

### 3.1 可以进入 Git 的内容

只有满足以下条件的证据才进入仓库：

- 对 review、复现或验收有长期价值；
- 体积适合 Git，内容稳定且可追溯；
- 不含私钥、token、设备序列号、用户数据或其他秘密；
- 能通过 ticket、commit、命令或输入 lock 说明来源；
- 放在 `docs/reports/...` 或对应的 `artifacts/<ticket-id>/`，而不是根目录。

报告是解释层，artifact 是证据层。不要把同一份 raw output 复制到报告、research 和
ticket 三处；报告应链接或引用 canonical artifact。

### 3.2 不进入 Git 的内容

以下内容必须放在 approved external resource root 或工具要求的明确 user-level 位置，
不得随意写入 source tree 或仓库根目录：

- `build/`、Gradle cache、编译中间产物和临时 APK；
- 下载的 toolchain、source archive、VM image、container layer 和 package cache；
- 大型 binary、完整 device log、core dump、临时 QEMU/AVF 数据和实验工作目录；
- 临时脚本、一次性转换结果和未经审查的 generated files。

当前项目 approved external resource root 由 `HOST_ENVIRONMENT_POLICY.md` 定义为：

`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer`

可按用途使用 `external/`、`tools/`、`caches/`、`artifacts/`、`test-runtimes/`、
`vm-images/` 或 `temp/` 等明确子目录，但必须带有项目用途、ticket/owner 和清理预期。
针对外部资产的生命周期、复用与清理，必须严格遵守 `ASSET_REUSE_AND_HYGIENE_POLICY.md`
确立的三级分类法：禁止误删 SDK 工具链、内核/rootfs 底包与 WSL 构建沙箱等 Class A 永久复用资产。
`.gitignore` 只是防止误提交的安全网，不是把临时文件写入仓库的授权；不要依赖“可能被
忽略”来决定路径。
涉及 `C:`、user-level runtime 或系统资源时，仍必须遵守 Host Environment Policy 的
disclosure 和最小范围规则。

### 3.3 构建与运行证据

- source-controlled 的 lock、manifest、provenance、checksum 和小型结果摘要放在其
  owning tool/ticket 的 canonical home；例如 QEMU 输入 lock 属于 `tools/qemu-android/`。
- 真实 build、device、benchmark 的大型或敏感原始输出放在
  `D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\artifacts\<ticket-id>\` 对应目录；正式报告只记录路径、命令、摘要和是否可复现。
- 若某个外部产物后来成为发布或验收所需的长期证据，先保留 raw artifact 的来源和 hash，
  再将经过审查的最小证据提升到仓库 `artifacts/<ticket-id>/`，不要直接把整个输出目录提交。

## 4. 命名规则

优先沿用当前仓库已经生效的 convention，不为“统一外观”重命名历史文件：

- Research：`docs/research/YYYY-MM-DD-<topic>.md`；沿用现有中文或英文 topic 风格。
- ADR：`docs/adr/NNNN-<decision-name>.md`。
- Ticket report：`docs/reports/tickets/<ticket-id>/implementation-report.zh-CN.md`；已有
  `worker-scope-contract.zh-CN.md`、`dispatch-packet.zh-CN.md` 等固定文件继续沿用。
- Review：`docs/reports/reviews/<ticket-id>/review-report.zh-CN.md`。
- Prototype：`docs/reports/prototypes/<ticket-id>/prototype-report.zh-CN.md`。
- Orchestration：`docs/reports/orchestration/<topic>.zh-CN.md`。
- Benchmark evidence：`artifacts/<ticket-id>/evidence/YYYY-MM-DD-<device>-<benchmark>.json`
  或同 basename 的 `.md` 摘要；device 使用可公开的稳定别名，不写入不必要的序列号。
- Manifest / checksum：使用产生它的 tool、ticket 或发布流程规定的固定文件名；不要创建
  `final`、`new`、`copy`、`v2` 等无法表达来源的并行副本。

Canonical policy、ADR 和 handoff 文件使用已有的稳定大写文件名；不要按 session 日期复制
出另一份 policy。

## 5. Before creating a file：新建文件前的强制判断

任何 Agent 在创建文件或目录前，必须依次执行：

1. 阅读 `AGENTS.md`、本策略、相关 handoff/governance policy 和当前目录结构；ticket 工作
   还必须阅读对应 ticket、scope contract 与报告规范。
2. 将文件分类为 production source、ADR、research、ticket report、review、prototype、
   governance、tracker、source-controlled evidence 或 generated output。
3. 查找该类别已有的 canonical file；能更新现有文件就不新建同概念副本。
4. 对 ticket artifact 选择包含 ticket id 的路径，并在 dispatch packet 中写明 report、
   evidence、temporary output 的位置、owner、retention 和 cleanup 责任。
5. 判断是否进入 Git：小型、脱敏、可审查证据才可提交；generated、cache、下载物和大型
   output 默认放外部 approved root，并在报告中记录实际路径。
6. 如果归类、归属、保留期限或是否应提交无法确定，停止创建，向 Orchestrator 升级；不要
   通过新增目录来绕过不确定性。

## 6. Ticket、Worker 与 Child 的额外约束

- Ticket 相关的报告、evidence 和实验输出原则上必须能通过 ticket id 找到；不得把 Ticket
  26 的资料放到无 ticket 归属的通用目录。
- Worker 只能写入 dispatch packet 和本票 scope 允许的 source/report/artifact 路径；
  Child 只能写入 packet 明确指定的 supporting-output 路径，并把结果交回 parent。
- 新增 prototype 必须在名称和目录上保持实验性质；prototype 不能直接变成 production
  source，也不能用实验结果替代验收或 ADR。
- 生成物的路径、hash、来源、命令、环境、是否可复现和清理结果必须记录在相应报告中；
  不把 cache、下载物或临时 VM 状态伪装成 source-controlled evidence。

## 7. 历史文件与变更纪律

本策略生效时不迁移、不重命名、不复制现有 research、ticket reports、orchestration reports
或历史上下文。已有文件继续按其历史路径作为记录；只有未来新增文件必须遵守本策略。

如果未来确需改变 canonical home、引入新的 artifact class 或调整保留规则，应只修改本文并
在变更中说明迁移/兼容策略；不得由单个 Worker 静默建立第二套目录体系。

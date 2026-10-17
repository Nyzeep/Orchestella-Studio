# 中文成果与开发文档

主分支源码配置为 **0.5.3 / code503**（`main b0a167c`，2026-10-18）。公开安装包仍为 [v0.4.5 开发预览版](https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.4.5)；源码同步不代表新安装包已发布，也不代表全部工单已完成。

## 当前状态

| 维度 | 当前事实 |
| --- | --- |
| 源码 / 公开包 | 源码 0.5.3 / code503；公开 release 仍为 v0.4.5 开发预览版 |
| 发布签名 | v0.4.5 与 0.5.3 验证包均为 Android Debug 证书；生产签名与数据迁移是下一公开版本的门 |
| 测试 | 747 项 App 测试 / 98 suites；lint 0 Error / 748 Warning；65 项 Guest 检查；8 项 Android 框架场景 |
| 运行时 | QEMU 是当前唯一有真实设备证据的完整 Linux 路径；QEMU / gvproxy 停止收敛、跨 Controller 单实例互斥仍是 P0 可靠性工作 |
| AVF | 真实 AVF Guest 成功样本为 **0**；当前测试 ROM 只声明 protected，non-protected 不适用；pKVM 未证实，存在 Gunyah 节点 |
| 16 KB | ELF / APK 对齐已验证（`zipalign -c -P 16 4` 通过）；16 KB 真机运行未测 |
| Android 17 | API 37 已稳定；项目 targetSdk 36，`ACCESS_LOCAL_NETWORK`、大屏方向、IME 可见性、static final 反射限制是 2026Q4–2027H1 适配窗口 |
| CI | 当前只门禁 `:runtime:contract:check` 与 `:app:testDebugUnitTest`；runtime 其余模块、lint、assemble 与 16 KB 检查尚未全部进入门禁 |

> 本次维护（2026-10-18）：已加入跨 Controller 状态串行化与并发契约测试，修复同一目录多个 `FileStore` 下的单实例竞态；详见[维护与优化 Prompt](reports/orchestration/optimization-prompt-20261018.zh-CN.md)。P0-1 生产签名、P0-2 QEMU/gvproxy 收敛、P0-4 输入与 P0-5 CI 门禁仍未完成。

## 当前交付与路线

1. [Orchestella 下一阶段优化方案（2026-10-18）](reports/orchestration/next-optimization-plan-20261018.zh-CN.md)：当前优先级。主线是 QEMU 全设备可靠可用；发布签名、运行时收敛、单实例锁、输入与 CI 门为 P0；AVF protected 是时间盒研究线，不阻塞发布。
2. [0.5.3 限定实现、构建与真机验收报告](reports/orchestration/avf-shizuku-device-20261016.zh-CN.md)：747 项 App 测试、lint 0 Error / 748 Warning、8 项 Android 框架验收，以及独立 Debian QA 的 QEMU / systemd / 日志与停止结果。
3. [源码与中文成果同步（2026-10-17）](reports/orchestration/github-source-sync-20261017.zh-CN.md)：0.5.3 可靠性改进与 Protected AVF 规划的公开同步记录。
4. [Protected 优先近期方案（2026-10-17）](reports/orchestration/protected-avf-near-term-plan-20261017.zh-CN.md)：Protected 专项门、阶段门与停止条件；其总体优先级已被 2026-10-18 方案重排，保留作 protected 证据要求。
5. [工单地图与依赖图](../.scratch/orchestella-avf-shizuku/map.md)、[规格](../.scratch/orchestella-avf-shizuku/spec.md)、[任务范围](../.scratch/orchestella-avf-shizuku/scope.md)。**建立工单不等于实现完成。**
6. [VM 所有权与 FD 边界 ACR-01](../.scratch/orchestella-avf-shizuku/issues/ACR-01-vm-ownership.md)、[保护意图与隔离语义 ACR-02](../.scratch/orchestella-avf-shizuku/issues/ACR-02-protection-semantics.md)：仍未决；本次没有新增生产 VM owner 或自动授予权限入口。

## 研究依据

- [Shizuku / AVF 综合研究](research/2026-10-15-avf-shizuku-upgrade.md)。
- [当前测试设备的 AVF / Gunyah 能力重检](research/2026-10-17-avf-device-recheck.md)：系统仅声明 protected，不能推导芯片完全没有硬件虚拟化；AVF 框架不等于 pKVM 后端。
- [Custom Linux、AOSP、ADB/vsock 与参考项目路线调研](research/2026-10-17-avf-custom-linux-route-study.md)：固定源码、教程与作者经验分层；protected 引导与 App 私有根盘仍需独立验证。
- [构建、CI 与开发预览发布可靠性](research/2026-10-15-build-release-reliability.md)。
- [v0.4.2 终端与运行时下一阶段的有限优化](research/2026-10-15-terminal-runtime-next-phase.md)。
- 各票的 [实施报告](reports/tickets/) 与 [独立审查](reports/reviews/)。历史报告中的版本、未提交和设备状态按当时快照理解。

真实本应用 **AVF Guest 成功样本仍为 0**，本次没有 AVF 性能提升声明。平台 feature、权限、模式声明、Microdroid、合成 FD 和 QEMU 验证都不能互相替代。

## 文档分类

| 目录 | 内容 | 说明 |
| --- | --- | --- |
| [`adr/`](adr/) | 18 份已接受架构决策 | 索引见 [`adr/README.md`](adr/README.md)；ADR-0015 缺失，禁止按标题补造 |
| [`research/`](research/) | 当前有效研究 | 旧 Phase 5 专项已移入 [`research/archive/`](research/archive/README.md) |
| [`reports/orchestration/`](reports/orchestration/) | 当前主线方案与阶段报告 | 以 2026-10-18 方案为第一入口 |
| [`reports/tickets/`](reports/tickets/)、[`reviews/`](reports/reviews/)、[`prototypes/`](reports/prototypes/) | 当前 AVF / Shizuku 工单、最终轮审查与原型 | 首审与前轮复审已移入归档 |
| [`reports/archive/`](reports/archive/README.md) | Phase 5 / 质量合流 / v0.4.5 / 被取代的 AVF 审查 | 历史快照，不代表当前版本 |
| [`standards/`](standards/) | 版本命名等标准 | `VERSIONING.md` 为唯一现行版本标准 |
| [`agents/`](agents/) | 本地 tracker、triage 与领域文档约定 | 当前 effort 见 [`issue-tracker.md`](agents/issue-tracker.md) |
| [`agent-handoff/`](agent-handoff/) | 现行治理、证据与宿主约束 | 防御性硬约束见 [`DEFENSIVE_CONSTRAINTS/README.md`](agent-handoff/DEFENSIVE_CONSTRAINTS/README.md) |
| [`agent-handoff/archive/`](agent-handoff/archive/README.md) | 已被取代的会话交接与 Prompt | 不代表当前任务状态 |

## 代码与验证入口

- [Guest 生成与严格字节校验](../guest-assets/scripts/sync-guest-init.py)：清洁检出先使用 `--prepare-resource`，再使用只读 `--check`，不要先覆盖 canonical 来掩盖漂移。
- [设备与样本工具说明](../tools/device-validation/README.avf-validation.zh-CN.md)：Python 3.12+；固定查询、受限路径、显式外部输出根。样本工具只校结构与关联，不认证设备物理真实性。
- [隔离 QA 原型](../prototypes/avf-shizuku-identity-probe/README.zh-CN.md)：研究组件和真实产品路径分开，QA 通过不签收原产品升级或 Full Linux 硬件运行。
- [本地构建](../README.md#本地编译与构建)。完整设备或 Linux fixture 使用独立外部工作目录，保护用户实例，不自动更改系统策略或重新安装工具链。

## 公开内容与本地证据约定

本次只跟踪经过范围审查的源码、测试、中文报告、规划与 tracker Markdown；其他开发者文档和临时资产继续默认忽略。`/docs/` 仍默认被 `.gitignore` 忽略，经审查的文档必须显式 `git add -f`，不要对 `docs/`、`.scratch/` 或项目外证据目录执行整体强制添加。

文档中的 `PROJECT_ROOT` 指开发根，`SOURCE_ROOT` 指活动源码检出，`LOCAL_EVIDENCE` / E / R / H / P 等为报告定义的本地证据别名。公开文本已去除具体开发者路径和设备标识。原始设备 receipt、截图/完整日志、ROM 指纹、签名材料、用户实例/根盘、embedded initrd、构建缓存及 APK 不在本次源码提交中；本地证据引用可能没有公开文件链接，这不代表已上传或可以推导新的通过结果。

部分历史治理/交接仍记录旧工作名、旧阶段或当时决策；本次公开用于解释约束与来源，不把历史建议自动改为新 accepted 架构。尤其 ADR-0010 关于 pVM 双向通信的旧绝对表述已发现与官方 vsock 证据冲突，见 [ACR-02](../.scratch/orchestella-avf-shizuku/issues/ACR-02-protection-semantics.md)；本次保留 accepted 正文和待裁定状态，不以公开同步代替架构批准。ADR 中旧镜像规格不代替当前固定清单与实际输入哈希。已知缺失文档的来源保全见[治理调查](../.scratch/orchestella-avf-shizuku/issues/16-governance-source-recovery.md)，不根据引用标题编造正文。

公开发布新的二进制需要另行执行版本、签名、完整对应源码、许可证/NOTICE 与验收门；本次提交和普通 push 不授权创建 Release 或上传安装包。

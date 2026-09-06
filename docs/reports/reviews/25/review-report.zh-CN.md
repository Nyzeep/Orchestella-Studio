# Review 报告：Ticket 25

- 审查对象：`ticket/25-guest-assets`，固定基线
  `f523f4b1216fcf1cfe6f6436c7ce6d59c3027965`，审查命令
  `git diff f523f4b...HEAD`。
- 最终复审 HEAD：`7045b0c5665e025c1cc12009500eb67bc39f5a9c`。
- 审查者：Codex Orchestrator + Standards / Spec 两个只读 review child。
- 审查方式：规范轴与 Spec 轴并行独立检查；review child 未修改文件、未启动
  VM/container、未重跑 build/test，也未接触 Ticket 26。

## Standards

**PASS**

- Hard/blocking findings：0。
- Non-blocking judgement calls：2。
- 初审发现原聚合实现 commit 过大，不符合
  `docs/agent-handoff/TICKET_EXECUTION_GUIDE.md` §5 的“小步提交”。实现随后按
  verification tooling、Guest Agent、asset build、negative/reproducibility gates、
  QEMU smoke、CI、文档和 executable modes 拆为八个逻辑 slice，并以独立行政
  commit 提交 tracker/report；定向复审确认该阻断项已解决。
- `guest-assets/run-in-builder.sh:8` 与 `guest-assets/ci/run.sh:10-38` 存在可能的
  **Repeated Switches**；这是 judgement call，不是文档化规范违反。
- `guest-assets/build.sh:66-357` 同时承载多个上游构建与 packaging，存在可能的
  **Divergent Change**；这是未来维护性风险，不构成当前验收风险。
- 当前为消除两项 smell 重构已真实验收的构建路径，会扩大 Ticket 25 review 修复范围并
  要求重跑高成本 gate；依据“不得顺手重构”，两项均不阻断 approve。
- 其余检查通过：修改均在 allowed modules 或行政例外内；未修改 forbidden modules、
  ADR/spec、CONTEXT、其他 ticket；报告披露、token 边界与 commit 命名符合工程规范。

## Spec

**PASS — 0 findings**

- 固定输入与可复现：scope contract `:62-72,101-102` 对应
  `guest-assets/inputs/inputs.lock:2-28`、`guest-assets/build.sh:24-61,301-357`、
  `guest-assets/scripts/check-reproducible.sh:13-33`。
- verify-or-fail：Ticket 25 `:10,17` 对应 `guest-assets/build.sh:70-105`、
  `ConfigVerifier.java:19-32`、`check-config-rejection.sh:13-42`。
- QEMU Provisioning：Ticket 25 `:18`、scope contract `:82,104` 对应
  `guest-assets/initramfs/init:28-90` 与 `qemu-provisioning-smoke.sh:33-120`；覆盖输入
  校验、格盘、解包、token/agent 安装、完成标志及磁盘回读。
- Guest Agent/token 边界：scope contract `:21-25,80,105` 对应
  `guest-assets/agent/guest-agent.c:32-96` 与 `GuestAgentProcessTest.java:21-93`；没有
  实现宿主 transport、终端或 MVP 命令集，Ticket 26 边界保持完整。
- 许可：scope contract `:106`、ADR-0014 `:8-11` 对应
  `guest-assets/GPL-SOURCES.md:8-75`、`guest-assets/SOURCE-OFFER.txt:1-20` 与
  `guest-assets/build.sh:313-357`。
- 范围审计：diff 仅涉及 `guest-assets/**`、允许的 workflow、`.gitignore` 和
  Ticket 25 行政文件；未发现遗漏要求、错误实现或 scope creep。

## 测试真实性与覆盖评估

- targeted-tests、config-negative、两个独立 clean reproducibility builds、final-build
  与 QEMU AArch64 smoke 均有实际结果和本地原始证据；review 没有用静态推断替代这些
  验收。
- 主 runner 的 `15-full-verify.exit=125` 没有被误写为全流程单进程 `rc=0`。结论由连续
  stage ledger（前四项 `rc=0`）与同一环境的
  `16-qemu-smoke-resume.exit=0`、console/disk assertions 共同成立。
- 原真实验收聚合快照为 `75c9f891e4685439589b0cc0dc4b71a89d29ad7b`。拆分后通过
  `git diff --exit-code 75c9f89 5447d47 -- .github/workflows/guest-assets.yml .gitignore guest-assets`
  确认已验收实现的内容与 executable modes 均未变化，因此没有重复已经通过的 gate。
- 覆盖与四项 acceptance criteria 一一对应；未发现以历史不同实现代替当前代码的情况。

## 暴露面检查

- Guest Agent 仅实现 guest-side 最小握手、版本协商和 token 校验；没有新增
  guest→host 能力、宿主 transport、终端通道或白名单外命令。
- token 不进入可复现产物、Git 或日志，由 smoke 在实例装配时随机注入；未发现公开常量
  凭据。
- 未触发 Architecture Change Gate。

## 结论

**approve**。Ticket 25 的实现、真实验收、scope 与报告满足关闭条件，可以由
Orchestrator 使用 `--no-ff` 集成到 `main`。两项非阻断 smell 仅作为维护性观察保留，
不授权后续 ticket 顺手重构。

汇总：Standards 0 个 blocking findings（2 个非阻断 judgement calls，最显著为
`build.sh` 的 Divergent Change）；Spec 0 findings（无最坏问题）。

报告人：Codex Orchestrator + Standards / Spec review children ｜ 日期：2026-09-07 ｜ 耗时：未单独计时

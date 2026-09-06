# Host Environment Policy 更新报告

- 变更基线：`b9df782`（Ticket 25 已 review、集成并关闭后的 `main`）。
- 变更性质：独立的项目级 Agent 环境策略更新；不属于产品或架构变更。

## 1. 新增内容

新增 `docs/agent-handoff/HOST_ENVIRONMENT_POLICY.md`，固化六类规则：default project
workspace、`C:` drive、tool-mandated user directories、system-level modification、
pre-modification disclosure 与对所有 Agent/child 的适用范围。

仅在 `docs/agent-handoff/ENGINEERING_HANDOFF.md` §14 文档入口索引新增一行 Policy
入口，未改动该文档其他内容。新增本报告用于记录理由、路径边界与 scope audit。

## 2. 为什么需要

Ticket 25 的真实 Linux/QEMU 验收证明，外部工具可能强制创建用户级 runtime 资源；同时，
项目源码、产物、缓存、VM 镜像与临时文件不应因便利而散落在系统盘。新 Policy 将默认
项目路径、工具强制例外、系统级 approval gate、事前 disclosure 和 child 权限边界统一为
长期可审计规则。

## 3. 允许的路径

- 当前代码工作区：
  `D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev`。
- 项目相关外部资源根：`D:\Development project(开发项目)\DroidLayer`；仅可用于具有
  明确 DroidLayer 用途的 `external`、`tools`、`caches`、`artifacts`、
  `test-runtimes`、`vm-images`、`temp` 等资源。

固化前只读核对显示，第一条为实际 Git toplevel；用户文案中的 `DroidLayer\_dev` 已按
转义后的实际目录名归一化为 `DroidLayer_dev`，避免未来 Agent 误建 shadow workspace。
第二条获准资源根在核对时尚不存在，本次没有创建它。

## 4. 默认禁止的路径与操作

项目源码、构建/测试产物、缓存、VM 镜像和临时文件不得默认落到 `C:\...`。Windows
Registry、system `PATH`、services、drivers、`Program Files`、system-wide package、
firewall、OS-level virtualization features 与 security policies 默认保持不变；确实不可
避免时必须先完整披露并取得用户对具体操作的明确批准。

无关系统文件、私钥与凭据不属于读取、输出或修改范围。

## 5. 工具强制例外与 crx.vmdk 归类

当工具确实要求且无法合理重定向时，允许最小 user-level runtime resource 使用其强制
目录，但必须记录 path、purpose、无法使用项目目录的原因、created/modified resources、
overwrite、cleanup 与 host impact。

`C:\Users\Nyzee\.vctl\bin\crx.vmdk` 属于该例外：VMware `vctl` 强制使用，Ticket 25
执行前目标不存在，创建时未覆盖；终态大小 196,608 bytes，SHA256
`aa2ca5154f7775371c2ef9e6d5dbb3eab49b1b4a367e0a92e1e92c93e7bce556`。vctl runtime
已停止；文件及工具管理的用户级 runtime state 保留，预期使用 VMware/vctl 支持的流程
清理，不直接破坏其状态。

## 6. 未修改的现有内容

以 `b9df782` 为本阶段基线，本次没有修改：

- 产品定义、`.scratch/droidlayer-platform/spec.md`、`CONTEXT.md`；
- `docs/adr/**`；
- ticket graph、ticket dependency、任何 ticket acceptance criteria 或 Ticket 26；
- `docs/reports/tickets/25/worker-scope-contract.zh-CN.md`；
- `docs/agent-handoff/AGENT_WORKER_CONTRACT.md`、
  `REPORTING_STANDARD.zh-CN.md`、`ARCHITECTURE_CHANGE_POLICY.md`；
- 既有历史报告、research 与 model selection policy；
- Ticket 25 已关闭的 tracker、实施报告和 review 报告。

## 7. 越界验证

提交前以 `git diff --name-status b9df782 --` 和 `git status --short` 核对变更集合。完成
标准是相对基线恰好出现以下三个路径，且 `git diff --check` 返回 `rc=0`：

1. `docs/agent-handoff/HOST_ENVIRONMENT_POLICY.md`（新增）；
2. `docs/agent-handoff/ENGINEERING_HANDOFF.md`（仅 §14 新增一行索引）；
3. `docs/reports/orchestration/host-environment-policy-update.zh-CN.md`（新增）。

本阶段未启动 build、VM、container 或 Ticket 26，也没有执行新的宿主环境写入。

报告人：Codex Orchestrator ｜ 日期：2026-09-07 ｜ 耗时：未单独计时

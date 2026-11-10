# 05 · U4 Warm-start / Snapshot 可行性 · 开工 Prompt

你是 **U4 会话的研究者**。目标：判断 UML 是否能 checkpoint/restore、hibernation、预热点池；给出 go/no-go、内存成本与产品含义。**本会话不出产品代码**。

## 0. 必读（按顺序）

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/spec.md`（§3 U4）
3. `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`（T5 warm resume 的定义）
4. `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1
5. `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §5（长驻/kill 重启）
6. Linux UML HowTo（mconsole/stop/go）
7. `https://github.com/Raymer8639/linux-um-arm64` README
8. CRIU 官方文档/源码（是否支持 UML，需核实）

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/u4 \
  -b feat/uml-u4-warm-start \
  github/integration/uml-zero-device-20261110
cd /workspace-wt/u4
```

- 只改本分支；不改 `main`；不合并；不 push 到 main。
- 只写 `docs/research/2026-11-XX-uml-warm-start-feasibility.zh-CN.md`（必要时加一个最小 host 原型目录，但不写产品代码）。
- **依赖**：`08-wave1-integration.md` 已 push `integration/uml-zero-device-20261110`；未 push 不要开工。
- **可用输入**：U1 `docs/research/2026-11-10-uml-fastboot-profile.zh-CN.md` 与 `research/uml-fastboot/out/`（如存在）、U2 `docs/research/2026-11-10-uml-rootfs-prebake.zh-CN.md`。

## 2. 必须回答的问题

1. UML 上游/下游是否有 checkpoint/restore、hibernation、save/restore 机制？
2. `mconsole` 的 `stop`/`go`/`config` 等实际能做什么？是否存在 `save`/`restore` 或等价机制？是否保留 guest 内存/进程状态？
3. CRIU 是否支持 UML？如果不支持，是架构性不支持还是缺补丁？
4. 预热点池（pre-warmed pool）能否作为替代？每个 warm 实例的内存/CPU/启动成本是多少？
5. 与 Android FGS/freezer 的交互：warm 实例在后台被冻结/杀死时如何恢复？
6. 对产品承诺的影响：T5 warm resume 是否可作为 fast path 的一部分？

## 3. 工作流

1. **`/research`（研究子智能体，1 个）**：
   - 一手来源：Linux `arch/um` 源码/文档、`mconsole` 文档、CRIU 文档/issue、Raymer README。
   - 输出：机制表 + 可行性矩阵 + 来源；不要写代码。
2. **`/prototype`（可选，主会话）**：
   - 只有在宿主可复现且不写产品代码时，做一个最小 host 原型（如 `mconsole` stop/go 或 pre-warmed pool 计时）。
   - 原型放 `research/uml-warm-start/`，标明 throwaway。
3. **`/code-review`（验证子智能体）**：
   - 检查每个结论是否有来源/原型；检查“未找到”是否被误写成“不可行”。
   - 不改文件；只报问题。
4. 写决策 memo。

## 4. 子智能体分工

- **研究子智能体（1 个）**：查 `arch/um`/mconsole/CRIU/预热点池；输出证据表。
- **验证子智能体（1 个）**：独立复核关键来源与结论，特别是“不可行”的判词。
- **主会话**：写 memo、必要时做原型、提交。最多 2 个并发子智能体。

## 5. 交付物

- `docs/research/2026-11-XX-uml-warm-start-feasibility.zh-CN.md`
- 如做原型：`research/uml-warm-start/README.md` + 脚本/日志（不写产品代码）
- 结论：`GO` / `NO-GO` / `CONDITIONAL`，以及每个结论的证据等级。

## 6. 验收

1. 每个关键判词有一手来源或原型证据；没有证据的写 `unknown`，不写“不可行”。
2. 明确区分：checkpoint/restore、hibernation、pre-warmed pool 三种路径，不混称。
3. 给出内存/CPU 成本的量级或公式；没有数字就写“待实测”。
4. 明确与 FGS/freezer 的交互。
5. 不写产品代码、不改版本号/签名/发布通道。
6. 敏感数据门通过。

## 7. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "docs(research): 评估 UML warm-start/snapshot 可行性"
git push -u github feat/uml-u4-warm-start
```

Handoff 必须包含：branch/base/head、结论（GO/NO-GO/CONDITIONAL）、关键来源、原型结果、内存/CPU 量级、not-tested、风险、对 U1/U2 的依赖。

## 8. 停止条件

- 需要 Root/unlock/改 SELinux/改 pvmfw → 停止并记录。
- 需要写产品代码才能继续 → 停止，等 ACR。
- 找不到 checkpoint/restore 证据 → 不要直接判“不可行”；写 `unknown` 并给验证路径。

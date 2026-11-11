# 07 · U6 多实例规模化 · 开工 Prompt

你是 **U6 会话的实现者**。目标：在宿主上测 N 个 UML 实例并存的 RSS/CPU/启动/端口/磁盘，给产品多实例上限的零设备输入；Android 侧只列设备窗口清单，不伪造结论。

## 0. 必读（按顺序）

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/spec.md`（§3 U6）
3. `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`（syscall/内存/启动）
4. `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §5、§7.1.6（长驻/freezer/RSS）
5. `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T8②（60min FGS 驻留）
6. `docs/research/2026-11-05-pvm-fabric-route.md` §2.2（资源账本/内存叠加）
7. `runtime/instance/.../LaneBudgetLedger.java`（只读账本语义）

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/u6 \
  -b feat/uml-u6-multi-instance \
  github/integration/uml-zero-device-20261110
cd /workspace-wt/u6
```

- 只改本分支；不改 `main`；不合并；不 push 到 main。
- 不写产品代码；只动 `tools/research/uml-multi-instance/`、必要的 docs/research。
- **依赖**：`08-wave1-integration.md` 已 push `integration/uml-zero-device-20261110`；未 push 不要开工。
- **可用输入**：U1 `research/uml-fastboot/out/`（fast kernel/rootfs，若存在）、U2 `research/uml-rootfs-prebake/`（Alpine x86_64 rootfs/manifest）。

## 2. 范围

**做：**
- host benchmark：N=1/2/4/8 个 UML 实例，记录 RSS、CPU、T3 启动时间、端口、磁盘占用、失败模式。
- 内存/CPU 账本公式与保守上限；与 `LaneBudgetLedger` 的语义对齐（只读，不改代码）。
- 设备窗口清单：Android LMK/FGS/freezer/端口冲突/存储配额；标 `not-tested`。
- 报告：方法、夹具、数据表、上限建议、风险。

**不做：**
- 不写产品代码、不改 `LaneBudgetLedger`/启动器/网络守护。
- 不把 host 数据外推为 Android 结论；设备项必须 `not-tested`。
- 不承诺“支持 N 实例”；只给输入与上限建议。
- 不引入第三方依赖；用 shell/Python 标准库。

## 3. 工作流（按顺序）

1. **`/implement`（主会话）**
   - 写 `tools/research/uml-multi-instance/bench.sh` + `parse.py`（或等价）；先跑 N=1/2，再扩到 4/8。
   - 每次运行记录：夹具、内核 config、rootfs sha、内存上限、宿主负载、温度（如可得）。
2. **`/code-review`（验证子智能体）**
   - 独立跑 N=1/2 复现、检查数据表/公式、检查是否把 host 当设备。
   - 不改文件；只报问题。
3. **可选 `/research`（研究子智能体，1 个）**
   - 核实 Android LMK/FGS/freezer/进程数限制的一手来源，给设备窗口清单；不写代码。
4. **提交前门**：`git diff --check` + 敏感数据门。

## 4. 子智能体分工

- **验证子智能体（1 个）**：独立跑 benchmark + 审阅数据/公式；不改文件。
- **研究子智能体（可选，1 个）**：Android 资源限制一手来源；不写代码。
- **主会话**：写脚本/报告、合并结论、提交。最多 2 个并发子智能体。

## 5. 交付物

- `tools/research/uml-multi-instance/bench.sh`
- `tools/research/uml-multi-instance/parse.py`（含 `--self-test`，若有解析逻辑）
- `docs/research/2026-11-XX-uml-multi-instance-scaling.zh-CN.md`
- 设备窗口清单（可放报告 §）

## 6. 验收

1. host 数据表覆盖 N=1/2/4/8（至少 1/2/4）；每项有 n、中位数、夹具、失败数。
2. 内存/CPU 账本公式有输入与假设；与 `LaneBudgetLedger` 语义对齐。
3. 设备项全部 `not-tested`，并给命令/入口。
4. 不写产品代码；`git diff --name-only github/integration/uml-zero-device-20261110..HEAD | grep -E '^(app/|runtime/|guest-assets/)'` 无输出。
5. 敏感数据门通过。

## 7. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "feat(research): 增加 UML 多实例规模化基准"
git push -u github feat/uml-u6-multi-instance
```

Handoff 必须包含：branch/base/head、N 数据表、内存/CPU 公式、失败模式、设备窗口清单、not-tested、风险、对 U1/U2 的依赖。

## 8. 停止条件

- host 资源不足跑不到 N=4/8 → 记录 N=1/2 数据与缺口，不伪造。
- 需要产品代码/账本改动 → 停止，交 ACR/协调者。
- 需要真机才能判断 LMK/FGS → 标 `not-tested`，不要猜。

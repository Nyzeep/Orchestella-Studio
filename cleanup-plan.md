# 仓库与工作区整理计划（会话 9 · 阶段四）

> 口径来源：`ASSET_REUSE_AND_HYGIENE_POLICY.md` 的 Class A/B/C 分层，外加一个「需维护者裁定」层。
> 原则：**先出清单后动手；宁可少删不可误删；任何「需裁定」项不静默删除。**

## 0. 基线（动手前实测）

| 指标 | 值 |
| --- | --- |
| 仓库根（含 `.git`） | **4.1 GiB** |
| `.git` | 18 MiB（`size-pack` 14.58 MiB，`garbage: 0`、`prune-packable: 0`） |
| 跟踪文件数 | 1658 |
| `build/` | 2.3 GiB |
| `app/build/` | 1.6 GiB |
| `.gradle/` | 4.5 MiB |
| 工作区根合计 | 62 GiB 实占 / 196.9 GiB 表观（93% 在非 git 的 `Orchestella/`） |

仓库本体几乎全是可重建的 `build/`（3.9 GiB），Git 历史只有约 15 MiB，**没有 gc 收益空间**。

## 1. 确定可删（本次执行）

| # | 路径 | 体积 | 跟踪 | 处置 |
| --- | --- | --- | --- | --- |
| 1-1 | `.gradle/` | 4.5 MiB | 0 | 删除（Gradle 本地缓存，可重建） |
| 1-2 | `.kotlin/` | 0 | 0 | 删除（空目录） |
| 1-3 | 5 处 `tools/**/__pycache__/` | ≈234 KiB | 0 | 删除（字节码缓存） |
| 1-4 | 工作区根 `2026-10-25-20-52-55/` | 4.0 KiB | 仓库外 | **先并入记忆再删**——内含一份独立会话日志（会话 5 / PVM-03），已追加到 `.workbuddy/memory/2026-10-25.md` |
| 1-5 | 工作区根 `2026-10-26-09-04-26/` | 8.0 KiB | 仓库外 | 同上，会话 8 的 02b 硬事实已并入 `2026-10-26.md` 与 `MEMORY.md`；目录已清空，**空目录残留**（回收站助手拒绝移除，0 字节，无收益） |

> 小计可回收约 **4.8 MiB**。这部分不含任何需要重建的产物。

## 2. 本会话暂缓删除（属构建产物，但删除有真实代价）

| 路径 | 体积 | 暂缓理由 |
| --- | --- | --- |
| `app/build/` | 1.6 GiB | 内含**唯一一份已装机的 QA APK**（`Orchestella-Studio-v0.5.3-qa.dvpvm.af763f3.dirty.apk`，324 MiB）。阶段二归因正在用这个包，删掉即失去本轮对照组。重建代价：分钟级。 |
| `build/pvm-compiler/`、`build/payload-02b/`、`build/payload*/` | ≈230 MiB | Gradle 打包输入与 payload `.so`，同上，分钟级可重建。 |
| `build/llvm-android/` | **2.1 GiB** | libclang 22.1.8 三库交叉构建产物。**重建代价是数小时级**，且本机 WSL 被安全策略禁用。技术上是构建产物，但把「数小时」和「分钟级」两种产物合并成一条「可删」是口径错误，故单列到 §4 交维护者裁定。 |

结论：**若追求「体积下降有数字」而删 §2，本轮报告体积会从 4.1 GiB 降到约 0.3 GiB；但代价是下一轮必须重新交叉构建 libclang。**本会话不做这个取舍。

## 3. 归档不删（`git mv` → `docs/reports/archive/`）

三个批次共 **30 个目录 / 39 个文件 / ≈380 KiB**，全部**完全跟踪**（`git ls-files` 计数与 `find -type f` 计数逐一相等，无未跟踪文件），且 `docs/reports/README.md` 未引用它们。

| 批次 | 目录数 | 文件数 | 体积 | 判定依据 |
| --- | --- | --- | --- | --- |
| `tickets/runtime-performance-01..08/` | 8 | 10 | 76 KiB | 全部 `ready-for-human`；报告 §7 实测数据为 `not-tested`，无真机数字；已被 10-21 Fast Lane 与 PVM 线取代 |
| `tickets/fastlane-P01..P12/` | 12 | 19 | 224 KiB | 标题均含「（零设备）」，P01 报告原文「没有运行 adb、没有连接真机」；已合入 main，设备门由 DV-01/02 接管 |
| `tickets/avf-zero-device-01..10/` | 10 | 10 | 80 KiB | 批名即 zero-device；对应 `.scratch/orchestella-hw-virt` 10 张票全部 `resolved` |
| 合计 | **30** | **39** | **≈380 KiB** | 收益是「当前有效」目录变干净，不是省盘 |

目标路径：`docs/reports/archive/2026-10-zero-device/tickets/<原目录名>/`，并在 `archive/README.md` 增一行批次说明。

> 体积收益可以忽略；本条的价值是索引卫生。若维护者认为「零设备报告仍要能被一眼找到」，这条可以整批回退（`git revert` 单个归档提交即可）。

## 4. 需维护者裁定（只列清单，本次不删）

| # | 项 | 量 | 为什么不自己决定 |
| --- | --- | --- | --- |
| 4-1 | `build/llvm-android/` | 2.1 GiB | 见 §2：数小时级重建，删了等于给下一轮加一道墙 |
| 4-2 | `.scratch/` 旧 tracker：`orchestella-hw-virt`（13 文件）、`orchestella-phase5`（13）、`runtime-performance`（18）、`p0-2-runtime-supervisor`（7）、`quality-phase5-integration`（5）、`release-readiness`（1）、`orchestella-platform`（68，其中 #17/#18 仍 open） | 213 文件 / 1.5 MiB | 被 `.gitignore` 忽略但 **195 个已跟踪**；`orchestella-platform` 仍有 open 票。按 gitignore 判「可丢」会误伤 |
| 4-3 | 工作区根 12 份 `*-Prompt-*.md` 交接文档 | ≈123 KiB | 在仓库之外，**无版本保护**；建议把会话 1–8 归档进 `docs/agent-handoff/archive/`，保留会话 9 |
| 4-4 | `docs/reports/` 下 9 个「磁盘有、索引无、被 `/docs/` 忽略」的文件 | — | `git mv` 会失败（源不在索引）；`git clean -fdx` 会**直接删掉**它们，其中含真机验证报告；保住它们需要 `git add -f`，而本轮规则禁止 |
| 4-5 | `phase5-references/` | 215 MiB / 23 个独立 git 仓库 | 第三方参考快照，与本项目源码无关但可能被历史报告引用 |
| 4-6 | 12 个第三方上游 clone（`termux-app`、`Shizuku`、`LinuxDeploy`、`proot-distro`、`UserLAnd`、`AndronixOrigin`、`DroidSpace`、`Podroid`、`Claw`、`termux-x11`、`Droidspaces-rootfs-builder`、`Shizuku-API`） | ≈213 MiB | 只作参考，删了要重新 clone 且可能对不上当时的版本 |
| 4-7 | `Orchestella/`（非 git 产物目录） | **58 GiB 实占 / 192.5 GiB 表观** | 稀疏文件重灾区，按表观数字决策会误判 5 倍；含 WSL 沙箱（20 GiB）与离线 Android 工具链（4.9 GiB），政策明列 Class A 永久保全 |
| 4-8 | 分支侧未竟工作 | 见会话 9 报告 §5 | `feat/pvm-09-facts`、`feat/pvm-09-manifest` 含 main 中不存在的实现；5 个 `backup/*` 是 353/20/18/6/3 个提交的唯一 ref |

## 5. 绝不动

- `.git/`（含 config、hooks、reflog）
- `LICENSE`、`NOTICE.*`、`ASSEMBLY_EXCEPTION.*`、`third-party-notices.txt`
- 签名与密钥相关的一切
- `docs/reports/` 里的证据与哈希（所有 `implementation-report.zh-CN.md`、`matrix-dry-run.tsv`）
- `GLOSSARY.md`、`CONTEXT.md`、`AGENTS.md`、`docs/adr/`
- 现役工单：`.scratch/orchestella-pvm-compiler/`、`.scratch/orchestella-accelerator/`、`.scratch/orchestella-fastlane/` 及其决策地图
- 工作区 5 个长期保持 `M` 状态的规划文档（AX-02、AX-08、两份决策地图、P09）

## 6. 执行顺序与提交切分

1. **删除提交**：`.gradle/`、`__pycache__`、`.kotlin/`（均未跟踪，无需 commit，只记录）+ 本文件 `cleanup-plan.md` 入库。
2. **归档提交**：30 个目录 `git mv` → `docs/reports/archive/2026-10-zero-device/`，加 `archive/README.md` 索引行。**与删除分开 commit**。
3. 每次提交前过敏感门 `python tools/ci/check_sensitive_data.py --range main..HEAD`。

# 编排报告：Ticket 26 已提交历史映射与重建采纳决策（2026-09-20）

- 背景 / scope：用户批准“保全优先、从最新 main 重建唯一实施线”。本报告在独立、严格
  只读审计后，记录 original 与 canonical **已提交**历史的最小功能域映射，以及新实施线
  对这些历史的采纳规则。它不审查、不引用或处置任一工作树的未提交内容。
- branch / 基线 / commit：本报告以 main 为唯一新实施基线。original、canonical 与
  follow-up 的精确 ref、绝对路径和原始 diff 仅在受控审计中保留；本报告不重复它们。

## 实际完成

1. original 中尚未进入 main 的 80 个已提交变化，按四个最小功能域映射到 canonical 的
   已提交历史：

   | 最小功能域 | canonical 已提交映射 | 证据强度 |
   | --- | --- | --- |
   | 基础契约：Distro、支持地板、实例正常生命周期、认证 Guest Bridge、策略中性 terminal | 5/5 | 逐提交 patch 等价 |
   | QEMU 运行资产、锁定供应链、native CI 与前置条件 | 17/18 | 17 项 patch 等价；1 项为同主题但文档补丁不同，须独立核对 |
   | 受控进程入口、Android 兼容性、可复现 native 构建 | 25/25 | 逐提交 patch 等价 |
   | resident second stage、OpenRC、PTY relay、QEMU smoke 与 CI 收口 | 32/32 | 逐提交 patch 等价 |

2. canonical 另有 22 项 original 中没有的已提交扩展，主题包括 manifest 下载验证、
   QEMU runtime facade、API 26 credential/启动认证顺序、Android runtime/terminal
   生命周期整合与 guest shell 静态检查修订。
3. 以上映射表明历史实现有可保全和复核价值，但不表明它们已进入 main、通过动态验证、
   符合 Spec/Standards、满足 GPL gate 或可直接合并。

## 新实施线采纳决策

| 历史功能域 | 新线处置 | 原因与强制规则 |
| --- | --- | --- |
| 四个 original→canonical 已映射域 | **重新设计/重新实现，不直接采纳** | 新线只从 main、Ticket 26、已批准 ADR/CONTEXT 与受限 dispatch packet 读取；禁止 merge、cherry-pick、复制历史 ticket 代码或把 patch 等价误作验证。 |
| QEMU 供应链、native CI 与可复现构建 | **保留为独立 review 候选** | 属于 Ticket 26 最终 gate，但必须在真实 Linux/QEMU 红色反馈建立后，按 supply-chain/Android review 和实际 gate 重新证明。 |
| canonical 的 API 26 credential 与启动认证扩展 | **冻结，独立安全 review 后再裁决** | 凭据持久化与认证顺序影响 token fail-closed 语义；不允许在诊断阶段默认为正确或作为隐式输入。 |
| QEMU runtime facade、App terminal/lifecycle 扩展 | **冻结，按当前 Ticket 26 scope 逐 slice 重建** | 必须先从 PTY 首字节到 terminal transcript 的实际症状建立 red-capable loop，随后以已批准 seam 做最小 TDD slice。 |
| manifest 下载验证、guest shell 静态检查修订 | **冻结，后续独立 review** | 它们可能有价值，但不能抢占诊断目标或替代产品/供应链验收。 |
| follow-up 与 canonical 的全部 WIP/冻结 delivery 草案 | **继续冻结，绝不作为新线输入** | custody 审计仅允许最小脱敏归属判断；没有授权给 implementation Worker 读取、修改或复用。 |

## 未完成内容

- 没有开展 Standards、Spec、安全、供应链、GPL 或动态 Linux/QEMU 审查；
- 没有运行 build、CI、APK、ADB 或真机验收；
- 没有将任何 historical change 合入 main、复用到新线、删除、reset、stash 或 clean
  任一历史 worktree；
- 还未登记 active Worker、canonical owner 和唯一获准的干净 Ticket 26 worktree。

## 修改文件

- docs/reports/orchestration/ticket-26-history-mapping-and-adoption-2026-09-20.zh-CN.md

## 模型、委派、workflow 或 worktree 影响

- 独立 Child 执行只读 Git-object 映射；没有读取 Ticket 26 工作树的 staged、unstaged
  或 untracked 内容；
- 新线只可在 owner、active Worker、worktree、受限 dispatch 与红色反馈门都登记后启动；
- Ticket 26 仍为 serial-only，不能同时派第二个产品 implementation Worker。

## 测试与验证结果

- 已验证：已提交对象的 patch-equivalence/主题映射，以及新线从 main 重建不存在 Git
  技术冲突；
- 未运行：产品、供应链、动态、真机和外部网络验证；
- 本报告提交前运行 git diff --check。

## 风险与后续动作

- 风险：历史线与新线在 bridge、credential、QEMU lifecycle 与 terminal 状态上有高语义
  重叠；若 Worker 直接借用历史代码，可能绕过红色反馈、独立审查和 current-main 约束。
- 后续：以本报告的采纳表为 dispatch 不变量，先登记唯一 clean worktree 与 Worker，再只做
  Linux/QEMU terminal relay 的 red-capable 诊断；只有该命令已经实际运行并稳定捕获
  sentinel 未达 relay，才允许转入最小实现。

## 越界与 Architecture Change Policy 检查

- 本报告未改变 ADR、spec、CONTEXT、Guest Bridge command set、Exposure Whitelist、
  tracker 状态或 dependency graph；
- mapping/adoption 是历史治理处置，不改变产品架构；Architecture Change Policy 未触发；
- 未发生写入历史 worktree、Git 状态改变、网络、构建、测试或设备操作。

报告人：Ticket 26 history-mapping Child / Codex Orchestrator ｜ 日期：2026-09-20 ｜ 耗时：未独立计时

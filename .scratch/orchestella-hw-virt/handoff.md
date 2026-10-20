# Handoff: AVF 零设备硬化

给新开发环境/并行 agent 的入口。先读 `map.md`，再读 `docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md`。

> **2026-10-20 合并完成**：本文中的开发分支已合并到 `main` `6d14087` 并删除；远端/本地只保留 `main`。新工作从 `main` 开始，下面的 branch/frontier 内容仅作历史追溯，不要再去 fetch 已删除分支。

## Branch / remote

- 远端：`git@github.com:Nyzeep/Orchestella-Studio.git`
- 交接分支：`avf/zero-device-hardening`
- 基线：`avf/mode-facts` @ `9f7790e`（票 23 的模式事实切片已在分支内）
- 每个工单从交接分支开：`git checkout -b ticket/<NN>-<slug> avf/zero-device-hardening`
- 集成分支建议：`avf/zero-device-integration`，按依赖顺序合并；最后一个工单是 10。

## Frontier（现在可并行）

`01` CI 全量门禁、`02` 所有权/停止、`04` 能力真相、`05` 平台访问、`09` 设备验收工具 dry-run。

之后：`02` → `03`；`04` → `06`；`03+04+06` → `07`；`02+03` → `08`；全部 → `10`。

## 硬约束

1. **零设备**：所有验收必须能在 fake host / fake vsock / JVM / URI fixture 上完成。不要等设备，不要把“真机验证”写进 Definition of Done。
2. **不声称 AVF 成功**：JVM/fake 通过只写“代码就绪”；真实 AVF sample 仍为 0。设备验收是下一个 effort。
3. **QEMU 冻结**：不改 QEMU 引擎功能；只允许 04 的 capability matrix 修正和 01 的 CI。
4. **不做 protected / shell-owner / H5 rig / 性能**：见 map 的 Out of scope。
5. **能力必须从接线派生**：没有真实 adapter 的能力一律 UNSUPPORTED；不得为了 UI 好看虚报。
6. **共享文件最小改动**：`AvfInstanceLauncher.java`（02/03/06/07/08）、`ReflectiveAvfVirtualMachineApi.java`（02/03/05/08）、`AvfCapabilityEvaluator.java`（04/05）。不做格式化，冲突在集成分支解决。
7. **测试先行**：每票先写 red，再最小 green；报告区分 fake/JVM 与真机未验。
8. **不泄露开发者数据**：不记录 serial/IMEI/MAC/账号/token/用户根盘；遵守仓库的敏感开发者数据策略。

## 常用命令

```bash
# 当前基线（Windows Git Bash；按项目既有环境执行）
./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :runtime:engine-avf:check :runtime:capability:check :runtime:distro:check \
  :runtime:network:check :app:testDebugUnitTest --no-daemon --stacktrace

# lint / assemble（qemu-android 已有，01 会提升为 PR/release 门）
./gradlew :app:lintDebug :app:assembleDebug --no-daemon --stacktrace
```

若某模块的 `check` 还没接线，先按工单 01 的 umbrella/CI 方案补；不要用 `-x test` 或关闭 lint 绕过。

## 交付物

- 每票：代码 + 测试 + 实施报告（`docs/reports/tickets/avf-zero-device-<NN>/implementation-report.zh-CN.md`，docs 被 ignore，按需 `git add -f`）。
- 票 10：集成守卫测试、票 23 的正式报告/双轴评审、设备验收清单、已知未验项。
- 推送：per-ticket 分支推到 `origin`；集成分支由集成 owner 推送；不要自行合并 main。

## 停止条件

- 任何工单需要真机才能完成 → 缩小到 fake 可验的接缝，把真机项写进 09/10 的“not-tested”清单，不要阻塞。
- 发现能力声明与实际接线冲突 → 以实际接线为准，标 UNSUPPORTED。
- 发现需要 protected/shell-owner/新权限架构 → 停，回到 map 的 Out of scope，交用户决策。
- 发现 QEMU 功能缺口 → 不在本 effort 修，记录到 10 的交接报告。

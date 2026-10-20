# 10: 集成守卫、票 23 收尾与交接报告

Type: implementation
Status: blocked
Priority: P0
Owner: integration owner
Blocked by: 01, 02, 03, 04, 05, 06, 07, 08, 09
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

前九票分散在不同分支；需要一次集成把行为拼起来，并用架构守卫锁住“能力/所有权/数据面/CI”不变量，避免后续 agent 又把声明改回旧状态。同时把票 23 的正式报告/双轴评审写完，给用户一个可交接的“零设备就绪”结论，明确哪些必须等真机。

来源：`.scratch/orchestella-hw-virt/map.md`；`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md`；票 23 的 `issues/23-*.md` 与现有 review 目录。

## What to build / 范围

1. 建集成分支 `avf/zero-device-integration`，按依赖合并 01–09；解决共享文件冲突（`AvfInstanceLauncher`、`ReflectiveAvfVirtualMachineApi`、`AvfCapabilityEvaluator`），不做格式化。
2. 架构守卫（可执行测试或脚本）：
   - AVF launcher 不 import `runtime.engineqemu`（除共享 manifest/bootstrap seam）；`QemuWaylandChannel` 不进入 AVF 生产路径；
   - `session.capabilityMatrix()` 与 UI/启动描述符一致；QEMU session 覆写矩阵；
   - 未接线的 audio/9p/forward 不出现在能力矩阵；`AvfWaylandChannel`/`AvfAudioChannel` 不在生产默认路径；
   - `useNetwork` 不是无条件 true；`ReflectiveHandle.waitFor` 不把 DELETED 当 0；`close()` 必停 VM；
   - CI 覆盖所有 `runtime:*` 模块（与 01 的守卫一致）。
3. 全量零设备门：
   ```bash
   ./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
     :runtime:engine-avf:check :runtime:capability:check :runtime:distro:check \
     :runtime:network:check :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
     --no-daemon --stacktrace
   ```
   以及 09 的 dry-run、16KB/ELF 检查（按 01 的接线）。
4. 票 23 收尾：
   - 写 `docs/reports/tickets/avf-shizuku-23/implementation-report.zh-CN.md`（或既有约定路径），覆盖模式事实/调用身份/诚实入口；
   - 双轴 review（Standards + Spec）同 id；
   - 勾选票 23 验收，状态改 completed；
   - 明确回答：App 内探针在 hidden API 受限时只能 UNKNOWN 的问题，是否已由 05 的豁免/typed state 解决；若未解决，留设备门。
   - 合并 `avf/mode-facts` 到集成分支；是否合并 main 由用户决定，本票只准备报告和分支。
5. 交接报告 `docs/reports/tickets/avf-zero-device-10/implementation-report.zh-CN.md`：
   - 代码就绪项（fake/JVM 通过）；
   - 明确 `not-tested` 清单（真机 create/run/vsock/stop/network/9p/display/audio/clipboard/performance/App death）；
   - 设备资格清单（research §3.3）与推荐候选；
   - 已知风险：HiddenApiBypass 脆弱、Android 17 crosvm_minimal、AVF 1 vCPU、RFB-only、protected 不可行；
   - 下一 effort 的入口：一台 non-protected 设备 + 09 工具。
6. 更新 map 的 Decisions so far / Out of scope，记录实际完成/未完成；不得把 fake 通过写成 AVF 成功。

## 行为验收（零设备）

- [ ] 集成分支所有零设备 check/lint/assemble 绿；没有跳测试/关 lint/`-x test`。
- [ ] 守卫测试能在故意回归时红（例如把 `useNetwork(true)` 改回无条件、把非 owner 当 CONFIRMED、把 audio 加回矩阵）。
- [ ] 票 23 报告/双轴 review/状态/验收完成；与代码一致。
- [ ] 交接报告与 map 一致；`not-tested` 清单完整；没有“AVF 已支持/已通过”字样。
- [ ] 09 的 dry-run 在集成分支可运行，输出 `not-tested`。
- [ ] 合并冲突已在集成分支解决；共享文件行为有回归测试。
- [ ] 分支/提交/远端推送信息写入报告，下一环境可直接接手。

## 测试 seam 与 red 命令

- 架构守卫可用 JUnit/ArchUnit（若项目已有）或 `grep` 测试；不引入重依赖。
- 故意回归验证：临时改一处（如非 owner 返回 CONFIRMED），确认守卫红，再撤回；记录命令/结果。
- 红命令：全量 Gradle 命令 + dry-run + 守卫测试；先让至少一个守卫在旧代码上 red，再在集成后 green。
- 若远端 CI 不可用，报告写“本地等价通过、远端未跑”，不得写成 CI 绿。

## 失败、数据与兼容

- 集成失败不得删用户数据/清缓存/卸载；只修代码。
- 报告不记录设备标识/用户根盘/屏幕内容/token。
- 不把 01–09 的失败藏进“已知问题”；失败的票要么修，要么明确 not-done 并阻塞交接。
- 不自行合并 main、不发布、不打 tag。

## 迁移、回滚与依赖

- 集成分支可回滚到 `avf/zero-device-hardening`；每票分支保留。
- 票 23 合并到 main 是用户/发布 owner 的决定；本票只准备。
- 依赖：无新第三方库；沿用各票依赖。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-10/implementation-report.zh-CN.md` + 票 23 报告/review。
- 完成 = 全量零设备绿 + 守卫有效 + 票 23 收尾 + 交接报告 + map 更新 + 推送集成分支。

## 不做

- 不做真机验收/性能/发布/合并 main；不做 protected/shell-owner/H5 rig；不修 QEMU 功能。

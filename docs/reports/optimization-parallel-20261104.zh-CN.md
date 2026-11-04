# 并行优化轮总报告（Optimization Parallel Round · 2026-11-04）

- 执行 session / 模型 / thinking：编排会话（5 个并行子智能体 lane）/
  GLM-5.3-Flash / 高
- 分支：各 lane 独立分支，已全部合入本地 `main`（基线 `1f4e0f4`，即远端 T9
  定案提交；**未 push**）
- 报告规范：`docs/agent-handoff/REPORTING_STANDARD.zh-CN.md`

## 0. 背景

用户要求：分析项目当前状态，找出可并行的优化项，开启多个子智能体并行完成
优化改进，完成后生成工作报告。启动时用户提及"后台存在构建"——实测：本机
无活动 java/gradle 进程、WSL 处于 Stopped；`build/ci/gradle-gate.log` 是
2026-11-03 的本地 gate 残留（BUILD SUCCESSFUL 2m54s，exit 0）。真正的问题是
**远端 CI 在 main 连续红**（`CI` 与两条 Fast Lane workflow，自 `46ce4e5` 起）。

## 1. scope

零设备可执行的五条并行车道；不含需要真机/维护者裁定的项（见 §6）。

| Lane | 分支 | 主题 |
| --- | --- | --- |
| A | `fix/ci-verify-runtime-assets` | CI gradle gate 连红修复 |
| B | `fix/fastlane-packaging` | Fast Lane zero-device / DV-01B 连红修复 |
| C | `feat/pvm-06-worker-wiring` | PVM-06 编译 worker 池接线 |
| D | `chore/repo-hygiene-20261104` | 工单对账、tracker、归档核对、release 分支建议 |
| E | `docs/quality-backlog-20261104` | 工程质量积压盘点（P0–P3 清单） |

## 2. 实际完成

### Lane A — CI 修复（根因 + 修复）
- **根因**：`46ce4e5` 把 `:app:verifyRuntimeAssets`（QEMU payload 缺件
  fail-fast，Q1 收敛产物）挂上 `preBuild`，但只给 ci.yml packaging 与
  release.yml 加了 `-Porchestella.assets.allowMissingPayload=true` 豁免，漏了
  ci.yml test job 的 gradle gate（`:app:testDebugUnitTest` 经 preBuild 拉起该
  任务，payload 三件全 git-ignore，CI 干净 checkout 必缺）。本地绿是因为主检出
  工作区存在 payload 文件。
- **修复**：`cae6c36` 在 ci.yml test job gate 补显式豁免（仅 workflow 文件
  +11 行）；发布侧 `check_runtime_payload.py --apk` 门禁未放松。
- 验证：worktree 内复现红（报错与 CI 日志逐字一致）→ dry-run 证明依赖链 →
  豁免转绿 + `runtime-assets-missing.flag` → gate 脚本转发 `-P` 实证 →
  `check_ci_module_coverage.py` 232 PASS / 自测 12 OK。
- 报告：`docs/reports/tickets/ci-verify-runtime-assets/implementation-report.zh-CN.md`

### Lane B — Fast Lane 修复（同根因第二、三处雷区）
- **判定**：run 41683970351 唯一红 job（APK bin/ injection and 16 KB
  packaging）与 run 41674445859 的 `jvm-androidtest` 红，均为
  verifyRuntimeAssets 同根因的**确定性回归**，非 flake、非设备门（历史 AVF
  socket / FastLaneProxyServerTest flake 本轮未出现）。事实更正：41674445859
  的 head 是 `777827e`（803f097 未触发该 workflow）。
- **修复**：`392b913` 给 `fastlane-zero-device.yml`（apk-bin-gate assemble）与
  `fastlane-dv01b-zero-device.yml`（jvm-androidtest）各补一处豁免。
- 验证：反向复现 FAILED 逐字一致；豁免后 `:app:assembleDebug` 48s 绿；
  DV-01B 全命令 1m46s 绿，`:runtime:engine-fastlane:test` 118/0。
- 报告：`docs/reports/tickets/fastlane-packaging-fix/implementation-report.zh-CN.md`

### Lane C — PVM-06 worker 池接线（部分接线，零设备最大范围）
- **方案**：接线缝为 `DistccTcpVsockBridge.ChannelFactory`——新增
  `WorkerPoolDistccDispatcher`（一条 distcc 连接 = 一次 `pool.acquire(job)`
  租约，租约恰一次归还；拒绝码映射 `WORKER_POOL_<CODE>`）；生产构造走
  `AcceleratorLaneAssembly.workerPool(...)`（与双车道共用账本，容量默认 0 =
  现行为逐字节等价）。
- **完成度**：分派器本体 + 生产构造缝 + `app` 主源
  `implementation(project(":runtime:fastlane-distcc"))`（零接线成因之一是模块
  方向断了）。**剩余缺口**：产品 TCP 监听起点（guest distcc →
  `bridge.start()`）按 PVM-09 报告 §5.4 划归 DV-PVM-02b 的设备边界，本轮不越
  界；已压至一个调用点。
- 验证：新增 11 条测试全绿（分派器 9 + 装配 2）；`:app:testDebugUnitTest`
  **1128/0/0/0**；`:runtime:fastlane-distcc` 77 tests/8 skipped（既有 POSIX
  前置，含 PVM-11 E2E）/0 failed；敏感门 OK。
- 报告：`docs/reports/tickets/pvm-06/worker-wiring-report.zh-CN.md`

### Lane D — 仓库卫生与工单对账
- 14 effort / 195+ 张票逐票对账（git log × 实施报告 × 票面），**回填 12 张过
  期票**；**仍 open 60 张**分 7 组写入 tracker（关键：fastlane P13 账本泄漏
  P1、avf-shizuku 27 可立即做；DV-01A `execute_no_trans` 单点阻塞 DV-01→04
  链）。
- cleanup-plan §3 归档核对：**已完整**（30/30 目录、39/39 文件、README 批次行
  在），无需补齐。
- `release/0.6.0-stabilization`：0 领先 / 落后 ~31-32，tip 即 merge-base
  （PR #57 已并入）——**建议删除（本地+远端）**，0.6.x 补丁从 main 重切；本
  轮未动分支。
- 报告：`docs/reports/repo-hygiene-20261104.zh-CN.md`

### Lane E — 工程质量积压盘点（分析，不改产品代码）
- **21 项 P0–P3**（P0×2、P1×4、P2×7、P3×8），18 项可零设备执行、9 项需维护者
  裁定。2026-10-27 两份历史报告 12 项"已落地"全部核实；6 项未落地中 3 项已被
  `46ce4e5` 修掉。
- **P0-1**：主检出 `docs/reports/` 有 **9 个磁盘有、索引无的证据文件**（含真
  机验证报告与 2026-10-19 硬件虚拟化方案），一次 `git clean -fdx` 即不可逆丢
  失——需维护者立即裁定 add -f 或显式放弃。
- **P0-2**：AGENTS.md 版本号过期两版（0.6.0/code600 vs 现实 0.6.2/code602）+
  "P0/P1 待完成"清单大半已完成 + "当前 effort"指向失真。
- **P1-1**：`RfbInputEncoder`（270 行输入编码器）至今零测试。
- 报告：`docs/reports/quality-backlog-20261104.zh-CN.md`

## 3. 整合过程（编排层）

1. 五 lane 全部基于 `b39e8e6` 开 ASCII 路径 worktree（规避中文路径 Gradle
   单测坑），`commit.gpgsign false`，只 commit 不 push。
2. 顺序合入 main：D → E → A → B → C。A/B 同改 `fastlane-zero-device.yml`
   同一步骤产生冲突一次：保留单份注释 + 去重复 `-P` 属性后解决（合并结果
   `yaml.safe_load` 校验通过）。
3. 敏感门首次扫出 3 处 `absolute_path`（Lane A/B 报告正文与 Lane B 修复提交
   信息里的 worktree 绝对路径）：报告正文直接修；**Lane B 分支软重置重写两
   个提交**（消息去路径），main 重置回 `origin/main` 重做五次合并。重写前所
   有提交均在 lane 分支引用上，可回溯。
4. 终态：`main` = `origin/main` + 16 commits（ahead 16 / behind 0），敏感门
   `--range origin/main..HEAD` = **SENSITIVE_DATA_OK**。

## 4. 测试与验证

- 各 lane 测试见 §2（全部真实运行，未跑项在 lane 报告如实标注）。
- **集成冒烟**：合并后 main 在 ASCII worktree 实跑
  `:app:testDebugUnitTest --tests "*WorkerPoolDistccDispatcherTest"`
  （带豁免属性）→ BUILD SUCCESSFUL 3m21s。
- 两个 workflow YAML `yaml.safe_load` 校验通过；CI 模块覆盖自检 232 PASS。
- **验证边界（如实）**：完整 gate 绿转需 push 后由 hosted CI 确认；本地无法
  证明 ubuntu-24.04 runner 行为，且 `:app:verifyFastlaneToolboxApk` 未本地单
  跑（需先建 toolbox，与失败点正交，已在 Lane B 报告标注）。

## 5. 修改文件（汇总）

- `.github/workflows/`：ci.yml、fastlane-zero-device.yml、
  fastlane-dv01b-zero-device.yml（各补豁免属性）
- `app/`：新增 `WorkerPoolDistccDispatcher.java` + 测试；
  `AcceleratorLaneAssembly.java`、`app/build.gradle.kts`（模块依赖 +1 行）
- `.scratch/`：12 张票回填 + PVM-06 Comment 回填
- `docs/`：tracker 快照、4 份 lane 报告、本总报告、reports README 索引行
- 产品代码其余零改动；生产门禁（release payload 校验）未放松。

## 6. 未完成 / 不适用

- **未 push**：本地 main 领先 origin/main 16 commits，等待维护者确认后推送
  （push 后 CI 绿转是最终验证）。
- 设备门项（本轮不涉及）：DV-01A OEM SELinux `execute_no_trans`（阻塞
  DV-01→04 链）、16K 宿主、真机 distcc@36。F-MTTCG smp4/8 已由 `1f4e0f4`
  （远端 T9 定案）收口。
- 需维护者裁定：P0-1 九个未跟踪证据文件处置；`release/0.6.0-stabilization`
  删除；P2-10 workflow pin SHA / 依赖锁定；AGENTS.md 过期描述重写；
  78 个纯空 catch 收口（详见 quality-backlog）。
- 未触发 Architecture Change Policy；各 lane 均未越界（C 的剩余缺口按
  PVM-09 §5.4 设备边界显式不越界）。

## 7. 风险

- 合并冲突手工解决处（fastlane-zero-device.yml）仅注释与重复 `-P` 清理，语义
  与两侧 lane 一致；已 YAML 校验 + 集成冒烟。
- 历史 rewrite 仅限本地未推送提交，远端无影响。
- Lane B 报告明示 `verifyFastlaneToolboxApk` 未单跑——若 push 后该 job 仍红，
  按其报告 §"后续动作"处理。

## 8. 后续动作

1. 维护者审阅本报告与 4 份 lane 报告 → push main → 观察 CI 三条 workflow 绿转。
2. 裁定 P0-1（9 个未跟踪证据文件）与 release 分支删除。
3. 按 quality-backlog 优先级开下一轮：P1-1 RfbInputEncoder 补测、P0-2
   AGENTS.md 刷新、fastlane P13 账本泄漏修复。
4. 设备窗：DV-01A（OEM 证据）仍是整链单点。

# 轮总报告：真机设备窗 2026-11-07（device-nova 首轮 · 竞品调研驱动 T1–T7）

## 1. 背景

本轮为 2026-11-06 设备窗开工 prompt（`docs/agent-handoff/DEVICE_WINDOW_PROMPT_20261106.zh-CN.md`）的执行轮。战略输入为竞品调研结论：pVM 编译卸载（distcc over vsock）全网无同类、UML 主路径编排层已就位、protected-only 生态位无人覆盖。设备窗七项任务 T1–T7 全部启动，其中六项收口、一项结构性受阻（见 §4）。

设备为**新连接真机**，与历史别名 device-myron（小米档/Android 17/gunyah）完全不同，全量重新探测，未套用任何 myron 历史事实。原始证据全部在仓外 `evidence/device-window-20261106/`（含 getprop 全文、别名映射、四份会话 trace、DV-01A 探针证据、qualification 报告、logcat 原件），本报告只引用脱敏摘要。

## 2. Scope

- T1 设备清点与别名重建（必做第一步）
- T2 构建 main 并安装（含 allowMissingPayload 缺件发现与 payload 重建）
- T3 distcc@36 真机端到端（ACR §8 最后一项）
- T4 票 27 真实 QEMU 事件原件采集（正常 + 失败腿）
- T5① C1/B2 派发语义真机验证；T5② UML 面产品接线第一步（零设备切片）
- T6 DV-01A 新设备复测（三分法判定）
- T7 protected-only 体验走查 + mode-facts 落档

## 3. 实际完成（逐项）

### T1 · 别名重建 ✅
- 新别名 **device-nova**：Android 16 / API 36 / HONOR 系（粗口径）/ SM8735（board=sun, hw=qcom）/ arm64-v8a / 安全补丁 2026-08-01 / ab_update=true。
- 页大小三渠道一致 4096（4K）：getconf=4096、`ro.product.cpu.page_size` 未设置、内核串 `-4k` 后缀。
- 声明形态：`ro.boot.hypervisor.vm.supported` **缺失**、`ro.boot.hypervisor.protected_vm.supported=true` → protected-only 候选（T7 实测坐实）。
- 与 device-myron 差异逐项表在仓外 alias-mapping.md。

### T2 · 构建与安装 ✅（含一次重大缺件发现）
- `:app:assembleDebug`（0.6.2/code602）上机成功，冷启动零崩溃。
- **缺件根因**：allowMissingPayload 豁免构建的 APK 无 `libdroidlayer_qemu.so`/`libgvproxy.so`/`Image`/initramfs → 产品 QEMU 启动 `IOException`（时间线死在 INITRAMFS_BUILT→GATEWAY_READY，与 10-30 同实例成功记录对照定位）。**修复**：主检出 gitignored 打包机 payload（摘要与 `runtime-artifacts-v1.sha256` 逐一核对通过）复制进 ASCII worktree 重建，含 payload APK 107.5 MB，重装后产品 QEMU 全链可用。
- T2 附加：accelerator lane flag 以 `-Porchestella.acceleratorLane=true` 重装入机（T3/T5 需要）。

### T4 · 票 27 真实事件采集 ✅（核心交付）
诊断开关 `stage_trace_diagnostics_enabled` 经设置页 UI 开启，采集后关闭。四条会话 trace 全部过 `avf_stage_trace_schema.py`（VALID）：

| 腿 | 结果 | 观测门 | stop facts |
| --- | --- | --- | --- |
| 成功腿（debian 实例，运行 273s，UI 正常关机） | ENDED | **10/16**：launch_requested/inputs_begin/inputs_verified/run_begin/vm_created/run_returned/vsock_connected/agent_authenticated/guest_command_ok/stop_requested | **2/3** |
| 环境失败①（payload 缺件 IOException） | FAILED(READINESS_FAILED) | 3 门 | 0 |
| 环境失败②（rootfs 校验和不匹配，guest 自我关机） | ENDED | 8 门 | 2 |
| 注入失败（rootfs 卷缺失注入+还原） | FAILED(CREATE_FAILED @ inputs_begin) | 2 门 | 0 |

- **STOP_COMPLETED 缺口实测坐实**：`guest_shutdown_acknowledged` 信号源缺失（"正常关机"路径 guest 侧无 ack 回执），stop_completed 依约不签——与票 27 报告 not_observed 清单一致，其余两事实（owned_vm_stopped/owned_resources_released）真实闭合。
- 缺门归类：preflight_begin/end、manager_begin 为 AVF lane 专属；vm_running_observed/services_ready/guest_shutdown_acknowledged 为已知缺信号源。
- QEMU 产品链路真机全通：启动→终端→关机、gvproxy+QEMU 双进程、`Virtio-serial netproxy gateway active`。
- **freezer 观察（新设备）**：HOME 后台 10s `isFrozen=false`、QEMU 存活——与本机 ROM 不复现 HyperOS "~5s 冻 uid" 行为；long-run 前景服务策略在本机风险下降（待长跑复证）。
- 体验清单 +1：启动时通知权限弹窗打断首启（已"始终允许"，属首启路径 UX 项）。

### T3 · distcc@36（ACR §8 最后一项）⚠️ pVM 面平台级阻塞（新形态），取证完备
- **交火链路建设完成**：accel-extension 两包制打通——`assemble_payload_libs.py` 产出 PVM_COMPILER_STAGE_OK（engine=clang/22.1.8/16KB 对齐/manifest 齐全），`-Porchestella.fastlane.payloadLibDir` + `-Porchestella.pvmCompiler.libDir` 构建 132MB 扩展包上机；两处构建陷阱修复（扩展包 payload 必须与 fastlane payload 同目录；staging 缺 PVM-09 manifest）。
- smoke 相 **PASS**：`smoke_decision_backend=PVM_PAYLOAD`（产品决策线认到扩展包）。
- crossfire 相失败于 `ACCEL_GATE_TIMEOUT_FALLBACK`，隔离诊断（qaMode=compiler 单跑）定位到**平台级根因**：

  ```
  virtmgr: Protected virtual machine "qa-csdl-*" (cid: 2053) started
  crosvm : unknown gh exit reason: 3
  crosvm : vcpu hit unknown error: Invalid argument (os error 22)
  crosvm : vcpu crashed → exiting with crash (exit 33)   # 启动后 ~3.4s
  ```

  即 **Gunyah hypervisor 层 vCPU 崩溃**，payload 回调（started/ready/finished）零发生。
- 佐证三件：① `qaDebugLevel=0` 时受保护 VM `VirtualMachineConfig.Builder.build()` 反常抛异常（本 ROM 受保护 VM 似要求 debuggable initrd，与 AOSP 语义相反）；② 本机自带的厂商 qcrosvm（trustedvm/cpusys_vm）常驻运行正常——hypervisor 本体可用，stock-AVF microdroid 崩溃；③ 同一 payload 链路 2026-10-26 在 device-myron（API 37）完成过 02b 交火。
- **判词：distcc@36 在本设备 blocked_external（新形态：Gunyah vcpu crash，先于一切用户态阶段）**；ACR §8 distcc@36 行维持缺项，新增跨设备失败形态一条。QEMU 面不受影响（T4 已证）。

### T5① · C1/B2 真机验证 ⚠️ 结构性结论
- **`FabricOrchestrator.dispatch` 产品侧零调用方**（全仓 grep 仅测试与接线格）——产品 dispatch 接线缺口与 DV-PVM-02b 监听起点缺口是同一件事的两面；"宽并行不派 QEMU 面"在产品路径**当前不可观测**，此为 B2 follow-up ② 的前置欠账，立票（§9）。
- 可观测部分已做：crossfire 探针经**生产 `DualLaneCoordinator`** 真机编排双车道（start_report: qemu READY / accel STARTING），QEMU lane 启动参数走生产 `QemuInstanceLauncher` 同型（含 qaDirectQemu 直通模式）；C1 记账语义已有 JVM 锁定测试（8 核 budget==3）。

### T5② · UML 面产品接线第一步 ✅（零设备切片）
- 分支 `lane/uml-face-wiring`（worktree 独立 lane）已合并 main：`UmlFaceLauncher`（生命周期/诚实降级/probe 惰性回收）、`UmlFaceKeepAliveService`（specialUse FGS + wake lock 2h 上限）、宽度供给（ncpus≥2 取 N 否则 1）、`AcceleratorLaneAssembly` 接线格 +47 行。
- 测试：新增 20 用例，`:app` 全量 **1227/0/0**（基线 1207+20 吻合），lint 0 errors。
- 真机联调遗留：内核资产落位、Channel seam（D-1 ACR-bound）、wake lock 续期。

### T6 · DV-01A 复测 ✅（三分法判定：新形态）
- 探针（QA 通道包 purpose=dv01a）运行返回 `PREFLIGHT_BLOCKED_EXTERNAL`：参数合同未满足（toolbox 缺位，QA_ARGUMENTS_REJECTED），VM 生命周期各阶段全部 blocked。
- **判词：新形态**——本设备的阻塞比 myron 的 SELinux `execute_no_trans` 更早更底层：payload VM 启动即遭 Gunyah vcpu crash（T3 compiler 探针证据，crosvm exit 33）。exec 阶段不可达，跨设备复现矩阵 +1（第三种形态：SELinux 阻塞 → 本机 hypervisor 崩溃）。
- 工单 Status 维持 blocked_external 不变。

### T7 · mode-facts 落档 ✅
- qualification 探针（QA 通道包 purpose=modefacts + `run_avf_device_validation.py --grant`）：**4 pass / 8 blocked / 0 fail**。
  - pass：PLATFORM_DECLARED、`PLATFORM_QUALIFIED_PROTECTED_ONLY`（capability bits=1）、PERMISSIONS_GRANTED、`NON_PROTECTED_CONFIG_REFUSED`（`setProtectedVm(false)` 实测 REJECTED）。
  - blocked：8 个生命周期阶段（QA_LAUNCH_NOT_REQUESTED——qualification 不产生命周期样本，按口径 protected-microdroid 模式另跑，本窗未跑：平台级 VM 崩溃使样本无意义，见 §4）。
- **protected-only 由声明形态升级为实测判词**。device-nova mode-facts 落档（仓外 qualification-run/）。
- 体验走查补充：设置页「🩺 诊断与开发者选项」开关 UI 正常；「加速与性能」页对缺 payload 构建正确显示"不可用（构建未含加速组件）"（诚实降级 UI 符合预期）。

## 4. 未完成 / not-run（如实）

| 项 | 状态 | 原因 |
| --- | --- | --- |
| distcc@36 实测数字（ACR 判据逐条） | not-run | pVM 面平台级崩溃，判据不可达（§3 T3） |
| protected-microdroid QA 样本（qualification 8 阶段） | not-run | 同上；QA plan 装配 rig 未在本机重建，且 VM 必崩使样本无意义 |
| T5① 宽并行真机长跑观测 | not-run | 产品 dispatch 零调用方（结构性缺口） |
| UML 面真机端到端（首个编译任务） | not-run | 需内核资产落位 + D-1 通道（ACR 票），本窗只落零设备切片 |
| guest_shutdown_acknowledged 信号源补齐 | 未做 | 票 27 既有 follow-up，本窗实测确认缺口仍在 |
| DV-01A 完整 toolbox 合同复跑 | not-run | toolbox 构建链未装配；VM 必崩下无增量信息 |

## 5. 修改文件（相对仓库根）

- `app/src/androidTest/java/io/github/nyzeep/orchestella/PvmProbeInstrumentation.java`（+8/−1：qaDebugLevel 覆盖，QA-only）
- 合并 lane/uml-face-wiring（5c97e7e5+eedc477e）：
  - 新增 `app/src/main/java/io/github/nyzeep/orchestella/UmlFaceLauncher.java`、`UmlFaceKeepAliveService.java`
  - 新增 `app/src/test/java/io/github/nyzeep/orchestella/UmlFaceLauncherTest.java`
  - 修改 `AcceleratorLaneAssembly.java`（+47 接线格）、`AndroidManifest.xml`（+13 服务条目）、`AcceleratorLaneAssemblyTest.java`（+2 用例）
- 文档：本报告、`docs/reports/README.md` 索引行、工单 Comments（票 27/DV-01A/B2 follow-up/ACR §8 注记）。

## 6. 测试与验证结果

- ASCII worktree（含 payload，detached @ b4ee9270）：`:app:testDebugUnitTest` 全量结果见收尾段（测试数与 lane 报告对账：1207 基线 + 20 新增）。
- 敏感门：`SENSITIVE_DATA_OK`（origin/main..HEAD）。
- 设备验证：T4 四条 trace schema VALID；T7 qualification 报告 schema v1；DV-01A 探针证据 schema fastlane.dv01.preflight.v1。
- CI 注记：main tip 既有 `:runtime:network:socketForwarderDiagnosticsTest` socket flake（42246012397，docs-only 提交触发，与记忆中已知 socket flake 同族，非本轮引入）。

## 7. Commit / branch

- main @ **b4ee9270**（Merge lane/uml-face-wiring）← 862b5324（qa 探针）← 5c97e7e5/eedc477e（UML lane）← origin/main 293d8c0f，ahead 4，push 见收尾段。
- lane worktree：/d/t/orch-uml-wiring（保留）、/d/t/orch-dw-20261106（保留至轮收尾后清理）。

## 8. 风险

1. **pVM 线在本设备平台级不可用**：HONOR/SM8735/Gunyah 档 protected microdroid vCPU 崩溃，且 `setDebugLevel(0)` 反常拒绝——两条独立异常指向 ROM 的 virt 框架与 stock AVF 存在实现分歧。短期 pVM 编译卸载在 protected-only 新设备上的可落地性显著下调，UML 主路径判断进一步强化。
2. debugLevel=0 build() 抛异常的确切 cause 未捕获（探针 notes 只记了异常名）——修复需再次改探针，优先级低（已足以判 blocked_external）。
3. allowMissingPayload 构建的"缺件 IOException"对新设备是新坑首现形态（报错只给 type 不给 cause），后续可在 launcher 失败分支补"payload 缺件"识别码。
4. socketForwarderDiagnosticsTest flake 与 AVF 模拟 socket flake 同族，main 红可能复现。

## 9. 后续动作

1. **立票**：产品 dispatch 接线缺口（FabricOrchestrator.dispatch 零调用方 + DV-PVM-02b 监听起点）合并一票跟踪（B2 follow-up ② 的收口条件）。
2. **立票**：device-nova Gunyah pVM 崩溃——跨设备失败形态矩阵第三形态；含 debugLevel=0 反常证据；待 ROM/Gunyah 上游适配后以完整 toolbox 合同复跑 DV-01A 与 distcc@36。
3. 票 27：guest shutdown ack 信号源补齐（设备侧 follow-up 继续有效）。
4. UML 联调窗：内核资产落位 + FGS/wake lock 驻留门对照 E18 + 宽度控温（lane 报告遗留清单）。
5. freeform：HONOR freez­er 行为长跑复证（本窗 10s 观察为短窗结论）。

## 10. 越界记录

- 无。ACR 管辖改动面（D-1 标记通道）零触碰；所有设备原始证据在仓外 evidence/；别名红线遵守（仓内只出现 device-nova 与粗粒度口径）。

## 11. ACP

- 设备单实例纪律：设备 lane 串行（T1→T4→T3→T6/T7），零设备 lane（T5② 构建/测试、payload staging、报告）并行。
- 子智能体：Explore（执行手册提取）×1、general-purpose（T2 构建）×1、general-purpose（T5② 实现）×1——每批 ≤2。
- 收尾链：敏感门→ASCII worktree 集成冒烟→push→CI 确认→报告/工单→记忆。

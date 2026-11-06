# 真机设备窗优化开发 Prompt（2026-11-06 · 新会话专用）

> 定位：**真机设备窗**专用开工指令——结合 2026-11-06 竞品调研的差异化结论，
> 把独有能力（pVM 编译卸载）与 UML 主路径在真机上向前推进。流程与硬约束的
> 完整版在 `HANDOFF_PROMPT_20261106.zh-CN.md`（必读），本文只叠设备窗增量。

## 0. 使命

用户已确认长期无 non-protected 设备：**UML 线 = 虚拟化性能优化主路径，AVF
保持时间盒研究线，QEMU 全设备可靠可用优先**。本轮设备窗的三条战略主线（依
据见 §3）：

1. **把全网独有的 pVM 编译卸载做成产品能力**（distcc over vsock，实测
   ≈11.7×）；
2. **UML 主路径产品化接线**（编排层 B2 已就位、零改动可挂）；
3. **protected-only 空白市场的体验打磨**（官方 Terminal/koiTerminal/
   UserLAnd Pro 全部只覆盖 non-protected，这是 Orchestella 独占生态位）。

## 1. 前置阅读（按序）

1. `docs/agent-handoff/HANDOFF_PROMPT_20261106.zh-CN.md`——**全文必读**：
   报告清单、状态快照、硬约束、工作流、收尾动作链全部以它为准。
2. 设备窗直接相关报告：
   - `docs/reports/tickets/dv01a-mode-facts/implementation-report.zh-CN.md`
     （mode-facts/DV-01A/16K 取证方法与结论）
   - `docs/reports/tickets/b2-fabric-orchestration-wiring/implementation-
     report.zh-CN.md`（三面宽度语义与三条 follow-up——UML 接线/ native 监听
     起点/D-1）
   - `docs/reports/tickets/c1-fabric-width-decision/implementation-report.
     zh-CN.md`（派发语义；真机验证项）
   - `docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`
     （not_observed gates 清单=真实事件采集待办）
   - `docs/reports/tickets/pvm-11/implementation-report.zh-CN.md`（distcc
     E2E 客户端口径）+ `docs/research/ACR-draft-four-lanes-20261029.md` §8
     （distcc@36 判据要求）
3. 竞品调研存档：项目记忆 `orchestella-competitive-survey-20261106`（四类
   车道对标地图；重点跟踪名单 UserLAnd Pro / koiTerminal / Podroid /
   GlassHaven-Haven / StrykerOSS / Droidspaces）。本地参考仓已克隆：
   termux-app、proot-distro、UserLAnd、Podroid、DroidSpace(±rootfs-builder)、
   linux-um-arm64、distcc-upstream、Shizuku(±API)、LinuxDeploy、Andronix。
4. `.scratch/round-20261104-device-window/spec.md` 与 `.scratch/
   orchestella-fastlane/issues/DV-01A-toolbox-exec-selinux.md`。

## 2. 当前状态快照（2026-11-06 实测）

**项目侧**：main 已推送（含 HANDOFF_PROMPT 提交）、CI 五条 workflow 绿、
`:app` 1207/0、版本 0.6.2+（code602 后）。零设备优化项全部出清。

**设备侧（2026-11-06 实测，本节事实必须在新会话重建核对）**：
- ⚠️ **当前连接设备与既有别名记录不一致**：实报 Android **16** / API 36 /
  4K 页 / arm64-v8a / Honor 系机型口径——而 evidence/dv01a-20261104 记录的
  device-myron 是 Android 17 / API 37 / 小米档。**这是另一台设备**：第一步
  必须重建别名映射（原始标识只入仓外 evidence/<topic>-<date>/），严禁把
  device-myron 的历史事实（protected-only、freezer 行为等）套用到新设备
  上——全部重新探测。
- 设备上安装的 Orchestella 是 **0.6.0（code600）旧包**——先构建当前 main
  安装新包（见 T2）。
- MCP android-emulator 插件 preflight 现状：SDK root / adb / emulator 均
  not found（插件未拿到本机 Android SDK 根路径）——两条路任选：给插件配置
  `ANDROID_PLUGIN_SDK_PATH` 指向本机 Android SDK 根目录（或 ANDROID_HOME）后用 MCP 设备
  工具；或按既有 lane 惯例直接用 `/d/AndroidSDK/platform-tools/adb.exe`
  （2026-11-06 快照即此路，设备在线 USB 供电）。

## 3. 战略依据（竞品调研 → 任务优先级）

| 调研结论 | 推导出的设备窗动作 |
| --- | --- |
| pVM 编译卸载（distcc over vsock）**全网无同类**——所有 AVF 项目只把它当"跑 VM 的壳"，无人当"算力卸载通道" | T3 distcc@36 闭合 ACR + DV-PVM-02b 产品监听起点 → B3 池容量旋钮 0→N 打开。这是把独有护城河产品化的最短路径 |
| Google Terminal / koiTerminal / nixos-avf 生态全部卡 non-protected；**protected-only 无人覆盖** | T7 protected-only 体验走查与新设备 mode-facts 取证——独占生态位的地基数据 |
| Haven（最像的工程形态）用 **UML-guest + passt 用户态网络**；Podroid #111 在讨论 UML 升主引擎；StrykerOSS 三引擎共享 rootfs | T5 UML 面产品接线（B2 编排零改动可挂）：启动器产品化 + 前台服务/wake lock；rootfs 跨车道共享列为后续架构候选（过 ACR） |
| UserLAnd Pro 双后端商业形态（AVF VM + QEMU 回退） | Orchestella 的 QEMU 主线即"回退面"，保持全设备可靠性即可对位；无需追 AVF 商业功能 |
| 发行版生态（nixos-avf 等）在官方 Terminal 形成 | 七发行版装配已有；设备窗只验证装配质量，不新增发行版 |

## 4. 任务清单（agent-ready，按序执行；每步完成即回填工单与报告）

**T1 · 设备清点与别名重建（必做第一步）**
- What：`adb devices` → getprop 全量快照（版本/API/abi/页大小/hypervisor/
  protected_vm 声明/安全补丁级）→ 仓外 evidence/device-window-<date>/ 存原
  始 → 仓内新别名（沿用 device-a/b 体系，新设备给新别名）。
- Acceptance：仓外别名映射文件 + getprop 全文；仓内工单只出现别名；与
  evidence/dv01a-20261104 的差异逐项列出。

**T2 · 构建当前 main 并安装**
- What：ASCII worktree `:app:assembleDebug`（带
  `-Porchestella.assets.allowMissingPayload=true`）→ 安装（am start 前
  force-stop；HyperOS freezer 亮屏+前台绕行；**新设备 freezer 行为未知，
  首次长跑前先做 5s 冻结观察**）→ 版本/启动自检。
- Acceptance：设备上运行当前 main 版本；启动自检通过；卸载/数据策略遵守
  （禁止用卸载绕过旧签名残留）。

**T3 · distcc@36 真机端到端（ACR §8 最后一项）**
- What：按 PVM-11 报告的客户端口径 + ACR §8 判据，在真机 QEMU 面走通
  distcc@36 编译卸载链路；pVM 面若被 DV-PVM-02b 监听起点卡住，用 QA 链路
  取证并记录缺口（不硬造产品路径）。
- Acceptance：ACR §8 distcc@36 判据逐条有真机数字；结论回填 ACR 草案与
  工单；数字与 98ms（protected VM p50）/32750ms（guest TCG）基线并列。

**T4 · 票 27 真实 QEMU 事件原件采集**
- What：按票 27 报告的 not_observed gates 清单，在真机跑正常启动 + 受控失
  败启动（一次成功/一次注入失败），采集 16 门真实事件原件（诊断开关开启，
  采完关闭）；原件入仓外 evidence/，脱敏摘要+事件 JSON 样本回填工单。
- Acceptance：STOP_COMPLETED 三事实在真实启动链闭合或明确记录哪个信号源
  缺失；schema v1 样本入库（仓外）。

**T5 · C1/B2 真机验证 + UML 面产品接线启动**
- What：① C1 派发语义真机长跑（宽并行任务不派 QEMU 面的实际观测；三面宽
  度之和恒等账本在真机日志核对）；② UML 面产品接线第一步——按 B2 报告
  follow-up ②：UML 启动器产品化 + 前台服务/wake lock 硬前提（零设备部分
  可先行，真机联调在本窗）。
- Acceptance：① 有真机日志证据（脱敏）；② UML 面经 FabricOrchestrator 派
  出首个真机编译任务的端到端记录（哪怕单任务）。
- 阻塞边：UML 接线若触及 ACR 管辖改动面（D-1 标记通道），按 ACR 流程立
  票，不擅自改。

**T6 · DV-01A 新设备复测**
- What：新设备（Honor 系口径）上复测 toolbox exec SELinux 阻塞（既有
  probe 步骤照做）；同签名/已解除/新形态三选一判定。
- Acceptance：判定+脱敏证据回填 DV-01A 工单 Comments（跨设备复现矩阵+1）。

**T7 · protected-only 体验走查（差异化主线）**
- What：QA 三模式启动链在新设备全走查（protected 声明/模式判决/降级路径），
  记录 mode-facts（复用 AvfQaProbeInstrumentation + run_avf_device_
  validation.py --grant 既有方法）。
- Acceptance：新设备 mode-facts 落档；protected-only 体验问题清单（若有）
  立票。

## 5. 硬约束增量（其余全按 HANDOFF_PROMPT §3）

- **别名红线**：当前设备的原始序列号/型号不进仓库——本 prompt 已刻意只写
  口径（Android 16/API 36/Honor 系）；T1 完成前一切设备事实标注"待重建"。
- **freezer**：VM 创建后 ~5s 冻 uid 是小米档已知行为；新设备首次长跑先做
  冻结观察；亮屏+前台 TOP_APP 绕行；deviceidle 白名单用完解除。
- **取证边界**：受保护 VM 的 avc 不可采（console 只转发 pvmfw 阶段）——
  判定靠 payload typed 诊断，照 DV-01A 报告方法。
- **长任务供电**：USB 常插（快照时 59%）；跑批前确认充电状态。
- **MCP 插件**：preflight 缺 SDK 路径时按 §2 两条路处理，不为凑 MCP 而
  重装 SDK。

## 6. 工作流（同 HANDOFF_PROMPT §5/§6，增量如下）

- 子智能体 ≤2/批；**设备是单实例资源——设备操作类 lane 一次只跑一个**，
  零设备 lane（构建/报告/工单）可与设备 lane 并行。
- 设备交互优先 `/d/AndroidSDK/platform-tools/adb.exe` 直用（既有惯例）；
  UI 自动化用 MCP 工具（配好 SDK 路径后）。
- 收尾固定动作链同 HANDOFF §6，另加：设备侧临时授权全部解除、无残留 VM/
  实例、evidence/ 目录有 sha256 清单。

## 7. 交付定义

1. 轮总报告 `docs/reports/device-window-<date>.zh-CN.md`（REPORTING_
   STANDARD 全必填项；T1–T7 逐项结果，not-run 如实标注）。
2. ACR §8 distcc@36 行回填（绿/红 + 数字）；DV-01A 复现矩阵 +1。
3. 工单回填（票 27、DV-01A、B2/C1 follow-up）+ `docs/reports/README.md`
   索引行。
4. 项目记忆更新（新设备别名画像、distcc@36 结论、UML 接线进度）。

# 设备窗取证与 CI 硬化轮总报告（2026-11-05）

- 执行 session / 模型 / thinking：编排会话（4 个并行子智能体 lane，分两批派发）/
  GLM-5.3-Flash / 高
- 分支：4 条 lane 分支全部合入 `main`（基线 `f366bbf`），合并后 `0f27e25`，
  **已 push**；上一批提交（`f366bbf`）push 后 **CI 首次全绿**。
- 报告规范：`docs/agent-handoff/REPORTING_STANDARD.zh-CN.md`

## 0. 背景

用户确认按上轮建议推进：真机已连接、允许联网、并行子智能体。执行序：push
main → CI 绿转验证 → 四 lane 并行（设备窗取证 / RFB 三疑点 / workflow 硬化 /
票据收尾）→ 合入 → 复推。

## 1. scope

| Lane | 分支 | 主题 |
| --- | --- | --- |
| F | `device/dv01a-mode-facts-20261104` | 真机取证：DV-01A / mode-facts / 16K 宿主 |
| G | `fix/rfb-pinned-issues` | RFB 三疑点修复（测试已钉住） |
| H | `chore/ci-hardening-20261104` | workflow 硬化（P1-2/P2-10/P2-11） |
| I | `chore/ticket-closure-20261104` | P13 闭环 + avf-shizuku 27 处置 |

## 2. 实际完成

### 里程碑：main CI 首次全绿
push `1f4e0f4..f366bbf` 后四条 workflow 全绿：CI 5m37s ✓、Sensitive data
guard ✓、Fast Lane zero-device 3m15s ✓、Fast Lane DV-01B 3m14s ✓——自
`46ce4e5` 引入 verifyRuntimeAssets fail-fast 误伤以来的首次绿转，上一轮根因
修复由 hosted CI 确认。

### Lane F — 设备窗取证（真机实测，别名 device-myron）
- **画像**：HyperOS OS4.0 / Android 17（API 37 release，patch 2026-10-01）/
  骁龙 SM8850 档 / arm64 / gunyah hypervisor / `protected_vm.supported=true`。
  真实标识仅存仓外 `evidence/dv01a-20261104/`。
- **16K 宿主（ACR §8.2）**：四渠道交叉证实 **4K 宿主**（getconf=4096、
  `/proc/version -4k` 等）。§8.2 更新为「三设备均 4K」并追加 Android 17
  release 级负样本；W-FAB-03「无出货设备默认 16K」判词时效延伸至 Android 17
  出货代。
- **mode-facts**：复用 QA 链路实测 **protected-only**（raw bits=1、
  `setProtectedVm(false)` REJECTED）；`real_avf_samples=0`、custom Linux 门
  `NO_NON_PROTECTED_VM`。
- **DV-01A：仍阻塞（同机制同签名跨平台复现）**：`fln1=7`（EXEC_FAILED）、
  `exec_stage=execve`、`errno=13`（EACCES）、`output_bytes=0`，与
  2026-10-21 权威证据逐字一致。诚实边界：本平台 avc 行不可采（受保护 VM
  console 只转发 pvmfw 阶段、host dmesg 被 SELinux 拒），判定由 payload
  typed 诊断承载。
- **新平台事实**：HyperOS freezer 在 VM 创建后 ~5s 冻结 QA app 整 uid
  （`freezeUid reason=new process/tobg`），deviceidle 白名单**不**豁免，亮屏
  +前台（TOP_APP）可绕行——`run_dv01.py` 因此挂起，改用同参
  `am instrument` 直跑并披露。
- 报告：`docs/reports/tickets/dv01a-mode-facts/implementation-report.zh-CN.md`

### Lane G — RFB 三疑点修复（RFC 6143 为据，线格式未动）
1. **SCROLL 不再重定位指针**：滚轮包复用编码器跟踪位置（事件 x/y 忽略）。
   依据 §7.5.5 每条 PointerEvent 都断言"pointer is now at (x,y)"，而三个
   生产者坐标语义不一致、相对捕获路径传 (0,0) 会把指针瞬移原点。
2. **LOCK 位收下并忽略**（原整条拒收导致锁开启期间全部键盘输入静默丢弃）：
   线上字节与 MOD_NONE 逐字节一致；锁键 keycode 58/69 仍作普通键发送。
   取舍：不做宿主→guest 锁同步（需协议扩展，越界）。
3. **非正尺寸 fail-fast**：指针路径对 ≤0 尺寸抛 IllegalArgumentException
   （原静默 clamp 到 (0,0)）；键盘/文本路径不受影响。
- 验证：`:runtime:contract:test` **141/3 skip/0 fail**（137 基线+4 新增；
  RfbInputEncoderTest 48→52）；`:runtime:contract:check` 绿。合并后再跑
  `:runtime:contract:check` 冒烟 BUILD SUCCESSFUL 1m13s。
- 报告：`docs/reports/tickets/rfb-pinned-fixes/implementation-report.zh-CN.md`

### Lane H — CI workflow 硬化
- **P1-2**：release.yml 唯一真实吞错（sdkmanager 装 android-36 的
  `|| true`）改为显式失败；`--licenses` 的有意吞错保留。
- **P2-10（部分）**：28 处 `uses:` tag 全部 pin 到 commit SHA（经
  `gh api` 双重核对，附版本注释）；guest-assets 既有 2 处 SHA pin 仅补注释。
- **P2-11**：5 个 workflow 补齐 `concurrency: group + cancel-in-progress`；
  qemu-android 三个超长 job（360/240/240 分钟）拆出 16 处单步
  `timeout-minutes`（合计等于 job 预算，脚本核对）。
- 验证：7 个 yml 过 `yaml.safe_load`；`check_ci_module_coverage.py` 232
  PASS；`check_zero_device_workflow.py` WORKFLOW_OK；tools/ci 单测 37 OK。
- 新发现：qemu-android.yml:531 存在既有真实设备标识文案——建议并入积压
  P1-3 分诊（本轮未改，待敏感门口径裁定）。
- 报告：`docs/reports/tickets/ci-hardening-20261104/implementation-report.zh-CN.md`

### Lane I — 票据收尾
- **P13 置终态 `implemented`**：三条验收判据逐条对照 `b07dca0` 全覆盖（崩溃
  分支补释放、红→绿回归用例、stop 幂等账本归零），无剩余泄漏面。
- **avf-shizuku 27**：四路交叉核实**未实现**；前置 22 已离线完成；Status 维
  持 `ready-for-agent` 并追加 agent-ready 开工说明（What to build、六组零设
  备可自验验收、阻塞边）。
- 报告：`docs/reports/tickets/ticket-closure-20261104/implementation-report.zh-CN.md`

## 3. 整合与验证

- 四分支顺序合入 main（零冲突）；敏感门揪出 Lane H 报告 1 处 worktree
  绝对路径 → 修正后 `SENSITIVE_DATA_OK`。
- 集成冒烟：合并后 main `:runtime:contract:check` BUILD SUCCESSFUL 1m13s。
- 合并结果 push（`f366bbf..0f27e25`），CI 复验进行中（绿转以 Actions 页为准）。

## 4. 未完成 / 不适用

- **Gradle dependency locking（P2-10 后半）**：需联网生成
  verification-metadata 且涉镜像策略，按积压判定留给维护者专项。
- 设备项 not run（Lane F 如实记录）：20 轮/storage persistence/负例、custom
  Linux、Pixel 16K 设备、distcc@36 真机端到端。
- **DV-01A 外部阻塞未解**（OEM SELinux 策略）；产品 AVF 硬门仍缺
  non-protected 设备（本机 protected-only）。
- qemu-android.yml:531 设备标识文案处置（需维护者裁定口径）。

## 5. 风险

- 28 处 SHA pin 后 action 升版需走 dependabot/手工换 SHA（建议下一步配
  dependabot）。
- qemu-android 单步 timeout 为预算等分比，非实测耗时分布（报告已声明）。
- RFB LOCK 位行为变更对外可见语义：锁开启期间键盘输入由"静默丢弃"变为
  "收下并忽略锁定位"——测试已锁新行为，调用方无感。

## 6. 后续动作

1. 观察 push 后 CI 全绿（合并含 workflow 改动，首次以 pin SHA 运行）。
2. 下一轮零设备主线候选：avf-shizuku 27（已 agent-ready）、静态检查
   （P2-3/4）、空 catch 分批收口、qemu-android.yml:531 文案分诊。
3. 下一设备窗：distcc@36 真机端到端（ACR §8 最后一项）、non-protected 设备
   候补搜寻（产品 AVF 硬门）。
4. ACR：16K 宿主缺项已补 Android 17 负样本，具备投票条件（待维护者）。
- 是否越界：各 lane 均未越界（RFB 明确不做协议扩展）；未触发 Architecture
  Change Policy。

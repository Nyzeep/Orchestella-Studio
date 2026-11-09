# 轮总报告：优化收口混合轮（2026-11-08 → 11-10 · T1–T6 + 追加 UI 打磨）

## 1. 背景

承接 2026-11-07 零设备全项目优化轮的六项债务与设备验证积压（开工 prompt：
`docs/agent-handoff/` 2026-11-08 版 + `.scratch/round-20261108-optimization-closeout/`）。
目标：① 零设备债务清零（ErrorCopy 单源、动效挂钩、pump 测试预算）；② 设备在线完成
冒烟 6 项 + 性能确认 4 项；③ 执行中用户追加 UI 打磨需求（灰边/灰按钮/icon 高级化/
材质），并入本轮为 W2。设备 device-nova（HONOR/API 36/4K 页/protected-only 实测，
仓内只用别名），2026-11-07 轮已装 payload 主包 + accel 扩展包。

## 2. Scope

- T1/T2 零设备 lane（并行子智能体，各自 ASCII worktree+分支）
- T3–T5 设备 lane（主会话串行）：构建上机、冒烟 6 项、性能确认 4 项
- W1 设备旧包清理（用户点名）；W2 UI 打磨 lane（联网调研+实现）
- 编排：合并→敏感门→集成冒烟→重建重装→真机验证→收尾链
- 2026-11-09 用户追加指令：本轮收口后直接开启下一轮大型优化
  （`.scratch/round-20261109-grand-optimization/`，工单 A–F 已就绪）

## 3. 实际完成

### T1 · ErrorCopy 单源统一 ✅
- `ErrorCopy.kt` 新增 7 固定码族（E-LIFECYCLE / E-AVF-PERMISSION / E-AVF-UNSUPPORTED
  / E-AVF-CONFIG / E-AVF-GENERIC / E-LANE / E-OOM，strings.xml 补 7 键）；
  `LaunchFailureCopy` 降级为委托壳（无生产消费者，可后续删除）；
  MainActivity `formatLaunchFailure` 改走 `ErrorCopy.detailMessage`（主文案+错误码
  副文本），异常类名仍写 OrchestellaLaunch 日志。
- 测试 1260→**1266/0/0**（+6：ErrorCopyTest 新族+判定顺序回归、委托一致性）。

### T2 · 动效挂钩 + 小项 ✅
- MainActivity 页签切换接 `stateChangeEnter/Exit`（`tabSwitchGeneration` 代数守卫
  防残影/竞态）；SettingsActivity 测速完成后 `playSectionStagger` 重放；
  ModernTerminalView.copySelection 成功路径补「已复制」Toast（strings 新键
  `terminal_copy_done`）；ControlChannelRecoveryTest 两条 pump 用例
  timeout 4s→10s、内部 1s 预算→5s（断言语义不变）。
- 测试 1266/0/0 持平；lint 0 error。交付 8 条真机走查点（M1–M5/S1/T1/R1）。

### T3 · 构建上机 ✅
- 集成 worktree（/d/t/orch-closeout-20261108，detached）+ 主检出 4 件 gitignored
  payload 复制（Image/initramfs/libdroidlayer_qemu.so/libgvproxy.so，sha256 与源
  逐一比对吻合；manifest 中 2 文档类 BAD 为 manifest 自 10-15 未随文件更新的存量
  现象，git 干净检出同状，非缺件）。
- APK#1 f299efaa（b52ba76f，T1+T2 合并）上机冷启动零崩溃；pVM 编译加速 · 就绪
  确认 accelerator lane flag 生效。
- APK#2 15287897（400151dc，W2 合并后）重装，UI 打磨真机验证通过。

### T4 · 人工冒烟 6 项（含 T2 动效点）
| 项 | 结果 | 证据 |
| --- | --- | --- |
| ① 向导磁盘输入非法值（空/非数字禁下一步+提示） | **PASS**（两分支） | w1-empty/w1-nonnumeric.png |
| ② 创建中取消确认框两分支 | **PARTIAL**：按钮路径代码审查确认接线（L460→L602，positive=离开/negative=留下）；**BACK 路径实测无确认框直接退出=缺陷立票**；按钮双分支真机复现未完成（向导页自动化坐标漂移+滚动敏感） | w11-backdialog.png |
| ③ 向导失败文案 E-* 随错误族变化 | **not-run**（4 次创建全成功、无安全注入点；ErrorCopy 映射有 21 JVM 用例锁定） | — |
| ④ 设置「启动验证与回退」卡展开收起+快速连点 | **PASS**（▶/▼动效、代数守卫无竞态残影） | s1-*.png |
| ⑤ 实例详情五页签快速切换无滞留半透明 | **PASS**（含第 5 页签横滑可达、维护页内容独立） | d5-*.png |
| ⑥ 终端 attach 滚动到底 / 无实例空态 | attach **PASS**（26.7s→82.6s 持续输出钉底）；空态 **not-run**（需零实例环境，避免清空用户数据） | t6-attach-follow*.png |
| T2 动效点 M1/M2/M3 | **PASS**（入场中间态/连点落定/快速回切三帧无残影） | m1–m3*.png |

### T5 · 性能与行为确认 4 项 ✅ 全 PASS
1. **通知权限首帧后弹出**：600ms 帧全黑（窗口未绘制）→ 2.7s 帧主界面已完整渲染
   且权限框才弹出；撤销授权后重启确实再问一次（测毕已恢复始终允许）。
2. **启动期渲染顺序**：700ms 帧机器卡为默认占位态（尚未创建实例/ARM64 pKVM）→
   1.6s 完成回调填充（实例名/引擎可用性），结构无跳动、无可感知卡顿。
3. **仪表盘 3s 快照**：两帧间 RAM 值已更新（6.2G→6.3G），滞后可接受。
4. **冷启动体感**：~1.6s 完整仪表盘，全程无 ANR/冻结，定性「快」。
证据：t5a-700ms/1600ms/3000ms、t5b-600ms/1500ms/2700ms。

### W1 · 设备旧包清理 ✅
- 卸载 8 个陈旧 QA/test 包（orchestella.test、qa.modefacts(.test)、qa.dv01(a)(.test)、
  qa.recreate）；设备现存仅主包 + accel 扩展包。

### W2 · UI 打磨（用户追加）✅
- 联网调研三条结论（Material Symbols 静态 vector 路线 / M3 outlined card 1dp
  outlineVariant / NN/g disabled=保色相降透明度）带来源，写入 commit body。
- 落地：卡片描边 1.5dp 灰带→**主题同源 1dp 发丝线**（四主题各自派生色）；
  禁用按钮扁平灰→**幽灵态**（14% enabled fill + 62% 墨水）；详情页签选中态
  对比修正；6 枚 Material Symbols Outlined 矢量（ic_sym_pin/bolt/storage/globe/
  warning/rocket）；30 个 strings 键剥 emoji 保留文字（error_* 区零改动，
  工控字形 ●■▲○✓✕❯ 有测试锁定者保留）。
- 测试 1266/0/0 零改动；lint 0 error。

### 真机 UI 验证（7 点全 PASS）
仪表盘/实例卡发丝线；详情禁用按钮幽灵态；详情页签矢量图标+选中可读；设置分区
头图标化；红主题回归无破损（暗色发丝线适配）；发行版徽记无 emoji。证据
u1–u6*.png。**冷启动零崩溃（两轮 APK 均验证）**。

### 实例数据操作记录（诚实披露）
- 向导验证过程产生 4 个测试实例；**0d58c792 已走维护页删除流程成功清理**
  （含「确认删除」二次验证阴性拦截 Toast 一次——验证逻辑工作正常的旁证）。
- 余 3 个（c810fe9f/d49bfb27/1 个 11-10 新建）为**运行中断/已创建闲置态**，无
  运行进程（pgrep qemu/gvproxy 空）；运行中断态删除守卫禁删、复位未生效，
  自动化清理止步——**留人工清单**：逐实例「维护→复位→删除→输入确认删除」。
- 过程中一次双 BACK 误出 App 进微信（个人设备隐私风险）：涉事截图已即时删除
  未入 evidence，此后改单动作+逐屏验证节奏。教训已记设备 quirks。

## 4. 未完成 / not-run（如实）

- T4② 按钮双分支真机复现（自动化受限；缺陷=BACK 无确认框已立票 E-ux）
- T4③ 失败文案真机腿（无受控注入点；JVM 已锁定）
- T4⑥ 无实例空态文案真机腿（需零实例环境）
- 余 3 个孤儿实例清理（人工）
- W2 遗留：详情页 applyTheme/ThemeApplier 描边双写待收敛；设置分区头图标
  24dp 偏大（需 InsetDrawable 或换行布局）；导航底栏禁用态未动

## 5. 修改文件（汇总，相对仓库根）

- T1：ErrorCopy.kt(+43/−5)、LaunchFailureCopy.kt(+14/−29)、MainActivity.kt(+7/−7)、
  strings.xml(+9)、ErrorCopyTest.kt(+81)、LaunchFailureCopyTest.kt(+76/−52)
- T2：MainActivity.kt(+34/−9)、SettingsActivity.kt(+10/−1)、ModernTerminalView.java(+4)、
  strings.xml(+3)、OrchestellaMotion.kt(KDoc)、ControlChannelRecoveryTest.java(+14/−8)
- W2：ThemeColors.kt(+31)、ThemeApplier.kt(+22/−7)、InstanceDetailActivity.kt(+17/−3)、
  6 枚 ic_sym_*.xml 新增、activity_instance_detail/settings/main/setup_wizard +
  item_distro_spinner/item_port_forwarding_rule、strings.xml(+60/−60)、
  SetupWizardActivity/SetupWizardModel/SettingsActivity/PortForwardingBottomSheet（文案）
- 文档：本报告、README.md 索引、工单回填

## 6. 测试与验证结果

- 集成冒烟（合并 T1+T2 @ b52ba76f）：**1266/0/0**；W2 合并后复跑：**1266/0/0**
- lint：0 error（34 warnings 存量级）
- 敏感门：每次合并后 SENSITIVE_DATA_OK（origin/main..HEAD）
- 设备：两轮 APK 冷启动零崩溃；7 点 UI 验证全 PASS；T4/T5 证据入仓外
  evidence/optimization-closeout-20261108/（sha256 清单同目录）

## 7. Commit / branch

- main @ **400151dc**（已推送，a5e786ca→400151dc 共 4 commits）：
  b3be7ab8（T1）→ merge b52ba76f（T2 13c9cb4f+73702b20）→ 33791997+400151dc（W2）
- lane worktree：orch-t1-errorcopy / orch-t2-motion / orch-ui-polish（均已合并，
  核查后清理）；orch-closeout-20261108（集成，收尾后清理）

## 8. 风险

1. W2 描边双写（applyTheme vs ThemeApplier）视觉无害但机制债——下一轮 B lane
   收敛。
2. 向导自动化暴露的交互坑（下一步预检窗口期、创建启动后上滑退出、IME 硬件
   注入绕过组词）——记录入设备 quirks，E-ux lane 复测时照单规避。
3. 3 个孤儿实例占用闲置磁盘（sparse，体量有限）——人工清单待办。
4. pump 测试放宽后真实回归窗口变窄（4s→10s）——若 CI 出现挂起类失败优先
   怀此处。

## 9. 后续动作

1. **下一轮已立**：`.scratch/round-20261109-grand-optimization/`（spec + 工单
   A 代码整合 / B UI 现代化基座 / C UML 加速 / D 动效深化 / E UX 修复含 BACK
   缺陷 / F 布局节奏；3 批次 × 2 lane）。
2. 立票（并入 E-ux）：向导创建中 onBackPressed 应走 confirmExitWhileCreating。
3. 人工清单：3 个孤儿实例删除（步骤见 §3 末）。
4. T4② 按钮双分支 + T4③ 注入腿并入 E-ux 真机复测。

## 10. 越界记录

- 设备侧：W1 卸载 QA 包经用户明示（「设备存在多个旧包」）；主包/accel 未动。
- 向导验证产生 4 个测试实例（其中 1 个已清理）；未触碰用户实例 9bb7e2e3。
- 一次误出 App 进微信：未在仓内/证据目录留下任何该屏内容；无数据改动。
- 零设备侧：零越界（lane 边界自检通过，合并零冲突）。

## 11. ACP

- 子智能体 ≤2/批（T1+T2 并行一批；W2 单发一批）；设备 lane 主会话串行。
- 收尾链：合并→敏感门→集成冒烟→push→CI→报告→工单→记忆（本报告即链尾）。
- 记忆更新：orchestella-optimization-closeout-20261108（索引已挂）。

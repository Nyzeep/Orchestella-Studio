# 06: 下游先例拆解——Haven / lakitu16K / 长驻 soak 证据

Type: research
Status: resolved（fabric-batch2，2026-10-31）
Priority: P1
Owner: fabric-batch2
Blocked by: None
External gates: 无（联网 + 代码只读分析，零设备）

## 背景

WorkBuddy 调研发现三个我方未覆盖的先例：`GlassHaven/Haven`（1391★，targetSdk 35，APK 内置 `libvmlinux.so`/`libuml-stub.so`/`libuml-passt.so` + UmlGuestManager，AGPL-3.0）、`lakitu12/linux-um-arm64-gki`（Android 17 GKI 上 71 补丁、16K 默认）、`uml4termux` v0.5.0 长驻证据（3606s 连续、105 轮 soak、60min dockerd）。拆解它们可以直接补齐我方的两个空白（app 域多核、16K 宿主）并避免重踩坑。

## 必须回答

- [ ] **Haven 架构拆解**：UML guest 的启动链（内核装载方式、stub 处理、passt 集成）、生命周期管理（启动/停止/崩溃恢复）、rootfs 管理；公开 issue 里 UML 相关坑的完整清单（#686 锁残留、5.89.7 signal 31、其他）→ 逐条给出「本项目对策建议」。
- [ ] **许可隔离**：Haven 是 AGPL-3.0——逐文件确认哪些只能借鉴思路/情报、哪些（若其内核补丁独立 GPL）可引用；产出「可借鉴 / 不可搬」清单。不得整仓搬代码。
- [ ] **lakitu 16K 分支拆解**：71 补丁的范围（4K→16K 移植做了什么）、「4K 变体在 16K 宿主被 `os_early_checks` 拒绝」的自述核实；为 G2（16K 宿主门）准备技术预案。
- [ ] **长驻证据评估**：uml4termux 的 soak 脚本与数据（RSS 无上升、dockerd 60min）可信度评估；其方法学能否被本项目直接复用为 G-长驻门的标准脚本。
- [ ] **产品定位互证**：Haven 定位（SSH/VNC 客户端内置环境）与 WorkBuddy 方案 B（构建引擎扩展包）的市场差异；窗口期判断。

## 交付

- `docs/research/2026-11-XX-downstream-teardown.md`（含「可借鉴/不可搬」许可清单 + 坑对策表）；
- G-长驻门的建议脚本方案（只设计，不进主构建）。

## 停止条件

- 仓库不可达或关键文件缺失 → 记录缺口，不脑补实现细节。

## Comments

- 2026-10-31 / CodeBuddy（ask-matt 轮）：建票。参考：WorkBuddy 报告 §2.2/§2.4；CodeBuddy 调研 §2.2（drolin 等）。
- 2026-10-31 / CodeBuddy（批次 1 收口后追加输入更新）：
  1. **新增两问（批次 1 建议采纳）**：① drolin/Stryker 的内核分发形态（jniLibs 随包 vs 运行时下载）与 GPL 对应源码交付完整性——衔接 `docs/research/2026-11-02-platform-policy-matrix.md` 的 Play 矩阵与 GPL 先例记录，**只深挖「分发形态 + 交付完整性」，不重复 02 的裁决**；② Podroid 的双权限（`MANAGE_VIRTUAL_MACHINE` + `USE_CUSTOM_VIRTUAL_MACHINE`）grant UX 全流程与五点能力诊断，作为 05 轨道 B（non-protected 开发者通道）的先例素材回填 `docs/research/2026-11-05-pvm-fabric-route.md` 的缺口清单。
  2. **先读批次 1 三份交付**（2026-11-01/02/05）再开工：Haven 三坑的构建期对策已在 `research/uml-kernel-build/checklist-haven-three-pits.md`，本票只补「运行期/恢复期」部分（锁清理、崩溃恢复设计），不重复构建期。
  3. 基线调研 B 已补写入库：`docs/research/2026-10-31-uml-full-spectrum-research.zh-CN.md`。
- 2026-10-31 / fabric-batch2（批次 2 收口）：**resolved**。交付 `docs/research/2026-11-06-downstream-teardown.md`（协调会话复核通过；未改动 2026-11-05 文档，回填建议在交付 §7 待裁定并入）。关键结论：
  1. Haven 运行期可靠性 = 四层叠防（单一 immortal fork 线程防 PDEATHSIG 误杀 / launcher 双 PDEATHSIG / poweroff→5s→硬杀三段式 / #686 三处代码修复）；**Haven 未做 pre-start 试锁**——F_GETLK 预检仍是本仓库 checklist 的自有对策，无先例可抄。构建期三坑未重复（引用 checklist）。
  2. 许可隔离：Haven 主仓（UmlGuestManager/uml_net.c 等）AGPL-3.0 → 只借鉴思路；内核补丁与配方在 **GlassOnTin/uml-transport（GPL-2.0，21 文件）** 可按 GPL 条款引用；可借鉴/不可搬清单已逐项产出。
  3. lakitu 补丁实数 **75**（README 自述 71 过时）；「4K 变体在 16K 宿主被 os_early_checks 拒绝」核实到**代码级**（zalexdev 树 `start_up.c:977-996` check_page_size，`host > UM_KERN_PAGE_SIZE` 即 fatal；mainline 无此项）；本项目 16K guest 内核二进制**天然覆盖 4K/16K 两类宿主** → G2 工作量在启动器/stub 侧而非内核移植（预案 7 项）。
  4. uml4termux soak 证据强于宣传但口径更窄：b1 verdict 实为 **partial**（boot 4 败、docker 101/105）；「10047s」[未找到证据]（疑为 b1 wall-clock 10044s 引用漂移）；RSS 判定仅 daemon、每循环一次瞬时采样；全部证据在 Termux 域不可平移 App 域。G-长驻门三门设计（循环 20 轮 / 驻留 60min@30s / kill 重启）+ App 域 preflight 清单已产出（只设计）。
  5. 分发形态：drolin = jniLibs 随包 + 逐路径 LICENSING.md（社区最完整交付形态，与本项目打包路线同构）；Stryker = release 资产 + manifest 运行时下载（2026-11-02 矩阵②活样本，交付完整性低于 drolin：license NOASSERTION、无 pin 清单）。02 的裁决未重裁。
  6. Podroid grant UX 六阶段（检测→引导→验证→选路→失败分支→权限生命周期）+ 五点诊断全文实读（建议追加第 6 点 custom-VM builder API 探测）→ D-5/D-6 增补文本 + 新增 D-7 建议（`uml_host_top`/装载基址）已按 05 格式给出，标注待裁定。
  未验证项：lakitu CI/真机自述未独立复核、um4 证据链未复现、PORT-STATUS 内部矛盾一处（以 sha 为准）。设备窗口增量 W-061~W-065。停止条件未命中。
- 2026-10-31 / ZCode 设备执行会话（真机窗口轮，device-myron）：**G-长驻门（W-064）App 域实跑 PASS；W-061/062 not-tested；W-065 部分覆盖 + 新增启动器缺口一条**。①门1 循环 20 轮：19/20 干净 + 1 归因（T3 遗留 `files/uml-stop` 被新实例消费，**启动器缺口：watchStop 在 `p.isAlive()` 退出后不消费 stop 文件**，进程死后遗留文件会毒化下一次启动——验收门「所有失败路径收敛单一 teardown owner」的补丁点）；②门2 驻留 60min@30s（App 域，**须屏幕常亮+deviceidle 白名单——attempt1 被 HyperOS cgroup freezer 冻结 33min（App+UML 同组 `cgroup.freeze=1`），RSS 零漂移、唤醒后 UML 时钟以宿主墙钟追赶 1710s 无回绕——「须持前台服务」preflight 项升级为设备级硬证据**）：phase A 36min |Δ|=−2.77%、kill -9 @36min→新实例 EXT4 recovery→mount 无 panic、phase B 24min |Δ|=+0.94%，RESIDENT_DONE→guest poweroff→exit 0；③W-061/W-062 not-tested（设备 4K 页内核无 16K 通道；反向半边=16K guest 内核在 4K 宿主全程正常）；④判定线全部满足：no_cycle_failures（归因后 20/20）、kernel_rss |Δ|<10%（2.77%/0.94%）、BUG/Oops/WARNING=0（console 无命中）、门3 重启无 bind/锁冲突。证据 `evidence/uml-fabric-20261031/t5/`，报告 `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §5。

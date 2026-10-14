# Phase 5 独立 Spec 轴审查

**结论：approve-local-scope（已核源码与本地 APK）；设备/GPU blocked，扩展能力 partial。** 背景为完整本地交付核验；该结论不授予完整 Phase5 产品验收。分支 `phase5/graphics-productivity-storage`，HEAD/基线 `8d0e8c75d769ecc645ea102b90a304c8657344a1`，无 commit。按 `git diff HEAD` 加新增 display/services/sharing/features/prototypes/App 模块审查；95 份第一会话依赖快照逐文件 SHA 通过，PortForwardingPresenter 按已登记 refresh 对账。[Spec](../../../../.scratch/orchestella-phase5/spec.md) 下称 S。

证据根 E：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/`。复评截止 **2026-10-13 13:53 UTC**；59 份命名源码 SHA、基线及 22 件证据身份见 `E/coordinator/spec-review/evidence/review-cutoff-final.json`，保全于 `preserved`；13:41 初评 request-changes 快照及失败原件保留。

## Findings 与定点复评

- **P2 已修：取消解包遗留数据。** S“失败可重试”“取消/磁盘满”：旧 `guest-assets/features/profilectl.py` SHA `822f0ec1…` 缺失败回收，独立真实合成 tar 取消后留下 **33,554,439 B**。现 integration SHA `67ad4b08acd60d2211e99d85be287bbc9eafa51a220a3d30b7159a486614c345` 已合入 parent-FD/inode/finally 回收；同一探针对 worker、integration 均复评 **0 B**，未发布安装目标、未删既有配置。初始失败和新通过原件分别保全；owner 六故障与四相关回归通过。embedded init 与最终 APK 内容已独立对账通过。
- **已反馈并见源码修复的 P2：** S“真实输入”“队列有界”“修复…context-loss”“没有真实帧确认不写 desktop ready”对应红蓝交换、灰 placeholder 误确认、物理键盘未发送、晚到帧坐标错误、失效 EGL 对象复用、尺寸回调无界持帧。现有 RGBA、非纯色/current-generation、evdev/ASCII、尺寸合并、EGL 重建/失败不计成功路径已核；真实设备行为未验证。
- AVF 同名冷恢复和 connect 阻塞 stop 已见 `get/getConfig` 精确校验、短锁、晚返回 FD 回收；官方形状与定点测试通过。旧 editor URL/撤销所有权/UNEXPOSE 竞态已见修复，不重复列开放。

## 验收缺口矩阵

| S 引用 | 本地可确认 | 未完成/边界 |
| --- | --- | --- |
| “帧完全来自服务”“旋转…重新附着” | 有界 RFB、鉴权、Surface/input 源码 | guest 画面随输入变化、rotation/context-loss 未运行；CJK/emoji 明确拒绝 |
| “profile…发行版/版本/架构” | Debian13 labwc/Docker CLI；Debian13、Ubuntu24.04 Podman/editor | KDE/niri、其余发行版未安装；研究包表不算实现；Docker daemon 未验 |
| “真实健康检查”“WebView…origin” | 随机凭据、QEMU 回环转发、显式 LAN 租约/撤销 | 实际 code-server 登录、WS/SW/cookie、LAN 未运行；AVF editor forward 明确不可用 |
| “USB…授权与FD…探针/原型”“guest已挂载” | USB FD 模型、授权选集 staging；标准 9p 实际 wire | Android USB/SAF held-FD adapter、catalog broker 未实现；guest mount 未验，不能标完成 |
| “MANAGE单项不足”“权限受阻” | 双权限、官方 API/FD/配置门 | AVF/OEM/原生 FD 未运行；GPU/共享 buffer/native USB 受 ACR-01 阻断，规则未修改 |
| “APK…SHA256、NOTICE”“可运行步骤与交接记录” | 71,892,457 B APK：`f58c865d8e83ef8c7629c9cf3ad7037e7f3b3a1e27d6452e7386847a2fed2623`；ELF/source/七 payload/NOTICE/manifest 对账通过；patch 与 buildlock 一致，56 份已审源码 SHA 匹配，141 文件合成基线 replay 通过 | 指南/矩阵/交接脚本齐；本报告最终更新后由 coordinator 重锁文档 hash，不包含设备授权 |

## 测试真实性与动作

核 raw：App **61**、RFB **18**、relay **15**、data **26**、control negative **12** 通过；Linux 9p **1500** 会话 FD 8→8、**400** 竞态无越界；AVF **19** 入口及恢复/停止目标通过。profile 全量 **24/25**，失败断言修正后定点通过、unit-v2 三目标通过，未冒称最终全量全绿。最后 `phase5-delivery-build.log.txt` 再跑 App61、contract/instance/QEMU check 与 APK 构建通过，覆盖最后合流。

本人运行三次 host 合成 tar 复现/复评及一次本地 APK 内容核验；未运行 Gradle、真实 guest、Android、USB、SAF、GPU。新增仅本报告和批准 E 内必要 supporting 证据；无生产/工单/ADR、设备/系统修改或外发。截止无尚未修复的已核 P1/P2；后续 coordinator 冻结封装文档，完整 Phase5 依矩阵分级。ACR-01 保留待裁定，无新批准暴露面。

报告人：Codex / independent Spec-axis reviewer ｜ 日期：2026-10-13 ｜ 耗时：未统计

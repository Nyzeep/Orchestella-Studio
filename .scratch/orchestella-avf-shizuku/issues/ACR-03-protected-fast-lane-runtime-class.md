# ACR-03: Protected Fast Lane 运行时类别与产品语义

> **2026-10-21 关联**：fastlane effort 的 **DV-04** 以本 ACR 为产品化前置；执行票见 `.scratch/orchestella-fastlane/`。本文件仍是唯一裁定票，不重复建票。

Type: decision
Status: needs-architecture-decision
Priority: P0（决定 Fast Lane 能否进入产品）
Owner: 产品/架构用户；主协调准备证据包
Blocked by: 无（可与 28/29/30 并行）；产品启用必须等 28/29/30 的门
Parent: `docs/reports/orchestration/protected-fast-lane-plan-20261021.zh-CN.md`

## 要裁定的问题

protected-only 设备上，Google 签名的 Microdroid + 应用 payload 可以提供一个硬件 CPU 加速的 Linux 用户空间，但它不是 Full Linux：没有项目 kernel、systemd/PID1、Docker、原生网络、GPU、native 包管理。它应如何进入产品？

需要具体裁定：

1. **Runtime 类别**：新增第三类（工作名 Accelerated Linux Workspace），还是作为 Full Linux 的“策略专属增强”？
   - 推荐：第三类。它不满足 Full Linux 契约（kernel、systemd、原生网络、包管理、存储模型均不同），不能塞进同一类别。
2. **隔离/保护表述**：可以说“protected pVM 内存隔离 + Microdroid verified boot + host 不可解密 encrypted storage”；不能声称“完全隔离/绝对安全/军用级”，也不能把“platform 声明 protected”写成“真实 Guest 已验收”。
3. **存储/导出**：encrypted storage 是 App 私有 backing file，但 host 不可解密；导出只能 guest 侧 tar → vsock → SAF。删除 VM = 数据不可恢复。是否接受这个与 ADR-0007 raw 根盘不同的模型？
4. **网络**：只有 vsock + 宿主代理；没有原生 `eth0`/NAT/DNS/端口转发。是否接受“代理网络”作为该 runtime 的正式能力？默认只绑 127.0.0.1。
5. **生命周期**：Fast Lane VM 由 App/Shizuku owner 创建；单运行实例？App 死亡是否随停？冷恢复？盘 lease？需要与 ADR-0011/0020/0021 的语义对齐或明确例外。
6. **回退**：Fast Lane 不可用/不适合时，显式提供 Full Lane（QEMU）；不静默降级；UI 显示原因。
7. **产品语言**：中文/英文名称；是否叫“Linux”；不能叫 Full Linux/Docker/原生网络；能力矩阵必须逐项列出 UNSUPPORTED。
8. **实验/默认**：先实验开关还是默认？推荐先实验开关，28/29/30 全部通过且性能 ≥2x 后再讨论默认。
9. **与既有 ADR 的关系**：ADR-0001（两类别）、0002（Full Linux 契约）、0007（raw 根盘）、0008（NAT）、0010（隔离）、0011（生命周期）、0020/0021（所有权）需要哪些 ACR/ADR 补充？不得静默改写 accepted 正文。

## 推荐答案（供裁定，不是已接受）

- 新增第三 Runtime 类别 **Accelerated Linux Workspace（ALW）**，定位“硬件加速的密封 Linux 工作区”，不是 Full Linux。
- 能力：terminal、hardware CPU、encrypted persistent home、sealed toolchain、host-proxied TCP、guest-side export/import；可选 VNC 后续。
- 非能力：自有 kernel、systemd/PID1、Docker/Podman、native network、native package installs、GPU、USB、audio、multi-user。
- 隔离表述：protected pVM 内存隔离 + Microdroid verified boot + host 不可解密存储；host 仍可拒绝服务/删除数据；不承诺 pKVM 全部语义。
- 存储：encrypted storage + guest-side export；不把 host 明文备份写成能力。
- 网络：默认 127.0.0.1 代理；LAN 暴露必须显式；不承诺 ICMP/raw socket。
- 回退：Fast Lane 不可用 → 显式 Full Lane（QEMU）；不静默。
- 发布：实验开关，默认关闭；28/29/30 全部通过后再评估默认。

## 需要用户明确回答

- [ ] 接受 ALW 作为第三类，还是要求 Fast Lane 必须满足 Full Linux 契约（后者意味着不做）？
- [ ] 接受“无 native 包管理/无原生网络/无 GPU/无 Docker”的能力矩阵？
- [ ] 接受 encrypted storage 的导出/删除语义？
- [ ] 接受先实验开关、后默认的节奏？
- [ ] 是否为 ALW 新增产品名/文案，还是内部代号？

## 边界

- 本 ACR 不修改 `CONTEXT.md`、accepted ADR、版本号、签名或代码。
- 28/29/30 的原型可在 QA 范围推进，但不能在 ACR-03 通过前对用户声明 ALW 能力。
- 若裁定“必须 Full Linux”，则 Fast Lane 停止，protected-only 设备继续 QEMU-only；non-protected 未来线保留。

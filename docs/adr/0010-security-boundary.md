---
status: accepted
date: 2026-09-05
amended: 2026-10-01
---

# 安全边界：隔离承诺三级分述 + 暴露面白名单制

1. **隔离承诺分级**（产品文案的官方措辞，宁紧勿松）：
   - L2·硬件虚拟化：guest 与 Android 之间有 **pKVM 硬件隔离边界**；
   - L1·软件虚拟化：guest 运行于独立进程 + Android 应用沙箱内，隔离较强但**非硬件级**；
   - Compatibility Runtime（未来）：**无实质隔离**（guest 即应用自身身份），必须明示——也是它只能经用户主动选择存在的理由。
2. **guest root ≠ Android 数据**：guest root 可触达的宿主内容仅限白名单显式共享面（当前唯一项：只读 Downloads）；宿主文件系统、其他 App 数据、Android 权限对 guest 不可见。
3. **暴露面白名单制**，初始清单固定：①桥接六命令 + per-VM token（ADR-0009）②只读共享 Downloads ③端口转发默认 127.0.0.1（ADR-0008）；无云服务、无遥测。**加面 = 显式产品决策 + 本 ADR 修订**。
4. **凭据纪律**：实例凭据每实例随机生成、用户可见可改；公开常量永远禁止。
5. **诚实残余风险清单**（写进产品文档）：VMM 漏洞为业界共性风险；manifest 托管渠道被篡改会削弱 SHA256 校验（ADR-0006 已记录）；不承诺恶意 guest 零风险，只承诺攻击面即白名单、每项显式。

## Considered Options

- **统一一句"安全隔离"（否决）**：三层面隔离强度是物理事实，拍平即虚假宣传——类别诚实原则在安全域的最终考验。
- **承诺写强一点利于营销（否决）**：安全承诺只能加码不能减码；收回过强承诺 = 信誉事故。

## Consequences

- 未来每个新功能引入新暴露面（剪贴板、读写共享、USB、遥测）都必须过本 ADR 的显式决策并修订清单。
- 分级表述需配通俗解释文案（如"保险箱 vs 锁着的门"），教育成本被接受为产品设计的一部分。
- 实例凭据随机化从第一个实例起生效（公开常量随镜像发布后不可撤回）。
- 残余风险清单随每个新 ADR 复核。

## Amendments & Errata (2026-10-01 修订与勘误)

1. **16 KB Page 规范与内存沙盒边界**：
   - Android 15/16 推进的 16 KB 页面机制进一步加固了内核页表隔离。DroidLayer 的 JNI 桥接库与原生 QEMU 进程均已适配 16 KB 页面对齐，防止因内存段越界污染宿主应用沙箱。
2. **SELinux 与字符设备访问真实边界澄清**：
   - 勘误澄清：第三方免 Root 应用（`untrusted_app` 沙盒域）被 Android 平台 SELinux 策略完全禁止直接读写 `/dev/kvm` 节点（触发 AVC 拒绝）；任何声称“免 Root 打开 `/dev/kvm`”的表述均属不实。
   - L2 硬件虚拟化只能且必须通过 Android Virtualization Framework (`VirtualizationService`) 进行受限 RPC 代理，其权限受 `MANAGE_VIRTUAL_MACHINE` 严格门控。
3. **双抽象套接字（Abstract Unix Domain Socket）安全特性勘误**：
   - Ticket 26 验证并采用的 `droidlayer-qmp` 与 `droidlayer-agent` 抽象套接字不属于文件系统挂载点，无物理文件节点；
   - Linux 内核语义中，抽象套接字在网络命名空间（Network Namespace）隔离。在非 Root Android 宿主上，抽象套接字的作用域天然限定于 Android 分配给应用专属的 UID 沙箱内，其他第三方未授权 App 无法发起嗅探或跨进程注入，保障了 Bridge 协议与 QMP 命令面的传输安全性。
4. **硬件虚拟化“保护与非保护”安全边界细化**：
   - 2026 调研证实：高通骁龙 8 Elite 封锁了 Non-Protected VM，仅提供 Protected VM（pVM）。在 pVM 下宿主完全无法触碰 Guest 内存，虽然隔离性最高，但也阻断了显示帧共享与双向数据交换；
   - DroidLayer 未来在 L2 档引入 Custom VM 时，必须在安全文案中明确标注其属于“Non-Protected VM 边界”，受宿主与 Guest 共享通道的既定白名单管辖。

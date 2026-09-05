# Ticket 25 Worker Scope Contract

- 票号/标题：25 — Guest 资产构建基线
- Worker：GPT-5.6 Sol，thinking/reasoning `max`
- 目标分支：`ticket/25-guest-assets`
- 基线：完成 Stage E bootstrap 与 initial takeover report 后的本地 `main`
- 调用工作流：`implement` + `tdd`，完成后进入独立 `code-review`
- 调度：B0，serial-only；不得同时启动 Ticket 26 或任何其他正式 implementation ticket

## 1. 放行与依赖

用户在 2026-09-06 的接管指令中明确要求完成 Stage A–F 后正式启动 Ticket 25，视为本票从 `needs-triage` 放行至 `ready-for-agent` 的授权。

- Tracker 直接依赖：`None`
- 已批准事实源：ADR-0005、ADR-0006、ADR-0009、ADR-0014，及 Ticket 25 本文
- 下游：Ticket 26；产物也供 prototype Ticket 18 使用
- 不启动：Ticket 26 及所有后续票

## 2. Scope 解释（消除 25/26 重叠）

Ticket 25 负责 **guest 资产内**的最小桥接代理：可被 initramfs 安装，具备握手、协议版本协商与 token 校验，并可通过其进程 I/O 做正反向测试。

Ticket 26 仍负责宿主侧桥接实现、实际 transport、终端通道、ping、Android 集成与端到端连接。Ticket 25 不得创建根级 `bridge/` 宿主模块，不得实现 Ticket 26 的 shell 垂直切片。

随机凭据与可复现构建必须分离：initramfs 只携带读取/安装 token 的机制；token 由宿主在实例装配时注入。禁止把固定 token 或构建时随机 token 烧进产物。

## 3. Allowed files/modules

产品范围：

- `guest-assets/**`
  - pinned inputs 与 checksum
  - kernel config fragment、必选能力清单与最终 `.config` 校验
  - 可复现构建入口与固定工具链描述
  - initramfs、Provisioning、guest-side 最小 bridge agent
  - 构建工具的 JVM 源码与测试
  - QEMU smoke harness 与测试 fixture
  - GPL 对应源码/构建清单
- `.github/workflows/guest-assets.yml`（若用于 Linux CI 验收）

行政例外：

- `.scratch/droidlayer-platform/issues/25-task-guest-assets-baseline.md`：仅允许本票状态、结果与证据链接
- `docs/reports/tickets/25/implementation-report.zh-CN.md`
- `docs/reports/reviews/25/review-report.zh-CN.md` 由 reviewer 写入，不由 implementation Worker 写入
- 根 `.gitignore`：只允许加入本票生成物/本地工具缓存规则；若无需则不改

Stage E 已提供 root Gradle wrapper。为保持 ownership，本票的 JVM tooling 使用 `guest-assets/` 内独立 Gradle build；除非有可证明的构建阻塞，不修改根 Gradle 或 `app/**`。

## 4. Forbidden files/modules

- `app/**`
- `runtime/**`、`bridge/**`、`network/**`、`storage/**`、`ui/**`、`terminal/**`
- AVF、QEMU Android 打包、jniLib、SLIRP、网络、生命周期、共享目录、GUI/GPU/USB、Docker/Podman 功能实现
- `.scratch/droidlayer-platform/spec.md`、`CONTEXT.md`、`docs/adr/**`
- 除 Ticket 25 外的任何 tracker ticket
- 现有研究结论与 handoff 规则正文
- Git remote、远端历史、`main` 合并

若完成本票必须触碰 forbidden 范围，立即停止扩大实现并回报 Orchestrator；不得自行越权。

## 5. 固定输入

- Linux kernel：`7.2.3`
  - 来源：`https://cdn.kernel.org/pub/linux/kernel/v7.x/linux-7.2.3.tar.xz`
  - SHA256：`8ba259e8e7b13ec6ef0941c8a39ad90b24bd4a4d6c0010ba6bafb794550ecd03`
  - 依据：2026-09-06 kernel.org `latest_stable`；精确 pin 属 ADR-0005 范围内实现取舍
- QEMU smoke 的官方 rootfs：Alpine minirootfs `3.24.1` / `aarch64`
  - 来源：`https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz`
  - SHA256：`f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259`
- 工具链容器：Worker 必须选定 Linux builder，按不可变 image digest 固定；不得只 pin 浮动 tag
- 所有网络输入必须在下载后、使用前校验 checksum；缺失 checksum 即失败

## 6. 已确认测试 seams

这些 seams 来自 Ticket 25 的 Test strategy 与 accepted ADR，用户已授权 Orchestrator 按其启动实现：

1. **Kernel config verifier 公共入口**：给定最终解析后的 `.config` 与必选能力清单，返回成功，或列出全部缺失/错误 symbol 并非零退出。
2. **SHA256 manifest generator 公共入口**：给定有序产物集合，生成稳定、可独立校验的 manifest；不通过实现内部状态断言。
3. **Guest-side bridge agent 进程协议**：通过 stdin/stdout（或等价公开进程 I/O）验证握手、版本协商、正确 token 接受、错误 token 拒绝；不测试私有函数。
4. **单命令 build seam**：干净输入产生 `Image`、`initramfs` 与 SHA256 manifest。
5. **QEMU Provisioning seam**：从空 raw 根盘 + 已校验官方 tarball 启动，观察格式化、解包、凭据注入、agent 安装与完成标志。

Ticket 25 不创建 Full Linux runtime contract suite；该 suite 的首建 ownership 属 Ticket 26。本票以 guest-asset build contract、JVM tests 与 QEMU smoke 代替。

## 7. TDD / 验证计划

按垂直 slice 执行 red → green，一次一个行为：

1. JVM：config 正向校验；再加缺项负向测试，断言错误包含具体 symbol。
2. JVM：manifest 稳定顺序与已知 SHA256；再加 tamper/缺文件失败。
3. Agent：正确版本/token 的握手；再加错误 token、未知版本拒绝。
4. 构建集成：一次命令产出三类交付物；第二次从干净目录构建并逐项比较 hash。
5. QEMU smoke：空 root volume → ext4 格式化 → 官方 tarball 解包 → token/agent 安装 → 完成标志；任何阶段缺失均非零退出。
6. 最终运行所有 guest-assets JVM tests、脚本静态检查、两次 reproducibility build 与 QEMU smoke。

不得把未运行的测试写成通过。若本机缺 Linux container runtime，只能报告“未运行/阻塞”，不能用脚本存在替代实测。

## 8. Acceptance criteria（Worker 必须逐条举证）

- 一条记录在 README/构建文档中的命令产生 kernel `Image`、initramfs 与 SHA256 manifest。
- 相同 pinned 输入的两次干净构建，交付物 hash 完全相同；若不相同，本票不得建议 completed。
- 最终 kernel `.config` 满足 ADR-0005 必选能力；故意移除至少一个要求时，构建以非零状态中断并输出缺失 symbol。
- QEMU AArch64 smoke 真实运行并出现可机读完成标志，且 root volume 内可验证 Alpine rootfs、注入 token 与已安装 agent。
- guest-side agent 的握手、版本协商、token 正/负路径测试通过。
- GPL 对应源码清单包含精确 kernel 版本、source URL/checksum、最终 config、完整构建脚本入口与 GPLv2 信息。
- diff 仅触碰 Allowed files/modules；所有 Forbidden modules 保持不变。
- 中文实施报告完整列出真实命令、退出码/摘要、未运行项、风险、commit。

## 9. Required tooling / 当前环境事实

- 已有：JDK 17、Git、MSYS2 bash/make/CMake/Ninja、ADB
- 当前缺失：本机 Gradle（wrapper 可用）、Android SDK、Docker/Podman、可用 WSL distro、QEMU AArch64、ARM64 cross compiler、cpio
- 完整构建/冒烟需要：Linux container runner、固定 cross toolchain、`qemu-system-aarch64`、`cpio`、`e2fsprogs` 与构建依赖
- GitHub：当前无 origin；`gh` token 失效；SSH host key verification 未通过。因此远端 CI 暂不可触发，除非认证由用户恢复

工具缺失不是降低验收标准的理由。Worker 可以完成可审查实现与 JVM 层验证；完整 Linux build/smoke 若无法真实执行，本票维持 `active` 或转为有明确解除条件的 `blocked`，不得进入 `completed`。

## 10. Architecture safety

以下任一情况必须触发 Architecture Change Gate：无法以同一 kernel 服务两种执行策略、必须弱化 ADR-0005 能力清单/可复现构建、必须改用非官方或重打包 rootfs、必须固定公开凭据、需要扩大桥接命令/宿主暴露面、或需要复制 GPL 参考项目实现。

普通构建参数、文件布局、精确工具版本与 QEMU smoke 参数属于本票实现取舍，记录在实施报告即可。

批准人：Codex Orchestrator（依据用户 2026-09-06 接管与启动指令） ｜ 日期：2026-09-06

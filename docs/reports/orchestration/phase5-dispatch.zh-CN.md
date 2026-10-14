# Phase 5 并行调度与数据边界

- 日期：2026-10-13；主协调者：Codex / 会话配置模型，高推理。
- 背景与 scope：执行用户指定的 Phase 5 第二会话文档，研究后继续原创实现、构建、验证、独立审查与本地 patch 交付。
- 隔离分支：`phase5/graphics-productivity-storage`；基线：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 工作区：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev`。
- 只读活动主仓：同级 `Orchestella_dev`。已有未提交生产接口需要精确核对和逐文件 SHA 记录后才能复制。不得覆盖其文件、工单或设备状态。
- 用户优先规则：不 commit、不 push、不远程 PR、不发布、不上传项目内容。正式报告保留为本地可审查文件；不因历史治理要求提交而 commit。

## 路径、输入、输出和网络

读取范围：当前工作区的生产源码、测试、构建定义、政策、ADR 与 `.scratch`，活动主仓中 Phase 5 文档列明的政策、规格、研究与必要生产接口；用户指定的公开参考目录只读。排除 `.env`、密钥、签名材料、凭据、provider 配置、实例根盘及 Git object 内容。

输出：正式研究写 `docs/research/2026-10-13-phase5-*.md`；决策/规格/工单写 `.scratch/orchestella-phase5/`；报告按原 canonical 规则归档。下载、独立 build/cache、证据和 APK 写 `Orchestella/artifacts/phase5-parallel-20261013/`；公开源码新增于同级 `phase5-references/<project-name>`。原始执行证据用 `.log.txt`/`.json`/`.md`，不用 `.log`；保全于明确 evidence 子目录。保留所有 Class A 资产，当前批不做清理。

联网只发送公开资料查询、公开源码 Git 请求与标准包/构建输入下载请求。端点按官方维护源核对：AOSP/android.googlesource.com、crosvm.dev、github.com 的官方项目、gitlab.freedesktop.org、gitlab.com/virtio-fs、qemu.org、kernel.org、developer.android.com、docs.podman.io、containers.github.io、code.visualstudio.com、coder.com、官方发行版包仓。不得把项目源码/日志/路径清单发送到第三方服务；不启用 telemetry/build scan/云 tunnel。网络失败保留有限证据并报告，不反复重扫工作区。

## 第一轮 supporting research 子代理

研究子代理仅为协调者提供支撑，不领取/修改正式 tracker、产品代码、ADR、共享契约或主仓；允许唯一所属 research 输出及唯一所属公开参考下载。无进一步派生。可共用协调工作区的只读源码，因为没有生产文件写入、测试和分支变更；正式实现 worker 后续使用独立 worktree。

| 所有者 | 唯一输出 | 研究问题 | 独占新增参考 |
| --- | --- | --- | --- |
| graphics-research | `docs/research/2026-10-13-phase5-graphics-avf.md` | AVF 权限、rutabaga/gfxstream/virgl、真实显示传输、共享 buffer 边界 | aosp-virtualization、crosvm、gfxstream、qemu、virglrenderer |
| desktop-research | `docs/research/2026-10-13-phase5-desktop-matrix.md` | 七发行版 ARM64 包、会话/seat、KDE/niri/labwc/轻量桌面，真实可执行首组合 | labwc、niri、weston、waypipe、noVNC、kwin |
| productivity-research | `docs/research/2026-10-13-phase5-productivity.md` | rootless Podman/Docker 与 code-server，认证、生命周期、loopback/LAN 边界 | podman、common、netavark、aardvark-dns、rootlesskit、moby-cli、code-server、openvscode-server |
| storage-research | `docs/research/2026-10-13-phase5-usb-storage.md` | Android USB Host/FD、usbip/9p/virtiofs/SAF、明确无 Root 数据面 | virtiofsd、libfuse、usbipd-win、linux-usbip（稀疏源码） |

研究 workflow：`research`，结合已建立 `wayfinder` 决策图。任务复杂度高、风险中高（权限/许可结论错误会影响产品），验证成本高；要求 primary source 分析能力。使用 `general-purpose` 配置模型，工具不提供独立 model/reasoning 选项，故不伪报精确选型。必须先读 AGENTS、防御约束全部 policy、artifact/host/architecture/report/asset policy。本轮六项并行核对：无生产依赖写冲突、所有权唯一、没有测试写入、共享协议只读、没有分支变更、仅 supporting-output 写入；满足研究并行。

返回结论与引用，不返回整文件 dump。每条关键结论带 URL、revision、源码位置、许可证、权限前提、未运行边界；报告中文，并说明对 ADR 的影响、实际完成与缺口。供应商不可用时记录失败，协调者继续独立工作。

## 生产模块所有权（待规格确定后派发）

协调者独占：共享控制协议、guest-agent、guest init、Android bridge/runtime 接入、Gradle/build、现有 UI 活动、基线依赖复制、规格与状态变更。

worker 仅拥有独立新模块及其测试/报告：图形通道核心；guest profile 脚本；服务模型；只读存储/USB 探针。不得并改 native bridge、guest init、App 页面或 Gradle。每 worker 开始前另建精确 dispatch packet 和独立 worktree，完成后提交文件级本地 patch，不 commit。

## 宿主与设备

SDK `D:/AndroidSDK`，Java `D:/JDK17`；W:/V:/O: 现有映射不改。短盘符先核对空闲再只绑定新工作区。独立 Gradle daemon/cache/output，Class A 依赖只读复用或定点复制。不改系统 PATH、注册表、服务、驱动、防火墙、虚拟化、ADB server。

荣耀 MEY-AN00 / Android 16 / API 36 由第一会话独占；本会话不 install、force-stop、启动 VM、截屏/输入、方向切换或 ADB 重启。准备脚本与本地设备交接请求，只有明确交接才运行。未实测项目保留未运行标签，不用 mock/模板图像冒充 desktop/GPU/container 成功。

## 当前验证与状态

已验证：活动主仓 HEAD 与 dirty 状态；目标原不存在；隔离分支与 worktree 创建成功、clean。未运行：设备验证、Phase 5 编译与产品测试。没有 commit；无外发/远端写入。架构冲突先写 ACR；本调度不修改既有 ADR 或权限白名单。

报告人：Codex ｜ 日期：2026-10-13 ｜ 耗时：未统计

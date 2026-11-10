# 03: Rootfs 预烘焙与 build-time provisioning — 首启零安装

Type: implementation
Status: claimed（u2-rootfs-prebake，2026-11-10）
Priority: P0（压缩“下载完还要等很久”的最大杠杆）
Owner: u2-rootfs-prebake
Blocked by: None（与 01/02 并行；首启计时对齐 01 的 T2/T3）
External gates: 真机首启 `not-tested`；16K 宿主 `not-tested`

## 背景

当前产品首启会做镜像下载、rootfs 装配、包安装、locale、证书、服务 enablement 等；T7 的成功样本里 92 包事务本身可到 <1800 s（`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T7-F）。用户感知的“等很久”主要来自这些首启步骤，而不是 UML 内核 boot。

proot-distro 的“下载完就秒开”并不执行这些步骤：它只解包 rootfs 然后 exec 用户态进程（外部一手来源见研究输入）。UML 方向不应复制 proot 的无内核模型，但可以把 provisioning 前移到构建期，让首启只做挂载 + init + exec。本票做这个预烘焙原型。

## 必须做

1. 新增 `research/uml-rootfs-prebake/`：
   - `build-rootfs.sh`：支持 Alpine 与一个 glibc 发行版（Ubuntu/Debian 二选一）；用跨架构 chroot/qemu-user 或发行版 rootfs 工具构建；
   - `manifest.schema.json` + `manifest.json`：distro、version、包清单、locale、证书、构建时间、输入 sha256、产物 sha256；
   - `init-zero-install`：只挂载 `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2，执行 `/ready.sh` 输出 `READY`；**不调用包管理器、不联网**；
   - `verify.sh`：检查 rootfs 内没有待安装包、没有 provisioning 标记、manifest 与 sha 一致。
2. 两组对照：
   - 当前 provisioning 流程（已有脚本/报告）vs 预烘焙 rootfs 的首启 T2/T3、产物体积、构建时间；
   - 网络关闭时首启能否到 `READY`。
3. 报告：`docs/research/2026-11-XX-uml-rootfs-prebake.zh-CN.md`，含包清单、体积、构建时间、两次构建 sha 对比、许可证/NOTICE 边界。

## 边界

- 不写产品代码、不改产品 provisioning 脚本；研究目录独立。
- 不把预烘焙 rootfs 说成“产品可分发物”；许可证/NOTICE 与对应源码义务另行审查。
- 不承诺所有发行版都可预烘焙；先做 2 个，其余标 `not-tested`。
- 不把“首启零安装”与“系统更新”混为一谈：更新策略另立票。

## 完成判据

- 同一输入两次构建 sha256 一致，或明确列出非确定项及原因；
- 断网首启到 `READY`，日志无 package manager 调用；
- manifest 与产物一致；`verify.sh` 通过；
- 体积/构建时间/包清单入库；敏感数据门通过；
- 报告明确与 proot 的差异（proot 无内核；UML 有内核但首启零安装）。

## 风险与停止条件

- **跨架构装配失败**：记录失败点，退回发行版官方 rootfs + 构建期离线安装的可行路径。
- **包管理器非确定性**：记录时间戳/仓库快照差异；不宣称可复现，直到两次 sha 一致。
- **许可证/NOTICE**：GPL/第三方包义务未审查前，不进入产品打包。
- 需要 Root/unlock/改 SELinux 才能继续 → 停止并记录。

## Comments

- 2026-11-10 / 主会话（ask-matt 研究轮）：建票。输入：`.scratch/uml-zero-device-20261110/spec.md` §3 U2、`docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`、`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T7-F。
- 2026-11-10 / u2-rootfs-prebake：认领；base 62d96e99；worktree /workspace-wt/u2。

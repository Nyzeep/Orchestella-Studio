# 26: 第一垂直切片——创建 Alpine 实例并进入 shell（QEMU 档）

Type: implementation
Status: needs-triage

## What to build

用户从安装应用到进入 Linux shell 的完整路径（软件虚拟化档）：新建实例（发行版目录选 Alpine）→ 下载官方 tarball 并强制 SHA256 校验 → 启动 QEMU（以 jniLib 形态打包、nativeLibraryDir 执行，规避 W^X）→ 挂载根盘与装配盘 → 首次引导装配 → 进入 root shell；实例可停止、可再次启动；实例列表与状态展示；性能徽章显示"完整 Linux · 软件模拟（较慢）"。

**本票同时建立最小桥接基座**（ADR-0009 的落地起点）：传输无关协议 v1 的握手、版本协商、per-VM token 校验、ping 与终端通道（控制面）；数据面通道结构预留。后续票（28/29/30/31）在此基座上扩展命令，不再各自搭通道。

**Blocked by:** 25

## Acceptance criteria

- [ ] 真机（arm64、RAM≥3GB）从安装到 shell 的全流程可重复，全程无 Root
- [ ] tarball 校验失败时创建被拒绝并给出明确原因
- [ ] 实例可优雅退出并再次启动；数据在重启后仍在
- [ ] 桥接握手/版本协商/token 校验在真机通过；token 错误时命令被拒绝
- [ ] 性能徽章正确显示；实例状态（运行中/已停止）跨应用重启持久
- [ ] 模拟器环境给出清晰提示

## Test strategy

契约测试 v0（实例生命周期、终端通道、桥接握手——针对 Full Linux 契约，为 34 的 AVF 实现复用做准备）；实例状态机与 DistroManifest 校验 JVM 测试；真机冒烟清单。

## Prototype/research dependency

无硬依赖；「QEMU TCG 基线」（18）在本票完成后领取。

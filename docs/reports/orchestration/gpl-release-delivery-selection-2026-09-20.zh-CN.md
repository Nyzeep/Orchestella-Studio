# 编排报告：GPL 公开源码交付模型确认（2026-09-20）

- 背景 / scope：用户选择“公开 Git 仓库 + 版本 tag + Release 源码包”作为含项目分发 GPL
  二进制的未来公开发行路径。本次只把该选择固化为主线治理与发布 gate；不创建公开仓库、tag、
  Release、二进制、外部存储或网络写入。
- branch / 基线：`main`；开始时工作树 clean，`main` 相对 `origin/main` 为 0 behind / 7 ahead。
- 决策性质：ADR-0014/0015 已经分别确定 GPL 义务流程化和 GitHub Releases 首发；本次记录的是
  这两个既有决定的交付机制细化，不改变 Full Linux 契约、暴露面、产品范围、许可证选择或
  Ticket 依赖图。

## 实际完成

1. 定义“对应源码发布”：固定版本 tag 与同版本 Release 源码包成对交付；源码包必须包含实际
   GPL 二进制的完整对应源码、补丁、配置、构建/安装脚本、source/license record 与校验值。
2. 将该机制写入 ADR-0014、ADR-0015 和 QEMU Android JNI 供应链 policy。
3. 新建首发前 release gate：公开仓库 URL/可见性、Release Maintainer、可恢复的三年保留责任、
   tag/commit、源码包和 release manifest 均须实际确认；任一缺失即禁止发布。

## 未完成内容

- 未确认公开仓库 URL 或其公开可见性；本地 `origin` 配置不构成该确认。
- 未指定 Release Maintainer、三年保留 recovery location 或 owner。
- 没有 release version、tag、source bundle、APK/AAB 或任何实际 GPL object-code 分发。
- Ticket 26 的 QEMU source、Android build、APK、真机和 GPL delivery gate 均仍未完成。

## 修改文件

- `CONTEXT.md`
- `docs/adr/0014-licensing.md`
- `docs/adr/0015-release-channels.md`
- `docs/agent-handoff/GPL_RELEASE_DELIVERY_POLICY.md`
- `docs/agent-handoff/QEMU_JNI_SUPPLY_CHAIN_POLICY.md`
- 本报告

## 模型、委派、workflow 或 worktree 影响

- workflow：domain-modeling；本次未创建 Worker 或 Child，没有创建/接手 Ticket 26 worktree。
- 后续任何 release audit 的 Risk 至少为 4；必须使用独立 reviewer，且不得由发布执行者自审。

## 测试与验证结果

- 已验证：GNU GPLv2 §3 的源码交付选项；QEMU 的 GPLv2 主许可说明；Linux kernel 的 GPL-2.0-only
  许可声明；本地 `main`/`origin/main` 关系。
- 未运行：构建、CI、QEMU、APK、ADB、真机、tag/Release 创建或公开 URL 可见性检查。
- 文档验证：本次提交前的 `git diff --check` 已通过；内容仅为政策文本，未运行构建或 CI。首个
  实际 release candidate 仍必须由独立 reviewer 核验 tag、源码包、source record、许可证与校验值。

## 风险与后续动作

- 风险：公开源码交付机制已选定，但其真实 URL、负责人和三年恢复保留尚未落实；因此首发 release
  gate 保持关闭。
- 后续：由用户确认上述三个外部事实；Ticket 26 仍须先完成 worktree/owner reconciliation、真实
  Linux/QEMU 红色反馈环、实现、真机验收和独立双轴审查，才能进入下一批 ticket。

## 越界与 Architecture Change Policy 检查

- 未改动产品代码、ADR 的许可证选择、Full Linux 契约、Guest Bridge 命令、Exposure Whitelist、
  tracker 状态或依赖图。
- 本次是用户已选择的 release-delivery 机制细化；未发现与 accepted ADR/spec 冲突，
  Architecture Change Policy 未触发。
- 未 push、创建 tag、创建 Release、变更仓库可见性或外发任何主机侧内容。

报告人：Codex Orchestrator ｜ 日期：2026-09-20 ｜ 耗时：未独立计时

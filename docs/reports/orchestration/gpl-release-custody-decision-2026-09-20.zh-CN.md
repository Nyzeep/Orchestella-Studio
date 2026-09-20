# 编排报告：GPL 公开源码责任与保留方案确认（2026-09-20）

- 背景 / scope：用户在已选“公开 Git 仓库 + 固定版本 tag + 同版本 Corresponding Source
  Release 源码包”的基础上，确认未来公开地址、Release Maintainer、三年保留责任和恢复
  方案。本次只记录治理事实和 release gate；不创建任何外部账号、镜像、tag、Release、
  二进制、源码包或网络存储对象。
- branch / 基线 / commit：main；起始基线为
  539631ca532ad4168b9d878a4c96d91f413bd54b。本报告与关联 policy 由本次
  docs(governance) Conventional Commit 一并提交；Git history 是该提交标识的权威来源。

## 实际完成

1. 记录用户批准的未来主公开地址：
   https://github.com/Nyzeep/DroidLayer。
2. 记录用户指定的 Release Maintainer 与三年保留责任人：Nyzeep。
3. 固定交付结构：GitHub Releases 为主公开交付；独立公开 GitLab 镜像为恢复交付；
   百度网盘为 Nyzeep 维护的私有灾备副本。
4. 明确 GitLab 尚未有账号、镜像或 URL；因此它只是占位目标，不能被写成已建立的公开
   来源，也不能打开 release gate。
5. 明确百度网盘仅保留同版本源码包、SHA-256 与发布记录的私有灾备副本；它不替代公开
   恢复交付。
6. 将上述事实更新到
   docs/agent-handoff/GPL_RELEASE_DELIVERY_POLICY.md 的 Current status，使该 policy
   成为本项治理事实的唯一 canonical document。

## 未完成内容

- 未创建 GitLab 账号、公开镜像、不可移动 tag 或镜像 Release 资产；
- 未验证 GitHub 仓库或未来 GitLab 镜像的实际公开可访问性；
- 没有 release version、tag、对应源码包、release manifest、APK/AAB 或 GPL
  object-code 分发；
- Ticket 26 的 QEMU source/build、APK/native、Linux/QEMU、真机和 GPL delivery
  gates 均未完成。

## 修改文件

- docs/agent-handoff/GPL_RELEASE_DELIVERY_POLICY.md
- docs/reports/orchestration/gpl-release-custody-decision-2026-09-20.zh-CN.md

## 模型、委派、workflow 或 worktree 影响

- workflow：grilling + domain-modeling 记录用户的责任与保留决定；
- 本项未创建、移动、删除或接管任何 Ticket 26 worktree；
- 独立 custody 审计只读检查已获用户授权，和 GPL 外部发布动作无关。

## 测试与验证结果

- 已验证：本地 policy 的 release-gate 条件、用户明确的主公开地址、维护责任与灾备选择；
- 未运行：GitHub/GitLab 可见性检查、GitLab 账号创建、镜像同步、tag/Release 创建、构建、
  CI、QEMU、APK、ADB 或真机验收；
- 文档验证：本次提交前运行 git diff --check；不以文档检查替代实际 release candidate
  的独立审查。

## 风险与后续动作

- 风险：没有实际公开的 GitLab recovery mirror 前，主站故障时的公开恢复能力未被证明；
  百度网盘只能降低私有数据丢失风险，不能替代该公开能力。
- 后续：用户创建 GitLab 账号后，单独确认镜像 URL 与创建/同步授权；Orchestrator 再以最小
  可见操作创建镜像并由独立 reviewer 核验公开可访问性、tag/asset 对等性与三年保留记录。
- 在首次适用 GPL 发布前，独立 reviewer 还必须核验主 GitHub Release、源码包、source
  record、许可证、checksums 及实际发布日期/保留截止日。

## 越界与 Architecture Change Policy 检查

- 未改变 Apache-2.0 项目许可、Full Linux 契约、Exposure Whitelist、Ticket 26
  acceptance criteria、tracker 状态或依赖图；
- 未外发工作区内容、创建网络端点、上传文件或改变任何外部账号状态；
- 本次是 ADR-0014 / ADR-0015 已有 release-delivery 机制的责任记录，未触发
  Architecture Change Policy。

报告人：Codex Orchestrator ｜ 日期：2026-09-20 ｜ 耗时：未独立计时

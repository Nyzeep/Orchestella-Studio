# 01: 清洁检出的Guest准备与严格校验闭环

Type: implementation
Status: completed
Owner: C / ZCode / new-provider/gpt-6.1-sol
Blocked by: None

## 用户价值与来源

不含ignored guest_init的检出可执行Guest CI，canonical漂移和字节变化仍失败。来源：研究§8，C的ci-repro-01/results.json与精确14输入fixture。

## 允许范围与端到端交付

限Guest sync CLI、现有test-local入口及其行为回归；新增prepare-resource先render/核canonical，成功才原子生成resource和父目录，随后strict check。保留App现有Copy打包职责。禁止先覆盖canonical、跳检查、引入App Python preBuild、迁移sourceSets、改kernel/rootfs/依赖锁或用户盘。

## 行为验收

- [x] 缺模板/父目录能prepare，随后check成功；canonical与模块输入bytes未改变。
- [x] canonical内容/CRLF漂移在prepare前拒绝，输出零写入；strict check不修复。
- [x] resource内容/CRLF漂移与缺文件明确非零；prepare后的bytes与canonical严格一致。
- [x] 两次prepare幂等、失败输入保全及主协调完整Guest65项回归通过。
- [x] 13集成APK的guest_init唯一且233538bytes与canonical相同；clean fixture与本机已有资源分别记录，双轴review通过。

## 测试、失败与设备门

Seam：真实CLI子进程与合成目录；red用既有C fixture缺资源/父目录和CRLF误过，再新回归逐slice验证。命令：`python3 guest-assets/scripts/test-sync-guest-init.py`（新增时固定此入口）、`sync-guest-init.py --prepare-resource`、`--check`；完整Guest现有入口在WSL已有外部测试根运行。缺依赖、漂移、I/O失败非零并保全旧canonical。设备不需要；APK构建由13统一。性能不适用。

## 边界、迁移与回滚

不新增IPC/权限/VM owner/网络；仅可再生模板原子写，日志不输出载荷。无用户数据迁移。回滚本票脚本/入口/测试；不恢复“跳检查”方案。依赖仅Python3标准库，原许可不变。

## 证据与完成

实施报告：`docs/reports/tickets/avf-shizuku-01/implementation-report.zh-CN.md`；review同id。原始结果实际保留于`Orchestella/artifacts/avf-shizuku-next-stage/20261015/research-defects/implementation-01/`并即时preservation，按主协调指示不移动已创建原件。已完成五片CLI/入口red→green、新增10项CLI跨Windows/WSL通过、既有6项builderPython通过及3入口场景；canonical原bytes未改。主协调已完成Guest65、新sync10、依赖门6，最终500 APK唯一条目/bytes验收与双轴approve；详见01实施/review和13集成证据。云CI与设备未运行，不能把本地闭环写成GitHub kernel/build/smoke通过。

## Comments

2026-10-15：报告与spec后建立，准许领取；不自动提交/推送。

2026-10-15 C：claimed/in_progress后按本地TDD完成限定四文件实施，现ready-for-review并冻结生产脚本，供主协调运行完整Guest/Gradle/APK。新增prepare仅生成可再生模板，check严格bytes，未知/冲突模式拒绝；独立runtime入口在fixture前验证。未修改canonical/App/UI/版本/ADR/policy，无commit/push/branch/worktree。

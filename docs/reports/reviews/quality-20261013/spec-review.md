# quality-20261013 独立 Spec review

主仓：`D:\Development project(开发项目)\Orchestella开发目录\Orchestella_dev`。分支 `main`，固定 HEAD `8d0e8c75d769ecc645ea102b90a304c8657344a1`；审查 `git diff HEAD -- app/src/main/java runtime guest-assets` 及未跟踪新源码。已读 AGENTS 与三份 DEFENSIVE policy。仅此报告写入；无实现编辑、构建、设备操作、网络访问、commit/push。Standards 轴独立，不合并排序。

1. **P2，已修，静态定点复核关闭。** Spec 第5条（`.scratch/orchestella-platform/spec-quality-20261013.md:16`）要求“新输出不得夺走正在阅读的历史位置”。原实现满额新增与淘汰相抵，historyRows 不变导致补偿为0。现 `TerminalScreenModel.java:268` 每淘汰一行递增累计 evictedHistoryRows，`:496` 将其传至 Snapshot；`ModernTerminalView.java:315` 按累计差值、`:317` 按实际行高计算 anchorY，`:322` 恢复上滚位置，`:89` 在绑定模型时初始化计数基线。满额继续输出100行时可正确补偿100行，修复原路径。`TerminalScreenModelTest.java:216` 的回归用例同时断言历史总数保持2000、淘汰增量100（`:225`），以及原500行锚点位于新400行（`:226`）。仅核查源码及断言，未运行测试或设备复现。

已立即报主并在后续源码中确认三项修正：Spec 第2条（同文件:13）的 Alpine inittab 覆盖已改受管区保留（`runtime-functions.sh:103`）；Spec 第5/6条（:16/:17）的 Activity 销毁中断共享 resize 已改 graceful shutdown（`TerminalActivity.java:437`）；Spec 第2/3条（:13/:14）的已运行 SSH 使用旧配置却写 ready 已改 systemd restart（`orchestella-guest-provision.sh:449`）。不再列为未修缺陷。

另已撤下同步期间的 /init 挂载损坏、截断VT重放、BusyBox修复顺序问题；保留前轮对metadata/签名、失败清除done、单reader、实际resize与跨Activity主题的静态核查记录。本次只定点复核P2相关模型、视图与回归用例，没有广泛重审其他源码。

动态边界：本子未运行JVM/target、Android滚动或设备验证；新增断言存在不等于通过。主会话全target rerun待结果；据主进展，真实Ubuntu仍在安装locale，尚不能在本报告确认首次ready、服务及重启完成。TLS bootstrap、各发行版实际包事务、连续输出下Android阅读位置和真机截图继续由主闭环，均不据源码判动态通过。

结论：**approve，仅Spec静态审查；未修findings=0，原P2已关闭**。动态验收仍pending，不构成运行或发行版全面通过声明。暴露面/越界与架构结论沿用前轮；本次无新增实现或能力，不触发Architecture Change Policy。修改文件仅本报告，审查commit无新增；Standards轴保持独立。

追加窄审：用户新增“所有Linux完全可用所有功能”，范围仅桌面启动、图形通道、GUI资产与安装状态。此前approve限原scope；本追加scope结论为 **request-changes / 桌面链未实现完整**，不影响已关闭P2或独立Standards。

- **P1：生产桌面不是真实guest画面/输入。** `QemuInstanceLauncher.java:58` 接 `QemuWaylandChannel`；后者 `:50` 初始CONNECTED、`:69` 生成本地背景、`:137` 重复模板帧、`:194` 输入仅日志后返回true。`WaylandSurfaceController.java:358` 将这些帧直接交给renderer。`AvfInstanceLauncher.AvfSession` 没有图形接口override，沿用 `ExecutionSession.java:63/:71` 的null。安装包不足以接通App图形链；不声称GPU硬件加速已验证。
- **P1：启动控制与资产缺口。** `DesktopActivity.java:224` 因QEMU默认connected跳过launch，即使发送，`guest-agent.c:398` 的ORCHESTELLA DISPLAY前缀长度20与实际19不符，`:403` 仅处理RESIZE/MODE/FPS，无LAUNCH；Activity忽略结果。桌面脚本/配置/display-agent.c源码存在，但只读解析实际 `app/src/main/resources/initramfs.cpio.gz` 未见这些资产；`build.sh:276` 及bootstrap payload只装guest-agent等基础输入。`guest_init:362` 的Debian条件复制也依赖实际未打包的scripts目录。
- **P1：GUI安装不覆盖所有族且unknown伪成功。** `orchestella-desktop-setup.sh:76/:90` 仅APK/APT，`:106` 未识别发行版check返回0，`:110/:195` 跳过安装仍报success。`:77/:91` 有labwc/foot/Xwayland/Mesa/Pulse等GUI包，baseline未包含。launch `:77` 缺labwc/weston返回127并明确要求先setup；可选display-agent调用 `:42` 又缺其main必需token参数。display-agent的pointer/touch/key `:243` 等只是接受分支，未实现真实注入；不能由脚本存在推导完整交互。

UI修法：基于guest实际状态显示“基础Linux就绪；可选GUI未安装/安装中/失败可重试/组件已验证”，提供直接进入真实终端的可选安装动作。APT/APK可复用已有包表并补齐实际依赖；Arch/pacman、Fedora/DNF、Alma/Rocky/DNF、openSUSE/zypper须明确版本profile并核验仓库包，当前无对应安装分支，未知应非零unsupported。仅在真实compositor、guest帧与输入通道就绪后启用“进入桌面”；当前应说明App图形桌面暂不可用，避免模板入口和静态matrix宣称ready。不能自动承诺RHEL第三方源或所有包名可用。

动态边界更新：主会话本次报告7个baseline及actual Ubuntu已ready；本子未独立运行或核验该结果，也未运行GUI安装、帧/输入、音频、GPU测试。只读源码/打包条目窄查，没有生产编辑、设备、build或upload；上述缺口已即时发主，后续GUIprofile/传输实现和动态闭环由主安排。本追加不据基础ready声称所有功能通过。

报告人：Spec reviewer / Codex ｜ 日期：2026-10-13 ｜ 耗时：未计时

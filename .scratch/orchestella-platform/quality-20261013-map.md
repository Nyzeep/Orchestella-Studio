# Orchestella Linux 工作站品质优化决策地图

Labels: wayfinder:map
Effort: orchestella-platform / quality-20261013

## Destination

完成用户于 2026-10-13 明确要求的联网研究、按名称拉取参考项目、Linux 基础服务完善、终端舒适性、首页实用信息、真正生效的主题、移除强制首次启动引导，并构建 APK 在已提供的 Android 真机验收。

## Notes

- 用户明确要求立即实施并使用并行子智能体。本次地图在研究形成结论后继续实施，不停在规划交付；可逆的修复、安装调试、截图已经授权。
- 保留现有未提交的构建主义 UI 设计。主会话整合主页/验收；终端、主题、guest 各自独占文件，研究不碰生产源码。
- 维持 VM-first、AVF/QEMU 透明降级、随机凭据、输入校验、默认仅回环端口转发；GPL 参考仅研究设计，不复制到 Apache 应用源码。
- 阅读范围：Orchestella_dev 的相关生产源码、测试、治理与 ADR，以及用户指定目录内克隆的公开参考源码。
- 网络：GitHub/API/raw/release 的公开资料和克隆、发行版官方镜像/包仓库；仅下载和普通搜索，不上传工作树/实例/日志。
- 输出：研究放 docs/research；本地决策与规格放 .scratch/orchestella-platform；APK/真机证据放已授权 Orchestella/artifacts/quality-20261013，保留到用户验收，禁止 .log 证据扩展名。
- 真机由主会话独占，不清除用户应用数据或既有实例。W: 映射代码目录用于 Gradle。

## Decisions so far

- [完整发行版与基础服务](issues/59-research-linux-workstation.md)：保留真实 init、恢复 supervisor、必需包和真实就绪门；研究决策已解决，动态结果继续实施验收。
- [成熟终端交互](issues/60-research-terminal-quality.md)：原创持久 VT、单控制 reader、shell 标记命令分段与真实视口；研究决策已解决。
- [首页与主题真实行为](issues/61-research-dashboard-theme.md)：保留构建主义设计、统一主题、真实动态信息和操作入口，移除强制 App 引导。
- 用户追加 Phase 5 图形/容器/外设/存储独立会话，已提供[详细开工 prompt](../../docs/agent-handoff/PHASE5_PARALLEL_SESSION_PROMPT.zh-CN.md)，隔离工作树与设备时间，禁止所有远端推送。

## Not yet specified

- 各发行版实际设备安装/服务/重启的最终结果由实施验收继续收敛；不能用官方包名检查代替运行结果。
- AVF 当前设备 custom VM 不可用时的其他机型实测，交 Phase 5 能力研究。
- 当前真实 App 图形帧/输入通道尚未接通，Phase 5 先闭合此通路再声明桌面可用。

## Out of scope

- 本次不发布远程 release、不提交/push、不替用户开启 LAN 暴露或删除既有数据。
- 不把 PRoot 当作 Full Linux 降级；参考项目权限需求与许可证必须显式记录。

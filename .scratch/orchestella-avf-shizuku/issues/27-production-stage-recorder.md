# 27: 真实生产启动阶段与停止来源接线，先QEMU后扩AVF

Type: implementation
Status: ready-for-agent（现有QEMU/owner不变的范围）
Priority: P1
Owner: recorder与各source模块owner协作；构建/设备主协调统一
Blocked by: 22（completed-offline-reviewed）
Conditional gates: AVF真实事件必须来自相应已合法运行的owner；未过07不产AVF成功receipt
Parent: 22离线工具的生产纵向切片，复用04/09样本边界

## 用户价值、来源与交付

能知道一次真实启动/失败/停止在哪个阶段耗时或中断，为稳定性排错和后续优化提供原始依据；不再把离线COMPLETE_OFFLINE标为生产完成。来源：22报告、详细研究的阶段/原reader边界、近期方案§4/7。

先打通一条现QEMU用户启动→输入准备→进程owner→原认证Bridge/终端/已能观测的服务→正常或异常停止的版本化本地事件闭包。它本身可独立验，不等待pVM。AVF只在既有已合法owner/接口处增加同口径可观测事件；模式/平台失败也准确记last-stage，不隐式尝试VM或更改launcher选择。

## 允许范围与非目标

允许新的小型event组件/有界sink与真实触发点、现bootstrap/launcher/监督/Bridge handler最小observer、离线工具兼容及行为测试。模块共用文件由原owner写锁协调。禁止新VMowner/权限/exec、修改Guest命令集或偷偷新增QA命令、第二reader/解析任意终端正文、全局诊断/上传、逐字符同步落盘、大规模性能重构。

现真实pipeline的顺序先核：例如bootstrap可能在adapter模式/API检查前；22旧schema若假设preflight全先于inputs，必须诚实版本化event偏序/来源适配或标缺失，不回填过去时间/伪排序。不把CLOCK_MONOTONIC、JVM/Python clock、Guest uptime和Android elapsedRealtime混减。

## 行为验收

- [ ] 显式诊断关闭时零持久采集/文件/queue/重试，不影响Guest正常执行；启用sink条数/字节/保留范围/超限策略明确。
- [ ] 同一次launch/source/APK/ROM alias/请求与实际策略/owner/session/generation、Android单调时基和真实last-stage绑定；rawexception/path/口令/token/候选/按键/metadata/embedded载荷不入事件。
- [ ] 成功、bootstrap校验失败、API/权限/模式拒绝、QEMU回退、cancel、迟到错代、sinkI/O失败及容量满保持真实结果，sink失效不假ready、不阻塞owner收尾。
- [ ] 原Bridge唯一reader和鉴权前置不变，stage来自原handler/owner信号，不抢读；没有固定QA挑战接口时记not_observed，不按终端提示符制造guest_command_ok。
- [ ] run返回、VM状态、通道连上、agent认证、终端命令、服务ready分别记录；customLinux不等待Microdroid payload，未知不填默认成功。
- [ ] stop意图、owner底层退出、资源/FD释放三事实独立，EOF/FGS通知结束/退出码0不能单独签normal stop。
- [ ] 新QA QEMU正常与至少一个受控失败有真实事件原件，非所有字段必须先有；缺必需设备证据的样本结构校验器拒成功组。synthetic永久单列、工具不认证物理真伪。
- [ ] 开关/事件接线开销记录方法明确，未测不称零开销；不报告硬件提速或未采CPU/RSS/GPU指标。

## Seam、red命令与设备门

优先现launch sequence、bootstrap、Bridge公共路由+注入clock/sink；red覆盖真实阶段/失败/关闭行为，green只加observer。App测试、runtime:contract:check、相关AVF/QEMU契约和Python recorder/validator按改动实际执行，确认JavaExec入口不空跑。真实QEMU新QA盘/无事务窗口；AVF样本/目标QA挑战/服务信号未闭合时不作为本票已有能力。

不要为了让全部字段齐而创建新Guest bridge命令：若确需命令版本扩张，另票遵循协议协商/ADR与scope后处理。本票可以完成真实阶段/缺项诚实闭包，不伪造09的完整benchmark前置已满足；09仍需真实AVF、完整来源/正常stop/资源工作负载。

## 回滚、兼容、许可与证据

事件schema有版本且旧synthetic输入历史保留；新旧工具需明确拒不兼容而非静默reinterpret。无用户盘格式/实例迁移，不改变策略结果；rollback定点observer/sink，不清旧证据。依赖标准库/现Android API，自研，不新增遥测或第三方服务。

报告 `docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`，双轴review同id；外部27专属批次/真实receipts与preservation。当前22仍只离线完成，本票仅建立尚未实现；旧127项不计本票已通过。

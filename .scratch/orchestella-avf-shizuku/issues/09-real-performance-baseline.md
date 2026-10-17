# 09: 同设备同资源的AVF/QEMU性能基线

Type: validation
Status: blocked-real-avf
Owner: 基准代理 / 设备维护者
Blocked by: 07真实owned AVF闭环、兼容nonprotected平台及合法owner；生产阶段接线/同资源样本，非device-availability

## 用户价值与来源

用户可理解启动与工作负载差异，研发能定位首个真实瓶颈。研究§7、B参数/API/网络接线边界与D样本接收工具。08不是强制前置：若其阻塞问题已影响样本则先记录失败，再按证据设边，不为顺序好看串长链。

## 交付范围

固定APK/source/ROM/module/资源/kernel/rootfs/hash、充电温度/背景负载与冷/热类型，按run/ready/stop及CPU/内存/I/O/vsock/网络阶段收原样本。真实AVF已通过07且独立核验真实性才进成功组；QEMU、fallback、失败和unknown分组保留。只用新QA数据、可控本地网络端点，不改用户盘/global thermal/sysfs/GPU/kernel。

## 验收与统计

- [ ] 同1vCPU/RAM/磁盘/输入，两路径相同负载；MATCH_HOST不冒充任意核数。
- [ ] 首次下载/装配、ready盘冷启、热页缓存与持久config复用分开，不把装配差异当引擎收益。
- [ ] 每阶段单调时间/来源receipt、失败样本、命令参数、资源/RSS/peak、噪声条件齐全。
- [ ] 小样本median/range，p95/p99仅有足够样本与明确算法；无数据不先定目标。
- [ ] 正确性/正常停止、实例身份/盘引用保全；网络未接线能力与GPU未测不写已交付。

## 测试、失败、回滚与许可

Seam：04 CLI合成校验+07真实receipt+固定QA工作负载；先reject回退/错session/混资源，再设备pilot确定样本数。缺门即标失败或blocked，零后台重试/外发。具体benchmark命令与资源规模由pilot固定README，不用用户盘fio/dd。性能是本票唯一实际测量，当前未运行且收益未知。停止采样即回滚，不持久改配置；自研工具+原依赖许可。

## 报告与完成

中文验证报告avf-shizuku-09，E/benchmark-09/含原样本+preservation。当前AVF样本0；完成需真实同资源样本、所有失败和统计口径，由独立review确认，结构JSON校验不能代替设备真伪审查。

## 2026-10-16 Worker E执行补记

本批真实AVF样本仍0，Status保持blocked-real-avf；设备可用但07未独立闭环，不能沿用历史“零设备”为理由。主协调独占设备/Gradle/版本/安装与VM启停，E未调用adb/UI/Gradle。

已复用04现100项并实现[22离线typed recorder](22-offline-typed-recorder.md)及最终27项新增合成回归；本批benchmark/evidence/final-tools-clock-fix为127 tests/17.021s/exit0、0失败错误跳过。早期126项结果与falsey callable clock修复的独立red/green保留，不累加覆盖。它只输出offline_stage_trace，Python/注入时钟，真实receipt0，不导出device样本。各slice red/green与preservation保留，不作为09耗时或性能结论；最终清单和保全核验在本批benchmark/closeout/。

最小原owner插桩包已提前交主协调并定稿至本批benchmark/owner-instrumentation-packet.zh-CN.md；生产接线暂不扩散。准确门：同代launch→preflight→bootstrap→真实manager/create/run/state→实际vsock→原认证单reader→固定QA结果/services→正常停止与资源释放，Android统一elapsedRealtimeNanos、绑定实际dirty源码/APK/ROM/module/owner与精确1CPU同RAM/QA盘/输入hash。当前BridgeClient无qa-uname-cpu-v1通用执行接口，E不通过终端正文或新reader补门；kernel not_observed。

07后由主协调以新QA盘和固定本地受控网络做pilot，预先固定AB/BA、冷热/首次装配组、温控/充电/噪声与样本数，全部失败/取消/fallback保留。小样本仅n/median/range；不drop cache、不改thermal/sysfs、无用户盘写基准。正式中文报告为docs/reports/tickets/avf-shizuku-09/implementation-report.zh-CN.md；本批benchmark与preservation为Class B长期归档。无AVF/QEMU收益数字，无commit/ref/版本/生产改动、外发或架构变更；真实性与独立review门未关闭。

## 2026-10-16 resume-01 最终证据收口

E/device/qa3-manager-capability-receipt.json真实getCapabilities bits1，仅protected=true、nonprotected=false，当前ROM不兼容现nonprotected custom Linux。E/device/qa3-context-fd-receipt.json中App新QA state可写，US所给App Context目录EACCES；App→US合成PFD通过仍非G4实际VM FD入口、合法持久state或owner。真实AVF样本0；07/08/09/10按准确外部门阻塞，ACR-01用户未裁定。

22离线工具和127=100+27项、双轴approve已完成；COMPLETE_OFFLINE不是Android原始recorder或真实性认证。App747/8框架PASS均不产生AVF样本。当前Bridge固定QA来源及bootstrap/preflight真实偏序缺口仍在原owner动作包，未解析终端或回填时间。无同资源AVF/QEMU真实基线、冷热样本或收益，保留全部失败，不做用户盘写压测。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

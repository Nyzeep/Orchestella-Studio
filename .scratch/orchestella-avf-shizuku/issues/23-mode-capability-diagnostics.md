# 23: 模式事实、调用身份与诚实的硬件准备入口

Type: implementation
Status: ready-for-agent
Priority: P1
Owner: capability/App授权owner
Blocked by: None
Parent: protected研发近期计划；复用02/03/05既有事实，不重做client

## 用户价值与来源

用户能分清系统声明支持哪种VM、哪个进程有权限、应用实际跑了什么；protected-only不再被描述为“无虚拟化硬件”，也不引导重复grant解决模式门。来源：研究2026-10-17-avf-device-recheck与avf-custom-linux-route-study§1/4/5/7，近期方案§3/G0；当前facts/evaluator只看权限与声明的静态缺口。

## What to build / 范围

从现facts/probe/evaluator到授权页面的一条完整只读状态切片：protected/non-protected/unknown各保留来源、actor、采样时刻和ROM绑定；系统声明/API shape/权限/实际Guest分层。当前已接受的只读探针可用时读取，普通App不能安全读取时unknown并给具体诊断，不为了状态偷偷启动VM/UserService或安装非SDK库。

允许现capability/probe/设置/Main/Setup相关展示和测试。非目标：自动改执行策略、把protected接入产品VM、protected-required新持久属性、grant/revoke、品牌/芯片永久黑名单、新owner/IPC、kernel/盘变化。读取模式能力不等于声明硬件物理极限；现READY枚举若含义不足，在既有预检范围加数据分层，不静默扩产品契约。

## 行为验收

- [ ] protected-only/nonprotected-only/both/neither/读取失败/仅部分actor可取分别明确，异常不写false或通过。
- [ ] App权限、Shizuku server/US身份权限与VM模式能力不得混填；API/属性同源不计两个独立实测。
- [ ] 当前平台模式不满足现adapter时说明“当前系统不支持本应用所需模式/尚未验证”，保留QEMU路径，不称芯片没EL2。
- [ ] 页面onResume/显式复查使用同一代facts更新，旧异步回调不覆新状态；不改用户CPU/RAM/根盘。
- [ ] 全有权限但接口不可达/模式unknown时仍不声称Guest或性能成功；不发隐式create/run/授权请求。
- [ ] 已存在的client拒绝/death、双权限/两命令和1CPU说明不退化。

## 测试seam与red命令

优先现AvfCapabilityEvaluator/AvfHostFactsProbe/授权状态公共边界；先新行为测试复现“permission满足但模式不符/未知”，再最小green。红命令起点 `:runtime:capability:check`、App `AvfHostFactsProbeTest`，按实际build确认main式任务运行；新增页面测试不只匹配字符串。设备当前ROM对照后，记录UI只读状态与固定系统查询；无法访问记unknown。单测不替OEM/其他ROM实测。

## 失败、数据与兼容

失败只读、固定原因、不自动重试/开特权进程，不保存serial/完整fingerprint/按键/token/raw设备日志。字段兼容旧facts调用者，旧仅MANAGE输入不能绕CUSTOM；无FD/VMowner迁移。性能无主张；不将额外API轮询加到主线程每帧。

## 迁移、回滚与依赖

无需实例数据迁移；回滚局部事实/文案/读入口，保留用户配置和既有授权。默认无新第三方依赖；确需库需固定许可/版本并另证安全，不能为展示绕系统策略。accepted ADR不变；新保护意图/降级语义转ACR-02。

## 证据与完成门

报告 `docs/reports/tickets/avf-shizuku-23/implementation-report.zh-CN.md`；独立规范/Spec review同id；外部 `Orchestella/artifacts/protected-avf-execution/<批次>/23/`，即时preservation、不.log。完成=模型/探针red→green+实际接线构建/lint+页面可验或准确设备限制+双轴审查。当前仅工单建立，尚未实现/设备验收。

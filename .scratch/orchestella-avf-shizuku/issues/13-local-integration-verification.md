# 13: 本地预览集成、回归与交接

Type: integration
Status: completed-local-and-framework（本批限定集成；外部门见对应票）
Owner: 主协调
Blocked by: None（本票本地/框架批次已完成）；真实VM/物理/OEM/软件权限UX/发布不在完成声明内

## 用户价值与范围

把已完成的准备升级形成可构建、可审查的唯一主仓结果。来源spec全部已解决范围。只集成01–04、版本审计/许可/中文报告，不等待07真实性门来做本地检查，也不冒充整条AVF已交付。禁止自动commit/push/tag/release/安装，禁止把ignored raw证据整体force-add。

## 行为与验证验收

- [x] 首次新包前递增0.5.0/code500；旧405整包SHA不变。
- [x] App715零失败错误跳过、contract/capability/20AVF入口/QEMU相关入口、Guest65/sync10/依赖门6、工具100均实际执行；构建与lint通过（747Warning）。
- [x] 新APK版本/包名/同证书验真，unique guest_init与MITnotice原bytes相同，kernel/baseinitrd/manifest未变，无key/密码输出。
- [x] 规范与Spec独立双轴覆盖新增源码与实际APK，三项P2已修复复验、最终approve，无未关闭发现。
- [x] map/frontier/依赖图/中文报告与证据保全已收口；零设备、云CI未跑、AVF真实性/性能未验，精确blocked保留。

测试seam为现Gradle实际task+Guest CLI+工具fixture；先定位01–04 red，集成只验green与无退化，不写镜像实现测试。设备不需要完成本地集成；05–09等设备验收仍blocked。性能不适用，无提升主张。

## 边界、回滚与许可

单main/单worktree、无提交；保留用户未提交归属与ClassA资产。无新增IPC/FD VMowner，client MIT notice/固定依赖齐。回滚本轮未提交diff需用户后续指示，不使用reset/clean；数据/405发布保持。报告 `docs/reports/tickets/avf-shizuku-13/implementation-report.zh-CN.md`，双轴review对应；E/integration/与preservation。

## 当前状态

上述本地门全部完成，详docs/reports/tickets/avf-shizuku-13/与独立review同id。最终APK106506176B，SHA256500643f1305bf01267ed76bb5bbdd17f408957ddf7c27e24f73e383f50a2583c，外部E/delivery/及独立preservation副本。HEAD仍428860a/main/单worktree，改动未提交；没有push/tag/release/安装。真实AVF/性能0样本，05–10硬件/ACR门保持原状态。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

503八场景为七向导一输入全部PASS，真实creating仅marker。13正式实施报告追加本批精确产物hash/证书/固定bytes与证据。C17三P2、QA4末P2的最终双轴均限定approve，18/19/20/22原审与相应复审齐；不虚构503整包重新独立双轴或把500的13审查当503新审。

QA4 code4/0.1.3-identity的R/qa4-device-summary.json为PASS_LIMITED_REAL_QA_SESSION_AND_LIFECYCLE：普通permission session同epoch跨旧5秒点8277ms仍可readback，normal remove；App force-stop→US退出且server活；固定shell server TERM→App观察SERVER_DEAD_PERMISSION_RESTORE_NOT_CONFIRMED、US0；官方starter重启后binder/clientGranted读回true、重新bind成功。最终QA双DENIED、US0/server1、crosvm/virtmgr0。原件R/device/qa4-final-*及preservation；早期QA3强停证据R/device/after-qa-app-death.json保留。无权限变更/VM，未强制真实B在途timer交错，不能签真实deadline交错、完整软件grant/restore或VM死亡。

E/device/qa3-manager-capability-receipt.json真实getCapabilities bits1，仅protected=true、nonprotected=false，当前ROM不兼容现nonprotected custom Linux。E/device/qa3-context-fd-receipt.json中App新QA state可写，US所给App Context目录EACCES；App→US合成PFD通过仍非G4实际VM FD入口、合法持久state或owner。真实AVF样本0；07/08/09/10按准确外部门阻塞，ACR-01用户未裁定。

保留本批runtime/contract/AVF20/QEMU64任务exit0、Guest65/sync10/依赖6与工具127原件；不累计715→746→747或局部/重复套件。工单实际22文件，无21。原01–04/16历史范围保持；主协调正式编排报告另写，本次未操作设备/Gradle/git/版本/签名/生产。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

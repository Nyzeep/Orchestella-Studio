# ACR-02: 保护模式的用户意图、降级规则与隔离承诺

Type: decision
Status: needs-architecture-decision
Priority: P1（具体产品策略实现前裁定；不阻塞独立诊断/QA）
Owner: 用户（产品最终裁定）；主协调（证据、推荐与影响检查）
Blocked by: user-product-decision
Related: ACR-01只负责owner/state/FD能力与死亡收敛；本票不合并其技术实现

## 已确认与仍需决定的边界

用户已确认近期protected优先研发、non-protected持续兼容、QEMU保证当前完整Linux，并授权调整优化文档。此研发优先级无需重复批准。

尚未批准：新增持久“必须保护”实例要求/安全保证、默认策略降级规则、存储/owner迁移或新的Guest暴露面。本票给具体可审阅proposal，保持未决，不把“很好/以protected为主”解释成所有安全承诺已被批准。

## 证据与真实冲突

1. 研究2026-10-17-avf-custom-linux-route-study§4/5/10：官方protected Microdroid可通过显式vsock双向通信；ADR0010修订§4“阻断双向数据交换”过于绝对，Custom VM不应一概限定non-protected。需要定点勘误，不静默改accepted原文。
2. 同研究与设备重检：AVF是框架，protected位是模式声明，不能唯一识别pKVM；当前有Gunyah集成线索，firmware/QTVM/without-firmware等安全属性不同。硬件执行、内存隔离、boot验证、attestation/密钥供应必须分开描述。
3. ADR0001/0002要求FullLinux策略中性与同实例可互换；ADR0003的L1+L2成本承诺、ADR0010安全表述与新增用户保护要求可能产生冲突。性能fallback不自动等于安全需求可退化。

## 推荐的具体产品语义（待裁定）

- 现普通FullLinux实例继续按已接受能力/失败/资源语义运行；是否在具体模式失败后回退必须先确认原owner已停、盘可重用，并准确显示实际路径，不把pVM失败当“安全保护仍在”。
- 若未来用户显式选择必须达到某保护性质，建议不满足就诚实拒绝，禁止静默变成non-protected或QEMU。该性质应通过策略中性的能力/需求数据表达，具体字段/持久化/兼容迁移在决策后形成设计，不预先把AVF专属类型塞契约。
- `protected`内存隔离不等“宿主看不到所有输入/共享内容/ROFD”；proxy收到含秘密initrd仍可读。无firmware实验或不同hypervisor不继承整套pKVM/attestation保证。
- VSock字节通信/只读共享/显示数据面/共享内存分别审查；保护内存不是禁止一切Guest↔Host通信，也不是允许任何新通道。原token/单reader/loopback/白名单保持。
- 不把Microdroid工具payload当FullLinux实例；若未来仅能交付窄工具环境，需另作类别/能力决定，本票不默认削弱systemd/Docker/独立Guestkernel承诺。

## 允许/禁止范围

本票允许威胁模型、精确术语、状态/UX示例、ADR勘误proposal、模式/声明/实际成功分层与兼容影响。禁止先写生产保护选择器或迁移旧实例、扩大权限/IPC/共享、实际执行系统策略修改、把pVM实验失败强行自动降级绿灯。

ACR-01继续单独裁定合法namespace、真实caller/owner、最小FD/embedded秘密读取、App/server死亡stop、state/盘迁移回滚。两票可以并行准备；任何产品改变同时触及时必须两个相关决策均清楚，不能把它们串成无意义前后等待或用一票批准代另一票。

## 决策验收

- [ ] 用户对普通兼容实例与显式保护需求的降级规则作具体选择，说明拒绝/提示/重试/替代路径。
- [ ] 保护性质的证据来源/ROM/真实运行范围明确，声明/API/guest/firmware分别展示，不承诺未知隔离强度。
- [ ] ADR0001/0002/0003/0010及spec/未来07/08/09受影响内容列明，必要勘误/新ADR/CONTEXT同步经用户裁定后才落accepted状态。
- [ ] 旧实例无保护要求字段时的兼容默认、损坏/未知/撤回、重启/ROM变更、创建/恢复/回退时行为有例子。
- [ ] 非SDK API和模式实现与产品安全语义分开，修改owner/私有盘另交ACR-01，不因本票通过假定硬件/Guest可用。

## 测试设计、失败与设备门

决策前只设计状态场景，不写新产品策略。裁定后红例需验证“required保护不满足/unknown/撤回/实际模式变更不会自动启动弱路径”，普通兼容实例保持旧行为；使用现launcher/capability公共seam和真实stop门。当前没有已选schema/红命令，也没有新测试通过；不为决策文档捏造结果。语义选择不必等全套硬件实验，但具体安全主张必须有对应真实证据；性能不适用。

## 迁移、回滚、依赖与报告

当前无数据/FD/权限迁移、无依赖。未来字段迁移/回滚要保留用户显式需求，未知字段不擅自弱化；不能靠rollback版本将保护要求消失。源码许可/源码交付按最终实现审查。证据引用原研究/AOSP固定vm_shell与virtmgr，不复制真实秘密。

决策报告 `docs/reports/tickets/avf-shizuku-acr-02/implementation-report.zh-CN.md`（明确decision，不当实现通过），review对应；外部本轮acr-02专属目录与preservation。当前状态needs-architecture-decision，仅已创建proposal，没有更改accepted ADR或生产行为。

# 02: 两权限与诚实的硬件虚拟化准备状态

Type: implementation
Status: completed-local（设备UI/权限验收在05/06）
Owner: A / 主协调
Blocked by: None

## 用户价值与来源

用户可知道缺哪项权限、当前只是预检、为何多核配置回退。来源研究§4/§8、C对facts/probe/evaluator/wizard/MainActivity/SetupWizard/Settings的静态证据，旧403实机反证。

## 允许范围与端到端交付

现有facts/probe/evaluator/向导/授权复查/文案与测试统一MANAGE和CUSTOM。ADB模板两命令；任一缺失可进入授权流程；全有仅表述声明与权限预检，API/nonprotected/实际Guest仍待启动。明确当前精确1CPU限制且不改用户选择。禁止改adapter策略、增加隐藏API豁免、隐式创建VM、grant权限或新VM进程。普通client接线由03单独负责，协调同文件顺序避免冲突。

## 行为验收

- [x] 分别缺MANAGE/CUSTOM、全缺、全有、读取失败有固定状态；两权限九组合与probe行为回归通过。
- [x] Main/创建向导/设置与模型、两条ADB模板一致；复查刷新卡片/按钮且保留硬件值（UI接线静态审查，设备绘制未验）。
- [x] 文案不承诺授完即AVF成功、近原生/pVM隔离；当前实际session徽章与QEMU回退保留。
- [x] 多核配置不自动改为1或MATCH_HOST；现适配器精确1CPU说明齐全。
- [x] feature不支持/unknown不隐式启动VM；原软件执行路径代码保持。

## 测试、失败与设备门

Seam：现有AvfHostFactsProbe、AvfCapabilityEvaluator、AvfAuthorizationWizardModel与App授权模型契约；先red覆盖CUSTOM缺失与两命令，再green。执行capability:check、App suite，查实际JavaExec入口。设备UI/权限状态待设备票05/06，不凭单测写真机通过。性能不适用；不改变失败后回退语义。

## 边界、迁移与回滚

无新IPC/权限请求/FD/owner，已有两权限仅读取。兼容既有实例和硬件值；若constructor扩展必须同步调用方，不用旧单boolean确认绕过第二权限。定点撤回模型/读取/文案/测试，不改根盘或Android授权。无新增依赖许可。

## 证据与完成

E/implementation-02/；中文实施/review avf-shizuku-02。行为red→green、App715全套/零失败错误跳过、capability实际入口、lint0error/747warning、最终500构建与双轴approve已完成。首审卡片旧状态P2已定点修正并复审闭合。真实UI/系统权限/AVF设备门仍未验，记录在05/06/07，不以本地完成替代。

## Comments

2026-10-15：可与01/04并行；与03共享UI由同owner顺序实施，非功能性blocking edge。

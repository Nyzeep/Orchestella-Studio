# 04: 只读设备取证与可信样本接收工具

Type: implementation
Status: completed-local（不认证设备真实性）
Owner: D / 主协调
Blocked by: None

## 用户价值与来源

提供真正可运行的验证工具，设备接入后可复用，防止回退/伪样本污染AVF性能集。来源研究§6–7，D外部harness与B对custom callback的更正。

## 允许范围与端到端交付

将经审查的read-only preflight与样本校验/汇总、fixture和README提升到现有tools/device-validation目录。固定allowlist命令与显式serial，不执行grant/install/start/stop/settings/su，不存serial/原始dumpsys/logcat或实例metadata。sample关联APK/commit/输入hash、资源、冷/热、engine/session/generation与实际receipt引用；样本文件验证只证明结构与关联，不认证物理真实性。

## 行为验收

- [x] fake adb证明只用固定只读命令，参数精确传递、无shell拼接；无设备/拒绝/超时/命令失败不写成功。
- [x] 输出仅允许字段，权限/API capability无typed探针则unknown，不把feature推成custom ready。
- [x] 缺证据、hash不符、乱序、错session/代际、回退/unknown/失败、资源混组拒绝或单列失败。
- [x] synthetic始终单独，AVF实测计数0；不得因JSON engine/boolean写“真机通过”。
- [x] receipt路径逃逸/符号链接/额外秘密字段等拒绝；单调阶段符合customLinux，非Microdroid payload callback门。
- [x] 小样本只median/range，失败样本保留，输出不含按键/口令/token/embedded载荷。

## 测试与设备门

Seam：Python公开CLI/假adb子进程/合成证据根；先拒危险输入与伪成功，再green。执行 `python -m unittest discover -s tools/device-validation -p 'test_avf*.py'`（实际文件命名以交付README固定），样本全合成并明确标记。物理真实性/设备性能由07/09验收，本票不需连接设备。性能口径为工具统计单元验证，不提供性能结论。

## 生命周期、回滚与许可

无新增IPC/FD VMowner；只读取用户明确证据根内allowlist小文件，未知端点不请求，不上传/建后台队列。输出原子/不覆盖旧证据，失败保留旧有效结果。回滚本票工具，保留历史证据和保全资产。依赖Python3标准库、自研GPL项目代码，无上游代码复制。

## 证据与完成

E/implementation-04/、E/validation-design/；中文实施/review avf-shizuku-04。研究harness测试结果与production工具提升后验证分述。已完成负例/正例、README与双轴最终approve；原运行时junction P2闭合，主协调100项独立复验通过，详正式review报告。真机未运行始终明示。

## Comments

2026-10-15：独立于VM owner决策，可与01/02/03并行。

2026-10-15 D：已提升6个自有AVF文件，未改现有terminal/distro工具、App/Guest或VMowner。99项公开CLI/fake adb子进程/合成证据根回归通过；unittest discovery与直接脚本两入口各实跑同一99项，0失败/错误/跳过，不相加为198项覆盖。研究原型96项另述。必需环境变量AVF_VALIDATION_TEST_ROOT为明确外部绝对路径；命令见tools/device-validation/README.avf-validation.zh-CN.md。真实adb/设备/VM/Guest均未调用，物理真实性与性能仍待07/09。中文实施报告docs/reports/tickets/avf-shizuku-04/implementation-report.zh-CN.md，原始red/green及最终输出E/implementation-04/evidence/，均有preservation。当前仅ready-for-review，独立review由主协调安排。

2026-10-15 D 规范轴P2修订：按主协调裁定将运行支持范围更正为Python3.12+且具备可调用Path.is_junction，移除False回退；公开CLI/IO先拒绝低版本/缺能力，固定RUNTIME_UNSUPPORTED、目标根零写入。新增一项公开入口负例（六组合）先red后green，p2-runtime-unittest与p2-runtime-direct均实际100项通过，0失败/错误/跳过；旧99结果不覆盖。证据E/implementation-04/evidence/p2-runtime-*及preservation，README/实施报告已更正。待reviewer复审批准，Status仍ready-for-review，不标completed；无设备/架构改动。

2026-10-16 Worker E复用补记：04保持原completed-local边界；未改avf_preflight/avf_benchmark/avf_evidence_io/avf_test_fixtures/test_avf_tools。先有公开seam实际red后建立[22离线typed recorder](22-offline-typed-recorder.md)，新增独立组件/测试并更新既有README。最终原100+新增27共127 tests，17.021s，exit0，0失败/错误/跳过；原件为本批benchmark/evidence/final-tools-clock-fix.{json,stdout.log.txt,stderr.log.txt}及preservation。早期126项与额外falsey clock修复red/green保留，重复slice不累加覆盖。Python trace标offline_stage_trace/synthetic_seam和Python或注入monotonic，真实receipt/AVF样本0，不产生device成功样本；Android来源/生产插桩/物理真实性门仍由07/09与原owner签收。设备已由主协调发现，旧“无设备”为历史事实；E未调用adb/UI/Gradle或改版本/生产owner，无新架构、外发或Git写操作。22独立审查待主协调安排；最终清单/保全核验见本批benchmark/closeout/。

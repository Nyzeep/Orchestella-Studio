# 05 / QA4 最终 Standards 定点复审：S-A-03

结论：**限定 approve，S-A-03 / P2 在所列冻结 QA4 源码与实际 Activity 的 JVM 回归范围闭合，无新增必须修改发现。** 本文件追加最终结论，不覆盖 `standards-rereview-20261016.zh-CN.md` 的原 QA3 发现和失败历史，不认证真实 Android deadline、Binder death 或权限软件 UX。

## 固定点与本次范围

沿既有 main@428860ad9a1cb335f8be78c157b2ff57faf2a6da 的 dirty 工作区与原 batch-01，本次仅复核 S-A-03。外部 Q 为 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/resume-01/qa-deadline-fix/`，原件孪生为 `resume-01/preservation/qa-deadline-fix/`。

固定清单 `Q/identity-v4-frozen-source.json` SHA256 为 **`6f83dcaffb72038776fd8088115aac93a6fdff0e0113a09590e73d36ec25af15`**，与主协调指定值相同。只读精确 source、before-qa3、qa3-to-qa4.patch、red02/green02、qa4-compile-01 及其孪生；另按主协调新增指针核 qa4-apk-01 的19输入来源和构建记录，未操作构建或设备。沿此前已完整读取的规范协议，不重读前置或重做其他模块，不读取另一轴。

独立核验脚本和摘要为 `batch-01/resume-01/review-standards/qa4/verify-qa4.py`、`frozen-verification.json`；即时孪生位于 `resume-01/review-standards/preservation/qa4/`。核验只读固定文件/hash/原始输出，不执行被审代码、编译器或测试 runner。

## S-A-03 闭合依据

实际行为修复限冻结 `source/src/main/java/io/github/nyzeep/orchestella/researchauth/ProbeActivity.java`，SHA256 `61f98bbabe87cd708b7b95ef25d5b67c331e5f6bc8243dbecb6b8bed0617eb91`，常规1起点行145–176、209–225、237–251。

- **每次 submit 独立身份**：获全局 IN_FLIGHT 后建立新的 Object token，登记为该 Activity 的 activeOperation。deadline 同时检查 token、visible、binding==epoch 和 busy；同一 permission session 复用 epoch 时，旧 A timer 不能退役当前 B。
- **旧 finish 不清当前操作**：完成回调只 removeCallbacks 自己的 deadline，再核 activeOperation==token；旧完成重放不清 B 的 busy/token，也不覆盖 B receipt。当前完成才清自身 busy/token 并交付结果。
- **迟到 snapshot 不重开权限 session**：worker 不再直接写 permissionSession。开启只发生在主线程且当前 token、visible、binding/epoch 均有效、返回固定 sessionState 时；deadline、end、death、旧 Activity 的晚到结果不能重开已退役会话或覆盖退役 receipt。
- **IN_FLIGHT 不假取消**：deadline、close、onStop、death 和 UI完成回调都不释放全局 flight；仍只有 CALLS worker 的 operation 实际返回后在 finally 释放。当前真正 deadline 会退役结果并明确 UNDERLYING_NOT_CANCELLED，busy/flight 在底层仍阻塞时保持；其他 Activity 不能据 UI截止发起另一调用。晚到结果清自身局部 busy 后仍须 epoch/visible 门，不复活旧 session。没有 Future.cancel、线程中断、自动重试或自动撤权。

这满足20261016 Worker A/reviewer 的旧代、截止/底层完成分离和固定权限恢复边界，以及 REPORTING 的可追溯结果要求。旧 timer 与新调用的混淆已被每次 operation 身份隔离；没有引入新权限、IPC、VM owner、数据通道或秘密出口。

## 最小差异与原件来源

19项构建输入中17项与 QA3 旧 seal/精确 before 相同；构建输入变化只有 ProbeActivity 和 build_qa.py。后者仅新增固定批准输出根及对应 preservation 路径选择，未扩大为任意输出根。README 为行为/证据说明，两个新增测试支持文件留在隔离原型；无生产源码变化。

已逐项核对：

- seal 所列源码/支持文件与 source/孪生全部 bytes/SHA 相同；before-qa3 的19项旧构建输入与原 QA3 seal、原 qa3-apk-01/source 相同。QA3 旧 Activity SHA `1e8e444552521ff2ce8a621fc03ccd2e33cb431cda53b88c87ff12e388b53e6c` 保持。
- 提供的 qa3-to-qa4.patch 与上述 before/source 的完整限定 diff 相同，恰为Activity、build runner、README变化及两个测试支持文件新增。
- red02/green02 编译的四份实际 Java 为 ProbeActivity、ProbeConnectionEvents、ProbeSession、FixedPermissionPlan；harness 和 runner 两轮完全相同。各自精确 source、javac 参数所列全部输入，以及生成的 Android/远端 Binder 替身与冻结 runner 的常量逐项匹配。未以复制 submit 的模型替代实际 Activity。
- 编译记录使用 snapshot；raw outputs、result、patch、动作包和所有所核输入均与指定 preservation 相同。新核验结果即时双份保存，没有回写实施方原件或任何旧审查记录。

## 实际结果与证据等级

`activity-deadline-red-02/result.json` 为 javac0，旧 QA3 九场景中八项失败。grant/readback/restore 明确输出旧 A timer 退役 B；旧 finish 重放清 busy、迟到 snapshot 在 deadline/end/death/旧 Activity 后重开 session 分别命中原始断言。**current-deadline 对照为 PASS/exit0**，没有把编译失败当行为 red。

`activity-deadline-green-02/result.json` 为 javac0，相同 harness 的九场景全部 exit0，逐项原始 PASS 行的断言数为14、15、15、15、11、9、9、9、13，合计 **110**。覆盖 grant/readback/restore 的 B结果与显式恢复、旧 timer 强制投递、旧 finish 重放、当前 deadline、snapshot迟到、end/death及旧 Activity；结果与上述源码语义相符。首轮 red01/green01历史保留，本次未据其失败另改或补跑测试。

`qa4-compile-01/result.json` 的 AIDL/javac 均 exit0，19输入及 notice 与 seal/source/孪生相同；原始编译 warning 保留，未声称零 warning。主协调的 `qa4-apk-01/result.json` 同为19输入匹配，AIDL/javac/D8/aapt2均0，独立 QA code4/0.1.3-identity、unsigned 53178B、SHA256 `95a691a6fa0349b166655c568f619191e97d7ee4da075c80c6974c3ac9e20722`；这里只签来源关联，不签名、安装或认证设备结果。

上述九场景是**真实 Activity 源码加 JVM 合成 Android 队列/时钟/视图和远端 Binder**。不证明真实系统投递、Binder死亡、grant/revoke或普通session设备验收。主协调构建/签装与真机复验并行，本报告不预先签收其结果。本 reviewer 没有重跑九场景、旧72项或任何完整套件。

## 完成、限制与治理

完成 S-A-03 最小差异、每次 operation、旧 timer/finish、全局 flight及源码/原件来源的定点规范复审。S-A-03在该冻结范围闭合；此前 A/B三P2和C S-C-01闭合结论、D限定框架签收保持，不重做它们。无新增硬规范或判断性smell发现，不为此扩大重构。

**QA1 的 ProbeActivity.java、IdentityProbeService.java、IIdentityProbe.aidl 三份精确历史源码 bytes 缺口继续明示**：QA4新快照不能倒填旧QA1，也不使原QA1变成完整源码已审。原QA3 request-changes、旧seal/APK源码及失败原件保持。真实Android deadline/death、typed grant/restore完整软件UX、物理/OEM、VMowner/Guest/normal stop/性能及ACR-01门均不由本结论关闭；真实AVF成功样本仍0。

修改仅本新增正式 `standards-final-qa4-20261016.zh-CN.md`、`resume-01/review-standards/qa4/`核验/最终索引/报告副本及即时孪生。无生产读取/写入、设备/adb/UI、Gradle、编译/测试重跑、版本、签名、网络、ref/worktree/commit操作；无资产清理、用户数据或凭据访问。branch沿既有main，Commit无；Class A、旧包/源码/证据保持，无生产回滚需求。后续设备门由主协调签收；若QA4输入再次变化，另seal后限定复核。

Architecture Change Policy：隔离QA的契约内局部修复和定点验证，无新产品权限/IPC/owner/暴露面，不触发新架构变更；ACR-01不受本次approve影响。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

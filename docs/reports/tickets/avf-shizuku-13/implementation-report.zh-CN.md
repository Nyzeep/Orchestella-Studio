# Ticket avf-shizuku-13 本地集成报告

- 票号：13，本地预览集成、回归与交接；状态：**completed-local**，本地验证及双轴最终审查完成（设备/发布不在本票通过范围）。
- 模型：Codex / GPT-6.1；分支main，基线/当前提交 `428860ad9a1cb335f8be78c157b2ff57faf2a6da`。
- 本次用户授权研究→工单→无阻塞升级，不包含commit/push/tag/release或设备系统操作。

## 1. 目标与实际完成

已依次完整读取两交接、政策和最新证据；四线研究固定来源并形成综合报告；新effort有scope/map/spec、16张编号票和ACR-01，依赖图已程序核对17票/无环。普通client、诚实预检、Guest生成和工具由独立owner实施；VM代理/真实AVF/性能保持设备与架构门。

主协调已修改app版本为0.5.0/code500并更新版本审计：client新增功能触发MINOR，未复用405。旧405 APK本轮重算SHA一致，未修改历史发布物。所有本轮编译与测试输出使用新批次外部build/temp/evidence，复用既有缓存与工具链。

## 2. 已运行验证

- 实际Gradle `:runtime:contract:check :runtime:engine-avf:test :runtime:engine-qemu:embeddedProvisionInputTest :runtime:engine-qemu:qemuLaunchSpecTest` exit0；56任务全部执行，1m10s，AVF20个JavaExec均运行。证据E/evidence/gradle-runtime-contract-avf.log.txt、preservation及integration/contract-verification.json。
- 研究CI八观测真实fixture与D最终96项合成测试分别见综合研究，不重复相加为App测试。
- 17票必填状态/owner/type/阻塞行和图无环检查通过；map含设备/用户架构外部门。
- 02单权限事实模型真实Gradle red→green：旧MANAGE-only输入误判READY的assert失败exit1，修正显式两权限/UNKNOWN后同任务exit0。App probe独立CUSTOM读取red也实际编译后5测1失败；后续green第一次被03进行中test辅助字段缺失挡在编译，明确不算产品行为失败或通过，修复后统一重验。
- 主协调WSL完整Guest运行时65项、生成器新增10项、既有builder Python6项均exit0，日志为E/evidence/guest-runtime-final.log.txt、guest-sync-final.log.txt、guest-builder-python-final.log.txt，integration/guest-verification.json单列集合。
- 主协调实际复验生产工具99 unittest全部通过（14.927s），E/evidence/validation-tools-final.log.txt；与研究原型96和开发者两入口99不相加。
- 隔离identity原型首次AIDL编译真实失败（方法id混用），修正后aidl/javac各exit0、6class；仅compile-only，非QA APK/设备/AVF。
- 首轮App实际集成exit0：714 tests、96 suites、failures/errors/skipped均0；capability含新client JavaExec通过；lint仅747 Warning、无Error/Fatal。四个SDK AAR均13.1.5，实际bytes/SHA256已由Gradle解析后记录。见E/integration/client-checks-first-pass.json。此时两项02/03审查P2尚待定点修复，不能用全套通过掩盖。
- 实际merged manifest中Shizuku provider仅1个，authorities本包.shizuku、exported=true/multiprocess=false、受INTERACT_ACROSS_USERS_FULL保护，精确queries仅manager；uses-permission/API元数据按SDK合并。MIT文本1531bytes包含四坐标和完整许可，About入口已接线，APK内条目待构建再核。见E/integration/merged-manifest-and-notice.json。
- 工具P2（3.9–3.11缺junction API静默放行）已最小修为Python3.12+且缺能力fail-closed；主协调新标签100 unittest实际全过，E/evidence/validation-tools-reviewed.log.txt，不覆盖旧99证据。两轴复审进行中。
- 01双轴approve、规范/Spec均0发现；02/03修复后将offline终验并独立复审，最终APK尚未assemble。

这些是本地代码与工具证据，绝非设备AVF成功。当前adb零设备；本轮未安装/配对/授权/启动Guest，真实AVF样本0。

## 3. 修改模块与范围

主协调：app/build.gradle.kts版本字段、docs/standards/VERSIONING.md审计、.gitignore中third-party-notices.txt唯一资产例外、compile-only隔离prototype；综合研究与本effort/报告、外部runner与证据。A负责02/03模型/授权UI/client SDK，C负责01 Guest脚本，D负责04工具。许可文件可后续正常纳入源码，生成Image/resources/docs仍ignored，无force-add或提交。最终文件以收口diff为准，不覆盖他人文件。

无kernel/rootfs/用户数据/私有盘/VM owner/全局策略变化，不读取签名秘密。SDK/Maven/Gradle缓存正常复用；没有仓库整包外发、新远程issue/PR或长期worktree。

## 4. 设计、风险与未完成

普通Shizuku client不grant Android权限，不提供exec/UserService/VM代理；Android两权限与hiddenAPI/SELinux/FD/owner/Guest分门。custom Linux不等Microdroid回调。样本工具只校结构/关联，不签发物理真实性。新增VM owner需ACR-01和05证据，当前阻塞。

本地验证已完成：最终offline reviewed-client-offline为capability check、App715 tests/96 suites、failures/errors/skipped=0、lint无Error/Fatal但747Warning，61任务24实际执行，3m38s。四SDK provenance与首轮一致。真实构建 `:app:assembleDebug` exit0，最终新APK106506176字节，SHA256 `500643f1305bf01267ed76bb5bbdd17f408957ddf7c27e24f73e383f50a2583c`，versionName0.5.0/code500、原包名，签名SHA256仍为 `3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06`。

交付包E/delivery/Orchestella-Studio-v0.5.0-local-validation.apk与preservation/delivery独立副本hash一致。实际APK唯一guest_init233538bytes==canonical；唯一third-party-notices.txt1531bytes==源码并覆盖四模块/完整MIT。Image、基础initramfs.cpio.gz及runtime-artifacts-v1.sha256与旧405条目逐bytes相等。历史405整包SHA仍b280472d...b541，未修改。证据E/integration/final-v050-apk-verification.json、apk-license-and-inputs.json和evidence/gradle-final-apk.log.txt。

01/02/03/04/13独立规范与Spec双轴均最终approve，三项不同P2已修复，当前无未关闭P1/P2。13规范审查者另独立运行只读apksigner/aapt2与ZIP/hash核验，需求轴另重算交付/保全APK和固定条目；原件在E/review-standards/13-final.md、review-spec/13.md及正式review目录。map/frontier与票状态已同步，最终文档/原始证据保全在closeout中登记。云CI kernel/build/smoke、OEM/真机Shizuku与AVF/性能始终未验，真实AVF样本0，不以本地测试填绿。没有安装新包或操作任何实例/设备。

回滚为定点撤回本轮源码/依赖/文案，不重置用户工作树/清缓存/删除实例；旧405发布与ClassA资产保留。本轮无对应源码发布动作，新增MIT SDK notice在03核对。

## 5. Commit、资产与架构门

无新commit/分支/推送/tag/release。原始证据E=`Orchestella/artifacts/avf-shizuku-next-stage/20261015/`，即时preservation，保留至验收归档，无自动清理。报告与tracker本地ignored，planning-seal精确保全仅本轮新建文档，不是整仓snapshot。

已提出ACR-01但未实施架构变化；01–04不新增guest→host权限面。正式review在 `docs/reports/reviews/avf-shizuku-13/`，独立双轴最终approve已完成；源码/产物及实际测试边界逐项记录，不重复运行已通过门。

报告人：Codex 主协调 / GPT-6.1 ｜ 日期：2026-10-15 ｜ 耗时：未可靠计量


## 2026-10-16 resume-01 最终证据收口

本节追加本批实际集成事实；前文500/20261015的授权、零设备、715/96、lint0/747及独立13审查保持历史范围，不能当当前503验收。恢复沿唯一源码Orchestella_dev、main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，0.5.0→0.5.3全部dirty保留，无新commit/ref/branch/worktree。本次文档Worker只核原件、追加报告/研究和更新指定tracker；主协调是设备/Gradle/版本/签名唯一执行者，正式最终编排报告由主协调另写。

### 本批实际完成与证据

E=`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`，R=`E/resume-01/`。本批03/02历史实现保留，11/12/14/15的运行反馈派生17/18/19/20，22交付离线工具；实存01–20、22及ACR-01共22文件，无21。

| 门 | 本批实际结果 | canonical原件与限制 |
| --- | --- | --- |
| App最终回归 | 747 tests/98 suites，failures/errors/skipped均0 | R/integration-503/tests-and-source-audit.json；与旧715/746及局部34/30/14不累加 |
| lint | Fatal0/Error0/Warning748 | 同上，748 warning保留，不称lint零告警 |
| 冻结来源 | C三P2后17输入、D五输入全部all_match，无mismatch | 同上；C新seal为lifecycle-logs/p2-stop-dispatch-fixed/final-freeze.json |
| 两503包 | 原包503/0.5.3与.phase5 503/0.5.3-phase5-local已构建核版本/证书 | R/integration-503/final-apk-verification.json；仅.phase5真实安装 |
| 唯一Guest/MIT | 两包唯一guest_init 233837B、SHA c8fbc1f8e4d76077385ad8a5e1027718615049042245cfcf802f039066461150；notice1531B、SHA b3deed6613ab532486b327bf4cd36fa16931224fe7aee7109987a60f8806deb5，均matches_source | 同上；完整MIT，与生成源码原bytes匹配；不解析embedded载荷 |
| pinned运行时 | Image、基础initramfs.cpio.gz、runtime-artifacts-v1.sha256及libdroidlayer_qemu.so均与500同bytes/hash | 同上，未换kernel/baseinitrd/QEMU或新增对应源码发布动作 |
| 503真实框架 | 8场景全部PASS：step3/4、input、invalid schema/distro/resource、creating、invalid-schema-creating | R/integration-503/framework-acceptance.json及其E/device八原件；7向导+1输入，无真实下载/create/VM事务 |
| 既有本批独立集合 | runtime contract/capability/AVF20/QEMU64任务exit0；Guest65/sync10/依赖6；工具最终127=100+27 | E下各原始运行及resume dispatch；来源分述不冒充503重新全跑或把synthetic当设备 |

### 精确交付身份

| 产物（E/delivery） | bytes / SHA256 | 包/版本 / 证书SHA256 |
| --- | --- | --- |
| Orchestella-Studio-v0.5.3-local-validation.apk | 106520160 / 53a2c4947724a048101ba62918da44996204b827a0422c4d806f3a09860f2dd2 | io.github.nyzeep.orchestella / 503 / 0.5.3；3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06 |
| Orchestella-v0.5.3-phase5-validation.apk | 106520184 / 9a02ca4327fdf6ca5cc2bc55182e0899c8a4411a5909e655e2da0fec13ed6399 | io.github.nyzeep.orchestella.phase5 / 503 / 0.5.3-phase5-local；a2001d21314616a7d475f442936da4c2d17423db22316336c84b8c60339b9669 |

final-apk-verification原件两包preservation_equal=true、uploaded=false/published=false。原产品code300为另一签名，user128数据保留；未卸载/清数据绕过。**原包503是已核产物，不是安装成功。** 真正本轮设备框架只在独立.phase5 503/probe3执行；原产品软件UX和数据升级不由QA替代。旧500/405/历史包与对应源码/失败保全。

### 修复、独立审查与QA4签收

C17三P2（expected-session捕获、watcher先于通知、排他admission）已有精确new seal、34JDK/精确Main片段red→green及来源绑定；17/spec-rereview和standards-rereview均限定approve。18三Guest输入不变，五受控sink/双轴approve后canonical与503 bytes门已闭合。19/20的30输入/14模型回归、源码双轴及502框架复审approve保留，503同一D输入再获八框架PASS。22最终127项及两轴approve完成离线范围。这里汇总各独立原审/复审，不伪造一次覆盖503整包的独立13新review。

QA4最后submit deadline P2在R/qa-deadline-fix封板，identity-v4-frozen-source SHA256 6f83dcaffb72038776fd8088115aac93a6fdff0e0113a09590e73d36ec25af15；同一harness旧QA3八失败加当前deadline控制通过→QA4九场景110断言PASS。每operation token、自己的timer/finish及真实底层返回才释放flight，05/spec-final-qa4与standards-final-qa4均限定approve，旧request-changes与QA1三源缺口不删。JVM替身不冒充Android/Binder精确交错。

R/qa4-device-summary.json实测status=PASS_LIMITED_REAL_QA_SESSION_AND_LIFECYCLE：code4普通permission session同epoch跨旧5秒点8277ms仍可readback；normal remove；App force-stop后US0/server活；固定shell server TERM后App观察SERVER_DEAD_PERMISSION_RESTORE_NOT_CONFIRMED、US0；官方starter重启后binder/clientGranted读回true并重新bind成功。最终QA双DENIED、US0/server1，crosvm/virtmgr0。R/device/qa4-final-*为原件，旧R/device/after-qa-app-death.json保留。无权限变更/VM/设备强制B在途交错，不签完整软件grant/restore、真实deadline交错或VM死亡。

R/permission-control-summary.json固定QA/user0 CUSTOM单项与两权限逐次ADB grant/cold-readback/revoke全PASS，最终恢复双DENIED；这是ADB人工对照与恢复，不是QA按钮/生产授权向导UX通过。QA3 MANAGE发生后App/US退出且旧回执未更新的具体因果仍UNKNOWN，不由deadline修复倒推。

### 未完成、风险与后续

当前真实US getCapabilities bits1/protected=true/nonprotected=false；ROM不兼容现nonprotected custom Linux。App新QA state可写，US访问所给App Context目录EACCES，App→US合成PFD通过仍不证G4实际VM FD、合法持久state或owner。07 blocked-platform-and-architecture，08 blocked-owner-and-platform，09 blocked-real-avf，10 blocked-measurement，ACR-01用户未裁定；无create/run/真实Guest/认证/服务/normal stop，无AVF性能样本或收益，真实AVF0。

仍未验：完整typed grant/readback/同session restore、client拒绝/记忆拒绝/管理器撤回、首次本机配对/reboot/OEM及原产品授权向导；跨UID Binder负客户端、真实在途deadline/迟到FD、state升级/G4/owner；17真实FGS/通知/Activity重入/VM正常退出/强停/冷恢复；18真实Guest/PID1/journal/native/recovery/failed及编辑体验；19实体国际布局键盘/OEM IME/焦点/前后台/锁屏；20物理rotation/uiMode/OEM、真实下载/create跨重建/进程死亡。云Guest kernel/build/smoke和发布未在本次执行。不能由8框架、QA4 US死亡或127工具结果关闭这些门。

### 修改、保全、权限和架构检查

本次仅本effort scope/map/spec/17份相关原票、13本报告追加、canonical研究追加；01–04/16历史文档bytes与范围保留，正式编排报告由主协调单独编写。完整清单、27文档/51显式源码输入before/after精确bytes/SHA、diff和证据hash索引位于R/tracker-closeout及R/preservation/tracker-closeout；源码输入定点保全用于证明本次文档收口未改生产/test/version，不是整仓归档。

无新commit/ref/worktree/reset/clean、源码/测试/版本/签名/设备/Gradle/网络或清理动作。Class A工具链/缓存/底包/WSL、所有旧包/源码/失败/用户数据保留；新证据不用.log，无token/serial/用户正文，不外发。报告状态为completed-local-and-framework（本批限定集成）；相关真实外部门由原票保留。局部修复为契约内bugfix，本次只是文档同步，未实施新架构；VMowner/特权IPC仍须ACR具体用户裁定。回滚仅本次文档有界变更，保留后续并行修改和全部原始证据。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板

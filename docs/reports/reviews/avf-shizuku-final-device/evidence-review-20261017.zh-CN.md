# Debian QEMU 最终有界证据验收

结论：**request-changes**。19份指定原件的 bytes/SHA-256 与摘要逐项相符，19份 preservation 孪生均字节一致；原生 systemd ready、journal/owned 输出可查、三条停止路径后的进程/UI 选择字段、最终前台服务撤销及正常/force 重启后的持久标记支持限定验收。必须更正一处报告的时间范围：固定原件仅支持“force 后停机与重启后最终状态的实例/根盘引用 hash 一致”，不支持“force 前后”。这不是新的代码审查，也不要求补跑测试或设备操作。

## 范围与方法

开发根为 `PROJECT_ROOT`；S 为该根的 `Orchestella_dev`，E 为 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01`，R 为 `E/resume-01`。本报告中的路径均相对于这些明确定义的根。

已读 S/AGENTS.md、CONTEXT.md、DEFENSIVE_CONSTRAINTS 下 README 及全部三份 policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING，以及 20261016 REMAINING_PROMPT 的最终交付与独立 review 约束。任务读取限定为 R/qemu-device-summary.json、其19条固定 evidence 原件及对应 preservation、当前 map 与 issues 11/12/17/18、R/tracker-qemu-update/{delivery-manifest,validation}.json 和孪生、正式编排报告的矩阵及第九节。先逐件阅读真实 actions、qa_sections、UI、owners/process 与 metadata 选择字段，再判断；未以 summary.pass 或 validation.all_pass 代替原件。

本次只执行本地文件读取、定点 SHA-256/字节比较及报告保全，没有设备/adb、Gradle、签名、Git、网络、套件重跑或全仓扫描。未读取另一轴旧报告，未重新审 QA4/C 生产代码。根目录及已指定约束/保全目录的浅层定位仅用于找到本次入口，未递归枚举仓库。

分支/基线仅沿当前 map 记载的 main@428860ad9a1cb335f8be78c157b2ff57faf2a6da 作关联，本审未通过 Git 重新核定；无新增 commit。审批范围为固定物理证据和当前报告的真实性，不批准 AVF、性能、代码或整阶段完成。

## 独立核对结果

| 目标 | 原件中的实际字段及可签范围 |
| --- | --- |
| 原件及保全 | 摘要6998B、SHA-256 `7db9bbf32b0298b989ce2c40d96f3c70054315e98a2cfd222828bf42ebf6f252`，摘要孪生一致；19/19原件大小/hash匹配，19/19孪生一致。完整定点结果见同目录 checks.json。字节一致不另构成保全生成时刻的鉴证。 |
| 原生环境 ready | status-observed-01.json 的 qa_sections.status 实际为 UID输出0、systemd、ready/verified、attempt=1、operation_exit_code=0；units-observed-01.json 含 QA503_DONE_PRESENT。force 前的 force-cycle-status-observed-final.json 为 attempt=5、already-complete，不把后续重启说成仍是首次装配。 |
| unit/journal/owned 文件 | units 的 Result=success、ExecMainStatus=0、ActiveState=active、SubState=exited、stdout/stderr=journal；journal-observed-01.json 与 owned-log-observed-01.json 的 qa_sections.journal/log 实际含 running→ready 的 owned marker。owned 文件 repository-refresh 阶段出现三次；阶段名不是 event ID，不能由此判定或签发逐 event 唯一性。 |
| Guest poweroff | guest-poweroff-cycle-a.json 实际经原终端发送 /sbin/poweroff，发送回执只写 OUTPUT_NOT_YET_VERIFIED；随后 R/device/qemu-cycle-a-after-poweroff-01.json 的 owners.exit=0，fields 仅余 App 进程，UI 为 STOPPED。按停止后的已观察状态验收，不把命令发送成功当退出证明。 |
| App graceful 入口 | E/device/qemu-final-app-graceful-selected.json 是坐标点击成功的回执，stdout/stderr 为空；须结合 qemu-app-graceful-result-01.json：owners 仅余 App、UI 实例已停止及“guest 正常关机，实例已停止”。支持本次入口后的停止结果，不由 UI 倒推出精确 owner exitCode。 |
| QA-only force | force-cycle-status-observed-final.json 先见 ready/already-complete；E/device/qemu-final-confirm-force-stop.json 记录确认点击，随后 force-stop-result-final.json 的 owners 仅余 App、UI 已停止。force-stop-state-selected.json 明确 metadata STOPPED、qemu_process_count=0、owned_service_record_present=false。UI 仍显示“guest 正常关机”，本审只签 intentional stop 及底层退出，不签 Guest graceful shutdown。 |
| 重启标记 | cycle-b-persist-observed-01.json 与 after-force-marker-observed-final-02.json 的 qa_sections.persist 均实际输出 ORCHESTELLA_QA503_PERSIST=1。支持正常停机重开及 force 重开后标记仍可查，不推广为完整文件系统一致性或异常死亡恢复测试。 |
| 最终状态/FGS | qemu-final-clean-poweroff.json 发出 /sbin/poweroff；final-clean-poweroff-confirmed.json 实际 owners 仅 App、services.fields 为空、UI STOPPED。final-terminal-state-audit.json 明确 metadata STOPPED、QEMU0、owned_foreground_service_present=false；metadata 仍保有同一实例/根盘引用摘要及4GiB容量。 |
| rootfs 有界复核 | 仅从 final-terminal-state-audit.json 的 rootfs 字段独立核对90351092B、SHA-256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907` 与摘要和当前被审文档一致。matching_current_manifest=true 是该原件字段；本审未读取实际 rootfs 或扩大到清单/下载链路复验，actual_download_endpoint_observed=false。 |
| 文档/矩阵 | map、11/12/17/18 的当前 bytes/hash 与 tracker-qemu-update manifest 均匹配；manifest 内 validation 与独立 validation.json 相同，二者孪生一致。源码51项未变是该已有 validation 的记载，本审没有越界重读这些生产输入。矩阵保留11/12/17/18未验门及07/08/09/10/ACR阻塞，没有把全部设备门改为 completed。 |

正面原件是有限终端/UI/进程选择输出，未保存完整终端正文；部分停止确认也未记录采集命令全文或独立 owner wait 结果。本审认可这些限定观测，不把阴性进程快照扩张为精确退出原因、持续无竞态或所有通知情形通过。创建/启动/选择停止的四份点击回执只证明动作已发，分别需要后续状态原件支持。

## 必须更正：EVIDENCE-01 / P2

触发：将 force 后的两次 metadata 观测称作 force 前后对照。

- R/qemu-device/force-stop-state-selected.json：UTC 2026-10-16T16:41:16.380611+00:00，晚于 force 确认点击的16:39:29及停止结果16:40:08；已经是 force 后 STOPPED。
- R/qemu-device/final-terminal-state-audit.json：UTC 2026-10-16T16:53:17.790011+00:00，在 force 后重启、标记查询及最终再次正常关机之后。
- 这两份均有 id_sha256=`ac9fbf026fd1ee63c24ba0208e46057664f0bec890d7cc65c4df6d04956bc032`、rootVolume_sha256=`f2f5f90ab1bb267076e14ad4a428c6661dda7fff43d74eb35b0c471e0445e407`，相等事实成立；指定19份中 force 前的状态原件没有这两个字段。

受影响文件及位置：

- S/.scratch/orchestella-avf-shizuku/map.md，2026-10-17增补中的“force前后实例ID及根盘引用hash一致”。
- S/.scratch/orchestella-avf-shizuku/issues/11-exit-session-reproduction.md，同日增补、同句。
- S/.scratch/orchestella-avf-shizuku/issues/12-owned-console-log-reproduction.md，同日增补、同句。
- S/.scratch/orchestella-avf-shizuku/issues/17-session-exit-supervision-fix.md，同日增补、同句。
- S/.scratch/orchestella-avf-shizuku/issues/18-owned-provision-log-routing-fix.md，同日增补、同句。
- S/docs/reports/orchestration/avf-shizuku-device-20261016.zh-CN.md，第九节“重启及数据保留”行中的“force前后实例ID及根盘引用hash相同”。

最小更正：将以上时间范围改为“force后停机状态与重启后最终状态的实例ID及根盘引用hash一致”。正常/force重启标记仍可保留已验；无需补设备、测试、源码或更改旧 seal。若主协调修改这些当前文档，只需同步本次 tracker-qemu-update 的受影响 after hash/保全关联，旧 tracker-closeout/source seals 保持原样。本审未执行上述更正。

## 保留限制、未完成内容及后续动作

成功 stop 的精确 owner exitCode 未独立记录；不能据 UI、EOF 或查询命令 exit=0 倒推 owner exit=0。force 的“正常关机”界面文字只能支持 intentional stop。routine console 全程计数、逐 event exactly-once、native failed 一次摘要及无 journal recovery 未验；不把 owned 文件重复阶段名当成 event 重复。真实异常退出/wait异常/错代/EOF竞态、Activity重入/通知全矩阵、App死亡后VM冷恢复仍开。QEMU结果不证明AVF/性能；GPU标签不是GPU运行证据。

本审没有独立重验安装身份、全部权限、Debian来源链、先前用户实例未改、Class A资产、源码冻结或旧审查；这些属于原协调记录或本任务明确保留范围，不因本报告转成新通过项。后续只需主协调处理 EVIDENCE-01 的文字范围并定点确认，其他已列未验门按原票保留，不新增无关测试。

本次新增文件仅为 R/final-evidence-review/{review.zh-CN.md,checks.json}、R/preservation/final-evidence-review/同名孪生及 S/docs/reports/reviews/avf-shizuku-final-device/evidence-review-20261017.zh-CN.md。三份报告同字节，两份 checks 同字节。原件、生产、工单、正式编排报告、旧 tracker-closeout/source seals 均未写入。保全 owner 为最终证据 reviewer，Class B 保留至主协调/维护者验收归档，不自动清理；无 C: 或系统资源写入，无新增暴露面，不触发 Architecture Change Policy。

报告人：最终证据 Reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-17（本批报告日期） ｜ 耗时：未可靠计量

## 定点更正复核：approve（最终结论）

**当前最终结论：approve，限定于本批固定物理证据和更正后报告真实性。** 上文首审 request-changes 与 EVIDENCE-01 原因完整保留；主协调已更正比较窗口，本节关闭该项，不新增代码、AVF、性能或未验门的批准。

复核依据为 R/final-evidence-correction/delivery-manifest.json（SHA-256 `60bfdf3f08dacebf2532b605ddd4a6c21acb4290665e184157f6d29161ec98b0`）及 evidence-window.patch、六份本轴受审文档的精确 before/after 和 preservation。六份为 map、issues 11/12/17/18 与正式编排报告：当前字节均匹配新 seal 的 after，before/after hash 均匹配，快照孪生均相等；每份唯一变化均为将“force前后实例ID及根盘引用hash”替换为“force后停机记录与重启后最终停机记录的实例ID及根盘引用hash”。该窗口准确对应两份原件。未据 manifest 中其他六份文档扩大本轴正文审查。

两份比较原件 force-stop-state-selected.json、final-terminal-state-audit.json 的 SHA-256 和 preservation 均保持首审值，未新增 force 前观测。正常与 force 重启后的持久标记、三停止路径后的限定退出/STOPPED、最终 FGS 无及 systemd/journal 观察结论不变。更正仅收窄一句话，矩阵和第九节既有未验门保持。无其他必须更正项。

原 tracker-qemu-update、tracker-closeout 和 source seals 保留历史事实；更正采用独立 final-evidence-correction seal，无需改写旧封板。首审 checks 在协调者更正进行中落盘，因此其中当前文档与旧 tracker-qemu-update after hash 不匹配属于时间切片；最终检查以本节新 seal 的六份 current/after 全匹配为准。checks.json 保留首审对象及其字节摘要，并另列最终复核，避免把旧失败改写成从未出现。

最终批准继续保留：精确成功 stop owner exitCode 未记录；force UI 只能支持 intentional stop；console 全程计数、逐 event exactly-once、native failed/recovery 未验；QEMU 非 AVF/性能，GPU 标签非 GPU 证据。没有设备、套件重跑、网络、生产/票/正式编排报告或旧 seal 写入。唯一正式新增 review 与本报告同字节，报告和 checks 均在指定 preservation 目录保留孪生。

报告人：最终证据 Reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-17（本批报告日期） ｜ 耗时：未可靠计量

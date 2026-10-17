# Phase 5 AVF API 独立审查报告

- 日期：2026-10-13；审查者：storage-research / ninep-worker，会话模型 `new-provider/gpt-6.1-sol`。
- 审查对象：另一位 avf-worker 的 Phase 5-09 修复，integration 工作区 `D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev`（下称 W）。
- 固定基线：`8d0e8c75d769ecc645ea102b90a304c8657344a1`；分支 `phase5/graphics-productivity-storage`；worker patch SHA-256 `c1017a033fe52f5b5328ee622177a306d8ca6f3369c3a953fafd0b86d921993e`。
- 审查结论：**request-changes，1 项 P2；未发现有证据的 P1。** 停止/取消被新连接锁阻塞的问题修复并补并发负例之前，不独立 approve 本次 AVF API 交付。
- 独立性：审查者没有实现或修改此 worker 的三个 API/engine生产文件和对应 fixtures。本人实现的 9p server/test 不在独立 approve 范围，未运行 9p 审查测试。

## 1. P2 即时发现与复现

**[P2] 不要在整个 vsock 连接和重试等待期间持有 session monitor。**

位置：`W/runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf/AvfVirtualMachineEngine.java` L233–264，特别是新增 `synchronized connectWithRetry`、L246 的 `api.connectVsock()`、L257 的 backoff。停止链在 L223–230 先执行 `closeChannels()`，L289 再取得同一个 session monitor，然后才调用 `handle.stop()`。

触发：另一个线程通过 `session.openChannel()` 连接尚未监听的 guest 服务，用户同时停止实例。新实现给 additional-channel 连接也加上了全程锁。停止线程无法进入 `closeChannels`，因此也无法到达 VM teardown。100 次、200ms 重试可阻塞约19.8秒加各次连接耗时；单次 platform `connectVsock` 阻塞时没有 session cancel deadline。官方 API 明确说明连接可能阻塞，不能把这一情形当成只存在于 mock 的问题。

此次独立 reviewer probe 通过公共 Engine/API 接缝，模拟一个受 latch 控制的阻塞连接，然后从另一线程调用 stop。**观察350ms内 `stopped_before_connection_release=false`，释放连接后 stop 才到达 handle，晚到 channel 关闭一次。** 原始输出与源码输入hash在：

`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/C/avf-api-review/evidence/first-review-execution.json`。

这是对新增锁覆盖 `openChannel` 的可重复正确性问题，不把此前 dedicated getters 已有同步缺陷误写为全部由本 patch 引入。`ReflectiveAvfVirtualMachineApi.connectVsock` L142–175 同样在 handle monitor 内调用 platform connect/adopt，`ReflectiveHandle.stop()` L418 又要同一锁；修复取消时应一并评估，避免只删 inner synchronized 而 outer dedicated getters 仍把等待锁住。

建议 worker 修复：仅在检查/登记/取出所有权时持短锁，连接与 backoff 移到锁外；关闭能先设置不可再接纳新 channel 的终态，等待能够被取消；晚返回的 raw channel 在关闭状态下立即回收并拒绝交付。所有 dedicated getter 与 additional `openChannel` 都应覆盖 stop-versus-connect、close-versus-backoff、late-return reclaim。底层 AOSP 自身也有 VM lock；对不可中断 Binder/platform调用应明确有界取消策略或可诚实观测的限制，不能仅由本地 flag 宣称原生连接已经取消。

验收应验证：连接 fixture 尚未释放时，停止路径可至少进入正确的 cancellation/VM teardown；关闭后不会继续 sleep/retry；晚到 FD 不注册为活跃且仅关闭一次；已有通道关闭不被 pending connector阻挡。保持用户未授权的设备/系统操作为未运行。

此 P2 在发现源码时已即时返协调者，复现后再次返证据。审查者没有修改被审查源码/tests。

## 2. 固定审查范围与快照

只读的主对象：

- `runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf/ReflectiveAvfVirtualMachineApi.java`。
- 同目录 `AvfVirtualMachineApi.java`、`AvfVirtualMachineEngine.java`。
- 对应 `ReflectiveAvfVirtualMachineApiTest`、`AvfVirtualMachineEngineResourceTest`、`AvfVirtualMachineEngineTest`、`AvfVirtualMachineApiFixture` 与 `android.content.Context`、`android.os.ParcelFileDescriptor`、`android.system.virtualmachine` source fixtures。
- 工单09、Phase5 spec、worker中文实施报告及精确官方source/交付证据。已读治理policy继续适用；不读取凭据、`.env`、私钥、实例盘或整仓历史。

生产快照 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| AvfVirtualMachineApi.java | `e883aabdf819fc7c63220000accbe84c02aedb7190f932529fcca7799b4ff9a1` |
| AvfVirtualMachineEngine.java | `9ecdb5d75f85c1191eb9e7600c04e18aa07413affdd191135f1c4588e66698a1` |
| ReflectiveAvfVirtualMachineApi.java | `97a28fe37ec4222e44f660e38375b206ceb2c0a856a91d6b0c2915ada2e0131f` |

worker manifest的17个源码/fixture文件和报告均逐hash与integration核验一致（18/18）；审查前后已执行测试所读的三生产文件与核心测试hash未改变。原件在 `C/avf-api-review/evidence/review-snapshot.json`。后续新改动不自动继承本报告结论，必须以新hash复核。

code-review Skill已调用，因项目/全局同名歧义被工具拒绝；采用先前已读项目 `.agents/skills/code-review/SKILL.md` 的规范/规格两轴审查。用户禁止再派生，故本人执行独立审查，未启用子代理。固定点和spec明确，无需额外询问。实际diff为基线到当前未提交worktree，不以HEAD空diff冒充没有改动。

## 3. Standards 轴

没有发现其他需要阻断的标准/所有权范围违反：三生产文件仍为Android-free Java反射边界；没有setAccessible、自动grant/directKVM、增加GPU权限或改变9p wire；public DescriptorAdapter文档说明成功转移original ownership、失败回收duplicate及original职责。worker正式报告如实区分JVM与设备。

规范观察：worker报告已披露首次runner漏设tmpdir导致两处C盘合成临时目录，原metadata在A/evidence/unexpected-test-temp-paths.json。该历史偏差不能写成“全程无越界”，但后续runner已指定D盘路径，报告未掩盖，审查者不读取/清理其内容。本次review所有编译、合成探针与临时结果只在批准的D盘外部证据子目录。未修改production、fixtures、tests、Gradle、ADR或tracker。

本轴不把不存在的style规定、纯偏好或静态未验证的GPU边界作为P1/P2。正式权限/能力产品决策仍由协调者持有。

## 4. Spec 轴：逐项核对

| 关注点 | 核对与结论 |
| --- | --- |
| 官方配置shape | 核验custom image与VM config分离：Disk.RWDisk→addDisk(Disk)、addParam；outer Builder(Context).setCustomImageConfig/setProtectedVm/setMemoryBytes/setCpuTopology；manager.create(String,VirtualMachineConfig)。修复不再沿用伪继承、addDisk(String)、createVm或int-vsock [S1–S3] |
| run时序 | manager.create返回VM后显式void run；失败调用close已获得VM，未自动delete/getOrCreate；对应run异常与close计数测试通过 [S2、S3] |
| permissions | MANAGE及USE_CUSTOM各检查，protected-only/nonprotected capability拒绝；不是MANAGE单权限即可启动。反射availability不代表hidden API或权限授予；两类permission测试通过 [S4] |
| CPU精确语义 | 固定源码只ONE_CPU/MATCH_HOST，cpuCount1设ONE_CPU；>1不默认为MATCH_HOST。本次独立probe确认cpuCount2在create前HARDWARE_UNSUPPORTED且create次数0。默认/App多核配置的可用性是协调者接线边界，不擅改UI/contract |
| PFD原始/dup ownership | 官方实例dup、两方向各自AutoCloseStream；默认adopt成功由OwnedStreams持有，任一stream close转channel idempotentclose；dup/input/output构造失败、permission失败、close错误聚合及注入adapter失败测试通过 [S5] |
| connect shape | `connectVsock(long)`返回PFD，精确return/参数signature检查；不访问伪getInputStream。能力需要真正Android PFD，fixture仅模拟公开shape及计数 |
| guest wait退出 | numeric getStatus0/1/2；独立probe确认RUNNING时wait未返回、改STOPPED后返回；状态查询异常有稳定reason。没有把假enum或flagshipmock写为官方设备 [S3] |
| exception cleanup | 获得VM后run失败close；PFD拒绝后original/dup分别回收；session关闭即使首个channel抛错仍尝试其他owners；永久permission/API/hardware错误不做瞬时重试；三个worker测试独立重跑通过 |
| concurrent stop / pending connect | **P2未覆盖且独立复现**，见§1。当前worker“锁外teardown”只在close取得session锁之后成立，不能解决connect持锁阻断 |

已核对的PFD失败场景未发现另一个有证据的P1/P2。不把trusted注入adapter违反它自身ownership契约、VM最佳努力close的platform竞态，或未经验证的GPU增强当作本票已经实现/通过的功能。

## 5. 官方源码与许可

| ID | 固定官方URL/revision/位置 | 审查范围 |
| --- | --- | --- |
| S1 | [VirtualMachineCustomImageConfig](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineCustomImageConfig.java)，Disk与Builder、@hide | 同worker固定android16-release；保留所有hidden/feature限制，不宣称普通publicSDK |
| S2 | [VirtualMachineManager](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineManager.java)，L110 capability、L188 getCapabilities、L218 create、L310 getOrCreate | reviewer独立抓取固定source与hash在official-manager-fetch.json；不用fixture作为唯一shape事实 |
| S3 | [VirtualMachine](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachine.java)，L745 status、L1488 run、L1873–1898 close、L2081–2105 connectVsock | 官方may-block依据；close最佳努力，不能保证任何OEM退出；真实设备未运行 |
| S4 | [AVF module manifest](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/android/android.system.virtualmachine.res/AndroidManifest.xml)与[VirtualMachineConfig](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java)，L155/L164 CPU、L1236–1259 memory/topology | MANAGE signature/preinstalled/development，USE_CUSTOM signature/development；不grant权限 |
| S5 | [ParcelFileDescriptor](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/os/ParcelFileDescriptor.java)，L387 dup、L1015/L1067 AutoClose构造、L1021/L1073 close | source SHA`550983ac359a592b56a32e2161ea7d55aaa45abc36c2a0e395df1ae0dcb0ad3d`；独立读A/preserved固定原件，不使用真正设备FD |

上述AOSP源码Apache-2.0；只读primary-source分析，review未引入/复制实现到production。本次公开GET仅S2固定官方source请求，不上传host源码/日志或路径。

## 6. 实际验证、未运行与证据

本次 reviewer independently compile integration源与所需只读依赖，Windows现有 `D:/JDK17`，`--release 17 -encoding UTF-8 -Xlint:all -Werror`；输出只写外部：

`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/C/avf-api-review/evidence`。

重新运行以下三测试全部exit0：`ReflectiveAvfVirtualMachineApiTest`、`AvfVirtualMachineEngineResourceTest`、`AvfVirtualMachineEngineTest`。独立 `AvfApiReviewProbe` 通过CPU拒绝与numeric等待正例，同时观察到P2取消失败。probe整体exit0是“成功记录这个缺陷与控制结果”，**不是P2 gate通过**。

worker的19项非9p完整JVM交付结果 `A/avf-api-repair/evidence/delivery-engine-suite-results.json`已只读核对；本review没有无理由重跑全部codec/network测试，不将worker结果冒充reviewer再次执行。本reviewer编译时需要依赖server类，但未运行/审查自己的9p实现或给它approve。

未运行：Android真实descriptor/kernel关闭与阻塞唤醒、AVF VM/hardware、custom权限授予、GPU/图形zero-copy、guest mount、APK/build/device tests；没有adb/设备/系统变更。GPU平台权限、OEMfeature/hidden-API、持久create名称恢复、App多核fallback仍属未解决/未验证的integration边界，只报告事实，不修改ADR/tracker/capabilityflag或宣称旗舰机tested。

## 7. 结论、产出与下一步

Standards轴：无新阻断项，历史tmpdir偏差已有报告；Spec轴：**1项P2停止取消阻塞**，需worker修复及精确hash重审。总裁定request-changes，不把既有测试全过与单个APIshape正确当作完成取消/设备验收。

新增唯一正式报告为本文件；外部originalprobe/编译/执行/hash仅在上述review evidence。被审查生产与tests未修改，9p冻结交付未修改，commit/branch变更无。Architecture Change Policy：本review不实施架构变更；提出的是既有生命周期bugfix，GPU/custom权限决策保持协调者ACR职责。

协调者已即时收到P2及复现结果。建议由AVF owner处理短锁/cancellation/late-FD cleanup后返回新delivery manifest；reviewer按新快照只读重跑此并发probe和相关API/resource tests。设备验证仍等待明确交接，不为审查补跑设备。

报告人：独立AVF API reviewer / new-provider/gpt-6.1-sol ｜ 日期：2026-10-13 ｜ 耗时：未统计

## 8. P2 增量修复独立复核与最终本地裁定（2026-10-13 追加）

**当前最终裁定：final-local-approve；原 P2 已关闭。** 上文§1–§7及首页request-changes保留为首次快照的历史记录；本节依据新的精确hash和独立停止probe，覆盖该历史裁定。批准范围仅为 Phase5-09 AVF API/engine 的本地代码与JVM生命周期修复；没有对本人9p实现作独立approve，也不代表Android设备、原生阻塞中断、AVF/GPU或guest验收通过。

### 8.1 新快照与独立读取

此次核对的权威增量manifest为：

`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/A/avf-api-repair/evidence/p2-stop-liveness/final-manifest.json`。

增量patch SHA-256：`e457b7318cb83e04f0365f48d2a455f5b2b3d26bfe7113951ac96f6e2a137140`。manifest内`integration_observed_sha256`属于worker合流前的旧值，reviewer此次对当前integration重新计算，与每项`current_sha256`核验 **5/5一致**：

| 文件（W下原目录） | 此次审查SHA-256 |
| --- | --- |
| AvfVirtualMachineEngine.java | `63a056ce46a1d099d7e6cef59245929c21601857e680fcc10921a5d145560973` |
| ReflectiveAvfVirtualMachineApi.java | `35689e7c12fb2b338f16c03b340cc11d35d4d27751bd6ae66f14a33e1ab2a956` |
| AvfVirtualMachineEngineResourceTest.java | `00b690123e4c3a236b340ff4fa9530a8b21ef41598877eaa232e76634aae7f3c` |
| ReflectiveAvfVirtualMachineApiTest.java | `7a9859f07d7cf3141147179f4cc75abbd08ddcac6b5dfc5f2059796c7c91e502` |
| docs/reports/tickets/phase5-09/implementation-report.zh-CN.md | `f44d7946075302ac62e471868f3d1f3aa78772b29312d8db98d1717ef6e578c3` |

精确hash记录位于 `C/avf-api-review/evidence/p2-recheck-input-hashes.json`。原接口/官方权限shape未扩展。reviewer只读新Engine、Reflective连接/停止部分：Engine的connect/backoff移出session monitor，关闭先发布终态并拒绝pending，stop在可能阻塞的channelclose前调用handle；Reflective连接与descriptor adoption也在handle锁外，晚到raw/adoptedowner在已停止状态下拒绝并回收，platform stop/close不持本地handle锁。

### 8.2 原停止probe复核与结果

没有重跑整个模块suite。仍使用既有 `D:/JDK17`，按Java17、UTF-8、`-Xlint:all -Werror`编译当前integration所需依赖；只执行reviewer拥有的外部 `AvfApiReviewProbe`，不改production或worker tests。

首先保留原probe/setup并原样运行当前integration。原probe过去默认要求阻塞连接最终成功，所以在修复后晚到连接被正确拒绝时抛AssertionError，cause为 `VSOCK_CONNECT_FAILED: session channels are closed`。这次exit1记录在 `p2-original-replay-review-execution.json`，**不是原停止锁缺陷仍存在**，也未隐藏该记录。

随后仅调整外部reviewer探针的预期：保留原350ms/latch阻塞连接场景，明确要求“停止先到达handle、晚到连接返回closed错误、channel仅关闭一次”。追加同一P2的official-shaped Reflective路径，要求VM fixture的stop/close在连接latch释放前都已调用，晚到original PFD关闭一次且没有dup/adopt，重复stop仍幂等。未新增产品功能或改变被审查测试。

最终独立probe退出0，原始结果为：

```text
REVIEW_STOP_DURING_CONNECT stopped_before_connection_release=true late_channel_close_count=1 late_return_rejected=true
REVIEW_OFFICIAL_SHAPE_STOP_OK platform_stop_close_before_connection_release=true late_original_pfd_close_count=1 late_return_rejected=true
REVIEW_NUMERIC_WAIT_OK running_blocks=true stopped_returns=true
REVIEW_CPU_REFUSAL_OK cpu2_rejected_before_create=true
```

证据：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/C/avf-api-review/evidence/p2-final-probe-review-execution.json`。它同时记录输入source/probe hash与严格编译exit0。独立结论是原本地monitor导致停止无法进入的P2已修复，以及late FD拒绝/单次回收成立。

worker新增19项非9p JVM回归的 `A/avf-api-repair/evidence/p2-delivery-engine-suite-results.json` 已只读核对：selected19、passed=true、无非零exit；reviewer没有把该suite或worker报告的1.1832ms STOP测量说成独立再次实测。独立验证只断言上述350ms内STOP可达，不作原生停止时延承诺。

### 8.3 原证据保全与最终权限边界

首次 `first-review-execution.json` 与其 `stopped_before_connection_release=false`输出保持原样；原probe副本、旧review报告及初审快照保全在 `C/avf-api-review/evidence/preserved/`，对应hash索引为 `before-evidence-preservation.json` 和 `p2-worker-evidence-observation.json`。修复后原样replay和最终通过结果使用新文件名，不覆盖失败前证据。

- **native blocking interrupt / Android设备 / OEM Binder阻塞唤醒：NOT_RUN。** 固定AOSP内部VM锁和底层不可中断调用仍可能影响真实stop时延；当前本地Java无长锁及fixture STOP可达不能证明原生connect/read已经中断。worker已把设备边界保留为NOT_RUN；本次不新增设备验收claim。
- GPU、custom permissions、feature flags、SELinux与旗舰机实际运行仍未验证；不修改能力flag、ADR、tracker、App或权限策略。此前MANAGE+USE_CUSTOM拒绝与CPU精确count限制维持原结论。
- 无9p独立approve；无production/tests修改、无整套重测、无commit/系统变更/ADB/设备操作。新增写入只有本报告追加和reviewer外部证据。Architecture Change Policy无新触发；取消修复属于既有生命周期bugfix。

在这些限制与上述hash范围内，Spec轴P2已关闭，Standards轴无新增阻断，**final-local-approve**。未来改变这些文件或原生取消策略需按新快照复核，不从本次批准推导设备/GPU可用。

报告人：独立AVF API reviewer / new-provider/gpt-6.1-sol ｜ 日期：2026-10-13 ｜ 耗时：未统计

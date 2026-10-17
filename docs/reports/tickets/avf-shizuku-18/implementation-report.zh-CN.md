# Ticket AVF/Shizuku 18 实施报告

- 票号/标题：18，自有装配日志单路输出与失败摘要；Parent=12。
- 执行 session / 模型 / thinking：Worker C / new-provider/gpt-6.1-sol；thinking未可靠计量。
- 分支/基线：main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，起点保留的 dirty 0.5.0/code500，未创建ref/worktree或commit。版本/最终APK由主协调串行负责。
- 外部E：`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/`。原始证据与preservation保留至维护者验收归档，Class B，不自动清理。

## 1. 任务目标

原12先验证真实owned report子进程的多路输出，成立后由18局部修复provisioner/owned firstboot unit。减少后台装配打断终端编辑，保留失败可见性、自有日志/state、audit、用户正文与无journal的recovery反馈，不建立新终端数据面。

## 2. 实际完成

| 验收 | 实际完成及证据 |
| --- | --- |
| 先取证再改路由 | 实际生产report/run_step/write_state/fail_stage子进程，native/recovery/failed受控sink运行red。修复前每条running/ready/failed console均两份，文件一份；red-owned-report-stream各case与summary保全 |
| native常规进度减噪 | owned systemd firstboot unit设Environment=ORCHESTELLA_PROVISION_REPORT=journal，StandardOutput/StandardError改journal；常规report仍stdout进journal及owned文件，console零份 |
| native失败一次可见 | failed保留文件/state/原操作退出码/sync，再由report向console写一次摘要；unit不再转发该stdout到console |
| recovery无journal不假死 | 默认stdout单路；recovery/interactive各报告一份，保留运行中/最终状态，未重写recovery supervisor/console/getty |
| 失败状态真实 | 合成子命令exit23驱动生产fail_stage；子进程exit1、state failed/operation_exit_code=23、sync请求一次、无done标记，child输出留owned文件且不进可见摘要 |
| 可维护回归 | 原Guest测试新增--owned-report-only模式，真实子进程/受控sink五场景；此模式不调用canonical prepare，其他模式保留既有canonical门 |
| 保留约束 | 未处理audit/kernel/全局stderr、未关闭journal，未正则过滤用户正文/按键/token，未增加reader/IPC；单getty、PATH、凭据与原正常/失败state语义保留 |

实际运行详见 `docs/reports/prototypes/avf-shizuku-12/prototype-report.zh-CN.md`；合成sink不是Guest屏显或journald设备验证。

## 3. 未完成 / 与预期偏差

未运行真实Guest/PID1/systemd-journal/recovery shell、设备屏显与命令编辑体验，也未做真实耐久性测试。未运行Gradle/完整Guest套件/APK字节门；这些由主协调在最终冻结后执行。

最初受控console是普通文件，用`>`会覆盖旧行，首轮计数不足。已保留原件，并明确将字符console的连续流映射为append后重跑；用于修复依据的是`red-owned-report-stream-*`。没有把首轮不完整计数或静态多写入当作真实设备重复次数。

## 4. 修改模块

本票仅三份输入：

- `guest-assets/scripts/orchestella-guest-provision.sh`：report总是保留owned文件及stdout，只有owned journal模式且failed时额外一次直接console。
- `guest-assets/initramfs/runtime-functions.sh`：owned orchestella-firstboot.service的Environment与stdout/stderr路由。
- `guest-assets/scripts/test-guest-runtime-provision.py`：新增owned report子进程回归及独立运行模式，原其他模式仍需canonical准备/校验。

没有改`guest-assets/initramfs/init`或`app/src/main/resources/guest_init`；生产canonical同步明确交主协调。没有改getty/PATH/其他unit、SetupWizard/input/AVF/公共strings/版本/签名。原测试脚本已有0.5.0未提交修改保留，本票只添加限定模式与测试。17的App输入另由17报告说明。

## 5. 设计决策

日志归属在生产来源端处理：owned native unit进journal，report知道该特定路由时只把failed直接摘要送console。recovery和用户交互默认继承stdout，因而无journal也能持续看到owned进度；没有全局quiet、吞终端正文或引入新数据面。

run_step原本将子命令stdout/stderr留自有日志，保留该行为；failed state/操作码/sync顺序不变。Environment只声明此owned unit的报告路由，不是新的能力或安全开关，不改变鉴权/owner。

按Architecture Change Policy属于契约内bugfix/测试扩展，无新权限/IPC/FD/VMowner/暴露面，不触发ACR或ADR修改。没有新增第三方依赖、代码复制或许可变化。

## 6. 测试与验证

固定外部入口：

```text
python -B <E>/run-owned-report.py --label red-owned-report-stream
exit1，五场景均不满足单路断言，console每条2份
python -B <E>/run-owned-report.py --label green-owned-report
exit0，五场景通过
```

项目维护性回归：

```text
GUEST_RUNTIME_TEST_ROOT=<E下已有明确外部目录> python -B guest-assets/scripts/test-guest-runtime-provision.py --owned-report-only
OWNED_REPORT_TEST_OK checks=5 guestValidated=false
```

实际命令由外部Python进程设置环境，不在C:写临时目录。`E/evidence/green-project-owned-report.json`保存精确argv/exit/stdout/stderr，项目结果在`E/owned-report-project/owned-report-results.json`；均有保全副本。五场景为同一范围的独立入口复验，不相加成10个产品场景。

| 场景 | 修复前console running/终态 | 修复后console running/终态 | process exit / state |
| --- | --- | --- | --- |
| native正常 | 2/2 | 0/0 | 0 / ready（仅报告fixture） |
| native失败 | 2/2 | 0/1 | 1 / failed，原操作码23 |
| recovery正常 | 2/2 | 1/1 | 0 / ready（仅报告fixture） |
| recovery失败 | 2/2 | 1/1 | 1 / failed，原操作码23 |
| interactive正常 | 2/2 | 1/1 | 0 / ready（仅报告fixture） |

每报告行owned文件一份，native journal一份；failed sync请求一次、无done标记；合成child输出只进owned文件。真实生产report/失败逻辑在子进程执行，只有ROOT/console与journal fanout为受控环境，不伪装PID1/实际journal/VM。

final-freeze前`git diff --check` exit0。未运行的完整Guest65/同步10/依赖门6不能沿用旧数写成本票通过；新计数须主协调按实际输出记录。没有设备/Gradle/安装/启停命令。

## 7. Benchmark / 实测数据

本票不涉及性能，没有AVF/QEMU样本或提速百分比。console份数仅受控sink观测，不外推实际终端所有日志来源。

## 8. 风险

实际journald路由/服务失败时是否落持久存储与实际Guestconsole屏显尚待新QA盘验证。owned失败摘要只承诺本报告路径；audit/kernel/发行版日志仍可能按自身策略输出，不能声称终端绝无异步消息。去重不通过过滤用户文本实现。无journal recovery仍默认stdout，因此保持可见性。

## 9. 新发现

报告来源确有双路可达路径，运行次数取决于上层sink；不能从静态双写推断每台ROM每条必双份。最初普通文件覆盖语义偏差已纠正并保留失败历史。没有借题修改发行版全局日志策略或终端。

## 10. 对后续票的影响与固定动作包

本票三份源已与17共17份输入最终冻结，精确bytes/SHA见E/final-freeze.json、E/frozen-source/。主协调在冻结窗口执行：

```text
python -B guest-assets/scripts/sync-guest-init.py
python -B guest-assets/scripts/sync-guest-init.py --check
```

首条无参数是显式源码维护，更新canonical init与resource guest_init；Worker C没有执行。随后以新外部GUEST_RUNTIME_TEST_ROOT跑现Guest全套/--owned-report-only/同步/依赖门，App test/assemble/lint，APK唯一guest_init与canonical原bytes比较；由主协调统一版本502+及签名，不复用500不同源码。

设备最小动作仅主协调：新QA盘，记录source/APK/Guest输入身份；native firstboot trace核owned进度不持续刷console、journal/文件可查；受控失败核state/原码/一次摘要；无journal recovery核进度/失败仍可见，保持唯一getty和既有PATH、用户文本输入。失败注入只新QA无用户事务；不关闭audit、不强停用户VM/复制用户盘。真实设备签名门按主协调证据处理，不卸载清数据绕过。

## 11. Commit / 资产 / 回滚

main，commit列表无，全部修改保留未提交；无网络、设备、Gradle、版本、签名或宿主系统写入。approved外部E的原件/脚本/合成fixture与preservation为Class B，保留期至维护者验收归档。未清理SDK/JDK/WSL/缓存/kernel/rootfs/旧APK/用户盘/旧日志。

回滚只恢复E/before对应两个生产脚本与测试的C限定hunk，并由主协调同步canonical；不回退别的owner、旧dirty、真实实例数据。没有整仓/.git归档，raw evidence不用.log、无secret/serial/用户正文，保全检查见E/preservation-check.json。

## 12. Ticket状态

建议 `implemented-local; canonical-integration-device-review-open`。局部实现和独立运行回归完成，canonical生成/全套集成、真实Guest/设备与独立审查仍open。原12保留受控运行证据范围，不改为设备验收completed。

报告人：Worker C / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板

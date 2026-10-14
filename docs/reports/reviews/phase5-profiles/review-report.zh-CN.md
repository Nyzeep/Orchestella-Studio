# Phase 5 profiles 独立审查报告

- 日期：2026-10-13。
- 审查者：graphics worker 转任 profiles 独立 reviewer / new-provider/gpt-6.1-sol；不派生、不改被审生产/测试代码。
- 结论：**approve（限定所列源码/hash快照及窄API一致性；不代表guest/设备验收）**。最终快照未剩未解决P1/P2；过程中发现的P2已立即反馈并由所有者修复、定点复核。无P1定论。
- 审查对象：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_profiles/guest-assets/features/` 及 `guest-assets/scripts/test-phase5-features.py`，branch `phase5/worker-profiles`，HEAD `8d0e8c75d769ecc645ea102b90a304c8657344a1`，新增未提交文件；integration `Orchestella_phase5_dev` 仅窄范围读取guest-agent helper、BridgeClient JSON结果、DesktopActivity/PresentationGate、GuestFeaturesActivity、LocalEditorActivity。
- 规格：integration `.scratch/orchestella-phase5/spec.md`、`issues/06-recoverable-guest-profiles.md`。政策与graphics阶段已读版本逐文件比对相同。`code-review`调用同名歧义后读取项目SKILL；遵从用户不派生要求亲自按Standards/Spec两轴审查，不独立approve本人实现的codec/relay。

## 1. 范围与快照

基线HEAD无本票commit，采用文件字节/hash快照而不是空的HEAD diff。profiles仍在收口，审查发现即时发送协调者，复核采用新快照；不等待未知长任务、不将测试fixture提升为guest事实。

最终生产快照（`A/implementation/evidence/profiles-review-snapshot-03.json`）：

| 文件 | SHA-256 |
| --- | --- |
| guest-assets/features/profilectl.py | `2e4455a95842ff3b89b5d7f727581774cd2895f37baf798a451eec649fedfbd1` |
| guest-assets/features/bootstrap-python.sh | `b7ac2fbda4bd6347a413b68b76c5faf6beb96090e77ace08feb6455eafcf0873` |
| guest-assets/features/feature-session.py | `1eb6809e7509f96e09d99847743025b22e882cd3c3bc1f781e93d4cb74d08bdb` |
| guest-assets/features/profilectl | `cd40f0a801b93dac99965a3d8171a52a7f6f0dfd0fe1cf6a5b48b7707afed609` |
| guest-assets/features/inputs.json | `4758dfd0383c0b174f443202602cdcab256fa69c0dce4abbad8b1f8453e816d7` |
| guest-assets/features/rootless-probe.py | `b4ff25fc63469b892f6c5332ccc6a87534da2a6380abe70a46ed4d25b42fea39` |

完整manifest还包括fake-runner/fault-process/fixture-http/fixture-port测试字节及integration窄接口hash。最终源码审查冻结在snapshot03；snapshot02到03的生产delta仅移除wayvnc配置的enable_resizing项、使用固定`--max-fps 15`命令参数，已静态复核。测试执行证据仍对应snapshot01/02，不把后续未复跑的测试字节纳入成功声明：snapshot02测试SHA为`8e02174aed8af71a39de2bd516bbdd4088bb27eae9bff5c35c4e3143d92d7841`，snapshot03为`abbaf206345825c8af18d03e51659bc366f89ececb80707bdcee5a81816adce6`；03全套本review未运行。

外部唯一输出根沿用已授权 `D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/A/implementation`。只读拷贝位于temp明确审查目录，证据位于evidence、即时副本位于preserved。无网络请求、无adb/设备、无系统安装/用户创建/WSL配置修改、无commit/merge/reset/push。唯一integration写入是本报告；其它production/test/ADR/tracker零写入。

## 2. Standards 轴

最终无未解决的政策/工程规范P1/P2。

- 输入与文件边界：仅固定profile/action与已批准下载域名；代码不发送host内容；code-server固定official ARM64 URL/size/SHA校验先于解包，redirect限制HTTPS固定allowlist，关闭proxy隐式端点；APT保留签名校验并拒trusted/allow-insecure bypass。解包限制成员数量/总大小/绝对或`..`路径/设备/FIFO，Python data filter约束symlink/hardlink，随后归一化owner服务目录权限；没有执行archive中用户指定任意shell入口。
- 状态与并发：0600 state、fsync+replace+目录fsync、全局operation锁、PID+boot/start identity；磁盘满owner发布失败修复后不留下永久空锁，旧空锁与创建窗口区分；install job已spawn而owner发布失败回收child/锁/状态。取消与timeout只回收本job进程组，不删除基础盘或用户workloads。
- 普通用户：uid/gid与home/shell检查，root primary/supplementary group拒绝；systemd以普通用户运行labwc/wayvnc/code-server，root仅承担固定token/device relay supervisor；不使用`--no-sandbox`。runtime/private目录0700、umask0077、固定relay路径与参数。
- 证据纪律：所有新增原始证据`.json`/`.log.txt`，不用`.log`；真实shell/local socket与synthetic command/metadata边界逐项标识，没有GPU/设备成功声明。保留旧失败证据，不销历史。

Standards期间P2：ordinary-user未拒gid0/补充root组、磁盘满owner发布遗留锁、bootstrap取消早释放锁及dash builtin kill参数问题。均已修复，见第4节；不将此worker自审等同其他模块独立review。

## 3. Spec 轴

最终无未解决的源码行为/窄API P1/P2。

| 验收主题 | 最终核对结果与边界 |
| --- | --- |
| signed inputs / tar containment | 静态检查符合输入锁与allowlist；现有合成archive越界/未知URL/hash失败测试有意义。解包后正规权限，普通用户配置保留。真实ARM64下载/发行版安装本review NOT_RUN。 |
| 状态atomic/cancel/baseboot | 基础version2 ready+PID1 systemd+dbus/ssh+provision lock门后才APT；安装installed与start/awaiting/ready分离。取消/失败不修改base init、rootfs。boot id使旧capture/auth proof不可延用。磁盘满可恢复锁定点通过。 |
| Python bootstrap | Python缺失只通过签名APT bootstrap，独立后台worker与可查询state；取消保持锁直到TERM→KILL→reap/确认无活group。使用绝对/bin/kill兼容真实/bin/sh；普通bash fixture不足以证明此项，独立shell probe已覆盖。 |
| normaluser/systemd/capabilities | root组拒绝，user runtime与service ownership清楚；desktop首组合仅Debian13已知包版本，其他未知desktop拒绝。Wayland globals+current foot/waybar pid/uid/exe+独立grim capture后才awaiting-frame；input keyboard/pointer只在同capture generation/protocols有效时为true，Unicode明确false。Podman info、run、DNS/TLS/volume/port分层，Docker CLI不升级daemon已验证。 |
| grim/capture/placeholder | PPM尺寸/像素长度/4MP/16MiB限制、非均匀内容、当前客户端存活；CONFIRM最终mutex内重新核expected generation==capture_generation、boot、state/proofs/cancel/live job，取消/resize不能被旧frame改ready。这个设计分别证明guest capture与host presentation；它不能用first Raw证明wayvnc绝无placeholder，也不证明GPU。本review没有实际grim/wayvnc/device执行。 |
| relay fixed API/unit | 固定`/run/user/<uid>/orchestella/wayvnc.sock`、root0600token与专用char device/sysfs名门、`display-relay.py --user orchestella --transport virtio` 或 `--transport vsock`匹配约定；virtio正常EOF exit0的unit已Restart=always/RestartSec=2，KillMode=control-group/BindsTo保留；明确stop不会自动再启。AVF path仍需真实probe，不因marker称capability。 |
| code-server auth | secrets.token_hex(32)生成、user-owned0600config、仅127.0.0.1:8080、config保留但不安全/明显弱重复pattern拒绝；无凭据URL/log；cookie suffix独立、未登录拒绝+healthz+login cookie+受保护workbench真实HTTP检查，成功才authenticationConfigured。真实code-server业务执行本review NOT_RUN。 |
| guestNIC EXPOSE lifecycle/schema | 只固定192.168.127.15:18080，显式action，auth与primary unit健康后建立；start失败/cancel会撤relay；UNEXPOSE检查unit+实际listener消失；STATUS核对primary/relay/listener并清stale exposed，auth/service失败撤ownedrelay。与host LocalEditorActivity的state/authenticationConfigured/exposed消费一致。guest NIC显式转发与host LAN十分钟lease是不同边界，不混称公网监听。 |

## 4. 发现与修复复核

审查过程中正式即时反馈8个P2；下表记录触发与修复证据，最终均已关闭。

| ID | 当时问题与触发 | 最终修复/复核 |
| --- | --- | --- |
| P2-R1 | display relay `Restart=on-failure`，正常RFB EOF exit0后后续重连无reader。初snapshot profilectl行465/478。 | 独立root relay unit `Restart=always`、2s延时，显式stop和依赖生命周期保留；source+worker目标测试核对。 |
| P2-R2 | CONFIRM先校验、systemctl后无条件save，STOP/CANCEL插入可返回ready/generation none/capture unverified。 | 最终mutex重读、generation/capture_generation/boot/cancel/live job全量条件提交；独立交错probe返回stale-frame并保持cancelled，worker新增回归。 |
| P2-R3 | STATUS只primary service，保存guest_relay标志使inactive relay仍exposed=true，host会向死endpointforward。 | STATUS relay unit+实际listener检查、失效清flag/地址/port；独立probe exposed=false；workerEXPOSE/UNEXPOSE目标覆盖。 |
| P2-R4 | UI读取旧generation后displayMode触发新generation，PresentationObserver持续旧generation或已confirmed旗标。 | DesktopPresentationGate/周期STATUS按capture generation+尺寸变化重置，reconnect与fresh timestamp门，拒旧generation完成；源码窄API复核，Android实际NOT_RUN。 |
| P2-R5 | bootstrap cancel cleanup只TERM即unlock；修订后dash builtin `kill --`被拒又吞错，group仍活、wait长期挂。 | `/bin/kill`外部实现、TERM有界wait→KILL→reap/活group检查后unlock；真实/bin/sh+忽略TERM合成apt结果listener/process已停、status cancelled/stopped。 |
| P2-R6 | 64hex形状等同强auth，已有全重复weak password仍可START/EXPOSE。 | 保留config但拒低distinct/repeated pattern；独立合成config拒绝，workerweak凭据回归。 |
| P2-R7 | command.lock mkdir后owner ENOSPC在yield前遗留无owner，后续永久busy；install.lock/spawn owner I/O同风险。 | 新建lock失败及时清理，旧空锁有时限恢复；launch异常回收已spawnchild/锁。独立ENOSPC retry accepted，最终3个目标测试通过。 |
| P2-R8 | uid普通但gid0/supplementary root组未拒，systemd Group0且与固定relay normal_user门冲突。 | profilectl及feature-session拒root组，保留普通会话；最终目标测试通过。 |

R5的初问题及dash修订复核合为同一生命周期finding。EXPOSE取消残留的初步疑点在正式定论前已被worker修复，独立probe确认listener关闭，因此未另列finding。

当前未剩需要request-changes的P1/P2。所有修复属既定spec/协议/权限语义，未修改ADR/产品承诺，不触发新的Architecture Change Request；AVF/GPU既有决策事实边界仍由协调者处理。

## 5. 窄 integration API 核对

- guest-agent `run_feature_helper`：固定profile/action白名单、无shell、清其它FD、3秒总期限、≤4096B单行JSON；安装/启动后台job立即返回受理state。`CONFIRM-FRAME`仅desktop且32 lowercase hex；v1未知command拒绝不破坏后续PING。vsock控制worker已重置SIGCHLD为DFL，避免helper waitpid被全局SIG_IGN吞掉。这里为源码检查，C helper真实集成测试本review未跑，不能替代协调者C回归。
- profilectl输出：≤4096B JSON行、静态错误reason、不含password/process_identity/config secrets；`input.keyboard/pointer/unicodeText`、`authenticationConfigured`、`exposed`、`generation/capture_generation/capture_width/capture_height`与host当前消费匹配。
- GuestFeaturesActivity消费phase/reason/state，失败不写桌面ready；LocalEditorActivity显式EXPOSE、轮询exposed后打开host回环forward，关闭发UNEXPOSE、auth失败撤forward，LAN需另行显式lease。
- DesktopActivity `applyDesktopEvidence`/DesktopPresentationGate：同capture generation+尺寸，新receive timestamp后成功presentation才能CONFIRM；周期poll捕获resize generation，旧缓存帧/旧finish被拒。仅审API/证据门，不审查本人RFB codec作为独立approve。

## 6. 实际测试、失败与证据

Linux执行入口：只读复用现有WSL `Ubuntu-24.04` `/usr/bin/python3` 与 `/bin/sh`，Windows参数数组调用wsl，PYTHONDONTWRITEBYTECODE=1/TMPDIR与输出均指定A/implementation/temp。没有真实guest/设备操作。

独立定点复现（外部脚本不改被审代码）：

- `profiles-review-bootstrap-01.json`：最初取消group仍活而lock移除；保留真实RED。
- `profiles-review-bootstrap-02.json`：修订dash builtin参数后group仍活/锁保留；保留失败，不销历史。
- `profiles-review-bootstrap-03.json`：final `/bin/kill`真实shell probe，group inactive、lock移除、cancelled/stopped。
- `profiles-review-state-02.json`至`state-04.json`：public command/worker+合成外部systemd/auth边界，EXPOSE取消真实本地socket回收、stale exposed纠正、CONFIRM竞态、弱密码、ENOSPC锁恢复；每次源码前后hash一致。不是guest/systemd证明。

现有CLI合成测试只运行于外部字节快照，避免测试中chmod runner对生产树产生写入：

1. snapshot01完整运行曾超过外部180秒runner期限；partial结果含2个FAIL并停在bootstrapqueryable。该历史运行**没有全套通过**、没有完整trace（writer未走到最终summary）；原因未确认，不擅称prod失败或成功。
2. 同snapshot01 fresh root定点复跑baseline_wait_and_cancel及code_authenticated_http_listener_lifecycle_and_tamper：`Ran 2 tests in 16.898s, OK`。证据`profiles-review-target-01-tests.log.txt/.json`。
3. 同snapshot01 bootstrapqueryable fresh root：`Ran 1 test in 11.261s, OK`。证据`profiles-review-bootstrap-query-tests.log.txt/.json`。
4. snapshot02最终原子发布磁盘满/普通用户root组/旧空锁三个目标：`Ran 3 tests in 21.118s, OK`、guestValidated=false。证据`profiles-review-target-02-tests.log.txt/.json`。未重复全套长测试；主协调者/profileworker的最终full gate由其真实记录承担。

完整快照hash evidence为`profiles-review-snapshot-01.json`、`profiles-review-snapshot-02.json`、`profiles-review-snapshot-03.json`。原始runner超时的partial输出在`temp/profiles-review-snapshot-01/test-output/profile-contracts.txt`，同时保全到evidence/preserved。所有test耗时仅宿主synthetic执行耗时，不是guest性能benchmark。

## 7. NOT_RUN 与验收分级

明确NOT_RUN：真实Debian/Ubuntu ARM64签名包安装；真实code-server官方tar业务执行及登录；真实普通用户systemd units/停止重启；labwc/grim/wayvnc当前capture与display relay virtio会话；AVF vsock成功probe/VM连接；Podman真实userns/cgroup/seccomp/overlay/容器网络；Androidnative Surface/input/旋转/URI/WebView/设备截图；GPU/零拷贝/60FPS与整机性能。

fixture和身份/原生shell/socket probe只证明具体宿主边界。批准为源码审查与本地合成/定点失败门可审查，不提升guest/Android验收状态。若生产文件在上述hash后变化，需review改动delta；本报告不对未来代码默认放行。

## 8. 输出与治理

实际新增文件仅本integration报告，plus授权外部temp/evidence/preserved审查产物；未改其它worker源码/测试或自身codec，不修改policy/ADR/spec/tracker。无commit。耗时未统计，Class A保留。无网络出站/真实token读取、无系统或设备修改。

规范轴最终open P1/P2：0；Spec/窄API轴最终open P1/P2：0。已解决finding 8项，当前最高未解决项：无；设备/guest验收均NOT_RUN。

报告人：profiles独立reviewer / new-provider/gpt-6.1-sol ｜ 日期：2026-10-13 ｜ 耗时：未统计

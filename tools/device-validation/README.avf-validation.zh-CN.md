# AVF 只读取证与阶段样本工具

工单 avf-shizuku-04。仅支持 Python 3.12+ 标准库，且必须提供可调用的 `pathlib.Path.is_junction`；本轮实测 3.12.9。两个公开 CLI 在访问 adb 或证据前检查运行环境，`EvidenceStore` 构造及文件路径入口也执行该检查：低于3.12或缺少该能力时以固定 `RUNTIME_UNSUPPORTED` 拒绝，CLI返回2，不创建输入/输出目录或结果文件，不以False跳过junction检查。工具校验证据结构、关联与完整性，**不认证物理真实性，不签发真机通过结论**。权限/feature/JSON 中的 engine/boolean/hash 均不能代替真实 owned AVF 验收。

本票04没有调用真实 adb，没有连接/启动设备、授权、安装 APK 或运行 VM/Guest；测试全部合成。零设备是2026-10-15的历史状态；2026-10-16主协调已发现device-01/user0，设备操作由主协调独占，Worker E没有调用设备。07真实闭环前，09真实 AVF 样本仍为0，性能保持blocked。研究原型的96项、04原100项和本轮新增recorder回归分别报告，不累加为实测证据。

## 文件与边界

- `avf_preflight.py`：固定命令只读发现/预检；collect 显式指定 serial，日志不保存 serial 或 raw stdout。
- `avf_benchmark.py`：离线样本+receipt 校验、固定单调阶段汇总；没有 shell/VM/按键执行能力。
- `avf_evidence_io.py`：显式 input/output root、受限相对路径、严格 JSON、原子不覆盖输出与独立 preservation 副本。
- `avf_test_fixtures.py`：仅测试使用的合成构造源码，不是两个 CLI 的运行依赖；不包含真实设备/用户证据，不生成文件到 tools。
- `test_avf_tools.py`：公开 CLI、合成证据根、fake adb 子进程回归；直接脚本与 unittest 两入口。

模块导入只依赖同目录固定名称；测试以 `Path(__file__).resolve().parent` 显式加入 sys.path，不依赖研究目录或当前工作目录。运行产物均写操作者明确提供的外部根，不默认创建 C:temp，不搬研究 run_checks/大型 fixture。历史 terminal/distro 工具保持原样。

## 实际测试命令

从仓根执行，唯一必需环境变量是绝对外部目录 `AVF_VALIDATION_TEST_ROOT`。目录中按每个用例 UUID 保留合成输入、结果与保全；不会自动清理。`-B` 或 PYTHONDONTWRITEBYTECODE 避免 bytecode 产物。

```bash
AVF_VALIDATION_TEST_ROOT='D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/avf-shizuku-next-stage/20261015/implementation-04/test-runs/manual-unittest' \
  PYTHONDONTWRITEBYTECODE=1 python -B -m unittest discover \
  -s tools/device-validation -p 'test_avf*.py'

AVF_VALIDATION_TEST_ROOT='D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/avf-shizuku-next-stage/20261015/implementation-04/test-runs/manual-direct' \
  PYTHONDONTWRITEBYTECODE=1 python -B tools/device-validation/test_avf_tools.py
```

不需要 adb/SDK/JDK/设备。fake adb 由 Python 子进程运行，只校验固定 argv 并输出合成属性；不会调用真实 adb。运行能力负例通过独立子进程模拟当前解释器缺少is_junction以及Python3.11，对公开IO/两个CLI入口核对拒绝且目标目录零写入；这是故障注入，不是实跑旧解释器兼容验收。路径链接拒绝测试在 Windows 无 symlink 权限时建立测试目录内 junction，仍只触及外部测试根；若主机两种方式均不允许则明确 skip，不宣称该项通过。旧99项结果保留，新复审回归使用implementation-04/evidence/p2-runtime-*标签。

## CLI

两个 CLI 都必须传入显式绝对 approved root；文件参数只能为该根内受限相对路径。以下设备命令只供后续已获准时使用，本票未执行。

```bash
python -B tools/device-validation/avf_preflight.py discover \
  --adb 'D:/手机调试与开发相关/platform-tools/adb.exe' \
  --output-root "$APPROVED_AVF_OUTPUT_ROOT" --output discovery/counts.json

python -B tools/device-validation/avf_preflight.py collect \
  --adb 'D:/手机调试与开发相关/platform-tools/adb.exe' \
  --serial "$QA_SERIAL" --device-alias qa-device-01 \
  --output-root "$APPROVED_AVF_OUTPUT_ROOT" --output preflight/facts.json

python -B tools/device-validation/avf_benchmark.py \
  --evidence-root "$APPROVED_AVF_EVIDENCE_ROOT" \
  --output-root "$APPROVED_AVF_OUTPUT_ROOT" \
  --manifest manifest.json --samples samples.json --output benchmark/summary.json
```

所有输出目标必须未占用。CLI 返回 0 表示输入结构接受；benchmark 返回 1 表示含排除样本；2 表示参数/JSON/hash/路径/I/O 拒绝或 preflight 部分失败。错误只输出固定 reason code，不回显输入、OS error 或 adb stderr。0 也不代表真机通过。

## Preflight allowlist

固定读取 ROM build ID/release（有限 ASCII 标识）、ROM fingerprint（仅保存 SHA-256）、API、ABI allowlist、page size（4096/16384/65536）、com.android.virt 或 com.google.android.virt 的 APEX versionCode、AVF feature、hypervisor protected/nonprotected 声明。只使用固定 getprop、getconf PAGE_SIZE 和 cmd package list/has-feature；不接受动态 shell 路径/命令/package。`subprocess` 用 shell=False、stdin DEVNULL、stderr DEVNULL、10 秒 timeout；MSYS_NO_PATHCONV 仅设置子进程，错误即停，无自动重试。

MANAGE/CUSTOM 权限与真实 framework capability API 无已批准 typed probe，因此固定为 unknown。feature/property 声明不推导 custom Linux ready；不同 ROM 输出不兼容时字段 unknown 或命令非零后停止，不写成功。预检从不启动 VM。

工具不执行 grant/install/start/stop/settings/su，不读取 dumpsys/logcat、实例 metadata、根盘、embedded initrd、token、口令或按键，不建立上传、远程端点或后台队列。设备发现只记计数，不选择设备。

## 样本接收与阶段

manifest 绑定 samples 文件精确字节 SHA-256；样本绑定 APK SHA-256、完整 commit、公开设备/owner/VM/QA volume alias、ROM/API/ABI/page/module、kernel/**基础** initrd/rootfs SHA-256、vCPU/RAM/disk、调用上下文、cold/warm/cache、电源/温控/充电条件。输入 hash 是已批准资产的记录，不会触发工具去读取设备文件、用户根盘或 embedded initrd。

每个事件必须关联相同 session/generation/owner/VM，时间为同一宿主 `android_elapsed_realtime_nanos`。`receipt_sha256` 只允许非零64位hex；唯一原件读取位置为显式 evidence root 下 `receipts/<sha>.json`。原始字节hash、kind、sample/session/generation/owner/VM、clock、完整 binding hash、event/source/outcome/时间必须一致；任意额外字段也拒绝。无原件、坏hash、路径逃逸/链接、错误session/代际、资源混组、QEMU/fallback/UNKNOWN/失败和重复样本均排除并保留原输入，不能进入 AVF device candidate 统计。

```text
launch_requested → preflight_begin → preflight_end
→ inputs_begin → inputs_verified → manager_begin → vm_created
→ run_begin → run_returned → vm_running_observed
→ vsock_connected → agent_authenticated → guest_command_ok
→ services_ready → stop_requested → stop_completed
```

custom Linux 没有通用 onVmStarted 门，不能要求 Microdroid payload callback。run 返回、RUNNING state、vsock连接均不单独表示 Guest ready。Guest标记用宿主收到类型化原件的时刻，不混用Guest时钟；run-to-vsock为组合区间，不标纯kernel/init耗时。固定 `qa-uname-cpu-v1` 仅表达获准控制器经原单reader执行 uname/CPU只读查询的结果，保存架构、kernel release、online CPU、exit code；本工具不发送命令，不解析终端正文。

`stop_completed` 只能由实际 recorder 在匹配正常停止意图、真实停止结果和owned资源/FD释放后产生；任意 onStopped/EOF/exit=0 不能统一映射为正常停止。recorder 本身及物理执行须在独立设备/lifecycle工单验收。

`kind=synthetic` 永久单列 synthetic_preview，AVF device candidate 为0。device输入只有完整原件/结构和review声明通过后给 **候选**统计；reviewed布尔和自洽JSON可以伪造，不能认证来源。始终输出 `authenticity=NOT_AUTHENTICATED_BY_THIS_TOOL`、`device_verdict=NOT_DETERMINED`。测试包含故意伪造整套device标签的合成反例，确认工具仍不签发真实性结论；这些也是合成数据。

仅输出11个阶段的 n/median/min/max（ns），不报p95/p99、不做跨资源或冷热混比、不报告CPU/RSS/I/O/FPS或AVF提速。需要这些性能结论必须先完成真实 owned AVF 闭环及独立审查。

## I/O 与保全

输入每文件限2MiB、最多500样本，拒绝JSON重复key/NaN/Infinity、额外字段、非整数单调时间。绝对文件参数、`..`、隐藏目录、动态shell路径、符号链接/junction、新`.log`证据均拒绝。

输出先分别写入目标目录内独立 staging 文件并fsync，先发布 preservation/同相对路径副本、再发布原件；各次发布通过同文件系统hard-link保证完整且不覆盖既有名字，原件与保全副本**不共享inode**。不支持该文件操作则失败即停，不回退为覆盖。只删除本次私有staging名，不清理证据或历史文件；中途失败可能留下完整保全原件，保留供诊断。此实现不承诺跨两个文件的事务或对抗同一目录恶意并发改写；操作者需独占该证据目录。双份同盘不是异地备份。

owner D / 主协调，所有真实或合成证据长期归档、无自动清理。研究报告、原始red/green和最终回归在获准批次的 validation-design 与 implementation-04；实施报告给出具体路径。

## 2026-10-16：22离线 typed recorder

`avf_stage_recorder.py` 是独立的离线行为组件；`test_avf_recorder.py` 仅测试其公开接口。它复用现有 `avf_benchmark.CHAIN`、alias校验、`avf_evidence_io.json_bytes` 和显式 `EvidenceStore` sink，不增加第二套preflight、validator或统计器，不接入Android生产链。

接口为 `CaptureIdentity(owner_alias, session_id, generation, vm_alias)` 与 `StageRecorder(enabled=..., identity=..., sink=..., clock=None, max_bytes=32768)`；只有显式传入的sink会接收固定JSON bytes。`mark(Stage, identity=..., route=Route.OWNER)` 必须依现16门顺序；agent认证、QA命令和services标记要求 `Route.AUTHENTICATED_SINGLE_READER`。这个枚举仅防止接线误用，不能认证物理单reader或真实授权，后续仍需原owner接线和独立审查；组件不读取任何控制/终端流，不接受命令、正文、token、路径、异常详情等payload。

`cancel`、`fallback`、`fail(Failure)` 退役当前trace，迟到事件不恢复；错owner/session/generation/VM在读clock或sink前拒绝，不损坏当前代。时间必须是非负int64且不倒退；默认标 `python_monotonic_ns`，注入clock标 `injected_monotonic_ns`，**永不冒充Android elapsedRealtimeNanos**。kernel固定not_observed，payload callback固定not_applicable。

正常停止要求先有STOP_REQUESTED，再由 `observe_stop(StopFact, identity=..., route=...)` 分别确认guest关机ack（认证路由）、owned VM停止、owned资源释放；缺任何一项拒绝STOP_COMPLETED。所有事实都是离线调用者输入，不能替代真实shutdown/FD/reader取证。`finish` 仅当16门完整时返回 `COMPLETE_OFFLINE`；snapshot永远 `real_receipt_count=0`、`device_verdict=NOT_DETERMINED`。trace的kind固定 `offline_stage_trace`、provenance固定 `synthetic_seam`，没有receipt/hash/manifest导出器，不能送入现benchmark成功集。

关闭时不保留identity/clock/sink，不采集、不读clock、不写文件、没有后台任务或pending队列。开启时最多16事件、总JSON payload最多32KiB，sink抛错或显式返回False即固定SINK_FAILED并退役，不重试、不排队；snapshot仅有限计数与身份/终态，不保留事件正文。组件为单调用者同步seam，要求操作者串行调用且sink及时返回；它没有为阻塞sink提供线程中断或Android总deadline保证。生产跨线程/跨进程适配、Android clock与有界落盘机制仍由原owner工单另验，不能直接移植此Python组件当生产recorder。

本轮最终公开入口回归：原100项+新增27项，**127 tests/0 failures/0 errors/0 skips，17.021s，exit0**。收口发现falsey callable clock被`clock or monotonic_ns`忽略，新增独立red→green后改为仅None选择默认clock；原26/126结果保留为早期修订，不累计覆盖。新增核心切片各有实际red→green，其余是同既定行为的边界扩展，不虚称每条都曾red。证据根为 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/benchmark/`，最终 `evidence/final-tools-clock-fix.{json,stdout.log.txt,stderr.log.txt}` 与preservation为原件，测试临时目录在同根test-runs；最终源码清单与核验为closeout/delivery-manifest.json、closeout/preservation-audit.json。

等价复验（选择未占用新外部目录，直接脚本也支持同一27项）：

```bash
AVF_VALIDATION_TEST_ROOT='<本批专属外部绝对根>/test-runs/独立新标签' \
  PYTHONDONTWRITEBYTECODE=1 python -B -m unittest discover \
  -s '<唯一源码绝对根>/tools/device-validation' -p 'test_avf*.py' -v
```

07主协调独立真实AVF闭环前，09样本0；10仍blocked-measurement，不选择瓶颈、不报收益。最小生产插桩请求已交原owner/主协调，详22中文报告；Worker E没有运行adb/UI/Gradle、设备安装/启停、Git写操作或改变版本。

## 后续设备验收清单：本票未运行

- [ ] 先确认具体设备/操作授权，显式serial与公开别名；指纹/口令由用户本人处理。
- [ ] 保护签名兼容，历史release key不自动启用；APK/commit/hash与实际安装代码一致。
- [ ] 通过现有获准流程在本机保护实例身份/凭据/根盘引用，不输出metadata/秘密；本工具不读取这些内容。
- [ ] 保护活VM和包事务，先经原owner正常完成/停止；不force-stop、不杀VMM、不盲装APK。
- [ ] 只用明确QA盘与固定合规资源，不对用户盘做写benchmark，不格式化/公开私有盘；不改全局hidden API/SELinux/thermal。
- [ ] 权限/capability unknown继续停门，由获准typed probe核实；不在工具内grant。
- [ ] 本轮owned create/run/state/认证vsock/Guest命令/services/stop原件闭环；系统其他crosvm或预检徽章不算证据。
- [ ] 保持原认证和唯一control reader，取消/迟到回调/FD释放由对应生命周期验收；无无限轮询。
- [ ] fallback/QEMU/未知/失败保留并排除，独立审查recorder及设备动作后才能签收真机通过。
- [ ] 用同owner正常stop，确认STOPPED和资源释放；恢复此前明确改动，保留用户实例引用。失败回滚不清app数据、不卸载、不覆盖历史APK/tag、不擅自删除QA盘。

## 2026-10-20：09 AVF 设备验收 dry-run

`run_avf_device_validation.py` 是一条命令的 AVF 设备验收入口，阶段严格对应 research §3.3 的 12 步：platform_declaration、capabilities、permissions、protected_mode、create、run_returned、vsock_handshake、stop_cycles、network、share_display、app_death、qemu_comparison。它不是新的证据认证器：JSON schema 固定为 `schema_version=1`，每阶段只有 `pass` / `fail` / `blocked` / `not-tested` 四种状态。

```bash
# 零设备 dry-run：不连 adb，所有阶段 not-tested，退出码 0
python3 -B tools/device-validation/run_avf_device_validation.py \
  --dry-run --out "$APPROVED_AVF_OUTPUT_ROOT/dry-run"

# 09 历史 stub（2026-10-20 已被下一节的设备模式取代）：不调用 adb，所有阶段 blocked
# python3 -B tools/device-validation/run_avf_device_validation.py \
#   --device "$QA_SERIAL" --device-alias qa-device-01 --out "$APPROVED_AVF_OUTPUT_ROOT"

# 校验已有报告 schema；坏 fixture 以 REPORT_SCHEMA_REJECTED 拒绝
python3 -B tools/device-validation/run_avf_device_validation.py \
  --validate report.json --out "$APPROVED_AVF_OUTPUT_ROOT"
```

输出为 JSON + Markdown，写入显式绝对根下的新文件名（带 UTC 微秒），并复用 `EvidenceStore` 的 preservation 副本。`--dry-run` 的每步 `evidence`/`rom_binding` 都是 `not-collected`，`real_avf_samples=0`，`verdict=NOT_TESTED`。工具本身不 root/解锁、不启停 VM；09 时 `--device` 只是 stub，2026-10-20 的设备模式见下一节。dry-run 通过只表示“工具与 schema 就绪”，不表示 AVF 已在设备上成功。


## 2026-10-20：设备模式（Session E）

`--device` 已从 stub 变成 host 侧真实执行路径；`--dry-run` 的输出保持不变（12 个 `not-tested`、0 pass、`real_avf_samples=0`）。

### Host 执行

- `avf_device_mode.py` 的 `AdbRunner` 是唯一生产命令执行器：固定 argv、`shell=False`、每命令 timeout、整轮 deadline、失败不重试；命令日志只保留 id/argv/timeout/exit/duration，并把 adb 路径、serial、APK 路径替换为 `<adb>` / `<device>` / `<apk>`，不保存 stdout/stderr。
- 设备发现要求 `adb devices -l` 恰好一台 `device`，且 serial 与 `--device` 一致；serial 只用于比较，不写入报告。多设备、unauthorized、offline、无设备分别以固定 reason code 停止。
- 复用 `avf_preflight.collect` 的只读命令读取 API/ABI/feature/hypervisor 声明；不再维护第二套设备命令。
- QA 包必须包含 `.qa.`。可用 `--apk` / `--test-apk` 安装 QA 包与 instrumentation 包；否则用 `pm path` 检查已安装。只安装 QA 包，不安装生产包。
- `--grant` 必须显式传入。host 会对 QA 包执行两条 `pm grant`，随后 `am force-stop` QA 包并重新启动探针复核；没有 `--grant` 时一条 grant 都不会执行。grant 失败、权限仍为 denied 都以 blocked/fail 停止。
- `--stage` 支持 `minimal`（默认，资格→grant→create→run→vsock→stop 的 8 个阶段）、`all` 或逗号分隔的阶段名；未请求的阶段为 `not-tested` / `STAGE_NOT_REQUESTED`。`--cycles 1..20` 传给 QA 探针。

### QA-only 设备探针

- `app/src/androidTest/java/io/github/nyzeep/orchestella/AvfQaProbeInstrumentation.java` 是 androidTest 专用 `Instrumentation`，不进入 release APK，也没有 launcher 入口。它拒绝在包名不含 `.qa.` 的 target 上运行。
- 探针调用现有 `ReflectiveAvfPlatformAccess` 读取 access/exemption/permission/mode facts，反射读取 capability raw bits，并用 `VirtualMachineConfig.Builder.setProtectedVm(false)` 记录 protected-mode 结果；ROM fingerprint 只保存 SHA-256。它不 root、不改 hidden API 全局策略、不启动 VM。
- 探针把 JSON 写到 QA app 私有目录 `files/avf-qa/probe-<token>.json`；host 用 `adb exec-out run-as <qa> cat ...` 拉取。令牌每次随机，报告只保存令牌的 SHA-256。
- 本票的探针只完成资格观察；`create`/`run_returned`/`vsock_handshake`/`stop_cycles` 明确返回 `blocked` / `QA_LAUNCH_ENTRY_NOT_IMPLEMENTED`。host 不会把缺失的探针结果补成 pass；将来接入 QA 启动 harness 后，host 的 stage 协议可直接接收其结果。
- `real_avf_samples` 只有在 create、run_returned、vsock_handshake、stop_cycles 四个阶段都真实 pass 时才为 1；其余为 0。

### 命令示例

```bash
# dry-run 不变
python3 -B tools/device-validation/run_avf_device_validation.py \
  --dry-run --out "$APPROVED_AVF_OUTPUT_ROOT/dry-run"

# 设备资格 + 显式 grant + 最小阶段
python3 -B tools/device-validation/run_avf_device_validation.py \
  --device "$QA_SERIAL" --device-alias qa-device-01 \
  --qa-package io.github.nyzeep.orchestella.qa.devicemode \
  --qa-test-package io.github.nyzeep.orchestella.qa.devicemode.test \
  --adb "$ADB" --grant \
  --out "$APPROVED_AVF_OUTPUT_ROOT/device"
```

### 本回合结果

本环境 `adb devices -l` 没有连接设备；用真实 adb 执行设备模式的报告为 `NO_DEVICE`、8 个请求阶段 blocked、4 个 not-tested、`real_avf_samples=0`。没有安装 APK、没有 grant、没有创建/启动 VM，也没有任何真机 AVF 结论。fake adb 只证明 host 编排、超时/停止/脱敏和报告 schema；不是设备证据。

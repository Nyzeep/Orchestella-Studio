# AVF 设备模式与真机验收（Session E）

- 日期：2026-10-20
- Base：avf/zero-device-integration @ cd4ad29
- Branch：lane/device-validation（远端目标 ticket/avf-device-mode）
- 代码提交：8e6f661
- 远端：github = git@github.com:Nyzeep/Orchestella-Studio.git
- 设备：本环境没有连接任何设备；没有可记录的设备 alias，也没有 serial。

## 1. 结论

1. --device 已从 stub 改为真实的 host 侧执行路径：设备发现、只读 preflight、QA 包检查/安装、显式 grant、QA instrumentation 启动、探针 JSON 拉取、阶段映射、报告与证据写入均已接线。
2. QA-only instrumentation 探针已完成资格观察：capabilities、权限、setProtectedVm(false)、ROM/API/ABI；它不启动 VM，也不进入 release APK。
3. 设备 launch 阶段（create/run/vsock/stop）在本票的探针中明确返回 blocked / QA_LAUNCH_ENTRY_NOT_IMPLEMENTED；host 的 stage 协议已能接收未来 QA launch harness 的结果，但不会补造结果。
4. 本环境 adb devices -l 没有设备，真实设备模式报告为 NO_DEVICE、8 个请求阶段 blocked、4 个 not-tested、real_avf_samples=0。
5. 没有安装 APK、没有 grant、没有创建/启动 VM、没有真机截图/日志；没有 AVF 已支持/已通过的声明。

## 2. 设备资格

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| adb devices -l 恰好一台目标设备 | blocked / NO_DEVICE | 本环境没有任何 device 行 |
| API >= 33 | not-tested | 没有设备，未读取 |
| ABI 含 arm64-v8a | not-tested | 没有设备，未读取 |
| android.software.virtualization_framework | not-tested | 没有设备，未读取 |
| getCapabilities bit2 | not-tested | 没有设备，未读取 |
| 两权限 + 显式 grant | not-tested | 没有设备；未执行任何 pm grant |
| setProtectedVm(false) | not-tested | 没有设备；未构造 config |
| protected-only? | unknown | 没有设备，不能判断 |

因此本报告不能把任何设备标记为合格或不合格；当前状态是“无设备，设备门未开始”。

## 3. 设备模式实现

### 3.1 Host 编排（tools/device-validation/avf_device_mode.py）

- AdbRunner 是唯一生产命令执行器：固定 argv、shell=False、stdin DEVNULL、stdout/stderr PIPE、每命令 timeout、整轮 deadline、失败不重试。
- 命令日志只保存 id、脱敏 argv、timeout、exit code、duration；adb 路径替换为 <adb>，serial 替换为 <device>，APK 路径替换为 <apk>；不保存 stdout/stderr。
- 设备发现要求恰好一台 state=device，且 serial 与 --device 一致；多设备、unauthorized、offline、无设备分别以 DEVICE_COUNT_NOT_ONE / DEVICE_UNAUTHORIZED / DEVICE_OFFLINE / NO_DEVICE 停止。
- 复用 avf_preflight.collect 的固定只读命令，读取 build id/release/fingerprint 哈希/API/ABI/page size/AVF module/feature/hypervisor 声明；不新增第二套设备命令。
- QA 包必须包含 .qa.；可用 --apk 与 --test-apk 安装 QA 包和 instrumentation 包，否则用 pm path 检查。工具不安装生产包。
- --grant 必须显式传入。host 只对 QA 包执行 MANAGE_VIRTUAL_MACHINE 与 USE_CUSTOM_VIRTUAL_MACHINE 两条 pm grant，然后 am force-stop QA 包并重新运行探针复核；没有 --grant 时一条 grant 都不执行。grant 失败或复核仍 denied 会停在 permissions 阶段。grant、install、instrument、force-stop、pull 执行前都会把脱敏命令打印到 stderr。
- --stage 支持 minimal（默认 8 阶段）、all、逗号分隔；未请求阶段为 not-tested / STAGE_NOT_REQUESTED。--cycles 1..20 传给探针。
- 报告新增 commands、device_facts、probe 三个字段；pass 阶段必须有 collected evidence；real_avf_samples 只有在 create/run_returned/vsock_handshake/stop_cycles 四个阶段都 pass 时才为 1。

### 3.2 QA-only 探针（app/src/androidTest）

- AvfQaProbeInstrumentation 是 androidTest 专用 Instrumentation，由 test APK manifest 声明，没有 launcher 入口，不进入 application APK。
- 目标包名不含 .qa. 时直接拒绝；它调用现有 ReflectiveAvfPlatformAccess 读取 access/exemption/permission/mode facts，反射读取 capability raw bits，并用 VirtualMachineConfig.Builder.setProtectedVm(false) 记录 protected-mode 结果。
- ROM fingerprint 只写 SHA-256；不写 serial/IMEI/MAC/账号/token/用户路径/屏幕内容。
- 探针把 JSON 写到 QA app 私有目录 files/avf-qa/probe-<token>.json；host 通过 adb exec-out run-as <qa> cat 拉取；报告只保存 token 的 SHA-256，命令日志把 qaRunToken 值和探针文件路径替换为 <token>。
- 探针不 root、不解锁 bootloader、不改 SELinux/hidden API 全局策略、不安装 APK、不 grant、不创建/运行 VM。
- 本票探针对 create/run_returned/vsock_handshake/stop_cycles 固定返回 blocked / QA_LAUNCH_ENTRY_NOT_IMPLEMENTED；这是有意的 fail-closed，不把资格观察当成 launch 证据。

### 3.3 构建验证

使用 QA channel 构建 instrumentation APK：

    JAVA_HOME=/opt/jdk17 ./gradlew :app:assembleDebugAndroidTest \
      -Porchestella.buildChannel=qa -Porchestella.buildPurpose=devicemode \
      --offline --no-daemon

结果：BUILD SUCCESSFUL；aapt2 核对 test APK 包名为 io.github.nyzeep.orchestella.qa.devicemode.test，instrumentation targetPackage 为 io.github.nyzeep.orchestella.qa.devicemode。默认 channel 的 test APK 也已编译通过；构建产物未入库。

## 4. 零设备测试

全部 fake adb 测试使用外部 scratch root，不连接真实 adb server、不安装 APK、不运行 VM：

    AVF_VALIDATION_TEST_ROOT=/tmp/avf-validation-all \
      python3 -B -m unittest discover -s tools/device-validation -p 'test_*.py'

结果：159 tests / 0 failures / 0 errors / 0 skipped。

新增 test_avf_device_mode.py 共 20 项，覆盖：

- 合格设备的 8 阶段最小闭环（fake probe）与 real_avf_samples=1；
- protected-only -> PROTECTED_ONLY，create 起 blocked；
- 权限拒绝且没有 --grant -> 不执行任何 pm grant；
- 显式 --grant -> 只对 QA 包执行两条 grant + force-stop + 重读；
- grant 失败 -> PERMISSIONS_GRANT_FAILED；
- create 失败 -> 后续 run/vsock/stop blocked；
- run returned 但 vsock 超时 -> run pass、vsock fail、real samples 0；
- stop 未确认 -> STOP_UNCONFIRMED；
- QA 包缺失 -> 不启动 instrumentation；
- 无设备 / 多设备 / adb timeout；
- 报告与 stdout 不含 serial，命令日志含 <device>；
- 未请求阶段 not-tested；
- --stage all 缺失探针结果 -> QA_STAGE_RESULT_MISSING；
- --cycles 传给探针；
- 生产包被拒绝；
- APK 路径脱敏；
- 设备报告可被 --validate 接受，伪造 pass 被拒绝。

Android 侧本会话还执行了：

    JAVA_HOME=/opt/jdk17 ./gradlew :app:testDebugUnitTest --offline --no-daemon

结果：BUILD SUCCESSFUL（该任务在本会话为 UP-TO-DATE；未改动 app 单元测试代码）。

    JAVA_HOME=/opt/jdk17 ./gradlew :app:lintDebug --offline --no-daemon

结果：BUILD SUCCESSFUL；lint 0 errors / 779 warnings。

## 5. 真实设备模式运行

在 /opt/android-sdk/platform-tools/adb 上执行：

    python3 -B tools/device-validation/run_avf_device_validation.py \
      --device FIXTURE-NOT-PRESENT --device-alias qa-device-01 \
      --qa-package io.github.nyzeep.orchestella.qa.devicemode \
      --adb /opt/android-sdk/platform-tools/adb --adb-timeout 5 \
      --out /tmp/avf-real-no-device

结果：

- summary：pass=0, fail=0, blocked=8, not-tested=4；
- verdict：BLOCKED；
- 首个失败阶段：platform_declaration / NO_DEVICE；
- create/run/vsock/stop：blocked，未尝试；
- stop confirmed：not-attempted（没有可停止的 VM）；
- real_avf_samples=0；
- 报告 JSON/Markdown 在外部输出根，未入库；报告不含 serial。

这不是真机运行：adb 只返回了空设备列表。工具在发现无设备后停止，没有安装 APK、grant、instrumentation 或 VM 操作。

## 6. 未测试项

- 真机 API/ABI/feature/hypervisor 声明与 getCapabilities bit2；
- App actor 与 shell/UserService actor 的 capabilities bits；
- 两权限的真实授予/冷启动复核；
- setProtectedVm(false) 在 non-protected 设备上的真实行为；
- App 私有 kernel/initrd/root raw create；
- run 返回与 guest ready 的区别；
- vsock 9100/9101 Bridge/终端握手；
- 正常 stop、20 次启停、孤儿/失败样本；
- 网络（无网络/静态/DHCP/Android 17 GpuConfig workaround/eth0/DNS/apt）；
- 只读 9p、RFB 显示与输入；
- App 进程死亡/强停后的 VM 收敛；
- 同资源 QEMU 对照与性能；
- HiddenApiBypass 6.1 在目标 ROM 的真实行为；
- protected-only 设备的真实异常时序。

## 7. 风险与阻塞

1. 没有设备：本会话无法产生任何真实 AVF sample；real_avf_samples 仍为 0。
2. 本票探针只做资格观察：create/run/vsock/stop 需要后续 QA launch harness；当前设备模式会在 create 阶段 blocked，不会假装最小闭环通过。
3. QA launch harness 需要独立的 QA 实例/根盘/共享目录与 payload；本票不创建用户实例，也不读取用户根盘。
4. run-as 依赖 QA debug 包可调试；QA release/生产包不支持此拉取路径。
5. 真机上 HiddenApiBypass 6.1、Android 17 网络、RFB/9p 仍可能失败；失败必须按阶段记录，不能升级为“支持”。

## 8. 用户数据与声明

- 是否触碰用户数据：否。没有安装/卸载、grant、force-stop 任何生产包；没有读取/修改用户实例、根盘、导出、备份、凭据；没有清数据/清缓存。
- 是否允许声明 AVF 已支持：否。真实 AVF 样本为 0，设备门未开始；本报告只声明设备模式代码与 fake adb 编排就绪。

## 9. 下一步

1. 连接一台声明 non-protected 的设备，确认只有一台目标设备；
2. 用 QA channel 构建 app APK 与 androidTest APK，按 README 的命令执行 --grant；
3. 先让 platform_declaration/capabilities/permissions/protected_mode 四项真实 pass；
4. 实现 QA launch harness（独立 QA 实例/根盘/共享目录），把 create/run/vsock/stop 的真实结果写入 probe JSON；
5. 最小闭环通过后，再按 research 3.3 做 20 次启停、网络、9p/RFB、App death；任何失败保留样本并降级，不写支持声明。

## 10. Session F：QA 启动链路与 protected-only 设备门（2026-10-20）

### 10.1 结论

1. QA 启动入口已从“只做资格、launch 固定 blocked”扩展为三种显式模式：`qualification`、`protected-microdroid`、`custom-linux`。所有 VM 只使用本次随机命名的 QA 实例；没有读取或改写任何用户实例/根盘。
2. `protected-microdroid` 使用系统 Microdroid OS 与 QA APK 内的 payload（`setProtectedVm(true)` + `setPayloadBinaryName`），create → run → 可选 vsock → 有界 stop；不加载 custom kernel/rootfs，不启用网络。仓库当前没有内置 Microdroid payload，因此必须显式传入 `--microdroid-payload`，否则探针返回 `QA_MICRODROID_PAYLOAD_MISSING` 且不创建 VM。
3. `custom-linux` 只在 non-protected bit（bit2）可读时进入；protected-only 或 bit2 缺失时 host 在启动探针前以 `NO_NON_PROTECTED_VM` 阻断，不创建 VM，不触碰用户数据。
4. protected-only 设备上的 `setProtectedVm(false)` 仍会被记录；`REJECTED` 是预期结果（`NON_PROTECTED_CONFIG_REFUSED`），不再把平台资格误判为失败。App 产品路径仍是 `MODE_UNSUPPORTED` + QEMU，QA 探针不改变产品 readiness。
5. stop 使用 `--stop-deadline-ms`（1000..60000，默认 15000）；只有 `getStatus()` 到 `STATUS_STOPPED` 才记 `stop_confirmed=true`，超时记 `STOP_UNCONFIRMED` 并停止，不继续后续阶段。确认停止后才删除本次 QA 实例。
6. 本环境 `adb devices` 没有任何 device 行，因此没有 protected-only 真机资格，也没有 protected Microdroid 或 custom Linux 真机生命周期。设备报告为 `NO_DEVICE`、8 blocked / 4 not-tested。

### 10.2 样本计数

| 计数 | 值 | 说明 |
| --- | --- | --- |
| platform_qualification_samples | 0 | 无设备，资格阶段未执行 |
| protected_microdroid_samples | 0 | 无设备；也没有内置 payload，未创建 protected VM |
| custom_linux_samples | 0 | custom Linux 被设备能力门阻断；本轮实际首因是 `NO_DEVICE` |
| real_avf_samples | 0 | 只统计真实 create/run/stop 生命周期；资格检查不递增 |

`custom_linux` 的产品门仍是 `NO_NON_PROTECTED_VM`；当前设备（若存在）必须实测 bit2，不能从 protected bit 推导。

### 10.3 代码与测试

- `app/src/androidTest/.../AvfQaProbeInstrumentation.java`：新增模式解析、protected Microdroid 配置/生命周期、custom Linux 配置/生命周期、vsock 超时、stop deadline、QA 私有 artifact 路径校验、实例清理与 typed reason code。
- `tools/device-validation/avf_device_mode.py`：两阶段探针（先 qualification 再按模式 launch）、模式门控、payload/stop/vsock/artifact 参数、四个独立样本计数、blocked 原因保留。
- `tools/device-validation/run_avf_device_validation.py`：`--qa-mode`、`--microdroid-payload`、`--vsock-port`、`--stop-deadline-ms`、custom artifact 参数；报告 schema 增加 `qa_mode`、`protected_only` 与四个计数，`--validate` 同步校验；`--dry-run` 仍为 12 个 `not-tested` 且全部计数为 0。
- `tools/device-validation/test_avf_device_mode.py`：28 项 fake adb 测试，覆盖 protected-only、bit2 缺失、权限拒绝、hidden API blocked、`setProtectedVm(false)` 拒绝、protected Microdroid 成功/失败、payload 缺失、custom Linux 阻断、stop UNCONFIRMED、脱敏与 schema。
- `tools/device-validation` 全量：167 tests / 0 failures / 0 errors / 0 skips（本轮实测）。
- QA channel 构建：`./gradlew :app:assembleDebugAndroidTest -Porchestella.buildChannel=qa -Porchestella.buildPurpose=devicemode --offline --no-daemon` 为 BUILD SUCCESSFUL；测试 APK 包名 `io.github.nyzeep.orchestella.qa.devicemode.test`，instrumentation targetPackage `io.github.nyzeep.orchestella.qa.devicemode`。

### 10.4 未测试项

- protected-only 真机的 API/ABI/feature/bit1/bit2/两权限/HiddenApiBypass 实测；
- 真实 `setProtectedVm(false)` 异常时序（仅 fake probe 覆盖）；
- protected Microdroid 的真实 create/run/vsock/stop（无设备、无内置 payload）；
- custom Linux 的真实 create/run/vsock/stop（无 non-protected 设备）；
- 20 次启停、网络、9p、RFB、性能与 App death。

### 10.5 边界

- 是否触碰用户数据：否。没有安装生产包、grant 生产包、读取/修改用户实例、根盘、凭据或屏幕内容。
- 是否允许声明 AVF 已支持：否。真实 AVF 样本为 0；protected Microdroid 即使未来通过也不是 Orchestella custom Linux 的产品 AVF 成功。
- 是否需要 non-protected 设备进行下一轮：是。custom Linux 的产品门只能由 bit2 真机闭环证明。

## 11. Session G：protected-only 设备门与 custom 工件 staging（2026-10-20）

- Base：`ticket/avf-device-mode @ 8f85a429`
- Branch：`lane/nonprotected-validation`
- 代码提交：`8018887`
- 设备：只连接到一台设备，公开 alias `qa-device-01`；报告不记录 serial。
- 结论：设备是 **protected-only**，`getCapabilities` raw bits=1，non-protected bit2 缺失；按停止条件记录 `NO_NON_PROTECTED_VM`，没有创建 custom VM，也没有用 protected Microdroid 替代。

### 11.1 设备资格（真实执行）

用 QA channel 构建并安装 `io.github.nyzeep.orchestella.qa.devicemode` 与 instrumentation 包，显式 `--grant` 后重读探针。结果是 4 个资格阶段 pass：

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| adb devices 恰好一台目标设备 | pass | 仅一台 `device`，serial 只用于比较 |
| API >= 33 | pass | API 36 |
| ABI 含 arm64-v8a | pass | `arm64-v8a` |
| `android.software.virtualization_framework` | pass | feature=true |
| App `getCapabilities()` | protected=true；non-protected=false | raw bits=1 |
| 两权限 + 显式 grant | pass | manage=GRANTED，custom=GRANTED；grant 后 force-stop 并重读 |
| hidden API exemption | INSTALLED | `AvfPlatformFacts` |
| `setProtectedVm(false)` | REJECTED | `UnsupportedOperationException` |
| App readiness | MODE_UNSUPPORTED | protected-only，产品路径仍是 QEMU |
| protected-only? | **yes** | bit2 缺失 |

设备报告（脱敏）写到外部 `--out`：

```text
<APPROVED_AVF_OUTPUT_ROOT>/avf-session-g-20261020/qualification/（外部输出根，未入库）
```

报告内容：`platform_declaration` / `capabilities` / `permissions` / `protected_mode` 四个阶段 pass，其余 8 个 not-tested；`platform_qualification_samples=1`，`custom_linux_samples=0`，`protected_microdroid_samples=0`，`real_avf_samples=0`。首个阻断阶段若请求 custom-linux 则是 `capabilities` / `NO_NON_PROTECTED_VM`；本轮没有启动 custom 阶段。

### 11.2 custom 工件 staging（零设备实现）

- 新增 `tools/device-validation/avf_qa_staging.py`：从已验证 QA batch `plan.json` 读取 kernel/initramfs 的 size/SHA-256；可选 `--apk` 用 APK 的 `assets/runtime-artifacts-v1.sha256` 与 `assets/Image` 复核 kernel 信任锚。
- 设备侧只写 QA app 私有 `files/avf-qa/custom/{kernel,initrd,root.img}`；主机不推送 8 GiB 整盘，只推 4096 字节 provisioning marker，设备用 `toybox truncate` 新建 sparse raw，root 大小默认取 plan `rawBytes`。
- 传输暂存为 `/data/local/tmp/avf-qa-stage-<token>`；一个 `run-as` 脚本用 `set -C`（noclobber）复制三个文件，设备上复核 kernel/initrd SHA-256、root 大小和 `ORCHESTELLA_EMPTY_ROOT_VOLUME_V2` marker。
- 目标已存在时拒绝覆盖；失败时 tarball trap 只删除本次创建的三个文件，随后只清理本次临时目录；不删除 app 私有磁盘、其他文件或用户数据。
- 独立 CLI：`python3 -B tools/device-validation/avf_qa_staging.py --adb ... --device ... --qa-package ... --plan ... --distro debian --apk ... --out ...`。
- 设备模式新增 `--stage-custom` / `--custom-plan` / `--custom-distro` / `--custom-apk` / `--custom-root-bytes` / `--custom-memory-mb` / `--vsock-terminal-port` / `--vsock-timeout-ms`；staging 成功 receipt 单独写入 `avf-qa-staging-<stamp>.json`。
- `AdbRunner` 命令日志新增对 `qaBridgeToken`、staging 目录和 push 本地路径的脱敏。

### 11.3 custom 启动链修正（编译通过，真机未验）

Session F 的 custom 配置缺少真实启动所需的接线，本轮修正：

1. `VirtualMachineCustomImageConfig.Builder.addParam(...)` 写入生产 `AvfLaunchSpec` 同款 cmdline：`console=hvc0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input droidlayer.vsock_control_port=9100 droidlayer.vsock_terminal_port=9101`。没有这些参数，guest initramfs 走 virtio-serial 分支，不会监听 vsock。
2. 探针改用官方 `VirtualMachine.connectVsock(long)`（生产 shape 验证的是该方法；旧的 `connectToVsockServer(int)` 不在生产 shape 中），并有界重试。
3. custom 模式要求 QA 64-hex bridge token，发送 `DROIDLAYER/HELLO 1 1 <token>`，等待 `DROIDLAYER/READY 1`；随后连接 terminal vsock，只记录首个字节是否存在，不保存终端正文。token 只通过 `am instrument -e qaBridgeToken` 传入，命令日志为 `<token>`。
4. custom 内存默认 1024 MiB（512 MiB 低于 Debian embedded initramfs 的 provisioning 下限）；CLI 范围 512..4096。
5. `vsock_timeout_ms` 默认 120000，host 的 instrumentation timeout 随之上调，避免 180s 固定超时先杀掉探针。

### 11.4 零设备回归

在 WSL Ubuntu-24.04 / Python 3.12.3 执行：

```bash
AVF_VALIDATION_TEST_ROOT=/tmp/avf-session-g/all \
  PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover \
  -s tools/device-validation -p 'test_*.py'
```

结果：**182 tests / 0 failures / 0 errors**。其中新增 `test_avf_qa_staging.py` 14 项，覆盖：

- 工件缺失、kernel hash 不匹配、plan 路径逃逸、symlink/junction；
- 目标文件已存在且不覆盖；
- `run-as` 不可用、设备侧 hash 不匹配、root marker 失败；
- 精确清理边界（只删三个本次文件 + 临时目录，保留其他 app 文件）；
- root size 覆盖、APK trust anchor、token 缺失、root seed marker。

另外 `test_avf_device_mode.py` 新增 1 项，断言 custom 探针默认传 9100/9101、1024 MiB，并把 `qaBridgeToken` 脱敏为 `<token>`；该文件共 29 项通过。QA channel 的 `:app:assembleDebugAndroidTest` / instrumentation Java 编译通过。

这些是 host/fake runner 证据，**不是真机 custom 样本**。

### 11.5 计数

| 计数 | 值 | 说明 |
| --- | --- | --- |
| platform_qualification_samples | 1 | 设备声明与 capabilities 两阶段真实 pass |
| custom_linux_samples | 0 | 设备 protected-only；custom 在创建前被 `NO_NON_PROTECTED_VM` 阻断 |
| protected_microdroid_samples | 0 | 未使用 protected Microdroid 替代 |
| real_avf_samples | 0 | 没有真实 create/run/stop 生命周期 |

### 11.6 未测试项

- 真实 non-protected 设备的 custom create/run/vsock/stop；
- 真实 `DROIDLAYER/HELLO` 与 terminal 首字节；
- 20 次启停、网络（无网络/静态/DHCP/Android 17 GpuConfig）、只读 9p、RFB 显示/输入、App death；
- 同资源 QEMU 对照与性能；
- custom 内存 1024 MiB 与 stub/真实 initramfs 的兼容性（仅结构性检查）。

### 11.7 边界

- 是否触碰用户数据：否。只安装/授权/运行 QA 包；只新建 QA 工件；没有读取或修改用户实例、根盘、导出、备份、凭据或屏幕内容；没有卸载/清数据/root/解锁/改系统策略。
- 是否允许声明 AVF 已支持：否。设备 protected-only，custom_linux_samples=0，real_avf_samples=0；staging 与 host 测试只表示代码就绪。
- 下一必需步骤：换一台实测 `getCapabilities() & 2 != 0` 的 non-protected 设备，重复 §11.1 资格，然后执行 §11.2 的 `--stage-custom` custom 最小闭环；任何失败停在该阶段。

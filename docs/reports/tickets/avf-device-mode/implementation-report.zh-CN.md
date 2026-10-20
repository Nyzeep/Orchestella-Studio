# AVF 设备模式与真机验收（Session E）

- 日期：2026-10-20
- Base：avf/zero-device-integration @ 311fcd48
- Branch：lane/device-validation（远端目标 ticket/avf-device-mode）
- 代码提交：630ecad7
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
- 探针把 JSON 写到 QA app 私有目录 files/avf-qa/probe-<token>.json；host 通过 adb exec-out run-as <qa> cat 拉取；报告只保存 token 的 SHA-256。
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

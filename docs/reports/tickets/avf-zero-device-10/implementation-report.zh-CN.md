# 10 实施报告：集成守卫、缺口收口与交接

- 日期：2026-10-20
- Agent：Agent D（工具 + 集成 owner）
- 基线：`ticket/01-ci-full-gate` @ `82009093`
- 票 09：`ticket/09-device-validation-harness` @ `d4c3f586`（已推送 GitHub）
- 票 10 代码提交：`3c57f281`（`feat(avf): 收口平台事实、RFB 输入与元数据保留`）
- 分支：`ticket/10-integration-and-report`（从 09 叠加）；集成分支 `avf/zero-device-integration` 从 10 head 创建
- 远端：`github` = `git@github.com:Nyzeep/Orchestella-Studio.git`
- Ancestry：`82009093` 已核实包含 02 `a59dcb25`、03 `1b51de47`、08 `78fc531c`、04 `a4e2e7a8`、06 `bd1ecd05`、07 `40bb2177`、05 `50c971b4`；09 从该基线分出，10 从 09 分出。没有重新 merge A/B/C。

## 1. 代码就绪项（fake / JVM 通过）

1. **OEM feature 缺失的声明回退**
   - `AvfPlatformFacts` 新增 `managerPresent`（6 参构造；5 参保留给旧调用方，默认 false）。
   - `ReflectiveAvfPlatformAccess` 在权限门之前探测 manager 服务；`HIDDEN_API_BLOCKED`/`PERMISSION_DENIED` 的 shape 失败也被视为 manager 存在的证据，缺失类/链接失败才是无平台。
   - `AvfCapabilityEvaluator` 把 `managerPresent` 计入“平台存在”：feature=false + 权限未授予但 manager 存在 → `NEEDS_AUTHORIZATION`；manager 不存在 → 仍 `UNSUPPORTED`（“设备未声明”）；权限未授予仍不等于 READY。
   - 测试：`AvfCapabilityEvaluatorTest.managerPresenceWithoutFeatureNeedsAuthorizationNotUndeclared`、`managerAbsenceWithoutFeatureStaysUndeclared`；`AvfPlatformAccessTest.permissionDeniedStillReportsManagerPresence`、`missingManagerServiceIsNotPresence`、hidden-API manager 断言。

2. **RFB 输入覆盖**
   - `AvfRfbOverVsockTest` 新增 `keyboardAndPointerTravelOverTheSameFakeVsockTransport`：`InputCapabilities(true, false, true)`，fake guest 读取 RFB 客户端消息，断言 key 消息类型/按下/`KEY_A -> 'a'` keysym，pointer 类型/无按键/x=10/y=20，`inputEvents=2`、`rejectedInputEvents=0`。
   - 这是 fake vsock 上的字节契约，不是 Android 输入面/真机结果。

3. **QEMU 外设能力边界**
   - 决定：**UI-driven，不在 session capability**。QEMU 音频、剪贴板、Android 指针捕获由 `DesktopActivity`/UI 驱动，`QemuInstanceLauncher.Session.capabilityMatrix()` 不声明它们；在出现 session-owned adapter 前不扩张产品承诺。
   - `QemuSessionCapabilityTest` 直接构造 QEMU session，断言 software 策略、terminal/bridge、无 RFB/Wayland/audio/clipboard/pointer/9p/GPU/hardware acceleration；`graphicsBadge()` 为“无图形通道”。
   - `docs/adr/README.md` 的“实现差异说明”记录该边界；未重写 ADR 正文。

4. **03 未知元数据键保留**
   - `InstanceLifecycle` 增加 `KNOWN_METADATA_KEYS` 与 `unknownMetadata` 字段；`fromProperties` 捕获未知键，`toProperties` 原样写回，未知键不参与硬件解析；所有 copy/transition 路径保留该 map。
   - 新增 golden fixture `runtime/instance/src/test/resources/instance-metadata-v1-golden.properties`，覆盖未知顶层键、未知 `hardware.*` 键、legacyDefaults  provenance；`InstanceMetadataMigrationTest.goldenMetadataRoundTripPreservesUnknownKeysAndProvenance` 先 red（`future.retentionHint` 读回 null）后 green。

5. **ADR-0017 / WaylandChannel 历史命名**
   - `docs/adr/0017-wayland-graphics-channel.md` 顶部加“实现状态（非正文修订）”：生产是 RFB，`WaylandChannel` 是历史接口名，`GRAPHICS_WAYLAND` 只在真实 Wayland 适配器接线后声明。
   - `docs/adr/README.md` 增加实现差异说明（0017/0018/0019/0021），不改 accepted 正文。

6. **16KB DT_NEEDED 闭包**
   - `check-elf-16kb.sh` 新增可选 `REQUIRE_BUNDLED_CLOSURE=1`：任何与已检查 ELF 同目录、且出现在 DT_NEEDED 的库必须已在本 gate 的输入集合中；`check-apk-16kb.sh` 启用它。
   - 本地 APK 结果：`BUNDLED_NEEDED_CLOSURE_OK resolved=0 external_not_asserted=13`。这断言的是 **APK bundled 闭包**；13 个 external（系统）库不在 APK 内，未断言其存在。QEMU 产品 ELF 未在本环境构建，其闭包仍是 release 风险，不写成“闭包已验证”。

7. **默认 2 vCPU**
   - 未改产品默认值；`SetupWizardActivity` 在 AVF READY 且 `AvfLaunchAdmission.cpuSupport(...) == NEEDS_SINGLE_VCPU` 时显示 `wizard_config_vcpu_avf_notice`（“AVF 硬件虚拟化当前仅支持 1 vCPU；此配置将回退到软件模拟……”）。
   - `IntegrationGuardsTest.avfSingleVcpuConstraintIsVisibleBeforeLaunch` 锁住该 UI 约束。
   - **待用户决策**：是否把 AVF 默认设为 1 vCPU；本票不自行拍板。

8. **架构守卫**
   - `IntegrationGuardsTest`：AVF engine 无 `runtime.engineqemu` import；已删除的 Wayland/audio/forward 通道不再出现；1 vCPU 提示可见；CI 运行 09 工具与 dry-run。
   - 既有 `AvfDisplayWiringTest`、`AvfCapabilityTruthTest`、`CapabilitySourceOfTruthTest`、`AvfRuntimeOwnershipTest`、`AvfNetworkAssemblyContractTest` 继续覆盖 QEMU/AVF 矩阵、close/stop、DELETED、useNetwork。
   - `check_ci_module_coverage.py` 继续检查所有 `runtime:*` 模块、JavaExec harness 接线、workflow 不引用已删除任务。

## 2. 全量零设备门（本机实际执行）

```bash
JAVA_HOME=/opt/jdk17 bash tools/ci/run-gradle-gate.sh \
  :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :runtime:engine-avf:check :runtime:capability:check :runtime:distro:check \
  :runtime:network:check :app:testDebugUnitTest
```

结果：`BUILD SUCCESSFUL in 55s`；`GRADLE_GATE_EXIT=0`；`FAILED_TASKS: (none reported)`；app **875 tests / 115 suites / 0 failures / 0 errors / 0 skipped**。

```bash
JAVA_HOME=/opt/jdk17 ./gradlew :app:lintDebug :app:assembleDebug --no-daemon --stacktrace
```

结果：`BUILD SUCCESSFUL`；lint **0 errors / 779 warnings**；`app-debug.apk` 7,576,335 bytes。

```bash
bash tools/ci/check-apk-16kb.sh app/build/outputs/apk/debug/app-debug.apk
```

结果：`BUNDLED_NEEDED_CLOSURE_OK resolved=0 external_not_asserted=13`、`ELF_CLOSURE_16KB_OK`、`APK_16KB_GATE_OK libs=2`。

```bash
python3 tools/ci/test_check_ci_module_coverage.py
python3 tools/ci/check_ci_module_coverage.py
```

结果：fixture tests **6/6 OK**；coverage guard **44 passed / 0 failed**。

```bash
AVF_VALIDATION_TEST_ROOT=/tmp/avf-validation-integration \
  python3 -B -m unittest discover -s tools/device-validation -p 'test_*.py'
python3 -B tools/device-validation/run_avf_device_validation.py \
  --dry-run --out /tmp/avf-dry-run-integration
```

结果：**139 tests / 0 failures**；dry-run `summary={"pass":0,"fail":0,"blocked":0,"not-tested":12}`、`verdict=NOT_TESTED`、退出 0。

**守卫红证据**（临时改、跑、撤回）：

- 在 `AvfVirtualMachineEngine.java` 加一行注释形式的 `import ...runtime.engineqemu...` → `IntegrationGuardsTest > avfEngineHasNoQemuEngineDependency FAILED`；撤回后 green。
- 在 `QemuInstanceLauncher.capabilityMatrix()` 临时加 `.audioOutput(true)` → `QemuSessionCapabilityTest > qemuSessionOverridesTheBaselineAndDoesNotClaimUiPeripherals FAILED`；撤回后 green。

远端 CI：以上均为本机等价执行；GitHub Actions **未触发**，不写成“CI 远端绿”。

## 3. 真实 AVF 状态

**真实 AVF 样本仍为 0。** 本报告只声明代码就绪；没有连接设备、没有安装 APK、没有 `pm grant`、没有启动 AVF、没有真机截图/日志。

## 4. not-tested 清单（真机，完整）

- 真机 feature/ABI/SDK 与 `ro.boot.hypervisor.*` 声明；
- App actor 与 shell/UserService actor 的 `getCapabilities` bits；
- 两项权限在目标 ROM 的实际授予/冷启动复核；
- `setProtectedVm(false)` 在非 protected 设备的真实行为；
- App 私有 kernel/initrd/root raw 创建；
- `run` 返回与 guest ready 的区别；
- vsock 9100/9101 Bridge/终端握手；
- 正常 stop、20 次启停收敛、孤儿/失败样本；
- 网络：无网络、静态、DHCP、Android 17 GpuConfig workaround、guest `eth0`/DNS/apt；
- 只读 9p 共享与 RFB 显示/输入的真实连通；
- 音频/剪贴板（AVF 声明 UNSUPPORTED；QEMU UI 路径未做真机验收）；
- App 进程死亡/强制停止后的 VM 收敛；
- 同资源 QEMU 对照与性能（n≥10、控温、median/range）；
- HiddenApiBypass 6.1 在目标 ROM 的真实行为；
- protected-only 设备的真实异常时序与 ROM 观察值。

## 5. 设备资格清单与推荐候选

按 research §3.3 执行 09 工具的 12 个阶段；选机必须实测：

1. `getprop ro.boot.hypervisor.vm.supported` / `protected_vm.supported`；
2. `pm list features | grep virtualization_framework`；
3. App 与 shell/UserService 分别 `getCapabilities()`，记录 bits + actor；
4. 两项权限授予后冷启动复核；
5. `setProtectedVm(false)` 不抛 `UnsupportedOperationException`；
6. App 私有 kernel/initrd/root raw create；
7. run 后 vsock 9100/9101 握手；
8. 正常 stop + 20 次启停；
9. 无网络/静态/DHCP + Android 17 GpuConfig workaround；
10. 9p/display 实际连通；
11. App 死亡/强停后的 VM 收敛；
12. 同资源 QEMU 对照。

推荐候选（只用于选机，不替代本应用验收）：Pixel 6 Pro、Pixel Tablet、Pixel 10/11；避免 Pixel 8a/8 Pro（已知 boot/panic）与 Redmi K Pad 2（hang/reboot 风险）；MT6991/GenieZone 需自验 `/dev/gzvm`、ROM 策略与 bit2。

## 6. 已知风险

- **HiddenApiBypass 6.1 脆弱**：Android 16 ART 变更曾破坏旧版；真实 ROM 可能再次失效。失败时评估 shell-owner（ACR-01），两者都不行则停 AVF。
- **Android 17 `crosvm_minimal`**：带 `useNetwork(true)` 的无 GPU VM 可能 status 35；已实现 headless 2D GPU workaround + 可识别/可降级，但真实 ROM 未验。
- **AVF v1 = 1 vCPU**：默认 2 vCPU 会回退 QEMU；是否改默认待用户决策。
- **RFB-only**：无 Wayland/音频/剪贴板/端口转发；产品承诺不能超出矩阵。
- **protected 不可行**：未签名 custom kernel 不能走 protected；不要用 protected 绕过设备门。
- **16KB 闭包**：APK bundled 闭包已断言；QEMU 产品 ELF 未构建，闭包未验证。
- **远端 CI 未跑**：本机等价通过，不等于 GitHub Actions 绿。
- **真机数据**：本报告不含 serial/IMEI/MAC/账号/token/屏幕内容；设备证据只允许稳定别名。

## 7. 下一 effort 入口

1. 用户提供一台声明 non-protected 的设备（优先 Pixel 6 Pro / Pixel Tablet / Pixel 10/11），按 research §3.2 选机；
2. 用 09 工具：
   ```bash
   python3 -B tools/device-validation/run_avf_device_validation.py \
     --device "$QA_SERIAL" --device-alias qa-device-01 \
     --out "$APPROVED_AVF_OUTPUT_ROOT"
   ```
   本票的设备模式仍是 `blocked`/`DEVICE_MODE_NOT_IMPLEMENTED`；下一 effort 先实现设备执行，再用同一 schema 归档。
3. 设备门通过后再讨论：AVF 是否默认、是否发布带 AVF 的版本、是否把 1 vCPU/RFB/无音频写进产品契约。

## 8. 结论

10 在零设备范围内完成：A/B/C 的行为在集成分支上通过全量 gate，架构守卫可在故意回归时红，已知缺口按“修复/文档/风险”分别收口，票 23 报告与双轴评审完成。**没有任何 AVF 真机成功声明；下一 effort 从一台 non-protected 设备 + 09 工具开始。**

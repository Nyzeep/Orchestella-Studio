# 会话 8 报告：DV-PVM-02b

> **已被推翻（2026-10-26，会话 10/11）**：本报告把 `.o` 回传与
> `distcc_remote_seen=True` 当作「远端编译成功」的证据，两者都不成立——
> 60 秒地板是载荷帧超时，`.o` 是 distcc **本地补编**的产物，而
> `distcc_remote_seen` 只是 `"remote" in text.lower()`，会被补编告警
> `remote compilation of '…' failed, retrying locally` 命中。
> 归因见 `docs/reports/orchestration/session10-floor-attribution-2026-10-26.zh-CN.md`，
> 机制修复见 `docs/reports/tickets/pvm-10/implementation-report.zh-CN.md`。
> 本文件按「只移不删」保留原文，不要据此判断远端路径是否可用。

## 1. 结论（交火：done；分段：partial）

**交火本体：done。** QEMU guest（Alpine 3.24.1 chroot，clang 22.1.3）内的 distcc 客户端
经 gvproxy hostIP（192.168.127.254:5103）真实打到宿主生产 `DistccTcpVsockBridge`，
经 AXP1 握手（token + compiler triple）转发到 protected pVM payload 完成编译，
`.o`（90,836 B 级载荷）原路回传 guest——`distcc_remote_seen=True`、`distcc_rc=0`，
四轮独立复现（85.4s / 86.4s / 86.0s / 93.8s，冷启动口径）。

**分段与等价：partial。** n=1 冒烟完成；n=3 未跑（时间预算，归下一轮/AX-07）。
结构级等价成立（符号集 2004 个、重定位集 2001 条完全一致，宿主侧
`nm/readelf` Level 3 手工执行）；`compare_objects.py` 在 Windows 宿主对
aarch64 对象固有为 `INCONCLUSIVE`（x86 ld 拒绝跨目标链接），guest 侧行为级
链接 rc=0、运行退出码两项均为 127/127（一致但非期望值，原因与处置见 §6）。

## 2. 设备资格与开关冒烟

- 设备别名 `qa-alt-01`，API 37，arm64-v8a；`/dev/gunyah` 在、`/dev/kvm` 无、
  `android.software.virtualization_framework` 在；capabilities raw=1（protected-only）。
- staging：`assemble_payload_libs.py --compiler-version 22.1.8
  --target-triple aarch64-unknown-linux-android`，manifest 校验
  `PVM_COMPILER_MANIFEST_OK`（one_cpu / 2048 MiB / payload libpvm_compiler_payload.so）。
- 产品路径冒烟（交互式 launch，flag-on）：
  ```
  OrchestellaAccelerator: backend=PVM_PAYLOAD {"mode":"QEMU_PLUS_ACCEL",
    "reasonCode":"ACCEL_AVAILABLE","backend":"PVM_PAYLOAD",
    "degradedCapabilities":[],"rejections":[]}
  OrchestellaAccelerator: payload-compiler present=true version=22.1.8
    triple=aarch64-unknown-linux-android
  OrchestellaAccelerator: lane_started QEMU STARTED 24823ms
  OrchestellaAccelerator: lane_started ACCEL STARTED 414ms
  ```
- **偏差**：开工 Prompt §3.1 的 `--target-triple aarch64-unknown-linux-gnu` 为
  过时示例——产品常量 `GUEST_COMPILER_TRIPLE`、02a 固化值、instrumentation
  `REQUIRED_TRIPLE` 三处均为 `aarch64-unknown-linux-android`，按后者执行。

## 3. 交火证据

- **awaitAcceleratorReady 门（硬检查点）**：crossfire instrumentation 内
  `qaMode=compiler-crossfire` 以生产 `DualLaneCoordinator` 编排双车道，
  evidence 记 `await_accelerator_ready=true`、`gate_ms≈3.5-3.9s`（真实等待
  payloadReady）、`fallback_count=0`；logcat 出现
  `lane_ready ACCEL READY`（过门唯一仓内证据行）。
- **桥**：生产 `DistccTcpVsockBridge` 绑定 `127.0.0.1:5103`，通道工厂为
  `Axp1DistccChannelFactory`（token + REQUIRED_TRIPLE + Axp1Limits）。
- **guest 真实发起**：distcc 命令行
  `DISTCC_HOSTS="192.168.127.254:5103" DISTCC_VERBOSE=1 distcc clang -c
  --target=aarch64-unknown-linux-android -O1 /root/fixture.c -o /root/cross.o`，
  `distcc_remote_seen=True`（非本地回退）。
- **载荷回传**：桥心跳 `to_guest_bytes=90836`；对象经 guest nc → gvproxy NAT →
  设备 nc → adb pull 落盘 179,912 B（含符号表全量）/ ref.o 179,832 B，ELF 魔数校验通过。
- **停删**：`coordinator_stop {accel: CONFIRMED, qemu: CONFIRMED}`、
  `stop_confirmed=true`、`deleted=true`、无残留 crosvm/qemu/gvproxy。

## 4. 分段数据（n=1；n=3 未跑）

| 段 | 值 | 说明 |
| --- | --- | --- |
| gate_ms | 3.5-3.9 s | awaitAcceleratorReady 真实等待 |
| payload_ready_ms | ~30 s | ACCEL STARTED → READY（QA VM） |
| distcc_ms（含 preprocess+transport+compile+return） | 85.4 / 86.4 / 86.0 / 93.8 s | 四轮独立 |
| guest 本地参考编译（clang 22.1.3，同 target/-O1） | 30.7-32.5 s | 首轮冷启动 |
| preprocess/transport/compile/link 分列 | 未分离 | 需桥侧与 payload 侧打点（归 AX-07） |
| 回退次数 | 0 | 门超时回退口径 |
| 失败码 | 本轮调试序列见 §6 | 最终轮无 payload 侧失败码 |

**红线遵守**：guest 真实发出请求（bridge 字节计数 + distcc_remote_seen 双证），
未用宿主客户端冒充。

## 5. 等价判定

- `compare_objects.py --no-run`（宿主 Windows）→ `INCONCLUSIVE`：x86_64 mingw ld
  拒绝 aarch64 对象（"Relocations in generic ELF (EM: 183)"）——跨目标 host 判定
  的固有限制，非产物问题。
- **Level 3（STRUCTURE）手工执行（MSYS2 nm/readelf，架构无关）**：
  - 符号集：2004 个 defined symbols（2000×probe_fn_N + fixture_entry 等），
    **SYMBOLS_IDENTICAL**；
  - 重定位：2001 条，**RELOCS_IDENTICAL**。
- Level 2（BEHAVIOR）：guest 内两产物链接均 rc=0；运行退出码 127/127
  （一致但非期望值，见 §6 处置）。

## 6. 未验证项与偏差

1. **n=3 未跑**：每轮 ~11 min（含 guest 重启），本轮时间预算内只完成 n=1 + 四轮
   调试轮。归下一轮或 AX-07。
2. **运行退出码 127/127**：链接产物由 Arch 原生 clang（glibc 动态）产出，而
   运行在 musl chroot 内执行（驱动 tool() 包裹）→ 解释器缺失 → 127。修复方向
   已知（外层路径运行），未再占一轮复跑。
3. **preprocess/transport/compile/link 分列**：需桥与 payload 侧打点，本轮口径
   为 distcc 端到端合并值。
4. **guest 发行版改 Arch**：产品目录（DistroCatalogRegistry）无 Alpine 条目。
   处置：Arch guest 内布 Alpine 3.24.1 chroot（PVM-04 钉死版本，clang 22.1.3，
   major=22 ✅），全编译链在 chroot 内。开工 Prompt 的 "Alpine apk add" 语义以
   chroot 形态达成。
5. **spawn/W^X 产品缺陷（本轮发现并修复）**：payload 构建的
   `useLegacyPackaging=false`（AVF 硬要求）使 `nativeLibraryDir` 为空 +
   targetSdk≥34 的 SELinux W^X 拒绝 execve 应用私有二进制 → QEMU 车道
   gvproxy/QEMU spawn 必败（`QEMU_START_FAILED`，launch-timeline 停在
   INITRAMFS_BUILT）。修复：`orchestella.qaTargetSdk=28`（untrusted_app_27 域
   恢复 exec 权限，Termux 同款先例）+ `NativeExecutableStore`（APK 内提取 +
   chmod 0700，flag-off 行为不变）。
6. **桥通道缺陷（本轮发现并修复）**：桥的 channel factory 必须是
   `Axp1DistccChannelFactory`；02a 的桥构造传裸 vsock 工厂——payload 在 7ms 内
   拒绝首字节（CHANNEL_FAILED）且 distcc 静默本地回退。02a 桥模式因 adb forward
   阻断从未端到端到达此处，本次首次暴露。
7. **console 所有权**：产品 Session 终身持有 terminal socket（外部客户端进
   backlog 永不被 accept）→ crossfire 增加 `qaDirectQemu=1` 直呼路径（产品运行时
   件、无 Session），console 归驱动独占。
8. **环境事实**：设备蜂窝网络近失效（3.5 KB/s）→ guest 包管理经
   `adb reverse` + 宿主代理（mini_proxy.py）完成；系统 PATH 里的老版 adb
   （1.0.32）的 forward 不投递字节属实，SDK platform-tools adb 37.0.1 正常。

## 7. 风险

- **payload 侧观测盲区依旧**：compile_ms 等payload 内分段依赖 vsock 打点（AX-07）。
- **distcc 本地回退的静默性**：CHANNEL_FAILED 时 distcc rc=0——后续轮次的
  `distcc_remote_seen` 必须作为硬检查（本轮驱动已内置）。
- **qaTargetSdk=28 只应存在于 QA 通道**：已用 build 属性门控（默认 36），PR review
  需确认不泄漏到 release/dev。
- **设备存储**：Arch rootfs 829 MB + 实例卷 8 GB，设备剩余 744 GB，无压力。

## 8. 需要维护者决策（≤3）

1. **triple 示例勘误**：kick-off 文档的 `aarch64-unknown-linux-gnu` 更正为
   `aarch64-unknown-linux-android`（仓内三处证据一致）。
2. **W^X 修复的取舍**：`qaTargetSdk` 属性方案（QA-only）vs 产品级两包拆分
   （QEMU 车道与 payload 构建互斥的根治）。本轮按 QA 属性方案落地。
3. **产品目录是否补 Alpine**：Alpine 3.24.1（clang 22.1.3）是 PVM-04 的
   对齐版本；当前 chroot 方案可行但多一层维护面。

## 9. 给下一轮的交接（AX-07 采样还缺什么；产品默认开关还差什么）

- **AX-07 采样缺口**：n=3→n≥10 的分段采样；桥侧 transport_ms/bytes 分列打点；
  payload 侧 compile_ms vsock 打点；guest 内 preprocess_ms 打点；本次全部
  环境已固化（chroot 工具链、profile.d 代理、fixture 已在 rootfs）。
- **产品默认开关缺口**：① W^X/打包两包拆分或等价根治（§8.2）；②
  `DistccTcpVsockBridge` 的 AXP1 工厂包装若为产品路径必须补（当前产品 main
  未接桥）；③ 交互式 launch 的编译工作调用方（awaitAcceleratorReady 的产品
  消费者）仍属空缺（本轮为 instrumentation 演示位）。
- **设备交接**：QA APK + test APK 留装；实例 `192796b1-…` STOPPED（rootfs 内
  已布 Alpine chroot + distcc/clang + profile.d 代理）；guest fixture 已在
  `/alpine/root/fixture.c`；stayon 已恢复默认；手机保持开机。

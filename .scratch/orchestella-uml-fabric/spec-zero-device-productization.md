# Spec · 零设备产品化（T1–T7）

- 基线：main `3c24c54`（origin = github.com/Nyzeep/Orchestella-Studio，分支 `main` 干净）
- 输入：`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md`（§1 根因表 / §8 缺口清单）、`docs/research/2026-11-08-t7-network-closure.md`、`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md`、`research/uml-runner/`、`research/uml-fabric-probe/t7/`
- 侦查笔记：`.scratch/orchestella-uml-fabric/notes/A0..A5-*.md`
- 纪律：全程零设备；任何需真机的验证项标 `not-tested` 并给下个窗口的执行入口。不裁定开发通道产品形态、不改版本号/签名/发布通道、不改 ADR 正文、不补造 ADR-0015。

## 0. 任务陈述

把 11-08 双设备实证暴露的 provision 与启动器缺口，以零设备可验证的方式落成产品代码：**锁恢复（T1）、超时分档（T2）、停止信号组件（T3）、前台服务硬化（T4）、staging 权限守卫（T5）**，并对 `ncpus=` 与 16KB stub 的产品通道联动（T6）与设备窗口 preflight（T7）给出明确结论。

## 1. 与 prompt 的三处偏差（已核实，以此为准）

| prompt 表述 | 仓库事实 | 处置 |
|---|---|---|
| T3「产品启动器移植 stop-file watcher」隐含产品已有 stop-file 面 | 产品零 stop-file/`uml-stop` 引用；停止是「控制套接字 + 有界执行器」模型，8 条停止路径全部收敛到 `RuntimeProcessSupervisor.stop()`（`RuntimeProcessSupervisor.java:120-137`） | 用户裁定：**抽 `runtime:instance` 独立组件 + 契约测试，不接线**（方案 B） |
| T4「manifest 四权限 + specialUse」作为待做项 | 已全部具备：`AndroidManifest.xml:112-119`（type + subtype）、`:6,12,13,14`（四权限）、`OrchestellaForegroundService.java:191`（IMPORTANCE_LOW） | 只补**真实缺口**：API34+ 显式 type、wake lock 2h 上限+续期+finally、`POST_NOTIFICATIONS` 运行时申请、开机自启空转保活 |
| Phase 4 门 `assembleSdk36Debug assembleSdk28Debug` | 产品 `:app` **无 productFlavors**（只有 debug/release）；`sdk36/sdk28` 是 `research/uml-runner` 的 flavor | 产品门用 `:app:assembleDebug`；研究门用 `research/uml-runner` 的双 flavor，两者都跑 |

## 2. 功能规格

### T1 · pacman 锁恢复（ZP-07）

- 触发：pacman 分支首次取 alpm 写锁之前（现状唯一点 `orchestella-guest-provision.sh:509`）。
- 判定：`$ROOT/var/lib/pacman/db.lck` 存在时，判定「是否仍有进程持有」：
  1. `/proc/*/fd` readlink 命中锁路径 → **持有**；
  2. 否则 `fuser` 可用则用 `fuser` → 命中即持有；
  3. 两条证据都不可得 → `UNKNOWN`，**禁删**，报 `package-lock-held`（宁可重试也不误删）；
  4. 无持有者时 3s 后二次确认，仍无 → 删除并记 `LOCK_RECOVERY action=removed`。
- 语义修正：锁遗留**不得**再落到 `offline-or-package-install-failed`；新 reason 为 `package-lock-held`（真持有）与 `package-lock-recovery-failed`（删不掉）。
- 不变式：不改变 `-Syu` 事务形态（`test-distro-runtime-matrix.py:224-226` 断言 `-Syu` 且禁 `-Sy`）。

### T2 · 超时分档（ZP-08）

- 四类档位（默认，全部可被 `ORCHESTELLA_PROVISION_TIMEOUT_<CLASS>` 覆盖）：
  - `download`（repository-refresh / certificate-bootstrap-refresh / certificate-bootstrap-install）→ **900**
  - `transaction`（package-install / package-recovery / init-repair / optional-upgrade）→ **3600**
  - `compute`（locale）→ **1800**
  - `verify`（其余全部 stage）→ **600**
- state 增加 `operation_timeout_class`（追加字段，不改 `version=2`，k=v 解析向后兼容）。
- **跨层一致性**：宿主 `BridgeClient.DEFAULT_STARTUP_TIMEOUT_MS` 从 3,900,000（65min）提到 **5,400,000（90min）**；guest systemd `TimeoutStartSec` 与 OpenRC 侧同步到能覆盖「download + transaction」两段之和。新增单测解析 guest 脚本的档位默认值 + `TimeoutStartSec`，断言 `宿主超时 > 两段之和 + 余量`，**任一侧漂移即红**。
- 不得写死 TCG 前提：档位按「操作类型」命名与归类，注释只描述 I/O 与解包规模，不出现加速器/模拟器前提。

### T3 · 停止信号组件（ZP-09）

- 新增 `runtime/instance/.../InstanceStopSignal.java`：`prepareRound()`（启动期 sweep）、`watch(BooleanSupplier alive, Runnable onConsume)`（500ms 轮询、一次性消费、删除 marker）、`completeRound()`（post-exit sweep）。纯 `java.nio.file`，无 Android 依赖。
- 契约测试镜像 `research/uml-runner/tools/stop_watcher_sim.sh` 的 4 场景 6 断言（含 S2 的「不修则遗留 / 修则清理」双向断言）。
- 注册到 `:runtime:instance:check`，否则 CI 不跑。
- **明确不接线**：`app` / `QemuInstanceLauncher` / `RuntimeProcessSupervisor` 零改动。

### T4 · 前台服务硬化（ZP-10）

- `startForeground` 在 API 34+ 走三参显式 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`。
- `PARTIAL_WAKE_LOCK`：单次 acquire 上限 2h；已持有则按剩余时间续期；`startForeground` 失败时 `finally` 释放，不留悬挂锁。
- 开机自启路径不再无条件获取 wake lock（无实例时不持锁）。
- `POST_NOTIFICATIONS` 运行时申请（API 33+，已授予则跳过），挂在既有启动前抬前台的位置。
- 全部为 `not-tested`（无设备），单元测试只覆盖可测的策略逻辑。

### T5 · staging 权限守卫（ZP-11）

- `common.sh` 新增 `staging_preserves_modes()`：行为探针（`mkdir` + `chmod 0700` + `stat -c %a` 读回 `700`）。
- `build.sh`：`safe_reset_directory` 之后 fail-fast（退出码 70 + 可操作提示）；打包前对 `INITRAMFS_ROOT` 做目录 mode 归一 + `-perm /022` 断言。
- 新增 `scripts/check-staging-fs.sh`（供仓外 rootfs 镜像配方复用）与 `scripts/test-staging-guard.sh`（stub 化正/负/不可判定三例），注册进 `test-local.sh`。
- `research/uml-fabric-probe/README.md` 补「staging 必须在 WSL 原生 ext4，不得在 `/mnt/*`」。

### T6 · ncpus / 16KB stub 产品通道（ZP-12）

- 结论型工单：给出「产品通道有没有 `ncpus=` 可核对」「16KB stub 与产品装配的联动点是否存在」的定案与依据，不改代码。

### T7 · 设备窗口 preflight（ZP-13）

- `tools/device-validation/preflight_device_window.sh`：把 11-08 轮方法论坑固化为开场自检（推送后 md5 对账、`force-stop→sleep 3→am start -W` 竞速防护、T7-F×T8② doze 互斥排程提示、凉机阈值、中停必须长驻负载），带 `--self-test`（纯 host、零设备）。

## 3. 验收口径（实施前冻结）

1. T1：陈旧锁被清理且不再报 offline；真持有锁不删；`-Syu` 形态不变。
2. T2：四类档位可覆盖；跨层一致性测试在任一侧漂移时失败。
3. T3：4 场景 6 断言全绿；`:runtime:instance:check` 自动执行。
4. T4：策略逻辑单测全绿；manifest 不变（已合规）。
5. T5：守卫在「mode 会丢失」时 fail-fast、正常 FS 不误报；CI 产物字节不变。
6. 全量：`:app:assembleDebug` 与 `research/uml-runner` 的 `assembleSdk36Debug assembleSdk28Debug` 均 BUILD SUCCESSFUL；新增单测在 ASCII 路径跑绿；敏感数据门三扫通过。

## 4. 排除项

- 开发通道产品形态裁定（UML/QEMU vs AVF）——等 ACR 投票。
- 版本号、签名、发布通道、`docs/adr/` 正文。
- 任何真机/模拟器操作。
- T3 的生产接线（方案 C）、`BridgeClient` 的进度感知超时（另立票）。

# runtime-performance 交付交接（2026-10-18）

## 0. 这份分支是什么

本分支把 `runtime-performance` 在 T1/T2/T4 之后的剩余工作（T3、T7、T8，以及 T5/T6 条件票决策）集中交付，供人工比对、真机实测后合并。基线是 `main` 的 `893fb72`；集成分支 `perf/runtime-performance` 的最终提交是 `122a4cf`。本分支不包含对 `main` 的直接修改。

- 交付分支：`review/runtime-performance-20261018`
- 集成分支：`perf/runtime-performance`
- 集成分支 HEAD：`122a4cf`
- `main`：`893fb72`（未改动）
- 未 push `main`、未合并 `main`、未 force-push

## 1. 工单结论

| 票 | 结论 | 说明 |
|---|---|---|
| T3 重复启动只嵌入凭据 | completed | 已装配实例不再读取 rootfs；全量路径保留且逐字节等价 |
| T7 QEMU 单变量调优 | completed（设备 not-tested） | `QemuTuning` 默认值不变；矩阵脚本 dry-run + not-tested 报告 |
| T8 启动性能回归门 | completed | 纯 JVM 回归测试 + CI PR 门；红/绿证据已记录 |
| T5 只读输入盘（宿主） | deferred | 无设备数据，无法证明首次启动/initrd 内存是主要成本 |
| T6 只读输入盘（guest） | deferred | 阻塞于 T5，未启动 |

## 2. 提交清单

### T3

- `f396de3` perf(runtime): 已装配实例重复启动只嵌入凭据
- `bd3178c` test(runtime): 输出 T3 initrd 大小证据
- `5c6f91e` docs(runtime): 记录 T3 凭据-only 启动实施与测试证据
- 合并：`7e081a8` merge(runtime): 合入 T3 已装配实例凭据-only 启动

### T7

- `9755242` perf(qemu): 增加单变量调优值对象与矩阵脚本
- `27dd0f3` docs(runtime): 记录 T7 QEMU 调优矩阵与 not-tested 结论
- 合并：`0cbbffe` merge(runtime): 合入 T7 QEMU 单变量调优实验

### T8

- `32784cc` test(runtime): 增加已装配启动零 rootfs 回归门
- `eb3b612` docs(runtime): 记录 T8 回归门红绿证据与 CI 接入
- 合并：`91510b2` merge(runtime): 合入 T8 启动性能回归门

### T5 / T6 条件票

- `e82bc44` docs(runtime): 记录 T5/T6 条件票 deferred 决策

### 集成分支审查与收尾

- `7e91ac8` docs(reports): 汇总 runtime-performance 剩余工单收尾
- `e5876ba` fix(runtime): 收紧 T3/T7/T8 审查边界
- `ffef116` test(runtime): 固化 T3 全量 initramfs 黄金哈希 [ticket-03]
- `2cab4fc` docs(reports): 记录审查修复与最终验证 [runtime-performance]
- `b766c83` docs(reports): 对齐最终集成 HEAD [runtime-performance]
- `122a4cf` docs(reports): 记录全量重跑与既有 flake [runtime-performance]

## 3. 关键实现点

### T3

- 入口：`QemuInstanceLauncher.credentialsOnlyLaunch(...)`。每次 launch 从 `InstanceLifecycle.FileStore(context.getFilesDir()/instances)` 重新加载实例，按 `rootfsFileName` 解析 `DistroCatalogRegistry` 当前 sha，只有 `instance.isProvisionedFor(sha)==true` 才走快速路径。
- `QemuRuntimeBootstrapper.bootstrap(..., boolean credentialsOnly)`：为 true 时不 stat/size/open rootfs；使用 `EmbeddedProvisionInput.writeCredentialsOnlySegment(...)`，只写 `.active`、信任元数据、token、guest 凭据、hostname、镜像源、first-boot、guest init、proxy relay。
- 全量路径仍调用原 `writeSegment`，产物有黄金 SHA-256 断言。
- 凭据-only segment 上限 256 KiB，在 `Files.move` 发布前检查。
- 回退：metadata 缺失/损坏、marker 版本不符、sha 不匹配、rootfs 文件名未知/歧义、读取异常。

### T8

- `ProvisionedLaunchRegressionTest` 3 个用例：
  - 已装配路径：opener 0 次、读取 0 B、无 rootfs entry、segment ≤ 256 KiB、timeline `rootfsBytes=0`；
  - 同一 256 KiB 不可压缩 fixture 的全量路径：打开 1 次、含 rootfs entry、segment > 256 KiB；
  - launcher evidence 决策：无 evidence / 不匹配 / 未知文件名 false，匹配 true。
- 红/绿证据：`docs/reports/tickets/runtime-performance-08/red-green-evidence.md`。
- CI：`.github/workflows/ci.yml` 新增命名回归门步骤，并把 runtime contract/instance/engine-qemu 检查接入全量步骤。

### T7

- `QemuTuning` 默认值：`cortex-a57`、无 `tb-size`、root 盘 `cache=writeback`、TCP loopback passt、vCPU 取 `VmHardwareConfig.cpuCores()`。
- 变体：CPU model、`tb-size`、`aio`、`discard=unmap`、`detect-zeroes=unmap`、vCPU、passt UNIX stream。
- 无 cache 字段；测试禁止 `cache=unsafe`。
- 矩阵：`python3 tools/qemu-tuning/matrix.py --dry-run --image ubuntu-24.04 --output docs/reports/tickets/runtime-performance-07/matrix-dry-run.tsv`。

## 4. 验证记录

```text
:runtime:contract:check    3 suites / 24 tests / 0 failures
:runtime:instance:check    4 suites / 15 tests / 0 failures
:runtime:engine-qemu:check 1 JUnit suite / 2 tests + 11 个 main-based JavaExec 任务
:app:testDebugUnitTest     105 suites / 823 tests / 0 failures / 0 errors / 0 skipped
:app:lintDebug             0 errors / 753 warnings
git diff --check           pass
新增 .log 证据文件         无
```

一次全量运行中 `:runtime:contract:bridgeClientResponsePumpTest` 出现一次时序 flake 失败；单跑与随后全量重跑均通过（`BRIDGE_RESPONSE_PUMP_OK tests=25`）。未修改该测试。

## 5. 真机实测清单（交接给另一环境）

1. 用 Ubuntu 与 Arch 各创建一个实例，完成首次装配，记录首次启动各阶段耗时、initrd 大小与 guest 内存占用。
2. 停止后重复启动同一实例，确认：
   - 宿主不再读取 distro tarball；
   - `launch-timeline.jsonl` 的 `rootfsBytes=0`、`credentialsOnly=true`、`initramfsBytes` 非空；
   - guest 到达提示符，凭据/主机名/镜像源仍生效。
3. 在真实设备上确认 guest 的 `DROIDLAYER_PROVISIONING_COMPLETE version=1` marker 能被宿主记录；旧实例走全量路径。
4. 停止 64 个 service forward，确认在硬窗口内返回；检查真实 gvproxy listener 关闭。
5. 若继续 T5/T6：先补首次启动/initrd 内存数据，再按 T5 → T6 顺序实现；不要绕过 T3 的 evidence 判定。
6. 若跑 T7：控温、同镜像、同资源、n≥10，记录设备型号/Android/电池温度/热状态；没有数据就保留 `not-tested`。

## 6. 已知风险

- T3：宿主 evidence 与根盘实际状态不一致时 guest fail closed（不写根盘）；旧实例无 evidence 仍走全量。
- T3：256 KiB 上限对真实 segment（约 59 KB）有余量，但新增合法小文件需显式评估。
- T8：只覆盖 launch 的零 rootfs 契约、initrd 上限与 launcher 决策；不覆盖 guest 消费、真实 QEMU/gvproxy 或 CI 实际运行。
- T7：`-netdev stream`、`io_uring`、部分 CPU model 仅参数构造通过，设备上可能启动失败。
- 继承 T1/T2/T4 偏差：T1 stop retry JSONL 保留第一次结果；T4 严格锁外停止未完全满足、force-wait 路径网关可能存活；T2 AVF sink 未接。
- 既有 `bridgeClientResponsePumpTest` 时序 flake（单跑/重跑通过）。

## 7. 建议的合并方式

- 先在本分支做代码比对与真机验证，再决定 merge 还是 squash。
- 不建议直接改 `main`；如需保留逐票审查记录，使用 merge commit；如需单一交付提交，使用 squash。
- 合并前确认 CI 的 `Run provisioned launch regression gate` 与全量 runtime/app 检查均为绿。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录

# 真机交叉加速测试交接 Prompt（2026-10-23）

> **2026-10-24 改道提示（先读这段）**：本任务书假定「加速车道 = non-protected 自定义 VM」，并要求设备具备 bit2。当前设备 `getCapabilities()=1`（protected-only），§4 的第一项就会停。现已新增 protected 后端（payload 内进程内编译，不需要 bit2）：`.scratch/orchestella-pvm-compiler/spec.md`、`issues/DV-PVM-01-设备机制门.md`。**新的设备轮请按 DV-PVM-01 执行**；本文件保留为 non-protected 设备的历史任务书，原文不删。另外 §2/§3 的「装配根未接线」已由 PR #18 在分支上闭合。

> 这份文档是给**另一个开发环境中的执行 Agent**的完整任务书。它假定你已经能拉取 GitHub 仓库、能操作一台真实 Android 设备，并且可以用子智能体与联网调研。
>
> **先读这一句**：零设备工单 **PL-01/PL-02/AX-01–AX-08 已全部交付**，四道前置门（Android 事实提供者、协调器装配点、QEMU 就绪探针、性能协议）在 `main` 上都有生产实现与证据。你**可以**直接开始真机流程，但仍要先按 §4 核对设备资格、按 §3 复核四道门；任何一门回落成 fake，就只能做 QEMU/AVF 基线并写明"交火未装配"。

---

## 0. 任务目标

在真实 Android 设备上验证两条车道的交叉加速：

- **Full Lane**：QEMU 中的完整 Linux guest；
- **Acceleration Lane**：non-protected 自定义 Linux VM（硬件 vCPU）；
- 通过 AXP1 → distccd 把 QEMU guest 的编译任务卸载到加速 VM；
- 测量端到端收益，保留失败样本，产出可复核证据。

不要求你修改 protected 车道的 exec 语义。protected 在当前平台仍是 `execute_no_trans` 阻塞，**不得绕过**，也不得把 protected 当加速器。

## 1. 先读哪些文件

按顺序：

1. `AGENTS.md`
2. `docs/reports/orchestration/accelerator-zero-device-progress-20261023.zh-CN.md`（当前进度与缺口）
3. `.scratch/orchestella-accelerator/决策地图.md`
4. 工单：
   - `.scratch/orchestella-accelerator/issues/AX-07-跨车道性能测量与验收协议.md`
   - `.scratch/orchestella-accelerator/issues/AX-08-设备前生产装配与探针.md`
   - AX-01/02/03/04/05/06 工单
5. 现有实现：
   - `app/src/main/java/io/github/nyzeep/orchestella/DualLaneCoordinator.java`
   - `app/src/main/java/io/github/nyzeep/orchestella/QemuLaneRuntime.java`
   - `runtime/engine-fastlane/`、`runtime/fastlane-capability/`、`runtime/fastlane-distcc/`
   - `tools/fastlane-build/build-distccd.sh`、`tools/fastlane-build/test-distcc-e2e.sh`
6. 证据报告：
   - `docs/reports/tickets/ax-01/` … `ax-06/`
   - `docs/reports/tickets/batch2-fixes/`

## 2. 仓库与分支预检

1. 工作目录假设为仓库根；`/workspace/Orchestella-Studio` 之类的未跟踪副本不要修改、不要 add、不要 clean。
2. 目标 ref：**直接用 `main`**。四级 accelerator 堆叠（`feat/accelerator-batch1` → `batch2` → `batch3` → `device-handoff`，PR #8–#11）已按 merge commit 全部合入 `main`，**无需 checkout 任何 accelerator feature 分支**。
3. 检查基线（不硬编码 SHA，避免文档再次漂移）：
   ```bash
   git log --oneline -6
   git log --oneline --merges -4   # 应能看到四级堆叠的四个 merge commit
   git status --porcelain=v1
   ```
   如果看不到四级合并提交，说明基线不是预期状态：**停止并报告**，不要自行修复历史。
4. 工作树有意外改动时停止并报告，不要覆盖。
5. 提交身份按仓库既有配置（从 `git config user.name` / `user.email` 读取，不要写进文档或提交信息）；feature 分支推 GitHub，不 push `main`。
6. CI 历史：当前 `main` 绿色；2026-10-21/22 的失败 run 都在旧 SHA 上，已由新 run 取代。清理历史失败 run 需要 `actions:write` token，可执行 `python tools/ci/cleanup_failed_runs.py`（默认 dry-run）确认后再 `--apply`；不要删除 workflow 定义。

## 3. 强制前置门：AX-08 与 AX-07

四道门已在 2026-10-23 的第五批闭合。**上设备前请复核它们没有回落**：

```bash
git grep -l "implements AcceleratorFactProvider" main -- '*.java'
# 期望：app/src/main/java/io/github/nyzeep/orchestella/AndroidAcceleratorFactProvider.java

git grep -n "new DualLaneCoordinator" main -- 'app/src/main/*'
# 期望：AcceleratorLaneAssembly 内的唯一 new 点

git show main:app/src/main/java/io/github/nyzeep/orchestella/QemuLaneRuntime.java | grep -n "QemuReadinessProbe"
# 期望：有接收 QemuReadinessProbe 的构造器

python tools/accelerator-perf/validate.py --self-test
# 期望：ACCELERATOR_PERF_OK
```

**如果任一项缺失：**

- 选项 A（推荐）：先补齐再上真机，零设备验收通过后再接设备；
- 选项 B：只做 QEMU/AVF 基线测试，报告中明确写"交火未装配，未验证协调器"；
- 禁止：把 fake 装配当生产装配，或声称"交叉加速已验证"。

### 3.1 你还需要自己接上的一段

装配根 `AcceleratorLaneAssembly` 需要两个 `LaneStarter`：QEMU 车道与加速车道。QEMU 侧用现成会话即可；**加速车道的 starter 要创建 non-protected 自定义 VM，这段属真机工作，零设备阶段无法实现**。也就是说：

- 装配入口、决策、预算、停止顺序都已就绪；
- 但"每实例启动都经过装配根"的调用链要由你在设备上接上，然后 flag 打开（`-Porchestella.acceleratorLane=true`）；
- 打开 flag 后先看 `Result.decision()`：不是 `QEMU_PLUS_ACCEL` 就按 reason code 排查（见 `docs/reports/tickets/ax-08/implementation-report.zh-CN.md` §5），不要"调到能跑"。

性能采集与停止条件见 `tools/accelerator-perf/README.md`；`requiredCompilerTriple` 用 guest 侧 `gcc -dumpmachine` 的输出。

## 4. 设备资格（先于任何性能测试）

记录并核对：

- 稳定设备别名（不写序列号/型号/IMEI）；
- Android API、ABI、`ro.build.type`；
- `android.software.virtualization_framework` 是否为 true；
- `VirtualMachineManager.getCapabilities()` 的 raw bits；
- non-protected 是否可用（bit2），`setProtectedVm(false)` 是否成功；
- `MANAGE_VIRTUAL_MACHINE` 与 `USE_CUSTOM_VIRTUAL_MACHINE` 是否 GRANTED；
- 可用 RAM/存储、热状态、电量；
- 是否已安装 QA/调试包；包名与版本后缀只写固定值，不写路径。

不满足 non-protected 条件时，停止交火测试，转 QEMU 基线或报告阻塞。

## 5. 工作流

### Phase 0：零设备复验

- 跑全量 Gradle、pytest、policy gate、CI coverage、敏感扫描；
- 构建 distccd arm64，跑 `check-distccd-artifact.sh`；
- 跑 `test-distcc-e2e.sh`；
- 任何一项红，先修再上设备。

### Phase 1：QEMU 基线

- 启动 QEMU Full Lane；
- 验证 terminal、网络、stop；
- 连续 3 次启停，记录 stop 结果与残留；
- 记录冷启动到 ready 的墙钟。

### Phase 2：加速 VM 基线

- 用 non-protected 自定义 kernel/initrd 启动加速 VM；
- 验证 console、vsock 连接、stop；
- 不跑 distcc，只证明 VM 可启停；
- 失败时保留 stage/reason code，不重试超过 2 次。

### Phase 3：上行链路

- 在 QEMU guest 内解析 `DISTCC_HOSTS`（`192.168.127.254:<port>`）；
- 验证 guest → gvproxy hostIP → 宿主 `127.0.0.1`；
- 用 AXP1 测试客户端完成 HELLO/HELLO_ACK；
- 记录 token 校验失败、端口占用、超时的结果；
- 不要在 LAN/公网暴露监听端口。

### Phase 4：distcc 端到端

- 最小 C 文件：远端编译 → 回传 `.o` → guest 链接 → 运行；
- 对比本地编译 `.o` 的 SHA-256；
- 真实项目：单核、`-j2`、`-j4`；
- 记录预处理/传输/编译/链接拆分、失败率、回退次数；
- 编译器 triple 必须与加速 VM manifest 一致；版本不一致时记录并回退。

### Phase 5：性能

- 场景：冷启动、小项目、中项目、`-j1/-j2/-j4`；
- n≥10，median/IQR，保留失败样本；
- 同设备、同镜像、同资源、控温；
- 基线 QEMU-only vs QEMU+ACCEL；
- 端到端 <1.5x → 记录负结果，不产品化；
- 任一场景回归 >5% → 回退该变体。

### Phase 6：证据与收口

- 原始产物放外部 artifact 根，仓库只写别名、SHA、固定 code；
- 更新设备矩阵一行（只追加）；
- 写实施报告到 `docs/reports/tickets/dv-acc/`；
- 若修改代码，开 feature 分支/PR，不直接改 `main`。

## 6. 子智能体与联网

- 允许并鼓励：
  - 调研子智能体：AVF non-protected、vsock、distcc、gvproxy 一手资料；
  - 评审子智能体：实现完成后独立 review；
  - 证据子智能体：只分析脱敏后的计数/哈希，不接触原始设备日志。
- **设备是单串行资源**：同一时刻只允许一个 Agent/一条 adb 命令操作设备；禁止并行安装、启停或抓日志。
- 联网只引用一手来源，报告 URL 与访问日期；参考 Podroid 时只读行为/架构，不复制 GPL-2.0 代码。

## 7. 证据与安全

- 永不入库：序列号、IMEI、Android ID、设备指纹、真实 LAN/公网 IP/MAC、账号、token、签名、本机绝对路径、原始 logcat/AVC、截图/录屏。
- 设备证据只用稳定别名 + SDK + ABI + 固定 reason code + SHA-256。
- AXP1 token 每次运行新生成；不写日志、不写文档、不写提交信息。
- 失败样本保留，不得只报成功。
- `STOP_UNCONFIRMED` 时保留 RUNNING/lease，不伪造停止。

## 8. 停止条件

- 设备不支持 non-protected（bit2 false / `setProtectedVm(false)` 失败）；
- 权限未授予且无法通过合规方式获得；
- 加速 VM 连续两次启动失败；
- QEMU 或加速 VM stop `UNCONFIRMED`；
- 需要 root、SELinux/pvmfw 修改、`/system/bin/sh`、memfd、execveat、伪 shell；
- 端到端 <1.5x（记录负结果后停止产品化，不继续调参掩盖）；
- 任何操作可能导致用户数据丢失或设备失控。

## 9. 最终报告格式

1. 结论：Phase 0–6 各自 pass/fail/blocked。
2. 仓库状态：ref、HEAD、分支、PR、CI。
3. 设备资格：别名、API、ABI、bits、权限、热/电。
4. 每阶段命令、结果、reason code。
5. 性能表：n、median、IQR、卸载率、失败样本。
6. 证据路径与 SHA-256。
7. 偏差、风险、未验证项。
8. 对产品/下一轮的建议。
9. 需要维护者决策的问题（≤5 条）。

不得包含 token、序列号、设备指纹、本机绝对路径、原始日志。

## 10. 提交规则

- 每票/每阶段一分支：`feat/dv-acc-<phase>`。
- PR 到当前集成分支或 `main`（按仓库当前 stacked 状态）；CI 全绿后由维护者手动合并。
- 不 push `main`，不使用 `--no-verify`，不删测试、不关 lint。

## 11. 交接判定

只有同时满足以下条件，才能在本轮报告里写“交叉加速真机验证完成”：

- AX-08 生产装配已存在并被设备实际执行；
- QEMU + ACCEL 双车道同时运行；
- guest → host → AXP1 → distccd 全链路成功；
- 至少一个真实项目 n≥10，端到端 ≥1.5x；
- 所有失败样本、stop 结果、证据 SHA 可复核。

否则只能写“基线验证”或“交火未验证”。

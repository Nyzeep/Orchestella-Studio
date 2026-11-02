# 08: T2 · provision 操作超时分档 + 跨层一致性

Type: implementation
Status: ready-for-agent
Priority: P0
Owner: （待分配）
Blocked by: None
Blocks: 07
External gates: 无（零设备）

## 背景（已实证）

- 现状：`guest-assets/scripts/orchestella-guest-provision.sh:118-143` 的 `run_step` 只有两档 —— 8 个 stage 走 1800s，其余 600s。
- 真机第二轮 attempt6：`-Syu` 是 92 包事务（含全量 `linux-firmware-*` 拆分包，下载 ~1.36GB @1.9MB/s），下载+解包击穿 1800s → `operation-timeout`(124)。
- 上层还有两道墙：guest systemd `TimeoutStartSec=2h`（`guest-assets/initramfs/runtime-functions.sh:307`、`init:725`）；宿主 `BridgeClient.DEFAULT_STARTUP_TIMEOUT_MS = 3_900_000L`（`runtime/contract/.../BridgeClient.java:19-20`，65min）。**单改 guest 档位而不动这两道墙，等于没修**。

## 必须做

1. **四类档位表**（默认值，全部可被环境变量覆盖）：
   | 类别 | stage | 默认秒 | 覆盖变量 |
   |---|---|---|---|
   | `download` | repository-refresh, certificate-bootstrap-refresh, certificate-bootstrap-install | 900 | `ORCHESTELLA_PROVISION_TIMEOUT_DOWNLOAD` |
   | `transaction` | package-install, package-recovery, init-repair, optional-upgrade | 3600 | `…_TRANSACTION` |
   | `compute` | locale | 1800 | `…_COMPUTE` |
   | `verify` | 其余全部（含 07 票新增的 package-lock-recovery） | 600 | `…_VERIFY` |
   - 变量值必须校验为纯正整数，非法值回落默认并记一行 `…_TIMEOUT_OVERRIDE_IGNORED`（不要静默）。
   - 分类方式：显式 `case` 映射，**不得**用「stage 名里含 install 就当事务」这类推断。
2. state 追加字段 `operation_timeout_class=<download|transaction|compute|verify>`；保持 `version=2`，`write_state` 与 `report` 同步（`report` 的 stdout 行**不必**新增字段，避免破宿主正则 `GuestRuntimeStatus.java:9-10`）。
3. **不得写死 TCG 前提**：注释只描述 I/O 规模与解包开销，不出现「TCG / 模拟器 / 加速器」等前提词。
4. 外层两道墙同步：
   - guest：`TimeoutStartSec` 提到能覆盖 `download + transaction` 两段之和 + 余量（≥ 4500s 量级），`runtime-functions.sh` 与 `init` 两处同步；OpenRC 侧若无超时则不动。
   - 宿主：`DEFAULT_STARTUP_TIMEOUT_MS` 3,900,000 → **5,400,000**（90min），注释改成「镜像校验 + 首启 provision 事务 + boot 余量」的推导式说明。
5. **跨层一致性单测**（关键，防漂移）：新增纯 JVM 测试（放 `runtime/contract/src/test/...`，JUnit4），从仓库根定位 `guest-assets/scripts/orchestella-guest-provision.sh` 与 `runtime/contract/.../BridgeClient.java`，解析出四个档位默认值、`TimeoutStartSec`、`DEFAULT_STARTUP_TIMEOUT_MS`，断言：
   - `DEFAULT_STARTUP_TIMEOUT_MS > (download + transaction) * 1000`；
   - `TimeoutStartSec >= download + transaction`；
   - 任一侧解析失败即测试失败（不要 skip）。
   - **路径必须相对定位**（从 `System.getProperty("user.dir")` 向上找 `settings.gradle.kts`），**不得**写死绝对路径（敏感数据门禁会拦）。

## 测试

- `guest-assets/scripts/test-distro-runtime-matrix.py`：现有 `timeout` 桩硬断言 `budget = 1800`（`:66-75`）与 `operation_timeout_seconds == "1800"`（`:268`）→ 按新表更新为 `transaction` 档 3600、`download` 档 900；`expect` 断言补充 `operation_timeout_class`。
- 新增用例：环境变量覆盖生效（`ORCHESTELLA_PROVISION_TIMEOUT_TRANSACTION=123`，桩断言 budget==123）；非法值回落默认并出现 `TIMEOUT_OVERRIDE_IGNORED`。
- 跨层一致性测试见上。

## 边界

- 不改 stage 集合的顺序语义（`run_step` 先写 state 再执行的顺序保持不变 —— SIGKILL 后 state 停在 `running/<stage>` 是可观测契约）。
- 不改 `--status` / `--bootstrap` / `--retry` 语义。
- 不改 `BridgeClient` 的其他常量与握手逻辑，仅改这一个数值 + 注释。

## 完成判据

四档可覆盖、非法值有信号；跨层一致性测试绿；guest 两个 python 驱动器绿；`:runtime:contract:check` 绿。

## Comments

- 2026-11-02 / 主会话：建票。依据 `.scratch/orchestella-uml-fabric/notes/A1-provision.md` §2（41 个 `run_step` 调用点归类 + 宿主无 provision 超时配置）与 `A2`/`A0`（`BridgeClient` 65min 单一常量风险，A0 P1）。用户裁定：分档 + 默认上调（事务 3600 / 宿主 90min）。
- 2026-11-02 / 实现子代理 + 主会话复核：**resolved**。四档（download 900 / transaction 3600 / compute 1800 / verify 600）走显式 `case` 映射，`ORCHESTELLA_PROVISION_TIMEOUT_*` 覆盖，非法值（负数/后缀/前导零）回落默认并打 `ORCHESTELLA_PROVISION_TIMEOUT_OVERRIDE_IGNORED`。state 增 `operation_timeout_class`（`version=2` 不变，`report` 行未动）。两道外墙：guest `TimeoutStartSec` 2h→5400s（模板 + 生成物），宿主 `DEFAULT_STARTUP_TIMEOUT_MS` 3.9e6→5.4e6。跨层一致性单测 `ProvisionTimeoutBudgetTest` 4/4（上溯找 `settings.gradle.kts`，正则解析两侧，失败即红，无绝对路径）。矩阵断言 1800→3600/900 已按新契约更新并注明理由。定向验证：抽函数 10 例全过（`900s`/`0900`/`0`/`-60` 均被拒并回落）。**主会话追加修正**：原 `[1-9][0-9]*` 会放过 `900s` 这类带后缀值，改为先拒非数字与 0 开头再取值（同批 10 例覆盖）。

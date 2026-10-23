# 交叉加速零设备进度与真机前置评估（2026-10-23）

> **结论先行**：零设备工单已完成 8 / 10。PL-01、PL-02、AX-01–AX-06 已交付；**AX-07（性能协议）与 AX-08（设备前生产装配）未完成**。
>
> 因此当前分支**还不是“真机交叉加速可测”状态**：`DualLaneCoordinator`、`AcceleratorPolicy`、`ProtectedWarmPool` 都只有测试装配，没有 Android 生产事实提供者、没有 QEMU readiness 探针、没有 app 装配根。现在上真机，只能验证 QEMU 基线、AVF 非保护启停和既有数据面，**不能验证交火协调器**。
>
> 当前 `main` 的 CI 全绿；历史 21 条失败 run 全部位于已被取代的旧 SHA，删除需要 `actions:write` token，本环境只有 SSH 推送权限，未执行删除。

---

## 1. 基线与分支

| 项 | 值 |
| --- | --- |
| `main` | `4765d4b`（2026-10-23 GitHub 最新） |
| 批次一 | `feat/accelerator-batch1 @ 5fa22c8` |
| 批次二 | `feat/accelerator-batch2 @ a596d73`（stacked 在批次一） |
| 批次三 | `feat/accelerator-batch3 @ f8380bd`（stacked 在批次二） |
| 本报告分支 | `feat/accelerator-device-handoff`（从批次三切出，仅文档与工单） |
| 未跟踪副本 | `/workspace/Orchestella-Studio`，全程未修改、未 add、未 clean |

批次一至三均未合并 `main`。任何“拉取最新状态”的会话必须显式 checkout 上述分支，不能只看 `main`。

## 2. 工单状态

| 票 | 主题 | 状态 | 主要证据 |
| --- | --- | --- | --- |
| PL-01 | protected 预热池 | 完成（零设备） | `ProtectedWarmPool`，18 个 JVM 测试 |
| PL-02 | protected 失败面与平台门 | 完成（零设备） | `ProtectedFailure`，23 个 CI 测试，3 个 policy fixture 均 `GATE_CLOSED` |
| AX-01 | 双车道并发与资源仲裁 | 完成（零设备） | `DualLaneCoordinator` 28 个测试 + `QemuLaneRuntime` 4 个 |
| AX-02 | 能力事实与选道 | 完成（零设备） | 16 组 truth table，112 次参数化执行 |
| AX-03 | AXP1 通道协议 | 完成（零设备） | 17 个协议测试，golden vector |
| AX-04 | guest→host 上行 | 完成（零设备） | `192.168.127.254` 主路径、`10.0.2.2` 回退，15 个测试 |
| AX-05 | distccd arm64 构建 | 完成（零设备） | 720,992 B 静态产物，`DISTCCD_ARTIFACT_OK` / `DISTCCD_LICENSE_OK` |
| AX-06 | distcc 宿主 e2e | 完成（宿主回环） | 真实 distcc 5 case，`DISTCC_E2E_OK cases=5` |
| AX-07 | 性能测量协议 | **未完成** | 仅有工单，无采集器/schema/runbook |
| AX-08 | 设备前生产装配 | **未完成** | 工单已建；无 Android fact provider、无 QEMU readiness、无 app 装配根 |

## 3. 关键设计

### 3.1 车道划分

- **Full Lane**：QEMU TCG，多发行版、桌面、容器、gvproxy 网络；继续作为产品交付面。
- **Acceleration Lane**：non-protected 自定义 Linux VM（与 Podroid 同路线），硬件 vCPU，只承担编译卸载；不是 Full Linux，也不承担桌面。
- **protected 车道**：继续做生命周期、预热、失败面与平台门复评；在 `execute_no_trans` 解除前不承担执行加速。

### 3.2 数据路径

```text
QEMU guest (distcc client)
  -> 192.168.127.254:<port>   (gvproxy hostIP, NAT 到宿主 127.0.0.1)
  -> AXP1 HOST 握手（token / caps / compiler triple）
  -> acceleration VM 内 distccd --inetd
  -> .o 原路返回 guest，链接在 guest 完成
```

- 回退：slirp `10.0.2.2`；guestfwd 只对 slirp backend 有效，不作为 gvproxy 主路径。
- AXP1 只做 token 鉴权，不做加密；必须限制在 vsock 或 loopback TCP。

### 3.3 停止与预算

- QEMU stop 仍由 `RuntimeProcessSupervisor` 拥有；protected/accelerator 由 `FastLaneSession.stopRuntime` 拥有。
- `DualLaneCoordinator` 只做编排，不新增第二套 stop 机制；停止顺序固定 QEMU → ACCEL。
- 预算拒绝返回 `LaneRejectCode`；`UNCONFIRMED` 保留 lease 与预算。

## 4. 测试与证据

| 命令 | 结果 |
| --- | --- |
| 全量 Gradle（5 模块） | engine-fastlane 109、instance 29、capability 144、distcc 63、app 925，0 失败 0 跳过 |
| `python -m pytest tools/ci tools/fastlane-device tools/fastlane-distcc -q` | 111 passed，7 subtests |
| `check_microdroid_exec_gate.py` | `open=0 closed=3 unclear=0` |
| `check_ci_module_coverage.py` | 58 passed，0 failed |
| `check_sensitive_data.py --range` | `SENSITIVE_DATA_OK` |
| `git diff --check` | 通过 |
| `test-distcc-e2e.sh` | `DISTCC_E2E_OK cases=5`，`.o` 哈希与本地一致 |

证据目录：

- `docs/reports/tickets/pl-01/`
- `docs/reports/tickets/pl-02/`
- `docs/reports/tickets/ax-01/`
- `docs/reports/tickets/ax-02/`
- `docs/reports/tickets/ax-03/`
- `docs/reports/tickets/ax-04/`
- `docs/reports/tickets/ax-05/`
- `docs/reports/tickets/ax-06/`
- `docs/reports/tickets/batch2-fixes/`

## 5. 未完成项与真机缺口

### 5.1 AX-08：生产装配缺失（P0）

现状：

- `AcceleratorFactProvider` 只有 fake，没有 Android 实现；
- `QemuLaneRuntime` 的 readiness 默认恒 false，没有生产探针；
- `new DualLaneCoordinator` 在 `app/src/main` 没有调用点；
- `ProtectedWarmPool` 没有生产实例。

后果：真机上无法触发 `QEMU_PLUS_ACCEL`，协调器、预算、停止顺序、选道都不会被执行。必须先完成 AX-08。

### 5.2 AX-07：性能协议缺失（P1）

没有采集器、schema、n≥10 统计与设备 runbook，无法把“比 Podroid 快”变成可复核结论。

### 5.3 其他未验证项

- 真实 vsock 上的 AXP1；
- 真实 non-protected AVF VM 启停与 vsock；
- gvproxy `.254` 到宿主 `127.0.0.1` 的真机连通；
- distccd 在 Android/加速 VM 内的执行与 16 KB 启动；
- 热/电读数与预算策略；
- 真实 stop 阻塞时间、进程死亡、OOM；
- C++、跨架构、编译器版本一致性。

## 6. CI 状态与历史失败

### 6.1 当前 main

2026-10-23 的最新 run 均为 success：

- CI `39897089127`
- Fast Lane zero-device `39896616998`
- Sensitive data guard `39897089154`

### 6.2 历史失败

GitHub Actions 中仍有 **21 条失败 run**，全部位于已被取代的旧 SHA：

| 工作流 | 分支 | 数量 | 说明 |
| --- | --- | --- | --- |
| CI | `main` | 4 | 2026-10-21/22 旧提交 |
| Fast Lane zero-device | `main` | 3 | 同上 |
| CI | `integration/fastlane-dv01b` | 4 | 旧集成分支 |
| Fast Lane zero-device | `integration/fastlane-dv01b` | 5 | 同上 |
| CI | `fix/fastlane-dv01b-runner-hardening` | 1 | 旧提交 |
| Fast Lane zero-device | 同上 | 1 | 旧提交 |
| Guest assets | 三个旧 tag | 3 | v0.3.0-preview.1 / v0.4.2 / v0.4.5 |

这些 run 不能通过改代码“修复”：它们绑定旧 SHA，当前 `main` 已有新的绿色 run。删除或重跑需要 GitHub token 的 `actions:write` 权限；本环境只有 SSH 推送权限，实测 `DELETE` 返回 401，**未执行删除**。也不建议删除 workflow 定义来“隐藏”历史，那会同时失去覆盖。

已提供 `tools/ci/cleanup_failed_runs.py`：默认 dry-run，`--self-test` 通过；具备 token 的环境可执行 `GITHUB_TOKEN=... python3 tools/ci/cleanup_failed_runs.py --apply`，只删除 `conclusion=failure` 的 run，不碰成功记录。

## 7. 真机前置条件

1. 设备 `getCapabilities` 显示 non-protected 可用（bit2），`setProtectedVm(false)` 成功。
2. 已完成一次 QEMU Full Lane 启停，确认 stop 收敛。
3. 加速 VM 的自定义 kernel/initrd/镜像已就位，且能启动、能连 vsock。
4. AX-05 的 distccd 产物已随镜像或 artifact 放入加速 VM。
5. AX-08 的生产装配已合入，feature flag 可显式打开。
6. 设备证据只写稳定别名、SDK、ABI、结果码；原始日志/序列号/路径留在外部 artifact 根。

## 8. 建议下一步

1. 完成 AX-08 与 AX-07（第四批）。
2. 为 `feat/accelerator-batch1`、`batch2`、`batch3` 开 PR，按顺序合并；每级合并后改基下一级。
3. 合并后跑全量 CI，确认 5 模块 + policy + e2e 全绿。
4. 再进入 DV-ACC 真机测试。
5. 若要清理历史失败 run，提供具备 `actions:write` 的 token，再批量删除；不要删除 workflow 定义。

## 9. 附录：快速核对命令

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew :runtime:engine-fastlane:test :runtime:instance:test \
  :runtime:fastlane-capability:test :runtime:fastlane-distcc:test \
  :app:testDebugUnitTest \
  -Porchestella.repositories.useAliyunMirror=true --console=plain
python -m pytest tools/ci tools/fastlane-device tools/fastlane-distcc -q
bash tools/fastlane-build/test-distcc-e2e.sh
python tools/ci/check_ci_module_coverage.py
python tools/ci/check_sensitive_data.py --range main..HEAD
```

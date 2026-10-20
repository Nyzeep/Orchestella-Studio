# 09: 设备验收工具 dry-run

Type: implementation
Status: resolved
Priority: P1
Owner: device-validation tooling owner
Blocked by: None
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

用户会在零设备工单完成后提供真机。现在需要一个**一条命令**的 AVF 设备验收工具：它能在没有设备时 dry-run，明确输出每一步 `not-tested`，绝不伪造通过；有设备时按同一 schema 产出可归档报告。现有 `tools/device-validation/` 已有 preflight、stage recorder、fixtures，应复用而不是重写。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §3.3；`tools/device-validation/README.avf-validation.zh-CN.md`、`avf_preflight.py`、`avf_stage_recorder.py`、`avf_test_fixtures.py`、`run_distro_smoke.py`。

## What to build / 范围

1. 新增/扩展一个 AVF 设备验收入口（建议 `tools/device-validation/run_avf_device_validation.py`，复用现有 recorder/fixtures），支持：
   - `--dry-run`：不连设备，输出结构化报告，所有设备步骤 `not-tested`；
   - `--device <serial>`：未来真机模式（本票只保证参数/阶段定义，不执行）；
   - `--out <dir>`：报告目录；JSON + Markdown 摘要。
2. 阶段定义覆盖 research §3.3 的 12 步：
   1) feature/ABI/SDK；2) `getCapabilities` bits（App 与 shell/US 分别记录 actor）；3) 两权限；4) `setProtectedVm(false)` 可构造；5) App 私有 kernel/initrd/root raw create；6) run 返回（不等于 ready）；7) vsock 9100/9101 Bridge/终端握手；8) 正常 stop/20 次启停；9) 网络（无网络/静态/DHCP/Android 17 GpuConfig workaround）；10) 9p/display；11) App 死亡/强停；12) 同资源 QEMU 对照入口（仅定义，不执行）。
3. 每步状态只能是 `pass` / `fail` / `blocked` / `not-tested`；dry-run 全部 `not-tested`，禁止默认 `pass`。
4. 失败归因字段：stage、reason code、evidence path、timestamp、actor（app/shell）、ROM binding（脱敏）；不记录 serial/IMEI/MAC/账号/token/用户根盘/屏幕内容。
5. 报告 schema 有版本；工具测试覆盖 dry-run、缺设备、故意坏 fixture、redaction。
6. 与 01 的 CI 联动：dry-run 可作为 CI 的轻量 job（不需要设备）；真机模式不在 CI 运行。

## 行为验收（零设备）

- [x] `--dry-run` 无设备可运行，退出码 0，报告所有设备步骤 `not-tested`，不含伪造 `pass`。
- [x] 报告包含 12 个阶段与每步的 evidence 字段；schema 可被测试解析。
- [x] 缺设备/ADB 不可用/权限未授予在 dry-run 中不崩溃；真机模式给出明确 `blocked`。
- [x] 现有 preflight/recorder 测试不退化；新增工具测试通过。
- [x] 输出不含 serial/IMEI/MAC/账号/token/用户根盘/屏幕内容；有 redaction 测试。
- [x] 一条命令能产出 Markdown 摘要，供用户归档/交接。
- [x] 不把 QEMU/AVF 的 fake 结果写成真机通过；dry-run 明确标注“设备无关”。

## 测试 seam 与 red 命令

- Python unittest/pytest（按仓库现有方式）：`tools/device-validation/test_avf_device_validation.py`。
- 用现有 fixtures 造“有设备/无设备/权限拒绝/status 35/空 console”等输入；不调用真实 adb。
- 红命令：
  ```bash
  python -m unittest discover -s tools/device-validation -p 'test_*.py'
  python tools/device-validation/run_avf_device_validation.py --dry-run --out /tmp/avf-dry-run
  ```
- 先 red：dry-run 缺阶段/默认 pass/输出 serial；再最小 green。

## 失败、数据与兼容

- 失败不自动重试无限次；每步有 deadline；工具不自动 grant/root/解锁。
- 不修改设备状态；真机模式未来只读/显式 QA 实例，使用合成盘。
- 现有工具 CLI/输出兼容；新增 schema 版本，不破坏旧报告读取。

## 迁移、回滚与依赖

- 纯工具，无实例数据迁移。
- 回滚：删除新入口/保留现有工具；不影响产品。
- 依赖：优先标准库；若新增 Python 包，需固定版本/许可并写报告。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-09/implementation-report.zh-CN.md`。
- 完成 = dry-run 报告 + 工具测试 + redaction + 现有工具回归 + 报告明确“真机未执行”。

## 不做

- 不执行真机验收；不安装 APK/不 grant/不 root/不解锁；不做性能基准；不替代 10 的设备清单。

Claimed by: Agent D / 2026-10-20T03:33:41Z

## Answer

- 新增 `tools/device-validation/run_avf_device_validation.py` 与 `test_avf_device_validation.py`；12 阶段严格对应 research §3.3，schema_version=1，状态仅 `pass/fail/blocked/not-tested`。
- `--dry-run`：不连 adb，12/12 `not-tested`，`pass=0`，退出 0；输出 JSON + Markdown + preservation 副本。
- `--device`：本票只定义参数/阶段，不执行；12/12 `blocked`，`adb_invoked=false`，退出 2，不记录 serial。
- `--validate` + 严格 schema：坏 fixture、缺阶段、额外字段、非法状态、伪造 dry-run `pass` 均以 `REPORT_SCHEMA_REJECTED` 拒绝。
- redaction 测试覆盖 serial/IMEI/MAC/64-hex token/Bearer；报告与 Markdown 明确“设备无关”“真实 AVF 样本：0”。
- 复用 `avf_evidence_io`、`avf_preflight`、`avf_test_fixtures`，未重写 preflight/recorder。
- red→green：实现前 12 项测试因缺少模块报错；实现后 12/12 通过；全量 device-validation 139 tests / 0 failures。
- CI：`.github/workflows/ci.yml` 增加工具测试 + dry-run step；GitHub Actions 远端未执行。
- 未验：所有真机阶段（create/run/vsock/stop/network/9p/display/App death/QEMU 对照）。真实 AVF 样本仍为 0，本票不构成 AVF 设备验收通过。
- 报告：`docs/reports/tickets/avf-zero-device-09/implementation-report.zh-CN.md`。

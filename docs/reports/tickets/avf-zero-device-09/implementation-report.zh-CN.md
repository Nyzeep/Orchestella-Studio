# 09 实施报告：AVF 设备验收工具 dry-run

- 日期：2026-10-20
- Agent：Agent D
- 分支：`ticket/09-device-validation-harness`
- 基线：`ticket/01-ci-full-gate` @ `82009093`（已核实包含 02/03/08/04/06/07/05）
- 工单：`.scratch/orchestella-hw-virt/issues/09-device-validation-harness.md`
- 状态：resolved（零设备范围）；真机阶段全部 not-tested

## 1. 交付内容

1. 新增 `tools/device-validation/run_avf_device_validation.py`：
   - `--dry-run`：不连接设备，12 个阶段全部 `not-tested`，退出码 0；
   - `--device <serial>`：真机模式参数/阶段已定义，但本票不执行；12 个阶段全部 `blocked`，`adb_invoked=false`，退出码 2；
   - `--validate <report>`：严格校验既有 JSON 报告 schema，坏 fixture 以 `REPORT_SCHEMA_REJECTED` 拒绝；
   - `--out <dir>`：显式绝对输出根，JSON + Markdown，复用 `EvidenceStore` 的原子写入与 preservation 副本。
2. 阶段严格对应 research §3.3 的 12 步：
   `platform_declaration`、`capabilities`、`permissions`、`protected_mode`、`create`、`run_returned`、`vsock_handshake`、`stop_cycles`、`network`、`share_display`、`app_death`、`qemu_comparison`。
3. 报告 schema `schema_version=1`；每阶段状态只能是 `pass` / `fail` / `blocked` / `not-tested`；dry-run 校验器显式拒绝任何伪造 `pass`。
4. 失败归因字段：`reason_code`、`evidence`、`sampled_at_utc`、`actor`、`rom_binding`。本票所有阶段为 `not-collected`，没有设备值。
5. 复用现有工具：`avf_evidence_io`（安全路径/JSON/原子输出）、`avf_preflight.COMMANDS`（只读预检命令）、`avf_test_fixtures`（测试 sentinel）。没有重写 preflight/recorder/fixtures。
6. `.github/workflows/ci.yml` 增加轻量 job step：运行 device-validation Python 测试并执行 dry-run（`$RUNNER_TEMP` 下）。远端 GitHub Actions 未执行，见 §5。
7. `tools/device-validation/README.avf-validation.zh-CN.md` 增加 09 使用说明与边界。

## 2. Red → green 证据

初始 red（实现前）：

```text
$ AVF_VALIDATION_TEST_ROOT=/tmp/avf-validation-tests python3 -B -m unittest \
    tools/device-validation/test_avf_device_validation.py
EFE.EEEEEEEE
...
ModuleNotFoundError: No module named 'run_avf_device_validation'
```

实现后 green：

```text
$ AVF_VALIDATION_TEST_ROOT=/tmp/avf-validation-tests PYTHONDONTWRITEBYTECODE=1 \
  python3 -B -m unittest tools/device-validation/test_avf_device_validation.py
Ran 12 tests in 0.252s
OK
```

全量工具回归（含既有 `test_avf_tools.py`、`test_avf_recorder.py`）：

```text
$ AVF_VALIDATION_TEST_ROOT=/tmp/avf-validation-full PYTHONDONTWRITEBYTECODE=1 \
  python3 -B -m unittest discover -s tools/device-validation -p 'test_*.py'
Ran 139 tests in 10.975s
OK
```

说明：工单给的命令未带 `AVF_VALIDATION_TEST_ROOT`；既有测试要求该环境变量为绝对外部根，因此实际执行时补充了它，未改动既有测试语义。

## 3. dry-run 输出

```text
$ python3 -B tools/device-validation/run_avf_device_validation.py \
    --dry-run --out /tmp/avf-dry-run
{"mode": "dry-run", ..., "status": "NOT_TESTED",
 "summary": {"blocked": 0, "fail": 0, "not-tested": 12, "pass": 0}}
exit=0
```

产物：
- `avf-device-validation-<UTC>.json`（schema_version=1，12 stages）
- `avf-device-validation-<UTC>.md`（Markdown 摘要）
- `preservation/` 下同内容副本

dry-run 摘要：`pass=0 / fail=0 / blocked=0 / not-tested=12`；`real_avf_samples=0`；`verdict=NOT_TESTED`；`device_mode.implemented=false`、`adb_invoked=false`。

## 4. 测试覆盖

- dry-run：12 阶段、全 `not-tested`、无 `pass`、summary 一致；
- 伪造 dry-run `pass`：schema 拒绝；
- 缺阶段/额外键/非法状态/错误 summary：schema 拒绝；
- 设备模式：全 `blocked`、`adb_invoked=false`、不调用 adb；
- 缺模式/相对输出根：固定错误码，无 traceback；
- serial 作为 device alias：`ALIAS_REJECTED`，不回显；
- 坏 fixture：`REPORT_SCHEMA_REJECTED`；
- redaction：serial/IMEI/MAC/64-hex token/Bearer 均替换为 `<redacted>`；
- Markdown：包含“设备无关”“真实 AVF 样本：0”，不含“AVF 已支持/已通过”。

## 5. 未验项与边界

以下全部 **not-tested**，本票没有连接设备、没有安装 APK、没有 grant/root/解锁、没有启动真实 AVF：

- 真机 feature/ABI/SDK 与 hypervisor 声明；
- App 与 shell/UserService 的 `getCapabilities` bits；
- 两个权限在目标 ROM 的实际授予行为；
- `setProtectedVm(false)`；
- App 私有 kernel/initrd/root raw create；
- run 返回与 guest ready 的区别；
- vsock 9100/9101 Bridge/终端握手；
- 正常 stop 与 20 次启停；
- 网络（无网络/静态/DHCP/Android 17 GpuConfig workaround）；
- 9p 与 RFB 显示连通、输入；
- App 进程死亡/强停后的 VM 收敛；
- 同资源 QEMU 对照与性能。

远端 CI：本票只在本机执行了等价命令；GitHub Actions 未触发，不能写成“CI 远端绿”。

## 6. 偏差与风险

- 工单要求的“缺设备/ADB 不可用”在本票统一收敛为：设备模式不调用 adb，直接 `blocked` 且退出码 2。这是有意的零设备边界，不是真机失败分类。
- `--device` 的 serial 不写入报告或 stdout；工具不验证设备是否在线，因为本票不实现设备 I/O。
- 报告文件名带 UTC 微秒，避免 `EvidenceStore` 的“不覆盖既有输出”策略阻塞重复 dry-run；没有删除任何历史报告。

## 7. 结论

09 在零设备范围内完成：工具、schema、12 阶段、dry-run、设备模式 blocked、redaction、测试与 CI 接线就绪。**真实 AVF 样本仍为 0；本报告不构成 AVF 设备验收通过。**

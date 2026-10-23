# 平台门复评 runbook（Microdroid execute_no_trans）

- 适用：PL-02 / DV-01A 平台阻塞
- 零设备约束：本 runbook 的常规执行只读 AOSP 源码与仓库 fixture；设备取样步骤单独标注，不在零设备批次执行。
- 判定器：`tools/ci/check_microdroid_exec_gate.py`

## 1. 触发条件

满足任一条件即复评，不得沿用上一次结论：

1. 新 Android 版本、季度 QPR 或 OEM OTA 到达目标设备。
2. AOSP `platform/system/sepolicy` 的 `microdroid*` 规则发生变化。
3. OEM 更换 `com.android.virt` APEX 或 guest microdroid 镜像。
4. 设备 preflight 的 terminal 结果从 blocked 变为其它值，或出现新的 `execute_no_trans` AVC。
5. 本仓库 fixture 的 `GATE_CLOSED` 与设备实测不一致。

## 2. 取样位置

AOSP（canonical，首选）：

```text
platform/system/sepolicy
  microdroid/system/private/microdroid_app.te
  microdroid/system/private/domain.te
  microdroid/system/public/file.te
  microdroid/system/public/global_macros
```

OEM/ROM（仅当 AOSP 与设备不一致时）：

```text
adb shell pm path com.android.virt
adb pull <apex-path> <external-artifact-root>/<rom-alias>/com.android.virt.apex
# 在外部 artifact 根解包，只读取 microdroid 的 precompiled sepolicy
```

设备证据只写稳定别名；APEX、日志、截图放外部 artifact 根，不入库。

## 3. 命令

1. 取 AOSP 分支与 commit：

```bash
git ls-remote https://android.googlesource.com/platform/system/sepolicy \
  refs/heads/android16-release refs/heads/android17-release refs/heads/main
```

2. 生成最小 fixture（只保留 type 声明、`coredomain` 规则与 payload 类型规则），放到：

```text
tools/ci/fixtures/sepolicy/<case>/
```

3. 在 `EXPECTATIONS.json` 增加该 case 的期望值，并在 `SOURCES.md` 记录分支、commit、访问日期、许可证。

4. 运行判定器：

```bash
python3 tools/ci/check_microdroid_exec_gate.py \
  --policy-fixtures tools/ci/fixtures/sepolicy
```

5. 单文件快速判定：

```bash
python3 tools/ci/check_microdroid_exec_gate.py policy.te
```

## 4. 判定

| 输出 | 含义 | 动作 |
| --- | --- | --- |
| `GATE_OPEN` | 有效域（`microdroid_app` + `coredomain`/`domain` 继承）对 payload 类型有 `execute_no_trans` | 记录证据行；按 DV-01A 重新跑 preflight；不得直接宣称产品已解锁 |
| `GATE_CLOSED` | 解析完成，未找到 `execute_no_trans` | 保持 `blocked_external`；不重试、不绕过；等待平台变更 |
| `GATE_UNCLEAR` | 规则冲突（allow/neverallow）、未知宏、缺 domain/payload 声明、文件不可读 | 人工复核 fixture；不得把 UNCLEAR 写成 OPEN 或 CLOSED |

`GATE_CLOSED` 只是当前 fixture 的判定，不是“永久不支持”的产品结论。

## 5. 记录字段

每次复评只追加一条记录，字段固定：

```text
recheck_date_utc
rom_alias
sdk_int
abi
aosp_branch
aosp_commit
oem_apex_version        # 无则写 not-applicable
fixture_case
gate_result             # GATE_OPEN / GATE_CLOSED / GATE_UNCLEAR
evidence_line_count
conclusion              # blocked / candidate / unclear
operator_alias
```

禁止写入：序列号、IMEI、Android ID、设备指纹、真实 LAN/公网 IP、MAC、原始 AVC/logcat、绝对路径、token。

## 6. 失败与停止条件

- 判定器报 `POLICY_FILE_MISSING` / `POLICY_FILE_UNREADABLE`：先修 fixture，不进入判定。
- `GATE_UNCLEAR` 且无法在一手 policy 中消歧：停止，记录 `unclear`，交维护者。
- 需要修改设备 SELinux、pvmfw、root 或使用 `/system/bin/sh`：停止；不属于本 runbook。
- 设备取样不可用：只跑 AOSP fixture，结论写 `aosp-only`，不得写成设备已验证。

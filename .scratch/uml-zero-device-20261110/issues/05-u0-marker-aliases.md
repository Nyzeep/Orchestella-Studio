# 05: U0.1 — Boot timeline parser marker aliases

Type: implementation
Status: claimed（u0-aliases 会话，2026-11-10）
Priority: P1（跨会话证据可被 U0 解析）
Owner: u0-aliases 会话
Blocked by: U0/U1/U2 Wave 1 集成已 push（`integration/uml-zero-device-20261110` @ `001add7a`）
External gates: 无（纯 Python，零设备）

## 背景

Wave 1 跨测已证明 U1 fast kernel + U2 预烘焙 rootfs 能到 `T2_MOUNTS_READY` → `T3_READY` → `READY`，但 U0 parser 只识别：

- `BENCH boot_uptime_ms=`
- `UMARM_WORK_START ... uptime=`
- `BOOTPROBE ... uptime=`
- `UMARM_BOOT_OK guest_uptime=`
- `UMARM_SHELL_READY`

它**不识别** U1 的 `U1_READY uptime=`、U1/U2 的 `T2_MOUNTS_READY`、U2 的 `T3_READY uptime=` 与 `READY`。结果是跨测 T3 只能人工记，无法进入 U0 JSON/报告。

## 任务

1. 在 `tools/research/uml-boot-bench/parse_boot_log.py` 增加 marker alias：
   - `U1_READY uptime=<s>` → T3
   - `T2_MOUNTS_READY uptime=<s>` → T2
   - `T3_READY uptime=<s>` → T3
   - 裸 `READY`（仅当同一 run 有 U2 `T3_READY` 或 `INIT_ZERO_INSTALL` 前缀）→ T3，避免误吞其他 READY
2. 更新 fixtures：至少覆盖 U1 正常、U2 正常、裸 READY 歧义、缺 uptime、单位错误。
3. 更新 README 的 marker 表与 T2/T3 段落；明确这些 alias 的优先级低于现有 `BENCH`/`UMARM` marker。
4. 保持 `--self-test` 全绿；新增 checks 数写入报告。

## 边界

- 不改 U1/U2 的脚本；只扩展解析器与 fixtures。
- 不改变现有 marker 的优先级/单位；不猜缺失值。
- 不写产品代码、不改版本号/签名/发布通道。

## 完成判据

- `python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test` 全绿；
- 用 U1/U2 的真实（脱敏）日志或合成 fixture 输出 T2/T3；
- 裸 `READY` 不会被错误归到无关 lane；
- 敏感数据门通过；产品路径零改动。

## Comments

- 2026-11-10 / Wave 1 集成：建票。跨测 PASS，但 parser 不识别 U1/U2 marker；不阻塞 Wave 2，建议在 Wave 2/3 并行补。
- 2026-11-10 / u0-aliases 会话：claim；base `github/integration/uml-zero-device-20261110` @ `5836a7ae`；worktree `/workspace-wt/u0-aliases`。

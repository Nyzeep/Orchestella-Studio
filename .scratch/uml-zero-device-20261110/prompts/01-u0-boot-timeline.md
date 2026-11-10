# 01 · U0 Boot Timeline Harness · 开工 Prompt

你是 **U0 会话的实现者**。目标：把 UML / QEMU / proot 的启动日志统一成 T0–T5 口径，产出一个零设备可测的解析器 + 协议 + self-test。这是后续 U1/U2/U3 和 ACR 证据包的**口径底座**。

## 0. 必读（按顺序）

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/spec.md`（§2 指标定义、§3 U0）
3. `.scratch/uml-zero-device-20261110/issues/01-boot-timeline-harness.md`
4. `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`（启动分解与来源）
5. `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1
6. `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §5.1
7. `research/uml-runner/README.md`、`research/uml-runner/guest/um-command.sh`、`research/uml-fabric-probe/guest-overlay-uml/init`

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/u0 \
  -b feat/uml-u0-boot-timeline \
  github/research/uml-zero-device-direction-20261110
cd /workspace-wt/u0
```

- 只改本分支；不改 `main`；不合并；不 push 到 main。
- 不写产品代码；只动 `tools/research/uml-boot-bench/` 和必要的 docs/research 报告。

## 2. 范围

**做：**
- 定义 T0 spawn、T1 kernel init、T2 rootfs mounted、T3 first command、T4 service ready、T5 warm resume、P0 proot exec、Q0 QEMU boot。
- 写 `parse_boot_log.py`：解析 UML console、`uml-exit.txt`、QEMU serial、`dmesg`、`systemd-analyze`、proot 计时日志；输出 JSON + Markdown。
- `--self-test`：合成日志覆盖 UML/QEMU/proot 三种 lane，断言字段提取、缺失字段、单位换算、中位数。
- 用仓库内已脱敏的 UML 日志做只读回归，确认 0.22–0.40 s 量级。

**不做：**
- 不执行 adb、不依赖 Android SDK、不引入第三方 Python 包。
- 不改 `research/uml-runner` / `research/uml-fabric-probe` 现有脚本；只读格式。
- 不写产品代码、不改版本号/签名/发布通道。
- 不把 x86_64 参考数据外推为 ARM64 设备结论。

## 3. 工作流（按顺序）

1. **`/research`（研究子智能体，1 个）**
   - 只核实日志格式：`/proc/uptime` 精度、`systemd-analyze` 输出、kernel `printk.time`/`initcall_debug`、QEMU serial 的可用字段。
   - 一手来源优先（kernel docs、systemd docs、QEMU docs）；内部日志优先于外部。
   - 输出：字段表 + 缺失字段时应该补什么日志；不要写代码。
2. **`/tdd`（主会话）**
   - 先写 self-test/fixtures（红），再实现 parser（绿）。
   - 至少覆盖：UML 正常、UML 缺 `duration_ms`、QEMU 有/无 `systemd-analyze`、proot 无内核阶段、非法单位。
3. **`/code-review`（验证子智能体）**
   - 独立跑 `--self-test`、检查解析器是否“猜值”、检查输出单位/中位数/缺失字段语义。
   - 不得编辑文件；只报问题。
4. **提交前门**
   - `python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test`
   - `git diff --check`
   - 敏感数据门。

## 4. 子智能体分工

- **研究子智能体（1 个）**：核实日志格式与外部文档；输出来源清单；不写代码。
- **验证子智能体（1 个）**：独立跑 self-test + 审查 parser；不改文件。
- **主会话**：写协议/代码/测试、合并结论、提交。最多 2 个并发子智能体；不要让子智能体改同一文件。

## 5. 交付物

- `tools/research/uml-boot-bench/README.md`：T0–T5 协议、字段、单位、n/中位数、控温、禁止跨就绪点相除。
- `tools/research/uml-boot-bench/parse_boot_log.py`：Python 3 标准库，`--lane uml|qemu|proot`、`--format text|json`、`--self-test`。
- `tools/research/uml-boot-bench/fixtures/`：合成日志 + 脱敏真实日志摘录。
- `docs/research/2026-11-XX-uml-boot-timeline-harness.zh-CN.md`：方法、字段、回归结果、缺失项。

## 6. 验收（必须逐条跑）

1. `python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test` → 唯一 OK 串。
2. 对已有 UML 日志输出 T3 与 `duration_ms`，与 0.22–0.40 s 量级一致；缺失时输出 `unknown` 而不是猜。
3. `parse_boot_log.py` 无第三方依赖；`python3 -m py_compile` 通过。
4. README 的 T0–T5 定义可被 U1/U2/U3 直接引用。
5. `git diff --check` 干净；敏感数据门 `SENSITIVE_DATA_OK`。
6. `git diff --name-only github/research/uml-zero-device-direction-20261110..HEAD | grep -E '^(app/|runtime/|guest-assets/)'` 无输出。

## 7. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "feat(research): 增加 UML/QEMU/proot 启动时间线解析器"
git push -u github feat/uml-u0-boot-timeline
```

给协调者的 Handoff 必须包含：branch / base / head SHA / changed files / 验收命令与结果 / 子智能体结论 / not-tested / 风险 / 合并提示。

## 8. 停止条件

- 需要 adb/真机才能拿到日志 → 不阻塞；用已有脱敏日志 + fixtures，标 `not-tested`。
- 日志格式无法可靠解析 → 输出 `unknown` 并写清需要补的字段，不要猜。
- 需要改产品代码 → 停止，交给协调者/ACR。

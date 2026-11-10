# 04 · U3 Android-like Seccomp / Host Compatibility · 开工 Prompt

你是 **U3 会话的实现者**。目标：在宿主上用接近 Android App 域的 seccomp/no-new-privs 约束跑 host-arch UML，建立 `ubd`、`hostfs`、`vector`、`ncpus`、`mconsole` 等能力矩阵，找出结构性阻断，并给下个设备窗口列“必须验”的 syscall。

## 0. 必读（按顺序）

1. `/workspace/AGENTS.md`
2. `.scratch/uml-zero-device-20261110/spec.md`（§3 U3）
3. `.scratch/uml-zero-device-20261110/issues/04-seccomp-host-compat.md`
4. `.scratch/orchestella-uml-fabric/issues/03-selinux-seccomp-closure.md`
5. `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1、§4（seccomp=on 真机证据）
6. `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`（UML syscall 开销）
7. `https://github.com/Raymer8639/linux-um-arm64` README（SECCOMP/ptrace 模式）
8. AOSP `seccomp_filter.txt` / seccomp 策略源（`android.googlesource.com`）

## 1. 分支与工作区

```bash
git fetch github
mkdir -p /workspace-wt
git worktree add /workspace-wt/u3 \
  -b feat/uml-u3-seccomp-compat \
  github/research/uml-zero-device-direction-20261110
cd /workspace-wt/u3
```

- 只改本分支；不改 `main`；不合并；不 push 到 main。
- 不写产品代码；只动 `tools/research/uml-seccomp/`、必要的 `docs/research/`。

## 2. 范围

**做：**
- `profile/`：从 AOSP seccomp 策略抽取/生成的允许列表；按 arch/OS 版本标注来源。
- `run_uml_seccomp.sh`：以 `no_new_privs` + seccomp filter 启动 host-arch UML；支持 `--strace` / `--bpftrace` 采集。
- `matrix.py`：解析日志，输出能力矩阵（`pass` / `fail` / `unknown`）与 denied syscall 列表；带 `--self-test`。
- 覆盖：`ubd0` ext4、`hostfs`、`vector` 网络、`ncpus=2/4`、`mconsole`、`SIGCHLD`/`clone`/`mount`/`personality`/`setrlimit`。
- 报告：AOSP 来源版本、profile 生成方法、矩阵、denied syscall、设备窗口必须复验清单。

**不做：**
- 不改产品 seccomp 策略、不改 `app/src/main`。
- 宿主 profile 是近似，不是 Android 真值；所有 `pass` 不能代替设备 `PASS`。
- 不使用 root/内核模块；不用 ptrace 绕过 seccomp。
- 不写产品代码、不改版本号/签名/发布通道。

## 3. 工作流（按顺序）

1. **`/research`（研究子智能体，1 个）**
   - 核实：AOSP seccomp 策略的位置/版本、App 域允许列表、`SECCOMP_RET_*` 语义、UML 在 SECCOMP vs ptrace 模式下需要的 syscall。
   - 一手来源：AOSP `system/sepolicy`、`bionic`、`kernel/common` 的 seccomp 文档、Raymer README。
   - 输出：profile 生成方案 + 版本差异 + unknown 判定规则；不写代码。
2. **`/tdd`（主会话）**
   - 先写 `matrix.py` 的 self-test（pass/fail/unknown/缺失字段），再实现解析与矩阵。
3. **`/implement`（主会话）**
   - 写 `run_uml_seccomp.sh` 与 profile；跑 host-arch UML（如果宿主内核/二进制不可用，记录原因并交付可复现的 harness）。
4. **`/code-review`（验证子智能体）**
   - 独立跑 self-test、检查 profile 来源/版本、检查 `unknown` 是否都有设备入口。
   - 不改文件；只报问题。
5. **提交前门**：`git diff --check` + 敏感数据门。

## 4. 子智能体分工

- **研究子智能体（1 个）**：AOSP seccomp 一手来源与 profile 方案；不写代码。
- **验证子智能体（1 个）**：独立跑 harness/self-test、审阅矩阵；不改文件。
- **主会话**：写 profile/harness/matrix/报告、合并结论、提交。最多 2 个并发子智能体。

## 5. 交付物

- `tools/research/uml-seccomp/profile/`
- `tools/research/uml-seccomp/run_uml_seccomp.sh`
- `tools/research/uml-seccomp/matrix.py`（含 `--self-test`）
- `tools/research/uml-seccomp/fixtures/`
- `docs/research/2026-11-XX-uml-seccomp-host-compat.zh-CN.md`

## 6. 验收（必须逐条跑）

1. `python3 tools/research/uml-seccomp/matrix.py --self-test` 通过。
2. harness 能在 filter 下启动 host-arch UML，或明确记录宿主内核/二进制不支持的原因。
3. 矩阵每项有 `pass`/`fail`/`unknown`；`unknown` 必须有设备验证命令入口。
4. 报告列出与 AOSP 策略源的对应关系；profile 版本化。
5. `git diff --name-only ... | grep -E '^(app/|runtime/|guest-assets/)'` 无输出；敏感数据门通过。

## 7. 提交与 Handoff

```bash
env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  git -c commit.gpgsign=false commit -m "feat(research): 增加 Android-like seccomp 下 UML 兼容性矩阵"
git push -u github feat/uml-u3-seccomp-compat
```

Handoff 必须包含：branch/base/head、profile 版本/来源、矩阵摘要、denied syscall、unknown 的设备入口、not-tested、风险、合并提示。

## 8. 停止条件

- Android seccomp 按 arch/OS 版本不同 → profile 必须版本化；不要把 Android 14 的结论外推到 16。
- 宿主内核与 Android 内核差异 → 只用于筛 blocker，不作为设备结论。
- UML 使用未列出的 syscall → 记 `unknown`，不擅自加 allow 规则。
- 需要 Root/unlock/改 SELinux → 停止并记录。

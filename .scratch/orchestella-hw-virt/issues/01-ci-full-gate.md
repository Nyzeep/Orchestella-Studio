# 01: CI 全量门禁

Type: implementation
Status: resolved
Priority: P0
Owner: build/CI owner
Blocked by: None
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

AVF 最脆弱的代码（hidden API 反射、持久 VM 配置、1 vCPU 约束、生命周期）目前不在 PR 或 release 门禁里；一个回归可以变红而无人看见。本票把所有 runtime 模块、lint/assemble/16KB 和 AVF 的 JavaExec harness 变成真正的门。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §4、§5.2；现有 `.github/workflows/ci.yml:45`、`release.yml:97`、`qemu-android.yml:383,463`。

## What to build / 范围

1. `ci.yml` 与 `release.yml` 的 Gradle 检查列表补齐：`:runtime:contract:check`、`:runtime:instance:check`、`:runtime:engine-qemu:check`、`:runtime:engine-avf:check`、`:runtime:capability:check`、`:runtime:distro:check`、`:runtime:network:check`、`:app:testDebugUnitTest`。
2. AVF 的 JavaExec harness 结果可见：至少把每个任务的输出/退出码作为 CI artifact 或日志摘要；能让 PR 页面直接看出哪个 AVF 任务失败。若改 JUnit 成本过高，允许保留 JavaExec，但必须有 `check` 依赖和失败传播。
3. 把 `qemu-android.yml` 里已有的 lint/assemble/16KB/`zipalign -P 16` 检查提升到 PR/release 门；优先复用现有脚本/job，不要复制一套新逻辑。若完整 `qemu-android` 太重，可在 `ci.yml` 增加轻量 job，只跑这些检查。
4. 增加一个“模块覆盖”守卫：从 `settings.gradle.kts` 枚举 `runtime:*` 模块，检查 CI/release 的 Gradle 任务列表是否覆盖；缺失即失败。守卫本身要有测试或可本地运行的脚本。
5. 故意失败验证：在临时分支/本地用 `--tests` 或一个临时失败测试证明门会阻塞；把命令与结果写进报告，不提交临时失败测试。

## 行为验收（零设备）

- [ ] PR 上能看到并执行 `engine-avf`、`capability`、`distro`、`network` 四个模块的 check；故意失败时 job 红。
- [ ] release workflow 执行同一组检查；不是只跑 contract/instance/engine-qemu/app。
- [ ] PR 上执行 lint、assembleDebug、16KB/ELF 检查或等价的轻量 job；不再只在 `workflow_dispatch` 的 `qemu-android` 里。
- [ ] AVF JavaExec 任务的失败能让 CI 失败，且失败任务名/日志可见；不是“Gradle 成功但 harness 内部 exit 1 被吞”。
- [ ] 模块覆盖守卫能发现“新增 runtime 模块但没加 CI”的情况；有本地运行方式。
- [ ] 不降低现有检查、不 `-x test`、不关闭 lint、不把失败标成 skipped-success。

## 测试 seam 与 red 命令

- 先改 CI/守卫，制造一个缺失模块或临时失败，确认红；再补齐让它绿。
- 本地命令：
  ```bash
  ./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
    :runtime:engine-avf:check :runtime:capability:check :runtime:distro:check \
    :runtime:network:check :app:testDebugUnitTest --no-daemon --stacktrace
  ./gradlew :app:lintDebug :app:assembleDebug --no-daemon --stacktrace
  ```
- CI 改动的验证不能只靠 YAML 语法；至少用 `act`/本地脚本或一次真实 workflow_dispatch（若可用）证明 job 会跑。

## 失败、数据与兼容

- 失败要定位到模块/任务，不要只显示一个总 exit code。
- 不新增 secret、不上传 APK/用户数据/设备日志；artifact 只含测试输出摘要。
- 不改变测试语义；若某模块 check 当前本身失败，先记录失败原因，另开票修，不要静默排除。

## 迁移、回滚与依赖

- 纯 CI/构建配置，无产品数据迁移。
- 回滚：撤回 workflow/守卫改动即可；不回滚产品代码。
- 依赖：无新第三方 action；沿用仓库现有 action pin 策略。

## 证据与完成门

- 实施报告：`docs/reports/tickets/avf-zero-device-01/implementation-report.zh-CN.md`（docs ignored，按需 `git add -f`）。
- 报告包含：改动文件、CI run/本地等价证据、故意失败证据、未验证项（如 GitHub Actions 远端未跑要写明）。
- 完成 = 门禁绿 + 故意失败红 + 守卫有效 + 报告与代码一致。

## 不做

- 不写设备测试、不做真机验收、不改 QEMU/AVF 产品行为、不修各模块已有的功能失败（只负责让它们可见）。

---

Claimed by: Agent C / 2026-10-20T02:33:55Z

## Answer

- `ci.yml` / `release.yml` 的 Gradle 门补齐 `contract`、`instance`、`engine-qemu`、`engine-avf`、`capability`、`distro`、`network` 与 `:app:testDebugUnitTest`；同一组命令由 `tools/ci/run-gradle-gate.sh` 执行，保留完整日志和 `FAILED_TASKS` 摘要，两个 job 用 artifact 上传（`if: always()`）。
- 新增轻量 `android-package-gate`：`:app:lintDebug :app:assembleDebug` + `tools/ci/check-apk-16kb.sh`；release job 也执行同一组，并对 release APK 再跑一次。完整 QEMU payload 仍在 `qemu-android.yml`，其 ELF 步骤改为复用 `tools/ci/check-elf-16kb.sh`。
- 新增 `tools/ci/check_ci_module_coverage.py` 与 6 个 fixture 测试：从 `settings.gradle.kts` 枚举 `runtime:*`，检查两个 workflow 的 `:runtime:<module>:check` 覆盖、app 测试、AVF `avfAllTestTasks` 布线与已删除任务引用。实现前 8 项失败；实现后 44 passed / 0 failed，测试 6/6 通过。
- 故意失败已记录：临时 app 失败测试使 `:app:testDebugUnitTest` FAILED；临时 AVF harness 异常使 `:runtime:engine-avf:avfPlatformAccessTest` FAILED 并出现在摘要；两处临时改动均已删除/恢复，未提交。
- 全量门本地通过：`run-gradle-gate.sh` 的 8 个任务 BUILD SUCCESSFUL / exit 0；`:app:lintDebug :app:assembleDebug` BUILD SUCCESSFUL；`check-apk-16kb.sh` 输出 `APK_16KB_GATE_OK ... libs=2`。YAML 4 个文件解析通过。
- 未验：GitHub Actions 远端、`workflow_dispatch`、完整 `qemu-android` payload job、release 签名步骤；本机无 `act`/`actionlint`，未做远端 runner 模拟。

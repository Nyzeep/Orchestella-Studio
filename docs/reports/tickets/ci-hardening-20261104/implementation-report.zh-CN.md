# CI workflow 硬化轮实施报告（积压 P1-2 / P2-10 部分 / P2-11）

- 票号/标题：ci-hardening-20261104（Lane H）——积压清单 `docs/reports/quality-backlog-20261104.zh-CN.md` 的 P1-2、P2-10（部分）、P2-11
- 执行 session / 模型 / thinking：ZCode agent（Lane H）/ GLM-5.3-Flash / thinking
- 分支：`chore/ci-hardening-20261104`　基线 commit：`f366bbf`（main）
- 工作区：`D:/t/orch-lane-wf`（独立 git worktree，未触碰主检出）

## 0. 任务目标

1. **P1-2**：`release.yml` 中 sdkmanager 安装 `platforms;android-36` 等真实包的 `|| true` 是全仓唯一非 `--licenses` 的真实吞错，照 `fastlane-zero-device.yml` 的 `if ! ...; then ::error::...; exit 1` 范式改为显式失败（`--licenses` 的 `|| true` 保留）。
2. **P2-10（部分）**：全部 workflow 的 `uses:` 从 tag pin 到 commit SHA，SHA 后注释保留原版本号；逐个核对 SHA 确实来自官方仓库与对应 tag，解析不到的如实跳过。
3. **P2-11**：每个 workflow 顶部补 `concurrency`（group=workflow 名-分支表达式 + cancel-in-progress，已有则核对）；审计超长 job 的 `timeout-minutes`，360 分钟级拆成单步 timeout（总预算不变）并在报告列出。

边界约束：不修改 ci.yml 与两条 fastlane workflow 中 2026-11-04 加入的 `allowMissingPayload` 豁免逻辑；不触碰 `ci.yml`/`qemu-android.yml` 之外其他 lane 的文件（本 lane 独占 `.github/workflows/**`）；新增内容不含真实设备数据。

## 1. 实际完成

### 1.1 P1-2 release.yml 吞错改显式失败 ✅

- `release.yml` "Accept Android SDK licenses and install components" 步骤：安装行改为
  `if ! "$SDKMANAGER" "platforms;android-36" "build-tools;36.1.0" "ndk;27.0.12077973"; then echo "::error::sdkmanager could not install ..." exit 1; fi`，
  与 `fastlane-zero-device.yml` apk-bin-gate job 的既有范式逐字对齐（同一包集合）。
- `yes | "$SDKMANAGER" --licenses || true` 按要求保留不动。
- 改后全仓 `|| true` 仅剩 `--licenses` 接受许可一类有意吞错（release.yml、ci.yml×2、fastlane-zero-device.yml×3、fastlane-dv01b-zero-device.yml、qemu-android 无），与积压 P1-2 描述一致。

### 1.2 P2-10（部分）uses: 全量 pin 到 commit SHA ✅

解析与核对方法（每个 SHA 双重核对，禁止臆造）：

1. `gh api repos/<owner>/<repo>/commits/<tag>` 取得 tag 当前指向的 commit SHA；
2. 再用 `gh api repos/<owner>/<repo>/tags?per_page=100` 列出该仓库 tag，反查该 SHA 同时挂着 minor tag 与 major tag（证明 SHA 确属官方仓库的该版本）。

| owner/repo@tag | → commit SHA | 核对的 tag | workflow 内处数 |
|---|---|---|---|
| actions/checkout@v4 | `11d5960a326750d5838078e36cf38b85af677262` | v4.4.0 + v4 | 16 |
| actions/setup-java@v4 | `cf277c60eb25467037889841efdb72551f06f6c3` | v4.9.1 + v4 | 9 |
| actions/setup-python@v5 | `a26af69be951a213d495a4c3e4e4022e16d87065` | v5.6.0 + v5 | 2 |
| actions/upload-artifact@v4 | `ea165f8d65b6e75b540449e92b4886f43607fa02` | v4.6.2 + v4 | 4 |
| actions/download-artifact@v4 | `d3f86a106a0bac45b974a628896c90dbdf5c8093` | v4.3.0 + v4 | 1 |

处数按文件分布（新 pin 合计 **28 处**）：`ci.yml` 6、`release.yml` 6、`fastlane-zero-device.yml` 10、`fastlane-dv01b-zero-device.yml` 4、`sensitive-data-guard.yml` 2。每处 SHA 后附版本注释（如 `# v4.4.0`）供 dependabot/renovate 升版。

既有 pin 处理（**2 处**，非本次新 pin）：`guest-assets.yml` 原本已是 SHA pin，经同一方法核对——checkout `11bd71901bbe5b1630ceea73d27597364c9af683` = v4.2.2、upload-artifact `ea165f8d65b6e75b540449e92b4886f43607fa02` = v4.6.2——本次仅补版本注释，未改动其 SHA（checkout 停在 v4.2.2，落后于 v4 现值 v4.4.0，属后续升版决定，见 §7）。

跳过/无法 pin 的项（如实记录）：

- `qemu-android.yml`：**0 处 `uses:`**，无需 pin。该文件头部策略注释明确"不使用第三方 action"（五个 job 用 plain git fetch 触发 commit，lock 无 action pin）。
- 两个 devcontainers 容器镜像（`guest-assets.yml`、`qemu-android.yml` 的 `container.image`）已是 digest pin（`@sha256:3612b800...`），非 `uses:`，不在本项范围且已达标。
- **本地 action：无**；除此之外没有解析不到的 tag。

### 1.3 P2-11 concurrency + 超长 job timeout 拆分 ✅

**concurrency**（7 个 workflow 全覆盖核对）：

| workflow | 状态 |
|---|---|
| ci.yml | 已有（核对无误，未改） |
| fastlane-zero-device.yml | 已有（核对无误，未改） |
| release.yml | 本次新增 |
| fastlane-dv01b-zero-device.yml | 本次新增 |
| guest-assets.yml | 本次新增 |
| sensitive-data-guard.yml | 本次新增 |
| qemu-android.yml | 本次新增 |

统一格式：`group: "${{ github.workflow }}-${{ github.ref }}"` + `cancel-in-progress: true`（与 ci.yml 既有写法一致，满足 group=<workflow 名>-<分支表达式> 的要求）。对 release.yml/qemu-android.yml 这类 workflow_dispatch-only 工作流，同 ref 重复派发会取代在途 run。

**timeout-minutes 审计与拆分**（总预算全部不变；job 级 `timeout-minutes` 保留为外层上限）：

| job（qemu-android.yml） | job 预算 | 拆分后单步 timeout（分钟） | 单步合计 |
|---|---|---|---|
| apk-packaging-signature-tamper | 360 | Fetch 5 / Build QEMU payload 110 / Rebuild guest-assets base (A6) 95 / Consumer 两轮 60 / Assemble payload 5 / Provision JDK+SDK 15 / Android build 45 / Maven closure 10 / Emit record 5 / zipalign+apksigner 10 | 360 ✅ |
| native-build-two-clean | 240 | Fetch 10 / Provision pinned inputs 40 / Two clean builds 190 | 240 ✅ |
| elf-closure-16kb | 240 | Fetch 10 / Provision+build once 220 / ELF 检查 10 | 240 ✅ |

其余 job 审计结论（未改动）：`guest-assets.yml` verify=120（覆盖依赖安装+双轮构建+smoke，合理）；`qemu-android.yml` provenance-lock=60、gpl-delivery=30、arm64-device-gate=30；`release.yml` build-release=60 / publish-draft=10；`fastlane-dv01b` schema=15 / jvm-androidtest=40；`sensitive-data-guard`=15；`ci.yml` 45/60/5；`fastlane-zero-device` 20/30/45/60/45/30。均未达"超长"标准，无改动。

拆分依据（如实说明）：GitHub Actions API 中该 workflow 仅存 2026-09 的旧 job 结构（"native-build" 单 job，约 22 分钟）成功运行记录，现行 6-job 结构（含 360 分钟 job）无任何成功运行样本；docs 中亦无逐步骤耗时记录。单步数值为**预算类比**（同仓 `guest-assets.yml` verify=120 覆盖 kernel 级构建、`native-build-two-clean` 两次 QEMU 构建折算约 100 分钟/次），非实测值；已按"各重步预算不小于类比推算值"取整，第一步挂掉的是悬挂步骤而非正常构建。

### 1.4 重要过程发现（键名纠错）

初次实施时单步超时误用 `timeout:` 键。经 SchemaStore `github-workflow.json` 官方 schema（经 `gh api` 获取）核实，步骤级键名为 **`timeout-minutes`**（`step` 与 `normalJob` 定义均只含该键），裸 `timeout` 会被 GitHub 静默忽略。已修正 16 处并 amend 进 P2-11 commit。

## 2. 未完成 / 与预期偏差

- **P2-10 为部分完成**（与任务书一致）：积压 P2-10/P2-4 还包含 `gradle/verification-metadata.xml` 与 `dependencyLocking`，任务书明确不在本 lane 范围，未做。
- **未升版 guest-assets 的 checkout pin**（v4.2.2 → v4.4.0）：本次 scope 是"pin 而非升级"，静默升版会改变构建输入；留给后续 dependabot/renovate 决策。
- 其余无偏差。

## 3. 修改的模块

全部在 `.github/workflows/**`（本 lane 独占目录）：

| 文件 | 改动 |
|---|---|
| `release.yml` | P1-2 吞错改显式失败；concurrency；uses pin |
| `ci.yml` | uses pin（未触碰 allowMissingPayload 逻辑） |
| `fastlane-zero-device.yml` | uses pin（同上） |
| `fastlane-dv01b-zero-device.yml` | uses pin；concurrency |
| `sensitive-data-guard.yml` | uses pin；concurrency |
| `guest-assets.yml` | 既有 2 处 SHA pin 补版本注释；concurrency |
| `qemu-android.yml` | concurrency；三个超长 job 拆 16 处单步 `timeout-minutes` |

未触碰 forbidden：`docs/agent-handoff/**`、源码、`tools/**` 均未改动；允许范围外的 workflow 逻辑（含 `allowMissingPayload` 豁免）零改动。新增内容无真实设备数据。

## 4. 设计决策

1. **group 表达式沿用 ci.yml 既有惯例**（`${{ github.workflow }}-${{ github.ref }}`），而非硬编码 workflow 名字符串——改名时不需要同步维护，且满足"workflow 名-分支表达式"语义。
2. **job 级 timeout 保留 + 单步拆分**，而非下调 job 预算：外层上限兜底未知的新增步骤，单步断路器解决"悬挂步骤吃满 6 小时"的问题；总预算不变符合积压要求。
3. **guest-assets 既有 SHA 只加注释不升版**：把"升级"与"pin"两个决策分离，避免本 lane 静默改变构建输入。
4. **版本注释格式** `# vX.Y.Z`（同时保留解析到的 minor tag）：dependabot/renovate 以注释中的版本作为升版基线。

## 5. 测试与验证结果（真实输出）

- **YAML 解析**：7 个 workflow 全部 `python -c "import yaml; yaml.safe_load(...)"` 通过（输出 `YAML_OK` ×7）。
- **模块覆盖门禁**：`python tools/ci/check_ci_module_coverage.py` → `SUMMARY: 232 passed, 0 failed`（与基线一致，未破坏）。
- **零设备 workflow 校验器**：`python tools/fastlane-build/check_zero_device_workflow.py` → `WORKFLOW_OK`。
- **校验器自身单测**：`python tools/ci/test_check_ci_module_coverage.py` → `Ran 12 tests ... OK`。
- **相关单测套件**：`tools/ci` 37 tests OK（连续 3 次复跑 + 基线 stash 对照均 OK）；`tools/fastlane-build` 11 tests OK；`tools/qemu-tuning` OK。
- **单步 timeout 合计核对脚本**：三个 job `step_sum == job 预算 == 期望值`，无遗漏步骤（输出 `ok= True missing= []` ×3）。
- **concurrency 全覆盖核对**：7 文件输出 `CONCURRENCY ... OK` ×7。
- **残留 tag 核对**：`grep -n "@v[0-9]" .github/workflows/*.yml` 0 命中。
- **diff 自查**：逐文件过 `git diff`；P2-10 commit 为 6 文件 ±30 行纯 pin 替换，P2-11 为 5 文件 +51 行（concurrency 6 行×5 + 注释 9 行 + 16 行单步 timeout）。
- **一次性异常记录（非复现）**：首次运行 `tools/ci` discover 套件时出现一次 `Ran 77 tests ... FAILED (failures=3, errors=14)`，立即复跑 3 次 + stash 后在基线复跑均为 `Ran 37 tests ... OK`，目录检查无残留临时测试文件；判定为一次性环境异常（疑似前一条命令的临时状态），与本次 .yml 改动无关联。如实记录备查。
- 真实 GitHub Actions 运行验证：本次未推送、无法触发，标注为**未做**（不虚报）。

## 6. Benchmark / 实测数据

本票不涉及性能基准。相关实测查询：`gh api .../actions/workflows/{352679692,351913610}/runs` 显示 qemu-android 仅 2026-09-12/09-19 两次成功 run（单 job "native-build"，约 22 分钟）、guest-assets 近 5 次 run 均为早期失败的 verify job——此即 §1.3 "无现行 360 分钟 job 成功样本" 结论的数据来源。

## 7. 风险

1. **单步 timeout 预算未经真实运行校准**（§1.3 已说明依据）：若首次真实运行中某重步被单步 timeout 误杀，按本报告表格上调该步并等量下调另一重步（总预算 360/240/240 不变）。
2. **cancel-in-progress 对 dispatch-only 工作流**：release/qemu-android 同 ref 重复派发会取消在途长构建。构建可重复且积压明确要求 cancel-in-progress，接受；若维护者希望 release 构建排队不取消，可仅对 release.yml 改为 `cancel-in-progress: false`（一行改动）。
3. **SHA pin 后升级路径变窄**：action 升级需改 SHA。建议后续加 `.github/dependabot.yml`（`package-ecosystem: github-actions`）自动出 PR（积压 P2-4 的建议动作）。
4. **guest-assets checkout pin 落后**（v4.2.2 vs v4.4.0）：功能正常，仅供应链新鲜度问题。

## 8. 新发现（供 Orchestrator 分诊，未自行修改）

1. `qemu-android.yml:531` 的 arm64-device-gate 步骤 echo 文案内含真实设备标识与系统版本（具体内容不在本报告复述）。属既有内容、本次未触碰，但与"公开仓敏感红线/积压 P1-3 的设备数据口径冲突"同族，建议并入 P1-3 一并由维护者裁定收敛为别名口径。
2. GitHub Actions 步骤级超时键名为 `timeout-minutes`（schema 证据见 §1.4）——全仓既有 16 处步骤级超时均为本次新增，无存量误用；后续 agent 写单步 timeout 时注意勿再误用裸 `timeout`。
3. `python -I -m unittest discover -s tools/ci` 存在一次性计数异常现象（§5），建议后续若复现抓取 `-v` 输出定位（可能是某测试的临时产物落入 discover 起始目录）。

## 9. 对后续 ticket 的影响

- 依赖 action 升级的票：升版 = 把注释里的版本号与 SHA 一起换，`gh api repos/<owner>/<repo>/commits/<tag>` 解析新 SHA。
- qemu-android.yml 首次真实运行后：按 §7.1 回调单步预算。
- P2-4 后续会（verification-metadata.xml + dependencyLocking + dependabot 配置）：本报告 §1.2 的 pin 清单即其输入。

## 10. Commit / branch

分支：`chore/ci-hardening-20261104`（基线 `f366bbf`），共 3 个 commit（未 push）：

```
b594377 chore(ci): release.yml sdkmanager 包安装吞错改为显式失败（积压 P1-2）
63479f9 chore(ci): 全部 workflow 的 uses 从 tag pin 到 commit SHA 并保留版本注释（积压 P2-10）
0836c9d chore(ci): 5 个 workflow 补 concurrency，qemu-android 超长 job 拆单步 timeout（积压 P2-11）
```

（0836c9d 由 54072c1 因 §1.4 键名纠错 amend 而来，内容以前者为准。报告本体由后续 docs commit 入库。）

## 11. 越界与 Architecture Change Policy 检查

- 越界：无。改动全部限于任务书授予的 `.github/workflows/**`；未 push、未动主检出、未动 `allowMissingPayload` 逻辑、未提交任何真实设备数据。
- Architecture Change Policy：不触发（纯 CI 配置硬化，无架构面变化）。

## 12. Ticket 状态

**completed**。P1-2 完成、P2-10 的 workflow pin 子项完成（Gradle 锁定子项按任务书不在范围）、P2-11 完成；全部本地可验证门禁通过。

---

报告人：ZCode agent（Lane H）/ GLM-5.3-Flash ｜ 日期：2026-11-05 ｜ 耗时：本会话约 60 万 tokens / 约 1.5 小时

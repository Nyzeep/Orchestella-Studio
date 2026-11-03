# 12: T6 · ncpus 旋钮与 16KB stub 的产品通道核对（结论票）

Type: audit
Status: resolved（2026-11-02，零设备产品化轮）
Priority: P2
Owner: （待分配）
Blocked by: None
External gates: 无（只读 + 结论）

## 背景

prompt T6：「`ncpus=` 旋钮产品通道核对（批次 3 只修了 research 侧 Config/prepare.sh/README）；16KB stub 与产品装配的联动点核对」。

## 必须回答（只读核查，不改任何代码）

1. **`ncpus=` 旋钮**：全仓 grep `ncpus` / `--cpus`。分别回答：
   - research 侧（`research/uml-runner/`）三处（Config / prepare.sh / README）是否一致、有无残留 `--cpus`；
   - **产品侧是否存在任何 UML 启动通道**（`app/`、`runtime/`、`accel-extension/` 内 grep）——若为零，明确写「产品无 ncpus 通道可核对，因为产品未集成 UML 车道」，并给出证据（grep 命令 + 0 命中）。
2. **16KB stub**：定位 `libuml_stub.so` / `stub_exe-16k` 的产物与校验脚本（`tools/ci/check-elf-16kb.sh`、`check-16kb-kernel.sh`、`research/uml-kernel-build/`），回答：
   - 该 stub 是否进入任何产品构建（`app/build.gradle.kts` 的 jniLibs/assets sourceSets、`accel-extension`）；
   - 若不进入，「与产品装配的联动点」是否存在？若存在，指出 file:line（例如产品 QEMU 车道的 jniLibs 是否复用同一 16KB 对齐约定）；
   - `tools/ci/check-apk-16kb.sh` 对产品 APK 的 16KB 断言与 stub 的 16KB 断言是否同一判据（`PT_LOAD Align >= 16384`）。
3. **结论形态**：做 / 不做 + 依据。若判定「不做」，必须写清将来触发条件（例如「UML 车道进产品时，`ncpus` 需在 LaunchParameters 暴露并加 2 的默认值」）。

## 交付

- 结论写入本票 Comments（≤40 行）+ 在最终报告里作为一节。
- 若发现**真实不一致**（例如 research 侧仍有 `--cpus` 残留、或 stub 16KB 判据与 APK 判据冲突），升级为独立实现票，不在本票内改代码。

## Comments

- 2026-11-02 / 主会话：建票。
- 2026-11-02 / 主会话只读核对：**resolved（结论=不做）**。① `ncpus` 在 `app/`、`runtime/`、`accel-extension/` 的源码零命中，唯一命中是 `app/src/main/assets/Image`（内核镜像内的字符串，不是通道）→ 产品侧无 `ncpus` 通道可核对；research 侧三处（`RunnerActivity` 的 Config 注释与 `defaultArgs`、`prepare.sh`、`README` 三节）口径一致，`--cpus` 只出现在「禁止使用」的说明文字里，无残留调用。② `libuml*` / `stub_exe` 在 `app`、`accel-extension`、`runtime/*` 的 build 脚本零引用 → stub 不进产品构建，**联动点不存在**。③ 16KB 判据一致性已核：`research/uml-kernel-build/check-16kb-kernel.sh` 自述「复用 `tools/ci/check-elf-16kb.sh` 同口径」，产品侧 `tools/ci/check-apk-16kb.sh` 亦调用同一脚本（带 `REQUIRE_BUNDLED_CLOSURE=1`）→ 不冲突。**将来触发条件**：UML 车道进产品时，`ncpus` 需在 `LaunchParameters` 暴露且默认 2（手机宿主最优宽度）；stub 进产品时需新增 jniLibs 条目并复用同一 16KB 门。

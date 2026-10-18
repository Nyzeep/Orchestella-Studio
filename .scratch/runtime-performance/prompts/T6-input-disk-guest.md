你是 Orchestella 的实施会话，只负责工单 **T6 只读输入盘（guest 侧）**。

## 前置

- 阻塞于 **T5**。先确认 T5 已合入且没有标为 `deferred`；否则本票不启动。
- 基线：`perf/runtime-performance` 集成分支（含 T3、T5）。

## 工作区

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

- 分支 `perf/t6-input-disk-guest`；独立 worktree + 空闲 ASCII 映射。
- guest 构建/回归优先复用已有 WSL `Ubuntu-24.04` 与 `guest-assets/build.sh`，不要重装工具链。

## 先读

1. `AGENTS.md`
2. `.scratch/runtime-performance/spec.md`
3. `.scratch/runtime-performance/issues/06-input-disk-guest.md`
4. `guest-assets/initramfs/init`（`verify_rootfs_input`、`INPUT_DIR`、`provisioned-v1` 分支）
5. `guest-assets/initramfs/runtime-entry.sh`
6. `guest-assets/smoke/qemu-official-alpine-embedded-input-smoke.sh`
7. `docs/adr/0007-storage-model.md`、`docs/adr/0011-lifecycle.md`

## 目标

guest init 优先从第二个 virtio-blk 设备读取并校验 rootfs，embedded-input 保留为兜底：

- 只读设备上的 tarball 必须按 manifest 的 size + SHA-256 校验后才允许解包；
- 校验失败、设备缺失、格式不识别时 fail，不得写根盘；
- 已装配根盘的重复启动路径不变；
- `provisioning_version=1` 与 console marker 契约不变；
- 不改变 ADR-0007 的私有存储与 Export 边界。

## 边界

- 你拥有：`guest-assets/initramfs/*` 的输入选择逻辑、guest smoke 与回归。
- 不改宿主 Java；不改 QEMU 参数（T5 已定）；不改根盘格式。
- 不把 9p 加回来；不改变 marker 文本。

## TDD

1. 先扩展 smoke/fixture：只读设备路径、正常装配、篡改/截断失败、设备缺失回退 embedded-input。
2. 实现输入选择与校验顺序，保证失败发生在任何根盘写入之前。
3. 跑 guest 65 项回归与相关 smoke。
4. 有设备时跑一次真实首次装配；没有就写 `not-tested`。

## 命令

```bash
cd /x
# guest 构建/回归命令按 guest-assets/README.md 与 ci/run.sh 执行
./gradlew :app:testDebugUnitTest --offline   # 宿主回归
git diff --check
```

## 交付

- 提交到 `perf/t6-input-disk-guest`，不要合入 `main`。
- 在工单文件末尾追加 `## Comments`：提交 hash、guest smoke/回归结果、设备结果/未测项、风险。
- 最终回复：设备发现方式、校验顺序、回退条件、未测项。

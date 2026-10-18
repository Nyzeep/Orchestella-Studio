# 下一版发布说明（草案）：运行时启动 / 停止性能

> **Status**: draft · 未发布 · 版本待定  
> **基线**: `main a32b477`（运行时性能合并 `881e37a`）  
> **适用**: QEMU 全设备路径；AVF 不在此次声明范围内

## 亮点

- **重复启动不再重新打包整个发行版 rootfs**。已装配实例只嵌入约 60 KB 凭据段；Ubuntu 24.04 实测 initramfs `30,225,552 B → 1,327,092 B`，宿主 `INITRAMFS_BUILT` `1115 ms → 62 ms`（HONOR MEY-AN00 / Android 16 / 4 vCPU / 3656 MB）。
- **旧实例与证据缺失自动回退**。首次启动识别 guest provisioning marker 并持久化 evidence；移除 evidence 后实测回到 `30,225,552 B / 1005 ms`，不会误跳过 rootfs。
- **停止更快且可确认**。跳过逐转发 remote `unexpose`，1 s 硬 deadline；运行中强制停止 `CONFIRMED` 约 **102 ms**，无 `qemu`/`gvproxy` 孤儿；正常停止幂等（`ALREADY_STOPPED`）。
- **新增启动时间线**。每次启动写一行 `launch-timeline.jsonl`：stage、耗时、字节数、停止结果；不含 token、口令或按键。
- **QEMU 调优脚手架**。`QemuTuning` 默认值不变：`cortex-a57`、无 `tb-size`、root `cache=writeback`；设备矩阵未跑，不宣称性能提升。

## 验证

- app：105 suites / **823 tests** / 0 failures / 0 errors / 0 skipped
- contract 24；instance 15；engine-qemu 2；lint 0 error / 748 warning
- CI：PR #3 `Contract & Unit Tests` pass（3m33s）
- 真机 5 次启动：首次全量、两次快速路径、旧实例回退、首启诊断；首次基础环境最终 `status=ready` / `stage=verified`；停止后 `state=STOPPED`，无孤儿。

## 兼容与升级

- 已装配实例在升级后需要一次全量启动（旧元数据没有 evidence），之后走快速路径。
- 同包升级必须使用与已安装包相同的签名；当前公开 v0.4.5 为 Debug 证书，不能直接升级到生产签名包。
- 新 `applicationId` 方案需要导出 / 导入迁移；不卸载、不清数据。

## 已知限制

- Arch 829 MB 首次启动、T5/T6 只读输入盘未实现。
- T7 设备矩阵、64-forward 真实 gvproxy 窗口未测。
- 主包升级 / 数据迁移未验证（user 128 旧签名 code300 数据）。
- 首次基础环境在 TCG 下较慢；中断后会重试，实测 `attempt=4` 完成。

## 发布前置（当前均未满足）

- [ ] 生产 release keystore：`signingReport` 当前为 `Variant: release / Config: null / Store: null`，现有 APK 全为 Debug。
- [ ] 版本决策：`0.5.4 / 504`（同包）或 `0.6.0 / 600`（新包 / 迁移）。
- [ ] 主包升级或导出 / 导入迁移演练。
- [ ] GPL / NOTICE / 对应源码与 release workflow。
- [ ] Arch 首启与 64-forward / 停止窗口的设备证据；做不到的项必须在预览说明中标 `not-tested`。
- [ ] release-signed APK 的 `aapt2` / `apksigner` / SHA-256 校验。

## 拟定发布文案（精简版）

**Orchestella Studio vX.Y.Z — 启动更快、停止更可靠**

- 重复启动不再重打包发行版：Ubuntu initramfs 30.2 MB → 1.33 MB，宿主准备 1115 ms → 62 ms。
- 旧实例自动回退全量路径；运行中停止确认约 102 ms，无孤儿。
- 新增启动时间线，便于现场诊断。
- 升级前请阅读迁移说明；Debug 包不可用于公开分发。

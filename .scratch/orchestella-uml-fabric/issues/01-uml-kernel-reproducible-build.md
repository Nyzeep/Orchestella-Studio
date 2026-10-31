# 01: UML 内核可复现构建流水线（含 Haven 三坑 checklist）

Type: task
Status: resolved（2026-10-31，fabric-batch1）
Priority: P0（其余 UML 工单的事实底座）
Owner: （待分配会话）
Blocked by: None
External gates: 无（容器构建，零设备）

## 背景

WorkBuddy 调研（`docs/research/2026-10-31-uml-full-spectrum-research.zh-CN.md` §5 W0）确认：现用内核来自 Raymer8639 v0.3.0 release 资产，但 `zalexdev` HEAD 已编译失败、prebuilt 配置丢 `CONFIG_SMP`——「用他人 prebuilt」不可复现。本仓库会话 21/22 的全部真机证据建立在 7.2.0-rc4 资产上，必须把「我们能自己重建」做掉。

## 任务（对应 WorkBuddy S1/S2/S3）

- [ ] S1：pin `Raymer8639/linux-um-arm64` v0.3.0 的确切 commit + `linux-bionic` 与 `linux-bionic-smp` 的 defconfig + 构建脚本；在 arm64 Linux 容器中 `make ARCH=um SUBARCH=arm64` 构建成功；产物（内核 + stub）sha256 写入 manifest，并与本仓库已用的 `release-v0.3.0` 资产（会话 21 §5.3：`libuml_kernel.so` 9,648,496 B）比对，记录逐字节一致或差异。
- [ ] S2：`linux-bionic` vs `linux-bionic-smp` config diff——确认 SMP 是否只差 `CONFIG_SMP`/`NR_CPUS`，列出全部差异项；评估 Raymer「2/4 vCPU SMP 内核」在 app 域使用的就绪度（对照 mainline Kconfig 的 `UML_SUBARCH_SUPPORTS_SMP` x86-only 限制，说明下游 SMP 补丁的范围）。
- [ ] S3：把 Haven 的三条公开坑写成构建期检查项：① guest 内核阉割 `rseq`/`set_robust_list`（Haven 5.89.7：Android 沙箱 signal 31 静默死亡）；② `ubda` 的 `F_SETLK` 锁残留导致重启 panic（Haven#686）；③ 16 KB ELF 段对齐过本项目 `check-apk-16kb.sh`。产出一页 checklist，注明每条的机制假设（在内核侧阉割 vs 在启动器侧规避）。

## 交付

- `research/uml-kernel-build/`（构建脚本、defconfig、manifest、checklist）；
- `docs/research/2026-11-XX-uml-kernel-reproducible-build.md`（方法、diff 结论、与 v0.3.0 资产的比对表）；
- 不改产品代码、不改主构建。

## 停止条件

- 容器内无法构建成功且原因在下游补丁本身 → 记录失败点，改评估 drolin 的 7.2.12 线，不硬扛。

## Comments

- 2026-10-31 / CodeBuddy（ask-matt 轮）：建票。参考：WorkBuddy 报告 §2.2/§5 W0；会话 21 §5.3。
- 2026-10-31 / CodeBuddy（fabric-batch1 轮）：resolved。S1/S2/S3 全部完成，未命中停止条件。S1：x86_64 容器 + stock NDK r29 交叉编译两 profile 构建成功（UP≈10min/SMP≈5min，arm64 binfmt 可用但因 NDK 无 linux-arm64 host 工具链弃为主路径）；pin commit 169b17ff3f66d507cb137b770ceb1fd9f4547a32（tag um-arm64-v0.3.0）；stub 逐字节复现（731c48d5…，两 profile 一致）；内核与 release 差 1,040 B（0.011%/0.0078%），主因=release clang flavor（dev,+pgo,-bolt,-lto,-mlgo）非 stock NDK 可得，MANIFEST 漏记 flavor 属 release 溯源缺口；release 资产 IKCONFIG 提取为 pinned defconfig（UP 1734 行/SMP 2021 行），config 往返仅剩编译器探针行。S2：SMP ≠ 只差 CONFIG_SMP/NR_CPUS——316 项 diff（18删/23改/275增），实为「SMP+Docker」组合；mainline x86-only=arch/x86/um/Kconfig:16 select，下游=新增 arch/arm64/um/（62 文件）+generic SMP 件；app 域配置面就绪（uml-runner kernel 参数通道已在），默认 UP、SMP 显式 opt-in。S3：Haven 三坑全落检查项——①rseq/set_robust_list=内核侧 post-link neuter+扫描门必须成对；②ubda F_SETLK panic 机制在内核侧（file.c:583 逐字一致）、修复在启动器侧 teardown；③16KB 内核全过、stub release 原生不过（-z max-page-size=16384 重链或 gate 豁免需二选一）。交付：research/uml-kernel-build/（脚本/defconfig/manifest/checklist/s2-config-diff.txt/release 资产/out 产物）+ docs/research/2026-11-01-uml-kernel-reproducible-build.md。协调复核：release 资产与重建产物 sha256 独立重算全过；SMP 重建哈希按最终产物更正为 62342700…（原 manifest 记 d414b760… 为首跑遗留，大小与结论不变）。未验证项（设备窗口 4 项，文档 §6）：未 neuter 内核沙箱首启存活、16KB 重链 stub 功能等价、SMP app 域 2/4 vCPU+性能对照、teardown 重启回归；dev flavor 工具链公开渠道 [未找到证据] → 内核逐字节复现不可达（上游溯源缺口）。

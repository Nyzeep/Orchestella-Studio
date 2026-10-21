# 29: Fast Lane 密封 Linux toolbox（APK 内静态工具链 + encrypted home）

> **2026-10-21 拆分**：执行票已迁至 `.scratch/orchestella-fastlane/` 的 P02/P03/P04/P06 + **DV-02**。本文件保留历史定义，不再单独推进。

Type: prototype
Status: blocked-by-28
Priority: P0（Fast Lane 的产品形态门）
Owner: Fast Lane payload/toolchain owner
Blocked by: 28 真实 protected payload 通过
External gates: 同 ROM、同 QA 窗口、APK 体积预算、工具链许可审查

## 要回答的问题与价值

在 protected Microdroid 的 SELinux 边界内，能否提供一个用户可感知的 Linux 环境：

- shell + 常用工具 + 至少一个编译器；
- 工具从 `/mnt/apk` 执行，数据写 `/mnt/encryptedstore`；
- 终端通过 vsock 接到现有 App；
- 数据跨 stop/start/设备重启保留；
- 不依赖 chroot/proot/mount/自定义内核。

如果可以，Fast Lane 就不是“后台 native 服务”，而是“硬件加速的密封 Linux 工作区”。如果不行，Fast Lane 降级为 native compute payload，产品价值显著缩小。

## 允许/禁止范围

允许：

- 候选 A：Termux bootstrap（Bionic/`$PREFIX`），验证 bash/git/python/clang；候选 B：自建静态 busybox/git/python/gcc。两者都必须从 **APK 根 `bin/`** 运行；zipfuse 对 `bin/` 默认给 execute 位，`lib/` 不能假设可执行。payload 本身仍在 `lib/<ABI>`。
- Termux 的 proot **不可用**（SELinux ptrace），但 Termux 本体不依赖 proot；只验证非 proot 路径。
- payload 从 `/mnt/apk/bin/` 执行；`PATH`/`HOME`/`LD_LIBRARY_PATH` 指向 APK 挂载点与 encrypted storage。
- 若 AGP 不能直接产出根 `bin/`，用自定义打包任务在签名前注入；必须验证 APK 签名、`zipalign -P 16` 与安装/更新。
- PTY + vsock 多路复用；复用现有终端 session 抽象。
- encrypted storage 作为 `/home`、`/workspace`、包缓存。
- 只读检查/静态分析 + 真机最小执行。

禁止：

- 修改 SELinux/pvmfw/targetSdk；Root/unlock。
- 把工具或库写到 encrypted storage 后执行；不绕过 `MS_NOEXEC`/neverallow。
- 使用 chroot、proot、mount、overlayfs、loop、FUSE 作为“必须成功”的路径；它们只作为边界测试。
- 承诺 `apt install` 任意 native 包、Docker、systemd、原生网络、GPU、USB、音频。
- 把 Full Linux 的 rootfs/内核塞进 Fast Lane。

## 行为验收

- [ ] 从 `/mnt/apk/bin` 执行一个 shell（静态 busybox 或 Termux bash）：`sh -c 'echo FASTLANE_OK'` 成功。
- [ ] shell 可读写 `/mnt/encryptedstore/home`；重启 VM 后文件仍在。
- [ ] 至少一个编译工具可运行（Termux clang 或静态 gcc）：编译一个最小 C 程序，产物写入 encrypted storage；不要求在 Fast Lane 内执行产物。
- [ ] 至少一个脚本运行时可用（python 或 busybox awk）；脚本从 encrypted storage 读取并执行。
- [ ] 终端通过 vsock 双向交互；窗口 resize/EOF/停止可收敛。
- [ ] 边界测试有真实记录：
  - 从 encrypted storage 执行：预期 EACCES/EPERM；
  - `chroot`/`mount`/`unshare`：预期 EPERM；
  - `proot`：预期 SELinux ptrace 拒绝；
  - `memfd_create`+`fexecve`：允许/拒绝都记录；
  - 从 encrypted storage `mmap(PROT_EXEC)` 动态库：允许/拒绝都记录；
  - Node/JVM JIT（若打包）：允许/拒绝都记录。
- [ ] APK 体积增量有明确预算（建议 v1 起步 ≤80 MB；完整 Termux bootstrap 另做体积决策），超过则裁剪工具集并记录取舍。

## 关键实现问题

1. **APK 打包路径**：优先用 APK 根 `bin/`（zipfuse 的 `EXECUTABLE_FILE_MODE`）；`lib/<ABI>` 只放 payload `.so`，不依赖它的 exec 位。需要验证 AGP/自定义任务能否产出根 `bin/` 并保持签名/对齐。
2. **动态链接**：若工具是动态链接，PT_INTERP 必须指向 `/mnt/apk/...` 的 loader；`LD_LIBRARY_PATH` 必须只指向 APK 挂载点。优先静态链接，避免依赖。
3. **JIT**：Node/V8、JVM 可能需要 executable memory；若被 W^X 拒绝，Fast Lane 只支持 AOT 工作负载，能力矩阵必须写明。
4. **数据初始化**：首次运行创建目录、权限、版本 marker；升级不破坏用户数据。
5. **导出**：guest 侧 tar → vsock → SAF；host 不落 encrypted storage 明文。

## 测试接缝与证据

- 复用 28 的 protected payload controller；新增 toolchain manifest（名称/版本/SHA256/许可证/来源）。
- 证据根：`protected-fastlane/20261021/f2/`；包含：
  - APK 内工具清单与 SHA；
  - 每个工具的 `--version`/最小执行；
  - 数据持久化记录；
  - 边界测试的 errno/SELinux denial（脱敏）；
  - APK 体积前后对比；
  - 失败样本。
- 不使用“能启动 shell”代替“工具可执行/数据可持久”。

## 停止条件

- 无法从 `/mnt/apk` 执行任何静态工具：Fast Lane 降级为 native payload 或停止。
- 从 encrypted storage 执行/ mmap 被拒且无合法替代：只保留 sealed 工具，不承诺包管理。
- JIT 被拒：只做 AOT 工作负载；不把 Node/JVM 写进 v1 能力。
- APK 体积不可接受（>80MB 且无法裁剪）：改为可选模块的决策票，不静默膨胀主 APK。
- 任何“修复”需要修改 SELinux/pvmfw/Root：停止。

## 数据、许可与回滚

- 工具链逐项记录许可证（GPL/LGPL/Apache/MIT/BSD）与对应源码义务；GPL 工具不进 APK 除非接受分发义务。
- 只使用新 QA 实例/新 encrypted storage；不操作正式数据。
- 报告：`docs/reports/prototypes/fastlane-29/prototype-report.zh-CN.md`。

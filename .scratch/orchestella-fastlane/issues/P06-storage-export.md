# P06: Fast Lane 存储与导出/导入（零设备）

Type: implementation
Status: implemented（zero-device 验收通过；DV-01/DV-02 真机待验）
Priority: P1
Owner: storage owner
Blocked by: None（零设备）；真机由 DV-01/DV-02 验证
External gates: 无

## 目标

把 `/mnt/encryptedstore` 变成可用的持久 home/workspace，并提供 guest 侧导出/导入：

- 目录布局：`home/`、`workspace/`、`tmp/`、`cache/`、`exports/`；
- 首次运行初始化 + 版本 marker；
- 导出：guest tar（可选 gzip）→ vsock → 宿主 SAF；
- 导入反向；manifest（路径/大小/SHA256/时间）；
- host 不落 encrypted storage 明文；删除 VM = 数据不可恢复。

## 零设备范围

- 纯 JVM 流式 tar/hash/manifest + 内存/本地文件测试；
- 与 payload 的 export channel 协议 fixture；
- 不要求设备。

## 行为验收

- [ ] 目录布局/权限/版本 marker 初始化；重复初始化幂等。
- [ ] 路径穿越（`..`、绝对路径、symlink）拒绝；只导出允许根。
- [ ] 流式 tar 导出/导入；大文件/空文件/深目录/特殊字符；SHA256 一致。
- [ ] 中断/取消/磁盘满/权限失败：回滚/保留原数据；不产生半包。
- [ ] SAF 写入失败可恢复；不覆盖用户已有文件（或显式确认）。
- [ ] 不导出 token/密钥/临时 socket；不把明文落宿主。
- [ ] 删除 VM 的 UI 语义与后端一致；无“回收站”假承诺。

## 联网调研

- AOSP encrypted storage README/`setEncryptedStorageBytes`；
- 本仓 SAF 导出/导入实现与 fail-closed 语义；
- tar 流式格式/长路径/PAX headers；
- Termux `proot-distro backup` 的备份布局（仅参考）。

## 交付

- `FastLaneStorageLayout` + manifest；
- 宿主 `FastLaneExporter`/`Importer` + tests；
- guest `export_bridge` 接口（与 P02 协作）；
- 失败分类/恢复文档。

## 停止条件

- 无法保证 host 不落明文：停止产品化。
- 导入无法保持数据一致性：只做导出，导入另开决策。

## Comments

- 2026-10-21（Agent B，`fastlane/agent-b-dataplane`）：零设备实现完成。`runtime/fastlane-storage` 提供 `StorageLayout`、manifest JSON、ustar/PAX tar 读写、`FastLaneExporter`/`FastLaneImporter`、SAF `ExportSink`/`GuestImportSink` seam；JVM 26/26 测试通过（8 MiB/空文件/深目录/UTF-8/gzip/取消/失败回滚/不覆盖）。导入以 `home/workspace` 整树替换；真实 SAF 原子性与设备 staging 未验证。详见 `docs/reports/tickets/fastlane-P06/implementation-report.zh-CN.md`。
- 2026-10-21（Agent B，第二轮加固）：单 entry 上限改为 8 GiB−1（ustar 八进制硬限制），总量 16 GiB；八进制字段溢出不再静默截断。PAX 长度/大小溢出、零进度流、重复路径拒绝；`end()` 成功后 close 失败不再 abort 已提交导入；`FileExportSink` overwrite 采用备份→发布→失败恢复，测试注入第二次 move 失败验证旧文件保留；manifest 总量溢出与超大 marker 拒绝。JVM **35/35** 通过。导入语义修正为“只替换 tar 中出现的顶层根”，真实 SAF/ENOSPC 仍 `not-tested`。详见报告 §3/§6。

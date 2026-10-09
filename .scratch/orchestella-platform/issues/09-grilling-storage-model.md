# 存储模型：镜像格式、扩容、快照与 Android 存储集成

Labels: wayfinder:grilling
Type: grilling
Status: resolved
Blocked by: 08

## Question

持久层用什么：raw ext4 镜像 vs qcow2 vs 稀疏文件？扩容/快照/备份是否进 MVP？与 Android 存储（SAF/Downloads/私有目录）的边界是什么？

## Why it matters

存储是用户数据安全的根；格式决定性能与快照能力；Android app 私有存储的 FUSE 层对镜像 I/O 有真实影响（需实测，见 fog 中的 benchmark 项）。

## Known facts

- Podroid：raw ext4 + squashfs lower/upper overlay；全部 app 私有 filesDir；备份导出到 Downloads（01 票）。
- AVF/crosvm 对 qcow2 的支持程度不确定（需原型验证）；QEMU 原生支持 qcow2。
- 两后端共享同一存储契约可显著降低非对称 bug（Podroid 的教训）。

## Working recommendation

MVP：raw ext4 稀疏文件（两后端行为一致）+ 显式扩容工具 + 经 SAF 导出到用户选定位置的备份；快照/多盘不进 MVP。qcow2 仅在原型证明必要时引入。

## Reversibility

raw→qcow2 可转换（qemu-img），格式选择可逆性较好；快照机制后补容易。

## Answer

2026-09-05 用户采纳。固化于 **docs/adr/0007-storage-model.md** 与 CONTEXT.md 词条（根盘 / 导出）。

决定要点：
1. raw 稀疏镜像，两执行策略同格式；qcow2 不进 MVP（可后转，留后路）。
2. 单根盘：OS 装配进根盘、guest 内自升级；App 升级永不触碰实例内容（区别于 Podroid squashfs 分层模式）。
3. 扩容进 MVP（依赖桥接票的 guest 内 resize 通道）；快照明确非目标；SAF 导出备份进 MVP，导入恢复后续。
4. 全部私有存储 + Android 自动备份排除镜像目录。
5. 待原型：FUSE 之下私有目录随机写性能（QEMU 基线原型必测）——数据糟糕则本题选型重开。

# 存储模型：镜像格式、扩容、快照与 Android 存储集成

Labels: wayfinder:grilling
Type: grilling
Status: open
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

# 33: 实例导出备份与磁盘扩容

Type: implementation
Status: needs-triage

## What to build

用户可以把实例（根盘 + 元数据）导出备份到设备上任意位置（经系统存储访问框架，单向导出——导入恢复为后续，ADR-0007）；用户可以为实例扩容：扩大镜像文件后 guest 内自动在线扩文件系统（装配时预置自动扩容机制，无需新增桥接命令——设计取向，实现期可调）。

**Blocked by:** 28

## Acceptance criteria

- [ ] 导出产物包含根盘与实例元数据，校验和一致、大小合理
- [ ] 导出到用户选定的任意位置成功（权限路径验证）
- [ ] 扩容操作后，guest 内 `df` 反映新容量且既有数据完好
- [ ] 空间不足时创建/扩容有明确预检与错误反馈（稀疏文件 ENOSPC 行为，ADR-0007）

## Test strategy

扩容判定与预检 JVM 测试；导出完整性校验测试；真机手动验证（含大镜像耗时记录）。

## Prototype/research dependency

「QEMU TCG 基线」（18）的存储 I/O 数据用于扩容耗时预期；不阻塞本票。

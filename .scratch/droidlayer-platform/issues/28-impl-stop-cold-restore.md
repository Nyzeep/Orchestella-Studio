# 28: 优雅停止、冷恢复与异常标记

Type: implementation
Status: needs-triage

## What to build

用户可以优雅关闭实例（数据完整落盘）；应用被系统杀死后重新打开时一键冷恢复实例；VMM 异常退出时实例被标记"异常终止"并提供日志导出（ADR-0011 的落地）。桥接协议在此票扩展 `power` 命令（经 26 建立的基座）。

**Blocked by:** 26

## Acceptance criteria

- [ ] 优雅停止后根盘文件系统一致性检查通过（无脏退出痕迹）
- [ ] 强杀应用进程 → 重新打开 → 实例状态正确显示并可再次启动（冷恢复）
- [ ] 模拟 VMM 崩溃 → 实例标记"异常终止"，用户可导出诊断日志
- [ ] 实例状态跨应用重启持久且 UI 与实际一致

## Test strategy

实例状态机 JVM 测试扩展（含异常路径）；契约测试（停止/恢复语义）；真机强杀/崩溃注入冒烟。

## Prototype/research dependency

无。

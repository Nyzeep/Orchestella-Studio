# 31: 桥接命令补全——通知与打开链接

Type: implementation
Status: ready-for-review

## What to build

用户在 guest 内长任务完成后能收到 Android 通知；在 guest 内能触发在 Android 上打开链接（`notify` / `open-url` 命令，经 26 的桥接基座 + 28 的协议扩展模式实现）。此二命令进入暴露面白名单（ADR-0010 修订流程）。AVF 档对齐并入票 37。

**Blocked by:** 28

## Acceptance criteria

- [x] guest 内触发通知，Android 通知栏可见且点击行为合理
- [x] guest 内触发 open-url，Android 按默认应用打开链接
- [x] token 错误或未协商版本时命令被拒绝（白名单与鉴权一致）
- [x] 白名单/命令集文档修订完成（ADR-0009 版本流程）

## Test strategy

协议序列化/校验 JVM 测试（新增命令）；契约测试（命令语义）；真机冒烟。

## Prototype/research dependency

无。AVF 档对齐依赖 34/37（传输已具备后为纯命令实现）。

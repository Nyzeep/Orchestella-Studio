# 编排报告：Ticket 26 Gradle Wrapper 未获准网络尝试

- 背景 / scope：在 Ticket 26 的既有 guest-assets JVM/process 回归前，尝试使用
  `--offline` 和已隔离的 Ticket 26 Gradle user-home。此报告只记录该次工具 bootstrap
  行为及停止动作；不把它当作测试结果，也不改变 Ticket 26 scope。
- branch / 基线 / commit：`ticket/26-linux-qemu-rebuild` / `8859234a7cd922d4a99385874d1638f31e20c518` /
  当前 implementation 未提交改动。

## 实际完成

- Gradle Wrapper 在其 external user-home 没有本地 Gradle 8.13 distribution 时，忽略
  Gradle task 的 `--offline` 预期，尝试访问未获本次授权的公开 endpoint
  `https://services.gradle.org/distributions/gradle-8.13-bin.zip`。
- 发现后立即停止：先只读核验 downloader Java 与其 `cmd` parent 的 PID/名称，再只终止
  这两个已核验进程；没有重试、没有继续执行 Gradle task、没有运行测试。
- 停止后的本地最小证据：Ticket 26 approved external user-home 的 `wrapper/dists` 下仅存在
  0-byte `.part` 和 `.lck`，没有可用 distribution archive 或已解压 Gradle runtime。
  这只能证明本机未留下下载字节；不能据此推断外部网络层绝对没有收到请求。

## 未完成内容

- 更新后的 guest-assets JVM/process suite 未运行；本次没有有效测试 verdict。
- 未删除 0-byte Wrapper 残留。它们位于可识别的 Ticket 26 external temp 分类中，当前保留，
  等待与其它 Ticket 26 temporary output 一起按允许的 cleanup channel 处理。

## 修改文件

- 新增本报告。没有修改产品源码、ADR、spec、`CONTEXT.md`、tracker、依赖图或安全 policy。
- 仅在 approved external temporary Gradle root 创建了本次隔离 build init script 与上述
  0-byte Wrapper bootstrap 残留；没有写入 Git worktree 的 generated build/cache。

## 模型、委派、workflow 或 worktree 影响

- 无委派；仍为 Ticket 26 的串行 `implement + tdd` session。
- worktree、branch、HEAD 与 ownership 未改变。

## 测试与验证结果

- 有效 Gradle/JVM 测试：**未运行**。
- 下载停止核验：两个已识别 downloader 进程停止；本地 partial archive 为 0 byte。
- Android、APK、CI、供应链和真机验证：**未运行**。

## 风险与后续动作

- 风险：Gradle Wrapper distribution bootstrap 不受 task-level `--offline` 完整约束；未来若需要
  使用 Wrapper，必须先确认一个已验证的本地 distribution，或先以精确 endpoint、输出路径、
  hash、失败即停和 cleanup 计划取得用户授权。
- 不得使用本次 0-byte partial file，也不得将该 endpoint 视为已获持续授权。
- 后续可继续运行不依赖 Wrapper bootstrap 的现有 `javac`、Linux/QEMU harness 和静态检查；
  任何新的网络、下载、Android/NDK、APK 或设备写入仍须另行披露并获准。

## 越界与 Architecture Change Policy 检查

- 这次尝试触及了未获准网络 endpoint，已停止并如实记录；没有上传工作树、Git 历史、token、
  日志、路径清单或设备数据，且未采用下载物。
- 未触及产品语义、Full Linux Contract、桥接命令集、暴露面、生命周期、QEMU APK-contained
  原则或 accepted ADR，因此不触发 Architecture Change Policy。

报告人：Codex / GPT-5 ｜ 日期：2026-09-21 ｜ 耗时：进行中

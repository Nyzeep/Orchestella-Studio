# 成熟终端交互与会话品质

Type: research
Labels: wayfinder:research
Status: resolved
Assignee: terminal-worker
Parent: ../quality-20261013-map.md
Blocked by: none

## Question

以公开 Termux 源码为一手参考，识别本项目 IME/修饰键/Unicode/滚动选择/会话输入输出/设置持久化的真实断链，选择兼容现有原创终端的可验证修复，并明确完整 VT/PTY 能力的实际边界。

## Comments

2026-10-13：terminal-worker 独占终端 Java 与关联测试，保留主仓 Apache-2.0 零代码复制立场。用户要求研究成熟终端并实际提升舒适性；不声称未经测试的功能已达 Termux 完整水平。

## Answer

采用原创持久 VT 屏幕模型、唯一控制 reader、分包 UTF-8、统一 IME/快捷键与有界启动握手；真实视口通过已鉴权 RESIZE/TIOCSWINSZ 更新。正常输出基于明确 Bash OSC133 标记归档已提交命令，以高对比颜色和空行分段；备用屏幕保持原网格，编辑中的异步混流边界如实记录。修复长命令显示换行、历史淘汰锚点和重入状态。真机已观察命令分段、41列/键盘变化、top 与 nano 的真实运行及重新进入恢复，后续最终 APK 证据由实施报告记录。

证据：[终端研究与实现审计](../../../docs/research/2026-10-13-terminal-reference-survey.md)。

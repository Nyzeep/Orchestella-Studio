# 原型报告：隔离身份与API shape探针编译

- 日期：2026-10-15；执行者：Codex主协调/GPT-6.1；主仓main/428860ad9a1cb335f8be78c157b2ff57faf2a6da，未提交工作区。
- Scope：在研究报告与05工单后，将A自研source-only原型落canonical `prototypes/avf-shizuku-identity-probe/` 并验证AIDL/Java可编译。原型永不直接并入生产sourceSet/VM代理路径。

## 假设与实际完成

✅ 固定AIDL接口及Java服务可以用现有SDK36和JDK17编译。初始AIDL混用自动和显式id，编译真实exit1：`You must either assign id's to all methods or to none of them`。canonical将inspect明确赋0，保留destroy=16777114；重新aidl与javac均exit0，生成6个class。未改原A研究封板记录，其source-only未编译结论保留为历史事实。

⚠️ 这只证明语法与SDK类型闭包。没有完整QA APK/Manifest/UI工程，未打包/签名/安装/运行；不证明Shizuku启动、调用者身份、UID/domain、non-SDK可见或真实AVF可运行。capabilityBits=NOT_QUERIED，未实例化manager、打开磁盘或创建VM。

## 环境、步骤与证据

SDK：D:/AndroidSDK/build-tools/36.1.0/aidl.exe、platforms/android-36/framework.aidl/android.jar；JDK17.0.10 javac，source/target17与UTF8。编译采用已核W:/V: ASCII映射，输出外部批次：

- E/prototype-identity-compile/result.json与compile-0.log.txt：初次真实失败，含源SHA。
- E/prototype-identity-compile-green/result.json及两compile日志：修复后aidl/javac各exit0。
- 两批各自preservation即时双份。E为开发根Orchestella/artifacts/avf-shizuku-next-stage/20261015。

源码两文件从明确A原型复制，非上游代码/整个仓快照。只修AIDL inspect方法id。编译生成stub/classes不进源码目录，不新建.log。

## 边界、触发器与风险

接口仅正generation及固定snapshot，调用前验owner UID；无path/command/FD、无grant/VM操作/磁盘I/O。UserService destroy只能结束本隔离进程。设备门仍要独立QA包、server UID2000、非daemon、拒绝/撤回/death/旧generation负例。Class.forName不初始化的shape查询成功也不证明构造或create/run。平台Context/私有文件/owner属于05与ACR-01，不靠本次编译关闭。

没有生产/Guest/CI/ADR/policy/系统设置变化，没有SDK安装、设备操作、用户盘/凭据读取。无新增网络/IPC产品能力；不触发实际架构变更，已识别新owner门仍blocked。回滚仅移除canonical prototype，保留外部失败/成功证据与现有资产。源码为本项目自研，许可沿GPL-3.0-or-later；未分发APK/无对应源码发布动作。

## 状态与后续影响

编译验证completed；05真实身份/Context/FD实验保持blocked-device，ACR-01仍needs-architecture-decision。无commit/push/tag/发布；无VM/性能数据。未完成内容明确为完整QA装配和全部设备门，不能写成AVF通过。

报告人：Codex主协调 / GPT-6.1 ｜ 日期：2026-10-15 ｜ 耗时：未单独计量

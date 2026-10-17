# 中文成果与开发文档

主分支源码配置为 **0.5.3 / code503**；本次仅同步源码和文档，公开安装包仍为 [v0.4.5 开发预览版](https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.4.5)。源码同步不代表新版本安装包已发布，也不代表全部工单已完成。

## 当前交付与路线

- [0.5.3 限定实现、构建与真机验收报告](reports/orchestration/avf-shizuku-device-20261016.zh-CN.md)：747 项 App 测试、lint 无 Error/Fatal 且 748 Warning、8 项 Android 框架验收，以及独立 Debian QA 的 QEMU/systemd/日志与停止结果；原包产物和隔离包安装范围分开。
- [Protected 优先近期优化方案](reports/orchestration/protected-avf-near-term-plan-20261017.zh-CN.md)：protected 优先验证、non-protected 持续兼容、QEMU 可靠交付；阶段门、并行任务、停止条件与推广限制。
- [工单地图与依赖图](../.scratch/orchestella-avf-shizuku/map.md)、[规格](../.scratch/orchestella-avf-shizuku/spec.md)、[任务范围](../.scratch/orchestella-avf-shizuku/scope.md)。当前 28 份工单中包含研究、原型、实施和未决 ACR，**建立工单不等于实现完成**。
- [VM 所有权与 FD 边界 ACR-01](../.scratch/orchestella-avf-shizuku/issues/ACR-01-vm-ownership.md)、[保护意图与隔离语义 ACR-02](../.scratch/orchestella-avf-shizuku/issues/ACR-02-protection-semantics.md)：仍未决，本次没有新增生产 VM owner 或自动授予权限入口。

## 研究依据

- [Shizuku / AVF 综合研究](research/2026-10-15-avf-shizuku-upgrade.md)。
- [当前测试设备的 AVF / Gunyah 能力重检](research/2026-10-17-avf-device-recheck.md)：系统仅声明 protected，不能推导芯片完全没有硬件虚拟化。
- [Custom Linux、AOSP、ADB/vsock 与参考项目路线调研](research/2026-10-17-avf-custom-linux-route-study.md)：固定源码、教程与作者经验分层，protected 引导与 App 私有根盘仍需独立验证。
- 各票的 [实施报告](reports/tickets/) 与 [独立审查](reports/reviews/)。历史报告中的版本、未提交和未设备状态按当时快照理解，最新增补和任务地图说明当前边界。

真实本应用 **AVF Guest 成功样本仍为 0**，本次没有 AVF 性能提升声明。平台 feature、权限、模式声明、Microdroid、合成 FD 和 QEMU 验证都不能互相替代。

## 代码与验证入口

- [Guest 生成与严格字节校验](../guest-assets/scripts/sync-guest-init.py)：清洁检出先使用 `--prepare-resource`，再使用只读 `--check`，不要先覆盖 canonical 来掩盖漂移。
- [设备与样本工具说明](../tools/device-validation/README.avf-validation.zh-CN.md)：Python 3.12+；固定查询、受限路径、显式外部输出根。样本工具只校结构与关联，不认证设备物理真实性。
- [隔离 QA 原型](../prototypes/avf-shizuku-identity-probe/README.zh-CN.md)：研究组件和真实产品路径分开，QA 通过不签收原产品升级或 Full Linux 硬件运行。
- [本地构建](../README.md#本地编译与构建)。完整设备或 Linux fixture 使用独立外部工作目录，保护用户实例，不自动更改系统策略或重新安装工具链。

## 公开内容与本地证据约定

本次只跟踪经过范围审查的源码、测试、中文报告、规划与 tracker Markdown；其他开发者文档和临时资产继续默认忽略。请勿对 `docs/`、`.scratch/` 或项目外证据目录执行整体强制添加。

文档中的 `PROJECT_ROOT` 指开发根，`SOURCE_ROOT` 指活动源码检出，`LOCAL_EVIDENCE` / E / R / H / P 等为报告定义的本地证据别名。公开文本已去除具体开发者路径和设备标识。原始设备 receipt、截图/完整日志、ROM 指纹、签名材料、用户实例/根盘、embedded initrd、构建缓存及 APK 不在本次源码提交中；本地证据引用可能没有公开文件链接，这不代表已上传或可以推导新的通过结果。

部分历史治理/交接仍记录旧工作名、旧阶段或当时决策；本次公开用于解释约束与来源，不把历史建议自动改为新 accepted 架构。尤其 ADR-0010 关于 pVM 双向通信的旧绝对表述已发现与官方 vsock 证据冲突，见 [ACR-02](../.scratch/orchestella-avf-shizuku/issues/ACR-02-protection-semantics.md)；本次保留 accepted 正文和待裁定状态，不以公开同步代替架构批准。ADR 中旧镜像规格不代替当前固定清单与实际输入哈希。已知缺失文档的来源保全见[治理调查](../.scratch/orchestella-avf-shizuku/issues/16-governance-source-recovery.md)，不根据引用标题编造正文。

公开发布新的二进制需要另行执行版本、签名、完整对应源码、许可证/NOTICE 与验收门；本次提交和普通 push 不授权创建 Release 或上传安装包。

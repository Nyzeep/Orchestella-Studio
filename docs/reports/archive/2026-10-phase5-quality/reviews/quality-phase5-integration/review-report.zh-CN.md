# 品质与 Phase 5 合流终审汇总

- 日期：2026-10-14；协调者：Codex / new-provider/gpt-6.1-sol。
- Scope：双线代码合流、身份链、统一 guest producer/运行包、适用宿主与产物验证，以及第一方集成分支源码交付；另核公开源码与既有 APK 下载地址。
- 分支：`integration/quality-phase5`；工作树：`Orchestella_merge_dev`。
- 审查固定基底：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 最终生产源码提交：`6bbc31c29e97c0eba64122d22d9ea301d658d097`。
- 提交链：品质 foundation `3b637e48f51fb4b78282d468d9c78b37d582de87`；合流 `cfec895ca0bd0186b212f7c73d27b8d6bb06898c`；编辑器终审修复 `6bbc31c29e97c0eba64122d22d9ea301d658d097`。后续具名报告和 README 提交只包含文档与脱敏摘要，不改变被审生产源码。

## 独立审查结论

两条审查轴均为 **approve**，最终冻结源码没有未关闭的阻断 finding。

| 轴 | 实际结论 | 独立性与报告 |
| --- | --- | --- |
| 品质保全／协议生命周期 | Standards 通过，本轴 Spec 通过，未关闭 finding 0 | [standards-review](standards-review.zh-CN.md)。审查者不审自己编写的 guest input lock/producer，由另一轴独立核验。 |
| Phase5 数据与产物边界 | Spec approve，未关闭阻断 finding 0 | [spec-review](spec-review.zh-CN.md)。未参与实现，独立实比输入、ELF/cpio/APK和实际 XML，并复现/复核 profile 期限与显示/编辑器边界。 |

原真实问题均经源码修复与当前证据关闭：选择驱动瞬态 reauth 误退休、握手中终端重入、同模型 READ 锚点、QEMU 启动失败 gateway 遗留、未锁实际消费头文件、profile 后代持 stdout 的 deadline 失效、显示连接建立阶段 socket 所有权、singleTask 编辑器跨实例重入。未以零文本冲突、旧报告或文字降级代替修复。

## 最终实际验证与产物

完整结果见 [实施报告](../../orchestration/quality-phase5-integration.zh-CN.md)、[验证报告](../../orchestration/quality-phase5-integration-verification.zh-CN.md) 与 [小型摘要](../../../../artifacts/quality-phase5-integration/verification-summary.json)。

- 完整 App：663 tests，failure/error/skip 各 0；完整 profiles：39 tests，failure/error/skip 各 0。
- contract/instance/QEMU/AVF/network/distro/capability checks 通过；AVF 官方 shape/resource 和 Bridge response-pump main 实际执行。
- guest-assets：73 tests，failure/error 0、skip 2；Linux guest 合成/失败诊断/native-loader/manual recovery 分别 133/14/56/11 checks，exit 0、skip 0。
- POSIX stdin/testTCP 50/50、865 assertions；HELLO 14/14、POWER mock 24/24。显示/服务数据/relay/标准只读 9p 与同源 native 颜色适用门通过。
- lint 0 errors、688 warnings；ARM64 NDK、assembleDebug 和最终 APK 内容/许可证验证通过。
- 最终 APK：71969178 bytes，SHA-256 `e0ac730bd4e0441e6d9d347e7f25643f1ba9b0b00c4c8fa5a63474fdd8e7101a`，主 ID `io.github.nyzeep.orchestella`，code300 / `0.3.0-preview.1`，min26/target36。来源提交为 `6bbc31c2`。
- 统一静态 Linux AArch64 agent：477400 bytes，SHA-256 `6c6470872f2bc45c56729bb4d9848f0f0b5e559f79dc8b3e64e3ba4c643893e1`。47 输入锁含 39 个实际消费头文件；无动态解释器/动态段、栈不可执行。
- cpio 只更新唯一 agent，另 428 项 payload/mode 保全。七个 optional payload 与最终 canonical init/APK resource 相等。

上述批准只覆盖被审源码与宿主/构建产物，不批准设备功能验收或公开二进制 release。荣耀 MEY-AN00/API36 已预检，安装主包与候选证书不兼容；新包没有安装，设备运行回归 **NOT_RUN**，原应用和实例数据保留。桌面/IDE/WebView、OEM AVF、Android 9p/SAF/USB、GPU/zero-copy 的实验、未运行或未实现状态保留；七发行版长测保持取消。

## 公开访问问题

另一次独立只读核验确认规范仓库是 PUBLIC，匿名项目页面、main 源码 ZIP、v0.1.5 源码 ZIP 和应用 APK 地址均返回 200。匿名 REST API 的 403 为请求出口限流，不是仓库 404。此前 404 的具体旧 URL 或发生时间未获证据，不能编造原因。

README 已修正：公开应用发布版为 v0.1.5，给出明确 APK/tag 和无需 SSH key 的 HTTPS clone；`distro-catalog-v1` 是素材发布，不含应用 APK。合流候选 `0.3.0-preview.1` 尚未公开发布，不能将素材 latest 或开发版本标成现成应用安装包。

- 公开源码：https://github.com/Nyzeep/Orchestella-Studio
- 既有应用发布：https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.1.5
- 既有应用 APK：https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.1.5/Orchestella-Studio-v0.1.5.apk

原始核验仅保存在外部 `public-access/public-access-verification.json`；没有改变仓库 visibility、创建 release、上传 APK 或写 GitHub 评论。用户邮件原文和回信不纳入 Git；回信只由协调者在最终回复中单独输出。

## 提交与普通推送门

第一方 endpoint 已核定为 `git@github.com:Nyzeep/Orchestella-Studio.git`，GitHub API owner/name/PUBLIC/ADMIN 实核。目标为新 `integration/quality-phase5` 分支，普通 push，不 force、不覆盖 main、不发布 release、不向参考仓写入。SSH 22 的连接失败后，使用 GitHub 官方 SSH 443 端口和既有主机身份验证；不修改持久 remote、安全策略或凭据。

本报告在文档提交与推送前收口。实际 push exit、远端提交相等性和匿名分支访问结果写入批准外部根 `delivery/push-result.json`；完成后的交付状态由协调者最终回复给出。本段不提前宣称推送成功。

## 终态与边界

原两来源工作树/索引与 Class A 资产保留，未执行 reset/clean/stash。只精确 stage 具名源码、报告和小型脱敏摘要；大 binary、实例盘、签名材料、私有 QA、完整原始日志不入 Git。profile 本批 39 个具名 child 均已退役，固定 lock 为 0；没有设备临时服务/forward/lease，无后台七族队列。原失败证据保留，没有降低 `-Werror`、禁用测试或新增 lint baseline。

未触发新的 Architecture Change Policy；既有 ACR 保留。无对参考仓写入、无系统配置修改、无邮件发送或 GitHub 上的邮件正文。后续真实设备验收与公开 binary release 仍须分别取得实际证据和完成既有发布门。

报告人：Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-14 ｜ 耗时：未单独统计

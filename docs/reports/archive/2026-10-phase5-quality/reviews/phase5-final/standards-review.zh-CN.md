# Phase 5 最终 Standards 单轴审查

- 日期：2026-10-13；独立 reviewer：Codex。工作区：`Orchestella_phase5_dev`；分支 `phase5/graphics-productivity-storage`；HEAD `8d0e8c75d769ecc645ea102b90a304c8657344a1`，无新 commit。
- 对照：`git diff HEAD`、显式新增、`coordinator/evidence/production-dependency-baseline.json`/captured-before，排除terminal/theme；依据项目code-review、policy/spec。
- 覆盖：RFB/relay/profile、host、AVF/9p/build。**自有services/sharing不独立approve**，由Spec reviewer另审。

**request-changes：0 P1、1 P2；源码已修，升级未覆盖。**

1. **P2：NNP修复未覆盖旧unit升级。** 初始 `guest-assets/features/profilectl.py:521` 的code-server `NoNewPrivileges=yes` 继承IDE终端，阻断 `:557–560` 的setuid UID helpers。已反馈；当前SHA `822f0ec1d3210e350f9aadca90cd283ccb58cf7807d92f41a3a22be494c396c9` 删除主unit NNP，NIC保留，`B/implementation/final-editor-unit/profile-contracts.txt` 1项通过。但 `inputs.json:3` 仍units_version=1，`profilectl.py:1280` INSTALL短路保留schema1旧unit，START不重配；需版本/迁移负例。真实IDE/Podman未运行。

其余源码门未见新增阻断：`RfbProtocol.java:205` 分配前限4MP/16MiB、真实Raw覆盖；`display-relay.py:213,256,341` 专用token/FD与降权；`BridgeClient.java:73` 单reader/固定白名单；native GL/EGL失败、末帧重附着/generation已有修复。`Avf9pShareServer.java:129,253,350` 有界wire、pinned目录、EROFS，平台缺项拒绝。AVF hash `63a056ce46a1d099d7e6cef59245929c21601857e680fcc10921a5d145560973` 已锁外connect/回收晚到FD，既有finding不重报。

7份嵌入payload与源码一致；code-server size/SHA/MIT锁线保留，未审核整个第三方license闭包。USE_CUSTOM仅AVF前提，无自动grant、新USB/广域存储权限；所查增量未见host上传/secret读取/云tunnel/任意host执行/mock frame或GPU成功。

只读证据：APK round2的59 App tests/assemble、AVF worker19项通过；非review重跑，最终增量hash/build仍待锁线。Profiles summary保留25项全跑中1失败、定点修正且未重跑全套。真实设备/guest/AVF/9p/USB、WS/SW/cookie、GPU/性能均未验证。

仅写报告，无派生/生产/政策/tracker/设备/系统/网络/commit操作；待unit迁移、锁线；无架构变更。

**2026-10-13 定点复评：P2 CLOSED；最终 local approve，仅限已审源码。** 初评历史保留，结论由本段替代。

`inputs.json` SHA `93d264b8b5e5ce9ace62448a5cb2e40314e655805fc919833d97dcb0e7a5d668` 已units_version=2，旧schema1不再命中 `profilectl.py:1280` INSTALL短路；controller/source/test hash匹配 `coordinator/evidence/profile-units-v2-integration.json`，嵌入inputs/controller字节一致。只读 `B/implementation/unit-upgrade-{red,green}/profile-contracts.txt`：red真实复现旧短路失败，green 3项通过；`guest-assets/scripts/test-phase5-features.py:580` 验证旧NNP unit升级schema2、保留私有YAML/密码/cookie/input、重复INSTALL幂等，另覆盖foreign unit拒绝。未重跑全scope/测试/设备；自有data独立排除与所有guest/Android未运行门不变。

报告人：Codex / independent Standards reviewer ｜ 日期：2026-10-13 ｜ 耗时：未统计

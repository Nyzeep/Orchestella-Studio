# 25: 合法最小protected custom Guest启动可行性

Type: prototype
Status: blocked-by-protected-platform-evidence
Priority: P1（24真实门通过后）
Owner: protected平台/Guest原型owner
Blocked by: 24
External gates: 24得到同ROM可信pVM真实boot及有界收尾；合法自定义启动输入与明确QA窗口
Parent: 07的protected候选证据，不宣称07已通过

## 问题、价值与来源

在系统payload成功后，当前无Root ROM能否接受我们控制的最小Guest kernel/initrd，并给出可归因的early-init标记和正常退出？这比换整套Debian/U-Boot/桌面更早决定路线。来源：详细研究§3/5/7/9、A15QPR1 protected:false例子、A16文件label/firmware/QTVM差异和24实验结果。

## What to build / 范围

仅独立QA/prototype，复用24控制器/固定身份，锁定kernel/initrd与构建配置/hash/许可，优先检查当前共同kernel所需能力，不先升级pinned kernel。单CPU、满足微型Guest需要的RAM，禁network/shared/GPU/GDB，最小类型化挑战或串口固定marker/退出；若平台不支持某观测则unknown，不手造ready。

先核目标ROM可接受的合法输入来源/标签/boot和信任方式，区分API/label/pre-crosvm、hypervisor、firmware、kernel/init失败。固定AOSP只是参照；不能根据pVM位、AOSPtestkey/QTVM分支或他机Root记录默认可用。

禁止改全局hiddenAPI/SELinux/targetSdk/pvmfw.path/bootloader、Root/解锁、外来VMM或信任验证绕过；不用正式用户root盘、凭据或embedded生产载荷。公共shell QA测试文件仅实验范围，不能写成产品数据存储已满足。若现共同kernel不满足，不静默换版本：记录差异并由后续kernel决策处理。

## 正负验收

- [ ] 输入明确合法来源与完整hash，caller/context/实际平台入口绑定；资源和全部文件副作用列明。
- [ ] 真实custom Guest早期挑战/exit可归属当前session，不借Microdroid payload回调或系统其他VM作证。
- [ ] 无效/篡改输入、错误模式或权限、过期callback/epoch/超时/取消fail-closed，旧QA/用户资产不被覆盖。
- [ ] normal stop及24所有适用的flight/FD/lease收尾保持；失败底层未知时禁止重入/同盘QEMU。
- [ ] 若拒绝，给出最早实际stage/固定类别/可复查原件，准确标该ROM/版本，不推广为芯片物理不支持或所有protected不能Linux。
- [ ] 没网络/磁盘/服务的成功只签G2，不能标FullLinux产品/性能通过。

## 测试seam与red

先在24公共控制边界注入创建/验证/早期失败，再构建本票最小Guest；测试命令需prototypeREADME固定实际工具链、输入/hash、输出根与预期。真正设备红是最早有证拒绝，不强行制造破坏；源码模型green不替boot。旧 `:runtime:engine-avf:test`保留相关边界；本票无性能比较/目标增幅。

## 迁移、数据与回滚

无生产迁移，不操作正式VM namespace/根盘/身份。原型source放 `prototypes/protected-custom-boot/`，构建/镜像在外部25专属批次，必要GPL对象/boot输入记录完整来源/配置/许可证，后续公开需对应源码；本轮不发布。正常释放QA owner/FD并确认，保留原始失败与受控新QA文件，不清ClassA或用户数据。

报告 `docs/reports/prototypes/avf-shizuku-25/prototype-report.zh-CN.md`，review同id；结果pass_minimal_custom_boot/rejected_at_stage/blocked_external/stop_unconfirmed。当前只规划，真实设备/构建均未运行；通过后26实际数据门及ACR仍不能跳过。

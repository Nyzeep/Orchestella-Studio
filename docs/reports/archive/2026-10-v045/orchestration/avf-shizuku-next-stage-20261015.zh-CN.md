# 编排报告：AVF / Shizuku 研究、工单与本地升级交付

- 日期：2026-10-15；执行者：Codex主协调/GPT-6.1，四条研究/实施线与独立规范、需求两轴。
- 用户授权：依次完整读取两份交接，以第二份主代理Prompt执行基线→证据研究→spec/独立工单依赖图→无阻塞升级。未授权commit/push/tag/release，均未执行。
- 活动主仓：Orchestella_dev，唯一main/单worktree；初末HEAD均428860ad9a1cb335f8be78c157b2ff57faf2a6da。初始clean；最终36个明确任务变更文件保留未提交，含新增源码/工具/prototype，未覆盖用户后续无关改动。

## 实际交付

正式综合研究：`docs/research/2026-10-15-avf-shizuku-upgrade.md`，含证据分级、方案取舍、授权状态/身份/FD/owner、ROM/设备门、性能阶段/五假设、CI/当前缺陷、许可与ACR、实施补记。

本地effort：`.scratch/orchestella-avf-shizuku/`，有scope/map/spec、16张编号票与ACR-01，共17张独立工单及Mermaid依赖图。01–04本地实施完成，13本地集成完成；16历史治理来源保全完成；11公共seam局部red已复现，上层/设备门仍开。其他票按设备/架构/框架/测量保持blocked，不伪ready。

已实施：01清洁Guest生成/严格原bytes/幂等与失败零写；02双权限/UNKNOWN/完整ADB模板与诚实预检、刷新卡片并保留硬件值；03固定SDK普通Shizuku client、生命周期/请求代际/拒绝/death/timeout/官方引导与MIT入口；04固定只读设备探针与受限样本/receipt校验，不认证物理真实性。新增client触发MINOR0.5.0/code500。identity隔离prototype仅aidl/javac编译通过，未装配完整QA APK/设备运行。

新VM UserService/特权代理未进入生产；没有grant/exec、全局hiddenAPI/SELinux/targetSdk/thermal修改、Root/解锁、用户盘复制或目录放权。ACR-01与05身份/Context/FD门仍未决。

## 参考来源与下载

- Shizuku depth1 clone：b844bc491f1790c72328e1a8e5b2349f8978f0ea，开发根Shizuku。
- Shizuku-API depth1 clone：a27f6e4151ba7b39965ca47edb2bf0aeed7102e5，等于前者api gitlink，开发根Shizuku-API；未递归子模块/运行上游脚本。
- AOSP Virtualization android16-release官方GET固定c984fc337c11ca5edc03ccf02037b2455dd8fcaf；Git clone443失败，改有限源码导出，不冒称checkout。
- frameworks/base99b01a65cc4c104933788b3143285ab6bae65827、sepolicy d4a7f392598cee96d9479a8ac0f84259c19b043a；只读定点GET。
- 现有crosvm0e9c99daf64e6f5f57f416fa5cfb949f3199c48b、gfxstream07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe、qemu c3d48b7d1e89604920e5b81b91140c2ad39a1943、virglrenderer c8e3fc3bd2f367d92fb1027061fe51afeec5136e均只读复用、终态clean。

完整remote/读过符号/逐文件许可/NOTICE/下载响应字节/失败记录分别在E/research-auth/reference-manifest.json与research-performance/reference-manifest.json。Git逻辑字节不冒充wire bytes，AOSP源码版本不冒充目标ROM。新增产品依赖仅官方MavenCentral api/provider及aidl/shared13.1.5实际AAR，MIT许可已入APK/About可达。

## 实际测试与独立审查

| 门 | 实际结果 | 证据（E为外部本批） |
| --- | --- | --- |
| runtime contract / AVF / QEMU | 56任务全部执行exit0，AVF20 JavaExec入口全部运行 | evidence/gradle-runtime-contract-avf.log.txt |
| Guest完整运行时 | 65 checks通过 | evidence/guest-runtime-final.log.txt |
| 新sync / 既有依赖门 | 10 tests / 6 checks通过 | evidence/guest-sync-final.log.txt、guest-builder-python-final.log.txt |
| 工具修复后 | 100 unittest通过；fake adb/合成数据 | evidence/validation-tools-reviewed.log.txt |
| App/capability离线终验 | App715 tests/96 suites，fail/error/skip均0；client八场景真实入口执行 | evidence/gradle-reviewed-client-offline.log.txt、integration/client-reviewed-verification.json |
| Android lint | 无Error/Fatal，747Warning | 同批build/app/reports/lint-results-debug.xml |
| SDK实际解析 | 四模块13.1.5字节数/完整SHA记录，离线复验相同 | integration/client-checks-first-pass.json及Gradle provenance |
| 新APK | assemble exit0/54s，同证书、版本500/0.5.0，资源/许可字节正确 | integration/final-v050-apk-verification.json、apk-license-and-inputs.json |
| 两轴审查 | 01/02/03/04/13最终均approve，无余留P1/P2 | docs/reports/reviews/avf-shizuku-*/、E/review-{standards,spec}/ |

审查三项不同P2已闭合：工具低Python版junction假放行（支持地板现3.12+且缺能力拒绝）；向导授权后旧卡片（同次facts刷新、不动硬件）；client读回grant后旧timeout覆盖（退役pending/deadline）。每项原失败/源码hash/red→green/独立复验保留，不用套件通过抹掉发现。研究96/工具初版99/最终100、初轮App714/最终715不累加虚增覆盖。

## 本地APK与真实性边界

交付：E/delivery/Orchestella-Studio-v0.5.0-local-validation.apk，106506176字节，SHA256 `500643f1305bf01267ed76bb5bbdd17f408957ddf7c27e24f73e383f50a2583c`。有效签名证书SHA256 `3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06`，与公开405一致。guest_init唯一233538B==canonical，MITnotice唯一1531B==source且四模块全文齐；Image/baseinitrd/输入manifest与405逐bytes相同。旧405整包SHA仍b280472dcec24e941b893eacb4eadfa99306aff6ad287c8db2d209272277b541。独立reviewer另做hash/ZIP/signature核验。

未安装新APK；本轮adb零设备，真实AVF成功/性能样本0。403/a06d29b两权限+1CPU在hiddenAPI拒绝后QEMU的历史证据原样继承，405 QEMU验证不充当AVF。未进行真实Shizuku配对/授权/撤回/重启/OEM、AVF create/run/Guest/stop、GPU/输入键盘/系统recreate、Actions/Guest kernel/build/smoke或发布。工具结构接收不认证设备真伪。

## 文件、资产与保全终态

E=`LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/`。closeout/delivery-manifest.json精确列本任务36变更文件与33份新研究/tracker/report（含本报告）的bytes/SHA；只定点保全本任务文件，不打包整仓/.git/凭据/根盘，不外发。每份原始证据即时preservation，新.log证据0。研究/refs各自结束clean，主仓改动白名单核验无意外路径、git diff --check通过。签名秘密未读取/归档。

SDK/NDK/JDK、Gradle缓存、WSL Ubuntu-24.04、kernel/rootfs、旧发布APK/对应源码和设备证据均保全；W:/V:映射未改，无系统安装/服务/注册表/防火墙变化。已有合法依赖缓存复用，只为固定SDK依赖补解析，未清缓存或重复大规模clone。所有本地docs/.scratch仍ignored，需用本地closeout交接，不能依赖GitHub fresh clone带它们。

## 风险、阻塞与回滚

真实硬件路线最先要05证明UserService identity/API/Context/合成FD，再由用户就VM owner/有限IPC/App死亡收敛裁定ACR-01。客户端可用不等VM代理批准。07要owned customLinux认证通道/命令/stop；09才可同设备同资源建基线；10只改已测单瓶颈。缺这些门不做性能承诺。

11局部运行反例证明afterGuestExit公共seam缺代际保护，但Android可达顺序/registry/非零退出等仍未运行，未把它包装成已发生设备bug或未经票据全面修复。12/14/15保持受控复现/框架/设备门。

回滚仅定点本轮源码/依赖/文案，不reset/clean工作树、不清实例/权限、不删ClassA或发布物；保留原失败和保全。没有实施新的权限/VMowner架构，ACR仅提出并冻结相关票。最终交付是本地可构建准备升级、证据研究与可执行工单，公开发布仍v0.4.5，未提交/推送/发布。

报告人：Codex主协调 / GPT-6.1 ｜ 日期：2026-10-15 ｜ 耗时：未可靠计量

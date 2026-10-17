# AVF / Shizuku 独立 QA 身份与合成能力探针

Owner：Worker A / 主协调，2026-10-16。固定独立包 `io.github.nyzeep.orchestella.researchauth`，仅 prototype，不在生产 App sourceSet 中，不直接合入产品 VM 路径。

2026-10-15 source-only 与 compile-only 历史保留于 `docs/reports/prototypes/avf-shizuku-identity-probe/prototype-report.zh-CN.md`。本轮实际进展的 canonical 报告为 `docs/reports/prototypes/avf-shizuku-05/prototype-report.zh-CN.md` 和06同路径，不以本文替代逐门设备结论。

## 当前固定边界

- 官方 api/provider/aidl/shared13.1.5（MIT），不嵌入管理器/server。独立 Manifest 精确 provider、manager queries；无 INTERNET、无业务广播/Service 导出或任意 Intent 命令参数。完整 MIT notice 随 QA assets 打包。
- UserService 仅接受 shell UID2000，真实 Binder caller 必须是当前 user0 的 QA owner，协议1和递增正 epoch；旧/重复/end/destroy 后请求拒绝。显式 daemon(false)，remove=true，保留 destroy transaction16777114。不存在生产 VM owner。
- 固定 identity/shape：App 与 UserService 分别取 UID/PID/domain、服务自身两权限和 Context metadata；shape lookup 不初始化 AVF 类。初始身份快照不开放文件；后续合成步骤独立报告副作用。
- 固定 Context/state：真实 Context dataDir 下全新 QA namespace，0700；固定0600/O_EXCL/O_NOFOLLOW哨兵，不造 Context、不改目录权限、不使用 public 副本或用户实例。
- 固定合成 FD：仅两个 RO/RW PFD role，32B 自有哨兵，metadata/owner/mode/sentinel/dev:ino 及重放检查，固定末字节 I/O，receiver finally 关闭副本；无任意 path/command。类型/errno/stage与effect ledger保留前序事实，不输出异常原文/路径/secret。
- QA3 权限实验仅固定 QA 包/user0 的 MANAGE/CUSTOM 两 enum，真实 shell PackageManager development API、requested/fixed flags/资格检查、逐项 readback；可见选择和同一 live session 恢复确认新增。服务死亡后内存 ledger 不可恢复，UNKNOWN 交主协调依事前证据处置，不自动撤预先授权。
- QA3 manager 查询只限固定 constructor/getCapabilities 和常量，实际 bits/错误分类分别取证，无 create/load/run/stop/delete。固定 AOSP pin这两个方法无spawn，目标ROM进程前后仍需实核；capability声明不等 AVF 可运行。
- 全进程单 in-flight Binder call，5秒 UI deadline只退役结果，不称底层取消；client request 30秒无自动重试。每次 submit 独立 operation token 与 deadline，正常完成只移除自己的 timer；旧 timer/结果不清后续 busy/session。全局 IN_FLIGHT 只在底层真实返回的 finally 释放。开启 permissionSession 只在主线程当前 token/epoch/可见性通过后执行，截止、end、death或旧Activity的迟到snapshot不能重开会话。旧Activity/连接回调按可见生命周期、epoch、对象身份拒绝。

## 构建与设备职责

`python scripts/build_qa.py --label <唯一标签> --gradle-cache <dev.rikka.shizuku缓存绝对目录>` 仅 AIDL/javac compile-only；也可通过 `ORCHESTELLA_SHIZUKU_CACHE` 提供同一目录。缓存必须预先存在并包含四个固定 13.1.5 AAR；缺失、相对路径或不完整输入会在创建输出前拒绝，runner 不猜测用户目录或联网补包。APK构建需主协调显式提供 `--apk --version-code ... --version-name ...`，不含签名或安装。现有SDK36/Build-Tools36.1.0/JDK17和缓存只读复用；W:/V:必须由主协调建立且 samefile 到主仓/外部资源根，runner不改映射、不访问网络、不写C:。

本地缓存配置回归：将 `QA_CACHE_TEST_ROOT` 设为明确的绝对外部测试目录后，运行 `python -B tests/test_build_cache_config.py`。用例仅创建合成缓存，验证固定输入和缺失输入早停，不构建或运行 VM。

默认输出为本批 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/research-auth-device/`；本轮 QA4 用 `--output-root <batch-01/resume-01/qa-deadline-fix绝对路径>`，runner只接受这两个批准根，保全同步至resume-01/preservation/qa-deadline-fix。新 label 不覆盖旧目录；执行前把 src/Manifest/runner/notice 原bytes即时双份保存，再仅编译 snapshot。每条输出用 .log.txt/json 等并即时 preservation。QA1 三份原源码保全缺口已单独审计，不能用后续源码或反编译冒充。

QA1/QA2/QA3及QA4 的版本、签名、APK、设备安装/UI/grant/death/进程查询由主协调唯一执行，Worker只交固定动作包。旧动作原件见本批A根 `identity-v1-actions.zh-CN.md`、`identity-v2-actions.zh-CN.md`、`identity-v3-actions.zh-CN.md`；QA4见resume-01/qa-deadline-fix/identity-v4-actions.zh-CN.md。主协调指定QA4 code4/0.1.3-identity，仅通过runner参数传入，未写入生产版本。未知仍未知；所有 VM/Guest/性能门独立，当前不因 identity/FD/permission/构建绿声称 AVF 成功。

## QA4 deadline 局部回归与冻结

`python tests/run_activity_deadline.py --label <唯一标签>` 编译原样ProbeActivity、ProbeConnectionEvents、ProbeSession、FixedPermissionPlan；测试替身仅提供Android主队列/时钟/视图与远端Binder依赖，不复制submit实现。通过真实按钮、onServiceConnected、onStop及death listener驱动。可用`--source-root <qa-deadline-fix/before-qa3/source绝对路径>`复现保全的QA3，或`<qa-deadline-fix/source绝对路径>`复验冻结QA4；所有生成物和原始输出均写外部批准根，逐项双份保全。

最终相同harness的activity-deadline-red-02：javac0，旧QA3九场景中八项失败；其中grant/readback/restore均明确出现stale A timer retired active B permission session，当前deadline控制保持PASS。activity-deadline-green-02：javac0，九场景110断言全部PASS，包括B结果与restore、旧结果重放、当前截止保留全局flight至真实返回、迟到snapshot/end/death与旧Activity。qa4-compile-01的AIDL/javac均0。首轮red01/green01原件保留；green01两项失败源于测试替身未把已派发timer标为退役，修正替身后在完全相同测试字节上重新运行red02/green02，未改断言绕过问题。

这些是实际Activity源码加合成框架/Binder的JVM回归，未执行真实Android deadline/death或权限系统变更。新冻结清单identity-v4-frozen-source.json、源码双份快照与QA3→QA4 patch在resume-01/qa-deadline-fix及对应preservation；封存后停止QA源码写入，待独立双轴复审。QA1原三源缺口、旧seal、旧APK源码与全部首审原件保留。

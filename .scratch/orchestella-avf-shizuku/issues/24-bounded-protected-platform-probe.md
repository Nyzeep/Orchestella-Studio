# 24: 有界控制下的protected平台payload启动与停止验证

Type: prototype
Status: ready-for-prototype（先离线控制门，真机步骤依设备窗口）
Priority: P1
Owner: protected原型owner；设备唯一操作者主协调
Blocked by: None
External gates: 可用目标设备、合法身份/权限、机主必要确认；尚未执行
Parent: 05/08研究输入与近期G1；非新生产owner实现

## 要回答的问题与价值

当前手机仅声明protected，能否真正完成系统可信最小payload的create/start/挑战/高位vsock/正常停止，并在失败/取消时准确界定底层终态？先证明这条最小硬件链，避免直接重构FullLinux或用capability宣称成功。来源：本轮设备重检、详细路线研究§5/9、近期方案G1；已有vm run-microdroid入口可能无期限wait、hostadb死亡不保证remote终止。

## 允许/禁止范围

独立QA/prototype控制程序，固定平台可信Microdroid/payload，最小已验证资源、单CPU，无网络/共享/GPU/GDB，不读用户根盘/embedded秘密。先具备控制句柄/请求代际/真实stop与FD收尾，再启Guest。选用受支持typed入口或有限固定平台客户端，入口和副作用须明确；不暴露通用shell/path/任意Binder执行接口。

禁止将prototype并入生产owner，禁止Root/解锁/全局hiddenAPI/SELinux/targetSdk/pvmfw修改、直接运行未知VMM/下载脚本，禁止只包host timeout然后声称VM已停。必要系统确认由机主，拒绝即停；既有调试授权内的常规QA动作按主协调窗口执行，不重复泛求许可。

## 行为验收

- [ ] 源码/构建输入/QA包/签名/ROM/APEX/callerUID-domain精确绑定；固定操作版本/epoch/实例alias，测试盘/state独立，不覆盖用户资产。
- [ ] 离线先red→green：成功、create失败、callback迟到/错代、单flight、取消、deadline、底层不响应、正常stop/错误stop与FD关闭；caller完成和底层结束两状态可区分。
- [ ] 真机实际create/run归属、payload固定挑战与合适高位vsock交互可查，系统已有qcrosvm/Microdroid不借用作成功；只支持声明仍记not_run。
- [ ] 正常stop后证实目标VM/owner/FD资源收敛；重复stop/death/取消按明确QA场景验，不以end epoch/US消失/EOF代真实VM停止。
- [ ] 停止未确认时结果STOP_UNCONFIRMED，保留盘lease、拒重入/第二在途，不把杀host adb或Future.cancel当底层已停止。
- [ ] 失败/无权限/不支持/锁屏或系统确认阻塞保存固定stage/reason和已发生副作用，不无限重试，不顺手grant未知权限。

## 测试接缝、设备门与结果分类

优先现QA4 submit/epoch与08迟到FD/shared-mLock seam，新增独立固定controller公共边界，Clock可注入；原型测试/构建命令必须在prototype README固定后真实运行。现 `:runtime:engine-avf:test` 可作保留回归，不能把空JUnit或已有20入口当本prototype通过。

真机绑定唯一目标/当前user/新QA环境，先盘/事务保护和初态，不force-stop用户App。操作超时预算写入动作包及配置，不把任意秒数当平台保证；timeout只发布取消意图，收尾必须独立可观察。每个固定失败门查明后若无已授权合法解决方案就冻结扩张，控制器离线工作仍可完成。

结果：pass_platform_protected_payload / rejected_at_stage / blocked_external / stop_unconfirmed。成功仅完成G1，不代表protected自定义Linux/共同kernel/网络/私有raw盘/GPU或性能。

## 数据、迁移、许可与回滚

只固定QA输入/本地脱敏stage/时钟/计数，无pairing key/token/口令/按键/rawinitrd。任何句柄/FD/capability仅本次固定QA，App/server death如何收敛写清；不把QA独立owner默认批准给产品ACR。正常释放owner/平台句柄并确认退出即回滚，QA新文件按动作包保留不自动删除，不动生产实例或预先grants。

优先系统可信payload与既有工具链；新源码/第三方依赖逐项固定许可，不提取/发布未经审查系统镜像。源码 `prototypes/protected-platform-probe/`（建立前查重），报告 `docs/reports/prototypes/avf-shizuku-24/prototype-report.zh-CN.md`，review同id，外部本轮24专属根和preservation。当前仅设计票，无新VM或测试通过。

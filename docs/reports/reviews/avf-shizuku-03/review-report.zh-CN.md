# Review：avf-shizuku-03 普通Shizuku客户端

2026-10-15，主协调汇总两个独立只读审查轴；main相对固定点428860ad9a1cb335f8be78c157b2ff57faf2a6da，空提交列表。显式审新增controller/Test、Android transport/dialog、MIT资产与SDK/Manifest/入口diff，未靠tracked diff遗漏新增/ignored文件。

## Standards

B/GPT-6.1-sol：**approve，P1=0/P2=0**。固定SDK13.1.5、四实际AAR/完整MIT可达，provider与精确manager查询符合官方接口。controller requestCode/Binder/lifecycle代际、death/异常/timeout/stop退役，transport保留原requestCode、主线程listener及close失效回调，dialog仅可见动作请求、onStop注销。无grant/exec/UserService/VM操作、FD/owner/私有盘变化。E/review-standards/03.md。

## Spec

独立Spec reviewer：首审P2=1，pending后授权readback成功仍保留旧deadline，30秒后误覆TIMED_OUT；真实production controller+fake transport反例exit1。修复refresh退休pending、dialog收到requestCode=-1清deadline，原反例逐bytes复用后编译/运行0，AUTHORIZED保持。最终 **approve，P2闭合，余留P1/P2=0**；其余未安装/拒绝/死亡/旧结果/重连/timeout与文案/许可均符合，无scope creep。E/review-spec/03.md及test-work保留原red/hash。

## 测试真实性与许可

controller八场景red→green、实际capability JavaExec、App715/0失败错误跳过、lint0Error/747Warning和最终500构建均有证据。13独立字节核验确认notice唯一1531bytes与源码一致，含api/provider/aidl/shared四13.1.5坐标与官方MIT全文；About本地入口已源码审查。SDK实际依赖hash固定，未内嵌manager/server/图标；项目GPL身份不替代MIT声明。新功能按MINOR0.5.0/code500，签名沿旧包。

## 范围、风险与状态

本地代码/编译/回归/打包/双轴完成；真实首次配对、撤回可能force-stop、重启/OEM/provider到达与UI绘制没有设备验证，仍在06门。client授权不等Android两权限/hiddenAPI/AVF成功，VM代理ACR-01及真实Guest/性能继续blocked。无新guest权限面，不触发本票实施架构变更。

结论：completed-local；规范0发现，需求0发现（1项P2已闭合）。回滚client/SDK/入口不改实例数据或用户已有Android授权。无commit/push/发布；E为开发根Orchestella/artifacts/avf-shizuku-next-stage/20261015，原件/preservation长期保全，无自动清理。

报告人：Codex主协调汇总独立双轴 / GPT-6.1 ｜ 日期：2026-10-15 ｜ 耗时：未可靠计量

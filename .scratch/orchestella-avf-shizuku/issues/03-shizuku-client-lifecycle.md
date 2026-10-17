# 03: 本机Shizuku客户端状态与主动授权

Type: implementation
Status: completed-local（真实授权/OEM验收在06）
Owner: A / 主协调
Blocked by: None

## 用户价值与来源

将现有文字引导变成真实可读的manager/server/client状态和用户主动授权，减少无效操作。来源研究§3–5、A固定Shizuku/API源码与官方MavenCentral13.1.5发布源；符合ADR0003已有client评估范围。02/03共享UI但没有语义阻塞边，按同owner顺序处理。

## 允许范围与交付

只接入api/provider13.1.5与受控Android transport，复用既有向导/设置入口；安装/首次启动官方引导、Binder listener、权限请求结果与死亡/重连/退役处理。状态模型可注入transport，在JVM事件序列验证。禁止Shizuku.exec/远程shell、development grant、UserService/VM代理、新长期server、自动打开系统设置/无限轮询。单纯client已授权不得点亮AVF运行成功。

## 行为验收

- [x] 已实现未安装、Binder待到达、未授权、拒绝、授权、death/重连；真实SDK接线编译，合成transport八场景通过。
- [x] 仅可见点击请求，拒绝零自动再请求；API异常/manager缺失明确失败并保留ADB兜底。
- [x] listener按lifecycle注册/注销，requestCode/Binder代际/旧结果隔离，授权readback退役旧timeout；独立反例red→green闭合。
- [x] 撤回可能force-stop与重开重新读取机制说明齐全；真机动作未验在06。
- [x] client授权/Android两权限/API访问/Guest分述，重启说明不承诺零步骤。
- [x] MIT全文和四模块13.1.5坐标已入唯一APK条目、About可达，实际依赖SHA齐全，不打包manager/server。

## 测试、失败与设备门

Seam：纯client controller+transport事件、真实Android SDK接线编译。red：缺少client能力/权限请求/death代际行为；green：事件序列与请求次数/退役行为，不查源码字符串。App testDebugUnitTest/assemble/lint由主协调统一运行；依赖缺口仅官方固定坐标在线解析。真机首次配对、授权拒绝/撤回重启、OEM后台须05/06实测，当前完成范围仅本地实现，不签收设备体验。性能不适用。

## 边界与回滚

不触碰实例/凭据/根盘/FD/VM owner，不新增guest→host或出站路径；安装状态查询仅固定Shizuku包。client授权不自动revoke/grant Android权限。回滚client类/SDK/provider manifest/依赖/文案，保留用户实例和已存在Android grants；不卸载管理器或清数据。依赖MIT与GPLv3兼容，NOTICE列明。

## 证据与完成

E/implementation-03/；中文实施/review avf-shizuku-03。普通client实现、八场景模型、Android接线构建、App715/lint与双轴最终approve已完成；唯一500/0.5.0 APK签名/版本/许可门通过。设备真实配对/拒绝/撤回/重启另票06 blocked-device；VM代理及性能不在完成声明中。

## Comments

2026-10-15：无VM代理/架构批准依赖；不得因VM路线未决而把client代码本身虚构为不可实施。

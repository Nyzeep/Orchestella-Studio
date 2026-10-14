# AVF GPU / buffer 与 USB 透传权限的架构复核

Type: decision
Status: needs-architecture-decision
Owner: phase5-coordinator
Blocked by: none

## 问题与证据

Phase 5 官方源码与实际打包输入核查推翻了部分历史“已闭环”叙述。ADR0017把QEMU guest视为共享宿主Linux内核、LocalSocket天然跨VM传memfd；真实Full Linux拥有独立guestkernel。vsock仅字节流，不能天然传宿主FD。平台GPU/display服务和customVM分别要求实际平台API/权限，MANAGE单项不足。当前包内QEMU是virtio-gpu2D，项目旧VirGL提交/fence实现不提供完整上游renderer；hostEGL初始化不证明guestMesa加速。现有USBHost用户授权FD与VMM进程/AVFattach仍无已验的数据面。

证据仅保留官方出处、固定revision及最小本地测试：[图形/AVF研究](../../../docs/research/2026-10-13-phase5-graphics-avf.md)、[USB/存储研究](../../../docs/research/2026-10-13-phase5-usb-storage.md)、[生产力研究](../../../docs/research/2026-10-13-phase5-productivity.md)。未运行设备项目不作型号普遍性判断。

## 受影响决策

ADR0002/0005/0009/0010/0012/0013/0017/0019；Phase5spec中的GPUzero-copy/USB数据面增强。历史ADR0010的abstractsocketUID天然隔离说法亦须按实际Linux权限更正；本轮显示采用app-private filesystem socket+token，不依赖该说法。

## 当前可交付与冻结范围

用户本轮明确授权CPU拷贝fallback，故同一Full Linux实例的Wayland headless/pixman+签名wayvnc、独立token鉴权字节流、Surface/input、版本化feature控制和既有只读协议bug修复继续。正式ADR、白名单与安全policy不被修改。

GPU硬件backend、hostvisibleblob/共享buffer、nativeUSB设备到guest、任意目录/读写共享、Android私有目录物理导出保持不可宣称可用。USB仅交授权FD生命周期原型；staging仅内部cache与逻辑catalog，MountStatus=UNVERIFIED。不会安装宿主系统驱动、服务或扩大系统权限。

## 建议可审核决策

1. 将显示能力分为合成器捕帧、数据传输、宿主呈现、guestrenderer证据四项；GPU通过真实renderer与可操作画面后再声明。
2. 在确有USE_CUSTOM/platform-display/render-node授权的第二台设备上分别测试AVF；保持CPUcopy可用路径，不预承诺旗舰型号。
3. 先独立原型验证QEMU既有hostdevice/fdset入口和Android授权FD跨进程ownership，明确支持的endpoint类型/reset/hotplug/FD回收；只有新暴露面经用户裁定后才进入产品。
4. Buffer零拷贝需明确virtio/blob设备、hostvisiblememory、fence/contextloss/显存限额与权限；取得证据前不把vsock吞吐视为zero-copy。

## 代价、可逆性与待用户动作

CPUcopy吞吐与CPU代价需要真guest基准；目前仅协议/宿主负例和合成文件基准。后续平台构建或设备授权涉及真正系统/产品取舍，本票保留具体架构裁定；无需阻塞已授权的本地实现/构建与交付。没有请求当前会话重绑盘符、重配ADB或占用第一会话设备。

本票未接受新ADR、不代表权限已获取；无commit/push/远端发布。

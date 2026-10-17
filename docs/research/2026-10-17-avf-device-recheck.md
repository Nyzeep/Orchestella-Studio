# 检测报告：当前 HONOR 真机的硬件虚拟化、AVF 与应用可用性

- 日期：2026-10-17；执行者：Codex主协调与两条只读源码/官方资料研究线。
- 目的：用户要求重新检测当前已重连设备，不能把旧nonprotected=false直接概括成“完全不支持硬件虚拟化，只能TCG”。本轮以新设备原件、平台命令和官方资料交叉判断。
- 设备别名：recheck-device-01；本轮始终恰好1台ready adb设备。采集约本地11:35–11:50（+08:00）。不保存serial/IMEI/MAC/账号、完整ROM fingerprint、其他App正文、用户根盘或秘密。
- 外部证据E：开发根 `Orchestella/artifacts/avf-device-recheck/20261017-113556/`，原件和preservation同字节；保留至维护者验收归档，无自动清理。
- 研究范围内无产品/Guest/CI/ADR/policy/refs修改，无构建/安装/grant/revoke/root/解锁/全局安全设置变化；正式新增本文及外部检测脚本/脱敏结果。设备诊断副作用详§6。

## 1. 结论与精确边界

**当前设备不能被判为“没有硬件虚拟化、只能软件仿真”。** 实机显示AVF模块/feature、protected支持声明、Gunyah内核与设备节点；平台自身 `vm info` 新执行exit0，明确输出“Only protected VMs are supported.”。这些证明当前ROM具有AVF protected-only的声明/集成条件，而不是完整开放所有虚拟机类型。

当前ROM没有向应用声明non-protected VM支持；Orchestella现adapter明确需要non-protected custom Linux，所以其当前可用且已有真实验证的完整Linux执行路径仍是QEMU/TCG。这个应用结论不能扩大为芯片没有硬件虚拟化。

**pKVM实际启用未证实。** 本机存在CONFIG_GUNYAH及/dev/gunyah，未见/dev/kvm，Gunyah路径有较强集成证据；AVF是框架，pKVM是AOSP参考hypervisor，protected位不是后端名称。不据厂商/SoC/系统VMM进程猜测精确后端、版本或Guest内容。

**真实protected Guest启动尚未验。** 本轮没有create/run新的VM；平台声明、内核配置、节点及system qcrosvm存在不代替本App实际硬件Guest闭环。没有充分证据给出“完整硬件虚拟化能力全部通过”的结论，未实测的pVM启动、FD/owner、服务/网络、Guest/正常stop分别保持待验。

## 2. 本轮新采集的脱敏设备信息

| 字段 | 观察 |
| --- | --- |
| 厂商/型号 | HONOR / MEY-AN00，device HNMEY |
| SoC/平台 | QTI SM8735，board sun，hardware qcom |
| CPU | 8个逻辑核，aarch64/arm64-v8a；公开CPU part集合0xd81、0xd82 |
| Android/API | 16 / 36 |
| ROM | build ID HONORMEY-AN00，incremental 10DLDLD170SP1C00E170，user/release-keys |
| 安全补丁 | system/vendor均2026-07-01 |
| Linux内核 | 6.6.118-android15-8-gf17133276a57-abogki518694926-4k |
| 页面 | 4096字节；CONFIG_ARM64_4K_PAGES=y，16K not set |
| 内存 | MemTotal11564844KiB，约11.03GiB系统可见；瞬时MemAvailable5102268KiB，不能当保留资源 |
| 系统安全 | VerifiedBoot green，flash.locked=1，SELinux Enforcing；本轮不改变 |
| 当前Android user | 0 |
| AVF | feature=true，com.android.virt versionCode3 |
| protected属性 | ro.boot.hypervisor.protected_vm.supported=true |
| nonprotected属性 | ro.boot.hypervisor.vm.supported为空，原属性读取记UNKNOWN |
| hypervisor版本/pvmfw属性 | 本轮所查固定属性为空，未知；不推断不存在 |
| 当前user已见QA/工具包 | phase5 code503、researchauth code4、Shizuku manager code1086 |

完整ROM/vendor指纹只记录SHA256，不保存原文。`pm list packages`采用名称包含过滤，结果可能有项目子包，不能拿父包查询返回非空就断言生产原包已安装；本轮没有枚举其他Android user或其私有数据。营销机型名/SM8735市场名称未得到一手对应依据，本文只用真实型号/SoC代码。

内核名含android15是GKI分支标识，不与Android16系统版本矛盾，不能仅据名称诊断系统版本错误。ARM64的/proc/cpuinfo没有x86 vmx/svm标志也不构成“不支持虚拟化”的证据。

原件：E/device-readonly-facts.json、platform-kernel-followup.json。

## 3. 硬件/内核/平台证据分别说明什么

| 证据 | 本轮实际结果 | 有效含义与限制 |
| --- | --- | --- |
| CONFIG_KVM | y，相关MMIO/VFIO/guest-work配置y | 内核编译了相关代码；不证明KVM运行后端或App可打开设备 |
| CONFIG_GUNYAH | y，PLATFORM_HOOKS/IRQFD/IOEVENTFD均y | Gunyah驱动/集成代码存在；具体固件版本未知 |
| /dev/gunyah | 存在，字符设备，vendor_gunyah_dev标签 | 本ROM暴露Gunyah设备路径；权限位不能替代SELinux或服务策略 |
| /dev/kvm | ls返回NOT_FOUND；平台vm info也确认不存在 | 当前没有常规KVM设备入口，不能据此等同无其他hypervisor |
| /dev/vhost-vsock | 存在，kvm_device标签 | 相关传输节点存在，不证明本Guest vsock已连通 |
| 系统qcrosvm | 两个root厂商qcrosvm进程，查询前后保持 | 属系统/厂商VMM线索，非本App，不知道其Guest内容/运行模式 |
| /sys/hypervisor/type | PERMISSION_DENIED | 精确运行hypervisor名称未知，未提权绕过 |
| DT hypervisor compatible | 节点可列，读取PERMISSION_DENIED | 不把无法读作没有或猜测具体字符串 |
| pvmfw/version属性 | 读取为空 | 仅该属性不可得，不证明pVM固件不存在 |

综合更符合“有Gunyah/AVF集成，但ROM只声明protected模式”，不是“纯软件仿真设备”。这些配置/节点仍不签发一次protected VM实际boot成功。

## 4. 新执行的平台查询与API关联

本轮使用系统只读命令：`/apex/com.android.virt/bin/vm info`，exit0。已核固定源码该入口不createVM/启动Guest/分配用户盘；其中设备、OS/debug查询可能启动短期virtmgr/系统VirtualizationService客户端。

实际脱敏输出：

```text
Only protected VMs are supported.
Hypervisor version not set.
/dev/kvm does not exist.
/dev/vfio/vfio does not exist.
VFIO-platform is not supported.
Assignable devices: empty
Available OS list: ["microdroid"]
Debug policy: Ok(DebugPolicy { log: false, ramdump: false, adb: false })
```

这是当前ROM系统工具的新查询，不是重复引用昨日日志。平台vm info与Java getCapabilities的支持位可能读取同源HypervisorProperties，不能当两次独立硬件运行实验。microdroid列出可用OS也不证明已经成功启动，debug policy字段仅作当前政策观察。

官方Android16 `VirtualMachineManager`（android-16.0.0_r1，blob ae34a1ed50d0bfd45cbd6b9d8b008185157bf338）以protected属性映射bit1、nonprotected映射bit2，缺省orElse(false)，不检测后端名字或试建VM。旧20261016的QA3真实UserService bits1与今日平台输出一致，但它仍是旧时间的APIreceipt；不能改写成今天重跑成功。

今日设备上QA4安装APK bytes重新读取并与批准包比对，均SHA256 be9e25298ec337ff947985fb2341e20974a49615eb15722beef74e0ff5f4ba12。打开现QA Activity后确得新App snapshot：serverUid2000、serverVersion13、binderAlive/clientGranted均true，App自身MANAGE/CUSTOM均DENIED，customBuilder仍hidden。这与shell/US身份权限分开。

本轮没有点授权或grant按钮。设备Dozing/锁屏，未取得QA焦点，故没有触发按钮6再查询US getCapabilities；不尝试机主解锁。取而代之执行了上述平台info新查询并记录完整边界。旧QA receipt先取固定字段/hash保全，没有归档其任意原始内容。原件E/qa-before.json、snapshot-current-app.json、platform-vm-info.json。

## 5. 官方资料核对与路线判断

已实际查阅一手来源，完整URL与适用性见E/source-ledger.json：

1. [Android16 Manager实现](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/android-16.0.0_r1/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineManager.java)：protected/nonprotected是两项独立支持声明；API与属性同源，不验证Guest运行。目标APEX3不被假定等于这个源码tag。
2. [Android16 framework README](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/android-16.0.0_r1/libs/framework-virtualization/README.md)：Java受限SystemApi/MANAGE；开发grant可解决部分权限门，示例为Microdroid/APK native payload、setProtectedVm(true)、vsock，不自动授予任意kernel能力。
3. [官方AVF概览](https://source.android.google.cn/docs/core/virtualization)及[架构](https://source.android.google.cn/docs/core/virtualization/architecture?hl=en)：AVF、VirtualizationService/VMM/hypervisor分层，AOSP pKVM参考设计不等于每个OEM实际后端。本轮直接源站有网络失败，使用官方镜像，不拿失败请求当已读来源。
4. [官方Microdroid](https://source.android.google.cn/docs/core/virtualization/microdroid)：固件、验证启动、payload和实例身份；protected Microdroid成功不等完整Debian/Ubuntu custom guest成功。
5. [Qualcomm官方Gunyah](https://github.com/quic/gunyah-hypervisor)：Arm64 EL2、独立于Linux的hypervisor。本文只借其说明Gunyah与pKVM概念不同，不将moving develop README当目标ROM源码/版本认证。

因此，当前设备的事实可分为：

| 问题 | 当前结论 |
| --- | --- |
| 是否可认定完全无硬件虚拟化 | 否；有AVF protected声明及Gunyah集成证据 |
| 系统是否完整开放protected/nonprotected两种模式 | 否；vm info仅protected，nonprotected不声明 |
| 是否已确认使用pKVM | 否；具体运行后端/版本未能只读确认，Gunyah线索更强 |
| 是否已启动protected硬件Guest通过 | 未验；本轮没有启动新VM |
| 当前Orchestella nonprotected custom Linux能否跑AVF | 当前ROM不满足其前置条件；既有App接口/owner门也未消失 |
| 当前Orchestella能否运行完整Linux | 已有phase5 code503 QEMU/TCG实测；本轮没有重启那个实例 |
| 能否直接改setProtectedVm(true)解决 | 不能据现证据承诺；需独立protected镜像/boot/权限/FD/state/owner/通道验证 |

不能将所有protected custom路线概括成必须OEM签名；固定AOSP还接受若干APK/shell_data等标签，具体文件与启动链分别检查。但App私有kernel/initrd/root路径不自动符合，Shizuku grant/UID2000也不会改变ROM支持位、文件标签或固件实现。

## 6. 实际动作、未运行与收尾

- 使用既有adb/SDK，插件preflight错误指向C:/Windows/adb.exe，不据此重装或改系统配置。
- 本轮运行固定getprop、feature/APEX查询、uname/page、有限meminfo/cpuinfo/config解析、固定节点/标签及精选进程信息，完整指纹仅hash、CPU serial和任意原始/proc内容不落盘。
- 初次压缩config读取解析失败，改binary-safe exec-out后成功；这是采集传输/解析问题，不当成内核失败或能力false。旧失败记录不充当成功。
- 只核并打开已安装QA4，写其正常owned snapshot；未bind QA UserService，未请求client/VM权限，不执行FD/state试验。锁屏只唤醒屏幕，没有解锁或改变屏幕超时，查询后HOME离开QA，无force-stop。
- vm info查询一度lazy启动系统virtualizationservice；后续精选进程复核该进程已不在，virtmgr0、QA US0、项目QEMU0、Shizuku server1、厂商qcrosvm2。没有kill系统厂商VMM或改变权限。
- QA HOME原记录的通用文字误写“after normal Remove”，操作实际只有KEYCODE_HOME，未调用Remove（因本轮没bind）；保留原件并由E/qa-home-record-clarification.json澄清，不把模板文字当已执行动作。

**未运行protected VM实际boot。** 已核系统run-microdroid help及固定run.rs：它会创建instance/idsig等文件、create/start并无期限wait_for_death，缓存实现未给受控总deadline/正常stop命令，也不能假定杀宿主adb必定停止远端VM；即使指定workdir，固定实现还有额外随机目录副作用。为避免把无界诊断直接落到设备，本轮不执行该入口，不给出硬件Guest成功结论。后续先准备独立受控生命周期probe，再做新QA protected Guest验证，不操作用户盘。

本轮未修改系统安全、开发权限、VM owner架构、生产签名/版本/源码，无安装、commit/push/tag/release。检测脚本与原始脱敏JSON在E，正式研究归本文；其他未来实现/设备动作不由本文自动触发。

## 7. 下一步建议

1. 对当前手机继续保留已验证的QEMU完整Linux路径，产品提示用“当前系统仅声明保护型虚拟机；本应用所需模式未开放”，不要写手机没有虚拟化硬件，也不要重复grant当解法。
2. 若目标是尽快实现当前nonprotected guest硬件加速，优先选择已通过nonprotected能力和最小QA Guest启动验证的平台，再补05/G4、合法state/FD/owner与ACR-01。仅feature/芯片型号不足以选机。
3. 若目标必须是当前手机，先独立验证受控protected Microdroid的真正create/start/payload/stop，绑定此ROM和owned进程；这只验证protected硬件路线，不直接关闭完整Linux工单。
4. protected验证成功后，再评估合法custom Linux镜像/boot信任、FD/state、网络/vsock/终端、持久性与有界停止；不得仅翻配置开关、放宽全局策略或公共复制用户盘。真实owner/IPC变化继续ACR。
5. 只有本应用Guest完整闭环之后才做同设备同资源AVF/QEMU性能比较；本轮硬件Guest/AVF性能样本0，不提供提速数字。

报告人：Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 耗时：未可靠计量

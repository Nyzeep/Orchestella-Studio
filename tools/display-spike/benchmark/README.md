# W4-05 显示通道基准

在独立实验 APK 中以同一份 packed QEMU 和 Image 启动隔离的 Alpine 实验桌面。Guest 使用 labwc Headless/Pixman，输出固定为 960×540。Raw 和 ZRLE 使用私有 WayVNC socket 与仓内认证中继。共享帧通道运行独立的 `wlr-screencopy` 客户端。捕获和 WayVNC 同样包含光标并限制到 15 FPS。

Android 复用 W4-01 的 `NativeGlesSurfacePresenter` 和 `SurfacePresenter.presentRgba()`。Raw 使用 W4-01 RFB 源码与 `DoubleBufferedRenderer`，ZRLE 从固定 W4-02 提交取启用 ZRLE 所需的解析器和通道源码。两个包使用同样的窗口与 VSYNC 调度。

## 共享帧协议

8 MiB BAR2 分为两个 4 MiB 槽。Guest 只继承已验证的 BAR2 与专用帧通知端口，采集过程在 UID/GID 1000 运行。帧头以 little-endian 编码并占 64 B，字段顺序为 magic、version、seq、width、height、stride、format、damage 位、16 B generation 和 16 B 保留零值。像素紧接帧头，格式为 RGBA8888。

Guest 写完像素和帧头，以写屏障发布序列并发送 `P <seq>`。宿主从只读映射中校验元数据和独立 generation，在上传后复核序列并回送 `A <seq>`。协议最多一个待确认帧，Guest 收到匹配 ACK 后才写下一帧。首个变化帧用单次快照通过现有 `DesktopPresentationGate`，后续帧从映射直接传给 GLES。PBO 复制与纹理上传保留在既有 native presenter 中。

## 构建和设备窗口

先按 [Guest 夹具说明](DESKTOP-FIXTURE.md) 构建同一份 `desktop-benchmark.cpio.gz`。SDK 与工具链要求见 [BAR2 首日探针](../track-a/README.md)。源码分支须能解析 W4-02 提交 `5a04db43`。

```powershell
python -B tools/display-spike/benchmark/build_apk.py --profile raw --source-apk $PayloadApk --guest-initrd $GuestInitrd --output $RawApk
python -B tools/display-spike/benchmark/build_apk.py --profile zrle --source-apk $PayloadApk --guest-initrd $GuestInitrd --output $ZrleApk
$pkg = 'io.github.nyzeep.orchestella.spike.w405bench'
foreach ($target in @($pkg, "$pkg.zrle")) {
    if ((adb -d shell pm list packages $target) -contains "package:$target") {
        adb -d shell pm clear $target
        adb -d uninstall $target
    }
}
adb -d install $RawApk
adb -d install $ZrleApk
python -B tools/display-spike/benchmark/run_bench.py --output $EvidenceDir --seconds 20 --rounds 2
```

在同一台已唤醒的设备上执行。每次启动新 Guest 并复用相同规格。启动时用首个桌面帧作为像素对照。每轮的 `GO` 触发同一种脚本，三种负载均从同一个 foot 窗口起步。延迟取宿主发出 `GO` 到首次可辨识变化画面完成 Surface swap 的时差。实际呈现 FPS 取窗口内成功 swap 数除以窗口秒数。App 与 QEMU CPU 按一核 100% 计算，Guest CPU 取 `/proc/stat` 忙时占全部 vCPU 时间的比例。

结果文件保留在仓外。验收时检查基准最终回执里的进程、目录和映射清理状态，保留通过场次的脱敏指标。

# W4-05 BAR2 首日探针

这个一次性 APK 在普通 Android App 进程中启动打包的 QEMU。启动参数使用独占的 `0600` 私有常规文件，Android 以 `PROT_READ | MAP_SHARED` 映射。打包 QEMU 和 Image 从已有的完整运行时 APK 提取并校验随包清单的 SHA-256。

最小 Guest init 仅识别 `1af4:1110` 的 BAR2。Guest root 核对 sysfs 节点后映射，子进程降到 UID/GID 1000，验证直接打开被拒绝。父进程核对 socketpair 上发送者的 PID、UID 和 GID 后发送该 BAR 的 FD。两端通过独立哨兵验证宿主、Guest root 和普通 Guest 用户各自的读写结果。

## 构建与运行

需要 Android SDK API 36、Build Tools 36.1.0、NDK 27.0.12077973、JDK、Python 3.10+ 和 Linux AArch64 交叉编译器。Windows 通过默认 WSL 发行版运行 Guest 交叉编译器。Windows Android 构建工具需要可解析的短 ASCII 工作路径。

```powershell
python -B tools/display-spike/track-a/build_probe.py --source-apk $PayloadApk --output $ProbeApk
$probe = 'io.github.nyzeep.orchestella.spike.w405a'
if ((adb -d shell pm list packages $probe) -contains "package:$probe") {
    adb -d shell pm clear $probe
    adb -d uninstall $probe
}
adb -d install $ProbeApk
adb -d shell am instrument -w "$probe/.TrackAProbe"
adb -d shell am instrument -w -e mode invalid-device "$probe/.TrackAProbe"
adb -d shell am instrument -w -e mode device-list "$probe/.TrackAProbe"
```

`normal` 预期输出 `guest_complete=pass`、两个 `host_observed_guest_*=1` 以及 `host_complete=pass`。`invalid-device` 预期 QEMU 退出码非零且标记 `unknown-device`。两组均要求 QEMU 停止、`munmap` 成功、删除 backing file 和本次私有工作目录。`device-list` 只记录模型是否存在。

请把设备输出保留在仓外。实验 APK、签名文件和 Guest initramfs 都是本地构建产物。

# W4-05 轨道 B Android 图形探针

探针 APK 用 ARM64 普通非 root App 进程查询已初始化的默认 EGL display。扩展检查按完整名称匹配 `EGL_EXT_image_dma_buf_import` 和 `EGL_EXT_image_dma_buf_import_modifiers`。设备节点探针分别记录打开模式和 errno。系统 heap 的只读打开成功后，探针尝试分配一页 DMA 缓冲并立即关闭两个文件描述符。

AHardwareBuffer 对照组通过 Android 公开 API 分配缓冲，由生产者写入像素标记，经 Binder 交给同包独立进程。接收者验证调用来自另一进程，使用其默认 EGL 导入缓冲，绑定 GLES 纹理并读回标记。这一结果覆盖两个 Android 进程间的框架分配、句柄传递和本机 EGL 导入。跨驱动与跨 VM 导入另需端到端证据。

## 构建与运行

构建需要 Python 3、JDK 17、Android API 36、Build Tools 36.1.0 和 NDK 27.0.12077973。从原型仓根目录运行下列命令。脚本根据 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 查找 SDK，也可通过 `--sdk` 指定其目录。脚本使用临时签名密钥，并验证 APK 签名及 ZIP 16 KiB 对齐，同时打印 ELF 段对齐。

```powershell
$probeApk = Join-Path $env:TEMP 'w405-display-probe.apk'
python tools/display-spike/track-b/build_probe.py --out $probeApk
adb -s $device uninstall io.orchestella.displayprobe
adb -s $device install $probeApk
adb -s $device shell am start -W -n io.orchestella.displayprobe/.ProbeActivity
adb -s $device logcat -d -v raw -s 'W405DisplayProbe:I' '*:S'
```

将 `$device` 设为本机 adb 目标。若旧探针已不存在，可直接安装。启动后等待 `END ahb_result=`，从同一次 `BEGIN` 到 `END` 提取证据。审核 SELinux 时，仅归档与该轮进程和探测路径对应的 AVC 脱敏摘要。日志中的 `direct_open` 和 `dma_heap_allocation` 分别描述节点打开与分配结果。将拒绝归因于 SELinux 需要匹配该操作的 AVC。

```powershell
adb -s $device uninstall io.orchestella.displayprobe
```

参考 [Khronos EGL DMA-BUF 导入扩展](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_image_dma_buf_import.txt)、[Khronos Android EGL native buffer 扩展](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_get_native_client_buffer.txt)、[Android NDK AHardwareBuffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer)。

package io.github.nyzeep.orchestella

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuDeviceModelProbe

/**
 * DW-02：QEMU 设备模型探测结果的进程内单源（UI 消费侧）。
 *
 * 探测涉及 exec 打包 QEMU 二进制（`-device help`），严禁在主线程同步执行：
 * [warmUpAsync] 在 Application 启动时于后台 daemon 线程完成探测并发布到
 * [deviceModelSupported]（Compose 响应式，探测完成后依赖它的文案自动刷新）。
 * 引擎侧（DW-01）的 VirglGpuLifecycle.activate 在启动工作线程上走
 * [QemuDeviceModelProbe.probeOutcome]，与本处共享同一个进程内缓存。
 *
 * fail-closed 口径：探测未完成（null）/ 失败 / 路径缺失一律按「不支持」处理
 * （[supportedOrConservativeFalse]）——诚实的「尚未证实」不会许诺一条可能
 * 开不了机的链路；误报「支持」的代价是 QEMU exit 1 的死 VM。
 */
object GpuDeviceModelProbeOwner {

    /**
     * null = 探测未完成；true = 打包 QEMU 支持 virtio-gpu-gl-device；
     * false = 不支持或探测失败（保守）。UI 直接读此 State 即可随探测完成刷新。
     */
    private val deviceModelSupportedState = mutableStateOf<Boolean?>(null)

    /** 只读视图：UI/测试消费入口。 */
    val deviceModelSupported: State<Boolean?> get() = deviceModelSupportedState

    /**
     * UI 注入口：true 仅当探测明确结论为支持；未完成/失败一律 false（保守）。
     */
    @JvmStatic
    fun supportedOrConservativeFalse(): Boolean = deviceModelSupportedState.value == true

    /**
     * 进程内预热：解析打包 QEMU 路径（NativeExecutableStore.ensure 幂等，
     * extractNativeLibs=true 时只做一次 nativeLibraryDir 存在性检查），随后
     * 阻塞式探测并发布。任何失败都落为 false，绝不上抛、绝不阻塞调用线程。
     */
    @JvmStatic
    fun warmUpAsync(context: Context) {
        val appContext = context.applicationContext
        Thread {
            try {
                val qemuPath = NativeExecutableStore.ensure(appContext, "libdroidlayer_qemu.so")
                val supported =
                    QemuDeviceModelProbe.probeOutcome(qemuPath) ==
                        QemuDeviceModelProbe.Outcome.SUPPORTED
                deviceModelSupportedState.value = supported
            } catch (t: Throwable) {
                // fail-closed：探测链路上任何异常都按不支持发布（见类注释）。
                deviceModelSupportedState.value = false
            }
        }.apply {
            name = "qemu-device-model-probe"
            isDaemon = true
            start()
        }
    }
}

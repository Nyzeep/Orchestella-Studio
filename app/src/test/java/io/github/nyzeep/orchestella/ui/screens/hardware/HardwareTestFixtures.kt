package io.github.nyzeep.orchestella.ui.screens.hardware

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.ui.screens.common.StorageFacts

/** 硬件页状态映射与语义用例共用的确定性样本；不含任何真机标识。 */
internal object HardwareTestFixtures {

    const val GIB = 1024L * 1024L * 1024L

    val device = HardwareDeviceFacts(
        alias = "工作机",
        model = "Pixel 8",
        androidRelease = "15",
        sdkInt = 35,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        socModel = "Tensor G3",
        kernelVersion = "6.1.75-android14",
        screenSpec = "1080×2400 · 420 dpi",
        gpuRenderer = "Mali-G715",
        selinuxStatus = "Enforcing",
    )

    fun profile(totalBytes: Long = 8L * GIB, availBytes: Long = 3L * GIB) =
        HostHardwareProfile(8, totalBytes, availBytes, 2L * GIB, false)

    val storage = StorageFacts(totalBytes = 128L * GIB, freeBytes = 62L * GIB)

    val battery = HardwareBatteryFacts(percent = 76, temperatureCelsius = 36.5f)

    fun engines(
        status: AvfCapabilityEvaluator.AvfStatus = AvfCapabilityEvaluator.AvfStatus.READY,
        reasons: List<String> = emptyList(),
        qemuAvailable: Boolean = true,
        umlRunningWidth: Int = 2,
        umlChannelWired: Boolean = false,
    ) = HardwareEngineFacts(
        avfStatus = status,
        avfReasons = reasons,
        avfModeSummary = "protected=支持；non-protected=支持",
        qemuAvailable = qemuAvailable,
        umlRunningWidth = umlRunningWidth,
        umlChannelWired = umlChannelWired,
    )

    fun ready(
        deviceFacts: HardwareDeviceFacts = device,
        hardware: HostHardwareProfile = profile(),
        storageFacts: StorageFacts = storage,
        batteryFacts: HardwareBatteryFacts = battery,
        engineFacts: HardwareEngineFacts = engines(),
    ): HardwareUiState.Ready = HardwareUiState.from(
        device = deviceFacts,
        hardware = hardware,
        storage = storageFacts,
        battery = batteryFacts,
        engines = engineFacts,
    )
}

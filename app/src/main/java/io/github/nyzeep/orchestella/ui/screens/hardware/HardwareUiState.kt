package io.github.nyzeep.orchestella.ui.screens.hardware

import androidx.annotation.StringRes
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.ui.screens.common.StorageFacts
import io.github.nyzeep.orchestella.ui.screens.common.formatGigabytes
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 硬件页的纯数据状态（UI3-07）。
 *
 * 页面与映射层都不读 Android Context，也不新增任何探测：
 * - 物理事实来自 [HostHardwareProfile]（现有 `HostHardwareDetector` 的输出）；
 * - 存储复用共享 [StorageFacts]，不另立类型、不重新统计磁盘；
 * - 引擎判定来自 [AvfCapabilityEvaluator.AvfStatus] 与既有注册表的读数；
 * - 设备公开参数（SoC / ABI / 内核 / 屏幕 / GPU / SELinux）由接线方传入，
 *   未提供时显示占位符，不猜测。
 *
 * 唯一标识（设备序列号、电话标识、Android ID、网络硬件地址、完整指纹）
 * 不在本类型的任何输入字段里，页面源码也不允许出现平台 Build 类读取或
 * 设备服务调用。
 */
sealed interface HardwareUiState {

    data class Ready(
        val summary: HardwareSummaryUi,
        val rings: HardwareRingsUi,
        val engines: HardwareEnginesUi,
        val details: List<HardwareDetailGroup>,
    ) : HardwareUiState

    /** 探针不可用：只有失败说明，没有任何可被误读为真实读数的字段。 */
    data class Failed(@StringRes val reasonRes: Int) : HardwareUiState

    companion object {
        fun from(
            device: HardwareDeviceFacts,
            hardware: HostHardwareProfile,
            storage: StorageFacts,
            engines: HardwareEngineFacts,
            battery: HardwareBatteryFacts = HardwareBatteryFacts(),
        ): Ready {
            val totalRam = hardware.totalRamBytes().coerceAtLeast(0L)
            val availRam = hardware.availRamBytes().coerceIn(0L, totalRam)
            val usedRam = totalRam - availRam
            val memoryAvailable = totalRam > 0L
            val ramFraction = if (!memoryAvailable) {
                0f
            } else {
                (usedRam.toDouble() / totalRam.toDouble()).toFloat().coerceIn(0f, 1f)
            }
            val ramPercent = if (memoryAvailable) {
                (ramFraction * 100f).roundToInt().coerceIn(0, 100)
            } else {
                null
            }
            val cores = hardware.totalCores().coerceAtLeast(1)

            val storageAvailable = storage.totalBytes > 0L
            val batteryPercent = battery.percent?.coerceIn(0, 100)
            val temperature = HardwareFormat.temperatureCelsius(battery.temperatureCelsius)
            val batteryFraction = batteryPercent?.let { it / 100f } ?: 0f

            val reasons = engines.avfReasons
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val avfState = HardwareAvfState.of(engines.avfStatus)

            val deviceName = device.alias?.trim()?.takeIf { it.isNotEmpty() }
                ?: device.model.trim().takeIf { it.isNotEmpty() }
            val abis = device.abis.map { it.trim() }.filter { it.isNotEmpty() }
            val architecture = abis.firstOrNull()

            val summary = HardwareSummaryUi(
                deviceName = deviceName,
                androidRelease = device.androidRelease.trim(),
                apiLevel = device.sdkInt,
                architecture = architecture,
                cores = cores,
                ramTotal = if (memoryAvailable) HardwareFormat.gigabytes(totalRam) else null,
                storageFree = if (storageAvailable) HardwareFormat.gigabytes(storage.freeBytes) else null,
                batteryPercent = batteryPercent,
                temperature = temperature,
            )

            val rings = HardwareRingsUi(
                memory = HardwareRingUi(
                    fraction = ramFraction,
                    percent = ramPercent,
                    detail = if (memoryAvailable) {
                        "${HardwareFormat.gigabytes(usedRam)} / ${HardwareFormat.gigabytes(totalRam)}"
                    } else {
                        null
                    },
                ),
                storage = HardwareRingUi(
                    fraction = if (storageAvailable) storage.fraction else 0f,
                    percent = if (storageAvailable) storage.usedPercent else null,
                    detail = if (storageAvailable) {
                        "${HardwareFormat.gigabytes(storage.usedBytes)} / " +
                            HardwareFormat.gigabytes(storage.totalBytes)
                    } else {
                        null
                    },
                ),
                battery = HardwareRingUi(
                    fraction = batteryFraction,
                    percent = batteryPercent,
                    detail = temperature,
                    charging = battery.charging,
                ),
            )

            val enginesUi = HardwareEnginesUi(
                avf = HardwareAvfUi(state = avfState, reason = reasons.firstOrNull()),
                qemuAvailable = engines.qemuAvailable,
                uml = HardwareUmlUi(
                    runningWidth = engines.umlRunningWidth.coerceAtLeast(0),
                    channelWired = engines.umlChannelWired,
                ),
            )

            return Ready(
                summary = summary,
                rings = rings,
                engines = enginesUi,
                details = detailsOf(
                    device = device,
                    abis = abis,
                    cores = cores,
                    totalRam = totalRam,
                    availRam = availRam,
                    storage = storage,
                    engines = enginesUi,
                    avfReasons = reasons,
                    avfModeSummary = engines.avfModeSummary,
                ),
            )
        }

        private fun detailsOf(
            device: HardwareDeviceFacts,
            abis: List<String>,
            cores: Int,
            totalRam: Long,
            availRam: Long,
            storage: StorageFacts,
            engines: HardwareEnginesUi,
            avfReasons: List<String>,
            avfModeSummary: String?,
        ): List<HardwareDetailGroup> {
            val memoryAvailable = totalRam > 0L
            val storageAvailable = storage.totalBytes > 0L
            val androidValue = device.androidRelease.trim()
                .takeIf { it.isNotEmpty() && device.sdkInt > 0 }
                ?.let { release ->
                    HardwareDetailValue.Template(
                        res = R.string.ui3_hardware_detail_android_value,
                        args = listOf(release, device.sdkInt.toString()),
                    )
                }

            return listOf(
                HardwareDetailGroup(
                    titleRes = R.string.ui3_hardware_details_group_chip,
                    rows = listOf(
                        detail(R.string.ui3_hardware_detail_soc, device.socModel),
                        detail(R.string.ui3_hardware_detail_abi, abis.joinToString(", ")),
                        detail(R.string.ui3_hardware_detail_cores, cores.toString()),
                    ),
                ),
                HardwareDetailGroup(
                    titleRes = R.string.ui3_hardware_details_group_system,
                    rows = listOf(
                        HardwareDetailUi(
                            labelRes = R.string.ui3_hardware_detail_android,
                            value = androidValue,
                        ),
                        detail(R.string.ui3_hardware_detail_kernel, device.kernelVersion),
                        detail(R.string.ui3_hardware_detail_selinux, device.selinuxStatus),
                    ),
                ),
                HardwareDetailGroup(
                    titleRes = R.string.ui3_hardware_details_group_display,
                    rows = listOf(
                        detail(R.string.ui3_hardware_detail_screen, device.screenSpec),
                        detail(R.string.ui3_hardware_detail_gpu, device.gpuRenderer),
                    ),
                ),
                HardwareDetailGroup(
                    titleRes = R.string.ui3_hardware_details_group_memory,
                    rows = listOf(
                        detail(
                            R.string.ui3_hardware_detail_ram_total,
                            if (memoryAvailable) HardwareFormat.gigabytes(totalRam) else null,
                        ),
                        detail(
                            R.string.ui3_hardware_detail_ram_available,
                            if (memoryAvailable) HardwareFormat.gigabytes(availRam) else null,
                        ),
                        detail(
                            R.string.ui3_hardware_detail_storage_total,
                            if (storageAvailable) HardwareFormat.gigabytes(storage.totalBytes) else null,
                        ),
                        detail(
                            R.string.ui3_hardware_detail_storage_free,
                            if (storageAvailable) HardwareFormat.gigabytes(storage.freeBytes) else null,
                        ),
                    ),
                ),
                HardwareDetailGroup(
                    titleRes = R.string.ui3_hardware_details_group_engines,
                    rows = listOf(
                        HardwareDetailUi(
                            labelRes = R.string.ui3_hardware_detail_avf_verdict,
                            value = HardwareDetailValue.Resource(engines.avf.state.labelRes),
                        ),
                        detail(R.string.ui3_hardware_detail_avf_mode, avfModeSummary),
                        detail(R.string.ui3_hardware_detail_avf_reasons, avfReasons.joinToString("；")),
                        HardwareDetailUi(
                            labelRes = R.string.ui3_hardware_detail_qemu,
                            value = HardwareDetailValue.Resource(engines.qemuStatusRes),
                        ),
                        HardwareDetailUi(
                            labelRes = R.string.ui3_hardware_detail_uml,
                            value = HardwareDetailValue.Resource(engines.uml.statusRes),
                        ),
                    ),
                ),
            )
        }
        private fun detail(@StringRes labelRes: Int, value: String?): HardwareDetailUi =
            HardwareDetailUi(
                labelRes = labelRes,
                value = value?.trim()?.takeIf { it.isNotEmpty() }?.let(HardwareDetailValue::Text),
            )
    }
}

/** 摘要卡首屏事实；`deviceName` 为稳定别名优先（空白别名回退公开型号）。 */
data class HardwareSummaryUi(
    val deviceName: String?,
    val androidRelease: String,
    val apiLevel: Int,
    val architecture: String?,
    val cores: Int,
    val ramTotal: String?,
    val storageFree: String?,
    val batteryPercent: Int?,
    val temperature: String?,
)

data class HardwareRingsUi(
    val memory: HardwareRingUi,
    val storage: HardwareRingUi,
    val battery: HardwareRingUi,
)

/**
 * 一个圆环的纯数值与已格式化读数；[percent] 为 null 表示读数不可用，
 * 页面此时显示占位符，不把 0 冒充成真实读数。
 */
data class HardwareRingUi(
    val fraction: Float,
    val percent: Int?,
    val detail: String?,
    /** Only the battery ring carries it; the page turns it into a localized note. */
    val charging: HardwareBatteryCharging = HardwareBatteryCharging.Unknown,
)

data class HardwareEnginesUi(
    val avf: HardwareAvfUi,
    val qemuAvailable: Boolean,
    val uml: HardwareUmlUi,
) {
    /** QEMU 状态的文案由模型给出资源 id，页面只负责取字符串。 */
    @get:StringRes
    val qemuStatusRes: Int
        get() = if (qemuAvailable) {
            R.string.ui3_hardware_qemu_available
        } else {
            R.string.ui3_hardware_qemu_unavailable
        }
}

data class HardwareAvfUi(
    val state: HardwareAvfState,
    /** 评估器给出的第一条可读原因；null 时使用状态的兜底文案。 */
    val reason: String?,
)

/**
 * UML 编译面的两层事实：容量（运行宽度）与可路由性（编译通道是否接线）。
 * 两者分开表达，运行但未接线不得说成已接线。
 */
data class HardwareUmlUi(
    val runningWidth: Int,
    val channelWired: Boolean,
) {
    val running: Boolean get() = runningWidth > 0

    @get:StringRes
    val statusRes: Int
        get() = if (running) R.string.ui3_hardware_uml_running else R.string.ui3_hardware_uml_idle

    /** 只有运行中才谈接线；未运行时为 null。 */
    @get:StringRes
    val channelRes: Int?
        get() = when {
            !running -> null
            channelWired -> R.string.ui3_hardware_uml_wired
            else -> R.string.ui3_hardware_uml_unwired
        }
}

/**
 * AVF 的产品级五状态。评估器的 7 个 `AvfStatus` 在这里收敛成五个用户动作
 * 不同的结论；原因文本仍由评估器给出，页面不自行推断：
 * - READY → 预检通过
 * - NEEDS_AUTHORIZATION → 待授权（唯一提供授权入口的状态）
 * - HIDDEN_API_BLOCKED / REFLECTION_UNAVAILABLE → 接口受限（授权无效）
 * - MODE_UNSUPPORTED / UNSUPPORTED → 不支持（设备或系统不满足）
 * - MODE_UNKNOWN → 状态未知（读不到，不判失败）
 */
enum class HardwareAvfState(
    @StringRes val labelRes: Int,
    @StringRes val fallbackReasonRes: Int,
    val offersAuthorization: Boolean,
) {
    Ready(
        labelRes = R.string.ui3_hardware_avf_ready,
        fallbackReasonRes = R.string.ui3_hardware_avf_ready_reason,
        offersAuthorization = false,
    ),
    NeedsAuthorization(
        labelRes = R.string.ui3_hardware_avf_needs_authorization,
        fallbackReasonRes = R.string.ui3_hardware_avf_needs_authorization_reason,
        offersAuthorization = true,
    ),
    InterfaceBlocked(
        labelRes = R.string.ui3_hardware_avf_interface_blocked,
        fallbackReasonRes = R.string.ui3_hardware_avf_interface_blocked_reason,
        offersAuthorization = false,
    ),
    Unsupported(
        labelRes = R.string.ui3_hardware_avf_unsupported,
        fallbackReasonRes = R.string.ui3_hardware_avf_unsupported_reason,
        offersAuthorization = false,
    ),
    Unknown(
        labelRes = R.string.ui3_hardware_avf_unknown,
        fallbackReasonRes = R.string.ui3_hardware_avf_unknown_reason,
        offersAuthorization = false,
    );

    companion object {
        /** Kotlin 2.2 对 Java 枚举做穷尽检查：新增状态会先编译失败。 */
        fun of(status: AvfCapabilityEvaluator.AvfStatus): HardwareAvfState = when (status) {
            AvfCapabilityEvaluator.AvfStatus.READY -> Ready
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> NeedsAuthorization
            AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED,
            AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE,
            -> InterfaceBlocked
            AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED,
            AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED,
            -> Unsupported
            AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN -> Unknown
        }
    }
}

/** 完整参数表的一行；值可以是直接文本、字符串资源，或缺失（页面显示占位符）。 */
data class HardwareDetailUi(
    @StringRes val labelRes: Int,
    val value: HardwareDetailValue?,
)

sealed interface HardwareDetailValue {
    data class Text(val text: String) : HardwareDetailValue
    data class Resource(@StringRes val res: Int) : HardwareDetailValue

    /** 带占位符的字符串资源（版本组合等）；null 值才代表“未提供”。 */
    data class Template(@StringRes val res: Int, val args: List<String>) : HardwareDetailValue
}

data class HardwareDetailGroup(
    @StringRes val titleRes: Int,
    val rows: List<HardwareDetailUi>,
)

/**
 * 设备公开参数。`alias` 是用户或宿主给出的稳定别名，优先于 [model]；
 * 这里刻意不接受设备序列号、电话标识、Android ID、网络硬件地址或完整指纹字段。
 */
data class HardwareDeviceFacts(
    val alias: String? = null,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val socModel: String? = null,
    val kernelVersion: String? = null,
    val screenSpec: String? = null,
    val gpuRenderer: String? = null,
    val selinuxStatus: String? = null,
)

/** 充电状态；[Unknown] 表示本次读数没有携带该信息，页面不臆测。 */
enum class HardwareBatteryCharging { Unknown, Charging, Full, Discharging }

/** 电池读数；呼入方把 `EXTRA_TEMPERATURE` 的 0.1°C 单位换算后再传入。 */
data class HardwareBatteryFacts(
    val percent: Int? = null,
    val temperatureCelsius: Float? = null,
    val charging: HardwareBatteryCharging = HardwareBatteryCharging.Unknown,
)

/**
 * 引擎输入：AVF 判定与原因来自 `AvfCapabilityEvaluator.AvfReadiness`，
 * QEMU 可用性来自既有启动车道事实，UML 两个读数来自 `UmlFaceStatusRegistry`。
 */
data class HardwareEngineFacts(
    val avfStatus: AvfCapabilityEvaluator.AvfStatus,
    val avfReasons: List<String> = emptyList(),
    val avfModeSummary: String? = null,
    val qemuAvailable: Boolean,
    val umlRunningWidth: Int = 0,
    val umlChannelWired: Boolean = false,
)

/** 摘要与圆环的确定性格格式化（Locale.US，保证截图与单测稳定）。 */
object HardwareFormat {

    /** 委托单一格式化源（UI3-12），与工作站页同一套读数口径。 */
    fun gigabytes(bytes: Long): String = formatGigabytes(bytes)

    fun percent(value: Int): String = "${value.coerceIn(0, 100)}%"

    fun temperatureCelsius(celsius: Float?): String? {
        if (celsius == null || !celsius.isFinite()) {
            return null
        }
        return String.format(Locale.US, "%.1f °C", celsius)
    }
}

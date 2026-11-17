package io.github.nyzeep.orchestella.ui.screens.dashboard

import io.github.nyzeep.orchestella.ui.screens.common.StorageFacts

import androidx.annotation.StringRes
import io.github.nyzeep.orchestella.InstanceCardUiModel
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.startup.DashboardSnapshot
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import kotlin.math.roundToInt

/**
 * 工作站页的纯数据状态。
 *
 * 页面不读 Android Context，也不重新测量任何东西：[from] 只把
 * [DashboardSnapshot]（实例列表、主实例、宿主容量）与 [InstanceCardUiModel]
 * （单实例展示事实）折算成渲染输入。存储数字来自调用方传入的 [StorageFacts]，
 * 生产路径用 [StorageFacts.from] 复用快照里已有的磁盘统计读数，不新增测量。
 */
data class DashboardUiState(
    val instanceCount: Int,
    val runningCount: Int,
    val primary: DashboardInstanceUi?,
    val storage: DashboardStorageUi,
    val cpu: DashboardCpuUi,
    val ram: DashboardRamUi,
    val recent: List<DashboardRecentUi>,
) {
    val hasInstances: Boolean get() = instanceCount > 0

    companion object {
        /** 最近实例一行最多三条；再多的实例交给实例页。 */
        const val MAX_RECENT = 3

        private const val GIB = 1024L * 1024L * 1024L
        private const val DEFAULT_CORES = 2
        private const val DEFAULT_RAM_MB = 2048
        private const val DEFAULT_DISK_BYTES = 8L * GIB

        /**
         * @param card 主实例的展示模型；来自其他实例或为 null 时按快照自身状态
         *   渲染，避免调用方传错卡片后状态 chip 与实例不符。
         * @param engineLabel 引擎 chip 文案；由接线方（UI3-12）按实际启动策略传入，
         *   null 或空白时不显示 chip，而不是猜一个引擎。
         */
        fun from(
            snapshot: DashboardSnapshot,
            card: InstanceCardUiModel?,
            storage: StorageFacts,
            engineLabel: String? = null,
        ): DashboardUiState {
            val instances = snapshot.instances
            val primary = snapshot.primary
            // 同一实例的卡也可能是上一轮状态的快照（3 秒刷新期间实例刚被停止/崩溃），
            // 因此卡必须与实例状态一致才可采信；否则整体回退到快照事实。
            val primaryCard = card?.takeIf { model ->
                primary != null &&
                    model.instanceId == primary.id() &&
                    model.state == primary.state()
            }
            val primaryUi = primary?.let { instance ->
                val hardware = instance.hardwareConfig()
                DashboardInstanceUi(
                    id = instance.id(),
                    distroId = instance.distro().id(),
                    name = instanceName(instance),
                    status = DashboardStatus.of(instance.state()),
                    engineLabel = engineLabel?.trim()?.takeIf { it.isNotEmpty() },
                    cpuCores = primaryCard?.cpuCores ?: hardware?.cpuCores() ?: DEFAULT_CORES,
                    ramMb = primaryCard?.ramMb ?: hardware?.ramMegabytes() ?: DEFAULT_RAM_MB,
                    diskGb = primaryCard?.diskGb
                        ?: ((hardware?.diskCapacityBytes() ?: DEFAULT_DISK_BYTES) / GIB).toInt(),
                    canOpenTerminal = primaryCard?.canOpenTerminal
                        ?: DashboardStatus.of(instance.state()).canOpenTerminal,
                )
            }

            val hostCores = snapshot.hardwareProfile.totalCores().coerceAtLeast(1)
            val instanceCores = primaryUi?.cpuCores ?: 0
            val cpuFraction = if (primaryUi == null) {
                0f
            } else {
                (instanceCores.toFloat() / hostCores).coerceIn(0f, 1f)
            }

            val ramTotal = snapshot.hardwareProfile.totalRamBytes().coerceAtLeast(0L)
            val ramAvailable = snapshot.hardwareProfile.availRamBytes().coerceIn(0L, ramTotal)
            val ramUsed = if (primaryUi == null) 0L else ramTotal - ramAvailable
            val ramFraction = if (primaryUi == null || ramTotal <= 0L) {
                0f
            } else {
                (ramUsed.toFloat() / ramTotal).coerceIn(0f, 1f)
            }

            val recent = instances
                .asSequence()
                .filter { it.id() != primary?.id() }
                .sortedByDescending { it.updatedAtMillis() }
                .take(MAX_RECENT)
                .map { instance ->
                    DashboardRecentUi(
                        id = instance.id(),
                        distroId = instance.distro().id(),
                        name = instanceName(instance),
                        status = DashboardStatus.of(instance.state()),
                    )
                }
                .toList()

            return DashboardUiState(
                instanceCount = instances.size,
                runningCount = instances.count { it.state() == InstanceLifecycle.State.RUNNING },
                primary = primaryUi,
                storage = DashboardStorageUi(
                    totalBytes = storage.totalBytes.coerceAtLeast(0L),
                    usedBytes = storage.usedBytes,
                    fraction = storage.fraction,
                    percent = storage.usedPercent,
                ),
                cpu = DashboardCpuUi(
                    hostCores = hostCores,
                    instanceCores = instanceCores,
                    fraction = cpuFraction,
                    enabled = primaryUi != null,
                ),
                ram = DashboardRamUi(
                    usedBytes = ramUsed,
                    totalBytes = ramTotal,
                    fraction = ramFraction,
                    enabled = primaryUi != null,
                ),
                recent = recent,
            )
        }

        private fun instanceName(instance: InstanceLifecycle.Instance): String {
            val distroId = instance.distro().id()
            val display = Oe3DistroIcons.brandOf(distroId)?.displayName
                ?: distroId.replaceFirstChar { it.uppercase() }
            val version = instance.distro().version().trim()
            return if (version.isEmpty()) display else "$display $version"
        }
    }
}

/** 主实例卡的展示事实；状态词表见 [DashboardStatus]。 */
data class DashboardInstanceUi(
    val id: String,
    val distroId: String,
    val name: String,
    val status: DashboardStatus,
    val engineLabel: String?,
    val cpuCores: Int,
    val ramMb: Int,
    val diskGb: Int,
    val canOpenTerminal: Boolean,
)

/** 存储环与已用/总量读数的纯数值形式；格式化留给页面。 */
data class DashboardStorageUi(
    val totalBytes: Long,
    val usedBytes: Long,
    val fraction: Float,
    val percent: Int,
)

/** CPU 紧凑卡：实例分配的 vCPU 相对宿主核心数。 */
data class DashboardCpuUi(
    val hostCores: Int,
    val instanceCores: Int,
    val fraction: Float,
    val enabled: Boolean,
)

/** RAM 紧凑卡：宿主已用内存相对总量。 */
data class DashboardRamUi(
    val usedBytes: Long,
    val totalBytes: Long,
    val fraction: Float,
    val enabled: Boolean,
)

/** 最近实例行；点击进入实例详情由 [DashboardActions.openInstance] 承担。 */
data class DashboardRecentUi(
    val id: String,
    val distroId: String,
    val name: String,
    val status: DashboardStatus,
)

/** 状态 chip 的语义权重；颜色只是辅助，文字始终表达状态。 */
enum class DashboardStatusTone { Active, Busy, Idle, Error }

/**
 * 工作站只需要的七个状态词。每个词都带字符串资源，确保 chip 永远有可读文本，
 * 不靠颜色单独表意。
 */
enum class DashboardStatus(
    @StringRes val labelRes: Int,
    val tone: DashboardStatusTone,
) {
    Running(R.string.ui3_dashboard_status_running, DashboardStatusTone.Active),
    Provisioning(R.string.ui3_dashboard_status_provisioning, DashboardStatusTone.Busy),
    Stopped(R.string.ui3_dashboard_status_stopped, DashboardStatusTone.Idle),
    Suspended(R.string.ui3_dashboard_status_suspended, DashboardStatusTone.Idle),
    Interrupted(R.string.ui3_dashboard_status_interrupted, DashboardStatusTone.Error),
    Crashed(R.string.ui3_dashboard_status_crashed, DashboardStatusTone.Error),
    Created(R.string.ui3_dashboard_status_created, DashboardStatusTone.Idle);

    /** 终端入口只对运行中实例开放；其余状态由 chip 文案解释原因。 */
    val canOpenTerminal: Boolean get() = this == Running

    companion object {
        /**
         * Kotlin 2.2 对 Java 枚举做穷尽检查：新增 [InstanceLifecycle.State] 常量会
         * 让这里编译失败，必须先补词表与验收，而不是静默落到某个旧状态。
         */
        fun of(state: InstanceLifecycle.State): DashboardStatus = when (state) {
            InstanceLifecycle.State.RUNNING -> Running
            InstanceLifecycle.State.PROVISIONING -> Provisioning
            InstanceLifecycle.State.STOPPED -> Stopped
            InstanceLifecycle.State.SUSPENDED -> Suspended
            InstanceLifecycle.State.INTERRUPTED -> Interrupted
            InstanceLifecycle.State.CRASHED -> Crashed
            InstanceLifecycle.State.CREATED -> Created
        }
    }
}

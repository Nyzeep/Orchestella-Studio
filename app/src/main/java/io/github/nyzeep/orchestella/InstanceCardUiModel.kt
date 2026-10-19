package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle

/**
 * Strategy-neutral presentation model for UTM-style instance cards (Ticket 52 / M4).
 *
 * <p>Encapsulates all presentation facts, badge labels, breathing animation state,
 * and button enablement rules derived from {@link InstanceLifecycle.Instance}.
 * Designed for 100% pure JVM unit testability.
 */
data class InstanceCardUiModel(
    val instanceId: String,
    val shortId: String,
    val distroId: String,
    val distroDisplayName: String,
    val distroVersion: String,
    val state: InstanceLifecycle.State,
    val stateBadgeText: String,
    val stateBadgeColorRes: Int,
    val stateBadgeBgColorRes: Int,
    val isBreathing: Boolean,
    val hardwareSpecsText: String,
    val ipAddress: String = "离线",
    val cpuCores: Int,
    val ramMb: Int,
    val diskGb: Int,
    val gpuEnabled: Boolean,
    val canLaunch: Boolean,
    val canOpenTerminal: Boolean,
    val canLaunchDesktop: Boolean,
    val canConfigureHardware: Boolean,
    val canClone: Boolean = false,
    val canDelete: Boolean,
    val hasCrashDiagnostic: Boolean,
    val crashDiagnostic: InstanceLifecycle.CrashDiagnostic?
) {
    companion object {
        @JvmOverloads
        fun from(
            instance: InstanceLifecycle.Instance,
            strategyBadge: String = "QEMU 软模拟",
            supportsDesktop: Boolean = false,
            guestIp: String? = null
        ): InstanceCardUiModel {
            val distroId = instance.distro().id()
            val distroDisplayName = distroId.replaceFirstChar { it.uppercase() }
            val shortId = if (instance.id().length > 8) instance.id().substring(0, 8) else instance.id()
            val state = instance.state()

            val isRunning = state == InstanceLifecycle.State.RUNNING
            val isStopped = state == InstanceLifecycle.State.STOPPED
            val isCrashed = state == InstanceLifecycle.State.CRASHED
            val isCreated = state == InstanceLifecycle.State.CREATED
            val isProvisioning = state == InstanceLifecycle.State.PROVISIONING

            val stateBadgeText = when (state) {
                InstanceLifecycle.State.RUNNING -> "● 运行中 · $strategyBadge"
                InstanceLifecycle.State.STOPPED -> "■ 已停止"
                InstanceLifecycle.State.CRASHED -> "▲ 异常终止（点击查看诊断）"
                InstanceLifecycle.State.INTERRUPTED -> "⚠️ 运行中断"
                InstanceLifecycle.State.SUSPENDED -> "⏸ 已挂起"
                InstanceLifecycle.State.CREATED -> "○ 已创建"
                InstanceLifecycle.State.PROVISIONING -> "⏳ 初始化装配中…"
            }

            val (stateColor, stateBgColor) = when (state) {
                InstanceLifecycle.State.RUNNING -> Pair(R.color.status_running, R.color.status_running_bg)
                InstanceLifecycle.State.STOPPED -> Pair(R.color.status_stopped, R.color.status_stopped_bg)
                InstanceLifecycle.State.CRASHED -> Pair(R.color.status_crashed, R.color.status_crashed_bg)
                InstanceLifecycle.State.INTERRUPTED -> Pair(R.color.status_crashed, R.color.status_crashed_bg)
                InstanceLifecycle.State.SUSPENDED -> Pair(R.color.status_suspended, R.color.status_suspended_bg)
                InstanceLifecycle.State.PROVISIONING -> Pair(R.color.status_provisioning, R.color.status_provisioning_bg)
                InstanceLifecycle.State.CREATED -> Pair(R.color.status_created, R.color.status_created_bg)
            }

            val hw = instance.hardwareConfig()
            val cpuCores = hw?.cpuCores() ?: 2
            val ramMb = hw?.ramMegabytes() ?: 2048
            val diskGb = (hw?.diskCapacityBytes() ?: (8L * 1024L * 1024L * 1024L)).let { (it / (1024L * 1024L * 1024L)).toInt() }
            val gpuEnabled = hw?.gpuAccelerationEnabled() ?: true

            val hwText = buildString {
                append("$cpuCores vCPU · $ramMb MB · $diskGb GB")
                if (gpuEnabled) {
                    append(" · GPU加速")
                }
            }

            val ipAddress = when {
                !isRunning -> "离线"
                guestIp != null -> guestIp
                else -> "运行中"
            }

            return InstanceCardUiModel(
                instanceId = instance.id(),
                shortId = shortId,
                distroId = distroId,
                distroDisplayName = distroDisplayName,
                distroVersion = instance.distro().version(),
                state = state,
                stateBadgeText = stateBadgeText,
                stateBadgeColorRes = stateColor,
                stateBadgeBgColorRes = stateBgColor,
                isBreathing = isRunning,
                hardwareSpecsText = hwText,
                ipAddress = ipAddress,
                cpuCores = cpuCores,
                ramMb = ramMb,
                diskGb = diskGb,
                gpuEnabled = gpuEnabled,
                canLaunch = isStopped || isCreated || state == InstanceLifecycle.State.INTERRUPTED,
                canOpenTerminal = isRunning,
                canLaunchDesktop = isRunning && supportsDesktop,
                canConfigureHardware = isStopped || isCreated,
                canClone = isStopped || isCreated,
                canDelete = isStopped || isCreated || isCrashed,
                hasCrashDiagnostic = instance.crashDiagnostic() != null,
                crashDiagnostic = instance.crashDiagnostic()
            )
        }
    }
}

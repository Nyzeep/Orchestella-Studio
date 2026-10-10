package io.github.nyzeep.orchestella.ui

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.InstanceActionSafety
import io.github.nyzeep.orchestella.InstanceHardwareConfigEditor
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import java.io.IOException
import java.nio.file.Files
import java.time.Clock

/**
 * Pure JVM UI ViewModel and state machine for Instance Detail Console (Ticket 04).
 *
 * <p>Decouples instance detail presentation, hardware modification guardrails,
 * two-step permanent deletion, and runtime lifecycle actions from Android Views.
 */
class InstanceDetailViewModel(
    val instanceStore: InstanceLifecycle.FileStore,
    val controller: InstanceLifecycle.Controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC()),
    val hostProfile: HostHardwareProfile? = null,
    val supportsDesktop: Boolean = false
) {

    sealed class UpdateHardwareResult {
        data class Success(val instance: InstanceLifecycle.Instance, val message: String) : UpdateHardwareResult()
        data class Rejected(val reason: String) : UpdateHardwareResult()
        data class Error(val message: String) : UpdateHardwareResult()
    }

    data class InstanceDetailUiState(
        val instance: InstanceLifecycle.Instance,
        val instanceId: String,
        val distroId: String,
        val distroDisplayName: String,
        val distroVersion: String,
        val hostname: String,
        val defaultUser: String,
        val state: InstanceLifecycle.State,
        val stateBadgeText: String,
        val stateBadgeColorRes: Int,
        val ipAddress: String,
        val vCpu: Int,
        val ramMb: Int,
        val diskCapacityBytes: Long,
        val diskCapacityGb: Int,
        val diskPhysicalBytes: Long,
        val gpuAccelerationEnabled: Boolean,
        val canOpenTerminal: Boolean,
        val canLaunchDesktop: Boolean,
        val canPowerOn: Boolean,
        val canPowerOff: Boolean,
        val canModifyHardware: Boolean,
        val hardwareModificationRejectionReason: String?,
        val canClone: Boolean,
        val canResetState: Boolean,
        val canDelete: Boolean,
        val hasCrashDiagnostic: Boolean,
        val crashDiagnostic: InstanceLifecycle.CrashDiagnostic?
    )

    var uiState: InstanceDetailUiState? = null
        private set

    var onStateChanged: ((InstanceDetailUiState?) -> Unit)? = null

    fun loadInstance(instanceId: String): InstanceDetailUiState? {
        val instance = try {
            instanceStore.load(instanceId)
        } catch (_: IOException) {
            null
        }
        val newState = instance?.let { mapToUiState(it) }
        uiState = newState
        onStateChanged?.invoke(newState)
        return newState
    }

    fun updateHardwareConfig(vCpu: Int, ramMb: Int, expandedDiskBytes: Long? = null): UpdateHardwareResult {
        val currentInstance = uiState?.instance ?: return UpdateHardwareResult.Error("实例未加载")
        if (!InstanceActionSafety.canUpdateHardware(currentInstance.state())) {
            return UpdateHardwareResult.Rejected("请先关机后再调整硬件参数")
        }

        return try {
            val currentHw = currentInstance.hardwareConfig() ?: VmHardwareConfig.defaultConfig()
            val newConfig = VmHardwareConfig.builder()
                .cpuCores(vCpu)
                .ramMegabytes(ramMb)
                .diskCapacityBytes(currentHw.diskCapacityBytes())
                .gpuAccelerationEnabled(currentHw.gpuAccelerationEnabled())
                .extraKernelArguments(currentHw.extraKernelArguments())
                .build()

            val editResult = InstanceHardwareConfigEditor.apply(
                currentInstance,
                newConfig,
                expandedDiskBytes,
                controller,
                hostProfile,
                null
            )
            val updatedUiState = mapToUiState(editResult.updatedInstance)
            uiState = updatedUiState
            onStateChanged?.invoke(updatedUiState)
            UpdateHardwareResult.Success(editResult.updatedInstance, editResult.noticeMessage)
        } catch (e: IllegalStateException) {
            UpdateHardwareResult.Rejected(e.message ?: "硬件参数修改被拒绝")
        } catch (e: Exception) {
            UpdateHardwareResult.Error(e.message ?: "硬件参数更新失败")
        }
    }

    fun requestDelete(confirmName: String): Boolean {
        val current = uiState?.instance ?: return false
        if (confirmName != current.id()) {
            return false
        }
        if (!InstanceActionSafety.canDelete(current.state())) {
            return false
        }
        return try {
            controller.delete(current.id())
            uiState = null
            onStateChanged?.invoke(null)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun cloneInstance(): InstanceLifecycle.Instance? {
        val current = uiState?.instance ?: return null
        if (!InstanceActionSafety.canClone(current.state())) {
            return null
        }
        return try {
            val cloned = controller.cloneInstance(current.id())
            cloned
        } catch (_: Exception) {
            null
        }
    }

    fun resetDeadlockState(): InstanceLifecycle.Instance? {
        val current = uiState?.instance ?: return null
        return try {
            val reconciled = controller.reconcileAfterHostDeath(current.id(), InstanceLifecycle.State.STOPPED)
            loadInstance(current.id())
            reconciled
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToUiState(instance: InstanceLifecycle.Instance): InstanceDetailUiState {
        val state = instance.state()
        val isRunning = state == InstanceLifecycle.State.RUNNING
        val isStopped = state == InstanceLifecycle.State.STOPPED
        val isCreated = state == InstanceLifecycle.State.CREATED
        val isCrashed = state == InstanceLifecycle.State.CRASHED
        val isInterrupted = state == InstanceLifecycle.State.INTERRUPTED

        val distroId = instance.distro().id()
        val distroDisplayName = distroId.replaceFirstChar { it.uppercase() }
        val hw = instance.hardwareConfig() ?: VmHardwareConfig.defaultConfig()

        val diskPhysicalBytes = try {
            if (Files.exists(instance.rootVolume())) Files.size(instance.rootVolume()) else 0L
        } catch (_: Exception) {
            0L
        }

        val diskGb = (hw.diskCapacityBytes() / (1024L * 1024L * 1024L)).toInt()

        val canModifyHw = InstanceActionSafety.canUpdateHardware(state)
        val hwRejectionReason = if (!canModifyHw) "请先关机后再调整硬件参数" else null

        val (badgeText, badgeColorRes) = when (state) {
            InstanceLifecycle.State.RUNNING -> Pair("● 运行中", R.color.status_running)
            InstanceLifecycle.State.STOPPED -> Pair("○ 已关机", R.color.status_stopped)
            InstanceLifecycle.State.CRASHED -> Pair("⚠ 崩溃告警", R.color.status_crashed)
            InstanceLifecycle.State.INTERRUPTED -> Pair("⏸ 已挂起", R.color.status_suspended)
            InstanceLifecycle.State.CREATED -> Pair("○ 已创建", R.color.status_created)
            InstanceLifecycle.State.PROVISIONING -> Pair("⏳ 装配中", R.color.status_provisioning)
        }

        val ipAddress = if (isRunning) "10.0.2.15" else "离线"

        return InstanceDetailUiState(
            instance = instance,
            instanceId = instance.id(),
            distroId = distroId,
            distroDisplayName = distroDisplayName,
            distroVersion = instance.distro().version(),
            hostname = instance.hostname(),
            defaultUser = instance.defaultUser(),
            state = state,
            stateBadgeText = badgeText,
            stateBadgeColorRes = badgeColorRes,
            ipAddress = ipAddress,
            vCpu = hw.cpuCores(),
            ramMb = hw.ramMegabytes(),
            diskCapacityBytes = hw.diskCapacityBytes(),
            diskCapacityGb = diskGb,
            diskPhysicalBytes = diskPhysicalBytes,
            gpuAccelerationEnabled = hw.gpuAccelerationEnabled(),
            canOpenTerminal = isRunning,
            canLaunchDesktop = isRunning && supportsDesktop,
            canPowerOn = isStopped || isCreated || isInterrupted,
            canPowerOff = isRunning,
            canModifyHardware = canModifyHw,
            hardwareModificationRejectionReason = hwRejectionReason,
            canClone = InstanceActionSafety.canClone(state),
            canResetState = isRunning || isInterrupted || isCrashed,
            canDelete = InstanceActionSafety.canDelete(state),
            hasCrashDiagnostic = instance.crashDiagnostic() != null,
            crashDiagnostic = instance.crashDiagnostic()
        )
    }
}

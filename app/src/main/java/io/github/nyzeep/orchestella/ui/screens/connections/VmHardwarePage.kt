package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.capability.GpuCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.engineqemu.GpuAccelerationStatus
import io.github.nyzeep.orchestella.runtime.engineqemu.GpuFallbackReason
import io.github.nyzeep.orchestella.runtime.engineqemu.VirglJniBridge
import io.github.nyzeep.orchestella.ui.design.components.OeSlider
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import kotlin.math.roundToInt

/** The exact values handed back to the host when the user saves. */
@Immutable
data class VmHardwareSelection(
    val cpuCores: Int,
    val ramMegabytes: Int,
    val diskCapacityGb: Int,
    val gpuAccelerationEnabled: Boolean,
    /** New absolute disk size when the user expanded it; null keeps the current disk. */
    val expandedDiskBytes: Long?,
)

/**
 * G-07: the honest state behind the GPU toggle subtitle. The toggle itself
 * stays a pure config field (runtime fallback is decided by the G-06 gate at
 * launch); this enum only picks what the UI may honestly claim.
 *
 * Resolution order: a live session's [GpuAccelerationStatus] wins
 * (ACTIVE / FALLBACK carry the launch's real outcome); DISABLED means the flag
 * was off at launch, so the subtitle falls back to the static copy. Without a
 * session the static capability declaration rules
 * ([GpuCapabilityEvaluator.virglNativeAvailable]).
 */
enum class GpuToggleStatus {
    /** No session: the VirGL driver is packaged, enabling the toggle may use it. */
    NATIVE_AVAILABLE,
    /** No session: the driver is not packaged, enabling falls back to 2D at launch. */
    NATIVE_UNAVAILABLE,
    /** Session running: this launch kept the gl-device arguments (wired, 未验证). */
    SESSION_ACTIVE,
    /** Session running: this launch fell back to the 2D device (reason in the subtitle). */
    SESSION_FALLBACK,
    ;

    companion object {
        fun resolve(nativeAvailable: Boolean, sessionStatus: GpuAccelerationStatus?): GpuToggleStatus {
            val status = sessionStatus
            if (status != null && status.state() != GpuAccelerationStatus.State.DISABLED) {
                return if (status.gpuDeviceActive()) SESSION_ACTIVE else SESSION_FALLBACK
            }
            return if (nativeAvailable) NATIVE_AVAILABLE else NATIVE_UNAVAILABLE
        }
    }
}

/**
 * Form state for the VM hardware page.
 *
 * Deliberately plain Kotlin over [VmHardwareConfig] + [HostHardwareProfile]:
 * the safety zone and the RED-means-no-save rule live in
 * [HostHardwareProfile.evaluateRamZone] and are not re-implemented here.
 */
class VmHardwarePageState(
    current: VmHardwareConfig,
    private val host: HostHardwareProfile,
    /** G-07 能力声明单源输出：virgl native 静态可用性（驱动是否打包在 UI 进程同 JVM 直接可读）。 */
    gpuNativeAvailable: Boolean,
    /** G-07 会话运行中的实时 GPU 决策快照；null = 无会话，副文案落回静态两态。 */
    val gpuSessionStatus: GpuAccelerationStatus? = null,
) {

    companion object {
        const val DISK_STEP_GB = 1
        const val RAM_STEP_MB = 256
        const val MAX_DISK_GB = 64
        private const val GIB = 1024L * 1024L * 1024L
    }

    val maxCpuCores: Int = minOf(VmHardwareConfig.MAX_CPU_CORES, host.totalCores())
    val minRamMegabytes: Int = VmHardwareConfig.MIN_RAM_MEGABYTES
    val maxRamMegabytes: Int =
        minOf(VmHardwareConfig.MAX_RAM_MEGABYTES, host.totalRamMb())
            .coerceAtLeast(minRamMegabytes)
    val currentDiskGb: Int = (current.diskCapacityBytes() / GIB).toInt().coerceAtLeast(1)
    val maxDiskGb: Int = maxOf(MAX_DISK_GB, currentDiskGb)

    val recommendedCpuCores: Int = host.recommendedCpuCores().coerceIn(1, maxCpuCores)
    val recommendedRamMegabytes: Int =
        host.recommendedRamMegabytesAligned(RAM_STEP_MB)
            .coerceIn(minRamMegabytes, maxRamMegabytes)

    var cpuCores by mutableIntStateOf(current.cpuCores().coerceIn(1, maxCpuCores))
        private set
    var ramMegabytes by mutableIntStateOf(
        current.ramMegabytes().coerceIn(minRamMegabytes, maxRamMegabytes),
    )
        private set
    var diskGb by mutableIntStateOf(currentDiskGb)
        private set
    var gpuAccelerationEnabled by mutableStateOf(current.gpuAccelerationEnabled())
        private set

    /** G-07 副文案三态（构造时定一次；会话快照与静态能力声明在页内不变）。 */
    val gpuToggleStatus: GpuToggleStatus =
        GpuToggleStatus.resolve(gpuNativeAvailable, gpuSessionStatus)

    /** [GpuToggleStatus.SESSION_FALLBACK] 时的降级原因；其余状态为 null。 */
    val gpuFallbackReason: GpuFallbackReason? =
        if (gpuToggleStatus == GpuToggleStatus.SESSION_FALLBACK) gpuSessionStatus?.reason() else null

    val ramIntervals: Int =
        ((maxRamMegabytes - minRamMegabytes) / RAM_STEP_MB).coerceAtLeast(1)
    val ramSteps: Int = (ramIntervals - 1).coerceAtLeast(0)
    val diskSteps: Int = (maxDiskGb - currentDiskGb - 1).coerceAtLeast(0)

    val ramZone: HostHardwareProfile.SafetyZone
        get() = host.evaluateRamZone(ramMegabytes)

    val canSave: Boolean
        get() = ramZone != HostHardwareProfile.SafetyZone.RED &&
            cpuCores in 1..maxCpuCores

    fun setCpuCores(value: Float) {
        cpuCores = value.roundToInt().coerceIn(1, maxCpuCores)
    }

    fun setRamMegabytes(value: Float) {
        ramMegabytes = value.roundToInt().coerceIn(minRamMegabytes, maxRamMegabytes)
    }

    fun setDiskGb(value: Float) {
        diskGb = value.roundToInt().coerceIn(currentDiskGb, maxDiskGb)
    }

    fun setGpuAcceleration(enabled: Boolean) {
        gpuAccelerationEnabled = enabled
    }

    fun selection(): VmHardwareSelection {
        val expanded = if (diskGb > currentDiskGb) {
            diskGb.toLong() * GIB
        } else {
            null
        }
        return VmHardwareSelection(
            cpuCores = cpuCores,
            ramMegabytes = ramMegabytes,
            diskCapacityGb = diskGb,
            gpuAccelerationEnabled = gpuAccelerationEnabled,
            expandedDiskBytes = expanded,
        )
    }
}

/**
 * VM hardware secondary page (vCPU / RAM / disk / GPU).
 *
 * Save is disabled while RAM sits in the profile's RED zone; the label and
 * colour come straight from [HostHardwareProfile.SafetyZone], so the old
 * "RED blocks saving" promise is preserved rather than re-invented.
 *
 * G-07: the GPU toggle's subtitle is honest — the host may pass the live
 * session's [GpuAccelerationStatus] (when the instance is running); without a
 * session the static capability declaration
 * ([GpuCapabilityEvaluator] + [VirglJniBridge], same JVM as the engine) picks
 * the copy. The toggle never pretends a checked box equals GPU acceleration.
 */
@Composable
fun VmHardwarePage(
    instance: io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Instance,
    hostProfile: HostHardwareProfile,
    onBack: () -> Unit,
    onSaved: (VmHardwareSelection) -> Unit,
    modifier: Modifier = Modifier,
    gpuAccelerationStatus: GpuAccelerationStatus? = null,
) {
    val current = instance.hardwareConfig() ?: VmHardwareConfig.defaultConfig()
    val gpuNativeAvailable = androidx.compose.runtime.remember {
        GpuCapabilityEvaluator.evaluate(VirglJniBridge.isAvailable()).virglNativeAvailable()
    }
    val state = androidx.compose.runtime.remember(
        current, hostProfile, gpuNativeAvailable, gpuAccelerationStatus,
    ) {
        VmHardwarePageState(current, hostProfile, gpuNativeAvailable, gpuAccelerationStatus)
    }
    VmHardwareContent(
        state = state,
        onBack = onBack,
        onCpuChange = state::setCpuCores,
        onRamChange = state::setRamMegabytes,
        onDiskChange = state::setDiskGb,
        onGpuChange = state::setGpuAcceleration,
        onSave = { onSaved(state.selection()) },
        modifier = modifier,
    )
}

@Composable
fun VmHardwareContent(
    state: VmHardwarePageState,
    onBack: () -> Unit,
    onCpuChange: (Float) -> Unit,
    onRamChange: (Float) -> Unit,
    onDiskChange: (Float) -> Unit,
    onGpuChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    OeScaffold(
        title = stringResource(R.string.connections_vm_hardware_title),
        modifier = modifier,
        leading = { ConnectionsBackButton(onBack = onBack) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Oe3Spacing.pageInset,
                    end = Oe3Spacing.pageInset,
                    top = Oe3Spacing.sm,
                    bottom = Oe3Spacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
        ) {
            Oe3Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.connections_vm_cpu_section),
                        style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                        color = colors.onSurface.oe3Color(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.connections_vm_cpu_value, state.cpuCores),
                        style = Oe3Typography.style(Oe3TextRole.Mono),
                        color = colors.primary.oe3Color(),
                    )
                }
                Spacer(Modifier.height(Oe3Spacing.xs))
                OeSlider(
                    value = state.cpuCores.toFloat(),
                    onValueChange = onCpuChange,
                    valueRange = 1f..state.maxCpuCores.toFloat(),
                    steps = (state.maxCpuCores - 2).coerceAtLeast(0),
                    stateDescription = state.cpuCores.toString(),
                    onValueChangeFinished = null,
                    modifier = Modifier.testTag("connections:vm:cpu"),
                )
                Text(
                    text = stringResource(
                        R.string.connections_vm_cpu_recommended,
                        state.recommendedCpuCores,
                        state.maxCpuCores,
                    ),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                )
            }

            Oe3Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.connections_vm_ram_section),
                        style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                        color = colors.onSurface.oe3Color(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.connections_vm_ram_value, state.ramMegabytes),
                        style = Oe3Typography.style(Oe3TextRole.Mono),
                        color = colors.primary.oe3Color(),
                    )
                }
                Spacer(Modifier.height(Oe3Spacing.xs))
                OeSlider(
                    value = state.ramMegabytes.toFloat(),
                    onValueChange = onRamChange,
                    valueRange = state.minRamMegabytes.toFloat()..state.maxRamMegabytes.toFloat(),
                    steps = state.ramSteps,
                    stateDescription = state.ramMegabytes.toString(),
                    modifier = Modifier.testTag("connections:vm:ram"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
                    Text(
                        text = stringResource(
                            R.string.connections_vm_ram_recommended,
                            state.recommendedRamMegabytes,
                            state.maxRamMegabytes,
                        ),
                        style = Oe3Typography.style(Oe3TextRole.Caption),
                        color = colors.onSurfaceVariant.oe3Color(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Oe3Spacing.xs))
                RamZoneBadge(zone = state.ramZone)
            }

            Oe3Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.connections_vm_disk_section),
                        style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                        color = colors.onSurface.oe3Color(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.connections_vm_disk_current, state.currentDiskGb),
                        style = Oe3Typography.style(Oe3TextRole.Caption),
                        color = colors.onSurfaceVariant.oe3Color(),
                    )
                }
                Spacer(Modifier.height(Oe3Spacing.xs))
                OeSlider(
                    value = state.diskGb.toFloat(),
                    onValueChange = onDiskChange,
                    valueRange = state.currentDiskGb.toFloat()..state.maxDiskGb.toFloat(),
                    steps = state.diskSteps,
                    enabled = state.currentDiskGb < state.maxDiskGb,
                    stateDescription = state.diskGb.toString(),
                    modifier = Modifier.testTag("connections:vm:disk"),
                )
                Text(
                    text = if (state.diskGb > state.currentDiskGb) {
                        stringResource(
                            R.string.connections_vm_disk_target,
                            state.diskGb,
                            state.diskGb - state.currentDiskGb,
                        )
                    } else {
                        stringResource(R.string.connections_vm_disk_keep, state.currentDiskGb)
                    },
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                )
            }

            Oe3Card {
                Oe3SwitchRow(
                    title = stringResource(R.string.connections_vm_gpu),
                    subtitle = gpuToggleSubtitle(state),
                    checked = state.gpuAccelerationEnabled,
                    onCheckedChange = onGpuChange,
                    modifier = Modifier.testTag("connections:vm:gpu"),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Oe3Spacing.pageInset,
                    end = Oe3Spacing.pageInset,
                    top = Oe3Spacing.xs,
                    bottom = LocalOe3PageBottomPadding.current,
                ),
            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
        ) {
            if (!state.canSave) {
                Text(
                    text = stringResource(R.string.hardware_ram_zone_red),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.error.oe3Color(),
                    modifier = Modifier.testTag("connections:vm:blocked"),
                )
            }
            Oe3Button(
                text = stringResource(R.string.connections_vm_save),
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connections:vm:save"),
            )
        }
    }
}

/** G-07: honest GPU toggle subtitle, keyed by [GpuToggleStatus] (strings stay in resources). */
@Composable
private fun gpuToggleSubtitle(state: VmHardwarePageState): String = when (state.gpuToggleStatus) {
    GpuToggleStatus.NATIVE_AVAILABLE ->
        stringResource(R.string.connections_vm_gpu_desc_available)
    GpuToggleStatus.NATIVE_UNAVAILABLE ->
        stringResource(R.string.connections_vm_gpu_desc_unavailable)
    GpuToggleStatus.SESSION_ACTIVE ->
        stringResource(R.string.connections_vm_gpu_desc_session_active)
    GpuToggleStatus.SESSION_FALLBACK ->
        stringResource(
            R.string.connections_vm_gpu_desc_session_fallback,
            gpuFallbackReasonText(state.gpuFallbackReason),
        )
}

/** Fallback reason → human text; NONE/null is defensive only (FALLBACK always carries a reason). */
@Composable
private fun gpuFallbackReasonText(reason: GpuFallbackReason?): String = when (reason) {
    GpuFallbackReason.NATIVE_UNAVAILABLE ->
        stringResource(R.string.connections_vm_gpu_reason_native_unavailable)
    GpuFallbackReason.INIT_FAILED ->
        stringResource(R.string.connections_vm_gpu_reason_init_failed)
    GpuFallbackReason.NONE, null ->
        stringResource(R.string.connections_vm_gpu_reason_unknown)
}

@Composable
private fun RamZoneBadge(zone: HostHardwareProfile.SafetyZone) {
    val (label, color) = when (zone) {
        HostHardwareProfile.SafetyZone.GREEN ->
            stringResource(R.string.hardware_ram_zone_green) to colorResource(R.color.zone_green)
        HostHardwareProfile.SafetyZone.YELLOW ->
            stringResource(R.string.hardware_ram_zone_yellow) to colorResource(R.color.zone_yellow)
        HostHardwareProfile.SafetyZone.RED ->
            stringResource(R.string.hardware_ram_zone_red) to colorResource(R.color.zone_red)
    }
    Text(
        text = label,
        style = Oe3Typography.style(Oe3TextRole.Caption),
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(OeShape.sm))
            .padding(horizontal = Oe3Spacing.xs, vertical = Oe3Spacing.xxs)
            .testTag("connections:vm:zone"),
    )
}

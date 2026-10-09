package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.distro.OfficialAlpineCatalog
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog

/**
 * Pure Kotlin decoupled state machine for the first-launch Setup Wizard (Ticket 53, US-28).
 *
 * <p>Maintains step transitions ({@code STEP_DETECT} -> {@code STEP_DISTRO} -> {@code STEP_CONFIG}),
 * accumulates distro selection and hardware allocation derived from {@link HostHardwareProfile},
 * and validates safe parameter bounds independently from Android views.
 */
class SetupWizardModel(
    val hardwareProfile: HostHardwareProfile,
    initialStep: Step = Step.STEP_DETECT,
    initialDistro: DistroOption = DistroOption.ALPINE,
    initialConfig: VmHardwareConfig? = null
) {

    enum class Step {
        STEP_DETECT,
        STEP_DISTRO,
        STEP_CONFIG
    }

    enum class DistroOption(
        val id: String,
        val displayName: String,
        val version: String,
        val tag: String,
        val description: String,
        val approxDownloadSize: String,
        val rootfsFileName: String
    ) {
        ALPINE(
            id = "alpine",
            displayName = "Alpine Linux",
            version = "3.24.1",
            tag = "⚡ 极简轻量 · 约 4MB · 极客容器",
            description = "极简轻巧、约 4MB 极速下载、极低内存占用、完美适配移动端命令行与轻量容器实验",
            approxDownloadSize = "~4 MB",
            rootfsFileName = "alpine-minirootfs-3.24.1-aarch64.tar.gz"
        ),
        DEBIAN(
            id = "debian",
            displayName = "Debian Linux",
            version = "13",
            tag = "🐧 完整生态 · 约 90MB · 桌面工作流",
            description = "生态丰富、开箱即用、预置全套 apt 包管理、适合完整桌面开发与编译工作流",
            approxDownloadSize = "~90 MB",
            rootfsFileName = "debian-13-rootfs.tar.xz"
        );

        fun manifest(): DistroManifest {
            return when (this) {
                ALPINE -> OfficialAlpineCatalog.alpine()
                DEBIAN -> OfficialDebianCatalog.debian()
            }
        }
    }

    var currentStep: Step = initialStep
        private set

    var selectedDistro: DistroOption = initialDistro
        private set

    var hardwareConfig: VmHardwareConfig = initialConfig ?: hardwareProfile.createRecommendedHardwareConfig()
        private set

    var isDownloading: Boolean = false
        private set

    var isCompleted: Boolean = false
        private set

    val stepIndex: Int
        get() = when (currentStep) {
            Step.STEP_DETECT -> 1
            Step.STEP_DISTRO -> 2
            Step.STEP_CONFIG -> 3
        }

    val totalSteps: Int
        get() = 3

    fun canGoBack(): Boolean = currentStep != Step.STEP_DETECT && !isDownloading

    fun canGoNext(): Boolean = currentStep != Step.STEP_CONFIG && !isDownloading

    fun nextStep(): Step {
        check(canGoNext()) { "Cannot advance past step: $currentStep" }
        currentStep = when (currentStep) {
            Step.STEP_DETECT -> Step.STEP_DISTRO
            Step.STEP_DISTRO -> Step.STEP_CONFIG
            Step.STEP_CONFIG -> Step.STEP_CONFIG
        }
        return currentStep
    }

    fun previousStep(): Step {
        check(canGoBack()) { "Cannot retreat before step: $currentStep" }
        currentStep = when (currentStep) {
            Step.STEP_CONFIG -> Step.STEP_DISTRO
            Step.STEP_DISTRO -> Step.STEP_DETECT
            Step.STEP_DETECT -> Step.STEP_DETECT
        }
        return currentStep
    }

    fun goToStep(step: Step) {
        if (!isDownloading) {
            currentStep = step
        }
    }

    fun selectDistro(distro: DistroOption) {
        selectedDistro = distro
    }

    fun getSelectedManifest(): DistroManifest {
        return selectedDistro.manifest()
    }

    fun updateCpuCores(cores: Int) {
        val clamped = cores.coerceIn(VmHardwareConfig.MIN_CPU_CORES, VmHardwareConfig.MAX_CPU_CORES)
        hardwareConfig = VmHardwareConfig.builder()
            .cpuCores(clamped)
            .ramMegabytes(hardwareConfig.ramMegabytes())
            .diskCapacityBytes(hardwareConfig.diskCapacityBytes())
            .gpuAccelerationEnabled(hardwareConfig.gpuAccelerationEnabled())
            .build()
    }

    fun updateRamMegabytes(ramMb: Int) {
        val clamped = ramMb.coerceIn(VmHardwareConfig.MIN_RAM_MEGABYTES, VmHardwareConfig.MAX_RAM_MEGABYTES)
        hardwareConfig = VmHardwareConfig.builder()
            .cpuCores(hardwareConfig.cpuCores())
            .ramMegabytes(clamped)
            .diskCapacityBytes(hardwareConfig.diskCapacityBytes())
            .gpuAccelerationEnabled(hardwareConfig.gpuAccelerationEnabled())
            .build()
    }

    fun updateDiskCapacityBytes(bytes: Long) {
        val clamped = bytes.coerceIn(VmHardwareConfig.MIN_DISK_CAPACITY_BYTES, VmHardwareConfig.MAX_DISK_CAPACITY_BYTES)
        hardwareConfig = VmHardwareConfig.builder()
            .cpuCores(hardwareConfig.cpuCores())
            .ramMegabytes(hardwareConfig.ramMegabytes())
            .diskCapacityBytes(clamped)
            .gpuAccelerationEnabled(hardwareConfig.gpuAccelerationEnabled())
            .build()
    }

    fun updateGpuAcceleration(enabled: Boolean) {
        hardwareConfig = VmHardwareConfig.builder()
            .cpuCores(hardwareConfig.cpuCores())
            .ramMegabytes(hardwareConfig.ramMegabytes())
            .diskCapacityBytes(hardwareConfig.diskCapacityBytes())
            .gpuAccelerationEnabled(enabled)
            .build()
    }

    fun setHardwareConfig(config: VmHardwareConfig) {
        hardwareConfig = config
    }

    fun safetyZone(): HostHardwareProfile.SafetyZone {
        return hardwareProfile.evaluateOverallZone(hardwareConfig.cpuCores(), hardwareConfig.ramMegabytes())
    }

    fun setDownloading(downloading: Boolean) {
        isDownloading = downloading
    }

    fun markCompleted() {
        isDownloading = false
        isCompleted = true
    }

    fun hardwareSpecsSummary(): String {
        val diskGb = hardwareConfig.diskCapacityBytes() / (1024L * 1024L * 1024L)
        val gpuText = if (hardwareConfig.gpuAccelerationEnabled()) " · GPU 加速" else ""
        return "${hardwareConfig.cpuCores()} vCPU · ${hardwareConfig.ramMegabytes()} MB · ${diskGb} GB$gpuText"
    }
}

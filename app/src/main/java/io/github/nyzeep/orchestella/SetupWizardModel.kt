package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest

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
    initialDistro: DistroOption = DistroOption.UBUNTU,
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
        UBUNTU(
            id = "ubuntu",
            displayName = "Ubuntu Linux",
            version = "24.04",
            tag = "🐧 工业标准 · 广泛支持 · 云原生首选",
            description = "工业标准、生态广泛、深度集成云原生开发栈、云原生首选",
            approxDownloadSize = "~30 MB",
            rootfsFileName = "ubuntu-base-24.04.5-base-arm64.tar.gz"
        ),
        DEBIAN(
            id = "debian",
            displayName = "Debian Linux",
            version = "13",
            tag = "🌀 稳如磐石 · 纯净开源 · 经典基石",
            description = "生态丰富、开箱即用、预置全套 apt 包管理、适合完整桌面开发与编译工作流",
            approxDownloadSize = "~90 MB",
            rootfsFileName = "debian-13-rootfs.tar.xz"
        ),
        FEDORA(
            id = "fedora",
            displayName = "Fedora Linux",
            version = "43",
            tag = "🎩 前沿创新 · RPM 生态 · 开发者友好",
            description = "前沿创新、DNF 与 RPM 现代生态、开发者友好且兼容最新技术",
            approxDownloadSize = "~92 MB",
            rootfsFileName = "fedora-43-minimal-rootfs.tar.gz"
        ),
        ARCH(
            id = "arch",
            displayName = "Arch Linux ARM",
            version = "rolling",
            tag = "🏹 极客首选 · 滚动更新 · 软件前沿",
            description = "极客首选、pacman 极速滚动更新、软件前沿与轻量定制",
            approxDownloadSize = "~830 MB",
            rootfsFileName = "ArchLinuxARM-aarch64-latest.tar.gz"
        ),
        OPENSUSE(
            id = "opensuse",
            displayName = "openSUSE Leap",
            version = "15.6",
            tag = "🦎 严谨规范 · YaST 工具 · 企业基线",
            description = "严谨规范、zypper 依赖解析与企业级基线架构，版本化稳定发布",
            approxDownloadSize = "~34 MB",
            rootfsFileName = "opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz"
        ),
        ALMALINUX(
            id = "almalinux",
            displayName = "AlmaLinux",
            version = "9",
            tag = "💼 企业就绪 · RHEL 二进制兼容 · 社区驱动",
            description = "企业级 Linux、1:1 二进制兼容 RHEL、DNF/RPM 稳定生态与长期支持保障",
            approxDownloadSize = "~104 MB",
            rootfsFileName = "AlmaLinux-9-Base-rootfs.tar.gz"
        ),
        ROCKY(
            id = "rocky",
            displayName = "Rocky Linux",
            version = "9.6",
            tag = "🏔️ 坚如磐石 · 企业计算 · 生产级稳定",
            description = "生产级企业发行版、严格遵循上游 RHEL 标准、云原生容器与服务器首选",
            approxDownloadSize = "~104 MB",
            rootfsFileName = "Rocky-9-Container-Base-9.6-20250531.0.aarch64.tar.xz"
        );

        fun manifest(): DistroManifest {
            return DistroCatalogRegistry.getInstance().require(id)
        }

        companion object {
            fun fromId(id: String): DistroOption? {
                return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
            }
        }
    }

    var currentStep: Step = initialStep
        private set

    var selectedDistro: DistroOption = initialDistro
        private set


    var userConfig: io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig =
        io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.defaultRoot()
        private set

    var isCustomUserEnabled: Boolean = false
        private set

    fun enableCustomUser(username: String, password: String, hasSudo: Boolean, hostname: String = "orchestella"): Boolean {
        if (!io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.isValidUsername(username)) {
            return false
        }
        val safeHostname = if (io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.isValidHostname(hostname)) {
            hostname
        } else {
            "orchestella"
        }
        userConfig = io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig(
            username, password, hasSudo, safeHostname
        )
        isCustomUserEnabled = true
        return true
    }

    fun skipToDefaultRoot() {
        userConfig = io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.defaultRoot()
        isCustomUserEnabled = false
    }
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

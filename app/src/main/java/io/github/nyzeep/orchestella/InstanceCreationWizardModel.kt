package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import java.util.Locale

/**
 * Pure Kotlin decoupled state machine for the 4-step Instance Creation Wizard (Ticket 01).
 *
 * <p>Encapsulates:
 * <ul>
 *   <li>Step 1 (STEP_STORAGE): Virtual disk capacity selection (presets & custom) & StatFs preflight check.</li>
 *   <li>Step 2 (STEP_NETWORK_MIRROR): Host network diagnostic awareness & Mirror selector (TUNA, USTC, Aliyun, Official).</li>
 *   <li>Step 3 (STEP_HARDWARE): vCPU cores and RAM manual bounds allocation with safety zone indicator.</li>
 *   <li>Step 4 (STEP_STARTUP_TASKS): Optional first-boot automatic software update scripts tailored per distribution.</li>
 * </ul>
 */
class InstanceCreationWizardModel(
    val distroId: String,
    val distroVersion: String = "latest",
    val hardwareProfile: HostHardwareProfile,
    initialStep: CreationStep = CreationStep.STEP_STORAGE
) {

    enum class CreationStep {
        STEP_STORAGE,
        STEP_NETWORK_MIRROR,
        STEP_HARDWARE,
        STEP_STARTUP_TASKS
    }

    sealed class StorageValidationResult {
        object Valid : StorageValidationResult()
        data class InsufficientSpace(
            val availableBytes: Long,
            val requestedBytes: Long,
            val requiredBytes: Long
        ) : StorageValidationResult()
        data class InvalidRequestedSize(val reason: String) : StorageValidationResult()
    }

    companion object {
        const val RESERVED_HOST_BYTES: Long = 2L * 1024L * 1024L * 1024L // 2 GB host safety reserve
        const val DEFAULT_DISK_BYTES: Long = 8L * 1024L * 1024L * 1024L // 8 GB default
        val DISK_PRESET_GBS = listOf(4, 8, 16, 32)
        const val DRAFT_SCHEMA = "1"
        private val DRAFT_KEYS = setOf("schema", "step", "distro", "version", "diskGb", "cpu", "ram",
            "mirror", "autoUpdate", "username", "hostname", "sudo", "desktop", "podman", "editor", "creating")

        /** Restores safe choices only, against today's catalog and host budgets. Never starts work. */
        @JvmStatic
        fun restoreDraft(draft: Map<String, String>, profile: HostHardwareProfile,
            distroVersions: Map<String, String>, minimumRamMb: (String) -> Int): InstanceCreationWizardModel {
            require(draft.keys == DRAFT_KEYS && draft["schema"] == DRAFT_SCHEMA) { "草稿版本或字段无效，请重新确认创建配置。" }
            fun text(key: String) = requireNotNull(draft[key])
            fun number(key: String) = text(key).toIntOrNull()
                ?: throw IllegalArgumentException("草稿数值无效，请重新确认创建配置。")
            fun flag(key: String): Boolean = when (text(key)) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("草稿选项无效，请重新确认创建配置。")
            }
            val distro = text("distro")
            require(distroVersions[distro] == text("version")) { "发行版清单已变化，请重新选择发行版。" }
            val disk = number("diskGb")
            val cpu = number("cpu")
            val ram = number("ram")
            require(disk in 2..64) { "草稿磁盘容量无效，请重新选择。" }
            require(profile.evaluateOverallZone(cpu, ram) != HostHardwareProfile.SafetyZone.RED
                && ram >= minimumRamMb(distro)) { "设备或镜像资源要求已变化，请重新确认硬件分配。" }
            val username = text("username")
            val hostname = text("hostname")
            require(username.length <= 64 && hostname.length <= 253) { "草稿身份字段过长，请重新填写。" }
            val model = InstanceCreationWizardModel(distro, text("version"), profile,
                CreationStep.valueOf(text("step")))
            model.setCustomDiskSizeGb(disk)
            model.updateCpuCores(cpu)
            model.updateRamMegabytes(ram)
            model.selectMirror(DistroMirrorProvisioner.MirrorSource.valueOf(text("mirror")))
            model.setAutoUpdateOnFirstBoot(flag("autoUpdate"))
            model.updateGuestIdentityDraft(username, hostname, flag("sudo"))
            model.setOptionalProfiles(flag("desktop"), flag("podman"), flag("editor"))
            model.setCreating(flag("creating"))
            return model
        }
    }

    var currentStep: CreationStep = initialStep
        private set

    val stepIndex: Int
        get() = when (currentStep) {
            CreationStep.STEP_STORAGE -> 1
            CreationStep.STEP_NETWORK_MIRROR -> 2
            CreationStep.STEP_HARDWARE -> 3
            CreationStep.STEP_STARTUP_TASKS -> 4
        }

    val totalSteps: Int
        get() = 4

    var requestedDiskBytes: Long = DEFAULT_DISK_BYTES
        private set

    val diskSizeGb: Int
        get() = (requestedDiskBytes / (1024L * 1024L * 1024L)).toInt()

    var mountHostSharedDirectory: Boolean = false
        private set

    var hostSharedDirectoryPath: String? = null
        private set

    var selectedMirror: DistroMirrorProvisioner.MirrorSource = DistroMirrorProvisioner.MirrorSource.MULTI_TIER
        private set

    var hardwareConfig: VmHardwareConfig = run {
        val defaultCores = (hardwareProfile.totalCores() / 2).coerceIn(1, 4)
        hardwareProfile.createRecommendedHardwareConfig().withCpuCores(defaultCores)
    }
        private set

    var autoUpdateOnFirstBoot: Boolean = false
        private set

    var userConfig: io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig =
        io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.defaultRoot()
        private set

    var guestUsernameDraft: String = userConfig.username()
        private set
    var guestHostnameDraft: String = userConfig.hostname()
        private set
    var guestSudoDraft: Boolean = userConfig.hasSudo()
        private set
    var desktopSelected: Boolean = false
        private set
    var podmanSelected: Boolean = false
        private set
    var editorSelected: Boolean = false
        private set

    fun setUserConfig(config: io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig) {
        this.userConfig = config
        updateGuestIdentityDraft(config.username(), config.hostname(), config.hasSudo())
    }

    /** Keeps editable, possibly incomplete identity text without persisting a credential. */
    fun updateGuestIdentityDraft(username: String, hostname: String, sudo: Boolean) {
        guestUsernameDraft = username
        guestHostnameDraft = hostname
        guestSudoDraft = sudo
        if (io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.isValidUsername(username)
            && io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.isValidHostname(hostname)) {
            userConfig = io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig(
                username, userConfig.password(), sudo, hostname)
        }
    }

    fun setOptionalProfiles(desktop: Boolean, podman: Boolean, editor: Boolean) {
        desktopSelected = desktop && distroId == "debian"
        val supported = distroId in setOf("debian", "ubuntu")
        podmanSelected = podman && supported
        editorSelected = editor && supported
    }

    /** Versioned whitelist. Never serialize userConfig, which contains the generated password. */
    fun saveDraft(): Map<String, String> = linkedMapOf(
        "schema" to DRAFT_SCHEMA, "step" to currentStep.name,
        "distro" to distroId, "version" to distroVersion, "diskGb" to diskSizeGb.toString(),
        "cpu" to hardwareConfig.cpuCores().toString(), "ram" to hardwareConfig.ramMegabytes().toString(),
        "mirror" to selectedMirror.name, "autoUpdate" to autoUpdateOnFirstBoot.toString(),
        "username" to guestUsernameDraft, "hostname" to guestHostnameDraft, "sudo" to guestSudoDraft.toString(),
        "desktop" to desktopSelected.toString(), "podman" to podmanSelected.toString(),
        "editor" to editorSelected.toString(), "creating" to isCreating.toString()
    )

    var isCreating: Boolean = false
        private set

    fun canGoBack(): Boolean = currentStep != CreationStep.STEP_STORAGE && !isCreating

    fun canGoNext(): Boolean = currentStep != CreationStep.STEP_STARTUP_TASKS && !isCreating

    fun nextStep(): CreationStep {
        check(canGoNext()) { "Cannot advance past step: $currentStep" }
        currentStep = when (currentStep) {
            CreationStep.STEP_STORAGE -> CreationStep.STEP_NETWORK_MIRROR
            CreationStep.STEP_NETWORK_MIRROR -> CreationStep.STEP_HARDWARE
            CreationStep.STEP_HARDWARE -> CreationStep.STEP_STARTUP_TASKS
            CreationStep.STEP_STARTUP_TASKS -> CreationStep.STEP_STARTUP_TASKS
        }
        return currentStep
    }

    fun previousStep(): CreationStep {
        check(canGoBack()) { "Cannot retreat before step: $currentStep" }
        currentStep = when (currentStep) {
            CreationStep.STEP_STARTUP_TASKS -> CreationStep.STEP_HARDWARE
            CreationStep.STEP_HARDWARE -> CreationStep.STEP_NETWORK_MIRROR
            CreationStep.STEP_NETWORK_MIRROR -> CreationStep.STEP_STORAGE
            CreationStep.STEP_STORAGE -> CreationStep.STEP_STORAGE
        }
        return currentStep
    }

    fun goToStep(step: CreationStep) {
        if (!isCreating) {
            currentStep = step
        }
    }

    fun selectDiskPresetGb(gb: Int) {
        require(gb > 0) { "Disk size must be greater than 0" }
        requestedDiskBytes = gb.toLong() * 1024L * 1024L * 1024L
        syncHardwareConfigDisk()
    }

    fun setCustomDiskSizeGb(gb: Int) {
        require(gb > 0) { "Disk size must be greater than 0" }
        requestedDiskBytes = gb.toLong() * 1024L * 1024L * 1024L
        syncHardwareConfigDisk()
    }

    private fun syncHardwareConfigDisk() {
        val clamped = requestedDiskBytes.coerceIn(
            VmHardwareConfig.MIN_DISK_CAPACITY_BYTES,
            VmHardwareConfig.MAX_DISK_CAPACITY_BYTES
        )
        hardwareConfig = VmHardwareConfig.builder()
            .cpuCores(hardwareConfig.cpuCores())
            .ramMegabytes(hardwareConfig.ramMegabytes())
            .diskCapacityBytes(clamped)
            .gpuAccelerationEnabled(hardwareConfig.gpuAccelerationEnabled())
            .build()
    }

    fun setMountHostSharedDirectory(enabled: Boolean) {
        mountHostSharedDirectory = enabled
    }

    fun setHostSharedDirectoryPath(path: String?) {
        hostSharedDirectoryPath = path
    }

    fun selectMirror(source: DistroMirrorProvisioner.MirrorSource) {
        selectedMirror = source
    }

    fun resolveMirrorUrls(targetDistro: String = distroId, mirrorChoice: DistroMirrorProvisioner.MirrorSource = selectedMirror): List<String> {
        return DistroMirrorProvisioner.resolveMirrorUrls(targetDistro, distroVersion, mirrorChoice)
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

    fun setAutoUpdateOnFirstBoot(enabled: Boolean) {
        autoUpdateOnFirstBoot = enabled
    }

    fun setCreating(creating: Boolean) {
        isCreating = creating
    }

    fun tryBeginCreation(): Boolean {
        if (isCreating) return false
        isCreating = true
        return true
    }

    fun safetyZone(): HostHardwareProfile.SafetyZone {
        return hardwareProfile.evaluateOverallZone(hardwareConfig.cpuCores(), hardwareConfig.ramMegabytes())
    }

    fun validateStorage(availableBytes: Long, requestedBytes: Long): StorageValidationResult {
        if (requestedBytes <= 0L) {
            return StorageValidationResult.InvalidRequestedSize("Requested disk capacity must be greater than 0")
        }
        val requiredBytes = requestedBytes + RESERVED_HOST_BYTES
        if (availableBytes < requiredBytes) {
            return StorageValidationResult.InsufficientSpace(
                availableBytes = availableBytes,
                requestedBytes = requestedBytes,
                requiredBytes = requiredBytes
            )
        }
        return StorageValidationResult.Valid
    }

    fun resolveUpdateCommand(targetDistro: String = distroId): String {
        val id = targetDistro.trim().lowercase(Locale.ROOT)
        return when (id) {
            "debian", "ubuntu", "kali" -> "DEBIAN_FRONTEND=noninteractive apt-get update && apt-get upgrade -y"
            "arch" -> "pacman -Sy --noconfirm && pacman -Su --noconfirm"
            "fedora", "almalinux", "rocky" -> "dnf upgrade -y --refresh"
            "opensuse" -> "zypper --non-interactive refresh && zypper --non-interactive update -y"
            else -> "echo 'No update command configured'"
        }
    }

    fun resolveUpdateTimeoutSeconds(targetDistro: String = distroId): Long {
        val id = targetDistro.trim().lowercase(Locale.ROOT)
        return when (id) {
            "fedora", "almalinux", "rocky" -> 360L
            "debian", "ubuntu", "kali", "arch", "opensuse" -> 300L
            else -> 300L
        }
    }

    fun generateFirstBootScript(targetDistro: String = distroId): String {
        val cmd = resolveUpdateCommand(targetDistro)
        val timeoutSeconds = resolveUpdateTimeoutSeconds(targetDistro)
        val dollar = "$"
        return """
            |#!/bin/sh
            |# Orchestella First-Boot Software Update Script (Ticket 01)
            |FLAG_FILE="/opt/orchestella/.firstboot-done"
            |if [ -f "${dollar}FLAG_FILE" ]; then
            |    exit 0
            |fi
            |
            |LOG_FILE="/var/log/firstboot-update.log"
            |mkdir -p /var/log /opt/orchestella
            |touch "${dollar}FLAG_FILE"
            |echo "[${dollar}(date '+%Y-%m-%d %H:%M:%S')] Starting initial software update..." >> "${dollar}LOG_FILE"
            |
            |# Run update in background with timeout guard (${timeoutSeconds}s)
            |(
            |    if command -v timeout >/dev/null 2>&1; then
            |        timeout ${timeoutSeconds}s sh -c '$cmd'
            |    else
            |        $cmd
            |    fi
            |    STATUS=${dollar}?
            |    echo "[${dollar}(date '+%Y-%m-%d %H:%M:%S')] Software update finished with exit status: ${dollar}STATUS" >> "${dollar}LOG_FILE"
            |    rm -f /opt/orchestella/firstboot.sh 2>/dev/null || true
            |) >> "${dollar}LOG_FILE" 2>&1 &
            |
        """.trimMargin()
    }
}

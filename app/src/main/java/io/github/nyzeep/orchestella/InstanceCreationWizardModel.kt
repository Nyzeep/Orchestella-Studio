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

    var selectedMirror: DistroMirrorProvisioner.MirrorSource = DistroMirrorProvisioner.MirrorSource.OFFICIAL
        private set

    var hardwareConfig: VmHardwareConfig = run {
        val defaultCores = (hardwareProfile.totalCores() / 2).coerceIn(1, 4)
        hardwareProfile.createRecommendedHardwareConfig().withCpuCores(defaultCores)
    }
        private set

    var autoUpdateOnFirstBoot: Boolean = false
        private set

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
            "alpine" -> "apk update && apk upgrade"
            "arch" -> "pacman -Sy --noconfirm && pacman -Su --noconfirm"
            "fedora" -> "dnf upgrade -y --refresh"
            "opensuse" -> "zypper --non-interactive refresh && zypper --non-interactive update -y"
            else -> "echo 'No update command configured'"
        }
    }

    fun resolveUpdateTimeoutSeconds(targetDistro: String = distroId): Long {
        val id = targetDistro.trim().lowercase(Locale.ROOT)
        return when (id) {
            "alpine" -> 120L
            "fedora" -> 360L
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

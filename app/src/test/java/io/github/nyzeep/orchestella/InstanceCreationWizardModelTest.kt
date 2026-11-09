package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * TDD Unit tests for InstanceCreationWizardModel (Ticket 01).
 * Verifies 4-step wizard flow, storage preflight validation, mirror source selection,
 * hardware quota assignment, and firstboot auto-update command mapping.
 */
class InstanceCreationWizardModelTest {

    private lateinit var profile: HostHardwareProfile
    private lateinit var wizard: InstanceCreationWizardModel

    @Before
    fun setUp() {
        profile = HostHardwareProfile(
            8,
            8L * 1024L * 1024L * 1024L,
            4L * 1024L * 1024L * 1024L,
            512L * 1024L * 1024L,
            false
        )
        wizard = InstanceCreationWizardModel(
            distroId = "debian",
            distroVersion = "13",
            hardwareProfile = profile
        )
    }

    @Test
    fun initialFourStepFlowProgression() {
        // Step 1: Storage
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_STORAGE, wizard.currentStep)
        assertEquals(1, wizard.stepIndex)
        assertEquals(4, wizard.totalSteps)
        assertFalse(wizard.canGoBack())
        assertTrue(wizard.canGoNext())

        // Step 1 -> Step 2 (Network & Mirror)
        var next = wizard.nextStep()
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR, next)
        assertEquals(2, wizard.stepIndex)
        assertTrue(wizard.canGoBack())
        assertTrue(wizard.canGoNext())

        // Step 2 -> Step 3 (Hardware Quota)
        next = wizard.nextStep()
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_HARDWARE, next)
        assertEquals(3, wizard.stepIndex)
        assertTrue(wizard.canGoBack())
        assertTrue(wizard.canGoNext())

        // Step 3 -> Step 4 (Startup Tasks)
        next = wizard.nextStep()
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS, next)
        assertEquals(4, wizard.stepIndex)
        assertTrue(wizard.canGoBack())
        assertFalse(wizard.canGoNext())

        // Cannot advance past Step 4
        try {
            wizard.nextStep()
            fail("Must throw when advancing beyond Step 4")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Cannot advance"))
        }

        // Retreating backwards Step 4 -> 3 -> 2 -> 1
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_HARDWARE, wizard.previousStep())
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR, wizard.previousStep())
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_STORAGE, wizard.previousStep())
        assertFalse(wizard.canGoBack())
    }

    @Test
    fun storageValidationEnforcesHostBuffer() {
        val gb = 1024L * 1024L * 1024L
        val requested8Gb = 8L * gb

        // Available 11 GB: requested 8GB + reserved 2GB = 10GB <= 11GB -> Valid
        val validResult = wizard.validateStorage(availableBytes = 11L * gb, requestedBytes = requested8Gb)
        assertTrue("11GB available for 8GB requested must be valid", validResult is InstanceCreationWizardModel.StorageValidationResult.Valid)

        // Available 9.5 GB: requested 8GB + reserved 2GB = 10GB > 9.5GB -> Insufficient
        val lowResult = wizard.validateStorage(availableBytes = (9.5 * gb).toLong(), requestedBytes = requested8Gb)
        assertTrue("9.5GB available for 8GB requested must fail with InsufficientSpace", lowResult is InstanceCreationWizardModel.StorageValidationResult.InsufficientSpace)
        val insufficient = lowResult as InstanceCreationWizardModel.StorageValidationResult.InsufficientSpace
        assertEquals(10L * gb, insufficient.requiredBytes)

        // Requested <= 0 -> Invalid
        val invalidZero = wizard.validateStorage(availableBytes = 20L * gb, requestedBytes = 0L)
        assertTrue(invalidZero is InstanceCreationWizardModel.StorageValidationResult.InvalidRequestedSize)
    }

    @Test
    fun presetAndCustomDiskSelection() {
        // Preset capsule selection: 4GB, 8GB, 16GB, 32GB
        wizard.selectDiskPresetGb(16)
        assertEquals(16L * 1024L * 1024L * 1024L, wizard.requestedDiskBytes)
        assertEquals(16, wizard.diskSizeGb)

        // Custom disk size
        wizard.setCustomDiskSizeGb(24)
        assertEquals(24L * 1024L * 1024L * 1024L, wizard.requestedDiskBytes)
        assertEquals(24, wizard.diskSizeGb)
    }

    @Test
    fun hostSharedDirectoryConfiguration() {
        assertFalse(wizard.mountHostSharedDirectory)
        wizard.setMountHostSharedDirectory(true)
        wizard.setHostSharedDirectoryPath("/storage/emulated/0/Documents")
        assertTrue(wizard.mountHostSharedDirectory)
        assertEquals("/storage/emulated/0/Documents", wizard.hostSharedDirectoryPath)
    }

    @Test
    fun mirrorSourceSelectionAndResolution() {
        wizard.selectMirror(DistroMirrorProvisioner.MirrorSource.TUNA)
        assertEquals(DistroMirrorProvisioner.MirrorSource.TUNA, wizard.selectedMirror)

        val urls = wizard.resolveMirrorUrls("debian", DistroMirrorProvisioner.MirrorSource.TUNA)
        assertTrue(urls.any { it.contains("mirrors.tuna.tsinghua.edu.cn") })
    }

    @Test
    fun updateCommandMappingForDifferentDistros() {
        // Debian / Ubuntu / Kali
        val debianCmd = wizard.resolveUpdateCommand("debian")
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get update && apt-get upgrade -y", debianCmd)
        assertEquals(300L, wizard.resolveUpdateTimeoutSeconds("debian"))

        val ubuntuCmd = wizard.resolveUpdateCommand("ubuntu")
        assertEquals("DEBIAN_FRONTEND=noninteractive apt-get update && apt-get upgrade -y", ubuntuCmd)

        // Arch
        val archCmd = wizard.resolveUpdateCommand("arch")
        assertEquals("pacman -Sy --noconfirm && pacman -Su --noconfirm", archCmd)
        assertEquals(300L, wizard.resolveUpdateTimeoutSeconds("arch"))

        // Fedora
        val fedoraCmd = wizard.resolveUpdateCommand("fedora")
        assertEquals("dnf upgrade -y --refresh", fedoraCmd)
        assertEquals(360L, wizard.resolveUpdateTimeoutSeconds("fedora"))

        // AlmaLinux & Rocky Linux
        val almaCmd = wizard.resolveUpdateCommand("almalinux")
        assertEquals("dnf upgrade -y --refresh", almaCmd)
        assertEquals(360L, wizard.resolveUpdateTimeoutSeconds("almalinux"))

        val rockyCmd = wizard.resolveUpdateCommand("rocky")
        assertEquals("dnf upgrade -y --refresh", rockyCmd)
        assertEquals(360L, wizard.resolveUpdateTimeoutSeconds("rocky"))

        // openSUSE
        val suseCmd = wizard.resolveUpdateCommand("opensuse")
        assertEquals("zypper --non-interactive refresh && zypper --non-interactive update -y", suseCmd)
        assertEquals(300L, wizard.resolveUpdateTimeoutSeconds("opensuse"))
    }

    @Test
    fun defaultHardwareAllocationRespectsFiftyPercentRule() {
        // 8 cores -> 50% = 4 cores
        assertEquals(4, wizard.hardwareConfig.cpuCores())

        // 2 cores profile -> 50% = 1 core
        val dualCoreProfile = HostHardwareProfile(
            2,
            4L * 1024L * 1024L * 1024L,
            2L * 1024L * 1024L * 1024L,
            512L * 1024L * 1024L,
            false
        )
        val dualWizard = InstanceCreationWizardModel(
            distroId = "ubuntu",
            hardwareProfile = dualCoreProfile
        )
        assertEquals(1, dualWizard.hardwareConfig.cpuCores())
    }

    @Test
    fun exitDecisionPinsBackGuardBehavior() {
        // T4② 契约：空闲向导 BACK 直接退出；创建进行中 BACK 必须先确认。
        // 恢复的草稿（creating=true）与本分支同路，由 WizardDraftCandidateTest 覆盖。
        assertFalse("未开始创建时 BACK 应直接退出，无需确认", wizard.shouldConfirmExit())
        assertTrue(wizard.tryBeginCreation())
        assertTrue("创建进行中 BACK 必须走确认框", wizard.shouldConfirmExit())
        wizard.setCreating(false)
        assertFalse("创建失败回落后 BACK 应恢复直接退出", wizard.shouldConfirmExit())
    }

    @Test
    fun storageOvercommitmentPreflightBlocksWhenExistingInstancesCommitted() {
        // T4③ 预检补强契约：磁盘请求上限 2–64 GB，125.5 GB 可用时单看请求量
        // 64 GB 恒可通过——这正是 2026-11-08 走查记录的静默放行洞。
        val gb = 1024L * 1024L * 1024L
        val available = 125.5 * gb
        val request64Gb = 64L * gb
        val alone = wizard.validateStorage(availableBytes = available.toLong(), requestedBytes = request64Gb)
        assertTrue("无已有占用时 64GB 请求应保持放行（行为不变）",
            alone is InstanceCreationWizardModel.StorageValidationResult.Valid)

        // 计入一个已存在 64GB 实例的承诺占用后必须拒绝，并携带占用明细。
        val overCommitted = wizard.validateStorage(
            availableBytes = available.toLong(),
            requestedBytes = request64Gb,
            existingCommittedBytes = 64L * gb
        )
        val insufficient = overCommitted as InstanceCreationWizardModel.StorageValidationResult.InsufficientSpace
        assertEquals(64L * gb, insufficient.existingCommittedBytes)
        assertEquals(request64Gb + 64L * gb + InstanceCreationWizardModel.RESERVED_HOST_BYTES,
            insufficient.requiredBytes)

        // 二参旧签名等价于零占用，保证既有调用方/用例语义不变。
        val twoArg = wizard.validateStorage(available.toLong(), request64Gb)
        assertTrue(twoArg is InstanceCreationWizardModel.StorageValidationResult.Valid)

        // 负数承诺（脏数据）按 0 计，不得让预检凭空变宽松或崩溃。
        assertTrue(wizard.validateStorage(1L, request64Gb, -5L)
            is InstanceCreationWizardModel.StorageValidationResult.InsufficientSpace)
    }

    @Test
    fun committedCapacityAggregationSumsAndClampsDirtyInput() {
        val gb = 1024L * 1024L * 1024L
        assertEquals(96L * gb, InstanceCreationWizardModel.committedCapacityBytes(listOf(64L * gb, 32L * gb)))
        assertEquals(0L, InstanceCreationWizardModel.committedCapacityBytes(emptyList()))
        assertEquals(8L * gb, InstanceCreationWizardModel.committedCapacityBytes(listOf(8L * gb, -1L * gb)))
    }

    @Test
    fun storagePreflightRejectionInjectsIntoStorageFullErrorFamily() {
        // T4③ 注入腿契约：预检拒绝异常必须命中 ErrorCopy 磁盘满族，真机上向导
        // 据此展示「存储空间不足（错误码 E-STORAGE-FULL）」，而不是静默通过。
        val rejection = InstanceCreationWizardModel.storagePreflightRejection("已有实例承诺 64 GB")
        val entry = ErrorCopy.resolve(rejection)
        assertEquals(ErrorCopy.CODE_STORAGE_FULL, entry.code)
        assertEquals(R.string.error_storage_full, entry.headlineRes)
        assertEquals(R.string.error_hint_check_storage, entry.hintRes)
    }

    @Test
    fun firstBootScriptGeneration() {
        wizard.setAutoUpdateOnFirstBoot(true)
        assertTrue(wizard.autoUpdateOnFirstBoot)

        val script = wizard.generateFirstBootScript("debian")
        assertNotNull(script)
        assertTrue(script.contains("#!/bin/sh"))
        assertTrue(script.contains("apt-get update && apt-get upgrade -y"))
        assertTrue(script.contains("/var/log/firstboot-update.log"))
        assertTrue(script.contains("timeout 300s"))
        assertTrue(script.contains(".firstboot-done"))

        val fedoraScript = wizard.generateFirstBootScript("fedora")
        assertTrue(fedoraScript.contains("timeout 360s"))
        assertTrue(fedoraScript.contains("dnf upgrade -y --refresh"))
    }
}

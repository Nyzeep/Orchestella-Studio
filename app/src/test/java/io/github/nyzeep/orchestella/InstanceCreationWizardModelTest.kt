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

        // Alpine
        val alpineCmd = wizard.resolveUpdateCommand("alpine")
        assertEquals("apk update && apk upgrade", alpineCmd)
        assertEquals(120L, wizard.resolveUpdateTimeoutSeconds("alpine"))

        // Arch
        val archCmd = wizard.resolveUpdateCommand("arch")
        assertEquals("pacman -Sy --noconfirm && pacman -Su --noconfirm", archCmd)
        assertEquals(300L, wizard.resolveUpdateTimeoutSeconds("arch"))

        // Fedora
        val fedoraCmd = wizard.resolveUpdateCommand("fedora")
        assertEquals("dnf upgrade -y --refresh", fedoraCmd)
        assertEquals(360L, wizard.resolveUpdateTimeoutSeconds("fedora"))

        // openSUSE
        val suseCmd = wizard.resolveUpdateCommand("opensuse")
        assertEquals("zypper --non-interactive refresh && zypper --non-interactive update -y", suseCmd)
        assertEquals(300L, wizard.resolveUpdateTimeoutSeconds("opensuse"))
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
    }
}

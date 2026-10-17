package io.github.nyzeep.orchestella

import org.junit.Assert.*
import org.junit.Test

/** Candidate model boundary only; this suite does not claim Android recreation. */
class WizardDraftCandidateTest {
    private val gib = 1024L * 1024L * 1024L
    private fun profile(cores: Int = 8, ramGb: Int = 8) =
        HostHardwareProfile(cores, ramGb * gib, ramGb * gib / 2, gib / 2, false)
    private fun configured(step: InstanceCreationWizardModel.CreationStep =
        InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS): InstanceCreationWizardModel =
        InstanceCreationWizardModel("debian", "13", profile(), step).apply {
            setCustomDiskSizeGb(24)
            updateCpuCores(2)
            updateRamMegabytes(4096)
            selectMirror(DistroMirrorProvisioner.MirrorSource.TUNA)
            setAutoUpdateOnFirstBoot(true)
            updateGuestIdentityDraft("qauser", "qahost", false)
            setOptionalProfiles(true, true, true)
        }
    private fun restore(draft: Map<String, String>, host: HostHardwareProfile = profile(),
        versions: Map<String, String> = mapOf("debian" to "13"), minimum: Int = 1024) =
        InstanceCreationWizardModel.restoreDraft(draft, host, versions) { minimum }
    private fun rejected(draft: Map<String, String>, host: HostHardwareProfile = profile(),
        versions: Map<String, String> = mapOf("debian" to "13"), minimum: Int = 1024) {
        try {
            restore(draft, host, versions, minimum)
            fail("invalid draft must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.isNullOrBlank())
        }
    }

    @Test fun creationAdmissionRunsOnlyOnceAndRestoredInFlightCannotReplay() {
        val original = configured()
        assertTrue(original.tryBeginCreation())
        assertFalse(original.tryBeginCreation())
        val restored = restore(original.saveDraft())
        assertTrue(restored.isCreating)
        assertFalse(restored.tryBeginCreation())
        assertFalse(restored.canGoBack())
        assertFalse(restored.canGoNext())
    }

    @Test fun safeDraftRoundTripsThirdAndFourthStepWithoutStoringCredentials() {
        for (step in listOf(InstanceCreationWizardModel.CreationStep.STEP_HARDWARE,
            InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS)) {
            val before = configured(step)
            val state = before.saveDraft()
            val after = restore(state)
            assertEquals(state, after.saveDraft())
            assertEquals(step, after.currentStep)
            assertEquals(24, after.diskSizeGb)
            assertEquals(2, after.hardwareConfig.cpuCores())
            assertEquals(4096, after.hardwareConfig.ramMegabytes())
            assertEquals("qauser", after.userConfig.username())
            assertEquals("qahost", after.userConfig.hostname())
            assertFalse(after.userConfig.hasSudo())
            assertTrue(after.desktopSelected && after.podmanSelected && after.editorSelected)
            assertFalse(state.keys.any { it.contains("password", true) || it.contains("token", true) })
            assertFalse(state.values.contains(before.userConfig.password()))
            assertNotEquals(before.userConfig.password(), after.userConfig.password())
        }
    }

    @Test fun badSchemaUnknownFieldMissingFieldAndBadScalarFailClosed() {
        val state = configured().saveDraft()
        rejected(state + ("schema" to "999"))
        rejected(state + ("token" to "synthetic-disallowed"))
        rejected(state - "cpu")
        rejected(state + ("ram" to "not-a-number"))
        rejected(state + ("diskGb" to "2147483647"))
        rejected(state + ("creating" to "TRUE"))
        rejected(state + ("step" to "UNKNOWN"))
        rejected(state + ("mirror" to "UNKNOWN"))
    }

    @Test fun removedOrUpdatedDistroAndChangedHostBudgetAreRevalidated() {
        val state = configured().saveDraft()
        rejected(state, versions = emptyMap())
        rejected(state, versions = mapOf("debian" to "14"))
        rejected(state, host = profile(1))
        rejected(state, host = profile(ramGb = 4))
        rejected(state, minimum = 6144)
    }

    @Test fun incompleteIdentityDraftIsPreservedWithoutCreatingInvalidCredentials() {
        val before = configured().apply { updateGuestIdentityDraft("", "bad host", false) }
        val after = restore(before.saveDraft())
        assertEquals("", after.guestUsernameDraft)
        assertEquals("bad host", after.guestHostnameDraft)
        assertEquals("root", after.userConfig.username())
        assertFalse(after.isCreating)
    }

    @Test fun staleUnsupportedProfilesCannotBecomeEnabledForAnotherDistro() {
        val state = configured().saveDraft() + mapOf("distro" to "fedora", "version" to "43")
        val after = restore(state, versions = mapOf("fedora" to "43"))
        assertFalse(after.desktopSelected || after.podmanSelected || after.editorSelected)
    }
}

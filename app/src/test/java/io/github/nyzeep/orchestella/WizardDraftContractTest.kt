package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate-4 contract locks for the [WizardDraft] value object: every
 * editable field survives the model → draft → model round trip on all four
 * steps, documented defaults hold, illegal input is clamped (or kept) before
 * it can reach a model mutator, and the two model-owned fields (distroId,
 * creating) are never written by [WizardDraft.applyTo]. Pure JVM; the
 * persistence map itself stays pinned by WizardDraftCandidateTest.
 */
class WizardDraftContractTest {

    private val gib = 1024L * 1024L * 1024L
    private fun profile(cores: Int = 8, ramGb: Int = 8) =
        HostHardwareProfile(cores, ramGb * gib, ramGb * gib / 2, gib / 2, false)

    private fun configured(step: InstanceCreationWizardModel.CreationStep =
        InstanceCreationWizardModel.CreationStep.STEP_STORAGE): InstanceCreationWizardModel =
        InstanceCreationWizardModel("debian", "13", profile(), step).apply {
            setCustomDiskSizeGb(24)
            updateCpuCores(2)
            updateRamMegabytes(4096)
            selectMirror(DistroMirrorProvisioner.MirrorSource.TUNA)
            setAutoUpdateOnFirstBoot(true)
            updateGuestIdentityDraft("qauser", "qahost", false)
            setOptionalProfiles(true, true, true)
            setMountHostSharedDirectory(true)
            setHostSharedDirectoryPath("primary%3Astorage-emulated")
        }

    @Test
    fun allFieldsRoundTripThroughDraftOnEveryStep() {
        val steps = InstanceCreationWizardModel.CreationStep.values()
        assertEquals(4, steps.size)
        for (step in steps) {
            val before = configured(step)
            val draft = WizardDraft.fromModel(before)
            val after = InstanceCreationWizardModel("debian", "13", profile(), draft.step)
            after.setUserConfig(before.userConfig)
            draft.applyTo(after)
            assertEquals("step $step", draft.normalized(), WizardDraft.fromModel(after))
            assertEquals("model state must round-trip identically for step $step",
                before.saveDraft(), after.saveDraft())
        }
    }

    @Test
    fun documentedDefaultsHold() {
        val draft = WizardDraft()
        assertEquals("ubuntu", draft.distroId)
        assertEquals(8, draft.diskSizeGb)
        assertEquals(WizardDraft.DEFAULT_CPU_CORES, draft.cpuCores)
        assertEquals(1024, draft.ramMb)
        assertEquals(DistroMirrorProvisioner.MirrorSource.MULTI_TIER.ordinal, draft.mirrorOrdinal)
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_STORAGE, draft.step)
        assertEquals("", draft.guestUsername)
        assertEquals("", draft.guestHostname)
        assertFalse(draft.guestSudo)
        assertFalse(draft.creating)
        assertNull(draft.sharedDirPath)
    }

    @Test
    fun fromModelOnFreshModelMirrorsDocumentedDefaults() {
        val fresh = InstanceCreationWizardModel("debian", "13", profile())
        val draft = WizardDraft.fromModel(fresh)
        assertEquals(8, draft.diskSizeGb)
        assertEquals(fresh.hardwareConfig.cpuCores(), draft.cpuCores)
        assertEquals(fresh.hardwareConfig.ramMegabytes(), draft.ramMb)
        assertEquals("root", draft.guestUsername)
        assertEquals(fresh.guestHostnameDraft, draft.guestHostname)
        assertFalse(draft.creating)
        assertFalse(draft.mountSharedDir)
        assertNull(draft.sharedDirPath)
    }

    @Test
    fun illegalDiskInputIsKeptNotApplied() {
        val base = WizardDraft(diskSizeGb = 24)
        for (raw in listOf(null, "", "abc", "0", "-4", "24 GB")) {
            assertEquals("raw=$raw must keep the current value", 24,
                WizardDraftFields.diskSizeGb.parseInto(base, raw).diskSizeGb)
        }
        assertEquals("whitespace is trimmed", 32,
            WizardDraftFields.diskSizeGb.parseInto(base, "  32  ").diskSizeGb)
    }

    @Test
    fun diskFloorClampedToRestoreAdmissionRangeUpperBoundLeftIntact() {
        // Below the 2 GB restore floor lands on the floor instead of leaving an
        // unpersistable request behind.
        assertEquals(2, WizardDraft(diskSizeGb = 1).normalized().diskSizeGb)
        assertEquals(2, WizardDraft(diskSizeGb = -5).normalized().diskSizeGb)
        // Above the range stays intact: an oversized request must reach
        // validateStorage so the E-STORAGE-FULL-family copy can explain itself.
        assertEquals(100, WizardDraft(diskSizeGb = 100).normalized().diskSizeGb)
    }

    @Test
    fun hardwareAndMirrorInputsClampIntoModelBounds() {
        val clamped = WizardDraft(cpuCores = 0, ramMb = 999999, mirrorOrdinal = 99).normalized()
        assertEquals(VmHardwareConfig.MIN_CPU_CORES, clamped.cpuCores)
        assertEquals(VmHardwareConfig.MAX_CPU_CORES,
            WizardDraft(cpuCores = 99).normalized().cpuCores)
        assertEquals(VmHardwareConfig.MIN_RAM_MEGABYTES,
            WizardDraft(ramMb = 0).normalized().ramMb)
        assertEquals(VmHardwareConfig.MAX_RAM_MEGABYTES, clamped.ramMb)
        assertEquals(WizardDraft.DEFAULT_MIRROR_ORDINAL, clamped.mirrorOrdinal)
    }

    @Test
    fun mirrorOrdinalParseAcceptsOnlyValidEnumRange() {
        val base = WizardDraft(mirrorOrdinal = WizardDraft.DEFAULT_MIRROR_ORDINAL)
        val ordinals = DistroMirrorProvisioner.MirrorSource.values().indices
        for (ordinal in ordinals) {
            assertEquals(ordinal, WizardDraftFields.mirrorOrdinal.parseInto(base, ordinal.toString()).mirrorOrdinal)
        }
        assertEquals("out-of-range parse must keep current", WizardDraft.DEFAULT_MIRROR_ORDINAL,
            WizardDraftFields.mirrorOrdinal.parseInto(base, ordinals.count().toString()).mirrorOrdinal)
        assertEquals("non-numeric parse must keep current", WizardDraft.DEFAULT_MIRROR_ORDINAL,
            WizardDraftFields.mirrorOrdinal.parseInto(base, "TUNA").mirrorOrdinal)
    }

    @Test
    fun booleanLanesParseStrictly() {
        for (field in listOf(WizardDraftFields.guestSudo, WizardDraftFields.desktopSelected,
                WizardDraftFields.podmanSelected, WizardDraftFields.editorSelected,
                WizardDraftFields.autoUpdateOnFirstBoot, WizardDraftFields.mountSharedDir)) {
            val base = field.get(WizardDraft())
            assertTrue(field.name, field.get(field.parseInto(WizardDraft(), "true")) as Boolean)
            assertFalse(field.name, field.get(field.parseInto(WizardDraft(), "false")) as Boolean)
            assertEquals(field.name, base, field.get(field.parseInto(WizardDraft(), "TRUE")))
            assertEquals(field.name, base, field.get(field.parseInto(WizardDraft(), "1")))
            assertEquals(field.name, base, field.get(field.parseInto(WizardDraft(), null)))
        }
    }

    @Test
    fun identityLanesPreserveIncompleteInput() {
        val base = WizardDraft(guestUsername = "qauser", guestHostname = "qahost")
        val partial = WizardDraftFields.guestUsername.parseInto(
            WizardDraftFields.guestHostname.parseInto(base, "bad host"), "")
        assertEquals("", partial.guestUsername)
        assertEquals("bad host", partial.guestHostname)
        assertEquals("null keeps current", "qauser",
            WizardDraftFields.guestUsername.parseInto(base, null).guestUsername)
    }

    @Test
    fun applyToNeverWritesDistroIdOrCreationState() {
        val draft = WizardDraft(distroId = "fedora", creating = true, diskSizeGb = 24)
        val model = InstanceCreationWizardModel("debian", "13", profile())
        draft.applyTo(model)
        assertEquals("distro switch rebuilds the model, not a draft write", "debian", model.distroId)
        assertFalse("creation state is model-owned lifecycle", model.isCreating)
        assertEquals(24, model.diskSizeGb)
    }

    @Test
    fun optionalProfileGatingStaysModelOwned() {
        val draft = WizardDraft(desktopSelected = true, podmanSelected = true, editorSelected = true)
        val ubuntu = InstanceCreationWizardModel("ubuntu", "24.04", profile())
        draft.applyTo(ubuntu)
        assertFalse("desktop is debian-only", ubuntu.desktopSelected)
        assertTrue(ubuntu.podmanSelected && ubuntu.editorSelected)
        val debian = InstanceCreationWizardModel("debian", "13", profile())
        draft.applyTo(debian)
        assertTrue(debian.desktopSelected && debian.podmanSelected && debian.editorSelected)
    }

    @Test
    fun registryNamesCoverEveryDraftProperty() {
        assertEquals(listOf(
            "distroId", "diskSizeGb", "cpuCores", "ramMb", "mirrorOrdinal",
            "guestUsername", "guestHostname", "guestSudo", "desktopSelected",
            "podmanSelected", "editorSelected", "autoUpdateOnFirstBoot",
            "mountSharedDir", "sharedDirPath"),
            WizardDraftFields.all.map { it.name })
    }
}

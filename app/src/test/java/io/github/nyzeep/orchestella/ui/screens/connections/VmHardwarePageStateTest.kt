package io.github.nyzeep.orchestella.ui.screens.connections

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VM hardware page contract: every safety decision stays with
 * [HostHardwareProfile]; the page only projects it. RED must keep save off.
 */
class VmHardwarePageStateTest {

    private val gib = 1024L * 1024L * 1024L
    private val profile = HostHardwareProfile(
        8,
        8L * 1024L * 1024L * 1024L,
        4L * 1024L * 1024L * 1024L,
        512L * 1024L * 1024L,
        false,
    )
    private val current = VmHardwareConfig(2, 2048, 8L * gib, true, null)

    @Test
    fun currentConfigAndHostCapacitySeedTheForm() {
        val state = VmHardwarePageState(current, profile)
        assertEquals(2, state.cpuCores)
        assertEquals(2048, state.ramMegabytes)
        assertEquals(8, state.diskGb)
        assertEquals(8, state.maxCpuCores)
        assertEquals(64, state.maxDiskGb)
        assertTrue(state.gpuAccelerationEnabled)
        assertTrue(state.canSave)
    }

    @Test
    fun ramInTheRedZoneDisablesSaving() {
        val state = VmHardwarePageState(current, profile)
        state.setRamMegabytes(8192f)
        assertEquals(HostHardwareProfile.SafetyZone.RED, state.ramZone)
        assertFalse("RED must block saving", state.canSave)
    }

    @Test
    fun recommendedRamKeepsSavingEnabled() {
        val state = VmHardwarePageState(current, profile)
        state.setRamMegabytes(state.recommendedRamMegabytes.toFloat())
        assertFalse(state.ramZone == HostHardwareProfile.SafetyZone.RED)
        assertTrue(state.canSave)
    }

    @Test
    fun cpuIsClampedToTheHostCeiling() {
        val state = VmHardwarePageState(current, profile)
        state.setCpuCores(99f)
        assertEquals(8, state.cpuCores)
        state.setCpuCores(0f)
        assertEquals(1, state.cpuCores)
    }

    @Test
    fun diskOnlyExpandsAndReportsAbsoluteExpandedBytes() {
        val state = VmHardwarePageState(current, profile)
        state.setDiskGb(4f)
        assertEquals("disk cannot shrink below the current volume", 8, state.diskGb)
        state.setDiskGb(12f)
        assertEquals(12, state.diskGb)
        val selection = state.selection()
        assertEquals(12L * gib, selection.expandedDiskBytes)
        assertEquals(12, selection.diskCapacityGb)
    }

    @Test
    fun keepingTheDiskReportsNoExpansion() {
        val state = VmHardwarePageState(current, profile)
        assertNull(state.selection().expandedDiskBytes)
    }

    @Test
    fun gpuToggleTravelsWithTheSelection() {
        val state = VmHardwarePageState(current, profile)
        state.setGpuAcceleration(false)
        assertFalse(state.selection().gpuAccelerationEnabled)
        state.setGpuAcceleration(true)
        assertTrue(state.selection().gpuAccelerationEnabled)
    }
}

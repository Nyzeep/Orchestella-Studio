package io.github.nyzeep.orchestella.ui.screens.connections

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.engineqemu.GpuAccelerationStatus
import io.github.nyzeep.orchestella.runtime.engineqemu.GpuFallbackReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VM hardware page contract: every safety decision stays with
 * [HostHardwareProfile]; the page only projects it. RED must keep save off.
 *
 * The GPU form can commit an enabled preference when the build's prerequisites
 * are present. The subtitle uses the live outcome, with static copy for DISABLED.
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

    private fun state(
        nativeAvailable: Boolean = true,
        deviceModelSupported: Boolean = true,
        sessionStatus: GpuAccelerationStatus? = null,
    ) = VmHardwarePageState(current, profile, nativeAvailable, deviceModelSupported, sessionStatus)

    @Test
    fun currentConfigAndHostCapacitySeedTheForm() {
        val state = state()
        assertEquals(2, state.cpuCores)
        assertEquals(2048, state.ramMegabytes)
        assertEquals(8, state.diskGb)
        assertEquals(8, state.maxCpuCores)
        assertEquals(64, state.maxDiskGb)
        assertTrue(state.gpuToggleEnabled)
        assertTrue(state.gpuAccelerationEnabled)
        assertTrue(state.canSave)
    }

    @Test
    fun ramInTheRedZoneDisablesSaving() {
        val state = state()
        state.setRamMegabytes(8192f)
        assertEquals(HostHardwareProfile.SafetyZone.RED, state.ramZone)
        assertFalse("RED must block saving", state.canSave)
    }

    @Test
    fun recommendedRamKeepsSavingEnabled() {
        val state = state()
        state.setRamMegabytes(state.recommendedRamMegabytes.toFloat())
        assertFalse(state.ramZone == HostHardwareProfile.SafetyZone.RED)
        assertTrue(state.canSave)
    }

    @Test
    fun cpuIsClampedToTheHostCeiling() {
        val state = state()
        state.setCpuCores(99f)
        assertEquals(8, state.cpuCores)
        state.setCpuCores(0f)
        assertEquals(1, state.cpuCores)
    }

    @Test
    fun diskOnlyExpandsAndReportsAbsoluteExpandedBytes() {
        val state = state()
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
        val state = state()
        assertNull(state.selection().expandedDiskBytes)
    }

    @Test
    fun gpuToggleTravelsWithTheSelection() {
        val state = state()
        state.setGpuAcceleration(false)
        assertFalse(state.selection().gpuAccelerationEnabled)
        state.setGpuAcceleration(true)
        assertTrue(state.selection().gpuAccelerationEnabled)
    }

    // ---- G-07: GPU toggle honesty resolution ----

    @Test
    fun noSessionFallsBackToTheStaticCapabilityDeclaration() {
        assertEquals(
            "packaged driver is honestly declared",
            GpuToggleStatus.NATIVE_AVAILABLE,
            GpuToggleStatus.resolve(true, null),
        )
        assertEquals(
            "missing driver is honestly declared",
            GpuToggleStatus.NATIVE_UNAVAILABLE,
            GpuToggleStatus.resolve(false, null),
        )
    }

    @Test
    fun liveSessionStatusOverridesTheStaticDeclaration() {
        assertEquals(
            "an active session wins even when the static read is missing",
            GpuToggleStatus.SESSION_ACTIVE,
            GpuToggleStatus.resolve(false, GpuAccelerationStatus.active(1024)),
        )
        assertEquals(
            "an explicit fallback wins over the static declaration",
            GpuToggleStatus.SESSION_FALLBACK,
            GpuToggleStatus.resolve(true, GpuAccelerationStatus.fallback(GpuFallbackReason.NATIVE_UNAVAILABLE, 0, 1024)),
        )
        assertEquals(
            "an init failure is a real fallback, not a static guess",
            GpuToggleStatus.SESSION_FALLBACK,
            GpuToggleStatus.resolve(true, GpuAccelerationStatus.fallback(GpuFallbackReason.INIT_FAILED, -1, 1024)),
        )
    }

    @Test
    fun disabledSessionFallsBackToTheStaticCopy() {
        assertEquals(
            "DISABLED means the flag was off at launch; the static copy describes the next launch",
            GpuToggleStatus.NATIVE_UNAVAILABLE,
            GpuToggleStatus.resolve(false, GpuAccelerationStatus.disabled()),
        )
        assertEquals(
            GpuToggleStatus.NATIVE_AVAILABLE,
            GpuToggleStatus.resolve(true, GpuAccelerationStatus.disabled()),
        )
    }

    @Test
    fun stateProjectsTheResolvedStatusAndFallbackReason() {
        assertEquals(GpuToggleStatus.NATIVE_AVAILABLE, state().gpuToggleStatus)
        assertNull("no session means no per-launch fallback reason", state().gpuFallbackReason)

        val fallback = state(
            nativeAvailable = true,
            sessionStatus = GpuAccelerationStatus.fallback(GpuFallbackReason.INIT_FAILED, -1, 1024),
        )
        assertEquals(GpuToggleStatus.SESSION_FALLBACK, fallback.gpuToggleStatus)
        assertEquals(
            "the subtitle needs the launch's real reason",
            GpuFallbackReason.INIT_FAILED,
            fallback.gpuFallbackReason,
        )

        val active = state(nativeAvailable = false, sessionStatus = GpuAccelerationStatus.active(1024))
        assertEquals(GpuToggleStatus.SESSION_ACTIVE, active.gpuToggleStatus)
        assertNull("ACTIVE carries no fallback reason", active.gpuFallbackReason)
    }

    @Test
    fun missingNativeForcesLegacyGpuPreferenceOff() {
        val state = state(nativeAvailable = false)
        assertFalse(state.gpuToggleEnabled)
        assertFalse(state.gpuAccelerationEnabled)
        state.setGpuAcceleration(true)
        assertFalse(state.gpuAccelerationEnabled)
        assertFalse(state.selection().gpuAccelerationEnabled)
        assertEquals(GpuToggleStatus.NATIVE_UNAVAILABLE, state.gpuToggleStatus)
    }

    // ---- DW-02: QEMU 设备模型因子进入静态三态 ----

    @Test
    fun deviceModelUnsupportedGetsItsOwnStaticState() {
        assertEquals(
            "driver packaged but the packaged QEMU lacks the gl-device model",
            GpuToggleStatus.DEVICE_MODEL_UNSUPPORTED,
            GpuToggleStatus.resolve(nativeAvailable = true, deviceModelSupported = false, sessionStatus = null),
        )
        assertEquals(
            GpuToggleStatus.NATIVE_AVAILABLE,
            GpuToggleStatus.resolve(nativeAvailable = true, deviceModelSupported = true, sessionStatus = null),
        )
        assertEquals(
            "a missing driver still wins the static slot",
            GpuToggleStatus.NATIVE_UNAVAILABLE,
            GpuToggleStatus.resolve(nativeAvailable = false, deviceModelSupported = false, sessionStatus = null),
        )
    }

    @Test
    fun sessionStatusStillWinsOverTheDeviceModelFactor() {
        assertEquals(
            GpuToggleStatus.SESSION_FALLBACK,
            GpuToggleStatus.resolve(
                true,
                false,
                GpuAccelerationStatus.fallback(GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED, 0, 1024),
            ),
        )
        assertEquals(
            GpuToggleStatus.SESSION_ACTIVE,
            GpuToggleStatus.resolve(true, false, GpuAccelerationStatus.active(1024)),
        )
        assertEquals(
            "DISABLED falls back to the static copy, which now reads the device model",
            GpuToggleStatus.DEVICE_MODEL_UNSUPPORTED,
            GpuToggleStatus.resolve(true, false, GpuAccelerationStatus.disabled()),
        )
    }

    @Test
    fun stateProjectsTheDeviceModelUnsupportedStaticState() {
        val state = state(nativeAvailable = true, deviceModelSupported = false)
        assertEquals(GpuToggleStatus.DEVICE_MODEL_UNSUPPORTED, state.gpuToggleStatus)
        assertNull("no session means no per-launch fallback reason", state.gpuFallbackReason)
        assertFalse(state.gpuToggleEnabled)
        assertFalse(state.gpuAccelerationEnabled)
        state.setGpuAcceleration(true)
        assertFalse(state.selection().gpuAccelerationEnabled)
    }

    @Test
    fun legacyTwoArgumentResolveKeepsThePreDw02Contract() {
        assertEquals(GpuToggleStatus.NATIVE_AVAILABLE, GpuToggleStatus.resolve(true, null))
        assertEquals(GpuToggleStatus.NATIVE_UNAVAILABLE, GpuToggleStatus.resolve(false, null))
    }
}

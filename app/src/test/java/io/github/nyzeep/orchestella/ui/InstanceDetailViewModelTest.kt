package io.github.nyzeep.orchestella.ui

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pure JVM Unit Test Suite for InstanceDetailViewModel (Ticket 04).
 *
 * <p>Verifies:
 * <ol>
 *   <li>Hardware configuration modification strictly blocked in RUNNING and permitted in STOPPED.</li>
 *   <li>Danger zone 2-step verification delete requiring exact matching instance ID and blocked in RUNNING.</li>
 *   <li>Console action availability matrix (Terminal, Desktop, Power, Clone, Reset) across states.</li>
 *   <li>Cloning and deadlock recovery lifecycle actions.</li>
 * </ol>
 */
class InstanceDetailViewModelTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var tempDir: Path
    private lateinit var store: InstanceLifecycle.FileStore
    private lateinit var controller: InstanceLifecycle.Controller
    private lateinit var hostProfile: HostHardwareProfile
    private lateinit var viewModel: InstanceDetailViewModel

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("instance-detail-vm-test-")
        store = InstanceLifecycle.FileStore(tempDir)
        controller = InstanceLifecycle.Controller(store, fixedClock)
        hostProfile = HostHardwareProfile(
            8,
            16L * 1024L * 1024L * 1024L,
            8L * 1024L * 1024L * 1024L,
            512L * 1024L * 1024L,
            false
        )
        viewModel = InstanceDetailViewModel(store, controller, hostProfile, supportsDesktop = true)
    }

    @After
    fun tearDown() {
        if (::tempDir.isInitialized && Files.exists(tempDir)) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach {
                try {
                    Files.deleteIfExists(it)
                } catch (_: IOException) {}
            }
        }
    }

    @Test
    fun hardwareConfigModificationBlockedWhenRunningAndAllowedWhenStopped() {
        val rootVol = tempDir.resolve("alpine-hw.raw")
        Files.createFile(rootVol)

        var instance = controller.create(
            InstanceLifecycle.DistroReference("alpine", "3.20"),
            rootVol
        )
        controller.beginProvisioning(instance.id())
        controller.completeProvisioning(instance.id())
        instance = controller.start(instance.id())
        assertEquals(InstanceLifecycle.State.RUNNING, instance.state())

        viewModel.loadInstance(instance.id())
        val runningState = viewModel.uiState
        assertNotNull(runningState)
        assertFalse(runningState!!.canModifyHardware)
        assertEquals("请先关机后再调整硬件参数", runningState.hardwareModificationRejectionReason)

        // Attempting to modify hardware while running must be strictly intercepted
        val rejectedResult = viewModel.updateHardwareConfig(4, 4096)
        assertTrue(rejectedResult is InstanceDetailViewModel.UpdateHardwareResult.Rejected)
        val rejected = rejectedResult as InstanceDetailViewModel.UpdateHardwareResult.Rejected
        assertEquals("请先关机后再调整硬件参数", rejected.reason)

        // Stop the instance and verify modification is now permitted
        controller.recordGracefulStop(instance.id())
        viewModel.loadInstance(instance.id())
        val stoppedState = viewModel.uiState
        assertNotNull(stoppedState)
        assertEquals(InstanceLifecycle.State.STOPPED, stoppedState!!.state)
        assertTrue(stoppedState.canModifyHardware)

        // Update hardware while stopped succeeds
        val successResult = viewModel.updateHardwareConfig(4, 4096)
        assertTrue(successResult is InstanceDetailViewModel.UpdateHardwareResult.Success)
        val success = successResult as InstanceDetailViewModel.UpdateHardwareResult.Success
        assertEquals(4, success.instance.hardwareConfig().cpuCores())
        assertEquals(4096, success.instance.hardwareConfig().ramMegabytes())
        assertEquals(4, viewModel.uiState?.vCpu)
        assertEquals(4096, viewModel.uiState?.ramMb)
    }

    @Test
    fun dangerZoneDeleteRequiresExactMatchingNameAndBlocksWhenRunning() {
        val rootVol = tempDir.resolve("debian-del.raw")
        Files.createFile(rootVol)

        var instance = controller.create(
            InstanceLifecycle.DistroReference("debian", "12"),
            rootVol
        )
        controller.beginProvisioning(instance.id())
        controller.completeProvisioning(instance.id())
        instance = controller.start(instance.id())

        viewModel.loadInstance(instance.id())

        // 1. Running instance cannot be deleted even if name matches exactly
        assertFalse(viewModel.requestDelete(instance.id()))
        assertNotNull(store.load(instance.id()))

        // 2. Stop instance
        controller.recordGracefulStop(instance.id())
        viewModel.loadInstance(instance.id())
        assertTrue(viewModel.uiState!!.canDelete)

        // 3. Name mismatch is intercepted
        assertFalse(viewModel.requestDelete("wrong-name"))
        assertFalse(viewModel.requestDelete(""))
        assertFalse(viewModel.requestDelete(instance.id().substring(0, 3)))
        assertNotNull(store.load(instance.id()))

        // 4. Exact match executes deletion successfully
        assertTrue(viewModel.requestDelete(instance.id()))
        assertTrue(store.list().none { it.id() == instance.id() })
        assertNull(viewModel.uiState)
    }

    @Test
    fun consoleActionsAvailabilityAndStatusReflectionMatrixAcrossStates() {
        val rootVol = tempDir.resolve("arch-actions.raw")
        Files.createFile(rootVol)

        val instance = controller.create(
            InstanceLifecycle.DistroReference("arch", "rolling"),
            rootVol
        )
        controller.beginProvisioning(instance.id())
        controller.completeProvisioning(instance.id())

        // State: STOPPED
        viewModel.loadInstance(instance.id())
        var state = viewModel.uiState!!
        assertEquals(InstanceLifecycle.State.STOPPED, state.state)
        assertEquals("○ 已关机", state.stateBadgeText)
        assertEquals("离线", state.ipAddress)
        assertFalse(state.canOpenTerminal)
        assertFalse(state.canLaunchDesktop)
        assertTrue(state.canPowerOn)
        assertFalse(state.canPowerOff)
        assertTrue(state.canClone)
        assertTrue(state.canDelete)

        // State: RUNNING
        controller.start(instance.id())
        viewModel.loadInstance(instance.id())
        state = viewModel.uiState!!
        assertEquals(InstanceLifecycle.State.RUNNING, state.state)
        assertEquals("● 运行中", state.stateBadgeText)
        assertEquals("10.0.2.15", state.ipAddress)
        assertTrue(state.canOpenTerminal)
        assertTrue(state.canLaunchDesktop)
        assertFalse(state.canPowerOn)
        assertTrue(state.canPowerOff)
        assertFalse(state.canClone)
        assertFalse(state.canDelete)

        // State: CRASHED
        val crashLog = tempDir.resolve("panic.log")
        Files.write(crashLog, listOf("Kernel panic - not syncing: Fatal exception"))
        controller.recordCrash(instance.id(), 139, crashLog, "SIGSEGV")
        viewModel.loadInstance(instance.id())
        state = viewModel.uiState!!
        assertEquals(InstanceLifecycle.State.CRASHED, state.state)
        assertEquals("⚠ 崩溃告警", state.stateBadgeText)
        assertFalse(state.canOpenTerminal)
        assertFalse(state.canLaunchDesktop)
        assertFalse(state.canPowerOn)
        assertFalse(state.canPowerOff)
        assertTrue(state.hasCrashDiagnostic)
        assertEquals(139, state.crashDiagnostic?.exitCode())
        assertTrue(state.canDelete)
    }

    @Test
    fun cloneAndDeadlockResetLifecycleReflectedInViewModel() {
        val rootVol = tempDir.resolve("clone-test.raw")
        Files.createFile(rootVol)

        val instance = controller.create(
            InstanceLifecycle.DistroReference("alpine", "3.20"),
            rootVol
        )
        controller.beginProvisioning(instance.id())
        controller.completeProvisioning(instance.id())

        viewModel.loadInstance(instance.id())
        assertTrue(viewModel.uiState!!.canClone)

        // Clone while stopped succeeds
        val cloned = viewModel.cloneInstance()
        assertNotNull(cloned)
        assertTrue(store.list().any { it.id() == cloned!!.id() })

        // Start instance: clone should be blocked while running
        controller.start(instance.id())
        viewModel.loadInstance(instance.id())
        assertFalse(viewModel.uiState!!.canClone)
        assertNull(viewModel.cloneInstance())

        // Deadlock reset reconciles stale state back to STOPPED
        val reset = viewModel.resetDeadlockState()
        assertNotNull(reset)
        assertEquals(InstanceLifecycle.State.STOPPED, reset!!.state())
        assertEquals(InstanceLifecycle.State.STOPPED, viewModel.uiState!!.state)
    }
}

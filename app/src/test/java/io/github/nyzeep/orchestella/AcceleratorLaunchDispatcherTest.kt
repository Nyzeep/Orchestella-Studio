package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.engineavf.AvfStopResult
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DV-ACC: the launch-chain dispatch. While the flag is off the dispatch must
 * be today's launch, byte for byte; while it is on the assembly still decides.
 */
class AcceleratorLaunchDispatcherTest {
    // --- feature flag off: today's launch path, untouched -----------------------

    @Test
    fun flagOffDelegatesToTodayLaunchWithoutReadingFactsOrBuildingLanes() {
        val factReads = AtomicInteger()
        val facts = AcceleratorFactProvider {
            factReads.incrementAndGet()
            throw AssertionError("facts must not be read while the lane is off")
        }
        val today = InstanceStrategyLauncher.Outcome(
            FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
            FakeSession(), null, null,
        )

        val dispatch = AcceleratorLaunchDispatcher.dispatch(
            false, params(), failingQemuLaunch(), failingAccelLauncher(), facts,
            { null }, TRIPLE, FakeClock(), LaneEventSink.discarding(),
        ) { today }

        assertSame(today, dispatch.outcome)
        assertNull(dispatch.coordinator)
        assertEquals("", dispatch.decisionJson)
        assertEquals(0, factReads.get())
    }

    // --- feature flag on: the assembly decides, the dispatch wires --------------

    @Test
    fun armedFactsStartBothLanesAndReturnTheQemuSession() {
        val fixture = Fixture().apply { armedFacts() }

        val dispatch = fixture.dispatch()

        assertTrue(dispatch.decisionJson.contains("\"mode\":\"QEMU_PLUS_ACCEL\""))
        assertSame(fixture.qemuSession, dispatch.outcome.session)
        assertFalse(dispatch.outcome.hardwareAccelerated())
        assertEquals(1, fixture.qemuStarts.get())
        assertEquals(1, fixture.accelStarts.get())
        assertEquals(LaneState.STARTING, dispatch.coordinator!!.state(LaneId.QEMU))
        assertEquals(LaneState.STARTING, dispatch.coordinator!!.state(LaneId.ACCEL))
        assertTrue(dispatch.coordinator!!.hasLease(LaneId.ACCEL))
        // The envelope is exactly what the two lanes request; nothing is left over.
        assertEquals(0, dispatch.coordinator!!.ledger().snapshot().cpuCores())
        dispatch.coordinator!!.close()
    }

    @Test
    fun unarmedFactsNeverStartTheAcceleratorLane() {
        val fixture = Fixture().apply {
            facts = AcceleratorFactProvider {
                AcceleratorFacts.builder()
                    .avfPresent(false)
                    .nonProtectedSupported(false)
                    .acceleratorImagePresent(false)
                    .vsockSupported(false)
                    .compilerTriple("")
                    .thermalState(ThermalState.NOMINAL)
                    .batteryState(BatteryState.OK)
                    .build()
            }
        }

        val dispatch = fixture.dispatch()

        assertTrue(dispatch.decisionJson.contains("\"mode\":\"QEMU_ONLY\""))
        assertTrue(dispatch.decisionJson.contains("ACCEL_UNAVAILABLE_NO_NON_PROTECTED"))
        assertSame(fixture.qemuSession, dispatch.outcome.session)
        assertEquals(1, fixture.qemuStarts.get())
        assertEquals(0, fixture.accelStarts.get())
        assertEquals(0, fixture.engineLookups.get())
        assertEquals(LaneState.DISABLED, dispatch.coordinator!!.state(LaneId.ACCEL))
        assertFalse(dispatch.coordinator!!.hasLease(LaneId.ACCEL))
        dispatch.coordinator!!.close()
    }

    @Test
    fun qemuLaunchFailurePropagatesTheOriginalCause() {
        val fixture = Fixture()
        val failure = IOException("test-injected")
        fixture.qemuLaunch = AcceleratorLaunchDispatcher.QemuLaunch { throw failure }

        try {
            fixture.dispatch()
            fail("the original launch failure must reach the existing failure path")
        } catch (expected: IOException) {
            assertSame(failure, expected)
        }
    }

    @Test
    fun missingImageOnAnArmedDeviceDegradesToQemuOnlyWithoutTouchingQemu() {
        val fixture = Fixture().apply {
            armedFacts()
            // No image files installed: the launcher refuses with the fixed code.
            imageDirectory = Files.createTempDirectory("accel-image-absent")
        }

        val dispatch = fixture.dispatch()

        assertTrue(dispatch.decisionJson.contains("\"mode\":\"QEMU_PLUS_ACCEL\""))
        assertSame(fixture.qemuSession, dispatch.outcome.session)
        assertEquals(1, fixture.qemuStarts.get())
        assertEquals(1, fixture.accelStarts.get())
        assertEquals(LaneState.LOST, dispatch.coordinator!!.state(LaneId.ACCEL))
        assertEquals(LaneState.STARTING, dispatch.coordinator!!.state(LaneId.QEMU))
        dispatch.coordinator!!.close()
    }

    private fun failingQemuLaunch(): AcceleratorLaunchDispatcher.QemuLaunch =
        object : AcceleratorLaunchDispatcher.QemuLaunch {
            override fun launch(params: InstanceStrategyLauncher.LaunchParameters): ExecutionSession =
                throw AssertionError("qemu launch must not run")
        }

    private fun failingAccelLauncher(): () -> AcceleratorVmLauncher =
        { throw AssertionError("accel launcher must not be built") }

    private class ReadyHandle : AccelVmHandle {
        override fun isRuntimeAlive(): Boolean = true

        override fun tryOpenControlChannel(): Boolean = true

        override fun stopRuntime(graceMs: Long): AvfStopResult = AvfStopResult.CONFIRMED
    }

    private class FakeClock : LaneClock {
        var nanos: Long = 0

        override fun nanoTime(): Long = nanos

        override fun sleep(millis: Long) {
            nanos += maxOf(0L, millis) * 1_000_000L
        }
    }

    private open class FakeSession : RuntimeOwnedSession {
        override fun waitFor(): Int = 0

        override fun terminalInput(): OutputStream = ByteArrayOutputStream()

        override fun terminalOutput(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun controlInput(): OutputStream = ByteArrayOutputStream()

        override fun controlOutput(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun close() {
            // No resources in this fake.
        }

        override fun stopRuntime(graceMs: Long): RuntimeProcessSupervisor.StopResult =
            RuntimeProcessSupervisor.StopResult.CONFIRMED

        override fun isRuntimeAlive(): Boolean = true
    }

    private class Fixture {
        val qemuStarts = AtomicInteger()
        val accelStarts = AtomicInteger()
        val engineLookups = AtomicInteger()
        val qemuSession = FakeSession()
        var imageDirectory: Path? = null
        var facts: AcceleratorFactProvider = AcceleratorFactProvider { AcceleratorFacts.unknown() }
        var qemuLaunch: AcceleratorLaunchDispatcher.QemuLaunch =
            AcceleratorLaunchDispatcher.QemuLaunch {
                qemuStarts.incrementAndGet()
                qemuSession
            }

        fun armedFacts() {
            facts = AcceleratorFactProvider {
                AcceleratorFacts.builder()
                    .avfPresent(true)
                    .nonProtectedSupported(true)
                    .acceleratorImagePresent(true)
                    .vsockSupported(true)
                    .compilerTriple(TRIPLE)
                    .thermalState(ThermalState.NOMINAL)
                    .batteryState(BatteryState.CHARGING)
                    .build()
            }
        }

        fun dispatch(): AcceleratorLaunchDispatcher.Dispatch {
            val images = imageDirectory ?: installImageFiles()
            val launcher = AcceleratorVmLauncher(
                "accel-instance-1",
                images.resolve("kernel"),
                images.resolve("initrd"),
                images.resolve("rootfs.ext4"),
                2048L,
            ) {
                engineLookups.incrementAndGet()
                AcceleratorVmLauncher.Engine { _ ->
                    accelStarts.incrementAndGet()
                    ReadyHandle()
                }
            }
            return AcceleratorLaunchDispatcher.dispatch(
                true, params(), qemuLaunch, { launcher }, facts,
                { qemuSession }, TRIPLE, FakeClock(), { },
            ) {
                throw AssertionError("today's path must not run while the flag is on")
            }
        }

        private fun installImageFiles(): Path {
            val dir = Files.createTempDirectory("accel-image")
            Files.write(dir.resolve("kernel"), byteArrayOf(1))
            Files.write(dir.resolve("initrd"), byteArrayOf(2))
            Files.write(dir.resolve("rootfs.ext4"), byteArrayOf(3))
            return dir
        }
    }

    private companion object {
        const val TRIPLE = "aarch64-linux-gnu"

        fun params(): InstanceStrategyLauncher.LaunchParameters {
            val hardware = VmHardwareConfig(2, 2048, 8L * 1024L * 1024L * 1024L, false, "")
            return InstanceStrategyLauncher.LaunchParameters(
                "instance-1", "token", "rootfs.tar.xz", "/data/volume.raw",
                "/data/runtime", "/data/distro-inputs", "pw", hardware,
            )
        }
    }
}

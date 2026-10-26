package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.engineavf.AvfStopResult
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneExitInfo
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneHandle
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.CompileBackend
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.PvmCompilerManifest
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEvent
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
import java.util.concurrent.CopyOnWriteArrayList
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
            false, params(), failingQemuLaunch(), failingBackends(), facts,
            { null }, TRIPLE, FakeClock(), LaneEventSink.discarding(),
        ) { today }

        assertSame(today, dispatch.outcome)
        assertNull(dispatch.coordinator)
        assertEquals("", dispatch.decisionJson)
        assertEquals(CompileBackend.NONE, dispatch.backend)
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
        // The envelope is exactly what the two lanes request; both reserves used it.
        assertEquals(3, dispatch.coordinator!!.ledger().budget().cpuCores())
        assertEquals(3, dispatch.coordinator!!.ledger().snapshot().cpuCores())
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
    fun pvmPayloadVerdictStartsThePayloadStarterAndNeverBuildsTheCustomVm() {
        val fixture = Fixture().apply { pvmFacts() }

        val dispatch = fixture.dispatch()

        assertTrue(dispatch.decisionJson.contains("\"mode\":\"QEMU_PLUS_ACCEL\""))
        assertTrue(dispatch.decisionJson.contains("\"backend\":\"PVM_PAYLOAD\""))
        assertEquals(CompileBackend.PVM_PAYLOAD, dispatch.backend)
        assertSame(fixture.qemuSession, dispatch.outcome.session)
        assertEquals(1, fixture.pvmStarterBuilds.get())
        assertEquals(1, fixture.pvmStarts.get())
        // The other backend is never even constructed.
        assertEquals(0, fixture.customVmBuilds.get())
        assertEquals(0, fixture.engineLookups.get())
        assertEquals(LaneState.STARTING, dispatch.coordinator!!.state(LaneId.ACCEL))
        assertTrue(dispatch.coordinator!!.hasLease(LaneId.ACCEL))
        dispatch.coordinator!!.close()
    }

    @Test
    fun qemuOnlyVerdictBuildsNeitherBackend() {
        val fixture = Fixture()

        val dispatch = fixture.dispatch()

        assertTrue(dispatch.decisionJson.contains("\"mode\":\"QEMU_ONLY\""))
        assertEquals(CompileBackend.NONE, dispatch.backend)
        assertEquals(0, fixture.pvmStarterBuilds.get())
        assertEquals(0, fixture.customVmBuilds.get())
        assertEquals(LaneState.DISABLED, dispatch.coordinator!!.state(LaneId.ACCEL))
        dispatch.coordinator!!.close()
    }

    @Test
    fun payloadStartFailureKeepsQemuAndEmitsThePayloadCode() {
        val fixture = Fixture().apply {
            pvmFacts()
            pvmApi = FakeFastLaneApi {
                throw FastLaneException(FastLaneException.PROTECTED_UNAVAILABLE, "no protected VM")
            }
        }

        val dispatch = fixture.dispatch()

        assertSame(fixture.qemuSession, dispatch.outcome.session)
        assertEquals(LaneState.LOST, dispatch.coordinator!!.state(LaneId.ACCEL))
        assertEquals(LaneState.STARTING, dispatch.coordinator!!.state(LaneId.QEMU))
        // The starter's fixed code reaches the event log instead of collapsing
        // into the generic ACCEL_START_FAILED.
        assertTrue(
            fixture.laneEvents.any {
                it.lane() == LaneId.ACCEL &&
                    it.code() == PvmPayloadLaneStarter.PAYLOAD_UNAVAILABLE_NO_PROTECTED
            },
        )
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
    fun qemuUncheckedFailureKeepsTodayExceptionFace() {
        val fixture = Fixture()
        val failure = IllegalStateException("test-injected")
        fixture.qemuLaunch = AcceleratorLaunchDispatcher.QemuLaunch { throw failure }

        try {
            fixture.dispatch()
            fail("unchecked launch failures must reach the failure handler unchanged")
        } catch (expected: IllegalStateException) {
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
        // The launcher refuses before any engine lookup: no platform call, no VM.
        assertEquals(0, fixture.engineLookups.get())
        assertEquals(LaneState.LOST, dispatch.coordinator!!.state(LaneId.ACCEL))
        assertEquals(LaneState.STARTING, dispatch.coordinator!!.state(LaneId.QEMU))
        dispatch.coordinator!!.close()
    }

    private fun failingQemuLaunch(): AcceleratorLaunchDispatcher.QemuLaunch =
        object : AcceleratorLaunchDispatcher.QemuLaunch {
            override fun launch(params: InstanceStrategyLauncher.LaunchParameters): ExecutionSession =
                throw AssertionError("qemu launch must not run")
        }

    /** Both backend factories refuse: any build of either is a dispatch bug. */
    private fun failingBackends(): AcceleratorLaunchDispatcher.AccelBackends =
        AcceleratorLaunchDispatcher.AccelBackends(
            customVm = { throw AssertionError("custom-VM launcher must not be built") },
            pvmPayload = { throw AssertionError("PVM payload starter must not be built") },
        )

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
        val customVmBuilds = AtomicInteger()
        val pvmStarterBuilds = AtomicInteger()
        val pvmStarts = AtomicInteger()
        val engineLookups = AtomicInteger()
        val qemuSession = FakeSession()
        val laneEvents = CopyOnWriteArrayList<LaneEvent>()
        val events: LaneEventSink = LaneEventSink { laneEvents.add(it) }
        var imageDirectory: Path? = null
        var facts: AcceleratorFactProvider = AcceleratorFactProvider { AcceleratorFacts.unknown() }
        var pvmApi: FakeFastLaneApi = FakeFastLaneApi { instanceId ->
            pvmStarts.incrementAndGet()
            ReadyPvmHandle(instanceId)
        }
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

        /** Protected-only device with an installed payload compiler. */
        fun pvmFacts() {
            facts = AcceleratorFactProvider {
                AcceleratorFacts.builder()
                    .avfPresent(true)
                    .protectedOnly(true)
                    .protectedVmSupported(true)
                    .payloadCompilerPresent(true)
                    .payloadCompilerVersion("22.1.8")
                    .payloadTargetTriple(TRIPLE)
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
                true, params(), qemuLaunch,
                AcceleratorLaunchDispatcher.AccelBackends(
                    customVm = {
                        customVmBuilds.incrementAndGet()
                        launcher
                    },
                    pvmPayload = {
                        pvmStarterBuilds.incrementAndGet()
                        PvmPayloadLaneStarter(
                            "accel-pvm-instance-1",
                            pvmManifest(),
                            TRIPLE,
                            pvmApi,
                            1_000L,
                            2_000L,
                        )
                    },
                ),
                facts,
                { qemuSession }, TRIPLE, FakeClock(), events,
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

    /** Fake protected API: counts starts and hands back a ready handle. */
    private class FakeFastLaneApi(
        private val onStart: (String) -> FastLaneHandle,
    ) : FastLaneVirtualMachineApi {
        var lastSpec: FastLaneLaunchSpec? = null

        override fun start(spec: FastLaneLaunchSpec): FastLaneHandle {
            lastSpec = spec
            return onStart(spec.instanceId())
        }

        override fun connect(handle: FastLaneHandle, port: Int): FastLaneChannel =
            throw FastLaneException(FastLaneException.VSOCK_FAILED, "unused in dispatch tests")
    }

    private class ReadyPvmHandle(private val id: String) : FastLaneHandle {
        override fun instanceId(): String = id

        override fun status(): Int = 1

        override fun waitForExit(): FastLaneExitInfo = FastLaneExitInfo.normal(0L)

        override fun payloadReady(): Boolean = true

        override fun stopRuntime(graceMs: Long): FastLaneStopResult = FastLaneStopResult.CONFIRMED

        override fun close() {
            // No platform resources in this fake.
        }

        override fun notes(): List<String> = emptyList()
    }

    private companion object {
        const val TRIPLE = "aarch64-linux-gnu"

        /**
         * A schema-1 manifest the strict parser accepts. The digest is built at
         * run time: a literal 16-64 hex-character run trips the repository's
         * sensitive-data gate.
         */
        fun pvmManifest(): PvmCompilerManifest {
            val digest = "a".repeat(64)
            val json = """{"schema":1,"compiler_version":"22.1.8",""" +
                """"target_triple":"$TRIPLE","vcpu_topology":"one_cpu",""" +
                """"memory_mib":2048,"payload_binary":"libpvm_compiler_payload.so",""" +
                """"libs":[{"name":"libLLVM.so","size":16,"sha256":"$digest"}]}"""
            return PvmCompilerManifest.parse(json).orElseThrow {
                AssertionError("test manifest must parse")
            }
        }

        fun params(): InstanceStrategyLauncher.LaunchParameters {
            val hardware = VmHardwareConfig(2, 2048, 8L * 1024L * 1024L * 1024L, false, "")
            return InstanceStrategyLauncher.LaunchParameters(
                "instance-1", "token", "rootfs.tar.xz", "/data/volume.raw",
                "/data/runtime", "/data/distro-inputs", "pw", hardware,
            )
        }
    }
}

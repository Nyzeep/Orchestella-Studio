package io.github.nyzeep.orchestella

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lane A (2026-11-13) wiring-owner contract tests: the built-in-initramfs
 * console contract parser, the read-until-EOF drain, the read-only face
 * status registry, and the ride-along lane's honest arm/disarm behavior over
 * real [UmlFaceLauncher]s with injected seams (never the BuildConfig truth —
 * every face here is constructed directly with its shape and gate stated).
 */
class UmlFaceWiringTest {

    @Rule
    @JvmField
    var temporaryFolder: TemporaryFolder = TemporaryFolder()

    // --- the §4 console contract ------------------------------------------------

    @Test
    fun readyMarkersClassifyAsReady() {
        assertEquals(UmlConsoleLineKind.READY, UmlConsoleContract.classify("U1_T2_READY uptime=0.08").kind)
        assertEquals(UmlConsoleLineKind.READY, UmlConsoleContract.classify("U1_READY uptime=0.10").kind)
        // The judgment contract tolerates console noise around the marker.
        assertEquals(UmlConsoleLineKind.READY, UmlConsoleContract.classify("  some prefix U1_READY trailing").kind)
    }

    @Test
    fun initErrorClassifiesOnlyWithCodeAndStep() {
        assertEquals(
            UmlConsoleLineKind.INIT_ERROR,
            UmlConsoleContract.classify("UM_INIT_ERROR code=E_ROOT step=mount detail=huh").kind,
        )
        // The strict contract: a bare UM_INIT_ERROR without both keys is not
        // the init's structured failure line — it stays a plain line.
        assertEquals(UmlConsoleLineKind.PLAIN, UmlConsoleContract.classify("UM_INIT_ERROR").kind)
        assertEquals(UmlConsoleLineKind.PLAIN, UmlConsoleContract.classify("UM_INIT_ERROR code=E_ROOT").kind)
    }

    @Test
    fun kernelChatterAndInfoBannerStayPlain() {
        assertEquals(UmlConsoleLineKind.PLAIN, UmlConsoleContract.classify("").kind)
        assertEquals(UmlConsoleLineKind.PLAIN, UmlConsoleContract.classify("U1_INFO kernel=6.6.0 ncpus=1").kind)
        assertEquals(UmlConsoleLineKind.PLAIN, UmlConsoleContract.classify("random kernel line").kind)
    }

    @Test
    fun drainFeedsEveryClassifiedLineAndTerminatesAtEof() {
        val received = mutableListOf<UmlConsoleLine>()
        val eof = AtomicInteger()
        val sink = object : UmlConsoleSink {
            override fun onLine(line: UmlConsoleLine) {
                received.add(line)
            }

            override fun onConsoleEof() {
                eof.incrementAndGet()
            }
        }
        UmlConsoleDrain.drain(
            BufferedReader(StringReader("U1_T2_READY uptime=0.08\nUM_INIT_ERROR code=E_ROOT step=mount\nplain\n")),
            sink,
        )
        assertEquals(
            listOf(UmlConsoleLineKind.READY, UmlConsoleLineKind.INIT_ERROR, UmlConsoleLineKind.PLAIN),
            received.map { it.kind },
        )
        // The drain loop itself never fires EOF; the spawner's finally does.
        assertEquals(0, eof.get())
        // Empty input: the loop ends immediately, no lines, no EOF of its own.
        UmlConsoleDrain.drain(BufferedReader(StringReader("")), sink)
        assertEquals(3, received.size)
    }

    // --- the status registry ------------------------------------------------------

    @Test
    fun registryBindsOneFaceAtATimeAndUnregistersByInstance() {
        val faceA = FaceFactory.gateOff(temporaryFolder.newFolder("reg-a-lib"))
        val faceB = FaceFactory.gateOff(temporaryFolder.newFolder("reg-b-lib"))
        try {
            assertTrue(UmlFaceStatusRegistry.register("instance-a", faceA))
            assertFalse("a second face must not bind while one is bound",
                UmlFaceStatusRegistry.register("instance-b", faceB))
            assertEquals("instance-a", UmlFaceStatusRegistry.current()?.instanceId)
            assertFalse("a foreign instance id must not steal the binding",
                UmlFaceStatusRegistry.unregister("instance-b"))
            assertTrue(UmlFaceStatusRegistry.unregister("instance-a"))
            assertNull(UmlFaceStatusRegistry.current())
            assertTrue("the slot is free again", UmlFaceStatusRegistry.register("instance-b", faceB))
        } finally {
            UmlFaceStatusRegistry.unregister("instance-b")
        }
    }

    @Test
    fun runningWidthIsZeroWithoutABoundFace() {
        assertEquals(0, UmlFaceStatusRegistry.runningWidth())
    }

    // --- the ride-along lane over a real face --------------------------------------

    @Test
    fun gateOffArmIsAnHonestRefusalWithZeroSideEffects() {
        val lines = mutableListOf<String>()
        val face = FaceFactory.gateOff(temporaryFolder.newFolder("arm-gate-lib"))
        var backendCalls = 0
        val lane = UmlFaceRideAlong(
            backend = { _, gate ->
                backendCalls++
                assertFalse("the consumed gate must ride into the slot", gate)
                face
            },
            log = { lines.add(it) },
        )
        try {
            assertNull(lane.arm("arm-gate", false))
            assertEquals(1, backendCalls)
            assertTrue(lines.single().contains("GATE_DISABLED"))
            assertNull("a refused face never binds", UmlFaceStatusRegistry.current())
            assertEquals(0, UmlFaceStatusRegistry.runningWidth())
            assertEquals(0, face.width())
        } finally {
            UmlFaceStatusRegistry.unregister("arm-gate")
        }
    }

    @Test
    fun missingAssetArmIsAnHonestNamedRefusal() {
        val lines = mutableListOf<String>()
        val emptyLib = temporaryFolder.newFolder("arm-missing-lib")
        val face = FaceFactory.armed(emptyLib, withAssets = false, aliveProcess = true)
        val lane = UmlFaceRideAlong(backend = { _, _ -> face }, log = { lines.add(it) })
        try {
            assertNull(lane.arm("arm-missing", true))
            assertTrue(lines.single().contains("MISSING_ASSET"))
            assertTrue(lines.single().contains(UmlFaceLauncher.KERNEL_LIB_NAME))
            assertNull(UmlFaceStatusRegistry.current())
        } finally {
            UmlFaceStatusRegistry.unregister("arm-missing")
        }
    }

    @Test
    fun successfulArmBindsTheFaceAndStatesTheUnwiredChannel() {
        val lines = mutableListOf<String>()
        val lib = temporaryFolder.newFolder("arm-ok-lib")
        val face = FaceFactory.armed(lib, withAssets = true, aliveProcess = true)
        val lane = UmlFaceRideAlong(backend = { _, _ -> face }, log = { lines.add(it) })
        try {
            val armed = lane.arm("arm-ok", true)
            assertTrue(armed === face)
            assertTrue(lines.single().contains("width=1"))
            assertTrue("the unroutability is stated, never papered over",
                lines.single().contains("channel=unwired"))
            assertEquals("arm-ok", UmlFaceStatusRegistry.current()?.instanceId)
            assertEquals("the fast UP face's honest width while up", 1,
                UmlFaceStatusRegistry.runningWidth())
        } finally {
            lane.disarm("arm-ok")
            UmlFaceStatusRegistry.unregister("arm-ok")
        }
    }

    @Test
    fun disarmStopsUnregistersAndIsIdempotent() {
        val lines = mutableListOf<String>()
        val lib = temporaryFolder.newFolder("disarm-lib")
        val face = FaceFactory.armed(lib, withAssets = true, aliveProcess = true)
        val lane = UmlFaceRideAlong(backend = { _, _ -> face }, log = { lines.add(it) })
        try {
            lane.arm("disarm", true)
            lane.disarm("disarm")
            assertNull("disarm unbinds the face", UmlFaceStatusRegistry.current())
            assertEquals(0, UmlFaceStatusRegistry.runningWidth())
            assertEquals("a stopped face probes as not running",
                UmlFaceLauncher.Health.NOT_RUNNING, face.probe())
            // Idempotent: a second disarm on the unbound registry does nothing.
            lane.disarm("disarm")
            assertNull(UmlFaceStatusRegistry.current())
            assertEquals("exactly one stop line for one armed face",
                1, lines.count { it.contains("uml-face stopped") })
        } finally {
            UmlFaceStatusRegistry.unregister("disarm")
        }
    }

    @Test
    fun disarmIgnoresAForeignInstanceBinding() {
        val lines = mutableListOf<String>()
        val lib = temporaryFolder.newFolder("disarm-foreign-lib")
        val face = FaceFactory.armed(lib, withAssets = true, aliveProcess = true)
        val lane = UmlFaceRideAlong(backend = { _, _ -> face }, log = { lines.add(it) })
        try {
            lane.arm("disarm-owner", true)
            lane.disarm("disarm-stranger")
            assertEquals("the owner's binding survives a stranger's disarm",
                "disarm-owner", UmlFaceStatusRegistry.current()?.instanceId)
            assertEquals("no stop line for a stranger's disarm",
                0, lines.count { it.contains("uml-face stopped") })
        } finally {
            UmlFaceStatusRegistry.unregister("disarm-owner")
        }
    }

    @Test
    fun armRefusesWhileAnotherFaceIsBound() {
        val lines = mutableListOf<String>()
        val boundFace = FaceFactory.armed(temporaryFolder.newFolder("arm-busy-lib"), withAssets = true, aliveProcess = true)
        var backendCalls = 0
        val lane = UmlFaceRideAlong(
            backend = { _, _ ->
                backendCalls++
                FaceFactory.armed(temporaryFolder.newFolder("arm-busy-lib2"), withAssets = false, aliveProcess = true)
            },
            log = { lines.add(it) },
        )
        try {
            assertTrue(UmlFaceStatusRegistry.register("arm-busy-owner", boundFace))
            assertNull(lane.arm("arm-busy-other", true))
            assertEquals("the backend is never consulted while a face is bound", 0, backendCalls)
            assertTrue(lines.single().contains("already bound"))
            assertEquals("arm-busy-owner", UmlFaceStatusRegistry.current()?.instanceId)
        } finally {
            UmlFaceStatusRegistry.unregister("arm-busy-owner")
        }
    }

    // --- the console sink's honest EOF reap -----------------------------------------

    @Test
    fun consoleEofReapsADeadKernelAndReleasesTheKeepAlive() {
        val lines = mutableListOf<String>()
        val face = FaceFactory.armed(temporaryFolder.newFolder("eof-dead-lib"), withAssets = true, aliveProcess = false)
        val keepAlive = FaceFactory.lastKeepAlive!!
        val sink = UmlFaceConsoleSink(faceSource = { face }, log = { lines.add(it) })
        try {
            assertTrue(face.start().started())
            assertEquals(1, keepAlive.acquires.get())
            // The §4 probe shape self-terminated: console EOF with a dead kernel.
            sink.onConsoleEof()
            assertTrue(lines.last().contains("reaped"))
            assertEquals("the reap turns the expected exit into width 0", 0, face.width())
            assertEquals("the keep-alive is released by the reap", 1, keepAlive.releases.get())
            assertEquals(UmlFaceLauncher.Health.NOT_RUNNING, face.probe())
        } finally {
            UmlFaceStatusRegistry.unregister("eof-dead")
        }
    }

    @Test
    fun consoleEofTeardownRaceIsClosedByTheDelayedReprobe() {
        // Device-measured 2026-11-13: the kernel's console closes at powerdown
        // a beat before the process is reaped, so the EOF probe can still read
        // HEALTHY. The delayed re-probe must catch the death in the grace
        // window and run the honest reap (width 0, keep-alive released).
        val lines = mutableListOf<String>()
        val face = FaceFactory.armed(temporaryFolder.newFolder("eof-race-lib"), withAssets = true, aliveProcess = true)
        val keepAlive = FaceFactory.lastKeepAlive!!
        val sink = UmlFaceConsoleSink(
            faceSource = { face }, log = { lines.add(it) },
            // Direct runner standing in for the 2s daemon thread; the kernel's
            // teardown completes (the process dies) before the re-probe runs —
            // exactly the device-measured order.
            delayedReprobe = { task ->
                // The kernel's teardown completes (the process dies) before the
                // re-probe runs — exactly the device-measured order.
                FaceFactory.lastProcess!!.destroy()
                task.run()
            },
        )
        try {
            assertTrue(face.start().started())
            assertEquals("the kernel holds the keep-alive while alive", 1, keepAlive.acquires.get())
            sink.onConsoleEof()
            assertTrue("the EOF probe raced the teardown", lines.first().contains("re-probing after teardown grace"))
            assertTrue(lines.last().contains("reaped after teardown grace"))
            assertEquals("the re-probe reap zeroes the width", 0, face.width())
            assertEquals("the re-probe reap releases the keep-alive", 1, keepAlive.releases.get())
            assertEquals(UmlFaceLauncher.Health.NOT_RUNNING, face.probe())
        } finally {
            UmlFaceStatusRegistry.unregister("eof-race")
        }
    }

    @Test
    fun consoleEofWithALiveKernelLeavesTheStopToTheWiringOwner() {
        val lines = mutableListOf<String>()
        val face = FaceFactory.armed(temporaryFolder.newFolder("eof-live-lib"), withAssets = true, aliveProcess = true)
        val keepAlive = FaceFactory.lastKeepAlive!!
        val sink = UmlFaceConsoleSink(
            faceSource = { face }, log = { lines.add(it) },
            delayedReprobe = { it.run() },
        )
        try {
            assertTrue(face.start().started())
            sink.onConsoleEof()
            assertTrue(lines.last().contains("stop stays the wiring owner's call"))
            assertEquals("a live kernel keeps its honest width", 1, face.width())
            assertEquals("no premature keep-alive release", 0, keepAlive.releases.get())
        } finally {
            face.stop()
            UmlFaceStatusRegistry.unregister("eof-live")
        }
    }

    @Test
    fun consoleSinkWithoutABoundFaceIsInertAtEof() {
        val lines = mutableListOf<String>()
        val sink = UmlFaceConsoleSink(faceSource = { null }, log = { lines.add(it) })
        sink.onConsoleEof()
        assertTrue(lines.isEmpty())
    }

    // --- fixtures -------------------------------------------------------------------

    /** Counts keep-alive acquire/release; [FaceFactory.lastKeepAlive] exposes the newest. */
    private class CountingKeepAlive : UmlFaceLauncher.KeepAlive {
        val acquires = AtomicInteger()
        val releases = AtomicInteger()

        override fun acquire() {
            acquires.incrementAndGet()
        }

        override fun release() {
            releases.incrementAndGet()
        }
    }

    private class DeadOrAliveProcess(alive: Boolean) : UmlFaceLauncher.UmlProcess {
        private var alive = alive

        override fun isAlive(): Boolean = alive

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly() {
            alive = false
        }

        override fun waitForExit(timeoutMs: Long): Boolean = true
    }

    /** Single factory so every test face carries its own keep-alive counter. */
    private object FaceFactory {
        var lastKeepAlive: CountingKeepAlive? = null
            private set

        var lastProcess: DeadOrAliveProcess? = null
            private set

        /**
         * A gate-off face: start() answers the fixed GATE_DISABLED code with
         * zero side effects — the spawner throws if it is ever consulted.
         */
        fun gateOff(libDir: File): UmlFaceLauncher = UmlFaceLauncher(
            false,
            environment(libDir),
            UmlFaceLauncher.LaunchParams.defaults(),
            CountingKeepAlive(),
            { _, _, _ -> throw IOException("a gate-off face never spawns") },
            null,
            UmlFaceLauncher.FastProfile.initramfs(),
        )

        /**
         * A real §4 INITRAMFS fast-shape face: with assets, start() answers
         * STARTED and width() reads 1 while the (fake) kernel lives; without
         * assets the honest MISSING_ASSET refusal names the kernel first.
         */
        fun armed(libDir: File, withAssets: Boolean, aliveProcess: Boolean): UmlFaceLauncher {
            if (withAssets) {
                val kernel = File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME)
                val stub = File(libDir, UmlFaceLauncher.STUB_LIB_NAME)
                assertTrue(kernel.createNewFile())
                assertTrue(stub.createNewFile())
                // POSIX CI honors the executable bit; Windows canExecute() is
                // always true for existing files (assembly test's same rule).
                kernel.setExecutable(true)
                stub.setExecutable(true)
            }
            val keepAlive = CountingKeepAlive()
            lastKeepAlive = keepAlive
            val process = DeadOrAliveProcess(aliveProcess)
            lastProcess = process
            return UmlFaceLauncher(
                true,
                environment(libDir),
                UmlFaceLauncher.LaunchParams.defaults(),
                keepAlive,
                { _, _, _ -> process },
                null,
                UmlFaceLauncher.FastProfile.initramfs(),
            )
        }

        private fun environment(libDir: File): UmlFaceLauncher.Environment =
            object : UmlFaceLauncher.Environment {
                override fun nativeLibraryDir(): File = libDir

                override fun filesDir(): File = libDir
            }
    }
}

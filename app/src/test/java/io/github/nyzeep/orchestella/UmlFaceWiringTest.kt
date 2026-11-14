package io.github.nyzeep.orchestella

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
            backend = { _, gate, _ ->
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
        val lane = UmlFaceRideAlong(backend = { _, _, _ -> face }, log = { lines.add(it) })
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
        val lane = UmlFaceRideAlong(backend = { _, _, _ -> face }, log = { lines.add(it) })
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
        val lane = UmlFaceRideAlong(backend = { _, _, _ -> face }, log = { lines.add(it) })
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
        val lane = UmlFaceRideAlong(backend = { _, _, _ -> face }, log = { lines.add(it) })
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
            backend = { _, _, _ ->
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

    // --- the compile-bridge wiring (Gate 1, D-1): the wired flip ---------------------

    /**
     * The MainActivity-shaped backend: the channel rides the assembly slot
     * through the six-argument overload when present, the historical
     * five-argument one when not. Shape-proof: the runner shape's rootfs
     * directory exists either way.
     */
    private fun slotBackend(libDir: File): (String, Boolean, UmlFaceLauncher.Channel?) -> UmlFaceLauncher? {
        // Shape-proof assets: the runner shape needs kernel+stub executables and
        // the rootfs directory; the INITRAMFS shape ignores the rootfs.
        val kernel = File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME)
        val stub = File(libDir, UmlFaceLauncher.STUB_LIB_NAME)
        kernel.createNewFile()
        stub.createNewFile()
        kernel.setExecutable(true)
        stub.setExecutable(true)
        File(libDir, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs()
        return { _, gate, channel ->
            if (channel != null) {
                AcceleratorLaneAssembly.umlFace(
                    gate,
                    FaceFactory.environment(libDir),
                    UmlFaceLauncher.LaunchParams.defaults(),
                    CountingKeepAlive(),
                    FaceFactory.aliveSpawner(),
                    channel,
                )
            } else {
                AcceleratorLaneAssembly.umlFace(
                    gate,
                    FaceFactory.environment(libDir),
                    UmlFaceLauncher.LaunchParams.defaults(),
                    CountingKeepAlive(),
                    FaceFactory.aliveSpawner(),
                )
            }
        }
    }

    @Test
    fun wiredArmFlipsTheLogToChannelWiredAndRidesTheRealSlot() {
        val lines = mutableListOf<String>()
        val wiring = UmlCompileBridgeWiring(
            enabled = true,
            log = { lines.add(it) },
            listenerPort = 0,
        )
        val lib = temporaryFolder.newFolder("arm-wired-lib")
        val receivedChannel = java.util.concurrent.atomic.AtomicReference<UmlFaceLauncher.Channel?>()
        val delegate = slotBackend(lib)
        val lane = UmlFaceRideAlong(
            backend = { instanceId, gate, channel ->
                receivedChannel.set(channel)
                delegate(instanceId, gate, channel)
            },
            compileBridge = wiring,
            log = { lines.add(it) },
        )
        try {
            val armed = lane.arm("arm-wired", true)
            assertNotNull("the flag-on wiring provisions the D-1 lane channel", armed)
            assertNotNull(receivedChannel.get())
            val armLine = lines.single { it.startsWith("uml-face started") }
            assertEquals("the wired flip is exact", "uml-face started width=1 channel=wired", armLine)
            assertTrue("the wiring states its lane endpoint",
                lines.any { it.contains("uml-face channel wired: uml-tcp lane 127.0.0.1:") })
            assertEquals("the width reads real once the channel is wired", 1,
                UmlFaceStatusRegistry.runningWidth())
        } finally {
            lane.disarm("arm-wired")
            UmlFaceStatusRegistry.unregister("arm-wired")
        }
    }

    @Test
    fun unwiredArmKeepsTheByteIdenticalLogLine() {
        val lines = mutableListOf<String>()
        val lib = temporaryFolder.newFolder("arm-unwired-lib")
        val face = FaceFactory.armed(lib, withAssets = true, aliveProcess = true)
        val lane = UmlFaceRideAlong(
            backend = { _, _, channel ->
                assertNull("no wiring means no channel", channel)
                face
            },
            compileBridge = null,
            log = { lines.add(it) },
        )
        try {
            lane.arm("arm-unwired", true)
            assertEquals("the historical arm line, byte for byte",
                "uml-face started width=1 channel=unwired", lines.single())
        } finally {
            lane.disarm("arm-unwired")
            UmlFaceStatusRegistry.unregister("arm-unwired")
        }
    }

    @Test
    fun unwiredRunnerShapeStillReadsWidthZero() {
        // The launcher's locked width honesty, stated shape-explicitly: the
        // RUNNER shape (fastProfile null, constructed directly, never through
        // the build-flag-following assembly slot) without a channel reads 0 —
        // under both gradle flag variants. The wired test above proves the
        // same honesty flips real once the channel rides in.
        val lines = mutableListOf<String>()
        val lib = temporaryFolder.newFolder("arm-unwired-runner-lib")
        val kernel = File(lib, UmlFaceLauncher.KERNEL_LIB_NAME)
        val stub = File(lib, UmlFaceLauncher.STUB_LIB_NAME)
        kernel.createNewFile()
        stub.createNewFile()
        kernel.setExecutable(true)
        stub.setExecutable(true)
        File(lib, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs()
        val face = UmlFaceLauncher(
            true,
            FaceFactory.environment(lib),
            UmlFaceLauncher.LaunchParams.defaults(),
            CountingKeepAlive(),
            FaceFactory.aliveSpawner(),
            null,
            null, // the exact runner shape, independent of the build flag
        )
        val lane = UmlFaceRideAlong(
            backend = { _, _, channel ->
                assertNull(channel)
                face
            },
            compileBridge = null,
            log = { lines.add(it) },
        )
        try {
            lane.arm("arm-unwired-runner", true)
            assertEquals("uml-face started width=0 channel=unwired", lines.single())
            assertEquals(0, UmlFaceStatusRegistry.runningWidth())
        } finally {
            lane.disarm("arm-unwired-runner")
            UmlFaceStatusRegistry.unregister("arm-unwired-runner")
        }
    }

    @Test
    fun flagOffWiringIsInert() {
        val lines = mutableListOf<String>()
        val wiring = UmlCompileBridgeWiring(
            enabled = false,
            log = { lines.add(it) },
            listenerPort = 0,
        )
        assertNull("flag off never provisions a channel", wiring.faceChannel())
        wiring.bindFabric(
            FabricOrchestrator(false, { emptyMap() }, emptyMap()),
        )
        wiring.disarm()
        assertTrue("a flag-off build logs nothing and starts nothing", lines.isEmpty())
    }

    @Test
    fun bindFabricStartsTheSubmitterAndDisarmStopsIt() {
        val lines = mutableListOf<String>()
        val wiring = UmlCompileBridgeWiring(
            enabled = true,
            log = { lines.add(it) },
            listenerPort = 0,
        )
        val orchestrator = FabricOrchestrator(false, { emptyMap() }, emptyMap())
        wiring.bindFabric(orchestrator)
        assertTrue("the submitter listener is up for the armed orchestrator",
            lines.any { it.startsWith("fabric submitter listening 127.0.0.1:") })
        wiring.disarm()
        val listeningLines = lines.count { it.startsWith("fabric submitter listening") }
        assertEquals(1, listeningLines)
        // A fresh arm after disarm binds a fresh listener.
        wiring.bindFabric(orchestrator)
        assertEquals("the listener never outlives its disarm",
            2, lines.count { it.startsWith("fabric submitter listening") })
        wiring.disarm()
    }

    @Test
    fun rideAlongBindFabricReachesTheCompileBridge() {
        val lines = mutableListOf<String>()
        val wiring = UmlCompileBridgeWiring(
            enabled = true,
            log = { lines.add(it) },
            listenerPort = 0,
        )
        val lane = UmlFaceRideAlong(
            backend = { _, _, _ -> null },
            compileBridge = wiring,
            log = { lines.add(it) },
        )
        try {
            lane.bindFabric(FabricOrchestrator(false, { emptyMap() }, emptyMap()))
            assertTrue(lines.any { it.startsWith("fabric submitter listening") })
        } finally {
            wiring.disarm()
        }
    }

    @Test
    fun faceChannelClosesTheStaleSessionNeverDoubling() {
        val lines = mutableListOf<String>()
        val wiring = UmlCompileBridgeWiring(
            enabled = true,
            log = { lines.add(it) },
            guestDistccPort = 1, // an ephemeral-dead dial target; open() does no I/O
            listenerPort = 0,
        )
        val first = wiring.faceChannel()
        assertNotNull(first)
        val second = wiring.faceChannel()
        assertNotNull(second)
        // The stale channel was closed by the second provisioning: a dispatch
        // through the first adapter refuses with the fixed closed detail under
        // the uml-channel: prefix.
        val error = java.util.concurrent.atomic.AtomicReference<FabricOrchestrator.FaceException>()
        try {
            first!!.execute(1, FabricCompileItem(
                java.io.PipedInputStream(1024),
                java.io.PipedOutputStream(java.io.PipedInputStream(1024)),
            ))
        } catch (mapped: FabricOrchestrator.FaceException) {
            error.set(mapped)
        } catch (ignored: java.io.IOException) {
            // The fixture pipe setup cannot fail here.
        }
        assertEquals("uml-channel:" + UmlChannel.CLOSED_DETAIL, error.get()?.message)
        wiring.disarm()
    }

    // --- the net-supervisor spawner (Gate 2, umnet 候选 a) ---------------------------

    /** Records every spawn call; answers one live fake kernel process. */
    private class RecordingSpawner : UmlFaceLauncher.ProcessSpawner {
        val commands = mutableListOf<List<String>>()
        val environments = mutableListOf<Map<String, String>>()
        val workingDirs = mutableListOf<File>()
        var calls = 0
        val process = DeadOrAliveProcess(true)

        override fun spawn(
            command: List<String>,
            environment: Map<String, String>,
            workingDir: File,
        ): UmlFaceLauncher.UmlProcess {
            calls++
            commands.add(ArrayList(command))
            environments.add(java.util.LinkedHashMap(environment))
            workingDirs.add(workingDir)
            return process
        }
    }

    /** A native-library dir with both helper libs staged, exec bit set for POSIX CI. */
    private fun stagedHelperLibDir(name: String): File {
        val libDir = temporaryFolder.newFolder(name)
        val umnet = File(libDir, UmlNetSupervisorSpawner.NET_HELPER_LIB)
        val passt = File(libDir, UmlNetSupervisorSpawner.PASST_HELPER_LIB)
        assertTrue(umnet.createNewFile())
        assertTrue(passt.createNewFile())
        umnet.setExecutable(true)
        passt.setExecutable(true)
        return libDir
    }

    @Test
    fun flagOffNetSupervisorPassesThroughByteForByte() {
        // The libs are even present — the flag alone gates the rewrite.
        val libDir = stagedHelperLibDir("net-off-lib")
        val recorder = RecordingSpawner()
        val spawner = UmlNetSupervisorSpawner(recorder, enabled = false, nativeLibraryDir = { libDir })
        val command = listOf(
            File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME).absolutePath,
            "mem=512M", "rw", "con0=fd:0,fd:1",
        )
        val environment = mapOf("TMPDIR" to "/data/tmp", "PATH" to "/system/bin")
        val workingDir = temporaryFolder.newFolder("net-off-work")
        val process = spawner.spawn(command, environment, workingDir)
        assertEquals(1, recorder.calls)
        assertEquals("the original command rides through untouched", command, recorder.commands[0])
        assertEquals("the original environment rides through untouched", environment, recorder.environments[0])
        assertFalse(
            "a flag-off build never grows a supervisor variable",
            recorder.environments[0].containsKey(UmlNetSupervisorSpawner.NO_ISOLATION_ENV),
        )
        assertEquals(workingDir, recorder.workingDirs[0])
        assertSame("the delegate's process is the wrapper's answer", recorder.process, process)
    }

    @Test
    fun flagOnNetSupervisorRewritesCommandAndAddsNoIsolationEnv() {
        val libDir = stagedHelperLibDir("net-on-lib")
        val recorder = RecordingSpawner()
        val spawner = UmlNetSupervisorSpawner(recorder, enabled = true, nativeLibraryDir = { libDir })
        val kernel = File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME).absolutePath
        val command = listOf(kernel, "mem=512M", "rw", "con0=fd:0,fd:1")
        val environment = mapOf("TMPDIR" to "/data/tmp")
        val workingDir = temporaryFolder.newFolder("net-on-work")
        spawner.spawn(command, environment, workingDir)
        assertEquals(1, recorder.calls)
        assertEquals(
            "[umnet, --passt, passt, --, kernel, …args] exactly, in order",
            listOf(
                File(libDir, UmlNetSupervisorSpawner.NET_HELPER_LIB).absolutePath,
                "--passt",
                File(libDir, UmlNetSupervisorSpawner.PASST_HELPER_LIB).absolutePath,
                "--",
                kernel,
                "mem=512M", "rw", "con0=fd:0,fd:1",
            ),
            recorder.commands[0],
        )
        assertEquals("the isolation escape rides in the environment",
            "1", recorder.environments[0][UmlNetSupervisorSpawner.NO_ISOLATION_ENV])
        assertEquals("the original environment survives alongside it",
            "/data/tmp", recorder.environments[0]["TMPDIR"])
        assertEquals("the working dir is the kernel's own", workingDir, recorder.workingDirs[0])
        assertFalse("the caller's map is never mutated",
            environment.containsKey(UmlNetSupervisorSpawner.NO_ISOLATION_ENV))
    }

    @Test
    fun supervisorAndPasstPathsDeriveFromNativeLibraryDir() {
        // The kernel command and the working dir both live elsewhere on
        // purpose: the two helper paths must come from nativeLibraryDir alone.
        val libDir = stagedHelperLibDir("net-path-lib")
        val kernelElsewhere = File(
            temporaryFolder.newFolder("net-path-kernel"), UmlFaceLauncher.KERNEL_LIB_NAME,
        ).absolutePath
        val recorder = RecordingSpawner()
        val spawner = UmlNetSupervisorSpawner(recorder, enabled = true, nativeLibraryDir = { libDir })
        spawner.spawn(
            listOf(kernelElsewhere, "rw"),
            emptyMap(),
            temporaryFolder.newFolder("net-path-work"),
        )
        val rewritten = recorder.commands[0]
        assertEquals(File(libDir, UmlNetSupervisorSpawner.NET_HELPER_LIB).absolutePath, rewritten[0])
        assertEquals(UmlNetSupervisorSpawner.PASST_HELPER_LIB, File(rewritten[2]).name)
        assertEquals(File(libDir, UmlNetSupervisorSpawner.PASST_HELPER_LIB).absolutePath, rewritten[2])
        assertEquals("the kernel keeps its own path behind the separator",
            kernelElsewhere, rewritten[4])
    }

    @Test
    fun flagOnWithoutUmnetThrowsTheNamedIOExceptionAndNeverSpawns() {
        val libDir = temporaryFolder.newFolder("net-no-umnet-lib")
        val recorder = RecordingSpawner()
        val spawner = UmlNetSupervisorSpawner(recorder, enabled = true, nativeLibraryDir = { libDir })
        val failure = assertThrows(IOException::class.java) {
            spawner.spawn(listOf("kernel", "rw"), emptyMap(), temporaryFolder.newFolder("net-no-umnet-work"))
        }
        assertEquals("uml-net-helper missing: libuml_net.so", failure.message)
        assertEquals("the honest refusal never reaches the delegate", 0, recorder.calls)
    }

    @Test
    fun flagOnWithoutPasstThrowsTheNamedIOExceptionAndNeverSpawns() {
        val libDir = temporaryFolder.newFolder("net-no-passt-lib")
        val umnet = File(libDir, UmlNetSupervisorSpawner.NET_HELPER_LIB)
        assertTrue(umnet.createNewFile())
        umnet.setExecutable(true)
        val recorder = RecordingSpawner()
        val spawner = UmlNetSupervisorSpawner(recorder, enabled = true, nativeLibraryDir = { libDir })
        val failure = assertThrows(IOException::class.java) {
            spawner.spawn(listOf("kernel", "rw"), emptyMap(), temporaryFolder.newFolder("net-no-passt-work"))
        }
        assertEquals("uml-net-helper missing: libuml_passt.so", failure.message)
        assertEquals("the honest refusal never reaches the delegate", 0, recorder.calls)
    }

    @Test
    fun netSupervisorPassthroughKeepsTheConsoleDrainContract() {
        // A real process through the wrapper (flag off, delegate = the
        // draining spawner): the console lines still classify through the
        // contract and the EOF still fires exactly once — the wrapper adds
        // nothing between the drain and its sink.
        val lines = mutableListOf<UmlConsoleLine>()
        val eof = java.util.concurrent.CountDownLatch(1)
        val sink = object : UmlConsoleSink {
            override fun onLine(line: UmlConsoleLine) {
                lines.add(line)
            }

            override fun onConsoleEof() {
                eof.countDown()
            }
        }
        val libDir = stagedHelperLibDir("net-drain-lib")
        val spawner = UmlNetSupervisorSpawner(
            ConsoleDrainingUmlSpawner(sink),
            enabled = false,
            nativeLibraryDir = { libDir },
        )
        val osName = (System.getProperty("os.name") ?: "").lowercase()
        val javaBin = File(
            File(System.getProperty("java.home"), "bin"),
            if (osName.contains("win")) "java.exe" else "java",
        )
        val process = spawner.spawn(
            listOf(javaBin.absolutePath, "-version"),
            emptyMap(),
            temporaryFolder.newFolder("net-drain-work"),
        )
        assertTrue("the real process exits on its own", process.waitForExit(30_000))
        assertTrue("the console EOF arrives exactly once after exit", eof.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(lines.isNotEmpty())
        assertTrue(
            "java -version's output (stderr merged) drains as plain console lines",
            lines.all { it.kind == UmlConsoleLineKind.PLAIN } &&
                lines.any { it.raw.contains("version", ignoreCase = true) },
        )
    }

    // --- the Gate 2 E2E external-root extra-args decision (launcher v2 wiring) -------

    @Test
    fun wiredArmWithAReadyTreeRidesTheExternalRootTriple() {
        val tree = temporaryFolder.newFolder("umroot-ready")
        val args = UmlRootfsExtraArgs.forArm(
            compileBridgeEnabled = true,
            readiness = UmlRootfsProvisioner.ReadinessReport(true, true, tree.toPath(), 42, 42),
        )
        // Exactly the three-arg triple, in order: the provisioner-owned extract
        // tree's absolute path (the HOSTFS mount target the fast init
        // switch_roots into), the fstype, then the v2 launcher's um.net knob.
        assertEquals(
            listOf(
                "umroot=" + tree.absolutePath,
                UmlRootfsExtraArgs.UMROOT_FSTYPE_ARG,
                UmlRootfsExtraArgs.UM_NET_ARG,
            ),
            args,
        )
        assertTrue("the umroot target is absolute", File(args[0].removePrefix("umroot=")).isAbsolute)
    }

    @Test
    fun bridgeOffKeepsTheProbeShapeWithZeroExtraArgsEvenWhenTheTreeIsReady() {
        val tree = temporaryFolder.newFolder("umroot-bridge-off")
        // Flag off → zero, whatever the readiness says: an unwired build never
        // fakes a routable guest, and the probe's self-termination stays the
        // load-bearing EOF-reap contract.
        assertEquals(
            UmlRootfsExtraArgs.NONE,
            UmlRootfsExtraArgs.forArm(
                compileBridgeEnabled = false,
                readiness = UmlRootfsProvisioner.ReadinessReport(true, true, tree.toPath(), 42, 42),
            ),
        )
        assertEquals(
            UmlRootfsExtraArgs.NONE,
            UmlRootfsExtraArgs.forArm(false, UmlRootfsProvisioner.ReadinessReport.disabled()),
        )
    }

    @Test
    fun flagOnWithAnUnreadyOrGatedRootfsKeepsTheProbeShapeWithZeroExtraArgs() {
        // Enabled but not provisioned yet: the honest degradation is the
        // byte-identical probe shape, never a umroot pointing at a missing tree.
        assertEquals(
            UmlRootfsExtraArgs.NONE,
            UmlRootfsExtraArgs.forArm(true, UmlRootfsProvisioner.ReadinessReport.notReady()),
        )
        // The rootfs-download gate closed: the probe answers disabled and the
        // decision stays zero even with the bridge flag on.
        assertEquals(
            UmlRootfsExtraArgs.NONE,
            UmlRootfsExtraArgs.forArm(true, UmlRootfsProvisioner.ReadinessReport.disabled()),
        )
        // A ready report without a tree (the record allows it; forArm must not
        // build a umroot from nothing).
        assertEquals(
            UmlRootfsExtraArgs.NONE,
            UmlRootfsExtraArgs.forArm(
                true,
                UmlRootfsProvisioner.ReadinessReport(true, true, null, 0, 0),
            ),
        )
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

        /** A spawner that answers one live fake kernel process. */
        fun aliveSpawner(): UmlFaceLauncher.ProcessSpawner {
            val process = DeadOrAliveProcess(true)
            lastProcess = process
            return UmlFaceLauncher.ProcessSpawner { _, _, _ -> process }
        }

        fun environment(libDir: File): UmlFaceLauncher.Environment =
            object : UmlFaceLauncher.Environment {
                override fun nativeLibraryDir(): File = libDir

                override fun filesDir(): File = libDir
            }
    }
}

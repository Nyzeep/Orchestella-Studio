package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog
import io.github.nyzeep.orchestella.runtime.engineuml.UmlKernelPayloadContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class IsolatedQaExecutionEntryFullProfileTest {
    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun selectKernelPayloadRejectsNonQaBuilds() {
        val entry = IsolatedQaExecutionEntry { false }
        val libDir = tmp.newFolder("libs")
        assertThrows(IllegalStateException::class.java) {
            entry.selectKernelPayload(
                UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
                libDir,
                emptyMap<String, String>(),
                emptyMap<String, String>(),
            )
        }
    }

    @Test
    fun selectKernelPayloadSeparatesFullAndFastProfilesExplicitly() {
        val entry = IsolatedQaExecutionEntry { true }
        val libDir = tmp.newFolder("libs")
        val fastKernel = File(libDir, "libuml_kernel.so").apply {
            writeText("fast-k")
            setExecutable(true)
        }
        val fastStub = File(libDir, "libuml_stub.so").apply {
            writeText("fast-s")
            setExecutable(true)
        }
        val fastPins = mapOf(
            "libuml_kernel.so" to UmlKernelAssets.sha256Hex(fastKernel),
            "libuml_stub.so" to UmlKernelAssets.sha256Hex(fastStub),
        )
        val expectedFullPins = mapOf(
            "libuml_kernel_full.so" to "a".repeat(64),
            "libuml_stub_full.so" to "b".repeat(64),
        )

        val mismatch = entry.selectKernelPayload(
            UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
            libDir,
            fastPins,
            expectedFullPins,
        )
        assertFalse(mismatch.ok())
        assertEquals(UmlKernelPayloadContract.FailureCode.PROFILE_MISMATCH, mismatch.failureCode())

        val fastOk = entry.selectKernelPayload(
            UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
            libDir,
            fastPins,
            fastPins,
        )
        assertTrue(fastOk.ok())
        assertEquals(fastKernel.absolutePath, fastOk.kernelPath())
        assertEquals(fastStub.absolutePath, fastOk.stubPath())
    }

    @Test
    fun ensurePersistentDebianInstanceBindsFullSmpProfileOneVcpuAndOneGibRam() {
        val root = tmp.newFolder("qa-debian-instance").toPath()
        val filesDir = Files.createDirectories(root.resolve("files"))
        val store = io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore(
            filesDir.resolve("instances"),
        )
        val controller = io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Controller(
            store,
            java.time.Clock.systemUTC(),
        )
        val qaEntry = IsolatedQaExecutionEntry { true }
        val instance = qaEntry.ensurePersistentDebianInstance(
            store = store,
            controller = controller,
            qaRootsDir = filesDir.resolve("qa-uml-roots"),
        )
        assertEquals("debian", instance.distro().id())
        assertEquals("13", instance.distro().version())
        assertEquals(
            io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            instance.executionIdentity().strategy().strategy(),
        )
        assertEquals("full-smp-arm64", instance.kernelProfile())
        assertEquals(1, instance.hardwareConfig().cpuCores())
        assertEquals(1024, instance.hardwareConfig().ramMegabytes())
        assertEquals(
            IsolatedQaExecutionEntry.DEFAULT_DEBIAN_DISK_BYTES,
            instance.hardwareConfig().diskCapacityBytes(),
        )
        assertTrue(Files.exists(instance.rootVolume()))
    }

    @Test
    fun replaceInstanceRootVolumeExplicitlyPreservesFullSmpProfileAndDebianProvisionContract() {
        val root = tmp.newFolder("qa-debian-replace").toPath()
        val filesDir = Files.createDirectories(root.resolve("files"))
        val store = io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore(
            filesDir.resolve("instances"),
        )
        val controller = io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Controller(
            store,
            java.time.Clock.systemUTC(),
        )
        val qaEntry = IsolatedQaExecutionEntry { true }
        val instance = qaEntry.ensurePersistentDebianInstance(
            store = store,
            controller = controller,
            qaRootsDir = filesDir.resolve("qa-uml-roots"),
        )
        val seedTar = filesDir.resolve("debian-13-rootfs.tar.xz")
        val seedBytes = "fake-debian-tar-xz".toByteArray()
        Files.write(seedTar, seedBytes)
        val seedSha = UmlKernelAssets.sha256Hex(seedTar.toFile())
        val seedInput = io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceRootfsProvisioner.SeedInput(
            "debian",
            "13",
            seedTar,
            null,
            null,
            seedSha,
            seedBytes.size.toLong(),
        )

        var observedMarker = ""
        var observedMemMb = -1
        val fakeFactory = io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessFactory { spec, _, _ ->
            observedMemMb = spec.memMb()
            observedMarker = spec.ephemeralRootDir()
                .resolve("guest-assets/etc/orchestella/provision-marker")
                .toFile()
                .readText()
            val script = spec.ephemeralRootDir().resolve("um-command.sh").toFile().readText()
            val okLine = script.lineSequence()
                .first { line -> line.contains("ORCHESTELLA_UML_PROVISION_OK ") }
                .substringAfter("'")
                .substringBefore("\\n")
            java.nio.file.Files.newByteChannel(
                spec.ubd0Path(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
            ).use { ch ->
                ch.position(0x438)
                ch.write(java.nio.ByteBuffer.wrap(byteArrayOf(0x53, 0xEF.toByte())))
                ch.position(instance.hardwareConfig().diskCapacityBytes() - 1L)
                ch.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
            }
            object : io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessHandle {
                private val alive = java.util.concurrent.atomic.AtomicBoolean(false)
                private val outBytes = (okLine + "\n").toByteArray()
                override fun pid(): Long = 4321L
                override fun isAlive(): Boolean = alive.get()
                override fun waitFor(): Int = 0
                override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
                override fun destroy() { alive.set(false) }
                override fun destroyForcibly() { alive.set(false) }
                override fun terminalInput() = java.io.OutputStream.nullOutputStream()
                override fun terminalOutput() = java.io.ByteArrayInputStream(outBytes)
                override fun controlInput() = java.io.OutputStream.nullOutputStream()
                override fun controlOutput() = java.io.InputStream.nullInputStream()
                override fun resizeTerminal(columns: Int, rows: Int): Boolean = true
                override fun closeChannels() {}
            }
        }
        val stager = io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceRootfsProvisioner.ToolchainStager { stageDir ->
            Files.createDirectories(stageDir.resolve("sbin"))
            Files.createDirectories(stageDir.resolve("etc"))
            Files.createDirectories(stageDir.resolve("bin"))
            Files.createDirectories(stageDir.resolve("opt/droidlayer"))
            stageDir.resolve("sbin/mkfs.ext4").toFile().writeText("mkfs")
            stageDir.resolve("etc/mke2fs.conf").toFile().writeText("[defaults]\n")
            stageDir.resolve("bin/busybox").toFile().writeText("bb")
            stageDir.resolve("opt/droidlayer/guest-agent").toFile().writeText("agent")
        }
        val provisioner = io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceRootfsProvisioner(
            fakeFactory,
            io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry(),
            stager,
            io.github.nyzeep.orchestella.runtime.contract.StorageContract.StorageSpaceChecker { 16L * 1024L * 1024L * 1024L },
            java.time.Clock.systemUTC(),
        )
        val receipt = qaEntry.replaceInstanceRootVolumeExplicitly(
            store = store,
            controller = controller,
            provisioner = provisioner,
            reservationRegistry = io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry(),
            instanceId = instance.id(),
            seedInput = seedInput,
            migrationReason = "w0103-review-test",
            kernelPath = "libuml_kernel_full.so",
            stubExePath = "libuml_stub_full.so",
            runtimeDir = Files.createDirectories(root.resolve("rt")),
        )
        assertEquals("debian", receipt.distroId())
        assertEquals(768, observedMemMb)
        assertTrue(observedMarker.contains("kernel_profile=full-smp-arm64"))
    }
}

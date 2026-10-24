package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException
import io.github.nyzeep.orchestella.runtime.engineavf.AvfLaunchSpec
import io.github.nyzeep.orchestella.runtime.engineavf.AvfStopResult
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DV-ACC: the accelerator lane starter refuses instead of guessing. */
class AcceleratorVmLauncherTest {

    @Test
    fun missingImageFilesRefuseWithTheFixedCodeBeforeAnyPlatformCall() {
        val engineLookups = AtomicInteger()
        val empty = Files.createTempDirectory("accel-absent")
        val launcher = launcher(empty, engineLookups)

        try {
            launcher.start()
            fail("a missing image must refuse")
        } catch (expected: LaneStartException) {
            assertEquals(AcceleratorVmLauncher.IMAGE_MISSING, expected.code())
        }
        assertEquals(0, engineLookups.get())
    }

    @Test
    fun startBuildsTheNonProtectedCustomVmSpecAndWrapsTheRuntime() {
        val engineLookups = AtomicInteger()
        val captured = AtomicReference<AvfLaunchSpec>()
        val images = installImageFiles()
        val launcher = AcceleratorVmLauncher(
            "accel-instance-1",
            images.resolve("kernel"),
            images.resolve("initrd"),
            images.resolve("rootfs.ext4"),
            2048L,
        ) {
            engineLookups.incrementAndGet()
            AcceleratorVmLauncher.Engine { spec ->
                captured.set(spec)
                ReadyHandle()
            }
        }

        val runtime: LaneRuntime = launcher.start()

        assertEquals(1, engineLookups.get())
        val spec = captured.get()
        assertEquals("accel-instance-1", spec.instanceId())
        assertEquals(images.resolve("kernel").toString(), spec.kernelPath())
        assertEquals(images.resolve("initrd").toString(), spec.initramfsPath())
        assertEquals(images.resolve("rootfs.ext4").toString(), spec.rootVolumePath())
        assertEquals(2048L, spec.memoryMb())
        // The pinned AVF SystemApi can only express ONE_CPU; asking for more
        // would fail at the platform, so the launcher pins one vCPU.
        assertEquals(1, spec.cpuCount())
        assertEquals(LaneId.ACCEL, runtime.laneId())
        assertTrue(runtime.isAlive)
        assertTrue(runtime.isReady)
    }

    @Test
    fun platformFailureMapsToTheFixedStartCode() {
        val images = installImageFiles()
        val launcher = AcceleratorVmLauncher(
            "accel-instance-1",
            images.resolve("kernel"),
            images.resolve("initrd"),
            images.resolve("rootfs.ext4"),
            2048L,
        ) {
            AcceleratorVmLauncher.Engine { _ ->
                throw AvfEngineException("VM_CREATE_FAILED", "test-injected")
            }
        }

        try {
            launcher.start()
            fail("a platform failure must map to the fixed code")
        } catch (expected: LaneStartException) {
            assertEquals(AcceleratorVmLauncher.START_FAILED, expected.code())
        }
    }

    private fun launcher(images: Path, engineLookups: AtomicInteger): AcceleratorVmLauncher =
        AcceleratorVmLauncher(
            "accel-instance-1",
            images.resolve("kernel"),
            images.resolve("initrd"),
            images.resolve("rootfs.ext4"),
            2048L,
        ) {
            engineLookups.incrementAndGet()
            AcceleratorVmLauncher.Engine { ReadyHandle() }
        }

    private fun installImageFiles(): Path {
        val dir = Files.createTempDirectory("accel-image")
        Files.write(dir.resolve("kernel"), byteArrayOf(1))
        Files.write(dir.resolve("initrd"), byteArrayOf(2))
        Files.write(dir.resolve("rootfs.ext4"), byteArrayOf(3))
        return dir
    }

    private class ReadyHandle : AccelVmHandle {
        override fun isRuntimeAlive(): Boolean = true

        override fun tryOpenControlChannel(): Boolean = true

        override fun stopRuntime(graceMs: Long): AvfStopResult = AvfStopResult.CONFIRMED
    }
}

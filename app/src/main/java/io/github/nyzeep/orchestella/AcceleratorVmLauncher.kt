package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException
import io.github.nyzeep.orchestella.runtime.engineavf.AvfLaunchSpec
import io.github.nyzeep.orchestella.runtime.engineavf.AvfVirtualMachineEngine
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import java.nio.file.Files
import java.nio.file.Path

/**
 * DV-ACC: the accelerator lane's starter. Creates a real non-protected custom
 * VM — kernel, initrd and root volume from the installed accelerator image —
 * through the existing engine-avf seam (setProtectedVm(false) plus custom
 * kernel paths), the same path the AVF full lane already uses.
 *
 * The image is not shipped with this build. A launcher whose files are absent
 * refuses with the fixed [IMAGE_MISSING] code before any platform call, so
 * the policy can arm the lane only for an image that is actually installed;
 * nothing here invents or substitutes one.
 */
class AcceleratorVmLauncher(
    private val instanceId: String,
    private val kernelPath: Path,
    private val initramfsPath: Path,
    private val rootVolumePath: Path,
    private val memoryMb: Long,
    private val engine: () -> Engine,
) {
    /** Engine seam; production adapts [AvfVirtualMachineEngine.launch]. */
    fun interface Engine {
        @Throws(AvfEngineException::class)
        fun launch(spec: AvfLaunchSpec): AccelVmHandle
    }

    init {
        require(instanceId.isNotEmpty()) { "instanceId" }
        require(memoryMb in 1..16384L) { "memoryMb must be within (0, 16384]" }
    }

    /** Starts the accelerator VM; must return fast and never wait for readiness. */
    @Throws(LaneStartException::class)
    fun start(): LaneRuntime {
        if (!Files.isRegularFile(kernelPath) || !Files.isRegularFile(initramfsPath) ||
            !Files.isRegularFile(rootVolumePath)
        ) {
            throw LaneStartException(IMAGE_MISSING)
        }
        val spec = AvfLaunchSpec.builder(
            instanceId,
            kernelPath.toString(),
            initramfsPath.toString(),
            rootVolumePath.toString(),
        )
            .memoryMb(memoryMb)
            // The pinned AVF SystemApi can only express ONE_CPU topology; a
            // larger request would fail at the platform, so pin one vCPU.
            .cpuCount(1)
            .build()
        return try {
            AccelLaneRuntime(engine().launch(spec))
        } catch (failure: AvfEngineException) {
            throw LaneStartException(START_FAILED)
        } catch (failure: RuntimeException) {
            throw LaneStartException(START_FAILED)
        }
    }

    companion object {
        /** Fixed code when the accelerator image is not installed or is incomplete. */
        const val IMAGE_MISSING = "ACCEL_IMAGE_MISSING"

        /** Fixed code when the platform refused to create or start the VM. */
        const val START_FAILED = "ACCEL_START_FAILED"

        /**
         * Production engine adapter. The control-channel connect attempt is the
         * readiness probe: opening the channel once and closing it is the same
         * signal the launch chain already waits for, and it carries no product
         * side effect beyond the connection itself.
         */
        fun engine(avfEngine: AvfVirtualMachineEngine): Engine = Engine { spec ->
            val session = avfEngine.launch(spec)
            object : AccelVmHandle {
                override fun isRuntimeAlive(): Boolean = session.isRuntimeAlive()

                override fun tryOpenControlChannel(): Boolean = try {
                    session.controlChannel().use { channel -> channel != null }
                } catch (blocked: Exception) {
                    false
                }

                override fun stopRuntime(graceMs: Long) = session.stopRuntime(graceMs)
            }
        }
    }
}

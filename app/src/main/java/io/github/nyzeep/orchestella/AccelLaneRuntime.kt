package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.engineavf.AvfStopResult
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome

/**
 * Narrow seam over one running accelerator VM; the production adapter wraps
 * the engine-avf session. The production control-channel probe is bounded by
 * the engine's internal connect-retry window (hundreds of ms to tens of
 * seconds when the guest is absent), not a single fast poll — before this
 * readiness signal is ever wired into {@code awaitAcceleratorReady}, the
 * probe must become non-blocking or the readiness wait must move off the
 * coordinator lock.
 */
interface AccelVmHandle {
    fun isRuntimeAlive(): Boolean

    /** Attempts the control-channel connect; true only when the guest answered. */
    fun tryOpenControlChannel(): Boolean

    fun stopRuntime(graceMs: Long): AvfStopResult
}

/**
 * DV-ACC: accelerator lane adapter over one running non-protected VM.
 *
 * Readiness is a guest signal — the control vsock answering once — never
 * liveness. A dead runtime is not ready even when the signal was seen, and a
 * live runtime without a signal keeps the lane in STARTING for the
 * coordinator, mirroring the QEMU lane adapter's discipline.
 */
class AccelLaneRuntime(private val handle: AccelVmHandle) : LaneRuntime {
    @Volatile
    private var controlSeen = false

    override fun laneId(): LaneId = LaneId.ACCEL

    override fun isAlive(): Boolean = handle.isRuntimeAlive()

    override fun isReady(): Boolean {
        if (!handle.isRuntimeAlive()) {
            return false
        }
        if (controlSeen) {
            return true
        }
        if (handle.tryOpenControlChannel()) {
            controlSeen = true
            return true
        }
        return false
    }

    override fun stop(graceMs: Long): LaneStopOutcome {
        return when (val result = handle.stopRuntime(maxOf(0L, graceMs))) {
            AvfStopResult.CONFIRMED -> LaneStopOutcome.CONFIRMED
            AvfStopResult.ALREADY_STOPPED -> LaneStopOutcome.ALREADY_STOPPED
            AvfStopResult.UNCONFIRMED -> LaneStopOutcome.UNCONFIRMED
            null -> LaneStopOutcome.UNCONFIRMED
        }
    }
}

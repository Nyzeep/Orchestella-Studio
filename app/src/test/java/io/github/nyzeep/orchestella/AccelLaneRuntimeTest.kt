package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.engineavf.AvfStopResult
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DV-ACC: accelerator lane readiness is a guest signal observed once, never
 * liveness; stop outcomes map one to one.
 */
class AccelLaneRuntimeTest {

    @Test
    fun readyRequiresAliveAndTheControlSignalExactlyOnce() {
        val handle = CountingHandle()
        handle.alive = false

        val runtime = AccelLaneRuntime(handle)

        assertFalse(runtime.isReady)
        assertEquals(0, handle.controlAttempts.get())

        handle.alive = true
        assertFalse(runtime.isReady)
        assertEquals(1, handle.controlAttempts.get())

        handle.controlOpens = true
        assertTrue(runtime.isReady)
        assertEquals(2, handle.controlAttempts.get())

        // The guest answered once; the lane stays ready without re-connecting.
        assertTrue(runtime.isReady)
        assertEquals(2, handle.controlAttempts.get())
    }

    @Test
    fun readyStaysFalseWhenTheRuntimeDiesAfterTheSignal() {
        val handle = CountingHandle()
        handle.controlOpens = true
        val runtime = AccelLaneRuntime(handle)
        assertTrue(runtime.isReady)

        handle.alive = false
        assertFalse(runtime.isReady)
    }

    @Test
    fun laneIdIsAccel() {
        val runtime = AccelLaneRuntime(CountingHandle())
        assertEquals(LaneId.ACCEL, runtime.laneId())
    }

    @Test
    fun stopMapsEveryOutcomeAndNeverPassesANegativeGrace() {
        val handle = CountingHandle()
        val runtime = AccelLaneRuntime(handle)

        handle.stopResult = AvfStopResult.CONFIRMED
        assertEquals(LaneStopOutcome.CONFIRMED, runtime.stop(500L))
        assertEquals(500L, handle.lastGraceMs.get())

        handle.stopResult = AvfStopResult.ALREADY_STOPPED
        assertEquals(LaneStopOutcome.ALREADY_STOPPED, runtime.stop(0L))

        handle.stopResult = AvfStopResult.UNCONFIRMED
        assertEquals(LaneStopOutcome.UNCONFIRMED, runtime.stop(-1L))
        assertEquals(0L, handle.lastGraceMs.get())
    }

    private class CountingHandle : AccelVmHandle {
        val controlAttempts = AtomicInteger()
        val lastGraceMs = AtomicLong(-1L)
        var alive = true
        var controlOpens = false
        var stopResult = AvfStopResult.CONFIRMED

        override fun isRuntimeAlive(): Boolean = alive

        override fun tryOpenControlChannel(): Boolean {
            controlAttempts.incrementAndGet()
            return controlOpens
        }

        override fun stopRuntime(graceMs: Long): AvfStopResult {
            lastGraceMs.set(graceMs)
            return stopResult
        }
    }
}

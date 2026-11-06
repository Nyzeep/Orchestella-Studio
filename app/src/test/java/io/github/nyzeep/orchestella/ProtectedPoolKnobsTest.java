package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Locks the shared protected-pool knob vocabulary: both pools' {@code Config} builders must
 * keep the historical messages and sentinel bounds now that the validation lives in
 * {@link ProtectedPoolKnobs} instead of being duplicated per pool.
 */
public final class ProtectedPoolKnobsTest {

    @Test
    public void warmPoolConfigKeepsHistoricalValidationMessages() {
        IllegalArgumentException memory = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWarmPool.Config.builder().memoryMb(0L).build());
        assertEquals("memoryMb must be within (0, 16384]", memory.getMessage());

        IllegalArgumentException memoryHigh = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWarmPool.Config.builder().memoryMb(16_385L).build());
        assertEquals("memoryMb must be within (0, 16384]", memoryHigh.getMessage());

        IllegalArgumentException timeouts = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWarmPool.Config.builder().helloTimeoutMs(0L).build());
        assertEquals("timeouts must be non-negative", timeouts.getMessage());

        IllegalArgumentException maxWait = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWarmPool.Config.builder().maxWaitMs(-1L).build());
        assertEquals("timeouts must be non-negative", maxWait.getMessage());

        IllegalArgumentException prefix = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWarmPool.Config.builder().instancePrefix("bad prefix!").build());
        assertEquals("instancePrefix must match [A-Za-z0-9._-]{1,48}", prefix.getMessage());

        // Boundary: the shared ceiling accepts exactly 16384 MiB.
        ProtectedWarmPool.Config.defaults();
        ProtectedWarmPool.Config.builder().memoryMb(16_384L).build();
    }

    @Test
    public void workerPoolConfigKeepsHistoricalValidationMessages() {
        IllegalArgumentException memory = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWorkerPool.Config.builder().memoryMbPerInstance(0L).build());
        assertEquals("memoryMbPerInstance must be within (0, 16384]", memory.getMessage());

        IllegalArgumentException timeouts = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWorkerPool.Config.builder().queueTimeoutMs(-1L).build());
        assertEquals("timeouts must be non-negative", timeouts.getMessage());

        IllegalArgumentException hello = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWorkerPool.Config.builder().helloTimeoutMs(-1L).build());
        assertEquals("timeouts must be non-negative", hello.getMessage());

        IllegalArgumentException prefix = assertThrows(IllegalArgumentException.class,
                () -> ProtectedWorkerPool.Config.builder().instancePrefix("bad prefix!").build());
        assertEquals("instancePrefix must match [A-Za-z0-9._-]{1,48}", prefix.getMessage());

        // Boundary: the shared ceiling accepts exactly 16384 MiB.
        ProtectedWorkerPool.Config.builder().memoryMbPerInstance(16_384L).build();
    }

    @Test
    public void sharedDefaultsKeepBothPoolsAligned() {
        ProtectedWarmPool.Config warm = ProtectedWarmPool.Config.defaults();
        assertEquals(ProtectedPoolKnobs.DEFAULT_MEMORY_MB, warm.memoryMb());
        assertEquals(ProtectedPoolKnobs.DEFAULT_IDLE_TTL_MS, warm.idleTtlMs());
        assertEquals(ProtectedPoolKnobs.DEFAULT_HELLO_TIMEOUT_MS, warm.helloTimeoutMs());
        assertEquals(ProtectedPoolKnobs.DEFAULT_PAYLOAD_READY_TIMEOUT_MS, warm.payloadReadyTimeoutMs());
        assertEquals(ProtectedPoolKnobs.DEFAULT_DRAIN_TIMEOUT_MS, warm.drainTimeoutMs());
        assertEquals(ProtectedPoolKnobs.DEFAULT_STOP_GRACE_MS, warm.stopGraceMs());

        ProtectedWorkerPool.Config worker = ProtectedWorkerPool.Config.defaults();
        assertEquals(ProtectedPoolKnobs.DEFAULT_MEMORY_MB, worker.memoryMbPerInstance());
        assertEquals(ProtectedPoolKnobs.DEFAULT_IDLE_TTL_MS, worker.idleTtlMs());
        assertEquals(ProtectedPoolKnobs.DEFAULT_HELLO_TIMEOUT_MS, worker.helloTimeoutMs());
        assertEquals(ProtectedPoolKnobs.DEFAULT_PAYLOAD_READY_TIMEOUT_MS, worker.payloadReadyTimeoutMs());
        assertEquals(ProtectedPoolKnobs.DEFAULT_DRAIN_TIMEOUT_MS, worker.drainTimeoutMs());
        assertEquals(ProtectedPoolKnobs.DEFAULT_STOP_GRACE_MS, worker.stopGraceMs());
        assertEquals(0, worker.capacity());
    }
}

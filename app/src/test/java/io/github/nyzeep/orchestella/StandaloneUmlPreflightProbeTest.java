package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.capability.ExecutionStrategyAdmissionEvaluator;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * Pure-JVM unit tests for {@link StandaloneUmlPreflightProbe}: verifies bounded timeout
 * classification, failure/incomplete handling, and strict isolation from ride-along face state.
 */
public final class StandaloneUmlPreflightProbeTest {

    @Test
    public void returnsReadyWhenCheckSucceedsWithinTimeout() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        StandaloneUmlPreflightProbe probe = new StandaloneUmlPreflightProbe(
                () -> {
                    clock.addAndGet(15_000_000L);
                    return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                            ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                            4096,
                            15L,
                            "4 KB host & bundled UML assets verified");
                },
                500L,
                clock::get);

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result = probe.probe();
        assertEquals(ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY, result.status());
        assertEquals(4096, result.hostPageSizeBytes());
        assertTrue(result.elapsedMs() >= 15L);
    }

    @Test
    public void returnsTimedOutWhenCheckExceedsBoundedTimeout() {
        StandaloneUmlPreflightProbe probe = new StandaloneUmlPreflightProbe(
                () -> {
                    Thread.sleep(2_000L);
                    return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                            ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                            4096,
                            2_000L,
                            "too slow");
                },
                50L,
                System::nanoTime);

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result = probe.probe();
        assertEquals(ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.TIMED_OUT, result.status());
        assertTrue(result.detail().contains("超时"));
    }

    @Test
    public void returnsIncompleteWhenCheckThrowsAndIgnoresRideAlongFaceState() {
        // Even if ride-along compile face were active or idle, standalone preflight only reflects its own probe.
        StandaloneUmlPreflightProbe probe = new StandaloneUmlPreflightProbe(
                () -> {
                    throw new IllegalStateException("sysconf unavailable");
                },
                500L,
                System::nanoTime);

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result = probe.probe();
        assertEquals(ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.INCOMPLETE, result.status());
        assertTrue(result.detail().contains("sysconf unavailable"));
    }
}

package io.github.nyzeep.orchestella.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * G-05 probe-walk FSM tests for {@link FallbackAudioSource}: phase transitions,
 * probe rhythm bookkeeping and the honest TCP-fallback reachability note. The
 * pre-existing behavior tests (attempt counts, lastError rethrow, close contract)
 * live in {@link FallbackAudioSourceTest} and keep passing unchanged.
 */
public class FallbackAudioSourceProbeTest {

    private static final class MockAudioSource implements AudioSourceSeam {
        boolean shouldFail = false;
        boolean connected = false;
        final byte[] data = new byte[]{1, 2, 3};
        int openAttempts = 0;

        @Override
        public InputStream openStream() throws IOException {
            openAttempts++;
            if (shouldFail) {
                throw new IOException("Simulated source failure");
            }
            connected = true;
            return new ByteArrayInputStream(data);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            connected = false;
        }
    }

    private static String detailsOf(List<FallbackAudioSource.Phase> phases, List<String> details,
            FallbackAudioSource.Phase phase) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i) == phase) {
                joined.append(details.get(i)).append('\n');
            }
        }
        return joined.toString();
    }

    @Test
    public void failedRoundBookkeepingRecoversOnTheNextSuccessfulProbe() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        MockAudioSource secondary = new MockAudioSource();
        List<FallbackAudioSource.Phase> phases = new ArrayList<>();
        List<String> details = new ArrayList<>();
        // One round per openStream: round 1 exhausts both sources, round 2 recovers.
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 1, 0,
                (phase, detail) -> {
                    phases.add(phase);
                    details.add(detail);
                });

        primary.shouldFail = true;
        secondary.shouldFail = true;
        try {
            fallback.openStream();
            fail("an exhausted round must throw the last transport error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Simulated source failure"));
        }
        assertEquals(FallbackAudioSource.Phase.EXHAUSTED, fallback.getPhase());
        assertEquals("one failed round must be booked", 1, fallback.getConsecutiveFailedRounds());
        assertTrue("the backoff copy must state the hostfwd reachability caveat",
                detailsOf(phases, details, FallbackAudioSource.Phase.BACKOFF)
                        .contains(FallbackAudioSource.SECONDARY_REACHABILITY_NOTE));

        // Recovery probe: primary comes back and clears the failure bookkeeping.
        primary.shouldFail = false;
        secondary.shouldFail = false;
        InputStream stream = fallback.openStream();
        assertNotNull(stream);
        assertSame(primary, fallback.getActiveSource());
        assertEquals(FallbackAudioSource.Phase.PRIMARY_ACTIVE, fallback.getPhase());
        assertEquals("a successful probe clears the failure bookkeeping",
                0, fallback.getConsecutiveFailedRounds());
        assertTrue(detailsOf(phases, details, FallbackAudioSource.Phase.PRIMARY_ACTIVE)
                .contains("recovered-after-1-failed-rounds"));

        fallback.close();
        assertEquals(FallbackAudioSource.Phase.CLOSED, fallback.getPhase());
    }

    @Test
    public void primaryFailureWalksSecondaryWithTheHonestReachabilityNote() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        primary.shouldFail = true;
        MockAudioSource secondary = new MockAudioSource();
        List<FallbackAudioSource.Phase> phases = new ArrayList<>();
        List<String> details = new ArrayList<>();
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 2, 1,
                (phase, detail) -> {
                    phases.add(phase);
                    details.add(detail);
                });

        InputStream stream = fallback.openStream();
        assertNotNull(stream);
        assertSame(secondary, fallback.getActiveSource());
        assertEquals(FallbackAudioSource.Phase.SECONDARY_ACTIVE, fallback.getPhase());

        String secondaryEvents = detailsOf(phases, details, FallbackAudioSource.Phase.SECONDARY_ACTIVE);
        assertTrue("the fallback copy must state the hostfwd reachability caveat",
                secondaryEvents.contains(FallbackAudioSource.SECONDARY_REACHABILITY_NOTE));
        assertTrue("the walk must record why the primary was left",
                detailsOf(phases, details, FallbackAudioSource.Phase.PROBING_SECONDARY)
                        .contains("primary-unavailable"));

        fallback.close();
        assertEquals(FallbackAudioSource.Phase.CLOSED, fallback.getPhase());
    }

    @Test
    public void exhaustedExceptionKeepsTheOriginalTransportMessage() {
        MockAudioSource primary = new MockAudioSource();
        primary.shouldFail = true;
        MockAudioSource secondary = new MockAudioSource();
        secondary.shouldFail = true;
        List<FallbackAudioSource.Phase> phases = new ArrayList<>();
        List<String> details = new ArrayList<>();
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 2, 0,
                (phase, detail) -> {
                    phases.add(phase);
                    details.add(detail);
                });

        try {
            fallback.openStream();
            fail("exhausted probes must throw the last transport error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Simulated source failure"));
        }
        assertEquals(FallbackAudioSource.Phase.EXHAUSTED, fallback.getPhase());
        assertEquals(2, fallback.getConsecutiveFailedRounds());
        assertEquals(2, primary.openAttempts);
        assertEquals(2, secondary.openAttempts);
        assertTrue(detailsOf(phases, details, FallbackAudioSource.Phase.EXHAUSTED)
                .contains("both-sources-failed"));
        // The backoff details must carry the honest secondary note for every failed round.
        assertEquals(2, detailsOf(phases, details, FallbackAudioSource.Phase.BACKOFF)
                .split(FallbackAudioSource.SECONDARY_REACHABILITY_NOTE, -1).length - 1);
    }

    @Test
    public void nullStreamIsReportedAsAProbeFailure() throws Exception {
        AudioSourceSeam nullPrimary = new AudioSourceSeam() {
            @Override public InputStream openStream() { return null; }
            @Override public boolean isConnected() { return false; }
            @Override public void close() { }
        };
        MockAudioSource secondary = new MockAudioSource();
        FallbackAudioSource fallback = new FallbackAudioSource(nullPrimary, secondary, 1, 0);

        InputStream stream = fallback.openStream();
        assertSame(secondary, fallback.getActiveSource());
        assertNotNull(stream);
        assertEquals(1, stream.read());

        fallback.close();
    }

    @Test
    public void interruptDuringBackoffRethrowsWithInterruptStatus() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        primary.shouldFail = true;
        MockAudioSource secondary = new MockAudioSource();
        secondary.shouldFail = true;
        // Deterministic phase sync: the interrupter is released by the FSM observer
        // exactly when the walk enters BACKOFF (immediately followed by the backoff
        // sleep), so the interrupt can only land at-or-inside that sleep. A blind
        // timed delay could fire while the probe thread was still descheduled before
        // openStream's first interrupt check, letting the loop-top guard consume the
        // flag as an early bail-out (EXHAUSTED, no InterruptedException cause).
        CountDownLatch backoffEntered = new CountDownLatch(1);
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 2, 60_000,
                (phase, detail) -> {
                    if (phase == FallbackAudioSource.Phase.BACKOFF) {
                        backoffEntered.countDown();
                    }
                });

        // Interrupt while the walk is parked in the backoff sleep (not before it starts).
        Thread probe = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                // The walk reaches BACKOFF within microseconds; the bound only keeps
                // the interrupter from lingering forever if the probe wedges.
                backoffEntered.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // best-effort interrupter
            }
            probe.interrupt();
        });
        interrupter.setDaemon(true);
        interrupter.start();
        try {
            fallback.openStream();
            fail("an interrupt during the probe rhythm must surface as an IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getCause());
            assertTrue(expected.getCause() instanceof InterruptedException);
        } finally {
            // Clear the flag BEFORE joining: the source under test re-set the probe
            // thread's interrupt status before throwing, and join() is interruptible
            // — joining with the flag still set threw InterruptedException out of
            // this finally whenever the interrupter had not fully exited yet (the
            // load-dependent half of this flake). Clearing first makes the cleanup
            // deterministic; the join afterwards only reaps the interrupter.
            Thread.interrupted();
            interrupter.join(2_000);
        }
    }

    @Test
    public void observerSeesEveryWalkPhaseInOrder() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        primary.shouldFail = true;
        MockAudioSource secondary = new MockAudioSource();
        secondary.shouldFail = true;
        List<FallbackAudioSource.Phase> phases = new ArrayList<>();
        FallbackAudioSource fallback = new FallbackAudioSource(primary, secondary, 2, 0,
                (phase, detail) -> phases.add(phase));

        // Round 1 exhausts both sources; a second openStream call succeeds via the secondary.
        try {
            fallback.openStream();
            fail("round 1 must exhaust");
        } catch (IOException expected) {
            // required
        }
        secondary.shouldFail = false;
        fallback.openStream();
        fallback.close();

        List<FallbackAudioSource.Phase> expected = List.of(
                // Round 1: maxAttempts=2 -> two full probe walks, then exhausted.
                FallbackAudioSource.Phase.PROBING_PRIMARY,
                FallbackAudioSource.Phase.PROBING_SECONDARY,
                FallbackAudioSource.Phase.BACKOFF,
                FallbackAudioSource.Phase.PROBING_PRIMARY,
                FallbackAudioSource.Phase.PROBING_SECONDARY,
                FallbackAudioSource.Phase.BACKOFF,
                FallbackAudioSource.Phase.EXHAUSTED,
                // Round 2: the secondary recovered and carries the stream.
                FallbackAudioSource.Phase.PROBING_PRIMARY,
                FallbackAudioSource.Phase.PROBING_SECONDARY,
                FallbackAudioSource.Phase.SECONDARY_ACTIVE,
                FallbackAudioSource.Phase.CLOSED);
        assertEquals(expected, phases);
    }

    @Test
    public void primaryOnlySkipsSecondaryAndRecoversOnRetry() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        primary.shouldFail = true;
        List<FallbackAudioSource.Phase> phases = new ArrayList<>();
        FallbackAudioSource fallback = FallbackAudioSource.forPrimaryOnly(
                primary, 2, 0, (phase, detail) -> phases.add(phase));

        try {
            fallback.openStream();
            fail("primary-only exhaustion must throw");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Simulated source failure"));
        }
        assertEquals(List.of(
                FallbackAudioSource.Phase.PROBING_PRIMARY,
                FallbackAudioSource.Phase.BACKOFF,
                FallbackAudioSource.Phase.PROBING_PRIMARY,
                FallbackAudioSource.Phase.BACKOFF,
                FallbackAudioSource.Phase.EXHAUSTED), phases);

        primary.shouldFail = false;
        InputStream stream = fallback.openStream();
        assertNotNull(stream);
        assertSame(primary, fallback.getActiveSource());
        assertEquals(FallbackAudioSource.Phase.PRIMARY_ACTIVE, fallback.getPhase());
        fallback.close();
    }

    @Test(timeout = 5_000)
    public void resetConnectionUnblocksOpenStreamBackoffWithoutDeadlock() throws Exception {
        MockAudioSource primary = new MockAudioSource();
        primary.shouldFail = true;
        CountDownLatch backoffEntered = new CountDownLatch(1);
        FallbackAudioSource fallback = FallbackAudioSource.forPrimaryOnly(
                primary, 4, 60_000,
                (phase, detail) -> {
                    if (phase == FallbackAudioSource.Phase.BACKOFF) {
                        backoffEntered.countDown();
                    }
                });

        CountDownLatch openExited = new CountDownLatch(1);
        Thread probe = new Thread(() -> {
            try {
                fallback.openStream();
            } catch (IOException expected) {
                openExited.countDown();
            }
        });
        probe.setDaemon(true);
        probe.start();
        assertTrue("probe must enter BACKOFF", backoffEntered.await(2, TimeUnit.SECONDS));

        // Must return immediately without waiting for the 60s backoff sleep.
        fallback.resetConnection();
        assertTrue("openStream must exit promptly after resetConnection", openExited.await(2, TimeUnit.SECONDS));
        probe.join(2_000);
        fallback.close();
    }
}

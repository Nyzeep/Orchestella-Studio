package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.UmlKeepAliveSession.Effects;
import io.github.nyzeep.orchestella.UmlKeepAliveSession.Ticker;
import io.github.nyzeep.orchestella.UmlKeepAliveSession.UmlWakeLock;
import io.github.nyzeep.orchestella.UmlKeepAliveSession.WakeLockFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.Before;
import org.junit.Test;

/**
 * The execution-order contract of {@link UmlKeepAliveSession} — the named
 * keeper of the grant lifecycle that used to live in the
 * {@code UmlFaceKeepAliveService.keepAlive} anonymous closure. All
 * JVM: a manual clock drives the monotonic seam, a fake lock drives the
 * wake-lock seam, recording fakes drive the effects and the ticker, so the
 * honest-stop ordering and the idempotence guards are pinned without an
 * Android runtime. The decision half is {@link WakeLockPolicy} (locked
 * separately); these tests lock that the session executes it exactly:
 * HOLD leaves the grant, RENEW re-takes a clean grant and spends budget, a
 * lost grant is re-taken, the ceiling stops the session honestly — notice
 * BEFORE service stop — and a failed tick neither kills the schedule nor
 * stays silent.
 */
public final class UmlKeepAliveSessionTest {

    private static final long T0 = 1_000_000L;
    private static final long MINUTE_MS = 60L * 1000;
    /** Inside the renew margin (elapsed 90min of a 2h grant, 30min left). */
    private static final long RENEW_STEP_MS = 90L * MINUTE_MS;

    private ManualClock clock;
    private FakeLock lock;
    private FakeLockFactory locks;
    private RecordingEffects effects;
    private ManualTicker ticker;
    private UmlKeepAliveSession session;

    @Before
    public void setUp() {
        List<String> events = new ArrayList<>();
        clock = new ManualClock();
        clock.nowMs = T0;
        lock = new FakeLock(events);
        locks = new FakeLockFactory(lock);
        effects = new RecordingEffects(events);
        ticker = new ManualTicker(events);
        session = new UmlKeepAliveSession(clock, locks, effects, ticker);
    }

    // --- fakes ------------------------------------------------------------------

    /** Manual monotonic clock standing in for {@code SystemClock.elapsedRealtime}. */
    private static final class ManualClock implements LongSupplier {
        long nowMs;

        @Override
        public long getAsLong() {
            return nowMs;
        }
    }

    /** Lock stand-in with explicit platform-side drop and one-shot failure control. */
    private static final class FakeLock implements UmlWakeLock {
        private final List<String> events;
        boolean held;
        int acquireCalls;
        int releaseCalls;
        long lastAcquireTimeoutMs = -1;
        private RuntimeException nextIsHeldFailure;

        FakeLock(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean isHeld() {
            if (nextIsHeldFailure != null) {
                RuntimeException failure = nextIsHeldFailure;
                nextIsHeldFailure = null;
                throw failure;
            }
            return held;
        }

        @Override
        public void acquire(long timeoutMs) {
            acquireCalls++;
            lastAcquireTimeoutMs = timeoutMs;
            held = true;
        }

        @Override
        public void release() {
            releaseCalls++;
            held = false;
            events.add("lock-released");
        }

        /** The platform took the grant away without the session's knowledge. */
        void dropExternally() {
            held = false;
        }

        void failNextIsHeld(RuntimeException failure) {
            nextIsHeldFailure = failure;
        }
    }

    /** Answers the shared lock, or {@code null} when "PowerManager" is gone. */
    private static final class FakeLockFactory implements WakeLockFactory {
        private final FakeLock lock;
        boolean unavailable;
        int createCalls;

        FakeLockFactory(FakeLock lock) {
            this.lock = lock;
        }

        @Override
        public UmlWakeLock create() {
            createCalls++;
            return unavailable ? null : lock;
        }
    }

    /** Records every external effect in call order. */
    private static final class RecordingEffects implements Effects {
        private final List<String> events;
        int raiseCalls;
        int noticeCalls;
        int stopServiceCalls;
        final List<RuntimeException> tickFailures = new ArrayList<>();

        RecordingEffects(List<String> events) {
            this.events = events;
        }

        @Override
        public void raiseForegroundService() {
            raiseCalls++;
            events.add("raise");
        }

        @Override
        public void postStopNotice() {
            noticeCalls++;
            events.add("notice");
        }

        @Override
        public void stopForegroundService() {
            stopServiceCalls++;
            events.add("stop-service");
        }

        @Override
        public void reportTickFailure(RuntimeException failure) {
            tickFailures.add(failure);
        }
    }

    /**
     * Manual ticker: {@link #fire} delivers one tick, {@link #fireOnStop}
     * simulates a lingering in-flight tick racing the honest stop (the exact
     * contention the {@code stoppedHonestly} guard exists for).
     */
    private static final class ManualTicker implements Ticker {
        private final List<String> events;
        int startCalls;
        int stopCalls;
        boolean fireOnStop;
        private Runnable tick;

        ManualTicker(List<String> events) {
            this.events = events;
        }

        @Override
        public void start(Runnable tickRunnable) {
            startCalls++;
            tick = tickRunnable;
        }

        @Override
        public void stop() {
            stopCalls++;
            events.add("ticker-stopped");
            if (fireOnStop && tick != null) {
                tick.run();
            }
        }

        void fire() {
            tick.run();
        }
    }

    // --- acquire ----------------------------------------------------------------

    @Test
    public void acquireRaisesTheServiceTakesABoundedGrantAndStartsTheTicker() {
        session.acquire();
        assertEquals(1, effects.raiseCalls);
        assertEquals(1, locks.createCalls);
        assertEquals(1, lock.acquireCalls);
        assertEquals(WakeLockPolicy.UML_GRANT_TIMEOUT_MS, lock.lastAcquireTimeoutMs);
        assertEquals(1, ticker.startCalls);
        assertTrue(lock.held);
    }

    @Test
    public void repeatedAcquireTakesAFreshGrantWithoutRestartingTheRunningTicker() {
        session.acquire();
        clock.nowMs = T0 + 5 * MINUTE_MS;
        session.acquire();
        // The ticker stays (idempotent start); the grant is re-taken fresh.
        assertEquals(1, ticker.startCalls);
        assertEquals(2, lock.acquireCalls);
        assertEquals(2, effects.raiseCalls);
        assertEquals(0, effects.noticeCalls);
    }

    // --- HOLD / RENEW ------------------------------------------------------------

    @Test
    public void holdBelowTheRenewalThresholdLeavesTheGrantAlone() {
        session.acquire();
        clock.nowMs = T0 + 10 * MINUTE_MS; // 110 minutes left: well above the margin
        ticker.fire();
        assertEquals(1, lock.acquireCalls);
        assertEquals(0, lock.releaseCalls);
        assertEquals(0, effects.noticeCalls);
        assertEquals(0, effects.stopServiceCalls);
        assertEquals(1, ticker.startCalls);
    }

    @Test
    public void renewInsideTheMarginRetakesACleanGrantAndResetsTheGrantClock() {
        session.acquire();
        clock.nowMs = T0 + RENEW_STEP_MS; // 90 minutes in, 30 left: below the margin
        ticker.fire();
        assertEquals(2, lock.acquireCalls);
        assertEquals(1, lock.releaseCalls); // the clean-grant release before re-take
        assertTrue(lock.held);
        // 30 minutes after the renewal: still inside the fresh grant. If the
        // grant clock had not been reset to the renewal time, this tick would
        // see an expired grant and renew again.
        clock.nowMs = T0 + RENEW_STEP_MS + 30 * MINUTE_MS;
        ticker.fire();
        assertEquals("the grant clock must track the latest acquire",
                2, lock.acquireCalls);
        assertEquals(0, effects.noticeCalls);
        assertEquals(0, effects.stopServiceCalls);
    }

    @Test
    public void lostGrantReadsNotHeldAndIsRetakenByTheNextTick() {
        session.acquire();
        lock.dropExternally(); // the platform took the grant away
        clock.nowMs = T0 + 1 * MINUTE_MS;
        ticker.fire();
        // The session never pretends protection it lacks: re-take immediately.
        assertEquals(2, lock.acquireCalls);
        assertTrue(lock.held);
        assertEquals(0, effects.noticeCalls);
        assertEquals(0, effects.stopServiceCalls);
        // A second loss is re-taken too — the re-take path is not one-shot.
        lock.dropExternally();
        ticker.fire();
        assertEquals(3, lock.acquireCalls);
    }

    // --- the honest ceiling -------------------------------------------------------

    @Test
    public void stopAtTheCeilingPostsTheNoticeBeforeStoppingTheService() {
        session.acquire();
        for (int i = 1; i <= WakeLockPolicy.UML_MAX_RENEWALS; i++) {
            clock.nowMs = T0 + RENEW_STEP_MS * i;
            ticker.fire();
        }
        assertEquals(WakeLockPolicy.UML_MAX_RENEWALS + 1, lock.acquireCalls);
        assertEquals(0, effects.noticeCalls);
        clock.nowMs = T0 + RENEW_STEP_MS * (WakeLockPolicy.UML_MAX_RENEWALS + 1);
        ticker.fire(); // the 13th renewal: the 24h ceiling — STOP
        assertEquals(java.util.Arrays.asList(
                        "ticker-stopped", "lock-released", "notice", "stop-service"),
                effects.events.subList(
                        effects.events.indexOf("ticker-stopped"),
                        effects.events.indexOf("stop-service") + 1));
        assertEquals("exactly one honest notice", 1, effects.noticeCalls);
        assertEquals(1, effects.stopServiceCalls);
        assertFalse("the ceiling ends with no lock held", lock.held);
        assertEquals(WakeLockPolicy.UML_MAX_RENEWALS + 1, lock.releaseCalls);
    }

    @Test
    public void anInFlightTickRacingTheHonestStopCannotDoublePostTheNotice() {
        session.acquire();
        for (int i = 1; i <= WakeLockPolicy.UML_MAX_RENEWALS; i++) {
            clock.nowMs = T0 + RENEW_STEP_MS * i;
            ticker.fire();
        }
        ticker.fireOnStop = true; // a second tick already past the running check
        clock.nowMs = T0 + RENEW_STEP_MS * (WakeLockPolicy.UML_MAX_RENEWALS + 1);
        ticker.fire();
        // The nested tick reaches the honest stop while the first one is still
        // inside it: the stoppedHonestly guard must keep one notice, one stop.
        assertEquals(1, effects.noticeCalls);
        assertEquals(1, effects.stopServiceCalls);
        assertFalse(lock.held);
    }

    @Test
    public void aTickAfterTheStopIsANoOp() {
        session.acquire();
        for (int i = 1; i <= WakeLockPolicy.UML_MAX_RENEWALS + 1; i++) {
            clock.nowMs = T0 + RENEW_STEP_MS * i;
            ticker.fire();
        }
        int acquires = lock.acquireCalls;
        ticker.fire(); // an in-flight tick racing the already-completed stop
        assertEquals("the stopped-ticker race guard must hold",
                acquires, lock.acquireCalls);
        assertEquals(1, effects.noticeCalls);
        assertEquals(1, effects.stopServiceCalls);
    }

    @Test
    public void acquireAfterAnHonestStopIsANewExplicitSession() {
        session.acquire();
        for (int i = 1; i <= WakeLockPolicy.UML_MAX_RENEWALS + 1; i++) {
            clock.nowMs = T0 + RENEW_STEP_MS * i;
            ticker.fire();
        }
        assertEquals(1, effects.noticeCalls);
        clock.nowMs += RENEW_STEP_MS;
        session.acquire(); // a new explicit session: budget and honesty reset
        assertEquals("the ticker restarts for the new session", 2, ticker.startCalls);
        int acquiresAfterRestart = lock.acquireCalls;
        // The first tick of the new session renews (budget was reset), it does
        // not stop again immediately.
        clock.nowMs += RENEW_STEP_MS;
        ticker.fire();
        assertEquals(acquiresAfterRestart + 1, lock.acquireCalls);
        assertEquals(1, effects.noticeCalls);
        // The new session runs its full budget, then stops honestly again.
        for (int i = 2; i <= WakeLockPolicy.UML_MAX_RENEWALS + 1; i++) {
            clock.nowMs += RENEW_STEP_MS;
            ticker.fire();
        }
        assertEquals("the honest notice posts once per session, budget reset",
                2, effects.noticeCalls);
        assertEquals(2, effects.stopServiceCalls);
    }

    // --- tick failures ------------------------------------------------------------

    @Test
    public void aFailedTickIsReportedAndTheScheduleSurvives() {
        session.acquire();
        lock.failNextIsHeld(new RuntimeException("boom"));
        clock.nowMs = T0 + RENEW_STEP_MS;
        ticker.fire(); // must not propagate: the schedule would die with it
        assertEquals("the failure is reported, not silently swallowed",
                1, effects.tickFailures.size());
        assertEquals("boom", effects.tickFailures.get(0).getMessage());
        assertEquals("the failed tick executed no policy action",
                1, lock.acquireCalls);
        // The next tick still runs the full decision path: the schedule lived.
        clock.nowMs = T0 + RENEW_STEP_MS + 1;
        ticker.fire();
        assertEquals(2, lock.acquireCalls);
        assertEquals(0, effects.noticeCalls);
    }

    // --- release ------------------------------------------------------------------

    @Test
    public void releaseStopsEverythingWithoutANotice() {
        session.acquire();
        clock.nowMs = T0 + RENEW_STEP_MS;
        ticker.fire(); // one renewal so the release path has state to tear down
        effects.events.clear();
        session.release();
        assertEquals(java.util.Arrays.asList(
                "ticker-stopped", "lock-released", "stop-service"), effects.events);
        assertEquals("the normal release claims nothing — deliberately no notice",
                0, effects.noticeCalls);
        assertFalse(lock.held);
        assertEquals(1, ticker.stopCalls);
    }

    @Test
    public void releaseBeforeAnyAcquireStopsQuietlyWithoutTakingAGrant() {
        session.release();
        assertEquals(0, locks.createCalls);
        assertEquals(0, lock.acquireCalls);
        assertEquals(0, effects.noticeCalls);
        assertEquals(1, effects.stopServiceCalls);
    }

    @Test
    public void releaseAfterAnHonestStopNeverPostsANotice() {
        session.acquire();
        for (int i = 1; i <= WakeLockPolicy.UML_MAX_RENEWALS + 1; i++) {
            clock.nowMs = T0 + RENEW_STEP_MS * i;
            ticker.fire();
        }
        assertEquals(1, effects.noticeCalls);
        session.release();
        assertEquals("release's stop-service is unconditional, its notice never fires",
                1, effects.noticeCalls);
        assertEquals(2, effects.stopServiceCalls);
    }

    // --- degraded platform ----------------------------------------------------------

    @Test
    public void unavailablePowerManagerMeansNoGrantButAnHonestSessionStillRuns() {
        locks.unavailable = true; // the historical PowerManager-null early exit
        session.acquire();
        assertEquals(1, locks.createCalls);
        assertEquals("no lock object, no grant — and no crash", 0, lock.acquireCalls);
        clock.nowMs = T0 + RENEW_STEP_MS;
        ticker.fire(); // RENEW with no lock to re-take: the early exit, no crash
        assertEquals(0, lock.acquireCalls);
        for (int i = 2; i <= WakeLockPolicy.UML_MAX_RENEWALS + 1; i++) {
            clock.nowMs = T0 + RENEW_STEP_MS * i;
            ticker.fire();
        }
        assertEquals("honesty does not depend on lock possession",
                1, effects.noticeCalls);
        assertEquals(1, effects.stopServiceCalls);
    }
}

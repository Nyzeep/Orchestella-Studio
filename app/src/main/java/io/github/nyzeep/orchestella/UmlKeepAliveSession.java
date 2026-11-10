package io.github.nyzeep.orchestella;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The execution-order half of the UML compile-worker face's keep-alive: the
 * grant lifecycle that lived in the {@code UmlFaceKeepAliveService.keepAlive}
 * anonymous closure, extracted into a named, constructible object so the
 * service keeps only its Android glue (onCreate channel, onStartCommand
 * startForeground) while this class owns the ticker lifecycle, the grant
 * bookkeeping, the idempotence guards and the honest shutdown. Every external
 * touch is an injected seam, so the whole execution order is JVM-testable
 * against fakes (no Android runtime, no framework).
 *
 * <p><b>ADR-0011 — the judgement this code executes.</b> The foreground
 * service + wake lock pair is the only proven anti-freeze protection on the
 * OEM skins (the 2026-11-07 device round, §7.1.6: without it the whole
 * process group — app and UML child together — is cgroup-frozen within
 * minutes of the screen dozing; with it, 60 minutes of zero-freeze
 * residency). The protection is the platform-reliable half; everything past
 * it is best-effort and honest: grants are bounded
 * ({@link WakeLockPolicy#UML_GRANT_TIMEOUT_MS}), renewed by policy
 * ({@link WakeLockPolicy#decideSession}) at most
 * {@link WakeLockPolicy#UML_MAX_RENEWALS} times per session — the 24h
 * residency ceiling — and past the ceiling the session ends honestly: lock
 * released, ticker stopped, notice posted, service stopped. It never
 * promises 24h unattended residency, and it never leaves a lapsed lock under
 * a notification that still claims a running worker. A workload that must
 * outlive the ceiling is a new explicit {@link #acquire}, never a silent
 * forever-lock.
 *
 * <p><b>Seams</b> (the Android-free test surface):
 * <ul>
 *   <li>{@code clock} — monotonic now; {@code SystemClock.elapsedRealtime()}
 *       in production. Every grant timestamp lives in its domain.</li>
 *   <li>{@link WakeLockFactory} / {@link UmlWakeLock} — the narrow lock
 *       surface; the production factory adapts {@code PowerManager} and
 *       answers {@code null} when it is unavailable (the historical
 *       early-exit: no grant, no crash).</li>
 *   <li>{@link Effects} — the external effects: raising and stopping the
 *       foreground service, the honest-stop notice, and tick-failure
 *       diagnostics; the production adapter lives in the service glue.</li>
 *   <li>{@link Ticker} — the renewal scheduling seam; the production
 *       {@link ScheduledTicker} is the proven single daemon thread.</li>
 * </ul>
 *
 * <p>The decision logic is {@link WakeLockPolicy} (pure, JVM-tested); this
 * class only executes the answer on the real lock — the decision half and
 * the execution half stay decoupled.
 */
public final class UmlKeepAliveSession implements UmlFaceLauncher.KeepAlive {

    /** The narrow platform-lock surface the session drives. */
    public interface UmlWakeLock {
        boolean isHeld();

        /** A bounded grant — the platform deadline tracks the latest acquire. */
        void acquire(long timeoutMs);

        void release();
    }

    /**
     * The lock factory seam. The production adapter resolves
     * {@code PowerManager} and adapts the created lock to the
     * reference-counted-off shape; answering {@code null} means no grant is
     * possible and the session simply runs unprotected-and-honest.
     */
    public interface WakeLockFactory {
        UmlWakeLock create();
    }

    /**
     * The external effects the service glue adapts. The session only invokes
     * them; none of them runs under the session monitor except where the
     * original closure ran it (the honest shutdown's notification and service
     * stop run outside it).
     */
    public interface Effects {
        /** Raise the foreground service — the acquire path's first effect. */
        void raiseForegroundService();

        /**
         * Post the honest-stop notice (the separate STOP_NOTICE_ID, so the
         * service's stop cannot retract it). The honest shutdown posts this
         * BEFORE stopping the service.
         */
        void postStopNotice();

        /** Stop the foreground service (the honest-stop and release paths). */
        void stopForegroundService();

        /**
         * Report a failed tick. A failed tick must not kill the scheduled
         * task (the schedule would silently stop renewing forever), and the
         * failure must not be invisible either — a silent catch leaves only
         * the symptom (the TerminalActivity ready-wait lesson). The
         * production adapter logs one warning line; the session guarantees
         * the schedule survives.
         */
        void reportTickFailure(RuntimeException failure);
    }

    /** The renewal scheduling seam. */
    public interface Ticker {
        /**
         * Starts delivering {@code tick} periodically; idempotent per running
         * period (the session additionally guards its own running flag).
         */
        void start(Runnable tick);

        /**
         * Stops the schedule. An in-flight tick may still complete — the
         * session's guards own that race.
         */
        void stop();
    }

    /**
     * The production ticker: the proven single daemon thread
     * ("uml-face-wakelock-renewal") polling on the policy's fixed check
     * interval. A fresh executor per start; {@link #stop} shuts it down now.
     */
    public static final class ScheduledTicker implements Ticker {
        private ScheduledExecutorService executor;

        @Override
        public void start(Runnable tick) {
            if (executor != null) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "uml-face-wakelock-renewal");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(
                    tick,
                    WakeLockPolicy.UML_CHECK_INTERVAL_MS,
                    WakeLockPolicy.UML_CHECK_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        }

        @Override
        public void stop() {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    private final Object monitor = new Object();
    private final LongSupplier clock;
    private final WakeLockFactory wakeLocks;
    private final Effects effects;
    private final Ticker ticker;

    private UmlWakeLock wakeLock;
    private long grantStartedMs = WakeLockPolicy.NOT_HELD;
    private int renewalsDone;
    private boolean stoppedHonestly;
    private boolean tickerRunning;

    /**
     * @param clock monotonic now ({@code SystemClock.elapsedRealtime()} in
     *        production); the grant timestamps live in its domain
     * @param wakeLocks the lock factory; {@code null} answers mean the
     *        platform offers no lock and the session runs honestly without one
     * @param effects the raise/notice/stop/diagnostics effects
     * @param ticker the renewal schedule
     */
    public UmlKeepAliveSession(
            LongSupplier clock, WakeLockFactory wakeLocks, Effects effects, Ticker ticker) {
        this.clock = clock;
        this.wakeLocks = wakeLocks;
        this.effects = effects;
        this.ticker = ticker;
    }

    /**
     * Starts (or restarts) a session: raise the foreground service first —
     * the wake lock is worthless without it (ADR-0011) — then a fresh grant
     * and the renewal ticker. A re-acquire resets the renewal budget and the
     * honest-stop flag: it is a new explicit session, never a continuation.
     */
    @Override
    public void acquire() {
        effects.raiseForegroundService();
        synchronized (monitor) {
            renewalsDone = 0;
            stoppedHonestly = false;
            reTakeGrantLocked(clock.getAsLong());
            startRenewalTickerLocked();
        }
    }

    /** Caller holds {@code monitor}. */
    private void reTakeGrantLocked(long nowMs) {
        if (wakeLock == null) {
            wakeLock = wakeLocks.create();
        }
        if (wakeLock == null) {
            // No PowerManager, no grant: the historical early-exit.
            return;
        }
        if (wakeLock.isHeld()) {
            // Renewal and post-expiry re-take both start from a clean
            // grant (the guest-execution service's proven pattern), so
            // the platform deadline tracks the latest acquire.
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // The re-take must not be masked by a stale release.
            }
        }
        wakeLock.acquire(WakeLockPolicy.UML_GRANT_TIMEOUT_MS);
        grantStartedMs = nowMs;
    }

    /** Caller holds {@code monitor}. Idempotent while the ticker runs. */
    private void startRenewalTickerLocked() {
        if (tickerRunning) {
            return;
        }
        tickerRunning = true;
        ticker.start(this::tick);
    }

    /** Caller holds {@code monitor}. */
    private void stopTickerLocked() {
        if (tickerRunning) {
            ticker.stop();
            tickerRunning = false;
        }
    }

    /**
     * One policy poll: HOLD keeps the live grant, RENEW re-takes a clean
     * grant and spends budget, STOP ends the session honestly. A lost grant
     * reads {@link WakeLockPolicy#NOT_HELD} and is re-taken (and counted) —
     * the session never pretends protection it does not have.
     */
    private void tick() {
        try {
            WakeLockPolicy.UmlSessionDecision decision;
            synchronized (monitor) {
                if (!tickerRunning) {
                    // The stop already happened: an in-flight tick must not
                    // resurrect anything (the stop-ticker race guard).
                    return;
                }
                long nowMs = clock.getAsLong();
                long heldSinceMs =
                        wakeLock != null && wakeLock.isHeld()
                                ? grantStartedMs
                                : WakeLockPolicy.NOT_HELD;
                decision = WakeLockPolicy.decideSession(nowMs, heldSinceMs, renewalsDone);
                if (decision == WakeLockPolicy.UmlSessionDecision.HOLD) {
                    return;
                }
                if (decision == WakeLockPolicy.UmlSessionDecision.RENEW) {
                    reTakeGrantLocked(nowMs);
                    renewalsDone++;
                    return;
                }
            }
            // STOP: outside the monitor — the honest shutdown.
            stopHonestly();
        } catch (RuntimeException tickFailure) {
            // A failed tick must not kill the scheduled task: the schedule
            // would silently stop renewing forever. Reported, not silent.
            effects.reportTickFailure(tickFailure);
        }
    }

    /**
     * The honest shutdown, idempotent (two racing STOP ticks must not
     * double-post the notice): stop the ticker and release the grant under
     * the monitor, then — outside it — post the notice BEFORE stopping the
     * service. The notice is a separate id from the foreground notification
     * exactly so the service's stop cannot retract it; the face is stopped
     * by that point and the notice says so instead of leaving a residency
     * notification that claims a running worker.
     */
    private void stopHonestly() {
        synchronized (monitor) {
            if (stoppedHonestly) {
                return;
            }
            stoppedHonestly = true;
            stopTickerLocked();
            releaseGrantLocked();
        }
        effects.postStopNotice();
        effects.stopForegroundService();
    }

    /** Caller holds {@code monitor}. */
    private void releaseGrantLocked() {
        grantStartedMs = WakeLockPolicy.NOT_HELD;
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // Release must not mask the face lifecycle result.
            }
            wakeLock = null;
        }
    }

    /**
     * The caller's normal release: stop the ticker and the grant, stop the
     * service — deliberately NO notice. The kernel is simply gone; only the
     * budget-exhausted honest stop claims a stopped worker, and conflating
     * the two would make every normal stop look like a failure.
     */
    @Override
    public void release() {
        synchronized (monitor) {
            stopTickerLocked();
            releaseGrantLocked();
        }
        effects.stopForegroundService();
    }
}

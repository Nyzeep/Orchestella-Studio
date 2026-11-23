package io.github.nyzeep.orchestella.runtime.engineuml;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single runtime owner for one standalone UML process, its PTY and Bridge channels,
 * ephemeral root/temp resources, and its root-volume reservation.
 *
 * <p>Strictly enforces {@code stop -> reap -> release}: channels, temporary assets,
 * and the root-volume reservation are never released until process death is
 * actually confirmed. Unconfirmed stops stay retryable and retain the root
 * reservation so concurrent or premature restarts cannot race on the same root.
 */
public final class UmlRuntimeSupervisor {
    public static final long DEFAULT_FORCE_WAIT_MS = 2_000L;
    public static final long DEFAULT_CHANNELS_CLOSE_TIMEOUT_MS = 1_000L;

    public enum StopResult { CONFIRMED, UNCONFIRMED, ALREADY_STOPPED }

    /** Observation-only lifecycle facts emitted during {@code stop -> reap -> release}. */
    public interface StopObserver {
        void onProcessReaped(long pid, int exitCode);

        void onChannelsReleased(boolean released);

        void onResourcesReleased(boolean released);

        void onRootReleased(boolean released);
    }

    /** Immutable evidence record summarizing the latest stop/reap/release state. */
    public record StopEvidence(
            StopResult result,
            String stage,
            long pid,
            int exitCode,
            boolean reaped,
            boolean forced,
            boolean channelsReleased,
            boolean resourcesReleased,
            boolean rootReleased) {
        public StopEvidence {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(stage, "stage");
        }
    }

    private final UmlProcessHandle process;
    private final Runnable closeBridge;
    private final Runnable releaseEphemeralResources;
    private final UmlRootReservationRegistry reservationRegistry;
    private final UmlRootReservationRegistry.Reservation rootReservation;
    private final long forceWaitMs;
    private final long channelsCloseTimeoutMs;
    private final StopObserver observer;

    private final Object lock = new Object();
    private final AtomicBoolean eofObserved = new AtomicBoolean(false);
    private final AtomicBoolean channelsClosed = new AtomicBoolean(false);
    private final AtomicBoolean resourcesReleased = new AtomicBoolean(false);
    private final AtomicBoolean rootReleased = new AtomicBoolean(false);
    private final AtomicReference<Thread> channelsWorker = new AtomicReference<>();

    private StopEvidence lastEvidence;
    private boolean reaped;
    private int reapedExitCode = -1;
    private boolean forcedStopUsed;

    public UmlRuntimeSupervisor(
            UmlProcessHandle process,
            Runnable closeBridge,
            Runnable releaseEphemeralResources,
            UmlRootReservationRegistry reservationRegistry,
            UmlRootReservationRegistry.Reservation rootReservation,
            long forceWaitMs,
            long channelsCloseTimeoutMs,
            StopObserver observer) {
        this.process = Objects.requireNonNull(process, "process");
        this.closeBridge = Objects.requireNonNull(closeBridge, "closeBridge");
        this.releaseEphemeralResources =
                Objects.requireNonNull(releaseEphemeralResources, "releaseEphemeralResources");
        this.reservationRegistry = reservationRegistry;
        this.rootReservation = rootReservation;
        this.forceWaitMs = Math.max(0L, forceWaitMs);
        this.channelsCloseTimeoutMs = Math.max(0L, channelsCloseTimeoutMs);
        this.observer = observer;
    }

    /**
     * Coalesced bounded stop enforcing {@code stop -> reap -> release}.
     * Concurrent callers share the single supervisor lock and observe the same
     * confirmed outcome; unconfirmed stops remain retryable.
     */
    public StopEvidence stop(long graceMs) {
        long boundedGrace = Math.max(0L, graceMs);
        synchronized (lock) {
            if (lastEvidence != null
                    && (lastEvidence.result() == StopResult.CONFIRMED
                            || lastEvidence.result() == StopResult.ALREADY_STOPPED)) {
                return lastEvidence;
            }

            boolean wasAliveAtEntry = process.isAlive();
            if (!reaped) {
                if (wasAliveAtEntry) {
                    try {
                        process.destroy();
                    } catch (RuntimeException failure) {
                        lastEvidence = new StopEvidence(
                                StopResult.UNCONFIRMED,
                                "destroy",
                                process.pid(),
                                -1,
                                false,
                                forcedStopUsed,
                                channelsClosed.get(),
                                resourcesReleased.get(),
                                rootReleased.get());
                        return lastEvidence;
                    }
                    if (!awaitExit(boundedGrace)) {
                        forcedStopUsed = true;
                        try {
                            process.destroyForcibly();
                        } catch (RuntimeException failure) {
                            lastEvidence = new StopEvidence(
                                    StopResult.UNCONFIRMED,
                                    "force",
                                    process.pid(),
                                    -1,
                                    false,
                                    true,
                                    channelsClosed.get(),
                                    resourcesReleased.get(),
                                    rootReleased.get());
                            return lastEvidence;
                        }
                        if (!awaitExit(forceWaitMs)) {
                            boolean channelsOk = closeChannelsOnce();
                            observeChannels(channelsOk);
                            lastEvidence = new StopEvidence(
                                    StopResult.UNCONFIRMED,
                                    "force-wait",
                                    process.pid(),
                                    -1,
                                    false,
                                    true,
                                    channelsOk,
                                    false,
                                    false);
                            return lastEvidence;
                        }
                    }
                }
                reaped = true;
                reapedExitCode = readExitCode();
                observeProcessReaped(process.pid(), reapedExitCode);
            }

            boolean channelsOk = closeChannelsOnce();
            observeChannels(channelsOk);
            if (!channelsOk) {
                lastEvidence = new StopEvidence(
                        StopResult.UNCONFIRMED,
                        "channels",
                        process.pid(),
                        reapedExitCode,
                        true,
                        forcedStopUsed,
                        false,
                        resourcesReleased.get(),
                        false);
                return lastEvidence;
            }

            boolean resourcesOk = releaseResourcesOnce();
            observeResources(resourcesOk);
            if (!resourcesOk) {
                lastEvidence = new StopEvidence(
                        StopResult.UNCONFIRMED,
                        "resources",
                        process.pid(),
                        reapedExitCode,
                        true,
                        forcedStopUsed,
                        true,
                        false,
                        false);
                return lastEvidence;
            }

            boolean rootOk = releaseRootOnce();
            observeRoot(rootOk);

            StopResult finalResult =
                    (wasAliveAtEntry || forcedStopUsed)
                            ? StopResult.CONFIRMED
                            : StopResult.ALREADY_STOPPED;
            String stage = finalResult == StopResult.CONFIRMED ? "confirmed" : "already-stopped";
            lastEvidence = new StopEvidence(
                    finalResult,
                    stage,
                    process.pid(),
                    reapedExitCode,
                    true,
                    forcedStopUsed,
                    true,
                    true,
                    rootOk);
            return lastEvidence;
        }
    }

    /** Coalesces launch-time cancellation onto the same supervisor stop path. */
    public StopEvidence cancelLaunch(long graceMs) {
        return stop(graceMs);
    }

    /** Coalesces exit-watcher teardown onto the same supervisor stop path. */
    public StopEvidence stopFromWatcher(long graceMs) {
        return stop(graceMs);
    }

    /**
     * Records terminal EOF. EOF alone never kills or releases a still-alive
     * process; only if the process has already exited does it trigger reap+release.
     */
    public StopEvidence signalTerminalEof() {
        eofObserved.set(true);
        if (process.isAlive()) {
            return null;
        }
        return stop(0L);
    }

    public boolean wasEofObserved() {
        return eofObserved.get();
    }

    public boolean isRuntimeAlive() {
        return process.isAlive() || (rootReservation != null && !rootReservation.isReleased());
    }

    public StopEvidence lastEvidence() {
        synchronized (lock) {
            return lastEvidence;
        }
    }

    private boolean awaitExit(long timeoutMs) {
        try {
            boolean exited = process.waitFor(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
            return exited || !process.isAlive();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    private int readExitCode() {
        try {
            if (!process.waitFor(0L, TimeUnit.MILLISECONDS)) {
                return -1;
            }
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private boolean closeChannelsOnce() {
        if (channelsClosed.get()) {
            return true;
        }
        Thread worker = channelsWorker.get();
        if (worker == null || !worker.isAlive()) {
            Thread next = new Thread(() -> {
                try {
                    closeBridge.run();
                } catch (RuntimeException ignored) {
                    return;
                }
                try {
                    process.closeChannels();
                    channelsClosed.set(true);
                } catch (RuntimeException ignored) {
                    // Retryable on next stop attempt.
                }
            }, "uml-channels-close");
            next.setDaemon(true);
            if (channelsWorker.compareAndSet(worker, next)) {
                worker = next;
                worker.start();
            } else {
                worker = channelsWorker.get();
            }
        }
        if (worker == null) {
            return channelsClosed.get();
        }
        if (channelsCloseTimeoutMs > 0L) {
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(channelsCloseTimeoutMs);
            while (worker.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (worker.isAlive()) {
            if (channelsCloseTimeoutMs > 0L) {
                worker.interrupt();
            }
            return false;
        }
        return channelsClosed.get();
    }

    private boolean releaseResourcesOnce() {
        if (resourcesReleased.get()) {
            return true;
        }
        try {
            releaseEphemeralResources.run();
            resourcesReleased.set(true);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean releaseRootOnce() {
        if (rootReleased.get()) {
            return true;
        }
        if (reservationRegistry != null && rootReservation != null) {
            reservationRegistry.release(rootReservation);
        }
        rootReleased.set(true);
        return true;
    }

    private void observeProcessReaped(long pid, int exitCode) {
        if (observer == null) {
            return;
        }
        try {
            observer.onProcessReaped(pid, exitCode);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void observeChannels(boolean released) {
        if (observer == null) {
            return;
        }
        try {
            observer.onChannelsReleased(released);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void observeResources(boolean released) {
        if (observer == null) {
            return;
        }
        try {
            observer.onResourcesReleased(released);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void observeRoot(boolean released) {
        if (observer == null) {
            return;
        }
        try {
            observer.onRootReleased(released);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}

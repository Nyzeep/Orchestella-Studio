package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudgetLedger;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEvent;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectCode;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectedException;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStarter;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneState;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;
import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AX-01: the single admission and stop owner for the QEMU and accelerator
 * lanes.
 *
 * <p>Both start requests are issued back-to-back before any readiness wait.
 * QEMU is the primary lane: an accelerator admission, start or runtime
 * failure degrades to {@code ACCEL_LOST}/{@code DISABLED} and never stops
 * QEMU. Stops always try QEMU first and then the accelerator, even when the
 * QEMU stop is unconfirmed or throws. An {@code UNCONFIRMED} stop keeps its
 * lease and its budget reservation.
 *
 * <p>Readiness waits never hold the coordinator lock across a sleep. A
 * concurrent {@link #cancel()} sets the volatile stop flag before it acquires
 * the lock, so a start blocked on readiness can be stopped promptly instead of
 * waiting for the start timeout.
 *
 * <p>This class adds no process stop of its own. Adapters delegate to
 * {@link RuntimeOwnedSession#stopRuntime(long)} or {@code
 * FastLaneSession#stopRuntime(long)}.
 */
public final class DualLaneCoordinator implements Closeable {
    /** Bounded start/stop timing. */
    public record Config(long qemuReadyTimeoutMs, long stopGraceMs) {
        public Config {
            if (qemuReadyTimeoutMs < 0L || stopGraceMs < 0L) {
                throw new IllegalArgumentException("timeouts must be non-negative");
            }
        }
    }

    /** Result of one start attempt. */
    public record StartReport(
            LaneState qemu, LaneState accel, LaneRejectCode rejectCode) {
        public StartReport {
            Objects.requireNonNull(qemu, "qemu");
            Objects.requireNonNull(accel, "accel");
            Objects.requireNonNull(rejectCode, "rejectCode");
        }
    }

    /** Result of one stop attempt, in execution order. */
    public record StopReport(LaneStopOutcome qemu, LaneStopOutcome accel) {
        public StopReport {
            Objects.requireNonNull(qemu, "qemu");
            Objects.requireNonNull(accel, "accel");
        }
    }

    private static final long READY_POLL_MS = 10L;

    private final LaneRequest qemuRequest;
    private final LaneRequest accelRequest;
    private final LaneStarter qemuStarter;
    private final LaneStarter accelStarter;
    private final Config config;
    private final LaneClock clock;
    private final LaneEventSink events;
    private final LaneBudgetLedger ledger;
    private final ReentrantLock lock = new ReentrantLock();

    private LaneRuntime qemuRuntime;
    private LaneRuntime accelRuntime;
    private LaneBudgetLedger.Reservation qemuReservation;
    private LaneBudgetLedger.Reservation accelReservation;
    private LaneState qemuState = LaneState.STOPPED;
    private LaneState accelState = LaneState.STOPPED;
    private boolean qemuLease;
    private boolean accelLease;
    private boolean started;
    private volatile boolean stopRequested;

    public DualLaneCoordinator(
            LaneBudget budget,
            LaneRequest qemuRequest,
            LaneRequest accelRequest,
            LaneStarter qemuStarter,
            LaneStarter accelStarter,
            Config config,
            LaneClock clock,
            LaneEventSink events) {
        this.ledger = new LaneBudgetLedger(Objects.requireNonNull(budget, "budget"));
        this.qemuRequest = Objects.requireNonNull(qemuRequest, "qemuRequest");
        this.accelRequest = Objects.requireNonNull(accelRequest, "accelRequest");
        if (qemuRequest.lane() != LaneId.QEMU || accelRequest.lane() != LaneId.ACCEL) {
            throw new IllegalArgumentException("request lane mismatch");
        }
        this.qemuStarter = Objects.requireNonNull(qemuStarter, "qemuStarter");
        this.accelStarter = Objects.requireNonNull(accelStarter, "accelStarter");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Issues both starts, then waits only for QEMU readiness. */
    public StartReport startAll() {
        LaneRejectCode accelReject = LaneRejectCode.NONE;
        lock.lock();
        try {
            if (started || stopRequested) {
                return report(accelReject);
            }
            started = true;

            try {
                qemuReservation = ledger.reserve(qemuRequest);
            } catch (LaneRejectedException rejected) {
                started = false;
                qemuState = LaneState.STOPPED;
                emit(LaneEvent.BUDGET_REJECTED, LaneId.QEMU, rejected.code().name(), 0L);
                return report(rejected.code());
            }

            try {
                accelReservation = ledger.reserve(accelRequest);
            } catch (LaneRejectedException rejected) {
                accelReject = rejected.code();
                accelState = LaneState.DISABLED;
                emit(LaneEvent.BUDGET_REJECTED, LaneId.ACCEL, rejected.code().name(), 0L);
            }

            long qemuStartedAt = clock.nanoTime();
            try {
                qemuRuntime = qemuStarter.start();
                if (qemuRuntime == null) {
                    throw new LaneStartException("QEMU_START_NULL");
                }
            } catch (LaneStartException | RuntimeException failure) {
                release(qemuReservation);
                qemuReservation = null;
                release(accelReservation);
                accelReservation = null;
                qemuState = LaneState.FAILED;
                accelState = LaneState.DISABLED;
                started = false;
                emit(LaneEvent.LANE_LOST, LaneId.QEMU,
                        startFailureCode(failure, "QEMU_START_FAILED"),
                        elapsed(qemuStartedAt));
                return report(accelReject);
            }
            qemuLease = true;
            qemuState = LaneState.STARTING;
            emit(LaneEvent.LANE_STARTED, LaneId.QEMU, "STARTED", elapsed(qemuStartedAt));

            if (stopRequested) {
                stopAllLocked(config.stopGraceMs());
                return report(accelReject);
            }

            if (accelReservation != null) {
                long accelStartedAt = clock.nanoTime();
                try {
                    accelRuntime = accelStarter.start();
                    if (accelRuntime == null) {
                        throw new LaneStartException("ACCEL_START_NULL");
                    }
                    accelLease = true;
                    accelState = LaneState.STARTING;
                    emit(LaneEvent.LANE_STARTED, LaneId.ACCEL, "STARTED",
                            elapsed(accelStartedAt));
                } catch (LaneStartException | RuntimeException failure) {
                    release(accelReservation);
                    accelReservation = null;
                    accelLease = false;
                    accelState = LaneState.LOST;
                    emit(LaneEvent.LANE_LOST, LaneId.ACCEL,
                            startFailureCode(failure, "ACCEL_START_FAILED"),
                            elapsed(accelStartedAt));
                }
            }

            if (stopRequested) {
                stopAllLocked(config.stopGraceMs());
                return report(accelReject);
            }
        } finally {
            lock.unlock();
        }

        awaitQemuReady();

        lock.lock();
        try {
            if (stopRequested) {
                stopAllLocked(config.stopGraceMs());
            }
            return report(accelReject);
        } finally {
            lock.unlock();
        }
    }

    /** Compile work calls this; a timeout means "build locally". */
    public boolean awaitAcceleratorReady(long timeoutMs) {
        long startedAt = clock.nanoTime();
        long deadline = startedAt + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            long remainingNanos;
            lock.lock();
            try {
                if (stopRequested || !accelLease || accelRuntime == null
                        || terminal(accelState)) {
                    return false;
                }
                if (!accelRuntime.isAlive()) {
                    accelState = LaneState.LOST;
                    emit(LaneEvent.LANE_LOST, LaneId.ACCEL, "ACCEL_LOST",
                            elapsed(startedAt));
                    return false;
                }
                if (accelRuntime.isReady()) {
                    if (stopRequested) {
                        return false;
                    }
                    accelState = LaneState.READY;
                    emit(LaneEvent.LANE_READY, LaneId.ACCEL, "READY", elapsed(startedAt));
                    return true;
                }
                remainingNanos = deadline - clock.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
            } finally {
                lock.unlock();
            }
            sleep(Math.max(1L, Math.min(READY_POLL_MS, remainingNanos / 1_000_000L)));
        }
    }

    /** QEMU first, accelerator second; never the reverse. */
    public StopReport stopAll(long graceMs) {
        lock.lock();
        try {
            return stopAllLocked(graceMs);
        } finally {
            lock.unlock();
        }
    }

    /** Cancels a start in progress or a running pair; idempotent. */
    public void cancel() {
        stopRequested = true;
        lock.lock();
        try {
            if (started || qemuLease || accelLease) {
                stopAllLocked(config.stopGraceMs());
            }
        } finally {
            lock.unlock();
        }
    }

    public LaneState state(LaneId lane) {
        lock.lock();
        try {
            return lane == LaneId.QEMU ? qemuState : accelState;
        } finally {
            lock.unlock();
        }
    }

    public boolean hasLease(LaneId lane) {
        lock.lock();
        try {
            return lane == LaneId.QEMU ? qemuLease : accelLease;
        } finally {
            lock.unlock();
        }
    }

    public LaneBudgetLedger ledger() {
        return ledger;
    }

    /** PL-01 integration: the pool shares this ledger and stays off by default. */
    public ProtectedWarmPool.Admission admission() {
        return ledger::canAdmitPool;
    }

    @Override
    public void close() {
        stopRequested = true;
        stopAll(config.stopGraceMs());
    }

    private void awaitQemuReady() {
        long startedAt = clock.nanoTime();
        long deadline = startedAt + config.qemuReadyTimeoutMs() * 1_000_000L;
        while (true) {
            long remainingNanos;
            lock.lock();
            try {
                if (stopRequested || !qemuLease || qemuRuntime == null) {
                    return;
                }
                if (!qemuRuntime.isAlive()) {
                    qemuState = LaneState.LOST;
                    emit(LaneEvent.LANE_LOST, LaneId.QEMU, "QEMU_LOST", elapsed(startedAt));
                    stopLane(LaneId.ACCEL, config.stopGraceMs());
                    return;
                }
                if (qemuRuntime.isReady()) {
                    qemuState = LaneState.READY;
                    emit(LaneEvent.LANE_READY, LaneId.QEMU, "READY", elapsed(startedAt));
                    return;
                }
                remainingNanos = deadline - clock.nanoTime();
                if (remainingNanos <= 0L) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            sleep(Math.max(1L, Math.min(READY_POLL_MS, remainingNanos / 1_000_000L)));
        }
    }

    private StopReport stopAllLocked(long graceMs) {
        LaneStopOutcome qemu = stopLane(LaneId.QEMU, graceMs);
        LaneStopOutcome accel = stopLane(LaneId.ACCEL, graceMs);
        started = qemuLease || accelLease;
        return new StopReport(qemu, accel);
    }

    private LaneStopOutcome stopLane(LaneId lane, long graceMs) {
        LaneRuntime runtime = lane == LaneId.QEMU ? qemuRuntime : accelRuntime;
        boolean leased = lane == LaneId.QEMU ? qemuLease : accelLease;
        if (runtime == null || !leased) {
            return LaneStopOutcome.ALREADY_STOPPED;
        }
        setState(lane, LaneState.STOPPING);
        long startedAt = clock.nanoTime();
        LaneStopOutcome outcome;
        try {
            outcome = runtime.stop(Math.max(0L, graceMs));
        } catch (RuntimeException failure) {
            outcome = LaneStopOutcome.UNCONFIRMED;
        }
        if (outcome == null) {
            outcome = LaneStopOutcome.UNCONFIRMED;
        }
        if (outcome == LaneStopOutcome.UNCONFIRMED) {
            setState(lane, LaneState.UNCONFIRMED);
            emit(LaneEvent.LANE_STOPPED, lane, "STOP_UNCONFIRMED", elapsed(startedAt));
            return outcome;
        }
        setState(lane, LaneState.STOPPED);
        releaseLaneReservation(lane);
        setLease(lane, false);
        emit(LaneEvent.LANE_STOPPED, lane, outcome.name(), elapsed(startedAt));
        return outcome;
    }

    private void releaseLaneReservation(LaneId lane) {
        if (lane == LaneId.QEMU) {
            release(qemuReservation);
            qemuReservation = null;
        } else {
            release(accelReservation);
            accelReservation = null;
        }
    }

    private void release(LaneBudgetLedger.Reservation reservation) {
        ledger.release(reservation);
    }

    private void setState(LaneId lane, LaneState state) {
        if (lane == LaneId.QEMU) {
            qemuState = state;
        } else {
            accelState = state;
        }
    }

    private void setLease(LaneId lane, boolean leased) {
        if (lane == LaneId.QEMU) {
            qemuLease = leased;
        } else {
            accelLease = leased;
        }
    }

    private StartReport report(LaneRejectCode accelReject) {
        return new StartReport(qemuState, accelState, accelReject);
    }

    private static boolean terminal(LaneState state) {
        return state == LaneState.DISABLED
                || state == LaneState.LOST
                || state == LaneState.UNCONFIRMED
                || state == LaneState.STOPPED
                || state == LaneState.FAILED;
    }

    private void emit(String name, LaneId lane, String code, long durationMs) {
        events.emit(new LaneEvent(name, lane, code, Math.max(0L, durationMs)));
    }

    private long elapsed(long startedAtNanos) {
        return Math.max(0L, (clock.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    /**
     * The starter's own fixed code when it has one, the lane-level fallback
     * otherwise. A transport failure and a refused capability must not look
     * the same in the event log: the accelerator lane now has distinct codes
     * ({@code PAYLOAD_LOAD_FAILED}, {@code PAYLOAD_UNAVAILABLE_NO_PROTECTED},
     * {@code PAYLOAD_COMPILER_MISMATCH}, ...) that used to collapse into one.
     */
    private static String startFailureCode(Exception failure, String fallback) {
        if (failure instanceof LaneStartException typed) {
            String code = typed.code();
            if (code != null && !code.isBlank()) {
                return code;
            }
        }
        return fallback;
    }

    private void sleep(long millis) {
        try {
            clock.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

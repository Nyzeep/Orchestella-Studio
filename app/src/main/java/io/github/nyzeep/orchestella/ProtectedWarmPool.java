package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneHandle;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1Payloads;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1TerminalCodec;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalFrame;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalFrameType;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalMux;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded warm pool for the protected Fast Lane.
 *
 * <p>Default capacity is zero: the pool refuses every acquire and never starts
 * a VM. With {@code capacity=1} it keeps at most one created-and-ready payload
 * and reuses it across leases. Reuse is never blind: each acquire performs one
 * FLN1 {@code HELLO/HELLO_OK} on the control port with a bounded timeout, and a
 * failed check discards the VM and cold-creates one replacement. A second
 * failure is reported instead of retried forever.
 *
 * <p>{@code HELLO_OK} only proves the control plane is alive. It is not
 * evidence that the terminal works; DV-01A still classifies terminal execution
 * as blocked until the platform gate changes.
 *
 * <p>Stop ownership: this class never stops a platform handle itself. It calls
 * {@link FastLaneSession#stopRuntime(long)}, the single owner stop already used
 * by the lane. {@link RuntimeProcessSupervisor} remains the QEMU stop owner and
 * cannot own a protected Microdroid payload; see the PL-01 implementation
 * report for the deviation.
 *
 * <p>Locking (P1-5): the pool-wide lock guards the slot state machine, but the
 * blocking platform calls of a cold create ({@code api.start}, payload
 * readiness, control HELLO) run with the lock released. The slot stays in
 * {@code CREATING} for the whole create episode, which keeps the create
 * exclusive; {@code state()}/{@code stats()}/{@code release()} stay responsive
 * meanwhile, and stats observed mid-create are weakly consistent.
 *
 * <p>All counters are numbers plus fixed {@link Code} values. No path, token,
 * argv, payload or log line is stored here.
 */
public final class ProtectedWarmPool implements Closeable {
    /** Fixed failure, eviction and audit codes. Never free-form text. */
    public enum Code {
        DISABLED,
        CREATE_FAILED,
        READY_TIMEOUT,
        HELLO_FAILED,
        BUDGET_REJECTED,
        WAIT_TIMEOUT,
        CLOSED,
        TTL_EXPIRED,
        PROCESS_DIED,
        DRAIN_TIMEOUT,
        STOP_UNCONFIRMED,
        /**
         * PVM-06: a warm instance was discarded because the worker pool's
         * injected health probe reported it unhealthy. Never emitted by the
         * pool's own acquire path.
         */
        PROBE_FAILED
    }

    /** States of the single pool slot. */
    public enum State {
        IDLE,
        CREATING,
        READY,
        LEASED,
        DRAINING,
        FAILED
    }

    /** Result of {@link #shutdown(long)}. */
    public enum ShutdownResult {
        DRAINED,
        TIMED_OUT,
        STOP_UNCONFIRMED
    }

    /** Typed, secret-free pool failure. */
    public static final class PoolException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Code code;

        public PoolException(Code code, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.code = Objects.requireNonNull(code, "code");
        }

        public Code code() {
            return code;
        }
    }

    /** Counters only; the map keys are {@link Code} constants. */
    public record Stats(
            long coldCreates,
            long reuses,
            long createMillis,
            long readyMillis,
            long evictions,
            Map<Code, Long> evictReasons) {
        public Stats {
            EnumMap<Code, Long> copy = new EnumMap<>(Code.class);
            copy.putAll(evictReasons);
            evictReasons = Collections.unmodifiableMap(copy);
        }
    }

    /** One checked-out warm VM. Release is idempotent. */
    public static final class Lease {
        private final FastLaneSession session;
        private boolean released;

        private Lease(FastLaneSession session) {
            this.session = session;
        }

        public FastLaneSession session() {
            return session;
        }

        public boolean isReleased() {
            return released;
        }
    }

    /** Builds a fresh launch spec; the generated instance id is unique per create. */
    @FunctionalInterface
    public interface SpecFactory {
        FastLaneLaunchSpec create(String instanceId);
    }

    /**
     * Cross-lane admission seam. AX-01 implements the real QEMU+protected
     * budget; this pool only asks before it starts a VM.
     */
    @FunctionalInterface
    public interface Admission {
        boolean admit(long memoryMb);

        static Admission allowAll() {
            return memoryMb -> true;
        }
    }

    /** Immutable pool configuration. Capacity defaults to zero (off). */
    public static final class Config {
        private final int capacity;
        private final long memoryMb;
        private final long poolMemoryLimitMb;
        private final long idleTtlMs;
        private final long maxWaitMs;
        private final long helloTimeoutMs;
        private final long payloadReadyTimeoutMs;
        private final long drainTimeoutMs;
        private final long stopGraceMs;
        private final String instancePrefix;

        private Config(Builder builder) {
            this.capacity = builder.capacity;
            this.memoryMb = builder.memoryMb;
            this.poolMemoryLimitMb = builder.poolMemoryLimitMb;
            this.idleTtlMs = builder.idleTtlMs;
            this.maxWaitMs = builder.maxWaitMs;
            this.helloTimeoutMs = builder.helloTimeoutMs;
            this.payloadReadyTimeoutMs = builder.payloadReadyTimeoutMs;
            this.drainTimeoutMs = builder.drainTimeoutMs;
            this.stopGraceMs = builder.stopGraceMs;
            this.instancePrefix = builder.instancePrefix;
        }

        public static Config defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public int capacity() { return capacity; }
        public long memoryMb() { return memoryMb; }
        public long poolMemoryLimitMb() { return poolMemoryLimitMb; }
        public long idleTtlMs() { return idleTtlMs; }
        public long maxWaitMs() { return maxWaitMs; }
        public long helloTimeoutMs() { return helloTimeoutMs; }
        public long payloadReadyTimeoutMs() { return payloadReadyTimeoutMs; }
        public long drainTimeoutMs() { return drainTimeoutMs; }
        public long stopGraceMs() { return stopGraceMs; }
        public String instancePrefix() { return instancePrefix; }

        public static final class Builder {
            private int capacity = 0;
            private long memoryMb = ProtectedPoolKnobs.DEFAULT_MEMORY_MB;
            private long poolMemoryLimitMb = ProtectedPoolKnobs.DEFAULT_MEMORY_MB;
            private long idleTtlMs = ProtectedPoolKnobs.DEFAULT_IDLE_TTL_MS;
            private long maxWaitMs = 0L;
            private long helloTimeoutMs = ProtectedPoolKnobs.DEFAULT_HELLO_TIMEOUT_MS;
            private long payloadReadyTimeoutMs = ProtectedPoolKnobs.DEFAULT_PAYLOAD_READY_TIMEOUT_MS;
            private long drainTimeoutMs = ProtectedPoolKnobs.DEFAULT_DRAIN_TIMEOUT_MS;
            private long stopGraceMs = ProtectedPoolKnobs.DEFAULT_STOP_GRACE_MS;
            private String instancePrefix = "flwarm";

            public Builder capacity(int value) { this.capacity = value; return this; }
            public Builder memoryMb(long value) { this.memoryMb = value; return this; }
            public Builder poolMemoryLimitMb(long value) { this.poolMemoryLimitMb = value; return this; }
            public Builder idleTtlMs(long value) { this.idleTtlMs = value; return this; }
            public Builder maxWaitMs(long value) { this.maxWaitMs = value; return this; }
            public Builder helloTimeoutMs(long value) { this.helloTimeoutMs = value; return this; }
            public Builder payloadReadyTimeoutMs(long value) { this.payloadReadyTimeoutMs = value; return this; }
            public Builder drainTimeoutMs(long value) { this.drainTimeoutMs = value; return this; }
            public Builder stopGraceMs(long value) { this.stopGraceMs = value; return this; }
            public Builder instancePrefix(String value) { this.instancePrefix = value; return this; }

            public Config build() {
                if (capacity != 0 && capacity != 1) {
                    throw new IllegalArgumentException("capacity must be 0 or 1");
                }
                ProtectedPoolKnobs.memoryMb(memoryMb, "memoryMb");
                if (poolMemoryLimitMb < 0L) {
                    throw new IllegalArgumentException("poolMemoryLimitMb must not be negative");
                }
                ProtectedPoolKnobs.commonTimeouts(idleTtlMs, helloTimeoutMs,
                        payloadReadyTimeoutMs, drainTimeoutMs, stopGraceMs);
                if (maxWaitMs < 0L) {
                    throw new IllegalArgumentException("timeouts must be non-negative");
                }
                ProtectedPoolKnobs.instancePrefix(instancePrefix);
                return new Config(this);
            }
        }
    }

    private static final long READY_POLL_MS = 10L;

    private final FastLaneVirtualMachineApi api;
    private final SpecFactory specFactory;
    private final Config config;
    private final Clock clock;
    private final Admission admission;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition leaseChanged = lock.newCondition();

    private State state = State.IDLE;
    private FastLaneSession session;
    private Lease currentLease;
    private long readyAtMillis;
    private boolean closed;
    private volatile boolean closeRequested;
    private int instanceSequence;

    private long coldCreates;
    private long reuses;
    private long createMillis;
    private long readyMillis;
    private long evictions;
    private final EnumMap<Code, Long> evictReasons = new EnumMap<>(Code.class);

    public ProtectedWarmPool(
            FastLaneVirtualMachineApi api, SpecFactory specFactory, Config config) {
        this(api, specFactory, config, Clock.systemUTC(), Admission.allowAll());
    }

    public ProtectedWarmPool(
            FastLaneVirtualMachineApi api, SpecFactory specFactory, Config config, Clock clock) {
        this(api, specFactory, config, clock, Admission.allowAll());
    }

    public ProtectedWarmPool(
            FastLaneVirtualMachineApi api,
            SpecFactory specFactory,
            Config config,
            Clock clock,
            Admission admission) {
        this.api = Objects.requireNonNull(api, "api");
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    public State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    public Stats stats() {
        lock.lock();
        try {
            EnumMap<Code, Long> reasons = new EnumMap<>(Code.class);
            reasons.putAll(evictReasons);
            return new Stats(coldCreates, reuses, createMillis, readyMillis, evictions, reasons);
        } finally {
            lock.unlock();
        }
    }

    /**
     * PVM-06 health hook: the warm session while the slot holds one ready for
     * reuse, {@code null} otherwise. Read-only; lets an external health probe
     * observe the instance without leasing it.
     */
    public FastLaneSession warmSessionIfReady() {
        lock.lock();
        try {
            return state == State.READY && session != null ? session : null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * PVM-06 health hook: discards the warm session when the slot holds one
     * ready for reuse, recording {@link Code#PROBE_FAILED}. No-op when the
     * slot is leased, empty or already failing; the next acquire cold-creates
     * a replacement. Reports whether a session was discarded.
     */
    public boolean evictWarm() {
        lock.lock();
        try {
            if (closed || closeRequested || state != State.READY || session == null) {
                return false;
            }
            evict(Code.PROBE_FAILED);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires at most one lease. The wait for a busy slot is bounded by
     * {@code maxWaitMs}; a zero budget makes a busy slot fail immediately.
     *
     * <p>P1-5: the cold create runs with the main lock released, so one slot's
     * VM start never blocks {@link #state()}, {@link #stats()} or
     * {@link #release(Lease)}. The slot stays in {@code CREATING} for the whole
     * create episode, which keeps the create exclusive without holding the lock.
     */
    public Lease acquire() throws PoolException {
        boolean locked;
        try {
            locked = lock.tryLock(config.maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PoolException(Code.CLOSED, "interrupted while waiting for a lease");
        }
        if (!locked) {
            throw new PoolException(Code.WAIT_TIMEOUT, "pool slot is busy");
        }
        int retriesLeft;
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.maxWaitMs);
            while (true) {
                if (closed || closeRequested) {
                    throw new PoolException(Code.CLOSED, "pool is closed");
                }
                if (config.capacity == 0) {
                    throw new PoolException(Code.DISABLED, "warm pool capacity is zero");
                }
                if ((state == State.LEASED && currentLease != null) || state == State.CREATING) {
                    awaitSlotChange(deadline,
                            state == State.CREATING ? "pool slot is cold-creating" : "pool slot is leased");
                    continue;
                }
                if (state == State.READY && session != null) {
                    ReuseOrReplace outcome = acquireReady();
                    if (outcome.lease() != null) {
                        return outcome.lease();
                    }
                    // The warm session was evicted; begin its replacement create
                    // with the retry budget of the path that evicted it.
                    beginColdCreate();
                    retriesLeft = outcome.replacementRetries();
                    break;
                }
                if (state == State.DRAINING) {
                    throw new PoolException(Code.CLOSED, "pool is draining");
                }
                beginColdCreate();
                retriesLeft = 1;
                break;
            }
        } finally {
            lock.unlock();
        }
        return createWithRetry(retriesLeft);
    }

    /** Bounded wait for the slot state to change; a lapsed budget fails into WAIT_TIMEOUT. */
    private void awaitSlotChange(long deadline, String timeoutMessage) throws PoolException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new PoolException(Code.WAIT_TIMEOUT, timeoutMessage);
        }
        try {
            leaseChanged.awaitNanos(remaining);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PoolException(Code.CLOSED, "interrupted while waiting for a lease");
        }
        if (closed) {
            throw new PoolException(Code.CLOSED, "pool is closed");
        }
    }

    /**
     * Leases the ready warm session, or evicts it and reports the HELLO retry
     * budget its replacement create gets: PROCESS_DIED and TTL_EXPIRED keep the
     * full "cold create once" retry, while a failed reuse HELLO already consumed
     * that budget and leaves the replacement single-attempt.
     */
    private ReuseOrReplace acquireReady() throws PoolException {
        if (!session.isAlive()) {
            evict(Code.PROCESS_DIED);
            return new ReuseOrReplace(null, 1);
        }
        if (expired()) {
            evict(Code.TTL_EXPIRED);
            return new ReuseOrReplace(null, 1);
        }
        if (!helloCheck(session)) {
            // The failed warm session already consumed the "cold create once"
            // retry budget; a failure here is reported, not retried again.
            evict(Code.HELLO_FAILED);
            return new ReuseOrReplace(null, 0);
        }
        reuses++;
        return new ReuseOrReplace(lease(), 0);
    }

    /** Outcome of the warm-reuse check: either a fresh lease or a replacement budget. */
    private record ReuseOrReplace(Lease lease, int replacementRetries) {}

    /**
     * Runs up to {@code retriesLeft + 1} create attempts. The slot stays
     * CREATING for the whole episode, so no second creator can start; every
     * terminal failure settles the slot back to FAILED before the exception
     * escapes, which wakes the waiting acquires.
     */
    private Lease createWithRetry(int retriesLeft) throws PoolException {
        while (true) {
            try {
                return createAttempt();
            } catch (PoolException failure) {
                if (failure.code() != Code.HELLO_FAILED || retriesLeft <= 0) {
                    settleCreateFailed();
                    throw failure;
                }
                retriesLeft--;
                // The replacement re-runs the budget and admission checks.
                try {
                    beginColdCreate();
                } catch (PoolException rejected) {
                    settleCreateFailed();
                    throw rejected;
                }
            } catch (RuntimeException unexpected) {
                // The slot must never stick in CREATING; waiters rely on the settle.
                settleCreateFailed();
                throw unexpected;
            }
        }
    }

    /** Budget and admission under the main lock; marks the slot CREATING. */
    private void beginColdCreate() throws PoolException {
        lock.lock();
        try {
            if (config.memoryMb > config.poolMemoryLimitMb) {
                state = State.FAILED;
                throw new PoolException(Code.BUDGET_REJECTED, "pool memory limit is below one VM");
            }
            if (!admission.admit(config.memoryMb)) {
                state = State.FAILED;
                throw new PoolException(Code.BUDGET_REJECTED, "lane admission rejected the create");
            }
            state = State.CREATING;
        } finally {
            lock.unlock();
        }
    }

    /** Settles a finished create episode that did not produce a lease. */
    private void settleCreateFailed() {
        lock.lock();
        try {
            state = State.FAILED;
            leaseChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * One platform create + payload readiness + control HELLO. The blocking
     * platform calls run with the main lock released; the created session stays
     * a local until {@link #publishLease(FastLaneSession)} publishes it, so no
     * other path can stop or observe an unpublished VM.
     */
    private Lease createAttempt() throws PoolException {
        String instanceId = beginCreateAttempt();
        long createStarted = clock.millis();
        FastLaneSession created;
        try {
            FastLaneLaunchSpec spec = specFactory.create(instanceId);
            FastLaneHandle handle = api.start(spec);
            created = new FastLaneSession(api, handle, spec);
        } catch (RuntimeException | FastLaneException failure) {
            throw new PoolException(Code.CREATE_FAILED, "protected VM create failed");
        }
        noteCreateMillis(createStarted);

        if (closeRequested) {
            stopQuietly(created);
            throw new PoolException(Code.CLOSED, "pool was closed during create");
        }

        long readyStarted = clock.millis();
        if (!awaitPayloadReady(created)) {
            stopQuietly(created);
            throw new PoolException(Code.READY_TIMEOUT, "payload readiness timed out");
        }
        noteReadyMillis(readyStarted);
        if (closeRequested) {
            stopQuietly(created);
            throw new PoolException(Code.CLOSED, "pool was closed while waiting for readiness");
        }

        if (!helloCheck(created)) {
            stopQuietly(created);
            recordEvictionUnderLock(Code.HELLO_FAILED);
            throw new PoolException(Code.HELLO_FAILED, "control HELLO failed");
        }
        return publishLease(created);
    }

    /** Allocates the next pool instance id and counts the cold-create attempt. */
    private String beginCreateAttempt() {
        lock.lock();
        try {
            coldCreates++;
            return config.instancePrefix + "-w" + (++instanceSequence);
        } finally {
            lock.unlock();
        }
    }

    private void noteCreateMillis(long startedAtMillis) {
        long elapsed = Math.max(0L, clock.millis() - startedAtMillis);
        lock.lock();
        try {
            createMillis += elapsed;
        } finally {
            lock.unlock();
        }
    }

    private void noteReadyMillis(long startedAtMillis) {
        long elapsed = Math.max(0L, clock.millis() - startedAtMillis);
        lock.lock();
        try {
            readyMillis += elapsed;
        } finally {
            lock.unlock();
        }
    }

    /** Counter commit from the lock-free create attempt; stats stay weakly consistent. */
    private void recordEvictionUnderLock(Code reason) {
        lock.lock();
        try {
            recordEviction(reason);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops an unpublished create attempt from the create-owner thread. The slot
     * state is left untouched (still CREATING); the caller settles it.
     */
    private void stopQuietly(FastLaneSession victim) {
        FastLaneStopResult result = stopSession(victim);
        if (result == FastLaneStopResult.UNCONFIRMED) {
            recordEvictionUnderLock(Code.STOP_UNCONFIRMED);
        }
    }

    /** Publishes the created session as the slot's lease and wakes the waiters. */
    private Lease publishLease(FastLaneSession created) {
        lock.lock();
        try {
            session = created;
            Lease published = lease();
            leaseChanged.signalAll();
            return published;
        } finally {
            lock.unlock();
        }
    }

    private Lease lease() {
        currentLease = new Lease(session);
        readyAtMillis = 0L;
        state = State.LEASED;
        return currentLease;
    }

    /**
     * Returns a lease to the pool. A second release of the same lease is a
     * no-op and reports {@code false}; it never evicts or double-stops.
     */
    public boolean release(Lease lease) {
        lock.lock();
        try {
            if (lease == null || lease != currentLease || lease.released) {
                return false;
            }
            lease.released = true;
            currentLease = null;
            if (closed || closeRequested) {
                stopSession(session);
                session = null;
                leaseChanged.signalAll();
                return true;
            }
            if (session != null && session.isAlive()) {
                state = State.READY;
                readyAtMillis = clock.millis();
            } else {
                session = null;
                state = State.IDLE;
            }
            leaseChanged.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Bounded drain. An outstanding lease may return before the deadline; if
     * it does not, the VM is stopped and the pool reports {@code TIMED_OUT}
     * instead of claiming a clean drain.
     */
    public ShutdownResult shutdown(long timeoutMs) {
        closeRequested = true;
        boolean locked;
        try {
            locked = lock.tryLock(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            // Another owner holds the pool lock; do not mutate counters without it.
            return ShutdownResult.TIMED_OUT;
        }
        try {
            closed = true;
            leaseChanged.signalAll();
            if (state == State.READY || state == State.IDLE || state == State.FAILED) {
                state = State.DRAINING;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
            while (currentLease != null || state == State.CREATING) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    leaseChanged.awaitNanos(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (state == State.CREATING) {
                // P1-5: the in-flight create owns its still-unpublished session and honors
                // closeRequested at its checkpoints, so it stops that VM itself; this path
                // never touches an unpublished session and reports the lapsed budget.
                return ShutdownResult.TIMED_OUT;
            }
            if (currentLease != null) {
                currentLease.released = true;
                currentLease = null;
                stopAndForget();
                recordEviction(Code.DRAIN_TIMEOUT);
                state = State.FAILED;
                return ShutdownResult.TIMED_OUT;
            }
            if (session == null) {
                state = State.IDLE;
                return ShutdownResult.DRAINED;
            }
            FastLaneStopResult result = stopSession(session);
            session = null;
            if (result == FastLaneStopResult.UNCONFIRMED) {
                recordEviction(Code.STOP_UNCONFIRMED);
                state = State.FAILED;
                return ShutdownResult.STOP_UNCONFIRMED;
            }
            state = State.IDLE;
            return ShutdownResult.DRAINED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        shutdown(config.drainTimeoutMs);
    }

    private boolean expired() {
        return config.idleTtlMs > 0L && readyAtMillis > 0L
                && clock.millis() - readyAtMillis >= config.idleTtlMs;
    }

    private boolean awaitPayloadReady(FastLaneSession candidate) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(config.payloadReadyTimeoutMs);
        while (true) {
            if (candidate.payloadReady()) {
                return true;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return false;
            }
            try {
                Thread.sleep(Math.max(1L, Math.min(READY_POLL_MS,
                        TimeUnit.NANOSECONDS.toMillis(remaining))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * One control HELLO/HELLO_OK, bounded by {@code helloTimeoutMs}. The
     * channel is closed afterwards; no terminal capability is inferred.
     */
    private boolean helloCheck(FastLaneSession candidate) {
        ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "protected-warm-hello");
            thread.setDaemon(true);
            return thread;
        });
        FastLaneChannel channel = null;
        Future<TerminalFrame> pending = null;
        try {
            channel = candidate.channel(candidate.spec().controlPort());
            Fln1TerminalCodec codec = new Fln1TerminalCodec(TerminalMux.Role.HOST);
            OutputStream output = channel.outputStream();
            codec.write(output, TerminalFrame.of(TerminalFrameType.HELLO, 0,
                    Fln1Payloads.encodeHello(new Fln1Payloads.Hello(1, 1, ""))));
            output.flush();
            InputStream input = channel.inputStream();
            pending = reader.submit(() -> codec.read(input));
            TerminalFrame frame = pending.get(config.helloTimeoutMs, TimeUnit.MILLISECONDS);
            if (frame == null || frame.type() != TerminalFrameType.HELLO) {
                return false;
            }
            Fln1Payloads.HelloOk helloOk = Fln1Payloads.parseHelloOk(frame.payload());
            return helloOk.version() == Fln1TerminalCodec.VERSION;
        } catch (TimeoutException | ExecutionException | IOException
                | FastLaneException | RuntimeException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
            reader.shutdownNow();
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // The session owns final teardown.
                }
            }
        }
    }

    private void evict(Code reason) {
        FastLaneSession victim = session;
        session = null;
        state = State.IDLE;
        recordEviction(reason);
        if (victim != null) {
            FastLaneStopResult result = stopSession(victim);
            if (result == FastLaneStopResult.UNCONFIRMED) {
                recordEviction(Code.STOP_UNCONFIRMED);
                state = State.FAILED;
            }
        }
    }

    private void stopAndForget() {
        FastLaneSession victim = session;
        session = null;
        if (victim != null) {
            FastLaneStopResult result = stopSession(victim);
            if (result == FastLaneStopResult.UNCONFIRMED) {
                recordEviction(Code.STOP_UNCONFIRMED);
                state = State.FAILED;
            }
        }
    }

    /** The lane's one owner stop; this pool never issues a platform stop twice. */
    private FastLaneStopResult stopSession(FastLaneSession victim) {
        if (victim == null) {
            return FastLaneStopResult.ALREADY_STOPPED;
        }
        try {
            return victim.stopRuntime(config.stopGraceMs);
        } catch (RuntimeException failure) {
            return FastLaneStopResult.UNCONFIRMED;
        }
    }

    private void recordEviction(Code reason) {
        evictions++;
        evictReasons.merge(reason, 1L, Long::sum);
    }
}

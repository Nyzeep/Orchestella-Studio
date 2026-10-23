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
        STOP_UNCONFIRMED
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
            private long memoryMb = 1024L;
            private long poolMemoryLimitMb = 1024L;
            private long idleTtlMs = 120_000L;
            private long maxWaitMs = 0L;
            private long helloTimeoutMs = 2_000L;
            private long payloadReadyTimeoutMs = 30_000L;
            private long drainTimeoutMs = 2_000L;
            private long stopGraceMs = 2_000L;
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
                if (memoryMb <= 0L || memoryMb > 16_384L) {
                    throw new IllegalArgumentException("memoryMb must be within (0, 16384]");
                }
                if (poolMemoryLimitMb < 0L) {
                    throw new IllegalArgumentException("poolMemoryLimitMb must not be negative");
                }
                if (idleTtlMs < 0L || maxWaitMs < 0L || helloTimeoutMs <= 0L
                        || payloadReadyTimeoutMs <= 0L || drainTimeoutMs < 0L
                        || stopGraceMs < 0L) {
                    throw new IllegalArgumentException("timeouts must be non-negative");
                }
                if (instancePrefix == null || !instancePrefix.matches("[A-Za-z0-9._-]{1,48}")) {
                    throw new IllegalArgumentException("instancePrefix must match [A-Za-z0-9._-]{1,48}");
                }
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
     * Acquires at most one lease. The wait for a busy slot is bounded by
     * {@code maxWaitMs}; a zero budget makes a busy slot fail immediately.
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
        try {
            if (closed || closeRequested) {
                throw new PoolException(Code.CLOSED, "pool is closed");
            }
            if (config.capacity == 0) {
                throw new PoolException(Code.DISABLED, "warm pool capacity is zero");
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.maxWaitMs);
            while (state == State.LEASED && currentLease != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new PoolException(Code.WAIT_TIMEOUT, "pool slot is leased");
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
            if (state == State.READY && session != null) {
                return acquireReady();
            }
            if (state == State.DRAINING) {
                throw new PoolException(Code.CLOSED, "pool is draining");
            }
            return coldCreateOnce();
        } finally {
            lock.unlock();
        }
    }

    private Lease acquireReady() throws PoolException {
        if (!session.isAlive()) {
            evict(Code.PROCESS_DIED);
            return coldCreateOnce();
        }
        if (expired()) {
            evict(Code.TTL_EXPIRED);
            return coldCreateOnce();
        }
        if (!helloCheck(session)) {
            evict(Code.HELLO_FAILED);
            // The failed warm session already consumed the "cold create once"
            // retry budget; a failure here is reported, not retried again.
            return coldCreate();
        }
        reuses++;
        return lease();
    }

    /** One cold create; a HELLO failure gets exactly one fresh replacement. */
    private Lease coldCreateOnce() throws PoolException {
        try {
            return coldCreate();
        } catch (PoolException first) {
            if (first.code() != Code.HELLO_FAILED) {
                throw first;
            }
            return coldCreate();
        }
    }

    private Lease coldCreate() throws PoolException {
        if (config.memoryMb > config.poolMemoryLimitMb) {
            state = State.FAILED;
            throw new PoolException(Code.BUDGET_REJECTED, "pool memory limit is below one VM");
        }
        if (!admission.admit(config.memoryMb)) {
            state = State.FAILED;
            throw new PoolException(Code.BUDGET_REJECTED, "lane admission rejected the create");
        }

        state = State.CREATING;
        coldCreates++;
        long createStarted = clock.millis();
        FastLaneSession created;
        try {
            String instanceId = config.instancePrefix + "-w" + (++instanceSequence);
            FastLaneLaunchSpec spec = specFactory.create(instanceId);
            FastLaneHandle handle = api.start(spec);
            created = new FastLaneSession(api, handle, spec);
        } catch (RuntimeException | FastLaneException failure) {
            state = State.FAILED;
            throw new PoolException(Code.CREATE_FAILED, "protected VM create failed");
        }
        createMillis += Math.max(0L, clock.millis() - createStarted);
        session = created;
        if (closeRequested) {
            stopAndForget();
            state = State.FAILED;
            throw new PoolException(Code.CLOSED, "pool was closed during create");
        }

        long readyStarted = clock.millis();
        if (!awaitPayloadReady(created)) {
            stopAndForget();
            state = State.FAILED;
            throw new PoolException(Code.READY_TIMEOUT, "payload readiness timed out");
        }
        readyMillis += Math.max(0L, clock.millis() - readyStarted);
        if (closeRequested) {
            stopAndForget();
            state = State.FAILED;
            throw new PoolException(Code.CLOSED, "pool was closed while waiting for readiness");
        }

        if (!helloCheck(created)) {
            stopAndForget();
            recordEviction(Code.HELLO_FAILED);
            state = State.FAILED;
            throw new PoolException(Code.HELLO_FAILED, "control HELLO failed");
        }
        return lease();
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
            while (currentLease != null) {
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

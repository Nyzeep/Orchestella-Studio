package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectCode;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PVM-06 implementation of {@link WorkerPool}: N protected VM slots, each one
 * a capacity-1 {@link ProtectedWarmPool}, so the PL-01 lease semantics (one
 * bounded HELLO before every reuse, evict-and-replace on a failed check,
 * exactly one replacement attempt, stop only through the session's owner stop)
 * are reused verbatim instead of reimplemented.
 *
 * <p>Dispatch model. One fair lock serialises slot choice, the bounded FIFO
 * queue and the counters. A dispatch claim happens under the lock; the inner
 * acquire (create, readiness wait, HELLO) always runs <em>outside</em> the
 * pool lock, and so does every platform stop, so no platform wait ever blocks
 * unrelated acquires. A waiter may only claim a slot while it is the head of
 * the queue, and new acquires must queue whenever the queue is non-empty, so a
 * freed slot is always served to the longest-waiting job first.
 *
 * <p>Budget. Before a cold create the pool asks the {@link WorkerPool.Admission}
 * seam with its cumulative footprint — one instance costs
 * {@code cpuPerInstance} (default 1, matching {@code ONE_CPU}) plus
 * {@code memoryMbPerInstance}. The default seam is allow-all;
 * {@link WorkerPool.Admission#ledgerBacked} wraps a {@code LaneBudgetLedger}
 * without changing it. Rejections carry the fixed {@link LaneRejectCode}. An
 * {@code UNCONFIRMED} stop keeps the instance counted (the slot is suspended,
 * not rebuilt) until an explicit {@link #resize(int)} retires it — the "do not
 * delete the lease" rule.
 *
 * <p>Rebuild discipline. A failed create or a failed health probe increments
 * the slot's consecutive-failure counter and opens an exponential backoff
 * window ({@code base · 2^(failures-1)}, capped). A dispatch landing inside
 * the window fails fast with {@link Code#REBUILD_BACKOFF} instead of
 * attempting a create. At {@code rebuildFailureCap} consecutive failures the
 * slot is suspended and further dispatches report {@link Code#REBUILD_GIVE_UP}
 * — a rebuild storm is impossible. Only a successful health probe resets the
 * counter (the probe proves the current instance works; a replacement create
 * does not, since the probe is what failed before). Only a
 * {@link #resize(int)} re-arms a suspended slot.
 *
 * <p>Stop ownership. The pool never stops a platform handle itself and holds
 * no QEMU reference at all: every stop funnels through the slot's
 * {@code ProtectedWarmPool}, which calls {@code FastLaneSession#stopRuntime} —
 * the single owner stop already used by the lane. Stopping a whole deployment
 * in QEMU-then-accelerator order remains the coordinator's job; this pool is
 * only ever on the accelerator side of that order.
 *
 * <p>Threading: the pool starts no background threads. Waits are bounded on
 * the calling thread with {@code System.nanoTime()} deadlines; the injected
 * {@link Clock} is used for the backoff windows, TTL-driven metrics and queue
 * wait times, which keeps every decision fake-clock-testable.
 *
 * <p>All counters are numbers plus fixed {@link WorkerPool.Code} values. No
 * path, token, argv, payload or job content is stored here; {@link WorkerPool.Job}
 * is matched by identity only.
 *
 * <p><b>Wiring state (PVM-06 follow-up).</b> The pool joins the design-intent
 * dispatch path via {@link WorkerPoolDistccDispatcher} (the pool-backed distcc
 * {@code ChannelFactory}) and is constructed for production through
 * {@code AcceleratorLaneAssembly#workerPool(...)} with the coordinator's
 * ledger. The shipped default capacity is still zero (decision map §8.4 — the
 * default must not change): with zero capacity the pool creates no VM, queues
 * nothing, and every acquire is a deterministic {@code DISABLED} refusal,
 * which is exactly today's behaviour. The product TCP listener that would feed
 * guest distcc connections into the dispatcher remains DV-PVM-02b work.
 */
public final class ProtectedWorkerPool implements WorkerPool {

    /** Builds a fresh launch spec; the instance id is unique per create. */
    @FunctionalInterface
    public interface WorkerSpecFactory {
        FastLaneLaunchSpec create(String instanceId, FastLaneLaunchSpec.CpuTopology topology);
    }

    /** Immutable pool configuration. Capacity defaults to zero (off). */
    public static final class Config {
        private final int capacity;
        private final int cpuPerInstance;
        private final long memoryMbPerInstance;
        private final FastLaneLaunchSpec.CpuTopology topology;
        private final boolean allowMatchHost;
        private final long queueTimeoutMs;
        private final long drainTimeoutMs;
        private final long helloTimeoutMs;
        private final long payloadReadyTimeoutMs;
        private final long idleTtlMs;
        private final long stopGraceMs;
        private final int rebuildFailureCap;
        private final long rebuildBackoffBaseMs;
        private final long rebuildBackoffMaxMs;
        private final String instancePrefix;

        private Config(Builder builder) {
            this.capacity = builder.capacity;
            this.cpuPerInstance = builder.cpuPerInstance;
            this.memoryMbPerInstance = builder.memoryMbPerInstance;
            this.topology = builder.topology;
            this.allowMatchHost = builder.allowMatchHost;
            this.queueTimeoutMs = builder.queueTimeoutMs;
            this.drainTimeoutMs = builder.drainTimeoutMs;
            this.helloTimeoutMs = builder.helloTimeoutMs;
            this.payloadReadyTimeoutMs = builder.payloadReadyTimeoutMs;
            this.idleTtlMs = builder.idleTtlMs;
            this.stopGraceMs = builder.stopGraceMs;
            this.rebuildFailureCap = builder.rebuildFailureCap;
            this.rebuildBackoffBaseMs = builder.rebuildBackoffBaseMs;
            this.rebuildBackoffMaxMs = builder.rebuildBackoffMaxMs;
            this.instancePrefix = builder.instancePrefix;
        }

        public static Config defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public int capacity() { return capacity; }
        public int cpuPerInstance() { return cpuPerInstance; }
        public long memoryMbPerInstance() { return memoryMbPerInstance; }
        public FastLaneLaunchSpec.CpuTopology topology() { return topology; }
        public boolean allowMatchHost() { return allowMatchHost; }
        public long queueTimeoutMs() { return queueTimeoutMs; }
        public long drainTimeoutMs() { return drainTimeoutMs; }
        public long helloTimeoutMs() { return helloTimeoutMs; }
        public long payloadReadyTimeoutMs() { return payloadReadyTimeoutMs; }
        public long idleTtlMs() { return idleTtlMs; }
        public long stopGraceMs() { return stopGraceMs; }
        public int rebuildFailureCap() { return rebuildFailureCap; }
        public long rebuildBackoffBaseMs() { return rebuildBackoffBaseMs; }
        public long rebuildBackoffMaxMs() { return rebuildBackoffMaxMs; }
        public String instancePrefix() { return instancePrefix; }

        public static final class Builder {
            private int capacity = 0;
            private int cpuPerInstance = 1;
            private long memoryMbPerInstance = 1024L;
            private FastLaneLaunchSpec.CpuTopology topology =
                    FastLaneLaunchSpec.CpuTopology.ONE_CPU;
            private boolean allowMatchHost = false;
            private long queueTimeoutMs = 30_000L;
            private long drainTimeoutMs = 2_000L;
            private long helloTimeoutMs = 2_000L;
            private long payloadReadyTimeoutMs = 30_000L;
            private long idleTtlMs = 120_000L;
            private long stopGraceMs = 2_000L;
            private int rebuildFailureCap = 3;
            private long rebuildBackoffBaseMs = 1_000L;
            private long rebuildBackoffMaxMs = 30_000L;
            private String instancePrefix = "pvmwork";

            public Builder capacity(int value) { this.capacity = value; return this; }
            public Builder cpuPerInstance(int value) { this.cpuPerInstance = value; return this; }
            public Builder memoryMbPerInstance(long value) { this.memoryMbPerInstance = value; return this; }
            public Builder topology(FastLaneLaunchSpec.CpuTopology value) { this.topology = value; return this; }
            public Builder allowMatchHost(boolean value) { this.allowMatchHost = value; return this; }
            public Builder queueTimeoutMs(long value) { this.queueTimeoutMs = value; return this; }
            public Builder drainTimeoutMs(long value) { this.drainTimeoutMs = value; return this; }
            public Builder helloTimeoutMs(long value) { this.helloTimeoutMs = value; return this; }
            public Builder payloadReadyTimeoutMs(long value) { this.payloadReadyTimeoutMs = value; return this; }
            public Builder idleTtlMs(long value) { this.idleTtlMs = value; return this; }
            public Builder stopGraceMs(long value) { this.stopGraceMs = value; return this; }
            public Builder rebuildFailureCap(int value) { this.rebuildFailureCap = value; return this; }
            public Builder rebuildBackoffBaseMs(long value) { this.rebuildBackoffBaseMs = value; return this; }
            public Builder rebuildBackoffMaxMs(long value) { this.rebuildBackoffMaxMs = value; return this; }
            public Builder instancePrefix(String value) { this.instancePrefix = value; return this; }

            public Config build() {
                if (capacity < 0) {
                    throw new IllegalArgumentException("capacity must not be negative");
                }
                if (cpuPerInstance < 1) {
                    throw new IllegalArgumentException("cpuPerInstance must be at least 1");
                }
                if (memoryMbPerInstance <= 0L || memoryMbPerInstance > 16_384L) {
                    throw new IllegalArgumentException("memoryMbPerInstance must be within (0, 16384]");
                }
                if (topology == FastLaneLaunchSpec.CpuTopology.MATCH_HOST && !allowMatchHost) {
                    throw new IllegalArgumentException(
                            "MATCH_HOST topology is an experimental opt-in; set allowMatchHost(true)");
                }
                if (queueTimeoutMs < 0L || drainTimeoutMs < 0L || helloTimeoutMs <= 0L
                        || payloadReadyTimeoutMs <= 0L || idleTtlMs < 0L || stopGraceMs < 0L) {
                    throw new IllegalArgumentException("timeouts must be non-negative");
                }
                if (rebuildFailureCap < 1) {
                    throw new IllegalArgumentException("rebuildFailureCap must be at least 1");
                }
                if (rebuildBackoffBaseMs < 0L || rebuildBackoffMaxMs < 0L) {
                    throw new IllegalArgumentException("backoff budgets must be non-negative");
                }
                if (instancePrefix == null || !instancePrefix.matches("[A-Za-z0-9._-]{1,48}")) {
                    throw new IllegalArgumentException("instancePrefix must match [A-Za-z0-9._-]{1,48}");
                }
                return new Config(this);
            }
        }
    }

    /** Dispatch-level bookkeeping for one protected instance slot. */
    private static final class Slot {
        final int number;
        final ProtectedWarmPool inner;
        SlotState state = SlotState.IDLE;
        boolean leaseOut;
        WorkerPool.Lease lease;
        ProtectedWarmPool.Lease innerLease;
        boolean suspended;
        boolean unconfirmedBudget;
        boolean retirePending;
        long consecutiveFailures;
        long lastFailureAtMillis;
        long seenEvictions;
        final EnumMap<WorkerPool.Code, Long> seenReasons = new EnumMap<>(WorkerPool.Code.class);

        Slot(int number, ProtectedWarmPool inner) {
            this.number = number;
            this.inner = inner;
        }
    }

    private static final class QueueEntry {
        final WorkerPool.Job job;
        final long enqueuedAtMillis;
        boolean cancelled;

        QueueEntry(WorkerPool.Job job, long enqueuedAtMillis) {
            this.job = job;
            this.enqueuedAtMillis = enqueuedAtMillis;
        }
    }

    private enum ClaimFailure {
        NONE,
        ALL_BACKOFF,
        ALL_SUSPENDED
    }

    private record Claim(Slot slot, ClaimFailure failure) {
        static Claim of(Slot slot) {
            return new Claim(slot, ClaimFailure.NONE);
        }

        static Claim failed(ClaimFailure failure) {
            return new Claim(null, failure);
        }
    }

    /** A claimed slot never waits on the inner lock; this covers reader races. */
    private static final long INNER_MAX_WAIT_MS = 100L;

    private final FastLaneVirtualMachineApi api;
    private final WorkerSpecFactory specFactory;
    private final Config config;
    private final Clock clock;
    private final WorkerPool.Admission admission;
    private final WorkerPool.HealthProbe probe;
    /** Fair: waiters wake in FIFO order, so freed slots are served in queue order. */
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition activity = lock.newCondition();
    private final ArrayDeque<QueueEntry> queue = new ArrayDeque<>();
    private final List<Slot> slots = new ArrayList<>();
    private final EnumMap<WorkerPool.Code, Long> evictReasons = new EnumMap<>(WorkerPool.Code.class);

    private int capacity;
    private int queueCapacity;
    private int slotSequence;
    private int outstandingLeases;
    private boolean closed;

    private long enqueuedTotal;
    private long dispatchedTotal;
    private long timedOutTotal;
    private long cancelledTotal;
    private long saturatedTotal;
    private long rebuildAttempts;
    private long rebuildFailures;
    private long suspensions;
    private long probeFailures;
    private long queueWaitMillisTotal;
    private long queueWaitMillisMax;

    public ProtectedWorkerPool(
            FastLaneVirtualMachineApi api, WorkerSpecFactory specFactory, Config config) {
        this(api, specFactory, config, Clock.systemUTC(),
                WorkerPool.Admission.allowAll(), WorkerPool.HealthProbe.sessionAlive());
    }

    public ProtectedWorkerPool(
            FastLaneVirtualMachineApi api,
            WorkerSpecFactory specFactory,
            Config config,
            Clock clock) {
        this(api, specFactory, config, clock,
                WorkerPool.Admission.allowAll(), WorkerPool.HealthProbe.sessionAlive());
    }

    public ProtectedWorkerPool(
            FastLaneVirtualMachineApi api,
            WorkerSpecFactory specFactory,
            Config config,
            Clock clock,
            WorkerPool.Admission admission) {
        this(api, specFactory, config, clock, admission, WorkerPool.HealthProbe.sessionAlive());
    }

    public ProtectedWorkerPool(
            FastLaneVirtualMachineApi api,
            WorkerSpecFactory specFactory,
            Config config,
            Clock clock,
            WorkerPool.Admission admission,
            WorkerPool.HealthProbe probe) {
        this.api = Objects.requireNonNull(api, "api");
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.capacity = config.capacity();
        this.queueCapacity = 2 * this.capacity;
        growToLocked(capacity);
    }

    private Slot newSlot() {
        slotSequence++;
        ProtectedWarmPool.Config inner = ProtectedWarmPool.Config.builder()
                .capacity(1)
                .memoryMb(config.memoryMbPerInstance())
                .poolMemoryLimitMb(config.memoryMbPerInstance())
                .idleTtlMs(config.idleTtlMs())
                .maxWaitMs(INNER_MAX_WAIT_MS)
                .helloTimeoutMs(config.helloTimeoutMs())
                .payloadReadyTimeoutMs(config.payloadReadyTimeoutMs())
                .drainTimeoutMs(config.drainTimeoutMs())
                .stopGraceMs(config.stopGraceMs())
                .instancePrefix(config.instancePrefix() + "w" + slotSequence)
                .build();
        ProtectedWarmPool pool = new ProtectedWarmPool(
                api,
                instanceId -> specFactory.create(instanceId, config.topology()),
                inner,
                clock,
                ProtectedWarmPool.Admission.allowAll());
        return new Slot(slotSequence, pool);
    }

    /** Appends slot shells until {@code usable} non-retiring slots exist. */
    private void growToLocked(int usable) {
        while (usableSlotsLocked() < usable) {
            slots.add(newSlot());
        }
    }

    private int usableSlotsLocked() {
        int count = 0;
        for (Slot slot : slots) {
            if (slot.state != SlotState.RETIRING) {
                count++;
            }
        }
        return count;
    }

    @Override
    public WorkerPool.Lease acquire(WorkerPool.Job job, long timeoutMs)
            throws WorkerPool.WorkerPoolException {
        Objects.requireNonNull(job, "job");
        if (timeoutMs < 0L) {
            throw new IllegalArgumentException("timeoutMs must not be negative");
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        Claim claim = Claim.failed(ClaimFailure.NONE);
        QueueEntry entry = null;
        lock.lock();
        try {
            requireOpenAndEnabled();
            if (queue.isEmpty()) {
                claim = claimSlotLocked();
            }
            if (claim.slot() == null) {
                entry = enqueueLocked(job);
            }
        } finally {
            lock.unlock();
        }
        if (claim.slot() != null) {
            return completeAcquire(claim.slot(), job);
        }

        try {
            while (true) {
                claim = Claim.failed(ClaimFailure.NONE);
                lock.lock();
                try {
                    requireOpen();
                    requireNotCancelled(entry);
                    if (queue.peekFirst() == entry) {
                        // Only the queue head may claim: FIFO fairness.
                        claim = claimSlotLocked();
                        if (claim.slot() != null) {
                            removeAndRecordDispatchLocked(entry);
                        }
                    }
                } finally {
                    lock.unlock();
                }

                if (claim.slot() != null) {
                    return completeAcquire(claim.slot(), job);
                }
                if (claim.failure() == ClaimFailure.ALL_SUSPENDED) {
                    throw new WorkerPool.WorkerPoolException(
                            WorkerPool.Code.REBUILD_GIVE_UP,
                            "every slot is suspended by rebuild failures or unconfirmed stops");
                }
                if (claim.failure() == ClaimFailure.ALL_BACKOFF) {
                    throw new WorkerPool.WorkerPoolException(
                            WorkerPool.Code.REBUILD_BACKOFF,
                            "every free slot is inside the rebuild backoff window");
                }

                lock.lock();
                try {
                    requireOpen();
                    requireNotCancelled(entry);
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0L) {
                        timedOutTotal++;
                        throw new WorkerPool.WorkerPoolException(
                                WorkerPool.Code.JOB_TIMEOUT, "job wait deadline elapsed");
                    }
                    try {
                        activity.awaitNanos(remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new WorkerPool.WorkerPoolException(
                                WorkerPool.Code.CLOSED, "interrupted while waiting for a dispatch");
                    }
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            dropEntry(entry);
        }
    }

    @Override
    public WorkerPool.Lease acquire(WorkerPool.Job job)
            throws WorkerPool.WorkerPoolException {
        return acquire(job, config.queueTimeoutMs());
    }

    private void requireOpenAndEnabled() throws WorkerPool.WorkerPoolException {
        requireOpen();
        if (capacity == 0) {
            throw new WorkerPool.WorkerPoolException(
                    WorkerPool.Code.DISABLED,
                    "worker pool capacity is zero; the pool never creates a VM");
        }
    }

    private void requireOpen() throws WorkerPool.WorkerPoolException {
        if (closed) {
            throw new WorkerPool.WorkerPoolException(WorkerPool.Code.CLOSED, "pool is closed");
        }
    }

    private static void requireNotCancelled(QueueEntry entry)
            throws WorkerPool.WorkerPoolException {
        if (entry != null && entry.cancelled) {
            throw new WorkerPool.WorkerPoolException(
                    WorkerPool.Code.CANCELLED, "queued job was cancelled");
        }
    }

    private QueueEntry enqueueLocked(WorkerPool.Job job)
            throws WorkerPool.WorkerPoolException {
        if (queue.size() >= queueCapacity) {
            saturatedTotal++;
            throw new WorkerPool.WorkerPoolException(
                    WorkerPool.Code.POOL_SATURATED, "queue is full at " + queueCapacity);
        }
        enqueuedTotal++;
        QueueEntry entry = new QueueEntry(job, clock.millis());
        queue.addLast(entry);
        return entry;
    }

    private void dropEntry(QueueEntry entry) {
        if (entry == null) {
            return;
        }
        lock.lock();
        try {
            queue.remove(entry);
        } finally {
            lock.unlock();
        }
    }

    private void removeAndRecordDispatchLocked(QueueEntry entry) {
        queue.remove(entry);
        long waited = Math.max(0L, clock.millis() - entry.enqueuedAtMillis);
        queueWaitMillisTotal += waited;
        queueWaitMillisMax = Math.max(queueWaitMillisMax, waited);
    }

    /**
     * Picks a slot under the lock. Warm slots first (no budget ask, no
     * create), then a cold slot behind an admission verdict. When nothing is
     * claimable, the failure class tells the caller whether waiting makes
     * sense or the pool can never serve this job.
     */
    private Claim claimSlotLocked() throws WorkerPool.WorkerPoolException {
        Slot warm = null;
        Slot idle = null;
        boolean anyBusy = false;
        boolean anySuspended = false;
        boolean anyBackoff = false;
        for (Slot slot : slots) {
            switch (slot.state) {
                case WARM -> {
                    if (warm == null) {
                        warm = slot;
                    }
                }
                case IDLE -> {
                    if (idle == null) {
                        if (backoffBlockedLocked(slot)) {
                            anyBackoff = true;
                        } else {
                            idle = slot;
                        }
                    }
                }
                case LEASED, RELEASING, RETIRING -> anyBusy = true;
                case SUSPENDED -> anySuspended = true;
            }
        }
        if (warm != null) {
            warm.state = SlotState.LEASED;
            return Claim.of(warm);
        }
        if (idle != null) {
            admitOrThrowLocked(activeInstancesLocked() + 1);
            idle.state = SlotState.LEASED;
            rebuildAttempts++;
            return Claim.of(idle);
        }
        if (anyBusy) {
            return Claim.failed(ClaimFailure.NONE);
        }
        if (anySuspended) {
            return Claim.failed(ClaimFailure.ALL_SUSPENDED);
        }
        return Claim.failed(anyBackoff ? ClaimFailure.ALL_BACKOFF : ClaimFailure.NONE);
    }

    private void admitOrThrowLocked(int instanceCount)
            throws WorkerPool.WorkerPoolException {
        long totalMemoryMb = config.memoryMbPerInstance() * instanceCount;
        int totalCpuCores = config.cpuPerInstance() * instanceCount;
        WorkerPool.Admission.Decision decision = admission.admit(totalMemoryMb, totalCpuCores);
        if (decision != null && decision.allowed()) {
            return;
        }
        LaneRejectCode reason = decision == null
                ? LaneRejectCode.INVALID_REQUEST
                : decision.rejectCode();
        throw new WorkerPool.WorkerPoolException(
                WorkerPool.Code.BUDGET_REJECTED,
                "budget refused a pool of " + instanceCount + " instance(s)",
                reason);
    }

    private boolean backoffBlockedLocked(Slot slot) {
        if (slot.consecutiveFailures <= 0L) {
            return false;
        }
        long backoffMs = backoffForLocked(slot.consecutiveFailures);
        return clock.millis() - slot.lastFailureAtMillis < backoffMs;
    }

    private long backoffForLocked(long failures) {
        long backoffMs = config.rebuildBackoffBaseMs();
        for (long i = 1L; i < failures && backoffMs < config.rebuildBackoffMaxMs(); i++) {
            backoffMs = Math.min(config.rebuildBackoffMaxMs(), backoffMs * 2L);
        }
        return Math.min(config.rebuildBackoffMaxMs(), backoffMs);
    }

    /**
     * Runs the inner acquire outside the pool lock, then re-syncs slot state
     * and counters. A failure is reported to this job's caller (no silent
     * retry across slots) and feeds the slot's rebuild counter.
     */
    private WorkerPool.Lease completeAcquire(Slot slot, WorkerPool.Job job)
            throws WorkerPool.WorkerPoolException {
        try {
            ProtectedWarmPool.Lease innerLease = slot.inner.acquire();
            lock.lock();
            try {
                syncSlotAfterInnerLocked(slot);
                slot.innerLease = innerLease;
                slot.leaseOut = true;
                slot.lease = new WorkerPool.Lease(this, job,
                        innerLease.session(), innerLease.session().instanceId());
                outstandingLeases++;
                dispatchedTotal++;
                activity.signalAll();
                return slot.lease;
            } finally {
                lock.unlock();
            }
        } catch (ProtectedWarmPool.PoolException failure) {
            lock.lock();
            try {
                syncSlotAfterInnerLocked(slot);
                recordSlotFailureLocked(slot);
            } finally {
                lock.unlock();
            }
            throw new WorkerPool.WorkerPoolException(
                    mapCode(failure.code()), "worker instance dispatch failed");
        }
    }

    private static WorkerPool.Code mapCode(ProtectedWarmPool.Code code) {
        return switch (code) {
            case DISABLED -> WorkerPool.Code.DISABLED;
            case CREATE_FAILED -> WorkerPool.Code.CREATE_FAILED;
            case READY_TIMEOUT -> WorkerPool.Code.READY_TIMEOUT;
            case HELLO_FAILED -> WorkerPool.Code.HELLO_FAILED;
            case BUDGET_REJECTED -> WorkerPool.Code.BUDGET_REJECTED;
            case WAIT_TIMEOUT -> WorkerPool.Code.WAIT_TIMEOUT;
            case CLOSED -> WorkerPool.Code.CLOSED;
            case TTL_EXPIRED -> WorkerPool.Code.TTL_EXPIRED;
            case PROCESS_DIED -> WorkerPool.Code.PROCESS_DIED;
            case DRAIN_TIMEOUT -> WorkerPool.Code.DRAIN_TIMEOUT;
            case STOP_UNCONFIRMED -> WorkerPool.Code.STOP_UNCONFIRMED;
            case PROBE_FAILED -> WorkerPool.Code.PROBE_FAILED;
        };
    }

    private void recordSlotFailureLocked(Slot slot) {
        rebuildFailures++;
        slot.consecutiveFailures++;
        slot.lastFailureAtMillis = clock.millis();
        if (slot.suspended) {
            activity.signalAll();
            return;
        }
        if (slot.consecutiveFailures >= config.rebuildFailureCap()) {
            suspendLocked(slot);
        } else {
            slot.state = SlotState.IDLE;
        }
        activity.signalAll();
    }

    private void suspendLocked(Slot slot) {
        slot.suspended = true;
        slot.state = SlotState.SUSPENDED;
        suspensions++;
        activity.signalAll();
    }

    /**
     * Diffs the slot's inner eviction counters into the pool counters and
     * re-derives the dispatch state from the inner state. An UNCONFIRMED stop
     * suspends the slot and keeps the instance counted against the budget.
     */
    private void syncSlotAfterInnerLocked(Slot slot) {
        ProtectedWarmPool.Stats inner = slot.inner.stats();
        long evictionDelta = inner.evictions() - slot.seenEvictions;
        if (evictionDelta > 0L) {
            for (Map.Entry<ProtectedWarmPool.Code, Long> reason
                    : inner.evictReasons().entrySet()) {
                WorkerPool.Code code = mapCode(reason.getKey());
                long delta = reason.getValue() - slot.seenReasons.getOrDefault(code, 0L);
                if (delta > 0L) {
                    evictReasons.merge(code, delta, Long::sum);
                    if (reason.getKey() == ProtectedWarmPool.Code.STOP_UNCONFIRMED) {
                        slot.unconfirmedBudget = true;
                        suspendLocked(slot);
                    }
                }
            }
        }
        slot.seenEvictions = inner.evictions();
        slot.seenReasons.clear();
        for (Map.Entry<ProtectedWarmPool.Code, Long> reason
                : inner.evictReasons().entrySet()) {
            slot.seenReasons.put(mapCode(reason.getKey()), reason.getValue());
        }
        if (slot.suspended) {
            slot.state = SlotState.SUSPENDED;
            return;
        }
        switch (slot.inner.state()) {
            case CREATING, LEASED -> slot.state = SlotState.LEASED;
            case READY -> slot.state = SlotState.WARM;
            default -> slot.state = SlotState.IDLE;
        }
    }

    @Override
    public boolean cancel(WorkerPool.Job job) {
        Objects.requireNonNull(job, "job");
        lock.lock();
        try {
            for (QueueEntry entry : queue) {
                if (entry.job == job) {
                    entry.cancelled = true;
                    cancelledTotal++;
                    activity.signalAll();
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean release(WorkerPool.Lease lease) {
        if (lease == null || lease.owner() != this || lease.isReleased()) {
            return false;
        }
        Slot slot;
        lock.lock();
        try {
            slot = null;
            for (Slot candidate : slots) {
                if (candidate.lease == lease) {
                    slot = candidate;
                    break;
                }
            }
            if (slot == null) {
                return false;
            }
            lease.markReleased();
            slot.leaseOut = false;
            slot.lease = null;
            outstandingLeases--;
            slot.state = SlotState.RELEASING;
        } finally {
            lock.unlock();
        }

        // Outside the pool lock: warm returns are quick, but a closing pool
        // stops the session here, and no platform wait belongs under the
        // dispatch lock.
        ProtectedWarmPool.Lease innerLease = slot.innerLease;
        slot.innerLease = null;
        slot.inner.release(innerLease);

        boolean retire;
        lock.lock();
        try {
            syncSlotAfterInnerLocked(slot);
            activity.signalAll();
            retire = slot.retirePending;
        } finally {
            lock.unlock();
        }
        if (retire) {
            WorkerPool.ShutdownResult result = shutdownSlot(slot, config.stopGraceMs());
            lock.lock();
            try {
                slots.remove(slot);
                if (result == WorkerPool.ShutdownResult.STOP_UNCONFIRMED) {
                    slot.unconfirmedBudget = true;
                }
                activity.signalAll();
            } finally {
                lock.unlock();
            }
        }
        return true;
    }

    @Override
    public WorkerPool.HealthReport health() {
        List<Slot> warmSlots = new ArrayList<>();
        lock.lock();
        try {
            syncAllSlotsLocked();
            for (Slot slot : slots) {
                if (slot.state == SlotState.WARM && slot.inner.warmSessionIfReady() != null) {
                    warmSlots.add(slot);
                }
            }
        } finally {
            lock.unlock();
        }

        // Probing runs outside the lock: a probe may open a connection.
        Map<Slot, Boolean> verdicts = new IdentityHashMap<>();
        for (Slot slot : warmSlots) {
            FastLaneSession session = slot.inner.warmSessionIfReady();
            boolean verdict = false;
            if (session != null) {
                try {
                    verdict = probe.healthy(session);
                } catch (RuntimeException | LinkageError failure) {
                    verdict = false;
                }
            }
            verdicts.put(slot, verdict);
        }

        List<Slot> unhealthy = new ArrayList<>();
        lock.lock();
        try {
            for (Slot slot : warmSlots) {
                if (!verdicts.containsKey(slot)) {
                    continue;
                }
                if (Boolean.TRUE.equals(verdicts.get(slot))) {
                    slot.consecutiveFailures = 0L;
                } else {
                    unhealthy.add(slot);
                }
            }
        } finally {
            lock.unlock();
        }
        for (Slot slot : unhealthy) {
            // Outside the lock: the eviction stops the session (owner stop).
            boolean evicted = slot.inner.evictWarm();
            lock.lock();
            try {
                if (evicted) {
                    probeFailures++;
                    syncSlotAfterInnerLocked(slot);
                    slot.consecutiveFailures++;
                    slot.lastFailureAtMillis = clock.millis();
                    if (!slot.suspended && slot.consecutiveFailures >= config.rebuildFailureCap()) {
                        suspendLocked(slot);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        lock.lock();
        try {
            syncAllSlotsLocked();
            List<WorkerPool.SlotHealth> report = new ArrayList<>();
            for (Slot slot : slots) {
                report.add(new WorkerPool.SlotHealth(
                        slot.number, slot.state, verdicts.get(slot)));
            }
            return new WorkerPool.HealthReport(
                    capacity,
                    activeInstancesLocked(),
                    countLocked(SlotState.LEASED),
                    countLocked(SlotState.WARM),
                    countLocked(SlotState.SUSPENDED),
                    queue.size(),
                    report);
        } finally {
            lock.unlock();
        }
    }

    private void syncAllSlotsLocked() {
        for (Slot slot : slots) {
            syncSlotAfterInnerLocked(slot);
        }
    }

    private int countLocked(SlotState state) {
        int count = 0;
        for (Slot slot : slots) {
            if (slot.state == state) {
                count++;
            }
        }
        return count;
    }

    /**
     * Instances the pool currently counts against the budget: every slot
     * whose inner pool still holds a session, plus one for every slot whose
     * stop was never confirmed (that VM may still exist, so its cost stays
     * held even after a replacement was created). Overcounting is safe; an
     * undercount could start a second VM on top of an unconfirmed one.
     */
    private int activeInstancesLocked() {
        int count = 0;
        for (Slot slot : slots) {
            ProtectedWarmPool.State innerState = slot.inner.state();
            boolean hasSession = innerState == ProtectedWarmPool.State.CREATING
                    || innerState == ProtectedWarmPool.State.READY
                    || innerState == ProtectedWarmPool.State.LEASED;
            if (hasSession) {
                count++;
            }
            if (slot.unconfirmedBudget) {
                count++;
            }
        }
        return count;
    }

    @Override
    public WorkerPool.ResizeResult resize(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        List<Slot> idleRetired = new ArrayList<>();
        WorkerPool.ResizeResult result;
        lock.lock();
        try {
            if (closed) {
                return new WorkerPool.ResizeResult(
                        this.capacity, false, LaneRejectCode.INVALID_REQUEST);
            }
            if (capacity == this.capacity) {
                return new WorkerPool.ResizeResult(this.capacity, true, null);
            }
            if (capacity > 0) {
                WorkerPool.Admission.Decision decision = admission.admit(
                        config.memoryMbPerInstance() * capacity,
                        config.cpuPerInstance() * capacity);
                if (decision == null || !decision.allowed()) {
                    LaneRejectCode reason = decision == null
                            ? LaneRejectCode.INVALID_REQUEST
                            : decision.rejectCode();
                    return new WorkerPool.ResizeResult(this.capacity, false, reason);
                }
            }

            if (capacity > this.capacity) {
                growToLocked(capacity);
            } else {
                // Retire idle slots first; only pull the remainder (never a
                // running job's slot while it runs) once their lease returns.
                int toRetire = usableSlotsLocked() - capacity;
                List<Slot> ordered = new ArrayList<>();
                for (Slot slot : slots) {
                    if (slot.state != SlotState.RETIRING && !slot.leaseOut) {
                        ordered.add(slot);
                    }
                }
                for (Slot slot : slots) {
                    if (slot.state != SlotState.RETIRING && slot.leaseOut) {
                        ordered.add(slot);
                    }
                }
                for (int i = 0; i < toRetire && i < ordered.size(); i++) {
                    Slot slot = ordered.get(i);
                    slot.state = SlotState.RETIRING;
                    if (slot.leaseOut) {
                        slot.retirePending = true;
                    } else {
                        idleRetired.add(slot);
                    }
                }
            }
            this.capacity = capacity;
            queueCapacity = 2 * capacity;
            activity.signalAll();
            result = new WorkerPool.ResizeResult(capacity, true, null);
        } finally {
            lock.unlock();
        }
        shutdownRetiredIdleSlots(idleRetired);
        return result;
    }

    /** Bounded stop of retired idle slots, outside the dispatch lock. */
    private void shutdownRetiredIdleSlots(List<Slot> retired) {
        for (Slot slot : retired) {
            shutdownSlot(slot, config.stopGraceMs());
            lock.lock();
            try {
                slots.remove(slot);
                activity.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private WorkerPool.ShutdownResult shutdownSlot(Slot slot, long timeoutMs) {
        long unconfirmedBefore = unconfirmedStopsOf(slot);
        ProtectedWarmPool.ShutdownResult result = slot.inner.shutdown(Math.max(0L, timeoutMs));
        lock.lock();
        try {
            syncSlotAfterInnerLocked(slot);
        } finally {
            lock.unlock();
        }
        // A forced stop reports TIMED_OUT even when the platform stop itself
        // was unconfirmed; the eviction reason carries the truth.
        if (unconfirmedStopsOf(slot) > unconfirmedBefore) {
            return WorkerPool.ShutdownResult.STOP_UNCONFIRMED;
        }
        return switch (result) {
            case DRAINED -> WorkerPool.ShutdownResult.DRAINED;
            case TIMED_OUT -> WorkerPool.ShutdownResult.TIMED_OUT;
            case STOP_UNCONFIRMED -> WorkerPool.ShutdownResult.STOP_UNCONFIRMED;
        };
    }

    private long unconfirmedStopsOf(Slot slot) {
        Long count = slot.inner.stats().evictReasons().get(ProtectedWarmPool.Code.STOP_UNCONFIRMED);
        return count == null ? 0L : count;
    }

    @Override
    public WorkerPool.Stats stats() {
        lock.lock();
        try {
            syncAllSlotsLocked();
            long coldCreates = 0L;
            long reuses = 0L;
            for (Slot slot : slots) {
                ProtectedWarmPool.Stats inner = slot.inner.stats();
                coldCreates += inner.coldCreates();
                reuses += inner.reuses();
            }
            return new WorkerPool.Stats(
                    capacity,
                    queueCapacity,
                    activeInstancesLocked(),
                    countLocked(SlotState.LEASED),
                    queue.size(),
                    coldCreates,
                    reuses,
                    enqueuedTotal,
                    dispatchedTotal,
                    timedOutTotal,
                    cancelledTotal,
                    saturatedTotal,
                    rebuildAttempts,
                    rebuildFailures,
                    suspensions,
                    probeFailures,
                    queueWaitMillisTotal,
                    queueWaitMillisMax,
                    evictReasons);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public WorkerPool.ShutdownResult shutdown(long timeoutMs) {
        List<Slot> stopping;
        boolean leasesOut;
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                for (QueueEntry entry : queue) {
                    entry.cancelled = true;
                    cancelledTotal++;
                }
                queue.clear();
            }
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
            while (outstandingLeases > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    activity.awaitNanos(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            leasesOut = outstandingLeases > 0;
            stopping = new ArrayList<>(slots);
            for (Slot slot : stopping) {
                slot.retirePending = false;
                slot.state = SlotState.RETIRING;
            }
            slots.clear();
            outstandingLeases = 0;
            activity.signalAll();
        } finally {
            lock.unlock();
        }

        // One owner stop per instance, outside the dispatch lock.
        WorkerPool.ShutdownResult worst = WorkerPool.ShutdownResult.DRAINED;
        long remainingMs = Math.max(0L, timeoutMs);
        for (Slot slot : stopping) {
            long budget = leasesOut ? 0L : remainingMs;
            WorkerPool.ShutdownResult result = shutdownSlot(slot, budget);
            if (result == WorkerPool.ShutdownResult.STOP_UNCONFIRMED) {
                worst = WorkerPool.ShutdownResult.STOP_UNCONFIRMED;
            } else if (result == WorkerPool.ShutdownResult.TIMED_OUT
                    && worst == WorkerPool.ShutdownResult.DRAINED) {
                worst = WorkerPool.ShutdownResult.TIMED_OUT;
            }
        }
        return worst;
    }

    @Override
    public void close() {
        shutdown(config.drainTimeoutMs());
    }
}

package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudgetLedger;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectCode;
import java.util.List;
import java.util.Objects;

/**
 * PVM-06: the scheduling surface of the compiler worker pool — acquire a
 * protected VM for one compile job, hand it back, observe health, and resize
 * the pool. The implementation is {@link ProtectedWorkerPool}, which composes
 * one {@link ProtectedWarmPool} slot per instance and therefore keeps the
 * PL-01 lease semantics: every reuse is preceded by one bounded FLN1
 * {@code HELLO/HELLO_OK}, a failed check discards the VM, and every stop goes
 * through {@code FastLaneSession#stopRuntime(long)} — the single owner stop.
 * This pool never touches {@code RuntimeProcessSupervisor} and never becomes
 * a second stop owner for any lane.
 *
 * <p>Default capacity is zero: {@code acquire} answers with the fixed code
 * {@link Code#DISABLED}, no VM is ever created, nothing is queued, and the
 * process is byte-for-byte today's zero-side-effect behaviour (decision map
 * §8.4: the shipped default stays 0; raising it is an explicit operator or
 * wiring decision).
 *
 * <p>Scheduling is FIFO fair. When jobs are waiting, a new acquire always
 * queues behind them instead of stealing a freed slot; the dispatch lock is
 * fair, so the longest-queued job is served first. The queue is bounded at
 * {@code 2 × capacity}; an overflow is rejected with {@link Code#POOL_SATURATED}
 * and a job that waits past its deadline fails with {@link Code#JOB_TIMEOUT}.
 * A queued job can be cancelled by identity with {@link #cancel(Job)}.
 *
 * <p>Budget: before an instance is created the pool asks the injected
 * {@link Admission} seam with the pool's cumulative footprint (existing
 * instances plus one). The seam reuses {@link LaneBudgetLedger} semantics
 * without changing them — one instance costs {@code cpuPerInstance} vCPUs
 * plus {@code memoryMbPerInstance}. A refusal is reported as
 * {@link Code#BUDGET_REJECTED} carrying the {@link LaneRejectCode} reason, and
 * no VM is started. An {@code UNCONFIRMED} stop keeps the instance counted
 * against the budget, matching the "do not delete the lease" rule.
 *
 * <p>Topology: the launch topology defaults to {@code ONE_CPU} and
 * {@code MATCH_HOST} is an experimental opt-in that stays off by default
 * (the platform supports it, but the memory cost scales with host cores).
 *
 * <p>Metrics are counters and fixed {@link Code} values only: pool size,
 * occupancy, queue depth, queue wait time, rebuild attempts. No path, token,
 * argv, payload or job content is ever stored here; {@link Job} is an opaque
 * identity used for FIFO ordering and cancellation matching only.
 *
 * <p><b>Wiring state (PVM-06 follow-up).</b> The pool is on the design-intent
 * dispatch path through {@link WorkerPoolDistccDispatcher}, the pool-backed
 * {@code DistccTcpVsockBridge.ChannelFactory}: one distcc connection = one
 * lease, constructed for production via {@code AcceleratorLaneAssembly#
 * workerPool(...)}. What does not exist yet is the product TCP listener that
 * would feed guest distcc connections into that dispatcher — starting it on a
 * device is DV-PVM-02b work. With the shipped capacity of zero every acquire
 * still refuses deterministically, so no product behaviour changes until a
 * caller raises the capacity.
 */
public interface WorkerPool extends AutoCloseable {

    /** Fixed failure, rejection and eviction codes. Never free-form text. */
    enum Code {
        /** Capacity is zero; the pool refuses without any side effect. */
        DISABLED,
        /** The bounded queue is full; the job was never enqueued. */
        POOL_SATURATED,
        /** The job waited in the queue past its deadline. */
        JOB_TIMEOUT,
        /** The queued job was cancelled by {@link #cancel(Job)}. */
        CANCELLED,
        /** The budget seam refused the create; see the rejection reason. */
        BUDGET_REJECTED,
        /** The platform start failed. */
        CREATE_FAILED,
        /** The payload did not report readiness in time. */
        READY_TIMEOUT,
        /** The control-plane HELLO check failed. */
        HELLO_FAILED,
        /** The inner pool's bounded lease wait expired; never expected. */
        WAIT_TIMEOUT,
        /** A rebuild was suppressed by the failure backoff window. */
        REBUILD_BACKOFF,
        /** A slot exhausted its rebuild budget and is suspended. */
        REBUILD_GIVE_UP,
        /** The pool is closed or shutting down. */
        CLOSED,
        /** A warm instance was discarded past its idle TTL. */
        TTL_EXPIRED,
        /** A warm instance's platform process died. */
        PROCESS_DIED,
        /** The injected health probe reported the warm instance unhealthy. */
        PROBE_FAILED,
        /** A shutdown ran out of time with a lease still outstanding. */
        DRAIN_TIMEOUT,
        /** The platform could not confirm a stop; the budget stays held. */
        STOP_UNCONFIRMED
    }

    /**
     * Opaque job identity. The pool never inspects it: it only uses reference
     * identity for FIFO ordering, {@link #cancel(Job)} matching, and echoing
     * the job back on the lease. Metrics never contain job content.
     */
    interface Job {
    }

    /**
     * Typed, secret-free pool failure. {@link #reason()} is non-null only for
     * {@link Code#BUDGET_REJECTED}.
     */
    final class WorkerPoolException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Code code;
        private final LaneRejectCode reason;

        public WorkerPoolException(Code code, String message) {
            this(code, message, null);
        }

        public WorkerPoolException(Code code, String message, LaneRejectCode reason) {
            super(Objects.requireNonNull(message, "message"));
            this.code = Objects.requireNonNull(code, "code");
            this.reason = reason;
        }

        public Code code() {
            return code;
        }

        /** Fixed budget rejection reason; null for every other code. */
        public LaneRejectCode reason() {
            return reason;
        }
    }

    /** One checked-out worker instance. Release via {@link #release(Lease)}. */
    final class Lease {
        private final WorkerPool owner;
        private final Job job;
        private final FastLaneSession session;
        private final String instanceId;
        private boolean released;

        Lease(WorkerPool owner, Job job, FastLaneSession session, String instanceId) {
            this.owner = owner;
            this.job = job;
            this.session = session;
            this.instanceId = instanceId;
        }

        void markReleased() {
            released = true;
        }

        /** The job this lease was dispatched for; identity, never inspected. */
        public Job job() {
            return job;
        }

        /**
         * The session of the dispatched instance. The dispatcher opens its own
         * distcc channel on {@code session.spec().distccPort()} via
         * {@link FastLaneSession#openChannel(int)}.
         */
        public FastLaneSession session() {
            return session;
        }

        public String instanceId() {
            return instanceId;
        }

        public boolean isReleased() {
            return released;
        }

        WorkerPool owner() {
            return owner;
        }
    }

    /** Per-slot health line; {@code alive} is null when never probed. */
    record SlotHealth(int slot, SlotState state, Boolean alive) {
    }

    /** Dispatch-level state of one slot. */
    enum SlotState {
        /** Free, no warm instance; the next dispatch cold-creates. */
        IDLE,
        /** Free with a warm, reuse-ready instance. */
        WARM,
        /** A lease is outstanding or being established. */
        LEASED,
        /** A release is completing; excluded from dispatch for one step. */
        RELEASING,
        /** Rebuild budget exhausted or stop unconfirmed; excluded until resize. */
        SUSPENDED,
        /** Removed by a shrink; stops as soon as its lease returns. */
        RETIRING
    }

    /** Point-in-time health snapshot; counters only, no job content. */
    record HealthReport(
            int capacity,
            int activeInstances,
            int leasedSlots,
            int warmSlots,
            int suspendedSlots,
            int queueDepth,
            List<SlotHealth> slots) {
        public HealthReport {
            slots = List.copyOf(slots);
        }
    }

    /** Result of {@link #resize(int)}; a refusal carries the budget reason. */
    record ResizeResult(int appliedCapacity, boolean accepted, LaneRejectCode rejectReason) {
    }

    /** Counters only; the map keys are {@link Code} constants. */
    record Stats(
            int capacity,
            int queueCapacity,
            int activeInstances,
            int leasedSlots,
            int queueDepth,
            long coldCreates,
            long reuses,
            long enqueuedTotal,
            long dispatchedTotal,
            long timedOutTotal,
            long cancelledTotal,
            long saturatedTotal,
            long rebuildAttempts,
            long rebuildFailures,
            long suspensions,
            long probeFailures,
            long queueWaitMillisTotal,
            long queueWaitMillisMax,
            java.util.Map<Code, Long> evictReasons) {
        public Stats {
            java.util.EnumMap<Code, Long> copy = new java.util.EnumMap<>(Code.class);
            copy.putAll(evictReasons);
            evictReasons = java.util.Collections.unmodifiableMap(copy);
        }
    }

    /** Result of {@link #shutdown(long)}. */
    enum ShutdownResult {
        DRAINED,
        TIMED_OUT,
        STOP_UNCONFIRMED
    }

    /**
     * Cross-lane admission seam. The pool asks before every create with its
     * <em>cumulative</em> footprint (existing instances plus the candidate):
     * {@code totalMemoryMb} and {@code totalCpuCores}. Implementations must be
     * quick and non-blocking; the real one wraps {@link LaneBudgetLedger}.
     */
    @FunctionalInterface
    interface Admission {
        /**
         * One admission verdict; a refusal names the fixed
         * {@link LaneRejectCode} reason.
         */
        Decision admit(long totalMemoryMb, int totalCpuCores);

        /** One admission decision; {@code rejectCode} is null when allowed. */
        record Decision(boolean allowed, LaneRejectCode rejectCode) {
            public static Decision ok() {
                return new Decision(true, null);
            }

            public static Decision rejected(LaneRejectCode rejectCode) {
                return new Decision(false, Objects.requireNonNull(rejectCode, "rejectCode"));
            }
        }

        static Admission allowAll() {
            return (totalMemoryMb, totalCpuCores) -> Decision.ok();
        }

        /**
         * Ledger-backed admission without changing {@link LaneBudgetLedger}:
         * accelerator availability, then vCPU headroom, then RAM headroom,
         * mirroring {@code reserve()} ordering and {@code canAdmitPool()}
         * semantics. The pool never reserves in the ledger — it owns its own
         * leases — so other lanes' usage is read through the ledger views.
         */
        static Admission ledgerBacked(LaneBudgetLedger ledger) {
            Objects.requireNonNull(ledger, "ledger");
            return (totalMemoryMb, totalCpuCores) -> {
                if (!ledger.budget().acceleratorAvailable()) {
                    return Decision.rejected(LaneRejectCode.ACCELERATOR_UNAVAILABLE);
                }
                if (totalMemoryMb <= 0L || totalCpuCores <= 0) {
                    return Decision.rejected(LaneRejectCode.INVALID_REQUEST);
                }
                LaneBudgetLedger.Snapshot used = ledger.snapshot();
                if (used.cpuCores() + totalCpuCores > ledger.budget().cpuCores()) {
                    return Decision.rejected(LaneRejectCode.CPU_EXCEEDED);
                }
                if (used.ramMiB() + totalMemoryMb > ledger.budget().ramMiB()) {
                    return Decision.rejected(LaneRejectCode.RAM_EXCEEDED);
                }
                return Decision.ok();
            };
        }
    }

    /**
     * Health probe seam over a warm instance: a payload heartbeat or a
     * connection check. Injected so fake VMs can exercise it; the default only
     * asks the platform whether the session is alive. A probe never stops an
     * instance — an unhealthy warm instance is evicted and rebuilt lazily with
     * backoff by the pool.
     */
    @FunctionalInterface
    interface HealthProbe {
        boolean healthy(FastLaneSession session);

        static HealthProbe sessionAlive() {
            return FastLaneSession::isAlive;
        }
    }

    /**
     * Acquires one instance for {@code job}, waiting at most {@code timeoutMs}
     * in the bounded FIFO queue. A full queue fails fast with
     * {@link Code#POOL_SATURATED}; an expired wait fails with
     * {@link Code#JOB_TIMEOUT}; {@code timeoutMs == 0} means "serve only if a
     * slot is free right now".
     */
    Lease acquire(Job job, long timeoutMs) throws WorkerPoolException;

    /**
     * Acquires one instance for {@code job} with the configured default queue
     * timeout. With the shipped zero capacity this is a deterministic
     * {@link Code#DISABLED} refusal.
     */
    Lease acquire(Job job) throws WorkerPoolException;

    /**
     * Cancels a queued job by identity. Returns {@code false} when the job was
     * never queued, already dispatched, or already cancelled; the caller then
     * owns the normal acquire outcome.
     */
    boolean cancel(Job job);

    /**
     * Returns a lease to the pool. A second release of the same lease is a
     * no-op and reports {@code false}; it never evicts or double-stops.
     */
    boolean release(Lease lease);

    /**
     * Probes every warm (idle, reuse-ready) instance with the injected
     * {@link HealthProbe} and reports the pool shape. An unhealthy instance is
     * taken offline (evicted, {@link Code#PROBE_FAILED}) and rebuilt lazily on
     * the next dispatch, subject to the rebuild backoff and cap. Leased slots
     * are never probed.
     */
    HealthReport health();

    /**
     * Resizes the pool. Growth asks the {@link Admission} seam for the full
     * target footprint first and is refused with the budget reason on failure;
     * shrink stops retired idle instances (bounded) and retires leased ones
     * as soon as their lease returns. A resize never stops a healthy leased
     * instance out from under a job.
     */
    ResizeResult resize(int capacity);

    /** Counters only; no job content. */
    Stats stats();

    /**
     * Bounded shutdown: queued jobs are cancelled, outstanding leases get the
     * deadline to return, then every instance is stopped through the single
     * owner stop. An unconfirmed stop is reported, never retried forever.
     */
    ShutdownResult shutdown(long timeoutMs);

    /** Shorthand for {@link #shutdown(long)} with the configured drain budget. */
    @Override
    void close();
}

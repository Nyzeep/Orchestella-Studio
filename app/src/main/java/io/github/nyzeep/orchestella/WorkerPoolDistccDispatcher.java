package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.PvmCompilerManifest;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.Axp1DistccChannelFactory;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccTcpVsockBridge;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PVM-06 wiring: the dispatcher that puts the compile worker pool on the
 * design-intent call path. {@code DistccTcpVsockBridge} opens exactly one
 * {@link FastLaneChannel} per accepted distcc connection through a
 * {@link DistccTcpVsockBridge.ChannelFactory}; this class is that factory,
 * backed by a {@link WorkerPool} — one distcc connection leases one protected
 * VM, so N instances serve N concurrent compile jobs and the PL-01 lease
 * semantics (bounded FLN1 HELLO before reuse, evict-and-rebuild, single owner
 * stop) apply to every job.
 *
 * <p>Per lease the production opener runs the AXP1 HOST handshake on the
 * <em>leased session's</em> distcc port ({@code session.openChannel(
 * spec.distccPort())}) via {@link Axp1DistccChannelFactory}; the token and
 * compiler identity are supplied by the caller per run (the uplink plan and
 * the payload {@link PvmCompilerManifest}), never stored here.
 *
 * <p>Lifecycle: the lease is returned to the pool when the bridge closes the
 * channel — the bridge closes the channel exactly once per connection in its
 * terminal transition, so the lease can never outlive the connection. A pool
 * refusal or an opener failure never leaks a lease. Closing a lease's output
 * stream is the AXP1 half-close, a normal protocol shape, and does not release
 * anything.
 *
 * <p>Failure codes: every pool refusal maps to the fixed reason
 * {@code WORKER_POOL_<CODE>} (for example {@code WORKER_POOL_DISABLED} or
 * {@code WORKER_POOL_POOL_SATURATED}). The bridge publishes the reason in its
 * connection stats, so a saturated or disabled pool is observable without any
 * new surface. With the shipped capacity of zero the dispatcher refuses
 * deterministically before any platform call — byte-for-byte today's
 * behaviour; raising the capacity stays an explicit config decision
 * (decision map §8.4).
 *
 * <p>Queueing: acquire waits in the pool's bounded FIFO queue up to the pool's
 * configured default (or the explicitly given timeout). Keep that timeout
 * shorter than the bridge's idle timeout; if the bridge's idle watchdog fires
 * first it closes the channel, which releases the lease — safe either way,
 * only less polite.
 *
 * <p>Metrics and failures carry counters and fixed codes only; the opaque
 * {@link WorkerPool.Job} identity never carries or logs job content.
 *
 * <p>Still outside this class (recorded gap, not an omission): the product
 * TCP listener that accepts the guest's distcc connections and hands them to
 * this dispatcher. Starting that listener on a device is DV-PVM-02b's
 * cross-lane work; until it exists, the dispatcher is the consumption-ready
 * seam the future listener hands its connections to.
 */
public final class WorkerPoolDistccDispatcher
        implements DistccTcpVsockBridge.ChannelFactory {

    /** Fixed Fast Lane reason prefix; the pool's {@link WorkerPool.Code} follows. */
    public static final String REASON_PREFIX = "WORKER_POOL_";

    /** Opens the compile channel for one leased instance. */
    @FunctionalInterface
    public interface LeaseChannelOpener {
        FastLaneChannel open(WorkerPool.Lease lease) throws FastLaneException, IOException;
    }

    /**
     * Production opener: the AXP1 distcc handshake on the leased session's
     * own distcc port. One handshake per connection, exactly as the direct
     * (non-pooled) wiring does it — only the session is now the leased one.
     */
    public static LeaseChannelOpener axp1OverLease(
            String token,
            Axp1Capabilities hostCapabilities,
            String requiredCompilerTriple,
            Axp1Limits limits) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(hostCapabilities, "hostCapabilities");
        Objects.requireNonNull(requiredCompilerTriple, "requiredCompilerTriple");
        Objects.requireNonNull(limits, "limits");
        return lease -> new Axp1DistccChannelFactory(
                () -> lease.session().openChannel(lease.session().spec().distccPort()),
                token,
                hostCapabilities,
                requiredCompilerTriple,
                limits).open();
    }

    private final WorkerPool pool;
    private final LeaseChannelOpener opener;
    private final Long acquireTimeoutMs;
    private final AtomicLong jobSequence = new AtomicLong();

    /**
     * Uses the pool's configured default queue timeout for every acquire.
     *
     * @param pool the compile worker pool; capacity decides everything (zero
     *        means this dispatcher refuses deterministically)
     * @param opener the per-lease channel opener; production passes
     *        {@link #axp1OverLease(String, Axp1Capabilities, String, Axp1Limits)}
     */
    public WorkerPoolDistccDispatcher(WorkerPool pool, LeaseChannelOpener opener) {
        this(pool, opener, null);
    }

    /**
     * @param acquireTimeoutMs explicit queue-wait bound in milliseconds;
     *        {@code null} means the pool's configured default
     */
    public WorkerPoolDistccDispatcher(WorkerPool pool, LeaseChannelOpener opener,
            Long acquireTimeoutMs) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.opener = Objects.requireNonNull(opener, "opener");
        if (acquireTimeoutMs != null && acquireTimeoutMs < 0L) {
            throw new IllegalArgumentException("acquireTimeoutMs must not be negative");
        }
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    /**
     * One distcc connection in, one leased instance out. Throws a
     * {@link FastLaneException} with a fixed {@code WORKER_POOL_*} reason when
     * the pool refuses; the bridge maps that to its
     * {@code CHANNEL_FAILED} outcome with the reason preserved.
     */
    @Override
    public FastLaneChannel open() throws FastLaneException, IOException {
        WorkerPool.Job job = new DispatchJob(jobSequence.incrementAndGet());
        WorkerPool.Lease lease;
        try {
            lease = acquireTimeoutMs == null
                    ? pool.acquire(job)
                    : pool.acquire(job, acquireTimeoutMs);
        } catch (WorkerPool.WorkerPoolException failure) {
            throw new FastLaneException(reasonCode(failure.code()),
                    "worker pool refused the dispatch: " + failure.code());
        }
        FastLaneChannel channel;
        try {
            channel = opener.open(lease);
        } catch (FastLaneException | IOException | RuntimeException failure) {
            // The slot is not the failure's judge; release returns it to the
            // pool, whose own HELLO/TTL/probe checks evict a dead instance.
            pool.release(lease);
            throw failure;
        }
        return new LeasedChannel(channel, lease, pool);
    }

    /** Mechanical, total mapping; every code stays inside the bridge's fixed-code shape. */
    static String reasonCode(WorkerPool.Code code) {
        return REASON_PREFIX + code.name();
    }

    /** Opaque pool identity; a sequence number only, never logged. */
    private static final class DispatchJob implements WorkerPool.Job {
        private final long number;

        DispatchJob(long number) {
            this.number = number;
        }

        @Override
        public String toString() {
            return "dispatch-" + number;
        }
    }

    /**
     * The bridge's channel view of one lease: every delegate call passes
     * through unchanged; only {@link #close()} adds the lease return, exactly
     * once, after the delegate closed.
     */
    private static final class LeasedChannel implements FastLaneChannel {
        private final FastLaneChannel delegate;
        private final WorkerPool.Lease lease;
        private final WorkerPool pool;
        private final AtomicBoolean released = new AtomicBoolean();

        LeasedChannel(FastLaneChannel delegate, WorkerPool.Lease lease, WorkerPool pool) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.lease = Objects.requireNonNull(lease, "lease");
            this.pool = Objects.requireNonNull(pool, "pool");
        }

        @Override
        public java.io.InputStream inputStream() {
            return delegate.inputStream();
        }

        @Override
        public java.io.OutputStream outputStream() {
            return delegate.outputStream();
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                releaseOnce();
            }
        }

        private void releaseOnce() {
            if (released.compareAndSet(false, true)) {
                pool.release(lease);
            }
        }
    }
}

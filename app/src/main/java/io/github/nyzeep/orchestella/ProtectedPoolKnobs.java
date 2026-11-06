package io.github.nyzeep.orchestella;

/**
 * Shared capacity knobs and sentinel bounds for the protected pools
 * ({@link ProtectedWarmPool} and its per-slot use by {@link ProtectedWorkerPool}).
 *
 * <p>Both pools expose their own immutable {@code Config} types, but the per-VM memory
 * ceiling, the instance-id charset and the timeout sentinels were previously defined and
 * validated twice, once per pool, and could drift apart. This class is the single source of
 * that shared knob vocabulary:
 *
 * <ul>
 *   <li>per-VM memory is bounded to {@code (0, 16384] MiB} in both pools;</li>
 *   <li>instance prefixes share one charset/length bound because they become prefixes of
 *       guest-visible instance ids;</li>
 *   <li>idle TTL, HELLO, payload readiness, drain and stop-grace budgets share one
 *       non-negative (HELLO/readiness: strictly positive) sentinel rule.</li>
 * </ul>
 *
 * <p>The acquire/release state machines are intentionally <em>not</em> unified here:
 * {@link ProtectedWarmPool} owns the single-slot session lifecycle (create, HELLO reuse
 * check, evict-and-replace), while {@link ProtectedWorkerPool} composes N capacity-1 warm
 * pools behind a fair dispatch queue. Unifying them would couple two different locking
 * disciplines for no shared behavior; only the knob vocabulary is genuinely duplicated.
 *
 * <p>Package-private on purpose: this is an internal deduplication seam, not API.
 */
final class ProtectedPoolKnobs {
    /** Shared per-VM memory default and inclusive upper bound (MiB). */
    static final long DEFAULT_MEMORY_MB = 1024L;
    static final long MAX_MEMORY_MB = 16_384L;
    static final long DEFAULT_IDLE_TTL_MS = 120_000L;
    static final long DEFAULT_HELLO_TIMEOUT_MS = 2_000L;
    static final long DEFAULT_PAYLOAD_READY_TIMEOUT_MS = 30_000L;
    static final long DEFAULT_DRAIN_TIMEOUT_MS = 2_000L;
    static final long DEFAULT_STOP_GRACE_MS = 2_000L;

    private ProtectedPoolKnobs() {}

    /**
     * Validates one pool's per-VM memory knob. {@code label} keeps each pool's historical
     * exception message unchanged ("memoryMb" / "memoryMbPerInstance").
     */
    static long memoryMb(long memoryMb, String label) {
        if (memoryMb <= 0L || memoryMb > MAX_MEMORY_MB) {
            throw new IllegalArgumentException(
                    label + " must be within (0, " + MAX_MEMORY_MB + "]");
        }
        return memoryMb;
    }

    /** Validates the instance-id prefix knob; the message is shared by both pools. */
    static String instancePrefix(String prefix) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9._-]{1,48}")) {
            throw new IllegalArgumentException("instancePrefix must match [A-Za-z0-9._-]{1,48}");
        }
        return prefix;
    }

    /**
     * Validates the timeout sentinels shared by both pools: idle TTL, drain and stop grace
     * must be non-negative, HELLO and payload readiness must be strictly positive. Pool-local
     * knobs (warm max-wait, worker queue timeout) stay validated at their own build site with
     * the same historical message.
     */
    static void commonTimeouts(
            long idleTtlMs,
            long helloTimeoutMs,
            long payloadReadyTimeoutMs,
            long drainTimeoutMs,
            long stopGraceMs) {
        if (idleTtlMs < 0L || helloTimeoutMs <= 0L || payloadReadyTimeoutMs <= 0L
                || drainTimeoutMs < 0L || stopGraceMs < 0L) {
            throw new IllegalArgumentException("timeouts must be non-negative");
        }
    }
}

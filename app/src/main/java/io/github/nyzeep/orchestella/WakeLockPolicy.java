package io.github.nyzeep.orchestella;

/**
 * Pure decision logic for the foreground service {@code PARTIAL_WAKE_LOCK} lifecycle.
 *
 * <p>Extracted from {@link OrchestellaForegroundService} so the renewal rules are unit
 * testable without an Android runtime. The caller passes monotonic timestamps
 * (elapsed-realtime domain) and applies the decision to the real lock.
 */
public final class WakeLockPolicy {
    /** Sentinel for "the lock is not held"; timestamps are non-negative. */
    public static final long NOT_HELD = -1L;

    /** What the caller should do with the platform wake lock. */
    public enum Decision {
        /** Not held, or the previous grant already expired: take a fresh grant. */
        ACQUIRE,
        /** Held but close to expiry: release and re-take to extend the deadline. */
        RENEW,
        /** Held with enough time left: leave it alone (no duplicate acquire). */
        NOOP
    }

    private WakeLockPolicy() {
    }

    /**
     * @param nowMs monotonic now
     * @param heldSinceMs when the current grant started, or {@link #NOT_HELD}
     * @param timeoutMs per-grant timeout granted to the platform
     * @param renewThresholdMs remaining time below which the grant is renewed
     */
    public static Decision decide(long nowMs, long heldSinceMs, long timeoutMs, long renewThresholdMs) {
        if (heldSinceMs < 0L) {
            return Decision.ACQUIRE;
        }
        long remainingMs = timeoutMs - (nowMs - heldSinceMs);
        if (remainingMs <= 0L) {
            return Decision.ACQUIRE;
        }
        if (remainingMs < renewThresholdMs) {
            return Decision.RENEW;
        }
        return Decision.NOOP;
    }
}
